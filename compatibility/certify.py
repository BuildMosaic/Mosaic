#!/usr/bin/env python3
"""Build once; run existing compiler and installation fixtures against exact Kotlin versions."""

import argparse
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
import zipfile

import registry

ROOT = Path(__file__).resolve().parent.parent
REPORTS = ROOT / 'build/reports/compatibility'
RUNTIME = ('mosaic-core', 'mosaic-test', 'mosaic-opentelemetry')


def compiler_profiles(jar):
    with zipfile.ZipFile(jar) as archive:
        text = archive.read('org/buildmosaic/analysis/compiler-profiles.properties').decode()
    entries = dict(line.split('=', 1) for line in text.splitlines() if line and not line.startswith('#'))
    default = entries.pop('default')
    bounds = {api: tuple(registry.version(v) for v in values.split(',')) for api, values in entries.items()}
    if default not in bounds or any(len(v) != 2 or v[0] >= v[1] for v in bounds.values()):
        raise ValueError('Invalid compiler ABI intervals')
    for api in bounds:
        registry.version(api)
    return default, bounds


def compiler_api(version, bounds):
    matches = [api for api, (lower, upper) in bounds.items() if lower <= registry.version(version) < upper]
    if len(matches) != 1:
        raise ValueError(f'No compatible introspector for Kotlin {version}')
    return matches[0]


def junit_results(directory):
    cases = [c for p in directory.glob('TEST-*.xml') for c in ET.parse(p).getroot().iter('testcase')]
    if not cases or any(c.find(tag) is not None for c in cases for tag in ('failure', 'error', 'skipped')):
        raise RuntimeError(f'Required tests failed, skipped or absent: {directory}')
    return len(cases)


def summaries(directory):
    result = {}
    for path in directory.glob('*.json'):
        value = json.loads(path.read_text())
        value.pop('payloadHash')
        value['producer'].pop('compilerVersion')
        result[path.name] = value
    return result


class Certification:
    def __init__(self, java_installations='', train='all', release=None, runtime=None, analysis=None, repository=None):
        self.directory = REPORTS / str(time.time_ns())
        self.directory.mkdir(parents=True)
        self.repository = Path(repository) if repository else self.directory / 'repository'
        self.java_installations = java_installations
        self.revision = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip()
        candidate = f'0.0.0-compat-{self.directory.name}'
        self.runtime_version = runtime or (release if train == 'runtime' and release else candidate)
        self.analysis_version = analysis or (release if train == 'analysis' and release else candidate)
        self.baseline = re.search(r'^kotlin = "([^"]+)"', (ROOT / 'gradle/libs.versions.toml').read_text(), re.M)[1]
        self.control = None
        self.checked_runtime = set()
        self.artifacts = {}

    def run(self, name, command):
        log = self.directory / f'{name}.log'
        print(f'{name}: {log}', flush=True)
        try:
            with log.open('a') as output:
                subprocess.run(list(map(str, command)), cwd=ROOT, stdout=output, stderr=subprocess.STDOUT,
                               check=True, timeout=1800)
        except subprocess.SubprocessError:
            print(''.join(log.read_text(errors='replace').splitlines(keepends=True)[-200:]), file=sys.stderr, flush=True)
            raise

    def gradle(self, name, *args):
        self.run(name, [ROOT / 'gradlew', *args, '--no-configuration-cache', '--console=plain', '--max-workers=2',
                        f'-Porg.gradle.java.installations.paths={self.java_installations}'])

    def build(self, scope):
        modules = list(RUNTIME)
        tasks = []
        if scope != 'runtime':
            modules += ['mosaic-compiler-plugin', 'mosaic-gradle-plugin']
            tasks = [':mosaic-compiler-plugin:testClasses', ':mosaic-analysis-core:testClasses']
        self.gradle('build', *[f':{m}:publishAllPublicationsToInstallTestRepository' for m in modules], *tasks,
                    f'-Pmosaic.runtime.version={self.runtime_version}', f'-Pmosaic.analysis.version={self.analysis_version}',
                    f'-Pmosaic.installTestRepository={self.repository}')

    def prepare_tests(self):
        self.gradle('fixtures', ':mosaic-compiler-plugin:testClasses', ':mosaic-analysis-core:testClasses',
                    ':mosaic-gradle-plugin:testClasses', '--no-configure-on-demand')

    def resolve(self, compiler, scope, runtime=None, baseline=None, extractor='mosaic-compiler-plugin'):
        directory = self.directory / compiler
        self.gradle(f'resolve-{compiler}', '-p', ROOT / 'compatibility', 'resolveEvidence',
                    f'-Pcompat.kotlin={compiler}', f'-Pcompat.baseline={baseline or self.baseline}',
                    f'-Pcompat.extractor={extractor}',
                    f'-Pcompat.candidate={self.analysis_version}', f'-Pcompat.runtimeCandidate={runtime or self.runtime_version}',
                    f'-Pcompat.repository={self.repository}', f'-Pcompat.evidence={directory}', f'-Pcompat.scope={scope}')
        resolution = json.loads((directory / 'resolution.json').read_text())
        self.java = resolution['java17']
        if not resolution['java17Version'].startswith('17.'):
            raise RuntimeError('Compatibility consumers must run on Java 17')
        artifacts = {name: entry['artifacts'] for name, entry in resolution['configurations'].items()}
        for values in artifacts.values():
            for artifact in values:
                if artifact['group'] == 'org.buildmosaic':
                    self.artifacts[artifact['coordinate']] = artifact['sha256']
        print(f'Resolved Mosaic artifacts for Kotlin {compiler}: {json.dumps(self.artifacts, sort_keys=True)}', flush=True)
        return artifacts

    @staticmethod
    def classpath(artifacts):
        return os.pathsep.join(a['file'] for a in artifacts)

    def compiler(self, artifacts, version):
        selected = [a for a in artifacts if a['module'] == 'kotlin-compiler-embeddable']
        if len(selected) != 1 or selected[0]['version'] != version:
            raise RuntimeError(f'Compiler resolution disagrees with {version}')
        command = [self.java, '-Xmx768m', '-cp', self.classpath(artifacts), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler']
        self.run(f'compiler-{version}', command + ['-version'])
        reported = re.search(r'kotlinc-jvm\s+(\S+)', (self.directory / f'compiler-{version}.log').read_text())
        if not reported or reported[1] != version:
            raise RuntimeError(f'Executing compiler disagrees with {version}')
        return command

    def runtime(self, version, artifacts):
        sources = [ROOT / 'compatibility/fixtures/RuntimeConsumer.kt'] + [
            ROOT / 'mosaic-compiler-plugin/src/test/resources/fixtures' / name for name in
            ('observer-composite.kt', 'public-api-keys.kt', 'public-api-roots.kt', 'execution-catalog.kt')]
        classes = self.directory / version / 'runtime-classes'
        self.run(f'runtime-compile-{version}', self.compiler(artifacts['compiler'], version) + [
            '-no-stdlib', '-no-reflect', '-jvm-target', '17', '-classpath', self.classpath(artifacts['consumer']),
            '-d', str(classes), *map(str, sources)])
        self.run(f'runtime-execute-{version}', [self.java, '-cp', str(classes) + os.pathsep +
                 self.classpath(artifacts['consumer']), 'certification.RuntimeConsumerKt'])

    def junit(self, name, module, artifacts, pattern, properties=None):
        runner = artifacts['runner']
        console = next(a['file'] for a in runner if a['module'] == 'junit-platform-console-standalone')
        tests = ROOT / module / 'build/classes/kotlin/test'
        classpath = os.pathsep.join([str(tests), str(ROOT / module / 'build/resources/test'),
                                    self.classpath(runner + artifacts['consumer'] + artifacts['extractor'])])
        xml = self.directory / name / 'junit'
        self.run(name, [self.java, '-Xmx768m', *[f'-D{k}={v}' for k, v in (properties or {}).items()],
                       '-jar', console, 'execute', '--disable-ansi-colors', '--fail-if-no-tests',
                       '--class-path', classpath, f'--scan-class-path={tests}', f'--include-classname={pattern}',
                       '--exclude-classname=.*ObserverCompositionConsumerTest', f'--reports-dir={xml}'])
        junit_results(xml)

    def extract(self, label, compiler, version, jar, artifacts):
        self.compiler(compiler, version)
        shutil.rmtree(self.directory / label, ignore_errors=True)
        output = self.directory / label / 'summaries'
        self.junit(label, 'mosaic-compiler-plugin', artifacts, '.*Test', {
            'mosaic.plugin.jar': jar, 'mosaic.fixture.classpath': self.classpath(artifacts['consumer']),
            'mosaic.compat.compiler.classpath': self.classpath(compiler), 'mosaic.compat.java': self.java,
            'mosaic.compat.evidence': output,
        })
        for path in output.glob('*.json'):
            if json.loads(path.read_text())['producer']['compilerVersion'] != version:
                raise RuntimeError(f'Incorrect compiler provenance: {path}')
        result = summaries(output)
        if not result:
            raise RuntimeError(f'No compiler summaries: {output}')
        return result

    def check(self, versions, scope):
        for version in versions:
            artifacts = self.resolve(version, scope)
            if version not in self.checked_runtime:
                self.runtime(version, artifacts)
                self.checked_runtime.add(version)
            if scope != 'runtime':
                default_jar = artifacts['extractor'][0]['file']
                default, bounds = compiler_profiles(default_jar)
                api = compiler_api(version, bounds)
                artifact = 'mosaic-compiler-plugin' + ('' if api == default else f'-kotlin-{api}')
                artifacts = self.resolve(version, scope, baseline=default, extractor=artifact)
                if self.control is None:
                    self.junit('metadata', 'mosaic-analysis-core', artifacts, '.*Summary.*Test')
                    self.control = self.extract('control', artifacts['controlCompiler'], default, default_jar, artifacts)
                selected = self.extract(f'selected-{version}', artifacts['compiler'], version, artifacts['extractor'][0]['file'], artifacts)
                if selected != self.control:
                    raise RuntimeError(f'Compiler summaries differ for {version}; compare control and selected summaries')
        if scope != 'runtime':
            self.install(versions, self.runtime_version, self.analysis_version)

    def install(self, versions, runtime, analysis):
        self.gradle(f'install-{runtime}-{analysis}', ':mosaic-gradle-plugin:releaseCompatibilityTest',
                    f'-Pmosaic.test.kotlinVersions={",".join(versions)}',
                    f'-Pmosaic.test.repository={self.repository}', f'-Pmosaic.test.runtimeVersion={runtime}',
                    f'-Pmosaic.test.analysisVersion={analysis}',
                    f'-Pmosaic.runtime.version={self.runtime_version}', f'-Pmosaic.analysis.version={self.analysis_version}')
        junit_results(ROOT / 'mosaic-gradle-plugin/build/test-results/releaseCompatibilityTest')


def certify_published(version, runtime, analysis=None, java_installations='', repository=None, prepare=True):
    registry.version(version)
    registry.version(runtime)
    if analysis:
        registry.version(analysis)
    check = Certification(java_installations, runtime=runtime, analysis=analysis, repository=repository)
    if analysis and prepare:
        check.prepare_tests()
    check.check([version], 'all' if analysis else 'runtime')
    result = {'result': 'pass', 'runtime': runtime, 'analysis': analysis, 'kotlin': [version],
              'revision': check.revision, 'artifacts': check.artifacts}
    (check.directory / 'result.json').write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps(result), flush=True)
    return result


def certify(versions, scope, java_installations='', release=None, counterparts=None):
    registry.versions(versions)
    data = registry.load()
    opposite = 'analysis' if scope == 'runtime' else 'runtime'
    if release:
        registry.version(release.split('-')[0])
        if not re.fullmatch(r'[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.-]+)?', release):
            raise ValueError('Invalid release version')
        # A release records only relationships it actually exercises. Extra counterparts are opt-in.
        counterparts = sorted(set(counterparts), key=registry.version) if counterparts is not None else sorted(data[opposite], key=registry.version)[-1:]
        if scope == 'analysis' and not counterparts:
            raise ValueError('Release a Runtime first; Analysis certification requires a published Runtime')
        if any(v not in data[opposite] for v in counterparts):
            raise ValueError('Counterparts must be recorded published releases')
    certification = Certification(java_installations, scope, release)
    certification.build('all' if release and counterparts else scope)
    certification.check(versions, scope)
    relationships = {}
    for counterpart in counterparts or []:
        if scope == 'analysis':
            # An unchanged published Runtime can gain Kotlin support without a new Runtime release.
            common = versions
            for compiler in common:
                certification.runtime(compiler, certification.resolve(compiler, 'runtime', counterpart))
        else:
            published = Certification(java_installations, runtime=release, analysis=counterpart,
                                      repository=certification.repository)
            resolved = published.resolve(versions[0], 'all')
            _, bounds = compiler_profiles(resolved['extractor'][0]['file'])
            common = [v for v in versions if any(lower <= registry.version(v) < upper for lower, upper in bounds.values())]
            if common:
                published.check(common, 'all')
        if not common:
            raise ValueError(f'No common Kotlin versions with {opposite} {counterpart}')
        if scope == 'analysis':
            certification.install(common, counterpart, release)
        relationships[counterpart] = common
    result = {'train': scope, 'version': release, 'kotlin': versions, 'counterparts': relationships,
              'revision': certification.revision, 'result': 'pass'}
    path = certification.directory / 'result.json'
    path.write_text(json.dumps(result, indent=2) + '\n')
    if release:
        (REPORTS / f'release-{scope}.json').write_text(path.read_text())
    print(f'Compatibility passed: {path}', flush=True)
    return result


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--kotlin', help='Comma-separated exact stable compiler versions; default: all discovered versions')
    parser.add_argument('--scope', choices=('all', 'runtime', 'analysis'), default='all')
    parser.add_argument('--java-installations', default='')
    parser.add_argument('--release', help='Certify this actual release version before publication')
    parser.add_argument('--counterparts', help='Comma-separated published counterparts; default: latest recorded release')
    parser.add_argument('--runtime', help='Verify this unchanged published Runtime; requires --kotlin')
    parser.add_argument('--analysis', help='Also verify this unchanged published Analysis with --runtime')
    parser.add_argument('--repository', type=Path, help='Additional Maven repository for local artifact verification')
    args = parser.parse_args()
    from maintain import central_versions
    versions = sorted(set(args.kotlin.split(',') if args.kotlin else central_versions()), key=registry.version)
    if args.counterparts and not args.release:
        parser.error('--counterparts requires --release')
    if args.release:
        if args.scope == 'all' or args.kotlin:
            parser.error('Releases require one train and the complete compiler matrix')
    if (args.analysis or args.repository) and not args.runtime:
        parser.error('--analysis and --repository require --runtime')
    if args.runtime and (not args.kotlin or args.release or args.counterparts or args.scope != 'all'):
        parser.error('Published verification requires --kotlin and --runtime, with optional --analysis')
    try:
        if args.runtime:
            for version in versions:
                certify_published(version, args.runtime, args.analysis, args.java_installations, args.repository)
        else:
            certify(versions, args.scope, args.java_installations, args.release,
                    args.counterparts.split(',') if args.counterparts else None)
    except Exception as failure:
        print(f'Compatibility failed: {failure}', file=sys.stderr)
        sys.exit(1)

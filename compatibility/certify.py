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
import urllib.request
import xml.etree.ElementTree as ET
import zipfile

import registry

TOOLING = Path(__file__).resolve().parent
ROOT = Path(os.environ.get('MOSAIC_SOURCE_ROOT', TOOLING.parent))
REPORTS = ROOT / 'build/reports/compatibility'
RUNTIME = ('mosaic-core', 'mosaic-test', 'mosaic-opentelemetry')


def central_versions():
    url = 'https://repo.maven.apache.org/maven2/org/jetbrains/kotlin/kotlin-compiler-embeddable/maven-metadata.xml'
    with urllib.request.urlopen(url, timeout=60) as response:
        nodes = ET.fromstring(response.read()).findall('./versioning/versions/version')
    result = set()
    for node in nodes:
        try:
            registry.version(node.text)
            result.add(node.text)
        except ValueError:
            pass
    return sorted(result, key=registry.version)


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


class Certification:
    def __init__(self, java_installations):
        self.directory = REPORTS / str(time.time_ns())
        self.directory.mkdir(parents=True)
        self.repository = self.directory / 'repository'
        self.java_installations = java_installations
        self.revision = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip()
        self.project_versions = []
        self.fixtures = ROOT
        self.fixtures_ready = False
        self.compiler_changes = False
        self.baseline = re.search(r'^kotlin = "([^"]+)"', (ROOT / 'gradle/libs.versions.toml').read_text(), re.M)[1]

    def run(self, name, command):
        log = self.directory / f'{name}.log'
        started = time.monotonic()
        print(f'Start {name}: {log}', flush=True)
        try:
            with log.open('a') as output:
                subprocess.run(list(map(str, command)), cwd=ROOT, stdout=output, stderr=subprocess.STDOUT,
                               check=True, timeout=1800)
        except subprocess.SubprocessError as failure:
            print(''.join(log.read_text(errors='replace').splitlines(keepends=True)[-200:]), file=sys.stderr, flush=True)
            raise RuntimeError(f'Failed {name}: {" ".join(map(str, command))}; log: {log}') from failure
        print(f'Pass {name} ({time.monotonic() - started:.1f}s)', flush=True)

    def gradle(self, name, *args):
        self.run(name, [ROOT / 'gradlew', *args, *self.project_versions, '--no-configuration-cache', '--console=plain', '--max-workers=2',
                        f'-Porg.gradle.java.installations.paths={self.java_installations}'])

    def build(self, scope, version):
        self.project_versions = [f'-Pmosaic.{train}.version={version}'
                                 for train in ('runtime', 'analysis') if scope in ('all', train)]
        modules = list(RUNTIME) if scope != 'analysis' else []
        if scope != 'runtime':
            modules += ['mosaic-compiler-plugin', 'mosaic-gradle-plugin']
        self.gradle('build', *[f':{m}:publishAllPublicationsToInstallTestRepository' for m in modules],
                    '--no-configure-on-demand', f'-Pmosaic.installTestRepository={self.repository}')

    def resolve(self, compiler, runtime, analysis=None, baseline=None, extractor='mosaic-compiler-plugin'):
        self.gradle(f'resolve-{runtime}-{analysis}-{compiler}', '-p', TOOLING, 'resolveEvidence',
                    f'-Pcompat.kotlin={compiler}', f'-Pcompat.baseline={baseline or self.baseline}',
                    f'-Pcompat.extractor={extractor}', f'-Pcompat.runtime={runtime}',
                    f'-Pcompat.analysis={analysis or ""}', f'-Pcompat.workspace={self.directory}',
                    f'-Pcompat.repository={self.repository}')
        resolution = json.loads((self.directory / runtime / (analysis or 'runtime') / compiler / 'resolution.json').read_text())
        self.java = resolution['java17']
        self.gradle_home = Path(resolution['gradleHome'])
        if not resolution['java17Version'].startswith('17.'):
            raise RuntimeError('Compatibility consumers must run on Java 17')
        artifacts = {name: entry['artifacts'] for name, entry in resolution['configurations'].items()}
        hashes = {a['coordinate']: a['sha256'] for values in artifacts.values() for a in values
                  if a['group'] == 'org.buildmosaic'}
        print(f'Resolved Mosaic artifacts for Kotlin {compiler}: {json.dumps(hashes, sort_keys=True)}', flush=True)
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

    def runtime(self, versions, runtime):
        sources = [ROOT / 'compatibility/fixtures/RuntimeConsumer.kt'] + [
            ROOT / 'mosaic-compiler-plugin/src/test/resources/fixtures' / name for name in
            ('observer-composite.kt', 'public-api-keys.kt', 'public-api-roots.kt', 'execution-catalog.kt')]
        for version in versions:
            artifacts = self.resolve(version, runtime)
            name = f'runtime-{runtime}-{version}'
            classes = self.directory / name / 'classes'
            self.run(f'{name}-compile', self.compiler(artifacts['compiler'], version) + [
                '-no-stdlib', '-no-reflect', '-jvm-target', '17', '-classpath', self.classpath(artifacts['consumer']),
                '-d', str(classes), *map(str, sources)])
            self.run(f'{name}-execute', [self.java, '-cp', str(classes) + os.pathsep +
                     self.classpath(artifacts['consumer']), 'certification.RuntimeConsumerKt'])

    def junit(self, name, module, artifacts, pattern, properties=None, extra_classpath=(), tag=None):
        runner = artifacts['runner']
        console = next(a['file'] for a in runner if a['module'] == 'junit-platform-console-standalone')
        tests = self.fixtures / module / 'build/classes/kotlin/test'
        classpath = os.pathsep.join([str(tests), str(self.fixtures / module / 'build/resources/test'),
                                    self.classpath(runner + artifacts['consumer'] + artifacts['extractor']),
                                    *map(str, extra_classpath)])
        xml = self.directory / name / 'junit'
        self.run(name, [self.java, '-Xmx768m', *[f'-D{k}={v}' for k, v in (properties or {}).items()],
                       '-cp', str(console) + os.pathsep + classpath, 'org.junit.platform.console.ConsoleLauncher',
                       'execute', '--disable-ansi-colors', '--fail-if-no-tests',
                       f'--scan-class-path={tests}', f'--include-classname={pattern}',
                       *(['--include-tag', tag] if tag else []), '--exclude-classname=.*ObserverCompositionConsumerTest', f'--reports-dir={xml}'])
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
        result = {}
        for path in output.glob('*.json'):
            value = json.loads(path.read_text())
            if value['producer'].pop('compilerVersion') != version:
                raise RuntimeError(f'Incorrect compiler provenance: {path}')
            value.pop('payloadHash')
            result[path.name] = value
        if not result:
            raise RuntimeError(f'No compiler summaries: {output}')
        return result

    def prepare_fixtures(self):
        if not self.fixtures_ready:
            self.gradle('fixtures', ':mosaic-compiler-plugin:testClasses', ':mosaic-analysis-core:testClasses',
                        ':mosaic-gradle-plugin:testClasses', '--no-configure-on-demand')
            self.fixtures_ready = True

    def analysis_version(self, version, runtime, analysis, metadata=False):
        artifacts = self.resolve(version, runtime, analysis)
        default, bounds = compiler_profiles(artifacts['extractor'][0]['file'])
        api = compiler_api(version, bounds)
        artifact = 'mosaic-compiler-plugin' + ('' if api == default else f'-kotlin-{api}')
        artifacts = self.resolve(version, runtime, analysis, baseline=default, extractor=artifact)
        if metadata:
            self.junit(f'{runtime}-{analysis}-metadata', 'mosaic-analysis-core', artifacts, '.*Summary.*Test')
        return self.extract(f'{runtime}-{analysis}-{version}', artifacts['compiler'], version,
                            artifacts['extractor'][0]['file'], artifacts), artifacts

    def installation(self, versions, runtime, analysis, artifacts, changes=False, changes_only=False):
        # Use the wrapper's real TestKit distribution, without rebuilding the prepared test classes.
        gradle_jars = list((self.gradle_home / 'lib').glob('*.jar')) + list((self.gradle_home / 'lib/plugins').glob('*.jar'))
        self.junit(f'install-{runtime}-{analysis}', 'mosaic-gradle-plugin', artifacts,
                   '.*', {
                       'user.dir': ROOT / 'mosaic-gradle-plugin',
                       'mosaic.test.javaInstallations': self.java_installations,
                       'mosaic.test.kotlinVersions': ','.join(versions),
                       'mosaic.test.compilerChanges': str(changes).lower(),
                       'mosaic.test.compilerChangesOnly': str(changes_only).lower(),
                       'mosaic.test.repository': self.repository,
                       'mosaic.test.runtimeVersion': runtime,
                       'mosaic.test.analysisVersion': analysis,
                   }, gradle_jars, tag='release-compatibility')

    def analysis(self, versions, runtime, analysis):
        self.prepare_fixtures()
        control = None
        for version in versions:
            selected, artifacts = self.analysis_version(version, runtime, analysis, metadata=control is None)
            if control is None:
                control = selected
            elif selected != control:
                raise RuntimeError(f'Compiler summaries differ for {version}; compare {versions[0]} and {version} summaries')
        self.installation(versions, runtime, analysis, artifacts, changes=self.compiler_changes)
        if '2.4.20' in versions and len(versions) > 1:
            self.compiler_changes = False


def certify(versions, scope, java_installations='', release=None, **requested):
    if release:
        from release import certify_release
        return certify_release(scope, release, java_installations, **requested)
    registry.versions(versions)
    certification = Certification(java_installations)
    candidate = f'0.0.0-compat-{certification.directory.name}'
    certification.build(scope, candidate)
    certification.compiler_changes = True
    certification.runtime(versions, candidate)
    if scope == 'all':
        certification.analysis(versions, candidate, candidate)
    print(f'Compatibility passed: {certification.directory}', flush=True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--kotlin', help='Comma-separated exact stable compiler versions')
    parser.add_argument('--scope', choices=('all', 'runtime', 'analysis'), default='all')
    parser.add_argument('--java-installations', default='')
    parser.add_argument('--release', help='Certify this actual release version before publication')
    for name in ('minKotlin', 'maxKotlin', 'minRuntime', 'maxRuntime'):
        parser.add_argument('--' + name, default='')
    parser.add_argument('--runtime', help='Verify this unchanged published Runtime; requires --kotlin')
    parser.add_argument('--analysis', help='Verify this unchanged published Analysis; requires --runtime')
    args = parser.parse_args()
    if args.release and (args.scope == 'all' or args.kotlin or args.runtime):
        parser.error('Releases require one train and an intended range')
    if args.scope == 'runtime' and (args.minRuntime or args.maxRuntime):
        parser.error('Runtime releases accept only Kotlin bounds')
    if args.scope == 'analysis' and not args.release:
        parser.error('--scope analysis requires --release')
    if args.analysis and not args.runtime:
        parser.error('--analysis requires --runtime')
    if args.runtime and (not args.kotlin or args.scope != 'all'):
        parser.error('Published verification requires --kotlin and --runtime, with optional --analysis')
    try:
        versions = [] if args.release else sorted(set(args.kotlin.split(',') if args.kotlin else central_versions()), key=registry.version)
        if args.runtime:
            registry.version(args.runtime)
            check = Certification(args.java_installations)
            if args.analysis:
                registry.version(args.analysis)
                check.analysis(versions, args.runtime, args.analysis)
            else:
                check.runtime(versions, args.runtime)
            print(f'Published compatibility passed: {check.directory}', flush=True)
        else:
            certify(versions, args.scope, args.java_installations, args.release,
                    **{key: getattr(args, key) for key in ('minKotlin', 'maxKotlin', 'minRuntime', 'maxRuntime')})
    except Exception as failure:
        print(f'Compatibility failed: {failure}', file=sys.stderr)
        sys.exit(1)

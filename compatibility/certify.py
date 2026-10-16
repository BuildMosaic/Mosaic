#!/usr/bin/env python3
"""Build once; run existing compiler and installation fixtures against exact Kotlin versions."""

import argparse
from copy import deepcopy
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

ROOT = Path(__file__).resolve().parent.parent
REPORTS = ROOT / 'build/reports/compatibility'
RUNTIME = ('mosaic-core', 'mosaic-test', 'mosaic-opentelemetry')


def central_versions():
    url = 'https://repo.maven.apache.org/maven2/org/jetbrains/kotlin/kotlin-compiler-embeddable/maven-metadata.xml'
    with urllib.request.urlopen(url, timeout=60) as response:
        nodes = ET.fromstring(response.read()).findall('./versioning/versions/version')
    result = set()
    for node in nodes:
        try:
            if registry.version(node.text) >= (2, 2, 0):
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
        self.baseline = re.search(r'^kotlin = "([^"]+)"', (ROOT / 'gradle/libs.versions.toml').read_text(), re.M)[1]

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

    def build(self, scope, version):
        modules = list(RUNTIME) if scope != 'analysis' else []
        if scope != 'runtime':
            modules += ['mosaic-compiler-plugin', 'mosaic-gradle-plugin']
        self.gradle('build', *[f':{m}:publishAllPublicationsToInstallTestRepository' for m in modules],
                    '--no-configure-on-demand', f'-Pmosaic.runtime.version={version}',
                    f'-Pmosaic.analysis.version={version}', f'-Pmosaic.installTestRepository={self.repository}')

    def resolve(self, compiler, runtime, analysis=None, baseline=None, extractor='mosaic-compiler-plugin'):
        self.gradle(f'resolve-{runtime}-{analysis}-{compiler}', '-p', ROOT / 'compatibility', 'resolveEvidence',
                    f'-Pcompat.kotlin={compiler}', f'-Pcompat.baseline={baseline or self.baseline}',
                    f'-Pcompat.extractor={extractor}', f'-Pcompat.runtime={runtime}',
                    f'-Pcompat.analysis={analysis or ""}', f'-Pcompat.workspace={self.directory}')
        resolution = json.loads((self.directory / runtime / (analysis or 'runtime') / compiler / 'resolution.json').read_text())
        self.java = resolution['java17']
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

    def analysis(self, versions, runtime, analysis):
        self.gradle('fixtures', ':mosaic-compiler-plugin:testClasses', ':mosaic-analysis-core:testClasses',
                    ':mosaic-gradle-plugin:testClasses', '--no-configure-on-demand',
                    f'-Pmosaic.runtime.version={runtime}', f'-Pmosaic.analysis.version={analysis}')
        control = None
        name = f'{runtime}-{analysis}'
        for version in versions:
            artifacts = self.resolve(version, runtime, analysis)
            default, bounds = compiler_profiles(artifacts['extractor'][0]['file'])
            api = compiler_api(version, bounds)
            artifact = 'mosaic-compiler-plugin' + ('' if api == default else f'-kotlin-{api}')
            artifacts = self.resolve(version, runtime, analysis, baseline=default, extractor=artifact)
            if control is None:
                self.junit(f'{name}-metadata', 'mosaic-analysis-core', artifacts, '.*Summary.*Test')
            selected = self.extract(f'{name}-{version}', artifacts['compiler'], version, artifacts['extractor'][0]['file'], artifacts)
            if control is None:
                control = selected
            elif selected != control:
                raise RuntimeError(f'Compiler summaries differ for {version}; compare {versions[0]} and {version} summaries')
        self.gradle(f'install-{name}', ':mosaic-gradle-plugin:releaseCompatibilityTest',
                    f'-Pmosaic.test.kotlinVersions={",".join(versions)}', f'-Pmosaic.test.repository={self.repository}',
                    f'-Pmosaic.test.runtimeVersion={runtime}', f'-Pmosaic.test.analysisVersion={analysis}',
                    f'-Pmosaic.runtime.version={runtime}', f'-Pmosaic.analysis.version={analysis}')
        junit_results(ROOT / 'mosaic-gradle-plugin/build/test-results/releaseCompatibilityTest')


def certify(versions, scope, java_installations='', release=None, counterparts=None):
    registry.versions(versions)
    published_kotlin = versions
    data = registry.load()
    opposite = 'analysis' if scope == 'runtime' else 'runtime'
    if release is not None:
        base = release.split('-')[0]
        registry.version(base)
        if not re.fullmatch(r'[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.-]+)?', release):
            raise ValueError('Invalid release version')
        counterparts = sorted(set(counterparts), key=registry.version) if counterparts is not None else sorted(data[opposite], key=registry.version)[-1:]
        if scope == 'analysis' and not counterparts:
            raise ValueError('Release a Runtime first; Analysis certification requires a published Runtime')
        if any(v not in data[opposite] for v in counterparts):
            raise ValueError('Counterparts must be recorded published releases')
        if scope == 'analysis':
            # Selecting endpoints also selects every published Runtime between them.
            counterparts = [r for r in data['runtime']
                            if registry.version(counterparts[0]) <= registry.version(r) <= registry.version(counterparts[-1])]
        else:
            counterparts = sorted(set(counterparts) | {a for a, interval in data['analysis'].items()
                                  if registry.within(interval, base, 'Runtime')}, key=registry.version)
    certification = Certification(java_installations)
    candidate = release or f'0.0.0-compat-{certification.directory.name}'
    certification.build(scope, candidate)
    if scope != 'analysis':
        certification.runtime(versions, candidate)
    if release is None and scope == 'all':
        certification.analysis(versions, candidate, candidate)
    records = []
    if release is not None and scope == 'runtime':
        records.append({'result': 'pass', 'train': 'runtime', 'version': release, 'kotlin': versions})
        runtimes = {**data['runtime'], base: {'minKotlin': versions[0], 'maxKotlin': versions[-1]}}
        for analysis in counterparts:
            interval = data['analysis'][analysis]
            lower = min(base, interval['minRuntime'], key=registry.version)
            upper = max(base, interval['maxRuntime'], key=registry.version)
            common = [v for v in versions if registry.within(interval, v)]
            required = sorted([r for r in runtimes if registry.version(lower) <= registry.version(r) <= registry.version(upper)
                        and (r == base or not registry.within(interval, r, 'Runtime'))], key=registry.version)
            for runtime in required:
                selected = registry.kotlin_versions(common, runtimes[runtime])
                if selected:
                    certification.analysis(selected, candidate if runtime == base else runtime, analysis)
            records.append({'result': 'pass', 'train': 'analysis', 'version': analysis,
                            'runtime': required, 'kotlin': common})
    elif release is not None:
        versions = [v for v in versions if any(registry.within(data['runtime'][r], v) for r in counterparts)]
        registry.versions(versions)
        for runtime in counterparts:
            selected = registry.kotlin_versions(versions, data['runtime'][runtime])
            if selected:
                certification.runtime(selected, runtime)
                certification.analysis(selected, runtime, candidate)
        records.append({'result': 'pass', 'train': 'analysis', 'version': release,
                        'kotlin': versions, 'runtime': sorted(counterparts, key=registry.version)})
    result = {'train': scope, 'version': release, 'records': records,
              'revision': certification.revision, 'result': 'pass'}
    if release is not None and '-' not in release:
        registry.apply_evidence(deepcopy(data), result, published_kotlin, data)
    path = certification.directory / 'result.json'
    path.write_text(json.dumps(result, indent=2) + '\n')
    if release is not None:
        (REPORTS / f'release-{scope}.json').write_text(path.read_text())
    print(f'Compatibility passed: {path}', flush=True)
    return result


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--kotlin', help='Comma-separated exact stable compiler versions; default: all discovered versions')
    parser.add_argument('--scope', choices=('all', 'runtime', 'analysis'), default='all')
    parser.add_argument('--java-installations', default='')
    parser.add_argument('--release', help='Certify this actual release version before publication')
    parser.add_argument('--counterparts', help='Comma-separated published counterparts; includes intervening Runtime releases; default: latest')
    parser.add_argument('--runtime', help='Verify this unchanged published Runtime; requires --kotlin')
    parser.add_argument('--analysis', help='Also verify this unchanged published Analysis with --runtime')
    args = parser.parse_args()
    if args.counterparts and args.release is None:
        parser.error('--counterparts requires --release')
    if args.release is not None:
        if args.scope == 'all' or args.kotlin:
            parser.error('Releases require one train and the complete compiler matrix')
    if args.scope == 'analysis' and args.release is None:
        parser.error('Local Analysis checks use the full candidate matrix; --scope analysis requires --release')
    if args.analysis and not args.runtime:
        parser.error('--analysis requires --runtime')
    if args.runtime and (not args.kotlin or args.release is not None or args.counterparts or args.scope != 'all'):
        parser.error('Published verification requires --kotlin and --runtime, with optional --analysis')
    try:
        versions = sorted(set(args.kotlin.split(',') if args.kotlin else central_versions()), key=registry.version)
        registry.versions(versions)
        if args.runtime:
            registry.version(args.runtime)
            if args.analysis:
                registry.version(args.analysis)
            check = Certification(args.java_installations)
            check.runtime(versions, args.runtime)
            if args.analysis:
                check.analysis(versions, args.runtime, args.analysis)
            print(f'Published compatibility passed: {check.directory}', flush=True)
        else:
            certify(versions, args.scope, args.java_installations, args.release,
                    args.counterparts.split(',') if args.counterparts else None)
    except Exception as failure:
        print(f'Compatibility failed: {failure}', file=sys.stderr)
        sys.exit(1)

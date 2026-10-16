#!/usr/bin/env python3
"""Prepare, execute and aggregate one release certification plan across runners."""

import argparse
from copy import deepcopy
import hashlib
import json
import os
from pathlib import Path
import re
import shutil

import certify
import registry

MODULES = ('mosaic-compiler-plugin', 'mosaic-analysis-core', 'mosaic-gradle-plugin')


def write(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2) + '\n')


def inventory(directory):
    return {str(p.relative_to(directory)): hashlib.sha256(p.read_bytes()).hexdigest()
            for area in ('repository', 'fixtures') for p in sorted((directory / area).rglob('*')) if p.is_file()}


def checks(plan):
    return registry.release_plan(plan['registry'], plan['train'], plan['version'], plan['catalog'], plan['policy'])


def matrix(plan):
    standalone, batches = checks(plan)
    return sorted({v for _, versions in standalone for v in versions} |
                  {v for _, _, versions in batches for v in versions}, key=registry.version)


def required(plan, analysis):
    return plan['train'] == 'analysis' or registry.within(
        plan['registry']['analysis'][analysis], plan['version'].split('-')[0], 'Runtime')


def prepare(directory, scope, version, java_installations='', **requested):
    from maintain import available, publications
    if scope not in ('runtime', 'analysis') or not re.fullmatch(r'[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.-]+)?', version):
        raise ValueError('Expected one release train and a release version')
    if scope == 'runtime' and any(requested.get(k) for k in ('minRuntime', 'maxRuntime')):
        raise ValueError('Runtime releases accept only Kotlin bounds')
    data = registry.load()
    checkpoint = registry.PATH.with_name('kotlin-checkpoint.txt').read_text().strip()
    upstream = publications()
    if any(item['published'] > checkpoint for item in upstream):
        raise ValueError('New Kotlin publications require discovery before release planning; retry the release workflow')
    published = {item['kotlin'] for item in upstream}
    catalog = [v for v in certify.central_versions() if v in published]
    policy = registry.release_policy(data, scope, catalog, **requested)
    for compiler in registry.kotlin_versions(catalog, policy):
        if not available(compiler):
            raise ValueError(f'Required Kotlin {compiler} compiler/KGP artifacts are unavailable')
    check = certify.Certification(java_installations)
    plan = {'train': scope, 'version': version, 'revision': check.revision, 'catalog': catalog,
            'policy': policy, 'registry': data, 'checkpoint': checkpoint}
    standalone, batches = checks(plan)
    print(f'Release policy: {json.dumps(policy, sort_keys=True)}', flush=True)
    print(f'Kotlin matrix: {matrix(plan)}; Runtime checks: {standalone}; Analysis batches: {batches}', flush=True)
    # Never reuse an old prepared repository or stale fixture classes.
    directory.mkdir(parents=True, exist_ok=False)
    check.repository = directory / 'repository'
    check.build(scope, version)
    if batches:
        check.prepare_fixtures()
        for module in MODULES:
            for area in ('classes/kotlin/test', 'resources/test'):
                source = certify.ROOT / module / 'build' / area
                if source.exists():
                    shutil.copytree(source, directory / 'fixtures' / module / 'build' / area)
    plan['files'] = inventory(directory)
    write(directory / 'plan.json', plan)
    if os.environ.get('GITHUB_OUTPUT'):
        with open(os.environ['GITHUB_OUTPUT'], 'a') as output:
            output.write(f'matrix={json.dumps(matrix(plan))}\n')
    return plan


def load(directory, java_installations):
    plan = json.loads((directory / 'plan.json').read_text())
    check = certify.Certification(java_installations)
    if check.revision != plan['revision'] or inventory(directory) != plan['files']:
        raise ValueError('Prepared certification does not match this commit or its immutable build outputs')
    check.repository = directory / 'repository'
    check.fixtures = directory / 'fixtures'
    check.fixtures_ready = True
    return plan, check


def worker(directory, kotlin, output, java_installations=''):
    plan, check = load(directory, java_installations)
    if kotlin not in matrix(plan):
        raise ValueError(f'Unplanned Kotlin worker: {kotlin}')
    evidence = {'plan': registry.fingerprint(plan), 'kotlin': kotlin, 'runtime': [], 'analysis': []}
    standalone, batches = checks(plan)
    failed = False
    for runtime, versions in standalone:
        if kotlin not in versions:
            continue
        result = {'runtime': runtime, 'result': 'pass'}
        try:
            check.runtime([kotlin], runtime)
        except Exception as error:
            print(f'Runtime {runtime} / Kotlin {kotlin}: {error}', flush=True)
            result['result'] = 'fail'
            failed = True
        evidence['runtime'].append(result)
    for runtime, analysis, versions in batches:
        if kotlin not in versions:
            continue
        result = {'runtime': runtime, 'analysis': analysis, 'result': 'pass', 'summaries': None}
        try:
            summaries, artifacts = check.analysis_version(kotlin, runtime, analysis, metadata=kotlin == versions[0])
            check.installation([kotlin], runtime, analysis, artifacts)
            result['summaries'] = summaries
        except Exception as error:
            print(f'Analysis {analysis} / Runtime {runtime} / Kotlin {kotlin}: {error}', flush=True)
            result['result'] = 'fail'
            failed |= required(plan, analysis)
        evidence['analysis'].append(result)
    write(output / f'{kotlin}.json', evidence)
    if failed:
        raise RuntimeError(f'Required Kotlin {kotlin} certification failed')
    return evidence


def normalized_summary(value, analysis):
    return (isinstance(value, dict) and
            set(value) == {'contractVersion', 'producer', 'moduleId', 'sourceSet', 'complete', 'payload'} and
            type(value['contractVersion']) is int and value['contractVersion'] > 0 and
            isinstance(value['producer'], dict) and value['producer'].get('analysisVersion') == analysis and
            'compilerVersion' not in value['producer'] and
            isinstance(value['moduleId'], str) and isinstance(value['sourceSet'], str) and
            type(value['complete']) is bool and isinstance(value['payload'], dict))


def validate_evidence(plan, directory):
    versions = matrix(plan)
    if {p.name for p in directory.iterdir()} != {f'{v}.json' for v in versions}:
        raise ValueError('Missing or unexpected Kotlin evidence')
    standalone, batches = checks(plan)
    evidence = {}
    for kotlin in versions:
        row = json.loads((directory / f'{kotlin}.json').read_text())
        if (set(row) != {'plan', 'kotlin', 'runtime', 'analysis'} or row['plan'] != registry.fingerprint(plan)
                or row['kotlin'] != kotlin):
            raise ValueError(f'Inconsistent Kotlin {kotlin} evidence')
        expected_runtime = [r for r, compilers in standalone if kotlin in compilers]
        expected_analysis = [(r, a) for r, a, compilers in batches if kotlin in compilers]
        if ([r['runtime'] for r in row['runtime']] != expected_runtime or
                [(r['runtime'], r['analysis']) for r in row['analysis']] != expected_analysis):
            raise ValueError(f'Incomplete Kotlin {kotlin} checks')
        for item in row['runtime']:
            if set(item) != {'runtime', 'result'} or item['result'] != 'pass':
                raise ValueError(f'Required Runtime check failed for Kotlin {kotlin}')
        for item in row['analysis']:
            if set(item) != {'runtime', 'analysis', 'result', 'summaries'} or item['result'] not in ('pass', 'fail'):
                raise ValueError(f'Malformed Analysis evidence for Kotlin {kotlin}')
            if item['result'] == 'pass':
                summaries = item['summaries']
                if not isinstance(summaries, dict) or not summaries or any(
                        not normalized_summary(value, item['analysis']) for value in summaries.values()):
                    raise ValueError(f'Missing normalized summaries for Kotlin {kotlin}')
            elif item['summaries'] is not None or required(plan, item['analysis']):
                raise ValueError(f'Required Analysis check failed for Kotlin {kotlin}')
        evidence[kotlin] = row
    return evidence


def aggregate(directory, evidence_directory, java_installations=''):
    plan, check = load(directory, java_installations)
    # Validate every worker before any optional failure can be interpreted as an extension failure.
    evidence = validate_evidence(plan, evidence_directory)
    updated = deepcopy(plan['registry'])
    scope, release = plan['train'], plan['version']
    if '-' not in release:
        updated[scope][release] = plan['policy']
    _, batches = checks(plan)
    compiler_changes = True
    for runtime, analysis, versions in batches:
        try:
            rows = [next(r for r in evidence[v]['analysis'] if (r['runtime'], r['analysis']) == (runtime, analysis))
                    for v in versions]
            if any(r['result'] != 'pass' for r in rows):
                raise RuntimeError('A Kotlin worker failed this Analysis extension')
            if any(r['summaries'] != rows[0]['summaries'] for r in rows[1:]):
                raise RuntimeError(f'Compiler summaries differ for Analysis {analysis} / Runtime {runtime}')
            # Preserve the existing once-per-certification cross-compiler/cache regression batch.
            if (compiler_changes and '2.4.20' in versions and
                    (check.repository / f'org/buildmosaic/mosaic-gradle-plugin/{analysis}').is_dir()):
                artifacts = check.resolve('2.4.20', runtime, analysis)
                check.installation(versions, runtime, analysis, artifacts, changes=True, changes_only=True)
                if len(versions) > 1:
                    compiler_changes = False
        except Exception as error:
            if required(plan, analysis):
                raise
            print(f'Analysis {analysis} / new Runtime {release} failed: {error}; its Runtime bound is unchanged', flush=True)
            continue
        if scope == 'runtime' and '-' not in release:
            interval = updated['analysis'][analysis]
            interval['maxRuntime'] = max(interval['maxRuntime'], release, key=registry.version)
    if scope == 'runtime' and '-' not in release:
        for analysis in registry.runtime_analyses(plan['registry'], release):
            interval = plan['registry']['analysis'][analysis]
            if not registry.kotlin_versions(plan['catalog'], plan['policy'], interval):
                updated['analysis'][analysis]['maxRuntime'] = max(interval['maxRuntime'], release, key=registry.version)
    result = {'train': scope, 'version': release, 'revision': plan['revision'], 'result': 'pass',
              'registryBefore': registry.fingerprint(plan['registry']), 'checkpointBefore': plan['checkpoint'],
              'registry': updated, 'checkpoint': plan['checkpoint']}
    if '-' not in release:
        registry.apply_result(plan['registry'], plan['checkpoint'], result)
    path = certify.REPORTS / f'release-{scope}.json'
    write(path, result)
    print(f'Compatibility passed: {path}', flush=True)
    return result


def certify_release(scope, version, java_installations='', **requested):
    directory = certify.Certification(java_installations).directory / 'prepared'
    plan = prepare(directory, scope, version, java_installations, **requested)
    evidence = directory.parent / 'evidence'
    evidence.mkdir()
    for kotlin in matrix(plan):
        worker(directory, kotlin, evidence, java_installations)
    return aggregate(directory, evidence, java_installations)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('stage', choices=('prepare', 'worker', 'aggregate'))
    parser.add_argument('--directory', type=Path, required=True)
    parser.add_argument('--java-installations', default='')
    parser.add_argument('--scope', choices=('runtime', 'analysis'))
    parser.add_argument('--release')
    for bound in ('minKotlin', 'maxKotlin', 'minRuntime', 'maxRuntime'):
        parser.add_argument('--' + bound, default='')
    parser.add_argument('--kotlin')
    parser.add_argument('--evidence', type=Path)
    args = parser.parse_args()
    if args.stage == 'prepare':
        if not args.scope or not args.release:
            parser.error('prepare requires --scope and --release')
        prepare(args.directory.resolve(), args.scope, args.release, args.java_installations,
                **{k: getattr(args, k) for k in ('minKotlin', 'maxKotlin', 'minRuntime', 'maxRuntime')})
    elif args.stage == 'worker':
        if not args.kotlin or not args.evidence:
            parser.error('worker requires --kotlin and --evidence')
        worker(args.directory.resolve(), args.kotlin, args.evidence, args.java_installations)
    else:
        if not args.evidence:
            parser.error('aggregate requires --evidence')
        aggregate(args.directory.resolve(), args.evidence, args.java_installations)

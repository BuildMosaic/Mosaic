#!/usr/bin/env python3
"""Release range planning and the small cross-version final check. Actions owns execution."""
import argparse
from copy import deepcopy
import json
import os
from pathlib import Path
import subprocess

import registry
from maintain import ROOT, available, central_versions, publications


def plan(train, release, **bounds):
    if train == 'runtime' and (bounds.get('minRuntime') or bounds.get('maxRuntime')):
        raise ValueError('Runtime releases accept only Kotlin bounds')
    data = registry.load()
    checkpoint = registry.PATH.with_name('kotlin-checkpoint.txt').read_text().strip()
    upstream = publications()
    if any(item['published'] > checkpoint for item in upstream):
        raise ValueError('Process outstanding Kotlin publications before release planning')
    published = {item['kotlin'] for item in upstream}
    catalog = [v for v in central_versions() if v in published]
    policy = registry.release_policy(data, train, catalog, **bounds)
    standalone, batches = registry.release_plan(data, train, release, catalog, policy)
    for compiler in registry.kotlin_versions(catalog, policy):
        if not available(compiler):
            raise ValueError(f'Kotlin {compiler} compiler/KGP artifacts are unavailable')
    updated = deepcopy(data)
    if '-' not in release:
        updated[train][release] = policy
    checks = [(r, '-', vs, False) for r, vs in standalone]
    checks += [(r, a, vs, train == 'runtime' and not registry.within(data['analysis'][a], release.split('-')[0], 'Runtime'))
               for r, a, vs in batches]
    if train == 'runtime' and '-' not in release:
        for analysis in registry.runtime_analyses(data, release):
            updated['analysis'][analysis]['maxRuntime'] = max(data['analysis'][analysis]['maxRuntime'], release, key=registry.version)
    matrix = [{'kotlin': v, 'checks': [{'runtime': r, 'analysis': a, 'optional': extension}
                                     for r, a, vs, extension in checks if v in vs]}
              for v in sorted({v for _, _, vs, _ in checks for v in vs}, key=registry.version)]
    result = {'train': train, 'version': release, 'revision': os.environ['RELEASE_SHA'],
              'registryBefore': registry.fingerprint(data), 'checkpointBefore': checkpoint,
              'registry': updated, 'checkpoint': checkpoint, 'matrix': matrix}
    Path('release-plan.json').write_text(json.dumps(result, indent=2) + '\n')
    with open(os.environ['GITHUB_OUTPUT'], 'a') as output:
        output.write('matrix=' + json.dumps(matrix) + '\n')


def verify_pair(runtime, analysis, versions):
    control = None
    for compiler in versions:
        directory = Path('certification') / runtime / analysis / compiler
        summaries = {}
        for path in directory.glob('*.json'):
            summary = json.loads(path.read_text())
            if summary['producer'].pop('compilerVersion') != compiler:
                raise ValueError(f'Incorrect compiler provenance: {path}')
            summary.pop('payloadHash')
            summaries[path.name] = summary
        if not summaries or (control is not None and summaries != control):
            raise ValueError(f'Missing or unequal summaries: {directory}')
        control = summaries
    if '2.4.20' in versions and len(versions) > 1:
        subprocess.run([str(ROOT / 'gradlew'), ':mosaic-gradle-plugin:releaseCompatibilityTest',
                        '--tests', '*IntrospectorSelectionInstallationTest', '--no-configure-on-demand', '--no-configuration-cache',
                        '-Pmosaic.test.compilerChangesOnly=true', f'-Pmosaic.test.kotlinVersions={",".join(versions)}',
                        f'-Pmosaic.test.repository={os.environ["MOSAIC_COMPAT_REPOSITORY"]}',
                        f'-Pmosaic.test.runtimeVersion={runtime}', f'-Pmosaic.test.analysisVersion={analysis}',
                        f'-Porg.gradle.java.installations.paths={os.environ["MOSAIC_JAVA_INSTALLATIONS"]}'], cwd=ROOT, check=True)


def finish():
    result = json.loads(Path('release-plan.json').read_text())
    failed = {p.name for p in Path('certification/optional-failures').glob('*')}
    original = registry.load()
    pairs = {}
    matrix = result.pop('matrix')
    optional = {c['analysis'] for row in matrix for c in row['checks'] if c['optional']}
    for row in matrix:
        for check in row['checks']:
            if check['analysis'] != '-':
                pairs.setdefault((check['runtime'], check['analysis']), []).append(row['kotlin'])
    for (runtime, analysis), versions in pairs.items():
        try:
            if analysis in failed:
                raise ValueError(f'Analysis {analysis} extension failed in the Kotlin matrix')
            verify_pair(runtime, analysis, versions)
        except (ValueError, subprocess.CalledProcessError) as error:
            if analysis not in optional:
                raise
            result['registry']['analysis'][analysis] = original['analysis'][analysis]
            print(f'{error}; retaining Analysis {analysis} Runtime bound', flush=True)
    result['result'] = 'pass'
    if '-' not in result['version']:
        registry.apply_result(original, result['checkpointBefore'], result)
    Path('release-result.json').write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps(result), flush=True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='command', required=True)
    planning = commands.add_parser('plan')
    planning.add_argument('--train', choices=('runtime', 'analysis'), required=True)
    planning.add_argument('--version', required=True)
    for bound in ('minKotlin', 'maxKotlin', 'minRuntime', 'maxRuntime'):
        planning.add_argument('--' + bound, default='')
    commands.add_parser('finish')
    args = vars(parser.parse_args())
    if args.pop('command') == 'plan':
        train, version = args.pop('train'), args.pop('version')
        plan(train, version, **args)
    else:
        finish()

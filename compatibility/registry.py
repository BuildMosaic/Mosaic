#!/usr/bin/env python3
"""Published compatibility facts. Unrecorded combinations are unknown."""

import argparse
import json
from pathlib import Path
import re

PATH = Path(__file__).with_name('registry.json')


def version(value):
    if not isinstance(value, str) or not re.fullmatch(r'(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)', value):
        raise ValueError(f'Expected stable major.minor.patch version: {value!r}')
    return tuple(map(int, value.split('.')))


def versions(values):
    if not isinstance(values, list) or not values or values != sorted(set(values), key=version):
        raise ValueError('Expected a nonempty, sorted list of unique stable versions')
    return values


def validate(data):
    if set(data) != {'runtime', 'analysis'}:
        raise ValueError('Expected runtime and analysis release maps')
    for release, kotlin in data['runtime'].items():
        version(release)
        versions(kotlin)
    for release, entry in data['analysis'].items():
        version(release)
        if set(entry) != {'kotlin', 'runtime'}:
            raise ValueError('Analysis requires Kotlin versions and tested Runtime relationships')
        versions(entry['kotlin'])
        if not entry['runtime']:
            raise ValueError('Analysis requires a tested Runtime relationship')
        for runtime, kotlin in entry['runtime'].items():
            if runtime not in data['runtime']:
                raise ValueError(f'Unknown Runtime release: {runtime}')
            if not set(versions(kotlin)) <= set(entry['kotlin']) & set(data['runtime'][runtime]):
                raise ValueError(f'Unverified Kotlin versions for Analysis {release} and Runtime {runtime}')
    return data


def load():
    return validate(json.loads(PATH.read_text()))


def record(data, result):
    if result['result'] != 'pass':
        raise ValueError('Only passing certification can extend the matrix')
    runtime, analysis, kotlin = result['runtime'], result['analysis'], versions(result['kotlin'])
    version(runtime)
    if analysis:
        version(analysis)
    data['runtime'][runtime] = sorted(set(data['runtime'].get(runtime, [])) | set(kotlin), key=version)
    if analysis:
        entry = data['analysis'].setdefault(analysis, {'kotlin': [], 'runtime': {}})
        entry['kotlin'] = sorted(set(entry['kotlin']) | set(kotlin), key=version)
        entry['runtime'][runtime] = sorted(set(entry['runtime'].get(runtime, [])) | set(kotlin), key=version)


def compatible(data, runtime, kotlin, analysis=None):
    entry = data['analysis'].get(analysis, {})
    return kotlin in data['runtime'].get(runtime, []) and (
        analysis is None or (kotlin in entry.get('kotlin', []) and kotlin in entry.get('runtime', {}).get(runtime, [])))


def apply_evidence(data, evidence):
    if 'records' in evidence:
        for result in evidence['records']:
            if result['runtime'] not in data['runtime'] or (result['analysis'] and
                    result['runtime'] not in data['analysis'].get(result['analysis'], {}).get('runtime', {})):
                raise ValueError('Daily certification only extends established published relationships')
            record(data, result)
        return data
    train, release = evidence['train'], evidence['version']
    if evidence['result'] != 'pass' or train not in ('runtime', 'analysis'):
        raise ValueError('Expected a passing release certification')
    version(release)
    versions(evidence['kotlin'])
    opposite = 'analysis' if train == 'runtime' else 'runtime'
    if any(v not in data[opposite] for v in evidence['counterparts']):
        raise ValueError('Unknown published counterpart')
    # Retries append the same verified facts after publication; they never replace facts.
    for compilers in evidence['counterparts'].values():
        if not set(versions(compilers)) <= set(evidence['kotlin']):
            raise ValueError('Relationship contains an unverified compiler')
    if train == 'runtime':
        record(data, {'result': 'pass', 'runtime': release, 'analysis': None, 'kotlin': evidence['kotlin']})
        for analysis, compilers in evidence['counterparts'].items():
            record(data, {'result': 'pass', 'runtime': release, 'analysis': analysis, 'kotlin': compilers})
    else:
        if not evidence['counterparts']:
            raise ValueError('Analysis requires a tested published Runtime')
        for runtime, compilers in evidence['counterparts'].items():
            record(data, {'result': 'pass', 'runtime': runtime, 'analysis': release, 'kotlin': compilers})
        data['analysis'][release]['kotlin'] = sorted(set(data['analysis'][release]['kotlin']) | set(evidence['kotlin']), key=version)
    return data


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=('validate',))
    parser.parse_args()
    load()
    print('valid')

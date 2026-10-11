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


def load(path=PATH):
    data = json.loads(Path(path).read_text())
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


def compatible(data, runtime, kotlin, analysis=None):
    entry = data['analysis'].get(analysis, {})
    return kotlin in data['runtime'].get(runtime, []) and (
        analysis is None or (kotlin in entry.get('kotlin', []) and kotlin in entry.get('runtime', {}).get(runtime, [])))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=('validate', 'check'))
    parser.add_argument('--runtime')
    parser.add_argument('--analysis')
    parser.add_argument('--kotlin')
    args = parser.parse_args()
    if args.command == 'check' and not (args.runtime and args.kotlin):
        parser.error('check requires --runtime and --kotlin')
    data = load()
    print('valid' if args.command == 'validate' else
          'supported' if compatible(data, args.runtime, args.kotlin, args.analysis) else 'unknown')

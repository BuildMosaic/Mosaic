#!/usr/bin/env python3
"""Inclusive Kotlin compatibility ranges. Unrecorded release pairings are unknown."""

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


def bounds(interval):
    if set(interval) != {'minKotlin', 'maxKotlin'}:
        raise ValueError('Expected inclusive minKotlin and maxKotlin')
    lower, upper = version(interval['minKotlin']), version(interval['maxKotlin'])
    if lower > upper:
        raise ValueError('Kotlin range is reversed')
    return lower, upper


def validate(data):
    if set(data) != {'runtime', 'analysis'}:
        raise ValueError('Expected runtime and analysis release maps')
    for release, interval in data['runtime'].items():
        version(release)
        bounds(interval)
    for release, pairings in data['analysis'].items():
        version(release)
        if not pairings:
            raise ValueError('Analysis requires a tested Runtime relationship')
        for runtime, interval in pairings.items():
            if runtime not in data['runtime']:
                raise ValueError(f'Unknown Runtime release: {runtime}')
            lower, upper = bounds(interval)
            rlower, rupper = bounds(data['runtime'][runtime])
            if not rlower <= lower <= upper <= rupper:
                raise ValueError(f'Unverified Kotlin range for Analysis {release} and Runtime {runtime}')
    return data


def load():
    return validate(json.loads(PATH.read_text()))


def compatible(data, runtime, kotlin, analysis=None):
    releases = data['runtime'] if analysis is None else data['analysis'].get(analysis, {})
    interval = releases.get(runtime)
    return interval is not None and version(interval['minKotlin']) <= version(kotlin) <= version(interval['maxKotlin'])


def apply_evidence(data, evidence):
    if 'records' in evidence:
        records = evidence['records']
        for result in records:
            if result['result'] != 'pass' or result['runtime'] not in data['runtime'] or (result['analysis'] and
                    result['runtime'] not in data['analysis'].get(result['analysis'], {})):
                raise ValueError('Daily certification requires passing, established published relationships')
        pairs = [(r['runtime'], r['analysis'], versions(r['kotlin'])) for r in records]
    else:
        train, release = evidence['train'], evidence['version']
        if evidence['result'] != 'pass' or train not in ('runtime', 'analysis'):
            raise ValueError('Expected a passing release certification')
        version(release)
        kotlin = versions(evidence['kotlin'])
        if train == 'analysis' and not evidence['counterparts']:
            raise ValueError('Analysis requires a tested published Runtime')
        opposite = 'analysis' if train == 'runtime' else 'runtime'
        pairs = [(release, None, kotlin)] if train == 'runtime' else []
        for counterpart, compilers in evidence['counterparts'].items():
            if counterpart not in data[opposite] or not set(versions(compilers)) <= set(kotlin):
                raise ValueError('Expected a published counterpart and verified compilers')
            runtime, analysis = (release, counterpart) if train == 'runtime' else (counterpart, release)
            pairs.append((runtime, analysis, compilers))
    # Merge only after all evidence is checked. Retries never replace certified bounds.
    for runtime, analysis, kotlin in pairs:
        releases = [data['runtime']]
        if analysis:
            releases.append(data['analysis'].setdefault(analysis, {}))
        for entries in releases:
            endpoints = kotlin + list(entries.get(runtime, {}).values())
            entries[runtime] = {'minKotlin': min(endpoints, key=version), 'maxKotlin': max(endpoints, key=version)}
    return data


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=('validate',))
    parser.parse_args()
    load()
    print('valid')

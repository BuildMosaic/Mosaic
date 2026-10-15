#!/usr/bin/env python3
"""Independent, inclusive Runtime and Analysis compatibility ranges."""

import argparse
from copy import deepcopy
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


def bounds(interval, kind='Kotlin'):
    lower, upper = version(interval[f'min{kind}']), version(interval[f'max{kind}'])
    if lower > upper:
        raise ValueError(f'{kind} range is reversed')
    return lower, upper


def within(interval, value, kind='Kotlin'):
    lower, upper = bounds(interval, kind)
    return lower <= version(value) <= upper


def validate(data):
    if set(data) != {'runtime', 'analysis'}:
        raise ValueError('Expected runtime and analysis release maps')
    for train, releases in data.items():
        fields = {'minKotlin', 'maxKotlin'} | ({'minRuntime', 'maxRuntime'} if train == 'analysis' else set())
        for release, interval in releases.items():
            version(release)
            if not isinstance(interval, dict) or set(interval) != fields:
                raise ValueError(f'Expected {train} range fields: {sorted(fields)}')
            bounds(interval)
            if train == 'analysis':
                bounds(interval, 'Runtime')
                if any(interval[key] not in data['runtime'] for key in ('minRuntime', 'maxRuntime')):
                    raise ValueError('Analysis Runtime endpoints must be published')
    return data


def load():
    return validate(json.loads(PATH.read_text()))


def compatible(data, runtime, kotlin, analysis=None):
    r = data['runtime'].get(runtime)
    a = data['analysis'].get(analysis)
    return (r is not None and within(r, kotlin) and
            (analysis is None or (a is not None and within(a, runtime, 'Runtime') and within(a, kotlin))))


def apply_evidence(data, evidence, published_kotlin):
    if evidence.get('result', 'pass') != 'pass':
        raise ValueError('Failed certification cannot update ranges')
    updated = deepcopy(data)
    for record in evidence['records']:
        train, release = record['train'], record['version']
        version(release)
        if record['result'] != 'pass' or train not in ('runtime', 'analysis'):
            raise ValueError('Expected passing Runtime or Analysis certification')
        if release not in updated[train] and (evidence.get('train'), evidence.get('version')) != (train, release):
            raise ValueError('New releases require matching release certification')
        entry = updated[train].setdefault(release, {})
        previous = entry.copy()
        kinds = ['Kotlin'] if train == 'runtime' else ['Runtime', 'Kotlin']
        if not any(kind.lower() in record for kind in kinds):
            raise ValueError('Certification must verify a range')
        for kind in kinds:
            if kind.lower() not in record:
                continue
            verified = versions(record[kind.lower()])
            published = published_kotlin if kind == 'Kotlin' else updated['runtime']
            if not set(verified) <= set(published):
                raise ValueError(f'Expected published {kind} versions')
            old = {key: entry[key] for key in (f'min{kind}', f'max{kind}') if key in entry}
            endpoints = verified + list(old.values())
            lower, upper = min(endpoints, key=version), max(endpoints, key=version)
            required = {v for v in published if version(lower) <= version(v) <= version(upper)
                        and (not old or not within(old, v, kind))}
            if not required <= set(verified):
                raise ValueError(f'Unverified intermediate {kind} releases: {sorted(required - set(verified), key=version)}')
            entry.update({f'min{kind}': lower, f'max{kind}': upper})
        validate(updated)
        if train == 'analysis':
            # Extending either dimension must cover the other dimension in full.
            # Recheck against the latest registry, including concurrent extensions.
            for kind, other, published in [('Runtime', 'Kotlin', published_kotlin),
                                            ('Kotlin', 'Runtime', updated['runtime'])]:
                if any(key not in previous or previous[key] != entry[key]
                       for key in (f'min{kind}', f'max{kind}')):
                    required = {v for v in published if within(entry, v, other)}
                    if not required <= set(record.get(other.lower(), [])):
                        raise ValueError(f'Analysis {kind} extension requires verified {other} coverage')
    data.clear()
    data.update(updated)
    return data


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=('validate',))
    parser.parse_args()
    load()
    print('valid')

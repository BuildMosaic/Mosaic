#!/usr/bin/env python3
"""Independent, inclusive Runtime and Analysis compatibility ranges."""

import argparse
from copy import deepcopy
import hashlib
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


def kotlin_versions(values, *intervals):
    return [v for v in values if all(within(interval, v) for interval in intervals)]


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


def fingerprint(data):
    return hashlib.sha256(json.dumps(data, sort_keys=True).encode()).hexdigest()


def event_plan(data, kotlin, previous):
    """Select one publication against its preceding catalog, without persisting a plan."""
    version(kotlin)
    latest = max(previous, key=version) if previous else None
    overall = latest is None or version(kotlin) > version(latest)
    family = version(kotlin)[:2]
    patches = [v for v in previous if version(v)[:2] == family]
    frontier = latest if overall else (
        max(patches, key=version) if patches else None)

    def follows(interval):
        if within(interval, kotlin):
            return True
        return (frontier is not None and version(interval['minKotlin']) <= version(kotlin)
                and (within(interval, frontier) if overall else interval['maxKotlin'] == frontier))

    updated = deepcopy(data)
    runtimes = sorted((r for r, interval in data['runtime'].items() if follows(interval)), key=version)
    for runtime in runtimes:
        interval = updated['runtime'][runtime]
        interval['maxKotlin'] = max(interval['maxKotlin'], kotlin, key=version)
    batches = []
    for analysis, interval in sorted(data['analysis'].items(), key=lambda item: version(item[0])):
        if not follows(interval):
            continue
        for runtime, coverage in sorted(updated['runtime'].items(), key=lambda item: version(item[0])):
            if within(interval, runtime, 'Runtime') and within(coverage, kotlin):
                batches.append((runtime, analysis, [kotlin]))
        updated['analysis'][analysis]['maxKotlin'] = max(interval['maxKotlin'], kotlin, key=version)
    return validate(updated), [(r, [kotlin]) for r in runtimes], batches


def release_policy(data, train, catalog, **requested):
    """Resolve defaults once; explicit policy is never adjusted by verification."""
    previous = data[train][max(data[train], key=version)] if data[train] else {}
    if not data['runtime']:
        raise ValueError('Release policy requires a published Runtime baseline')
    latest_runtime = max(data['runtime'], key=version)
    defaults = {'minKotlin': previous.get('minKotlin', data['runtime'][latest_runtime]['minKotlin']),
                'maxKotlin': max(catalog, key=version)}
    if train == 'analysis':
        defaults.update(minRuntime=previous.get('minRuntime', min(data['runtime'], key=version)),
                        maxRuntime=latest_runtime)
    policy = {key: requested.get(key) or value for key, value in defaults.items()}
    bounds(policy)
    if any(policy[key] not in catalog for key in ('minKotlin', 'maxKotlin')):
        raise ValueError('Kotlin endpoints must be available stable publications')
    if train == 'analysis':
        bounds(policy, 'Runtime')
        if any(policy[key] not in data['runtime'] for key in ('minRuntime', 'maxRuntime')):
            raise ValueError('Runtime endpoints must be published releases')
    return policy


def runtime_analyses(data, candidate):
    previous = max(data['runtime'], key=version)
    return sorted((a for a, interval in data['analysis'].items()
                   if within(interval, candidate, 'Runtime') or
                   (within(interval, previous, 'Runtime') and
                    version(candidate) >= version(interval['minRuntime']))), key=version)


def release_plan(data, train, release, catalog, policy):
    version(release.split('-')[0])
    compilers = kotlin_versions(catalog, policy)
    versions(compilers)
    if train == 'runtime':
        if release.split('-')[0] in data['runtime']:
            raise ValueError('Runtime candidate is already published')
        analyses = runtime_analyses(data, release.split('-')[0])
        batches = [(release, a, kotlin_versions(compilers, data['analysis'][a]))
                   for a in sorted(analyses, key=version)]
        return [(release, compilers)], [b for b in batches if b[2]]
    if release.split('-')[0] in data['analysis']:
        raise ValueError('Analysis candidate is already published')
    batches = [(r, release, kotlin_versions(compilers, coverage))
               for r, coverage in sorted(data['runtime'].items(), key=lambda item: version(item[0]))
               if within(policy, r, 'Runtime')]
    return [], [b for b in batches if b[2]]


def apply_result(data, checkpoint, evidence):
    """Only a complete passing operation can replace the serialized snapshot."""
    if evidence['result'] != 'pass':
        raise ValueError('Failed certification cannot update ranges or checkpoint')
    if evidence['registryBefore'] != fingerprint(data) or evidence['checkpointBefore'] != checkpoint:
        raise ValueError('Compatibility facts changed; retry against the latest registry and checkpoint')
    updated = validate(deepcopy(evidence['registry']))
    for train, releases in data.items():
        for release, interval in releases.items():
            replacement = updated[train][release]
            for key, value in interval.items():
                if key.startswith('min') and replacement[key] != value:
                    raise ValueError('Automatic maintenance cannot change minima')
                if key.startswith('max') and version(replacement[key]) < version(value):
                    raise ValueError('Automatic maintenance cannot lower maxima')
    if evidence['checkpoint'] < checkpoint:
        raise ValueError('Checkpoint cannot move backwards')
    return updated, evidence['checkpoint']


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=('validate',))
    parser.parse_args()
    load()
    print('valid')

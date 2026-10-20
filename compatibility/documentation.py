#!/usr/bin/env python3
"""Refresh only marked current-release recommendations; never migration examples."""
import argparse
import json
import re

from automation import ROOT, REGISTRY, STABLE, gh, validate, version, within

START = '<!-- current-releases:start -->'
END = '<!-- current-releases:end -->'
TARGETS = (
    'README.md', 'mosaic-bom/README.md', 'mosaic-opentelemetry/README.md',
    'website/src/pages/index.astro',
    'website/src/content/docs/start/installation.md',
    'website/src/content/docs/start/quick-start.md',
    'website/src/content/docs/guides/analysis.md',
    'website/src/content/docs/guides/tracing.md',
    'website/src/content/docs/reference/compatibility.mdx',
    'website/src/content/docs/reference/releases.md',
)


def recommendations(data):
    validate(data)  # Registry admits stable certified releases only, never prereleases.
    if not all(data.values()):
        raise ValueError('Current documentation requires both certified release trains')
    selected = {train: max(releases, key=version) for train, releases in data.items()}
    runtime = data['runtime'][selected['runtime']]
    analysis = data['analysis'][selected['analysis']]
    if not within(analysis, selected['runtime'], 'Runtime'):
        raise ValueError('Latest Runtime and Analysis are not certified together; review recommendations')
    minimum = max(runtime['minKotlin'], analysis['minKotlin'], key=version)
    maximum = min(runtime['maxKotlin'], analysis['maxKotlin'], key=version)
    if version(minimum) > version(maximum):
        raise ValueError('Latest Runtime and Analysis have no certified Kotlin intersection')
    return selected, (minimum, maximum)


def refresh(text, data):
    selected, kotlin_range = recommendations(data)
    mdx = '{/* current-releases:start */}' in text
    if mdx:
        text = text.replace('{/* current-releases:start */}', START).replace('{/* current-releases:end */}', END)
    if text.count(START) != text.count(END) or START not in text:
        raise ValueError('Missing or unbalanced current-release markers')

    def block(match):
        body = match[1]
        for train, release in selected.items():
            label = train.capitalize()
            body = re.sub(r'(' + label + r' )' + STABLE, lambda m: m[1] + release, body)
            body = re.sub(r'(' + train + r'-)' + STABLE, lambda m: m[1] + release, body)
        body = re.sub(r'(org\.buildmosaic:mosaic-(?:core|bom|opentelemetry):)' + STABLE,
                      lambda m: m[1] + selected['runtime'], body)
        body = re.sub(r'(<version>)' + STABLE + r'(</version>)',
                      lambda m: m[1] + selected['runtime'] + m[5], body)
        body = re.sub(r'(id\("org.buildmosaic.analysis"\) version ")' + STABLE,
                      lambda m: m[1] + selected['analysis'], body)
        # Keep explicitly selected tutorial KGP versions, but fail if a release makes them incompatible.
        for kotlin in re.findall(r'kotlin\("jvm"\) version "([^"]+)"', body):
            if not version(kotlin_range[0]) <= version(kotlin) <= version(kotlin_range[1]):
                raise ValueError(f'Documented Kotlin {kotlin} is outside the recommended pair range')
        return START + body + END

    text = re.sub(re.escape(START) + r'(.*?)' + re.escape(END), block, text, flags=re.S)
    if mdx:
        text = text.replace(START, '{/* current-releases:start */}').replace(END, '{/* current-releases:end */}')
    return text


def consumer_matrix(text):
    rows = (ROOT / 'compatibility/consumer-matrix.csv').read_text().splitlines()
    if rows[0] != 'gradle,kotlin' or len(rows) < 2:
        raise ValueError('Expected nonempty Gradle/Kotlin consumer fixture')
    table = '| Consumer Gradle | Kotlin / KGP |\n| --------------- | ------------ |\n'
    for row in rows[1:]:
        gradle, kotlin = row.split(',')
        version(gradle)
        version(kotlin)
        table += f'| {gradle:<15} | {kotlin:<12} |\n'
    start, end = '{/* consumer-matrix:start */}', '{/* consumer-matrix:end */}'
    if text.count(start) != 1 or text.count(end) != 1:
        raise ValueError('Missing consumer matrix markers')
    return re.sub(re.escape(start) + r'.*?' + re.escape(end), start + '\n\n' + table + '\n' + end, text, flags=re.S)


def check_publications(data, releases):
    """Detect a publication/recording gap without creating compatibility claims."""
    published = {'runtime': set(), 'analysis': set()}
    for release in releases:
        if release['draft'] or release['prerelease'] or not release.get('published_at'):
            continue
        match = re.fullmatch(r'(runtime|analysis)-(' + STABLE + ')', release['tag_name'])
        if match:
            published[match[1]].add(match[2])
    for train in published:
        if published[train] != set(data[train]):
            raise ValueError(f'{train}: published releases and certified registry differ. '
                             'Inspect publication and recording jobs; recover recording only after verification. '
                             'Do not retry artifact publication or invent certification.')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--write', action='store_true')
    parser.add_argument('--check-publications', action='store_true')
    args = parser.parse_args()
    data = json.loads(REGISTRY.read_text())
    recommendations(data)
    if args.check_publications:
        pages = gh('api', 'repos/BuildMosaic/Mosaic/releases?per_page=100', '--paginate', '--slurp')
        check_publications(data, [release for page in pages for release in page])
    stale = []
    for name in TARGETS:
        path = ROOT / name
        before = path.read_text()
        after = refresh(before, data)
        if name.endswith("compatibility.mdx"):
            after = consumer_matrix(after)
        if before != after:
            if args.write:
                path.write_text(after)
            else:
                stale.append(name)
    if stale:
        raise SystemExit('Stale release recommendations: ' + ', '.join(stale) +
                         '. Run python3 compatibility/documentation.py --write')

#!/usr/bin/env python3
"""Plan compatibility checks and record their passing ranges. Actions runs the checks."""
import argparse
import base64
import calendar
from copy import deepcopy
from datetime import date, datetime, timezone
import json
import os
from pathlib import Path
import re
import subprocess
import urllib.error
import urllib.request

ROOT = Path(__file__).resolve().parent.parent
REGISTRY = ROOT / 'compatibility/registry.json'
CHECKPOINT = REGISTRY.with_name('kotlin-checkpoint.txt')
STABLE = r'(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)'


def version(value):
    if not re.fullmatch(STABLE, value):
        raise ValueError(f'Expected stable major.minor.patch version: {value}')
    return tuple(map(int, value.split('.')))


def within(interval, value, kind='Kotlin'):
    return version(interval['min' + kind]) <= version(value) <= version(interval['max' + kind])


def validate(data):
    if set(data) != {'runtime', 'analysis'}:
        raise ValueError('Expected runtime and analysis release maps')
    for train, releases in data.items():
        fields = {'minKotlin', 'maxKotlin'} | ({'minRuntime', 'maxRuntime'} if train == 'analysis' else set())
        for release, interval in releases.items():
            version(release)
            if set(interval) != fields or not within(interval, interval['minKotlin']):
                raise ValueError(f'Invalid {train} {release} range')
            if train == 'analysis' and (not within(interval, interval['minRuntime'], 'Runtime') or
                    any(interval[key] not in data['runtime'] for key in ('minRuntime', 'maxRuntime'))):
                raise ValueError(f'Invalid Analysis {release} Runtime range')
    return data


def gh(*args, payload=None):
    result = subprocess.check_output(['gh', *args, *(['--input', '-'] if payload is not None else [])],
                                     input=json.dumps(payload) if payload is not None else None, text=True)
    return json.loads(result) if result.strip() else None


def output(**values):
    for key, value in values.items():
        line = f'{key}={json.dumps(value, separators=(",", ":")) if not isinstance(value, str) else value}'
        print(line)
        if os.environ.get('GITHUB_OUTPUT'):
            with open(os.environ['GITHUB_OUTPUT'], 'a') as target:
                target.write(line + '\n')


def publications():
    pages = gh('api', 'repos/JetBrains/kotlin/releases?per_page=100', '--paginate', '--slurp')
    return sorted(({'kotlin': r['tag_name'].removeprefix('v'), 'published': r['published_at']}
                   for page in pages for r in page if not r['draft'] and not r['prerelease'] and
                   r.get('published_at') and re.fullmatch('v?' + STABLE, r['tag_name'])),
                  key=lambda r: (r['published'], version(r['kotlin'])))


def supported(event, catalog):
    family = event['kotlin'].rsplit('.', 1)[0] + '.0'
    first = date.fromisoformat(next(r['published'][:10] for r in catalog if r['kotlin'] == family))
    year, month = divmod(first.year * 12 + first.month - 1 + 18, 12)
    expiry = date(year, month + 1, min(first.day, calendar.monthrange(year, month + 1)[1]))
    return first <= datetime.now(timezone.utc).date() < expiry


def tag(train, release, expected):
    if not re.fullmatch(STABLE + r'(-[0-9A-Za-z.-]+)?', release):
        raise ValueError('Invalid release version')
    name = f'{train}-{release}'
    subprocess.run(['git', 'fetch', '--no-tags', 'origin', '+refs/heads/main:refs/remotes/origin/main',
                    f'refs/tags/{name}'], check=True)
    ref = gh('api', f'repos/{{owner}}/{{repo}}/git/ref/tags/{name}')['object']
    if ref['type'] != 'tag' or (expected and ref['sha'] != expected):
        raise ValueError('Release requires an unchanged signed annotated tag')
    annotated = gh('api', f'repos/{{owner}}/{{repo}}/git/tags/{ref["sha"]}')
    if not annotated['verification']['verified'] or annotated['object']['type'] != 'commit':
        raise ValueError('Release tag must have a verified signature and point to a commit')
    sha = annotated['object']['sha']
    subprocess.run(['git', 'merge-base', '--is-ancestor', sha, 'origin/main'], check=True)
    subprocess.run(['git', 'cat-file', '-e', f'{sha}:docs/releases/{name}.md'], check=True)
    changelog = subprocess.check_output(['git', 'show', f'{sha}:CHANGELOG.md'], text=True)
    if not re.search(r'^## \[' + re.escape(name) + r'\] - \d{4}-\d{2}-\d{2}$', changelog, re.M):
        raise ValueError(f'Missing dated changelog entry for {name}')
    # Refuse a normal rerun after even a partial immutable publication.
    modules = ['mosaic-core', 'mosaic-test', 'mosaic-opentelemetry', 'mosaic-bom']
    if train == 'analysis':
        profiles = subprocess.check_output(['git', 'show',
            f'{sha}:mosaic-analysis-core/src/main/resources/org/buildmosaic/analysis/compiler-profiles.properties'], text=True)
        profiles = dict(line.split('=', 1) for line in profiles.splitlines() if line and not line.startswith('#'))
        modules = ['mosaic-gradle-plugin', 'mosaic-compiler-plugin'] + [
            'mosaic-compiler-plugin-kotlin-' + api for api in profiles if api not in ('default', profiles['default'])]
    urls = [f'https://repo.maven.apache.org/maven2/org/buildmosaic/{m}/{release}/{m}-{release}.pom' for m in modules]
    if train == 'analysis':
        marker = f'org/buildmosaic/analysis/org.buildmosaic.analysis.gradle.plugin/{release}/org.buildmosaic.analysis.gradle.plugin-{release}.pom'
        urls += [f'https://{host}/{marker}' for host in ('repo.maven.apache.org/maven2', 'plugins.gradle.org/m2')]
    for url in urls:
        try:
            with urllib.request.urlopen(urllib.request.Request(url, method='HEAD'), timeout=30):
                raise ValueError(f'Already published: {url}. Inspect partial publication before recovery.')
        except urllib.error.HTTPError as error:
            if error.code != 404:
                raise
    output(sha=sha, tagObject=ref['sha'])


def plan(train, release, requested, retry):
    before = validate(json.loads(REGISTRY.read_text()))
    data = deepcopy(before)
    checkpoint = CHECKPOINT.read_text().strip()
    catalog = publications()
    compilers = sorted({r['kotlin'] for r in catalog}, key=version)
    matrix = []

    def add(runtime, analysis, kotlin, optional=False):
        matrix.append(dict(runtime=runtime, analysis=analysis, kotlin=kotlin, optional=optional))

    if train:
        tag(train, release, '')
        base = release.split('-')[0]
        if base in data[train]:
            raise ValueError(f'{train} {base} is already recorded')
        previous = data[train][max(data[train], key=version)] if data[train] else {}
        latest_runtime = max(data['runtime'], key=version)
        policy = dict(minKotlin=previous.get('minKotlin', data['runtime'][latest_runtime]['minKotlin']), maxKotlin=compilers[-1])
        if train == 'analysis':
            policy.update(minRuntime=previous.get('minRuntime', min(data['runtime'], key=version)), maxRuntime=latest_runtime)
        elif requested.get('minRuntime') or requested.get('maxRuntime'):
            raise ValueError('Runtime releases do not accept Runtime bounds')
        policy.update({key: value for key, value in requested.items() if value})
        if any(policy[key] not in compilers for key in ('minKotlin', 'maxKotlin')) or not within(policy, policy['minKotlin']):
            raise ValueError('Kotlin bounds must be ordered stable publications')
        if train == 'analysis' and (not within(policy, policy['minRuntime'], 'Runtime') or
                any(policy[key] not in data['runtime'] for key in ('minRuntime', 'maxRuntime'))):
            raise ValueError('Runtime bounds must be ordered published endpoints')
        selected = [k for k in compilers if within(policy, k)]
        if train == 'runtime':
            for kotlin in selected:
                add(release, '-', kotlin)
            for analysis, interval in data['analysis'].items():
                if not (within(interval, base, 'Runtime') or within(interval, latest_runtime, 'Runtime') and
                        version(base) >= version(interval['minRuntime'])):
                    continue
                overlap = [k for k in selected if within(interval, k)]
                for kotlin in overlap:
                    add(release, analysis, kotlin, not within(interval, base, 'Runtime'))
                if overlap and '-' not in release:
                    interval['maxRuntime'] = max(interval['maxRuntime'], release, key=version)
        else:
            for runtime, interval in data['runtime'].items():
                if within(policy, runtime, 'Runtime'):
                    for kotlin in selected:
                        if within(interval, kotlin):
                            add(runtime, release, kotlin)
            if not matrix:
                raise ValueError('Analysis policy has no Runtime/Kotlin intersection to verify')
        if '-' not in release:
            data[train][release] = policy
        pending = True
    else:
        events = [r for r in catalog if r['published'] > checkpoint or r['kotlin'] == retry]
        if retry and retry not in compilers:
            raise ValueError('Retry must name a stable upstream publication')
        pending = bool(events)
        for event in events:
            kotlin = event['kotlin']
            checkpoint = max(checkpoint, event['published'])
            if kotlin != retry and not supported(event, catalog):
                print(f'{kotlin}: outside the 18-month family window')
                continue
            previous = [r['kotlin'] for r in catalog[:catalog.index(event)]]
            latest = max(previous, key=version) if previous else None
            patches = [k for k in previous if version(k)[:2] == version(kotlin)[:2]]
            overall = latest is None or version(kotlin) > version(latest)
            frontier = latest if overall else max(patches, key=version) if patches else None

            def follows(interval):
                return within(interval, kotlin) or (frontier is not None and version(interval['minKotlin']) <= version(kotlin) and
                    (within(interval, frontier) if overall else interval['maxKotlin'] == frontier))

            for runtime, interval in data['runtime'].items():
                if follows(interval):
                    add(runtime, '-', kotlin)
                    interval['maxKotlin'] = max(interval['maxKotlin'], kotlin, key=version)
            for analysis, interval in data['analysis'].items():
                if follows(interval):
                    overlaps = [r for r, coverage in data['runtime'].items()
                                if within(interval, r, 'Runtime') and within(coverage, kotlin)]
                    for runtime in overlaps:
                        add(runtime, analysis, kotlin)
                    if overlaps:
                        interval['maxKotlin'] = max(interval['maxKotlin'], kotlin, key=version)
    output(matrix=matrix, registry=validate(data), before=before, checkpoint=checkpoint,
           beforeCheckpoint=CHECKPOINT.read_text().strip(), pending='true' if pending else 'false',
           tooling=subprocess.check_output(['git', 'rev-parse', 'HEAD'], text=True).strip())


def results(maintenance):
    matrix = json.loads(os.environ['MATRIX'])
    data = json.loads(os.environ['REGISTRY'])
    before = json.loads(os.environ['BEFORE'])
    pages = gh('api', 'repos/{owner}/{repo}/actions/runs/' + os.environ['GITHUB_RUN_ID'] +
               '/jobs?filter=latest&per_page=100', '--paginate', '--slurp')
    jobs = {j['name']: j for page in pages for j in page['jobs']}
    failed = []
    for row in matrix:
        name = 'Check {runtime} / {analysis} / {kotlin}'.format(**row)
        if jobs.get(name, {}).get('conclusion') != 'success':
            if row['optional']:
                data['analysis'][row['analysis']] = before['analysis'][row['analysis']]
                print(f'::warning::{name} failed; Analysis Runtime range remains unchanged')
            else:
                failed.append((row, jobs.get(name, {}).get('html_url', run_url())))
    if maintenance:
        issues = gh('api', 'repos/{owner}/{repo}/issues?state=open&per_page=100', '--paginate', '--slurp')
        issues = {i['title']: i['number'] for page in issues for i in page if 'pull_request' not in i}
        for kotlin in {row['kotlin'] for row in matrix}:
            title = f'Kotlin compatibility: {kotlin} / publication'
            failures = [(r, url) for r, url in failed if r['kotlin'] == kotlin]
            if failures:
                body = '\n\n'.join(f'[Failure logs]({url})\n```bash\n./compatibility/check.sh {r["runtime"]} {r["analysis"]} {kotlin}\n```' for r, url in failures)
                body += f'\n\nRetry Kotlin compatibility maintenance with `kotlin={kotlin}`. Ranges and checkpoint were not updated.'
                if title in issues:
                    gh('api', f'repos/{{owner}}/{{repo}}/issues/{issues[title]}/comments', payload={'body': body})
                else:
                    gh('api', 'repos/{owner}/{repo}/issues', payload={'title': title, 'body': body})
            elif title in issues:
                gh('api', f'repos/{{owner}}/{{repo}}/issues/{issues[title]}', '--method', 'PATCH', payload={'state': 'closed'})
    if failed:
        raise RuntimeError('Required compatibility jobs failed or are incomplete; no publication or compatibility updates')
    output(registry=validate(data))


def run_url():
    return f'{os.environ["GITHUB_SERVER_URL"]}/{os.environ["GITHUB_REPOSITORY"]}/actions/runs/{os.environ["GITHUB_RUN_ID"]}'


def record():
    data = validate(json.loads(os.environ['REGISTRY']))
    head = gh('api', 'repos/{owner}/{repo}/git/ref/heads/main')['object']['sha']
    files = {}
    for name in ('registry.json', 'kotlin-checkpoint.txt'):
        entry = gh('api', f'repos/{{owner}}/{{repo}}/contents/compatibility/{name}?ref={head}')
        files[name] = base64.b64decode(entry['content']).decode()
    if json.loads(files['registry.json']) != json.loads(os.environ['BEFORE']) or files['kotlin-checkpoint.txt'].strip() != os.environ['BEFORE_CHECKPOINT']:
        raise ValueError('Compatibility records changed; verify again against current main before recording')
    contents = {'registry.json': json.dumps(data, indent=2) + '\n', 'kotlin-checkpoint.txt': os.environ['CHECKPOINT'] + '\n'}
    additions = [{'path': 'compatibility/' + name, 'contents': base64.b64encode(value.encode()).decode()}
                 for name, value in contents.items() if value != files[name]]
    # Keep ordinary Markdown recommendations in the same atomic commit as certification.
    from documentation import TARGETS, refresh
    for name in TARGETS:
        entry = gh('api', f'repos/{{owner}}/{{repo}}/contents/{name}?ref={head}')
        before = base64.b64decode(entry['content']).decode()
        after = refresh(before, data)
        if after != before:
            additions.append({'path': name, 'contents': base64.b64encode(after.encode()).decode()})
    if not additions:
        print('No compatibility changes')
        return
    result = gh('api', 'graphql', payload={
        'query': 'mutation($input: CreateCommitOnBranchInput!) { createCommitOnBranch(input: $input) { commit { oid } } }',
        'variables': {'input': {'branch': {'repositoryNameWithOwner': os.environ['GITHUB_REPOSITORY'], 'branchName': 'main'},
            'expectedHeadOid': head, 'message': {'headline': 'Record verified Kotlin compatibility', 'body': run_url()},
            'fileChanges': {'additions': additions}}}})
    if result.get('errors'):
        raise RuntimeError(result['errors'])
    print(result['data']['createCommitOnBranch']['commit']['oid'])


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='command', required=True)
    commands.add_parser('validate')
    planning = commands.add_parser('plan')
    planning.add_argument('--train', choices=('runtime', 'analysis'))
    planning.add_argument('--version', default='')
    planning.add_argument('--kotlin', default='')
    for key in ('minKotlin', 'maxKotlin', 'minRuntime', 'maxRuntime'):
        planning.add_argument('--' + key, default='')
    checking = commands.add_parser('tag')
    checking.add_argument('--train', choices=('runtime', 'analysis'), required=True)
    checking.add_argument('--version', required=True)
    checking.add_argument('--expected-object', default='')
    commands.add_parser('results').add_argument('--maintenance', action='store_true')
    commands.add_parser('record')
    args = parser.parse_args()
    if args.command == 'validate':
        validate(json.loads(REGISTRY.read_text()))
    elif args.command == 'tag':
        tag(args.train, args.version, args.expected_object)
    elif args.command == 'plan':
        plan(args.train, args.version, {key: getattr(args, key) for key in ('minKotlin', 'maxKotlin', 'minRuntime', 'maxRuntime')}, args.kotlin)
    elif args.command == 'results':
        results(args.maintenance)
    else:
        record()

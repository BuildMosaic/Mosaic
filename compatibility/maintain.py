#!/usr/bin/env python3
"""Certify published artifacts and extend compatibility ranges on main."""

import argparse
import base64
import calendar
from datetime import date, datetime, timezone
from functools import cache
from itertools import groupby
import json
import os
from pathlib import Path
import subprocess
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET

import registry

ROOT = Path(__file__).resolve().parent.parent
REPORTS = ROOT / 'build/reports/compatibility'


def central_versions():
    url = 'https://repo.maven.apache.org/maven2/org/jetbrains/kotlin/kotlin-compiler-embeddable/maven-metadata.xml'
    with urllib.request.urlopen(url, timeout=60) as response:
        nodes = ET.fromstring(response.read()).findall('./versioning/versions/version')
    result = set()
    for node in nodes:
        try:
            registry.version(node.text)
            result.add(node.text)
        except ValueError:
            pass
    return sorted(result, key=registry.version)


def gh(*args, payload=None, token=None):
    return subprocess.check_output(['gh', *args, *(['--input', '-'] if payload is not None else [])],
                                   input=json.dumps(payload) if payload is not None else None,
                                   env={**os.environ, **({'GH_TOKEN': token} if token else {})},
                                   cwd=ROOT, text=True).strip()


def run_url():
    return f"{os.environ['GITHUB_SERVER_URL']}/{os.environ['GITHUB_REPOSITORY']}/actions/runs/{os.environ['GITHUB_RUN_ID']}"


def open_issues():
    pages = json.loads(gh('api', 'repos/{owner}/{repo}/issues?state=open&per_page=100', '--paginate', '--slurp'))
    return {item['title']: item for page in pages for item in page if 'pull_request' not in item}


def issue_title(kotlin, runtime, analysis):
    return f'Kotlin compatibility: {kotlin}' + (f' / Runtime {runtime}' if runtime else ' / publication') + (f' / Analysis {analysis}' if analysis else '')


def report_failure(kotlin, failure, issues):
    title = issue_title(kotlin, None, None)
    command = f'python3 compatibility/maintain.py scan --kotlin {kotlin}'
    revision = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip()
    body = (f'Published-artifact verification failed at `{revision}`.\n\n'
            f'[Actions diagnostics]({run_url()})\n\n'
            f'```text\n{str(failure)[-4000:]}\n```\n\n'
            f'Reproduce with JDK 17 and 21 available:\n\n```bash\n{command}\n```\n\n'
            'Implementation fixes require review. Dispatch Kotlin compatibility maintenance with '
            f'`kotlin={kotlin}` to retry existing artifacts. A passing retry closes this issue; '
            'a replacement Mosaic release is certified separately.')
    if title in issues:
        result = gh('api', f'repos/{{owner}}/{{repo}}/issues/{issues[title]["number"]}/comments', payload={'body': body})
    else:
        result = gh('api', 'repos/{owner}/{repo}/issues', payload={'title': title, 'body': body})
    print(json.loads(result)['html_url'])


@cache
def available(version):
    for artifact in ('kotlin-compiler-embeddable', 'kotlin-gradle-plugin'):
        for extension in ('pom', 'jar'):
            url = f'https://repo.maven.apache.org/maven2/org/jetbrains/kotlin/{artifact}/{version}/{artifact}-{version}.{extension}'
            try:
                with urllib.request.urlopen(urllib.request.Request(url, method='HEAD'), timeout=30):
                    pass
            except urllib.error.HTTPError as error:
                if error.code == 404:
                    return False
                raise
    return True


def publications():
    pages = json.loads(gh('api', 'repos/JetBrains/kotlin/releases?per_page=100', '--paginate', '--slurp'))
    result = []
    for item in (item for page in pages for item in page):
        if item['draft'] or item['prerelease'] or not item.get('published_at'):
            continue
        kotlin = item['tag_name'].removeprefix('v')
        try:
            registry.version(kotlin)
        except ValueError:
            continue
        result.append({'kotlin': kotlin, 'published': item['published_at']})
    return sorted(result, key=lambda item: (item['published'], registry.version(item['kotlin'])))


def supported(event, catalog):
    family = event['kotlin'].rsplit('.', 1)[0] + '.0'
    first = next(item['published'] for item in catalog if item['kotlin'] == family)
    first = date.fromisoformat(first[:10])
    year, month = divmod(first.year * 12 + first.month - 1 + 18, 12)
    expiry = date(year, month + 1, min(first.day, calendar.monthrange(year, month + 1)[1]))
    return first <= datetime.now(timezone.utc).date() < expiry


def verify_event(data, event, previous):
    updated, standalone, batches = registry.event_plan(data, event['kotlin'], previous)
    for runtime, analysis in [(r, '-') for r, _ in standalone] + [(r, a) for r, a, _ in batches]:
        subprocess.run([str(ROOT / 'compatibility/check.sh'), runtime, analysis, event['kotlin']], check=True)
    return updated


def scan(kotlin):
    data = registry.load()
    checkpoint = registry.PATH.with_name('kotlin-checkpoint.txt').read_text().strip()
    catalog = publications()
    pending = [item for item in catalog if item['published'] > checkpoint]
    if kotlin:
        registry.version(kotlin)
        retry = next((item for item in catalog if item['kotlin'] == kotlin), None)
        if retry is None:
            raise ValueError('Retry must name a stable upstream Kotlin publication')
        if retry not in pending:
            pending.append(retry)
    if not pending:
        print(f'No Kotlin publications after {checkpoint}; no certification or writes', flush=True)
        return
    discovered = central_versions()
    issues = open_issues()
    before = registry.fingerprint(data)
    original_checkpoint = checkpoint
    failure = None
    # Publications sharing one timestamp form one checkpoint boundary.
    for timestamp, events in groupby(pending, key=lambda item: item['published']):
        group_data = data
        passed_issues = set()
        try:
            for event in events:
                compiler = event['kotlin']
                print(f'Start Kotlin publication {compiler} ({timestamp})', flush=True)
                if compiler != kotlin and not supported(event, catalog):
                    print(f'Kotlin {compiler}: outside the 18-month maintenance window', flush=True)
                    continue
                if compiler not in discovered or not available(compiler):
                    raise RuntimeError(f'Kotlin {compiler}: compiler/KGP artifacts are not all available; retry discovery')
                previous = [item['kotlin'] for item in catalog[:catalog.index(event)]
                            if item['kotlin'] in discovered]
                _, standalone, batches = registry.event_plan(group_data, compiler, previous)
                updated = verify_event(group_data, event, previous)
                titles = [issue_title(compiler, r, None) for r, _ in standalone]
                titles += [issue_title(compiler, r, a) for r, a, _ in batches]
                passed_issues.update(issues[title]['number'] for title in titles if title in issues)
                title = issue_title(compiler, None, None)
                if title in issues:
                    passed_issues.add(issues[title]['number'])
                group_data = updated
                print(f'Pass Kotlin {compiler}: {len(standalone)} Runtime checks, {len(batches)} Analysis batches', flush=True)
        except Exception as error:
            failure = error
            report_failure(compiler, error, issues)
            break
        data = group_data
        checkpoint = max(checkpoint, timestamp)
        for number in passed_issues:
            gh('issue', 'close', str(number), '--comment', f'Published artifacts passed verification: {run_url()}')
    if checkpoint != original_checkpoint or registry.fingerprint(data) != before:
        evidence = {'result': 'pass', 'registryBefore': before, 'checkpointBefore': original_checkpoint,
                    'registry': data, 'checkpoint': checkpoint}
        REPORTS.mkdir(parents=True, exist_ok=True)
        (REPORTS / 'published.json').write_text(json.dumps(evidence, indent=2) + '\n')
        if os.environ.get('GITHUB_OUTPUT'):
            with open(os.environ['GITHUB_OUTPUT'], 'a') as output:
                output.write('evidence=true\n')
        print(json.dumps(evidence), flush=True)
    if failure:
        raise RuntimeError(f'Kotlin publication {compiler} failed; its checkpoint and ranges were not advanced') from failure


def main_snapshot():
    repository = os.environ['GITHUB_REPOSITORY']
    head = json.loads(gh('api', f'repos/{repository}/git/ref/heads/main'))['object']['sha']
    files = {}
    for name in ('registry.json', 'kotlin-checkpoint.txt'):
        entry = json.loads(gh('api', f'repos/{repository}/contents/compatibility/{name}?ref={head}'))
        files[name] = base64.b64decode(entry['content']).decode()
    return head, files


def refresh():
    _, files = main_snapshot()
    registry.validate(json.loads(files['registry.json']))
    for name, content in files.items():
        registry.PATH.with_name(name).write_text(content)


def publish(evidence):
    repository = os.environ['GITHUB_REPOSITORY']
    for attempt in range(3):
        head, files = main_snapshot()
        data = registry.validate(json.loads(files['registry.json']))
        checkpoint = files['kotlin-checkpoint.txt'].strip()
        if data == evidence['registry'] and checkpoint == evidence['checkpoint'] and evidence['result'] == 'pass':
            print('Verified compatibility facts are already recorded')
            # Release recovery may need to redeploy after an earlier failed deployment.
            if evidence.get('train'):
                gh('api', f'repos/{repository}/actions/workflows/website.yml/dispatches', payload={'ref': 'main'},
                   token=os.environ.get('GH_WORKFLOW_TOKEN'))
            return
        updated, checkpoint = registry.apply_result(data, checkpoint, evidence)
        contents = {'registry.json': files['registry.json'] if updated == data else json.dumps(updated, indent=2) + '\n',
                    'kotlin-checkpoint.txt': checkpoint + '\n'}
        additions = [{'path': 'compatibility/' + name, 'contents': base64.b64encode(content.encode()).decode()}
                     for name, content in contents.items() if content != files[name]]
        commit = {
            'query': 'mutation($input: CreateCommitOnBranchInput!) { createCommitOnBranch(input: $input) { commit { oid } } }',
            'variables': {'input': {'branch': {'repositoryNameWithOwner': repository, 'branchName': 'main'},
                'expectedHeadOid': head, 'message': {'headline': 'Record verified Kotlin compatibility', 'body': run_url()},
                'fileChanges': {'additions': additions}}}}
        try:
            response = json.loads(gh('api', 'graphql', payload=commit))
        except subprocess.CalledProcessError as error:
            response = json.loads(error.output)
            if not response.get('errors'):
                raise
        if response.get('errors'):
            latest = json.loads(gh('api', f'repos/{repository}/git/ref/heads/main'))['object']['sha']
            if latest != head and attempt < 2:
                continue  # Recheck facts; only unrelated head movement is retryable.
            raise RuntimeError(response['errors'])
        print(response['data']['createCommitOnBranch']['commit']['oid'])
        # The website's path filter excludes checkpoint-only App commits.
        return


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='command', required=True)
    commands.add_parser('refresh')
    scan_command = commands.add_parser('scan')
    scan_command.add_argument('--kotlin', default='')
    publication = commands.add_parser('publish')
    publication.add_argument('--evidence', type=Path, required=True)
    release = commands.add_parser('record')
    release.add_argument('--evidence', type=Path, required=True)
    release.add_argument('--train', choices=('runtime', 'analysis'), required=True)
    release.add_argument('--version', required=True)
    release.add_argument('--revision', required=True)
    args = parser.parse_args()
    if args.command == 'refresh':
        refresh()
    elif args.command == 'scan':
        scan(args.kotlin)
    else:
        evidence = json.loads(args.evidence.read_text())
        if args.command == 'record':
            if (evidence['result'], evidence['train'], evidence['version'], evidence['revision']) != ('pass', args.train, args.version, args.revision):
                raise ValueError('Certification does not match the published release')
            published = json.loads(gh('release', 'view', f'{args.train}-{args.version}', '--json', 'isDraft'))
            if published['isDraft']:
                raise ValueError('Release is not published')
        publish(evidence)

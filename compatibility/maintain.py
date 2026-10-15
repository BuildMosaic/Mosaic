#!/usr/bin/env python3
"""Certify published artifacts and append exact compatibility facts to main."""

import argparse
import base64
import calendar
from datetime import date, datetime, timezone
import json
import os
from pathlib import Path
import subprocess
import sys
import urllib.request
import urllib.error
import xml.etree.ElementTree as ET

import certify
import registry


def central_versions():
    url = 'https://repo.maven.apache.org/maven2/org/jetbrains/kotlin/kotlin-compiler-embeddable/maven-metadata.xml'
    with urllib.request.urlopen(url, timeout=60) as response:
        nodes = ET.fromstring(response.read()).findall('./versioning/versions/version')
    result = set()
    for node in nodes:
        try:
            if registry.version(node.text) >= (2, 2, 0):
                result.add(node.text)
        except ValueError:
            pass
    return sorted(result, key=registry.version)


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


def supported_versions(versions, today=None):
    today = today or datetime.now(timezone.utc).date()
    families = {}
    result = []
    for version in versions:
        major, minor, _ = registry.version(version)
        family = f'{major}.{minor}.0'
        if family not in families:
            release = json.loads(gh('api', f'repos/JetBrains/kotlin/releases/tags/v{family}'))
            first = date.fromisoformat(release['published_at'][:10])
            year, month = divmod(first.year * 12 + first.month - 1 + 18, 12)
            expiry = date(year, month + 1, min(first.day, calendar.monthrange(year, month + 1)[1]))
            families[family] = first <= today < expiry
        if families[family]:
            result.append(version)
    return result


def gh(*args, payload=None, token=None):
    return subprocess.check_output(['gh', *args, *(['--input', '-'] if payload is not None else [])],
                                   input=json.dumps(payload) if payload is not None else None,
                                   env={**os.environ, **({'GH_TOKEN': token} if token else {})},
                                   cwd=certify.ROOT, text=True).strip()


def run_url():
    return f"{os.environ['GITHUB_SERVER_URL']}/{os.environ['GITHUB_REPOSITORY']}/actions/runs/{os.environ['GITHUB_RUN_ID']}"


def open_issues():
    pages = json.loads(gh('api', 'repos/{owner}/{repo}/issues?state=open&per_page=100', '--paginate', '--slurp'))
    return {item['title']: item for page in pages for item in page if 'pull_request' not in item}


def issue_title(kotlin, runtime, analysis):
    return f'Kotlin compatibility: {kotlin} / Runtime {runtime}' + (f' / Analysis {analysis}' if analysis else '')


def report_failure(kotlin, runtime, analysis, failure, issues):
    title = issue_title(kotlin, runtime, analysis)
    command = f'python3 compatibility/certify.py --kotlin {kotlin} --runtime {runtime}'
    if analysis:
        command += f' --analysis {analysis}'
    body = (f'Published-artifact verification failed at `{os.environ["GITHUB_SHA"]}`.\n\n'
            f'[Diagnostics and artifact hashes]({run_url()})\n\n'
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


def scan(kotlin, java_installations):
    data = registry.load()
    issues = open_issues()
    if kotlin and registry.version(kotlin) < (2, 2, 0):
        raise ValueError('Compatibility discovery starts at Kotlin 2.2.0')
    versions = [kotlin] if kotlin else supported_versions(central_versions()) if data['runtime'] else []
    records, failures, passed_issues = [], [], []
    prepared = False
    checked_runtime = set()
    cases = [(r, None) for r in data['runtime']] + [
        (r, a) for a, entry in data['analysis'].items() for r in entry['runtime']]
    for compiler in versions:
        pending = [(r, a) for r, a in cases if not registry.compatible(data, r, compiler, a)
                   and (kotlin or issue_title(compiler, r, a) not in issues)]
        if not pending or not available(compiler):
            continue
        for runtime, analysis in pending:
            try:
                if analysis and not prepared:
                    certify.Certification(java_installations).prepare_tests()
                    prepared = True
                records.append(certify.certify_published(compiler, runtime, analysis, java_installations,
                                                        prepare=False, checked_runtime=checked_runtime))
                title = issue_title(compiler, runtime, analysis)
                if title in issues:
                    passed_issues.append(issues[title]['number'])
            except (subprocess.SubprocessError, OSError, ValueError, RuntimeError, KeyError) as failure:
                print(f'{issue_title(compiler, runtime, analysis)}: {failure}', file=sys.stderr, flush=True)
                failures.append((compiler, runtime, analysis, failure))
    certify.REPORTS.mkdir(parents=True, exist_ok=True)
    evidence = {'records': records}
    (certify.REPORTS / 'published.json').write_text(json.dumps(evidence, indent=2) + '\n')
    print(json.dumps(evidence), flush=True)
    if os.environ.get('GITHUB_OUTPUT'):
        with open(os.environ['GITHUB_OUTPUT'], 'a') as output:
            output.write(f'evidence={str(bool(records)).lower()}\n')
    for compiler, runtime, analysis, failure in failures:
        report_failure(compiler, runtime, analysis, failure, issues)
    for number in passed_issues:
        gh('issue', 'close', str(number), '--comment', f'Published artifacts passed verification: {run_url()}')
    if failures:
        raise RuntimeError(f'{len(failures)} published-artifact compatibility checks failed')


def apply_evidence(data, evidence):
    if 'records' in evidence:
        for result in evidence['records']:
            if result['runtime'] not in data['runtime'] or (result['analysis'] and result['analysis'] not in data['analysis']):
                raise ValueError('Daily certification requires recorded published artifacts')
            registry.record(data, result)
        return data
    train, version = evidence['train'], evidence['version']
    if evidence['result'] != 'pass' or train not in ('runtime', 'analysis'):
        raise ValueError('Expected a passing release certification')
    registry.version(version)
    registry.versions(evidence['kotlin'])
    opposite = 'analysis' if train == 'runtime' else 'runtime'
    if any(v not in data[opposite] for v in evidence['counterparts']):
        raise ValueError('Unknown published counterpart')
    # Retries append the same verified facts after publication; they never replace facts.
    for versions in evidence['counterparts'].values():
        if not set(registry.versions(versions)) <= set(evidence['kotlin']):
            raise ValueError('Relationship contains an unverified compiler')
    if train == 'runtime':
        registry.record(data, {'result': 'pass', 'runtime': version, 'analysis': None, 'kotlin': evidence['kotlin']})
        for analysis, versions in evidence['counterparts'].items():
            registry.record(data, {'result': 'pass', 'runtime': version, 'analysis': analysis, 'kotlin': versions})
    else:
        if not evidence['counterparts']:
            raise ValueError('Analysis requires a tested published Runtime')
        for runtime, versions in evidence['counterparts'].items():
            registry.record(data, {'result': 'pass', 'runtime': runtime, 'analysis': version, 'kotlin': versions})
        data['analysis'][version]['kotlin'] = sorted(set(data['analysis'][version]['kotlin']) | set(evidence['kotlin']), key=registry.version)
    return data


def publish(evidence):
    repository = os.environ['GITHUB_REPOSITORY']
    for attempt in range(3):
        head = json.loads(gh('api', f'repos/{repository}/git/ref/heads/main'))['object']['sha']
        file = json.loads(gh('api', f'repos/{repository}/contents/compatibility/registry.json?ref={head}'))
        original = base64.b64decode(file['content']).decode()
        data = registry.validate(json.loads(original))
        content = json.dumps(apply_evidence(data, evidence), indent=2) + '\n'
        registry.validate(json.loads(content))
        if content == original:
            print('Verified compatibility facts are already recorded')
            break
        commit = {
            'query': 'mutation($input: CreateCommitOnBranchInput!) { createCommitOnBranch(input: $input) { commit { oid } } }',
            'variables': {'input': {'branch': {'repositoryNameWithOwner': repository, 'branchName': 'main'},
                'expectedHeadOid': head, 'message': {'headline': 'Record verified Kotlin compatibility', 'body': run_url()},
                'fileChanges': {'additions': [{'path': 'compatibility/registry.json',
                                             'contents': base64.b64encode(content.encode()).decode()}]}}}}
        try:
            response = json.loads(gh('api', 'graphql', payload=commit))
        except subprocess.CalledProcessError as failure:
            # gh exits nonzero even when GraphQL returns structured head-conflict errors.
            response = json.loads(failure.output)
            if not response.get('errors'):
                raise
        if response.get('errors'):
            latest = json.loads(gh('api', f'repos/{repository}/git/ref/heads/main'))['object']['sha']
            if latest != head and attempt < 2:
                continue
            raise RuntimeError(response['errors'])
        print(response['data']['createCommitOnBranch']['commit']['oid'])
        # An App-authenticated commit triggers the website's normal main push workflow.
        return
    # No new push occurs on an idempotent retry; dispatch the website explicitly.
    gh('api', f'repos/{repository}/actions/workflows/website.yml/dispatches', payload={'ref': 'main'},
       token=os.environ.get('GH_WORKFLOW_TOKEN'))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='command', required=True)
    scan_command = commands.add_parser('scan')
    scan_command.add_argument('--kotlin', default='')
    scan_command.add_argument('--java-installations', default='')
    publication = commands.add_parser('publish')
    publication.add_argument('--evidence', type=Path, required=True)
    release = commands.add_parser('record')
    release.add_argument('--evidence', type=Path, required=True)
    release.add_argument('--train', choices=('runtime', 'analysis'), required=True)
    release.add_argument('--version', required=True)
    release.add_argument('--revision', required=True)
    args = parser.parse_args()
    if args.command == 'scan':
        scan(args.kotlin, args.java_installations)
    else:
        evidence = json.loads(args.evidence.read_text())
        if args.command == 'record':
            if (evidence['result'], evidence['train'], evidence['version'], evidence['revision']) != ('pass', args.train, args.version, args.revision):
                raise ValueError('Certification does not match the published release')
            published = json.loads(gh('release', 'view', f'{args.train}-{args.version}', '--json', 'isDraft'))
            if published['isDraft']:
                raise ValueError('Release is not published')
        publish(evidence)

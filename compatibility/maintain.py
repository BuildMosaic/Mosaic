#!/usr/bin/env python3
"""Discover stable Kotlin releases and propose measured compatibility updates."""

import argparse
import base64
import json
import os
from pathlib import Path
import subprocess
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


def gh(*args, payload=None):
    return subprocess.check_output(['gh', *args, *(['--input', '-'] if payload is not None else [])],
                                   input=json.dumps(payload) if payload is not None else None,
                                   cwd=certify.ROOT, text=True).strip()


def propose(branch, title, path, content):
    if (certify.ROOT / path).read_text() == content:
        print('Compatibility mapping is unchanged')
        return
    base = os.environ['GITHUB_REF_NAME']
    repository = os.environ['GITHUB_REPOSITORY']
    head = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=certify.ROOT, text=True).strip()
    if json.loads(gh('api', f'repos/{repository}/git/ref/heads/{base}'))['object']['sha'] != head:
        raise ValueError('Base branch advanced; rerun compatibility against its new revision')
    gh('api', f'repos/{repository}/git/refs', payload={'ref': f'refs/heads/{branch}', 'sha': head})
    gh('api', 'graphql', payload={
        'query': 'mutation($input: CreateCommitOnBranchInput!) { createCommitOnBranch(input: $input) { commit { oid } } }',
        'variables': {'input': {'branch': {'repositoryNameWithOwner': repository, 'branchName': branch},
            'expectedHeadOid': head, 'message': {'headline': title}, 'fileChanges': {'additions': [
                {'path': path, 'contents': base64.b64encode(content.encode()).decode()}]}}}})
    body = f'Verified by {run_url()}. See the compatibility artifact for compiler and installation logs.\n\nReview before merging.'
    print(gh('pr', 'create', '--base', base, '--head', branch, '--title', title, '--body', body))


def run_url():
    return f"{os.environ['GITHUB_SERVER_URL']}/{os.environ['GITHUB_REPOSITORY']}/actions/runs/{os.environ['GITHUB_RUN_ID']}"


def discover():
    _, mapping = certify.compiler_profiles()
    pages = json.loads(gh('api', 'repos/{owner}/{repo}/issues?state=open&per_page=100', '--paginate', '--slurp'))
    active = {item['title'] for page in pages for item in page}
    pending = [v for v in central_versions() if v not in mapping and f'Kotlin compatibility: {v}' not in active and available(v)]
    print(json.dumps(pending))
    with open(os.environ['GITHUB_OUTPUT'], 'a') as output:
        output.write('versions=' + json.dumps(pending) + '\n')


def investigate(version, java_installations):
    if registry.version(version) < (2, 2, 0):
        raise ValueError('Kotlin support starts at 2.2.0')
    original = certify.MAPPING.read_text()
    default, mapping = certify.compiler_profiles()
    # Try the nearest demonstrated profile first; add a shared-source API target only if needed.
    target = registry.version(version)
    nearest = sorted(mapping, key=lambda v: tuple(abs(a - b) for a, b in zip(registry.version(v), target)))
    profiles = list(dict.fromkeys([mapping[v] for v in nearest] + [version]))
    check = certify.Certification(java_installations)
    failures = []
    try:
        for api in profiles:
            tentative = {**mapping, version: api}
            text = original.splitlines()[0] + '\ndefault=' + default + '\n'
            for profile in sorted(set(tentative.values()), key=registry.version):
                text += profile + '=' + ','.join(sorted((v for v in tentative if tentative[v] == profile), key=registry.version)) + '\n'
            certify.MAPPING.write_text(text)
            check.mapping = tentative
            try:
                check.build('all')
                check.check([version], 'all')
            except (subprocess.SubprocessError, OSError, ValueError, RuntimeError, KeyError) as failure:
                failures.append(f'API {api}: {failure}')
                continue
            (certify.REPORTS / f'mapping-{version}.properties').write_text(text)
            return
        raise RuntimeError('\n'.join(failures))
    finally:
        certify.MAPPING.write_text(original)


def record(evidence, train, version, revision):
    registry.version(version)
    result = json.loads(evidence.read_text())
    if (result['result'], result['train'], result['version'], result['revision']) != ('pass', train, version, revision):
        raise ValueError('Certification does not match the published release')
    data = registry.load()
    registry.versions(result['kotlin'])
    opposite = 'analysis' if train == 'runtime' else 'runtime'
    if any(v not in data[opposite] for v in result['counterparts']):
        raise ValueError('Unknown published counterpart')
    if version in data[train]:
        raise ValueError('Release already recorded; do not replace published compatibility facts')
    for kotlin in result['counterparts'].values():
        if not set(registry.versions(kotlin)) <= set(result['kotlin']):
            raise ValueError('Relationship contains an unverified compiler')
    if train == 'runtime':
        data['runtime'][version] = result['kotlin']
        for analysis, kotlin in result['counterparts'].items():
            if not set(kotlin) <= set(data['analysis'][analysis]['kotlin']):
                raise ValueError('Relationship exceeds published Analysis admission')
            data['analysis'][analysis]['runtime'][version] = kotlin
    else:
        if not result['counterparts']:
            raise ValueError('Analysis requires a tested published Runtime')
        for runtime, kotlin in result['counterparts'].items():
            data['runtime'][runtime] = sorted(set(data['runtime'][runtime]) | set(kotlin), key=registry.version)
        data['analysis'][version] = {'kotlin': result['kotlin'], 'runtime': result['counterparts']}
    return json.dumps(data, indent=2) + '\n'


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='command', required=True)
    commands.add_parser('discover')
    investigation = commands.add_parser('investigate')
    investigation.add_argument('--kotlin', required=True)
    investigation.add_argument('--java-installations', default='')
    proposal = commands.add_parser('propose')
    proposal.add_argument('--kotlin', required=True)
    release = commands.add_parser('record')
    release.add_argument('--evidence', type=Path, required=True)
    release.add_argument('--train', choices=('runtime', 'analysis'), required=True)
    release.add_argument('--version', required=True)
    release.add_argument('--revision', required=True)
    args = parser.parse_args()
    if args.command == 'discover':
        discover()
    elif args.command == 'investigate':
        investigate(args.kotlin, args.java_installations)
    elif args.command == 'propose':
        registry.version(args.kotlin)
        propose(f'automation/kotlin-{args.kotlin}-{os.environ["GITHUB_RUN_ID"]}-{os.environ.get("GITHUB_RUN_ATTEMPT", "1")}', f'Kotlin compatibility: {args.kotlin}',
                str(certify.MAPPING.relative_to(certify.ROOT)),
                (certify.REPORTS / f'mapping-{args.kotlin}.properties').read_text())
    else:
        published = json.loads(gh('release', 'view', f'{args.train}-{args.version}', '--json', 'isDraft'))
        if published['isDraft']:
            raise ValueError('Release is not published')
        content = record(args.evidence, args.train, args.version, args.revision)
        propose(f'automation/compatibility-{args.train}-{args.version}-{os.environ["GITHUB_RUN_ID"]}-{os.environ.get("GITHUB_RUN_ATTEMPT", "1")}',
                f'Record {args.train} {args.version} compatibility', 'compatibility/registry.json', content)

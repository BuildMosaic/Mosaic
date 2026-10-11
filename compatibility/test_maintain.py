import base64
import copy
import os
import subprocess
from datetime import datetime, timezone
import json
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

import maintain


class DiscoveryTest(unittest.TestCase):
    def test_only_exact_stable_versions_from_authoritative_metadata(self):
        versions = ['2.4.21', '2.4.22-RC1', '2.5.0-Beta2', '2.5.0-EAP', '2.5.0-dev-123',
                    '2.5.0-SNAPSHOT', 'latest', '2.4', '02.4.21', '2.4.21', '2.5.0']
        metadata = '<metadata><versioning><versions>' + ''.join(
            f'<version>{v}</version>' for v in versions) + '</versions></versioning></metadata>'
        self.assertEqual(['2.4.21', '2.5.0'], maintain.stable_versions(metadata))

    def test_backports_new_families_policy_artifact_availability_and_handled_versions(self):
        versions = ['2.1.0', '2.1.10', '2.2.0', '2.2.21', '2.2.22', '2.3.0', '2.3.21',
                    '2.4.0', '2.4.21', '2.4.22', '2.5.0', '2.5.10']
        mapping = {'2.2.0': '2.3.0', '2.2.21': '2.3.0', '2.4.21': '2.4.20'}
        def headers(artifact, version, extension):
            return {'Last-Modified': 'Mon, 01 Sep 2025 00:00:00 GMT' if version == '2.5.0'
                    else 'Mon, 01 Jan 2024 00:00:00 GMT'}
        with patch('maintain.artifact_headers', side_effect=headers), \
                patch('maintain.available', side_effect=lambda v: v != '2.5.10') as available:
            result = maintain.pending_versions(versions, mapping, {'2.4.22'},
                                               datetime(2026, 10, 10, tzinfo=timezone.utc))
        self.assertEqual(['2.2.22', '2.4.0', '2.5.0'], result)
        self.assertNotIn('2.4.22', [call.args[0] for call in available.call_args_list])
        self.assertEqual({'2.2.0', '2.2.21', '2.4.21'}, set(mapping))

    def test_requires_both_compiler_and_kgp_poms_and_jars(self):
        for missing in [(a, e) for a in maintain.ARTIFACTS for e in ('pom', 'jar')]:
            with patch('maintain.artifact_headers', side_effect=lambda a, v, e: None if (a, e) == missing else {}):
                self.assertFalse(maintain.available('2.5.0'))
        with patch('maintain.artifact_headers', return_value={}):
            self.assertTrue(maintain.available('2.5.0'))

    def test_no_new_release_does_not_construct_harness(self):
        with patch('maintain.central_versions', return_value=['2.4.21']), \
                patch('maintain.certify.compiler_profiles', return_value=('2.4.20', {'2.4.21': '2.4.20'})), \
                patch('maintain.handled_versions', return_value=set()), patch('maintain.investigate') as investigate:
            maintain.discover('jdk17')
        investigate.assert_not_called()

    def test_closed_and_open_proposals_and_failure_issues_prevent_retesting(self):
        responses = [json.dumps([[{'title': 'Kotlin compatibility: 2.4.22'}, {'title': 'Unrelated'}]]),
                     json.dumps([[{'title': 'Kotlin compatibility: 2.5.0'}]])]
        with patch('maintain.gh', side_effect=responses):
            self.assertEqual({'2.4.22', '2.5.0'}, maintain.handled_versions())

    def test_a_failed_consumer_does_not_rebuild_the_batch_runtime_artifacts(self):
        runtime = SimpleNamespace(version='2.4.22', report={'runtimeArtifacts': [{'sha256': 'unchanged'}]})
        failed = {'runtime': 'fail', 'analysis': 'not-run', 'attempts': [], 'diagnostic': 'compiler failure'}
        passed = {'runtime': 'pass', 'analysis': 'pass', 'attempts': [], 'api': '2.4.20',
                  'newProfile': False, 'mapping': 'verified mapping'}
        with tempfile.TemporaryDirectory() as directory, patch('maintain.ROOT', Path(directory)), \
                patch('maintain.certify.compiler_profiles', return_value=('2.4.20', {'2.4.21': '2.4.20'})), \
                patch('maintain.central_versions', return_value=[]), patch('maintain.handled_versions', return_value=set()), \
                patch('maintain.pending_versions', return_value=['2.4.22', '2.4.30']), \
                patch('maintain.certify.Harness', return_value=runtime) as build, \
                patch('maintain.investigate', side_effect=[failed, passed]) as investigate, \
                patch('maintain.evidence_url', return_value='actions-evidence'), \
                patch('maintain.gh', return_value='failure-issue'), patch('maintain.propose'):
            maintain.discover('jdk17')
        self.assertEqual(1, build.call_count)
        self.assertEqual(['2.4.22', '2.4.30'], [call.args[0] for call in investigate.call_args_list])
        self.assertTrue(all(call.args[2] is runtime for call in investigate.call_args_list))


class InvestigationTest(unittest.TestCase):
    def investigation(self, outcomes, installation_failure=False):
        original = '# Mapping\ndefault=2.4.20\n2.3.0=2.2.0\n2.4.20=2.4.21\n'
        harnesses = []
        def candidate(version, scope, java, probe=False, api='', runtime=None):
            h = SimpleNamespace(directory=Path(f'evidence-{len(harnesses)}'),
                                report={'failureDiagnostics': ['actual ABI failure']}, finish=lambda: 0,
                                api=api, scope=scope, probe=probe, runtime=runtime)
            harnesses.append(h)
            return h
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            path = root / maintain.MAPPING
            path.parent.mkdir(parents=True)
            path.write_text(original)
            with patch('maintain.ROOT', root), \
                    patch('maintain.certify.compiler_profiles', return_value=('2.4.20', {'2.2.0': '2.3.0', '2.4.21': '2.4.20'})), \
                    patch('maintain.certify.Harness', side_effect=candidate), \
                    patch('maintain.certify.certify', side_effect=outcomes), \
                    patch('maintain.certify.production_installation',
                          side_effect=RuntimeError('installation semantic failure') if installation_failure else None,
                          return_value={'tests': 1, 'failed': 0, 'skipped': 0}):
                result = maintain.investigate('2.4.22', 'jdk17')
            self.assertEqual(original, path.read_text())
        return result, harnesses

    def test_reuses_nearest_passing_profile_then_checks_production_with_identical_runtime(self):
        result, harnesses = self.investigation([0, 0, 0])
        self.assertEqual('pass', result['analysis'])
        self.assertEqual('2.4.20', result['api'])
        self.assertFalse(result['newProfile'])
        self.assertEqual(['runtime', 'analysis', 'analysis'], [h.scope for h in harnesses])
        self.assertTrue(harnesses[1].probe)
        self.assertFalse(harnesses[2].probe)
        self.assertIs(harnesses[0], harnesses[1].runtime)
        self.assertIs(harnesses[0], harnesses[2].runtime)
        self.assertIn('2.4.20=2.4.21,2.4.22', result['mapping'])

    def test_only_attempts_new_shared_source_api_after_every_existing_profile_fails(self):
        result, harnesses = self.investigation([0, 1, 1, 0, 0])
        self.assertTrue(result['newProfile'])
        self.assertEqual(['2.4.20', '2.3.0', '2.4.22', '2.4.22'], [h.api for h in harnesses[1:]])
        self.assertIn('2.4.22=2.4.22', result['mapping'])

    def test_probe_success_is_insufficient_and_failures_never_update_mapping(self):
        for outcomes, installation_failure in [([1], False), ([0, 1, 1, 1], False),
                                                ([0, 0, 1, 1, 1], False), ([0, 0, 0, 1, 1], True)]:
            result, _ = self.investigation(outcomes, installation_failure)
            self.assertNotIn('mapping', result)
            self.assertNotEqual('pass', result['analysis'])

    def test_new_compilers_consume_the_prepared_runtime_candidate(self):
        candidate = SimpleNamespace(version='2.4.21')
        with patch('maintain.certify.Harness', side_effect=RuntimeError('stop after construction')) as harness:
            with self.assertRaisesRegex(RuntimeError, 'stop after construction'):
                maintain.investigate('2.4.22', 'jdk17', candidate)
        harness.assert_called_once_with('2.4.22', 'runtime', 'jdk17', runtime_candidate=candidate)

    def test_generated_mapping_is_sorted_and_idempotent(self):
        text = 'default=2.4.20\n2.4.20=2.4.0,2.4.21\n'
        updated = maintain.admit(text, '2.4.10', '2.4.20')
        self.assertIn('2.4.0,2.4.10,2.4.21', updated)
        self.assertEqual(updated, maintain.admit(updated, '2.4.10', '2.4.20'))


class ProposalTest(unittest.TestCase):
    branch = 'automation/kotlin-2.5.0'
    title = 'Kotlin compatibility: 2.5.0'

    def invoke(self, responses, revision='base'):
        with patch.dict(os.environ, {'GITHUB_REPOSITORY': 'BuildMosaic/Mosaic'}), \
                patch('maintain.gh', side_effect=responses) as gh, \
                patch('maintain.subprocess.check_output', return_value=revision):
            maintain.propose(self.branch, self.title, 'Evidence link', {'mapping': 'verified'})
        return gh

    def test_existing_pr_or_report_has_no_mutations(self):
        gh = self.invoke([json.dumps([{'url': 'existing-pr'}])])
        self.assertEqual(1, gh.call_count)
        gh = self.invoke(['[]', json.dumps([{'title': self.title, 'url': 'existing-issue'}])])
        self.assertEqual(2, gh.call_count)

    def test_proposal_commit_uses_tested_revision_and_verified_changes(self):
        responses = ['[]', '[]', '{"object":{"sha":"base"}}', '[]', '{"object":{"sha":"base"}}',
                     '{"data":{"createCommitOnBranch":{"commit":{"signature":{"isValid":true}}}}}', 'new-pr']
        gh = self.invoke(responses)
        mutation = gh.call_args_list[5].kwargs['payload']['variables']['input']
        self.assertEqual('base', mutation['expectedHeadOid'])
        self.assertEqual(self.branch, mutation['branch']['branchName'])
        self.assertEqual('verified', base64.b64decode(mutation['fileChanges']['additions'][0]['contents']).decode())

    def test_main_race_or_unverified_signature_cannot_create_pr(self):
        with self.assertRaisesRegex(ValueError, 'main advanced'):
            self.invoke(['[]', '[]', '{"object":{"sha":"new-base"}}'])
        with self.assertRaisesRegex(ValueError, 'signature'):
            self.invoke(['[]', '[]', '{"object":{"sha":"base"}}', '[]', '{"object":{"sha":"base"}}',
                         '{"data":{"createCommitOnBranch":{"commit":{"signature":{"isValid":false}}}}}'])

    def test_token_rejection_leaves_verified_branch_and_actionable_issue(self):
        responses = ['[]', '[]', '{"object":{"sha":"base"}}', '[]', '{"object":{"sha":"base"}}',
                     '{"data":{"createCommitOnBranch":{"commit":{"signature":{"isValid":true}}}}}',
                     subprocess.CalledProcessError(1, 'gh', stderr='GitHub Actions is not permitted to create pull requests'),
                     'failure-issue']
        gh = self.invoke(responses)
        issue = gh.call_args_list[-1].args
        self.assertEqual(('issue', 'create'), issue[:2])
        self.assertIn('Allow GitHub Actions to create and approve pull requests', issue[-1])
        self.assertIn(self.branch, issue[-1])

    def test_interrupted_proposal_reuses_signed_identical_commit(self):
        ref = {'ref': 'refs/heads/' + self.branch, 'object': {'sha': 'existing-commit'}}
        responses = ['[]', '[]', '{"object":{"sha":"base"}}', json.dumps([ref]),
                     '{"parents":[{"sha":"base"}],"commit":{"verification":{"verified":true}}}',
                     json.dumps({'content': base64.b64encode(b'verified').decode()}), 'new-pr']
        gh = self.invoke(responses)
        self.assertFalse(any('graphql' in call.args for call in gh.call_args_list))
        responses[-2] = json.dumps({'content': base64.b64encode(b'human-edited').decode()})
        with self.assertRaisesRegex(ValueError, 'refusing to overwrite'):
            self.invoke(responses)


class PublishedReleaseGateTest(unittest.TestCase):
    def arguments(self, version='1.0.0'):
        return ['maintain.py', 'registry', '--evidence', 'unused-evidence.json', '--train', 'runtime',
                '--version', version, '--revision', 'released-sha']

    def test_prerelease_publication_never_populates_registry(self):
        with patch('sys.argv', self.arguments('1.0.0-RC1')), patch('maintain.gh') as gh, \
                patch('maintain.propose') as propose:
            maintain.main()
        gh.assert_not_called()
        propose.assert_not_called()

    def test_missing_or_draft_github_release_blocks_proposal_before_evidence_is_used(self):
        for response in [subprocess.CalledProcessError(1, 'gh', stderr='release not found'),
                         '{"isDraft":true,"url":"draft"}']:
            with patch('sys.argv', self.arguments()), patch('maintain.gh', side_effect=[response]), \
                    patch('maintain.release_update') as update, patch('maintain.propose') as propose:
                with self.assertRaises((ValueError, subprocess.CalledProcessError)):
                    maintain.main()
            update.assert_not_called()
            propose.assert_not_called()


class ReleaseRegistryTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.versions = ['2.2.0', '2.2.21', '2.3.0', '2.4.21']
        self.reports = []
        for v in self.versions:
            path = Path(self.temp.name) / f'{v}.json'
            report = {'requestedCompilerVersion': v, 'actualCompilerVersion': v, 'scope': 'runtime',
                      'sourceRevision': 'released-sha', 'harnessResult': 'pass', 'compatibilityProbe': False,
                      'environment': {'java17Version': '17.0.15'}, 'areas': {'runtime': {'result': 'pass'}},
                      'runtimeArtifacts': [{'coordinate': 'unchanged-runtime', 'sha256': 'same-bytes'}]}
            path.write_text(json.dumps(report))
            self.reports.append(str(path))
        self.evidence = {'result': 'pass', 'scope': 'runtime', 'releaseVersion': '1.0.0',
                         'sourceRevision': 'released-sha', 'compilers': self.versions, 'reports': self.reports}
        self.empty = {'schemaVersion': 1, 'latestCertifiedKotlin': None, 'runtime': {}, 'analysis': {}}

    def update(self, current=None, evidence=None, versions=None, train='runtime'):
        return maintain.release_update(current or self.empty, evidence or self.evidence, train, '1.0.0',
                                       'released-sha', versions or self.versions)

    def test_successful_released_evidence_generates_minimal_entry_and_idempotent_changes(self):
        result = self.update()
        self.assertEqual({'1.0.0': {'minKotlin': '2.2.0'}}, result['runtime'])
        self.assertEqual('2.4.21', result['latestCertifiedKotlin'])
        self.assertEqual({}, result['analysis'])
        self.assertEqual(result, self.update(current=result))

    def test_frontier_never_expands_older_runtime_or_analysis_guarantees(self):
        current = copy.deepcopy(self.empty)
        current.update(latestCertifiedKotlin='2.3.0', runtime={'0.9.0': {'minKotlin': '2.2.0'}},
                       analysis={'0.9.0': {'minKotlin': '2.2.0', 'minRuntime': '0.9.0'}})
        before = copy.deepcopy(current)
        result = self.update(current=current)
        self.assertFalse(maintain.registry.compatible(result, '0.9.0', '2.4.21'))
        self.assertEqual('2.3.0', result['runtime']['0.9.0']['maxKotlin'])
        self.assertEqual('2.3.0', result['analysis']['0.9.0']['maxKotlin'])
        self.assertTrue(maintain.registry.compatible(result, '0.9.0', '2.3.0', '0.9.0'))
        self.assertEqual(before, current)

    def test_failures_probe_version_revision_unexecuted_areas_and_holes_are_rejected(self):
        for key, value in [('result', 'fail'), ('releaseVersion', '1.0.1'), ('sourceRevision', 'dev-sha'),
                           ('compilers', self.versions[:-1])]:
            evidence = {**self.evidence, key: value}
            with self.assertRaises(ValueError):
                self.update(evidence=evidence)
        with self.assertRaisesRegex(ValueError, 'Uncertified stable compiler'):
            self.update(versions=self.versions + ['2.3.21'])
        path = Path(self.reports[0])
        original = json.loads(path.read_text())
        for key, value in [('compatibilityProbe', True), ('actualCompilerVersion', '2.4.21'),
                           ('harnessResult', 'fail'), ('areas', {'runtime': {'result': 'not-run'}}),
                           ('runtimeArtifacts', [{'coordinate': 'different', 'sha256': 'different'}]),
                           ('environment', {'java17Version': '21.0.1'})]:
            path.write_text(json.dumps({**original, key: value}))
            with self.assertRaises(ValueError):
                self.update()
        path.write_text(json.dumps(original))

    def test_analysis_requires_real_production_installation_and_no_synthetic_relationship_claim(self):
        evidence = {**self.evidence, 'scope': 'analysis'}
        for path in self.reports:
            report = json.loads(Path(path).read_text())
            report.update(scope='analysis', areas={a: {'result': 'pass'} for a in maintain.certify.AREAS[1:]})
            Path(path).write_text(json.dumps(report))
        for result in [{}, {'tests': 0, 'failed': 0, 'skipped': 0}, {'tests': 1, 'failed': 0, 'skipped': 1}]:
            with self.assertRaisesRegex(ValueError, 'Production installation'):
                self.update(evidence={**evidence, 'productionInstallation': result}, train='analysis')
        updated = self.update(evidence={**evidence, 'productionInstallation': {'tests': 1, 'failed': 0, 'skipped': 0}},
                              train='analysis')
        self.assertEqual({'minKotlin': '2.2.0'}, updated['analysis']['1.0.0'])

    def test_older_certification_does_not_inherit_a_newer_frontier(self):
        current = {**self.empty, 'latestCertifiedKotlin': '2.5.0'}
        self.assertEqual({'minKotlin': '2.2.0', 'maxKotlin': '2.4.21'}, self.update(current=current)['runtime']['1.0.0'])

    def test_unknown_historical_support_is_not_manufactured(self):
        current = {**self.empty, 'runtime': {'0.9.0': {'minKotlin': '2.2.0'}}}
        with self.assertRaisesRegex(ValueError, 'historical support'):
            self.update(current=current)


if __name__ == '__main__':
    unittest.main()

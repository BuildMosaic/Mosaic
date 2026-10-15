"""Registry claims and automation boundaries; Kotlin fixtures own compiler verification."""

import base64
from copy import deepcopy
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import subprocess
from tempfile import TemporaryDirectory
import unittest
from unittest.mock import call, patch

import certify
import maintain
from registry import apply_evidence, compatible, validate


def interval(lower='2.2.0', upper='2.4.21'):
    return {'minKotlin': lower, 'maxKotlin': upper}


def published():
    return {'runtime': {'1.0.0': interval(), '1.1.0': interval()},
            'analysis': {'1.0.0': {'1.0.0': interval()}}}


class CompatibilityClaimsTest(unittest.TestCase):
    def test_inclusive_numeric_ranges_and_only_certified_pairs(self):
        data = validate(published())
        for kotlin in ['2.2.0', '2.2.9', '2.3.99', '2.4.21']:
            self.assertTrue(compatible(data, '1.0.0', kotlin, '1.0.0'))
        for r, k, a in [('1.0.0', '2.1.99', None), ('1.0.0', '2.4.22', '1.0.0'),
                        ('1.1.0', '2.2.0', '1.0.0'), ('9.0.0', '2.2.0', None),
                        ('1.0.0', '2.2.0', '9.0.0')]:
            self.assertFalse(compatible(data, r, k, a))

    def test_exhaustive_published_release_results_become_two_ranges(self):
        compilers = ['2.2.0', '2.2.10', '2.2.20', '2.2.21', '2.3.0', '2.3.10',
                     '2.3.20', '2.3.21', '2.4.0', '2.4.10', '2.4.20', '2.4.21']
        data = {'runtime': {}, 'analysis': {}}
        runtime = {'train': 'runtime', 'version': '1.0.0', 'result': 'pass',
                   'kotlin': compilers, 'counterparts': {}}
        analysis = {**runtime, 'train': 'analysis', 'counterparts': {'1.0.0': compilers}}
        apply_evidence(data, runtime)
        apply_evidence(data, analysis)
        self.assertEqual(validate(data), {'runtime': {'1.0.0': interval()},
                                         'analysis': {'1.0.0': {'1.0.0': interval()}}})
        original = deepcopy(data)
        apply_evidence(data, runtime)
        apply_evidence(data, analysis)
        self.assertEqual(data, original)

    def test_independent_releases_record_only_the_exercised_pair_range(self):
        data = published()
        runtime = {'train': 'runtime', 'version': '1.2.0', 'result': 'pass',
                   'kotlin': ['2.2.0', '2.3.0', '2.4.21'], 'counterparts': {'1.0.0': ['2.2.0', '2.3.0']}}
        apply_evidence(data, runtime)
        self.assertTrue(compatible(validate(data), '1.2.0', '2.4.21'))
        self.assertTrue(compatible(data, '1.2.0', '2.3.0', '1.0.0'))
        self.assertFalse(compatible(data, '1.2.0', '2.4.21', '1.0.0'))
        self.assertFalse(compatible(data, '1.1.0', '2.2.0', '1.0.0'))

    def test_daily_extension_preserves_historical_bounds_and_is_idempotent(self):
        data = published()
        evidence = {'records': [{'result': 'pass', 'runtime': '1.0.0', 'analysis': '1.0.0',
                                 'kotlin': ['2.1.21', '2.4.22']}]}
        apply_evidence(data, evidence)
        self.assertEqual(validate(data)['analysis']['1.0.0']['1.0.0'], interval('2.1.21', '2.4.22'))
        original = deepcopy(data)
        apply_evidence(data, evidence)
        apply_evidence(data, {'records': [{'result': 'pass', 'runtime': '1.0.0', 'analysis': None,
                                         'kotlin': ['2.3.0']}]})
        self.assertEqual(data, original)
        self.assertEqual(data['runtime']['1.1.0'], interval())

    def test_invalid_ranges_and_relationships_are_rejected(self):
        for bounds in [interval('2.4.21', '2.2.0'), interval('2.2.0-RC1'),
                       {'minKotlin': '2.2.0'}, ['2.2.0', '2.4.21']]:
            with self.subTest(bounds=bounds), self.assertRaises(ValueError):
                validate({'runtime': {'1.0.0': bounds}, 'analysis': {}})
        for pairings in [{}, {'9.0.0': interval()}, {'1.0.0': interval('2.2.0', '2.5.0')}]:
            with self.subTest(pairings=pairings), self.assertRaises(ValueError):
                validate({**published(), 'analysis': {'1.0.0': pairings}})

    def test_failed_or_untested_evidence_cannot_establish_support(self):
        data = published()
        release = {'train': 'analysis', 'version': '1.1.0', 'result': 'pass',
                   'kotlin': ['2.2.0'], 'counterparts': {'1.0.0': ['2.2.0']}}
        for changes in [{'result': 'fail'}, {'counterparts': {}},
                        {'counterparts': {'9.0.0': ['2.2.0']}},
                        {'counterparts': {'1.0.0': ['2.4.21']}}]:
            with self.subTest(changes=changes), self.assertRaises(ValueError):
                apply_evidence(deepcopy(data), {**release, **changes})
        daily = {'result': 'pass', 'runtime': '1.0.0', 'analysis': '1.0.0', 'kotlin': ['2.5.0']}
        for changes in [{'result': 'fail'}, {'runtime': '9.0.0'}, {'runtime': '1.1.0'}, {'analysis': '9.0.0'}]:
            with self.subTest(changes=changes), self.assertRaises(ValueError):
                apply_evidence(deepcopy(data), {'records': [{**daily, **changes}]})
        original = deepcopy(data)
        with self.assertRaises(ValueError):
            apply_evidence(data, {'records': [daily, {**daily, 'result': 'fail'}]})
        self.assertEqual(data, original)


class MaintenanceTest(unittest.TestCase):
    def setUp(self):
        directory = TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.reports = Path(directory.name)
        self.data = published()
        self.mocks = {}
        for target, options in {
            'maintain.registry.load': {'return_value': self.data},
            'maintain.open_issues': {'return_value': {}},
            'maintain.certify.central_versions': {'return_value': ['2.2.0', '2.4.21', '2.4.22']},
            'maintain.supported_versions': {'side_effect': lambda versions: versions},
            'maintain.available': {'return_value': True},
            'maintain.certify.Certification': {}, 'maintain.report_failure': {}, 'maintain.gh': {},
            'maintain.run_url': {'return_value': 'https://example.test/run'},
        }.items():
            patcher = patch(target, **options)
            self.mocks[target.rsplit('.', 1)[1]] = patcher.start()
            self.addCleanup(patcher.stop)
        patcher = patch.object(certify, 'REPORTS', self.reports)
        patcher.start()
        self.addCleanup(patcher.stop)
        self.check = self.mocks['Certification'].return_value
        self.check.revision = 'source-sha'

    def evidence(self):
        return json.loads((self.reports / 'published.json').read_text())

    def test_daily_rechecks_in_range_backports_and_extends_established_pairs(self):
        self.mocks['central_versions'].return_value = ['2.2.0', '2.2.1', '2.4.21', '2.4.22']
        maintain.scan('', '')
        self.check.analysis.assert_any_call(['2.2.1'], '1.0.0', '1.0.0')
        self.check.runtime.assert_any_call(['2.2.1'], '1.0.0')
        self.check.runtime.assert_any_call(['2.2.1'], '1.1.0')
        apply_evidence(self.data, self.evidence())
        self.assertTrue(compatible(validate(self.data), '1.0.0', '2.4.22', '1.0.0'))
        self.assertFalse(compatible(self.data, '1.1.0', '2.4.22', '1.0.0'))
        self.assertEqual(len(self.evidence()['records']), 3)

    def test_failure_inside_range_blocks_later_boundary_success_but_other_pairs_advance(self):
        self.mocks['central_versions'].return_value = ['2.2.0', '2.2.1', '2.4.22']
        def fail(versions, *_):
            if versions == ['2.2.1']:
                raise RuntimeError('backport')
        self.check.analysis.side_effect = fail
        with self.assertRaises(RuntimeError):
            maintain.scan('', '')
        self.mocks['report_failure'].assert_called_once()
        apply_evidence(self.data, self.evidence())
        self.assertEqual(self.data['analysis']['1.0.0']['1.0.0'], interval())
        self.assertEqual(self.data['runtime']['1.0.0'], interval(upper='2.4.22'))

    def test_runtime_failure_blocks_dependent_pair(self):
        def fail(versions, runtime):
            if versions == ['2.2.0'] and runtime == '1.0.0':
                raise RuntimeError('runtime')
        self.check.runtime.side_effect = fail
        with self.assertRaises(RuntimeError):
            maintain.scan('', '')
        self.assertEqual([(r['runtime'], r['analysis']) for r in self.evidence()['records']], [('1.1.0', None)])
        self.assertEqual(self.mocks['report_failure'].call_count, 2)

    def test_unavailable_intervening_release_cannot_be_skipped(self):
        self.mocks['available'].side_effect = lambda compiler: compiler != '2.4.21'
        maintain.scan('', '')
        self.assertEqual(self.evidence(), {'records': []})

    def test_open_failure_holds_bounds_without_automatic_retry_and_explicit_retry_closes_it(self):
        title = maintain.issue_title('2.4.22', '1.0.0', '1.0.0')
        self.mocks['open_issues'].return_value = {title: {'number': 42}}
        maintain.scan('', '')
        self.assertFalse(any(r['analysis'] for r in self.evidence()['records']))
        self.mocks['gh'].assert_not_called()
        maintain.scan('2.4.22', '')
        self.mocks['gh'].assert_called_once_with('issue', 'close', '42', '--comment',
                                               'Published artifacts passed verification: https://example.test/run')
        self.assertTrue(any(r['analysis'] for r in self.evidence()['records']))

    def test_open_runtime_failure_holds_pair_bounds_without_indirect_retry(self):
        title = maintain.issue_title('2.4.22', '1.0.0', None)
        self.mocks['open_issues'].return_value = {title: {'number': 42}}
        maintain.scan('', '')
        self.assertEqual([(r['runtime'], r['analysis']) for r in self.evidence()['records']], [('1.1.0', None)])
        self.assertNotIn(call(['2.4.22'], '1.0.0'), self.check.runtime.call_args_list)

    def test_exact_retry_checks_intervening_upper_and_lower_releases(self):
        self.mocks['central_versions'].return_value = ['2.2.0', '2.4.21', '2.4.22', '2.5.0']
        maintain.scan('2.5.0', '')
        self.check.analysis.assert_has_calls([call(['2.4.22'], '1.0.0', '1.0.0'),
                                              call(['2.5.0'], '1.0.0', '1.0.0')])
        self.check.reset_mock()
        self.mocks['central_versions'].return_value = ['2.2.0', '2.3.0', '2.4.21']
        self.data['analysis']['1.0.0']['1.0.0'] = interval('2.4.21', '2.4.21')
        maintain.scan('2.2.0', '')
        self.check.analysis.assert_has_calls([call(['2.2.0'], '1.0.0', '1.0.0'),
                                              call(['2.3.0'], '1.0.0', '1.0.0')])
        self.assertEqual(next(r['kotlin'] for r in self.evidence()['records'] if r['analysis']), ['2.2.0', '2.3.0'])

    def test_resumed_maintenance_checks_intervening_expired_versions_without_erasing_history(self):
        self.mocks['central_versions'].return_value = ['2.2.0', '2.4.21', '2.4.22', '2.5.0']
        self.mocks['supported_versions'].side_effect = lambda _: ['2.5.0']
        maintain.scan('', '')
        self.check.analysis.assert_any_call(['2.4.22'], '1.0.0', '1.0.0')
        apply_evidence(self.data, self.evidence())
        self.assertEqual(self.data['analysis']['1.0.0']['1.0.0'], interval(upper='2.5.0'))

    def test_exact_historical_retry_rechecks_published_artifacts(self):
        self.mocks['supported_versions'].side_effect = lambda _: []
        maintain.scan('2.2.0', '')
        self.check.runtime.assert_any_call(['2.2.0'], '1.0.0')
        self.check.analysis.assert_called_once_with(['2.2.0'], '1.0.0', '1.0.0')
        original = deepcopy(self.data)
        apply_evidence(self.data, self.evidence())
        self.assertEqual(self.data, original)


class CertificationTest(unittest.TestCase):
    def test_analysis_release_exhaustively_rechecks_runtime_including_backports(self):
        with TemporaryDirectory() as directory, patch.object(certify, 'REPORTS', Path(directory)), \
                patch.object(certify.registry, 'load', return_value=published()), \
                patch.object(certify, 'Certification') as factory:
            check = factory.return_value
            check.directory = Path(directory)
            check.revision = 'source-sha'
            compilers = ['2.2.0', '2.2.1', '2.4.21']
            result = certify.certify(compilers, 'analysis', release='1.2.0')
            check.runtime.assert_called_once_with(compilers, '1.1.0')
            check.analysis.assert_called_once_with(compilers, '1.1.0', '1.2.0')
            self.assertEqual(result['counterparts'], {'1.1.0': compilers})
            data = apply_evidence(published(), result)
            self.assertFalse(compatible(data, '1.0.0', '2.2.1', '1.2.0'))
            self.assertEqual(json.loads((Path(directory) / 'release-analysis.json').read_text()), result)

    def test_runtime_release_certifies_only_continuous_abi_coverage(self):
        with TemporaryDirectory() as directory, patch.object(certify, 'REPORTS', Path(directory)), \
                patch.object(certify.registry, 'load', return_value=published()), \
                patch.object(certify, 'Certification') as factory, \
                patch.object(certify, 'compiler_profiles') as profiles:
            check = factory.return_value
            check.directory = Path(directory)
            check.revision = 'source-sha'
            compilers = ['2.2.0', '2.3.0', '2.4.21']
            profiles.return_value = ('2.2.0', {'2.2.0': ((2, 2, 0), (2, 4, 0))})
            result = certify.certify(compilers, 'runtime', release='1.2.0')
            check.runtime.assert_called_once_with(compilers, '1.2.0')
            check.analysis.assert_called_once_with(compilers[:2], '1.2.0', '1.0.0')
            self.assertEqual(result['counterparts'], {'1.0.0': compilers[:2]})
            profiles.return_value = ('2.2.0', {'2.2.0': ((2, 2, 0), (2, 3, 0)),
                                             '2.4.0': ((2, 4, 0), (2, 5, 0))})
            with self.assertRaisesRegex(ValueError, 'continuous'):
                certify.certify(compilers, 'runtime', release='1.3.0')

    def test_failed_release_does_not_produce_passing_evidence(self):
        with TemporaryDirectory() as directory, patch.object(certify, 'REPORTS', Path(directory)), \
                patch.object(certify.registry, 'load', return_value=published()), \
                patch.object(certify, 'Certification') as factory:
            check = factory.return_value
            check.directory = Path(directory)
            check.runtime.side_effect = RuntimeError('compiler failure')
            with self.assertRaises(RuntimeError):
                certify.certify(['2.2.0'], 'runtime', release='1.2.0')
            self.assertFalse((Path(directory) / 'release-runtime.json').exists())

    def test_discovery_excludes_prereleases_and_orders_numeric_versions(self):
        with patch.object(certify.urllib.request, 'urlopen') as request:
            request.return_value.__enter__.return_value.read.return_value = b'''<metadata><versioning><versions>
                <version>2.1.21</version><version>2.2.0</version><version>2.2.9</version>
                <version>2.2.10</version><version>2.2.1</version><version>2.5.0-RC1</version>
                </versions></versioning></metadata>'''
            self.assertEqual(certify.central_versions(), ['2.2.0', '2.2.1', '2.2.9', '2.2.10'])

    def test_support_window_is_eighteen_calendar_months_from_family_release(self):
        with patch.object(maintain, 'datetime') as clock, patch.object(maintain, 'gh') as gh:
            clock.now.return_value = datetime(2026, 10, 15, tzinfo=timezone.utc)
            gh.side_effect = lambda *args: json.dumps({'published_at':
                '2025-04-15T00:00:00Z' if 'v2.2.0' in args[-1] else '2025-04-16T00:00:00Z'})
            self.assertEqual(maintain.supported_versions(['2.2.0', '2.2.1', '2.3.0', '2.3.1']),
                             ['2.3.0', '2.3.1'])


class RegistryPublicationTest(unittest.TestCase):
    def test_concurrent_writer_reloads_latest_registry_and_preserves_newer_certification(self):
        evidence = {'records': [{'result': 'pass', 'runtime': '1.1.0', 'analysis': None,
                                 'kotlin': ['2.4.22']}]}
        for conflict in ['graphql', 'process']:
            with self.subTest(conflict=conflict), patch.dict(os.environ, {'GITHUB_REPOSITORY': 'BuildMosaic/Mosaic'}), \
                    patch.object(maintain, 'run_url', return_value='https://example.test/run'), \
                    patch.object(maintain, 'gh') as gh:
                latest = published()
                latest['runtime']['1.0.0'] = interval(upper='2.5.0')
                latest['analysis']['1.1.0'] = {'1.1.0': interval()}
                commits = []
                heads = iter(['old', 'new', 'new'])
                def api(*args, payload=None, **kwargs):
                    if args[-1].endswith('ref/heads/main'):
                        return json.dumps({'object': {'sha': next(heads)}})
                    if '/contents/' in args[-1]:
                        data = published() if args[-1].endswith('ref=old') else latest
                        return json.dumps({'content': base64.b64encode((json.dumps(data, indent=2) + '\n').encode()).decode()})
                    commits.append(payload['variables']['input'])
                    if len(commits) == 1:
                        error = json.dumps({'errors': [{'message': 'head changed'}]})
                        if conflict == 'process':
                            raise subprocess.CalledProcessError(1, 'gh', output=error)
                        return error
                    return json.dumps({'data': {'createCommitOnBranch': {'commit': {'oid': 'committed'}}}})
                gh.side_effect = api
                maintain.publish(evidence)
                self.assertEqual([c['expectedHeadOid'] for c in commits], ['old', 'new'])
                written = json.loads(base64.b64decode(commits[-1]['fileChanges']['additions'][0]['contents']))
                self.assertEqual(validate(written)['runtime']['1.0.0'], interval(upper='2.5.0'))
                self.assertEqual(written['runtime']['1.1.0'], interval(upper='2.4.22'))
                self.assertEqual(written['analysis'], latest['analysis'])

    def test_idempotent_publication_dispatches_website_without_a_commit(self):
        data = published()
        original = json.dumps(data, indent=2) + '\n'
        with patch.dict(os.environ, {'GITHUB_REPOSITORY': 'BuildMosaic/Mosaic', 'GH_WORKFLOW_TOKEN': 'workflow-token'}), \
                patch.object(maintain, 'gh') as gh:
            gh.side_effect = [json.dumps({'object': {'sha': 'head'}}),
                              json.dumps({'content': base64.b64encode(original.encode()).decode()}), '']
            maintain.publish({'records': [{'result': 'pass', 'runtime': '1.0.0', 'analysis': None,
                                           'kotlin': ['2.2.0', '2.4.21']}]})
            self.assertEqual(gh.call_count, 3)
            gh.assert_called_with('api', 'repos/BuildMosaic/Mosaic/actions/workflows/website.yml/dispatches',
                                  payload={'ref': 'main'}, token='workflow-token')


if __name__ == '__main__':
    unittest.main()

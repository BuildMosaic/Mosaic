"""Selection boundaries for unattended published-artifact certification."""

from contextlib import ExitStack
from copy import deepcopy
from datetime import date
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import certify
import maintain
import registry


class ScanSelectionTest(unittest.TestCase):
    def scan(self, kotlin='', fail=False):
        compiler = kotlin or '2.4.21'
        data = registry.validate({
            'runtime': {'1.0.0': ['2.2.0', compiler], '1.1.0': ['2.2.0', compiler], '1.2.0': ['2.2.0']},
            'analysis': {
                '1.0.0': {'kotlin': ['2.2.0', compiler], 'runtime': {'1.0.0': ['2.2.0', compiler]}},
                '1.1.0': {'kotlin': ['2.2.0'], 'runtime': {'1.0.0': ['2.2.0'], '1.1.0': ['2.2.0']}},
            },
        })
        original = deepcopy(data)
        title = maintain.issue_title(compiler, '1.0.0', '1.1.0')

        def github(*args):
            if args[0] == 'api':
                dates = {'v2.2.0': '2024-01-01', 'v2.4.0': '2026-01-01'}
                return json.dumps({'published_at': dates[args[1].split('/')[-1]] + 'T12:00:00Z'})
            return ''

        def verify(compiler, runtime, analysis, *args, **kwargs):
            if fail and (runtime, analysis) == ('1.1.0', '1.1.0'):
                raise RuntimeError('Unverified published combination')
            return {'result': 'pass', 'runtime': runtime, 'analysis': analysis, 'kotlin': [compiler]}

        with tempfile.TemporaryDirectory() as directory, ExitStack() as stack:
            stack.enter_context(patch.dict('os.environ', {'GITHUB_OUTPUT': ''}))
            stack.enter_context(patch.object(maintain.registry, 'load', return_value=data))
            stack.enter_context(patch.object(maintain, 'open_issues', return_value={title: {'number': 12}}))
            discovery = stack.enter_context(patch.object(maintain, 'central_versions', return_value=['2.2.99', '2.4.21']))
            clock = stack.enter_context(patch.object(maintain, 'datetime'))
            clock.now.return_value.date.return_value = date(2026, 10, 14)
            stack.enter_context(patch.object(maintain, 'available', return_value=True))
            stack.enter_context(patch.object(maintain, 'run_url', return_value='https://example.invalid/run'))
            gh = stack.enter_context(patch.object(maintain, 'gh', side_effect=github))
            report = stack.enter_context(patch.object(maintain, 'report_failure'))
            stack.enter_context(patch.object(certify, 'REPORTS', Path(directory)))
            stack.enter_context(patch.object(certify.Certification, 'prepare_tests'))
            verified = stack.enter_context(patch.object(certify, 'certify_published', side_effect=verify))
            if fail:
                with self.assertRaises(RuntimeError):
                    maintain.scan(kotlin, '')
            else:
                maintain.scan(kotlin, '')
            evidence = json.loads((Path(directory) / 'published.json').read_text())
            self.assertEqual(original, data)
            return verified, evidence['records'], report, gh, discovery

    def test_routine_scan_extends_only_missing_recorded_relationships_despite_partial_failure(self):
        verified, records, report, gh, _ = self.scan(fail=True)
        self.assertEqual([('1.2.0', None), ('1.1.0', '1.1.0')], [c.args[1:3] for c in verified.call_args_list])
        self.assertEqual([('1.2.0', None)], [(r['runtime'], r['analysis']) for r in records])
        self.assertEqual(('2.4.21', '1.1.0', '1.1.0'), report.call_args.args[:3])
        self.assertFalse(any(c.args[:2] == ('issue', 'close') for c in gh.call_args_list))

    def test_manual_retry_retries_unresolved_failures_but_skips_verified_successes(self):
        verified, records, report, gh, discovery = self.scan(kotlin='2.2.99')
        expected = [('1.2.0', None), ('1.0.0', '1.1.0'), ('1.1.0', '1.1.0')]
        self.assertEqual(expected, [c.args[1:3] for c in verified.call_args_list])
        self.assertEqual(expected, [(r['runtime'], r['analysis']) for r in records])
        discovery.assert_not_called()  # Explicit historical retries bypass discovery and its support window.
        report.assert_not_called()
        self.assertEqual(('issue', 'close', '12'), gh.call_args.args[:3])

    def test_support_window_expires_by_first_stable_minor_release_in_calendar_months(self):
        dates = {'v2.2.0': '2024-04-14', 'v2.3.0': '2024-04-15', 'v2.4.0': '2024-08-31'}
        versions = ['2.2.0', '2.2.99', '2.3.0', '2.4.0']
        def release(*args):
            return json.dumps({'published_at': dates[args[1].split('/')[-1]] + 'T12:00:00Z'})
        for today, expected in [(date(2025, 10, 13), versions),
                                (date(2025, 10, 14), versions[2:]),
                                (date(2025, 10, 15), versions[3:]),
                                (date(2026, 2, 27), versions[3:]),
                                (date(2026, 2, 28), [])]:
            with self.subTest(today=today), patch.object(maintain, 'gh', side_effect=release) as gh:
                self.assertEqual(expected, maintain.supported_versions(versions, today))
                self.assertEqual(3, gh.call_count)

    def test_runtime_success_is_reused_only_for_the_same_release_and_compiler_within_a_scan(self):
        checked = set()
        with tempfile.TemporaryDirectory() as directory, ExitStack() as stack:
            stack.enter_context(patch.object(certify, 'REPORTS', Path(directory)))
            stack.enter_context(patch.object(certify.Certification, 'resolve', return_value={
                'extractor': [{'file': 'plugin.jar'}], 'compiler': [], 'controlCompiler': []}))
            runtime = stack.enter_context(patch.object(certify.Certification, 'runtime', autospec=True))
            stack.enter_context(patch.object(certify.Certification, 'junit'))
            stack.enter_context(patch.object(certify.Certification, 'extract', return_value={'fixture': 'verified'}))
            stack.enter_context(patch.object(certify.Certification, 'install'))
            stack.enter_context(patch.object(certify, 'compiler_profiles', return_value=('2.2.0', {'2.2.0': ((2, 2, 0), (2, 5, 0))})))
            for compiler, release, analysis in [('2.4.21', '1.0.0', None), ('2.4.21', '1.0.0', '1.0.0'),
                                                 ('2.4.21', '1.0.0', '1.1.0'), ('2.4.21', '1.1.0', '1.0.0'),
                                                 ('2.4.22', '1.0.0', '1.0.0')]:
                certify.certify_published(compiler, release, analysis, prepare=False, checked_runtime=checked)
            self.assertEqual([('1.0.0', '2.4.21'), ('1.1.0', '2.4.21'), ('1.0.0', '2.4.22')],
                             [(c.args[0].runtime_version, c.args[1]) for c in runtime.call_args_list])


if __name__ == '__main__':
    unittest.main()

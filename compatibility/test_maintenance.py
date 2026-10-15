"""Selection policies, independent of GitHub Actions and artifact verification."""

from copy import deepcopy
from datetime import date
import unittest

from discovery import supported_versions
from maintain import issue_title, pending_checks
from registry import validate


class ScanSelectionTest(unittest.TestCase):
    def setUp(self):
        self.data = validate({
            'runtime': {'1.0.0': ['2.2.0', '2.4.21'], '1.1.0': ['2.2.0', '2.4.21'], '1.2.0': ['2.2.0']},
            'analysis': {
                '1.0.0': {'kotlin': ['2.2.0', '2.4.21'], 'runtime': {'1.0.0': ['2.2.0', '2.4.21']}},
                '1.1.0': {'kotlin': ['2.2.0'], 'runtime': {'1.0.0': ['2.2.0'], '1.1.0': ['2.2.0']}},
            },
        })
        self.issues = {issue_title('2.4.21', '1.0.0', '1.1.0'): {'number': 12}}

    def test_routine_scan_extends_only_missing_recorded_relationships_without_open_failures(self):
        original = deepcopy(self.data)
        self.assertEqual([('1.2.0', None), ('1.1.0', '1.1.0')],
                         pending_checks(self.data, '2.4.21', self.issues))
        self.assertEqual(original, self.data)
        self.assertEqual([], pending_checks({'runtime': {}, 'analysis': {}}, '2.4.21', {}))

    def test_retry_bypasses_issues_but_never_repeats_success_or_invents_relationships(self):
        self.assertEqual([('1.2.0', None), ('1.0.0', '1.1.0'), ('1.1.0', '1.1.0')],
                         pending_checks(self.data, '2.4.21', self.issues, retry=True))
        self.assertEqual([], pending_checks(self.data, '2.2.0', {}, retry=True))
        self.assertEqual([(r, None) for r in self.data['runtime']] + [
            ('1.0.0', '1.0.0'), ('1.0.0', '1.1.0'), ('1.1.0', '1.1.0')],
            pending_checks(self.data, '2.2.99', {}, retry=True))

    def test_support_window_uses_first_stable_minor_release_and_calendar_months(self):
        releases = {'2.2.0': date(2024, 4, 14), '2.3.0': date(2024, 4, 15), '2.4.0': date(2024, 8, 31)}
        versions = ['2.2.0', '2.2.99', '2.3.0', '2.4.0']
        for today, expected in [(date(2024, 4, 13), []),
                                (date(2024, 4, 14), versions[:2]),
                                (date(2025, 10, 13), versions),
                                (date(2025, 10, 14), versions[2:]),
                                (date(2025, 10, 15), versions[3:]),
                                (date(2026, 2, 27), versions[3:]),
                                (date(2026, 2, 28), [])]:
            with self.subTest(today=today):
                self.assertEqual(expected, supported_versions(versions, releases, today))


if __name__ == '__main__':
    unittest.main()

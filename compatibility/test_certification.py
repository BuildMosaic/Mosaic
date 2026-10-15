"""Harness-only invariants; Kotlin fixtures own compiler and installation behavior."""

from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import certify


class RequiredResultsTest(unittest.TestCase):
    def test_empty_failed_and_skipped_junit_results_cannot_certify(self):
        with tempfile.TemporaryDirectory() as directory:
            directory = Path(directory)
            with self.assertRaises(RuntimeError):
                certify.junit_results(directory)
            xml = directory / 'TEST-fixtures.xml'
            for result in ['', '<skipped/>', '<error/>', '<failure/>']:
                with self.subTest(result=result):
                    xml.write_text(f'<testsuite><testcase name="required">{result}</testcase></testsuite>')
                    if result:
                        with self.assertRaises(RuntimeError):
                            certify.junit_results(directory)
                    else:
                        self.assertEqual(1, certify.junit_results(directory))


class RuntimeReuseTest(unittest.TestCase):
    def test_success_is_shared_only_for_the_same_runtime_and_compiler(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(certify, 'REPORTS', Path(directory)):
            first = certify.Certification(runtime='1.0.0')
            other_analysis = certify.Certification(runtime='1.0.0', analysis='1.1.0')
            other_runtime = certify.Certification(runtime='1.1.0')
            for check in (other_analysis, other_runtime):
                check.checked_runtime = first.checked_runtime
            with patch.object(certify.Certification, 'resolve', return_value={}) as resolve, \
                    patch.object(certify.Certification, 'runtime', autospec=True) as runtime:
                for check in (first, other_analysis, other_runtime):
                    check.check(['2.4.21', '2.4.22'], 'runtime')
                self.assertEqual([(first, '2.4.21'), (first, '2.4.22'),
                                  (other_runtime, '2.4.21'), (other_runtime, '2.4.22')],
                                 [call.args[:2] for call in runtime.call_args_list])
                self.assertEqual(6, resolve.call_count)  # Each combination still resolves its own evidence.

    def test_failure_never_enters_the_success_cache(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(certify, 'REPORTS', Path(directory)):
            check = certify.Certification(runtime='1.0.0')
            with patch.object(check, 'resolve', return_value={}), \
                    patch.object(check, 'runtime', side_effect=RuntimeError('consumer failed')):
                with self.assertRaises(RuntimeError):
                    check.check(['2.4.21'], 'runtime')
            self.assertEqual(set(), check.checked_runtime)


if __name__ == '__main__':
    unittest.main()

"""Only registry claims: real Kotlin fixtures own artifact and compiler verification."""

from copy import deepcopy
import unittest

from registry import apply_evidence, compatible, validate


class CompatibilityClaimsTest(unittest.TestCase):
    def test_records_exact_pairs_and_compilers_without_inference_or_duplicate_updates(self):
        data = {'runtime': {'1.0.0': ['2.2.0'], '1.1.0': ['2.2.0']}, 'analysis': {}}
        analysis = {'train': 'analysis', 'version': '1.0.0', 'result': 'pass',
                    'kotlin': ['2.2.0'], 'counterparts': {'1.0.0': ['2.2.0']}}
        runtime = {'train': 'runtime', 'version': '1.2.0', 'result': 'pass',
                   'kotlin': ['2.2.0', '2.4.21'], 'counterparts': {'1.0.0': ['2.2.0']}}
        apply_evidence(data, analysis)
        apply_evidence(data, runtime)
        self.assertTrue(compatible(data, '1.2.0', '2.4.21'))
        self.assertTrue(compatible(data, '1.2.0', '2.2.0', '1.0.0'))
        for r, k, a in [('1.2.0', '2.4.21', '1.0.0'), ('1.1.0', '2.2.0', '1.0.0'),
                        ('1.2.0', '2.3.99', None), ('9.0.0', '2.2.0', None)]:
            self.assertFalse(compatible(data, r, k, a))
        original = deepcopy(validate(data))
        apply_evidence(data, analysis)
        apply_evidence(data, runtime)
        self.assertEqual(original, validate(data))

    def test_failed_or_untested_evidence_cannot_establish_support(self):
        data = {'runtime': {'1.0.0': ['2.2.0'], '1.1.0': ['2.2.0']},
                'analysis': {'1.0.0': {'kotlin': ['2.2.0'], 'runtime': {'1.0.0': ['2.2.0']}}}}
        release = {'train': 'analysis', 'version': '1.1.0', 'result': 'pass',
                   'kotlin': ['2.2.0'], 'counterparts': {'1.0.0': ['2.2.0']}}
        for changes in [{'result': 'fail'}, {'counterparts': {}},
                        {'counterparts': {'9.0.0': ['2.2.0']}},
                        {'counterparts': {'1.0.0': ['2.4.21']}}]:
            with self.subTest(changes=changes), self.assertRaises(ValueError):
                apply_evidence(deepcopy(data), {**release, **changes})
        daily = {'result': 'pass', 'runtime': '1.0.0', 'analysis': '1.0.0', 'kotlin': ['2.4.21']}
        for changes in [{'result': 'fail'}, {'runtime': '9.0.0'}, {'runtime': '1.1.0'}, {'analysis': '9.0.0'}]:
            with self.subTest(changes=changes), self.assertRaises(ValueError):
                apply_evidence(deepcopy(data), {'records': [{**daily, **changes}]})
        apply_evidence(data, {'records': [daily]})
        self.assertTrue(compatible(validate(data), '1.0.0', '2.4.21', '1.0.0'))
        self.assertFalse(compatible(data, '1.1.0', '2.4.21', '1.0.0'))


if __name__ == '__main__':
    unittest.main()

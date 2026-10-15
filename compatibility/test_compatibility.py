"""Only critical claim boundaries; compiler behavior is covered by the existing Kotlin fixtures."""

from copy import deepcopy
import json
from pathlib import Path
import tempfile
import unittest

from certify import summaries
from registry import apply_evidence, compatible, record, validate


class CompatibilityClaimsTest(unittest.TestCase):
    def test_missing_relationships_and_unseen_compilers_never_imply_support(self):
        data = {'runtime': {'1.0.0': ['2.2.0', '2.4.21'], '2.0.0': ['2.2.0']},
                'analysis': {'1.0.0': {'kotlin': ['2.2.0', '2.4.21'], 'runtime': {'1.0.0': ['2.2.0']}}}}
        self.assertTrue(compatible(data, '1.0.0', '2.2.0', '1.0.0'))
        self.assertTrue(compatible(data, '1.0.0', '2.4.21'))
        for runtime, kotlin, analysis in [('2.0.0', '2.2.0', '1.0.0'),
                                           ('1.0.0', '2.2.99', None),
                                           ('1.0.0', '2.4.21', '1.0.0'),
                                           ('9.0.0', '2.2.0', None),
                                           ('1.0.0', '2.2.0', '9.0.0')]:
            self.assertFalse(compatible(data, runtime, kotlin, analysis))
        patch = {'result': 'pass', 'runtime': '1.0.0', 'analysis': None, 'kotlin': ['2.4.22']}
        record(data, patch)
        self.assertTrue(compatible(data, '1.0.0', '2.4.22'))
        self.assertFalse(compatible(data, '1.0.0', '2.4.22', '1.0.0'))
        record(data, {**patch, 'analysis': '1.0.0'})
        self.assertTrue(compatible(data, '1.0.0', '2.4.22', '1.0.0'))
        self.assertFalse(compatible(data, '2.0.0', '2.4.22', '1.0.0'))
        before = json.dumps(data)
        with self.assertRaises(ValueError):
            record(data, {**patch, 'result': 'fail', 'kotlin': ['2.4.23']})
        self.assertEqual(before, json.dumps(data))
        self.assertEqual(data, validate(data))

    def test_release_evidence_records_only_tested_pairs_and_is_idempotent(self):
        data = {'runtime': {'1.0.0': ['2.2.0']}, 'analysis': {}}
        evidence = {'train': 'analysis', 'version': '1.1.0', 'result': 'pass',
                    'kotlin': ['2.2.0', '2.4.21'], 'counterparts': {'1.0.0': ['2.2.0', '2.4.21']}}
        apply_evidence(data, evidence)
        self.assertTrue(compatible(data, '1.0.0', '2.4.21', '1.1.0'))
        runtime = {'train': 'runtime', 'version': '1.2.0', 'result': 'pass',
                   'kotlin': ['2.2.0', '2.4.21'], 'counterparts': {'1.1.0': ['2.2.0']}}
        apply_evidence(data, runtime)
        self.assertTrue(compatible(data, '1.2.0', '2.2.0', '1.1.0'))
        self.assertFalse(compatible(data, '1.2.0', '2.4.21', '1.1.0'))
        original = deepcopy(validate(data))
        apply_evidence(data, evidence)
        apply_evidence(data, runtime)
        self.assertEqual(original, validate(data))

    def test_unverified_release_evidence_cannot_extend_claims(self):
        data = {'runtime': {'1.0.0': ['2.2.0']}, 'analysis': {}}
        evidence = {'train': 'analysis', 'version': '1.1.0', 'result': 'pass',
                    'kotlin': ['2.2.0'], 'counterparts': {'1.0.0': ['2.2.0']}}
        for changes in [{'result': 'fail'}, {'train': 'unknown'}, {'version': '1.1.0-RC'},
                        {'kotlin': ['2.2.0', '2.2.0']}, {'counterparts': {}},
                        {'counterparts': {'9.0.0': ['2.2.0']}},
                        {'counterparts': {'1.0.0': ['2.4.21']}}]:
            with self.subTest(changes=changes), self.assertRaises(ValueError):
                apply_evidence(deepcopy(data), {**evidence, **changes})
        for result in [{'result': 'pass', 'runtime': '9.0.0', 'analysis': None, 'kotlin': ['2.2.0']},
                       {'result': 'pass', 'runtime': '1.0.0', 'analysis': '9.0.0', 'kotlin': ['2.2.0']}]:
            with self.subTest(result=result), self.assertRaises(ValueError):
                apply_evidence(deepcopy(data), {'records': [result]})

    def test_comparison_ignores_only_compiler_provenance_and_derived_hash(self):
        with tempfile.TemporaryDirectory() as directory:
            directory = Path(directory)
            path = directory / 'fixture.json'
            value = {'producer': {'compilerVersion': '2.2.0', 'analysisVersion': '1.0.0'},
                     'payloadHash': 'first', 'payload': {'effects': ['lookup', 'unknown'], 'line': 3}}
            path.write_text(json.dumps(value))
            before = summaries(directory)
            value['producer']['compilerVersion'] = '2.4.21'
            value['payloadHash'] = 'second'
            path.write_text(json.dumps(value))
            self.assertEqual(before, summaries(directory))
            value['payload']['effects'].reverse()
            path.write_text(json.dumps(value))
            self.assertNotEqual(before, summaries(directory))
            value['payload']['effects'].reverse()
            value['payload']['line'] = 4
            path.write_text(json.dumps(value))
            self.assertNotEqual(before, summaries(directory))


if __name__ == '__main__':
    unittest.main()

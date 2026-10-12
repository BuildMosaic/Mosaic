"""Only critical claim boundaries; compiler behavior is covered by the existing Kotlin fixtures."""

import json
from pathlib import Path
import tempfile
import unittest

from certify import summaries
from registry import compatible, record, validate


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

"""Independent range claims and certification failure boundaries."""

from copy import deepcopy
import unittest
from unittest.mock import Mock

from maintain import verify_analysis
from registry import apply_evidence, compatible, validate

KOTLIN = ['2.2.0', '2.3.0', '2.4.21', '2.4.22', '2.5.0']


def published():
    return {'runtime': {r: {'minKotlin': '2.2.0', 'maxKotlin': '2.4.21'}
                        for r in ['1.0.0', '1.1.0', '1.2.0']},
            'analysis': {'1.0.0': {'minRuntime': '1.0.0', 'maxRuntime': '1.1.0',
                                   'minKotlin': '2.3.0', 'maxKotlin': '2.5.0'}}}


def record(train, release='1.0.0', **ranges):
    return {'result': 'pass', 'train': train, 'version': release, **ranges}


class CompatibilityClaimsTest(unittest.TestCase):
    def test_inclusive_independent_ranges_and_effective_overlap(self):
        data = validate(published())
        for runtime in ['1.0.0', '1.1.0']:
            for kotlin in ['2.3.0', '2.3.99', '2.4.21']:
                self.assertTrue(compatible(data, runtime, kotlin, '1.0.0'))
        self.assertTrue(compatible(data, '1.0.0', '2.2.0'))
        for runtime, kotlin, analysis in [('1.0.0', '2.2.0', '1.0.0'), ('1.0.0', '2.4.22', '1.0.0'),
                                           ('1.2.0', '2.3.0', '1.0.0'), ('9.0.0', '2.3.0', None),
                                           ('1.0.0', '2.3.0', '9.0.0')]:
            self.assertFalse(compatible(data, runtime, kotlin, analysis))

    def test_bound_updates_are_independent_historical_and_idempotent(self):
        data = published()
        records = [record('runtime', kotlin=['2.4.22']),
                   record('analysis', runtime=['1.2.0'], kotlin=KOTLIN[1:]),
                   record('analysis', kotlin=['2.2.0'], runtime=['1.0.0', '1.1.0', '1.2.0'])]
        for update in records:
            before = deepcopy(data)
            apply_evidence(data, {'records': [update]}, KOTLIN)
            other = 'analysis' if update['train'] == 'runtime' else 'runtime'
            self.assertEqual(data[other], before[other])
        self.assertEqual(data['analysis']['1.0.0'], {'minRuntime': '1.0.0', 'maxRuntime': '1.2.0',
                                                   'minKotlin': '2.2.0', 'maxKotlin': '2.5.0'})
        self.assertEqual(data['runtime']['1.0.0']['minKotlin'], '2.2.0')
        before = deepcopy(data)
        apply_evidence(data, {'records': records}, KOTLIN)
        self.assertEqual(data, before)

    def test_new_releases_require_complete_ranges(self):
        data = published()
        for train, ranges in [('runtime', {'kotlin': KOTLIN}),
                              ('analysis', {'kotlin': KOTLIN, 'runtime': list(data['runtime']) + ['2.0.0']})]:
            evidence = {'records': [record(train, '2.0.0', **ranges)]}
            with self.assertRaises(ValueError):
                apply_evidence(data, evidence, KOTLIN)
            apply_evidence(data, {**evidence, 'train': train, 'version': '2.0.0', 'result': 'pass'}, KOTLIN)
        self.assertTrue(compatible(data, '2.0.0', '2.5.0', '2.0.0'))
        self.assertEqual(data['analysis']['1.0.0']['maxRuntime'], '1.1.0')

    def test_invalid_or_incomplete_evidence_is_atomic(self):
        for update in [record('runtime', kotlin=['2.5.0']),
                       record('analysis', runtime=['2.0.0']),
                       record('analysis', runtime=['1.2.0'], kotlin=['2.3.0']),
                       record('analysis', kotlin=['2.2.0'], runtime=['1.0.0']),
                       record('analysis', '2.0.0', runtime=['1.0.0', '1.2.0'], kotlin=KOTLIN),
                       record('analysis', '2.0.0', kotlin=KOTLIN),
                       record('runtime', kotlin=['2.4.22-RC1']),
                       record('runtime', kotlin=[]),
                       {**record('runtime', kotlin=KOTLIN), 'result': 'fail'}]:
            data = published()
            with self.subTest(update=update), self.assertRaises(ValueError):
                apply_evidence(data, {'train': update['train'], 'version': update['version'],
                                      'records': [record('runtime', '1.1.0', kotlin=['2.4.22']), update]}, KOTLIN)
            self.assertEqual(data, published())

    def test_invalid_registry_ranges_are_rejected(self):
        for changes in [{'minRuntime': '1.2.0'}, {'maxRuntime': '9.0.0'},
                        {'minKotlin': '2.5.1'}, {'maxKotlin': '2.5.0-RC1'},
                        {'1.0.0': {'minKotlin': '2.2.0', 'maxKotlin': '2.5.0'}}]:
            data = published()
            data['analysis']['1.0.0'].update(changes)
            with self.subTest(changes=changes), self.assertRaises(ValueError):
                validate(data)

    def test_failed_analysis_batch_cannot_pass_through_diagnostic_retries(self):
        failure = RuntimeError('multi-version installation failed')
        check = Mock()
        check.analysis.side_effect = [failure, None, None]
        with self.assertRaisesRegex(RuntimeError, 'multi-version installation failed'):
            verify_analysis(check, ['2.4.21', '2.4.22'], '1.0.0', '1.0.0')
        self.assertEqual(check.analysis.call_count, 3)


if __name__ == '__main__':
    unittest.main()

"""Registry claims and automation boundaries; Kotlin fixtures own compiler verification."""

import base64
from copy import deepcopy
import json
import os
from pathlib import Path
import subprocess
from tempfile import TemporaryDirectory
import unittest
from unittest.mock import patch

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
    def test_backport_sweep_batches_analysis_and_attributes_failures_without_advancing_pair(self):
        versions = ['2.2.0', '2.2.1', '2.4.22']
        for broken in [False, True]:
            with self.subTest(broken=broken), TemporaryDirectory() as directory, \
                    patch.object(maintain.registry, 'load', return_value=published()) as load, \
                    patch.object(maintain, 'open_issues', return_value={}), \
                    patch.object(certify, 'central_versions', return_value=versions), \
                    patch.object(maintain, 'supported_versions', return_value=versions), \
                    patch.object(maintain, 'available', return_value=True), \
                    patch.object(certify, 'REPORTS', Path(directory)), \
                    patch.object(certify, 'Certification') as factory, \
                    patch.object(maintain, 'report_failure') as report:
                check = factory.return_value
                check.revision = 'source-sha'
                def analysis(compilers, *_):
                    if broken and '2.2.1' in compilers:
                        raise RuntimeError('backport regression')
                check.analysis.side_effect = analysis
                if broken:
                    with self.assertRaises(RuntimeError):
                        maintain.scan('', '')
                    self.assertEqual(report.call_args.args[:3], ('2.2.1', '1.0.0', '1.0.0'))
                    report.assert_called_once()
                else:
                    maintain.scan('', '')
                    check.analysis.assert_called_once_with(versions, '1.0.0', '1.0.0')
                evidence = json.loads((Path(directory) / 'published.json').read_text())
                data = apply_evidence(load.return_value, evidence)
                self.assertTrue(compatible(validate(data), '1.0.0', '2.4.22'))
                self.assertEqual(compatible(data, '1.0.0', '2.4.22', '1.0.0'), not broken)
                self.assertFalse(compatible(data, '1.1.0', '2.4.22', '1.0.0'))


class RegistryPublicationTest(unittest.TestCase):
    def test_concurrent_writer_reloads_latest_registry_before_merging_evidence(self):
        data, latest = published(), published()
        latest['runtime']['1.0.0'] = interval(upper='2.5.0')
        latest['analysis']['1.1.0'] = {'1.1.0': interval()}
        def file(records):
            return json.dumps({'content': base64.b64encode(json.dumps(records).encode()).decode()})
        with patch.dict(os.environ, {'GITHUB_REPOSITORY': 'BuildMosaic/Mosaic'}), \
                patch.object(maintain, 'run_url', return_value='https://example.test/run'), \
                patch.object(maintain, 'gh') as gh:
            head = lambda sha: json.dumps({'object': {'sha': sha}})
            conflict = subprocess.CalledProcessError(1, 'gh', output=json.dumps({'errors': [{'message': 'head changed'}]}))
            gh.side_effect = [head('old'), file(data), conflict, head('new'), head('new'), file(latest),
                              json.dumps({'data': {'createCommitOnBranch': {'commit': {'oid': 'committed'}}}})]
            maintain.publish({'records': [{'result': 'pass', 'runtime': '1.1.0', 'analysis': None, 'kotlin': ['2.4.22']}]})
            commit = gh.call_args.kwargs['payload']['variables']['input']
            self.assertEqual(commit['expectedHeadOid'], 'new')
            written = json.loads(base64.b64decode(commit['fileChanges']['additions'][0]['contents']))
            self.assertEqual(validate(written)['runtime']['1.0.0'], interval(upper='2.5.0'))
            self.assertEqual(written['runtime']['1.1.0'], interval(upper='2.4.22'))
            self.assertEqual(written['analysis'], latest['analysis'])


if __name__ == '__main__':
    unittest.main()

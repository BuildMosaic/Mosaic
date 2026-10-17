"""Independent range semantics and atomic certification failure boundaries."""

from copy import deepcopy
import json
import os
from pathlib import Path
import subprocess
from tempfile import TemporaryDirectory
import unittest
from unittest.mock import patch

from maintain import verify_event
import registry
import release
from registry import apply_result, compatible, fingerprint, validate


def published():
    return {'runtime': {'1.0.0': {'minKotlin': '2.2.0', 'maxKotlin': '2.4.21'}},
            'analysis': {'1.0.0': {'minRuntime': '1.0.0', 'maxRuntime': '1.0.0',
                                   'minKotlin': '2.3.0', 'maxKotlin': '2.5.0'}}}


class CompatibilityClaimsTest(unittest.TestCase):
    def test_inclusive_independent_ranges_and_numeric_comparison(self):
        data = validate(published())
        for kotlin in ['2.3.0', '2.3.99', '2.4.9', '2.4.21']:
            self.assertTrue(compatible(data, '1.0.0', kotlin, '1.0.0'))
        self.assertTrue(compatible(data, '1.0.0', '2.2.0'))
        for runtime, kotlin, analysis in [('1.0.0', '2.2.0', '1.0.0'), ('1.0.0', '2.4.22', '1.0.0'),
                                          ('1.1.0', '2.3.0', '1.0.0'), ('9.0.0', '2.3.0', None)]:
            self.assertFalse(compatible(data, runtime, kotlin, analysis))

    def test_invalid_registry_ranges_are_rejected(self):
        for changes in [{'minRuntime': '1.2.0'}, {'maxRuntime': '9.0.0'},
                        {'minKotlin': '2.5.1'}, {'maxKotlin': '2.5.0-RC1'}, {'pairings': {}}]:
            data = published()
            data['analysis']['1.0.0'].update(changes)
            with self.subTest(changes=changes), self.assertRaises(ValueError):
                validate(data)

    def test_failed_event_cannot_advance_ranges_or_checkpoint(self):
        data = published()
        before = deepcopy(data)
        checkpoint = '2026-10-08T09:25:15Z'
        event = {'kotlin': '2.4.22', 'published': '2026-10-09T09:25:15Z'}
        with patch('maintain.subprocess.run', side_effect=subprocess.CalledProcessError(1, 'check.sh')):
            with self.assertRaises(subprocess.CalledProcessError):
                verify_event(data, event, ['2.2.0', '2.3.0', '2.4.21'])
        self.assertEqual(data, before)
        failed = {'result': 'fail', 'registryBefore': fingerprint(data), 'checkpointBefore': checkpoint,
                  'registry': data, 'checkpoint': event['published']}
        with self.assertRaisesRegex(ValueError, 'Failed certification'):
            apply_result(data, checkpoint, failed)
        self.assertEqual(data, before)
        self.assertEqual(checkpoint, '2026-10-08T09:25:15Z')

    def test_snapshot_guard_and_monotonic_automatic_updates(self):
        data = published()
        checkpoint = '2026-10-08T09:25:15Z'
        evidence = {'result': 'pass', 'registryBefore': fingerprint(data), 'checkpointBefore': checkpoint,
                    'registry': deepcopy(data), 'checkpoint': '2026-10-09T09:25:15Z'}
        evidence['registry']['runtime']['1.0.0']['maxKotlin'] = '2.4.22'
        updated, advanced = apply_result(data, checkpoint, evidence)
        self.assertEqual(updated['runtime']['1.0.0']['minKotlin'], '2.2.0')
        self.assertEqual(advanced, evidence['checkpoint'])
        self.assertEqual(data, published())
        with self.assertRaisesRegex(ValueError, 'facts changed'):
            apply_result(updated, checkpoint, evidence)
        for key, value in [('minKotlin', '2.1.0'), ('maxKotlin', '2.3.0')]:
            evidence['registry'] = deepcopy(data)
            evidence['registry']['runtime']['1.0.0'][key] = value
            with self.assertRaisesRegex(ValueError, 'minima|lower maxima'):
                apply_result(data, checkpoint, evidence)


class ReleaseCertificationTest(unittest.TestCase):
    def setUp(self):
        self.workspace = TemporaryDirectory()
        self.addCleanup(self.workspace.cleanup)
        previous = Path.cwd()
        os.chdir(self.workspace.name)
        self.addCleanup(os.chdir, previous)
        self.data = published()
        self.catalog = ['2.2.0', '2.3.0', '2.4.21', '2.5.0']
        for context in [patch('registry.load', return_value=self.data),
                        patch('release.central_versions', return_value=self.catalog),
                        patch('release.publications', return_value=[{'kotlin': v, 'published': '2000-01-01T00:00:00Z'} for v in self.catalog]),
                        patch('release.available', return_value=True),
                        patch.dict(os.environ, RELEASE_SHA='source-commit', GITHUB_OUTPUT='outputs')]:
            context.start()
            self.addCleanup(context.stop)

    def test_release_matrix_uses_patches_and_independent_intersections(self):
        release.plan('runtime', '1.1.0')
        planned = json.loads(Path('release-plan.json').read_text())
        self.assertEqual([r['kotlin'] for r in planned['matrix']], self.catalog)
        self.assertEqual(planned['matrix'][0]['checks'], [{'runtime': '1.1.0', 'analysis': '-', 'optional': False}])
        self.assertTrue(planned['matrix'][-1]['checks'][-1]['optional'])
        release.plan('analysis', '1.1.0')
        planned = json.loads(Path('release-plan.json').read_text())
        self.assertEqual([r['kotlin'] for r in planned['matrix']], ['2.3.0', '2.4.21'])
        self.assertTrue(all(not c['optional'] for r in planned['matrix'] for c in r['checks']))
        release.plan('analysis', '1.1.0', minKotlin='2.5.0')
        self.assertEqual(json.loads(Path('release-plan.json').read_text())['matrix'], [])
        release.finish()
        self.assertIn('1.1.0', json.loads(Path('release-result.json').read_text())['registry']['analysis'])

    def test_optional_failures_retain_claims_and_mandatory_failures_prevent_result(self):
        release.plan('runtime', '1.1.0')
        with patch('release.verify_pair', side_effect=ValueError('summary or cache check failed')):
            release.finish()
        recorded = json.loads(Path('release-result.json').read_text())
        self.assertEqual(recorded['registry']['analysis'], self.data['analysis'])
        self.assertIn('1.1.0', recorded['registry']['runtime'])
        failure = Path('certification/optional-failures/1.0.0')
        failure.parent.mkdir(parents=True)
        failure.touch()
        with patch('release.verify_pair') as verify:
            release.finish()
        verify.assert_not_called()
        self.assertEqual(json.loads(Path('release-result.json').read_text())['registry']['analysis'], self.data['analysis'])
        failure.unlink()
        Path('release-result.json').unlink()
        release.plan('analysis', '1.1.0')
        with patch('release.verify_pair', side_effect=ValueError('summary or cache check failed')):
            with self.assertRaisesRegex(ValueError, 'summary or cache'):
                release.finish()
        self.assertFalse(Path('release-result.json').exists())

    def test_normalized_summary_equivalence_requires_provenance_and_complete_maps(self):
        for compiler in ['2.2.0', '2.3.0']:
            directory = Path('certification/1.0.0/1.0.0') / compiler
            directory.mkdir(parents=True)
            (directory / 'fixture.json').write_text(json.dumps({'producer': {'compilerVersion': compiler},
                                                                'payloadHash': compiler, 'module': {'id': 'same'}}))
        release.verify_pair('1.0.0', '1.0.0', ['2.2.0', '2.3.0'])
        path = Path('certification/1.0.0/1.0.0/2.3.0/fixture.json')
        summary = json.loads(path.read_text())
        summary['producer']['compilerVersion'] = '2.2.0'
        path.write_text(json.dumps(summary))
        with self.assertRaisesRegex(ValueError, 'provenance'):
            release.verify_pair('1.0.0', '1.0.0', ['2.2.0', '2.3.0'])
        path.unlink()
        with self.assertRaisesRegex(ValueError, 'Missing or unequal'):
            release.verify_pair('1.0.0', '1.0.0', ['2.2.0', '2.3.0'])

    def test_older_family_patch_follows_its_frontier_without_reviving_pinned_ranges(self):
        self.data['runtime']['0.9.0'] = {'minKotlin': '2.2.0', 'maxKotlin': '2.4.20'}
        updated, standalone, batches = registry.event_plan(self.data, '2.4.22', self.catalog)
        self.assertEqual(standalone, [('1.0.0', ['2.4.22'])])
        self.assertEqual(batches, [('1.0.0', '1.0.0', ['2.4.22'])])
        self.assertEqual(updated['runtime']['1.0.0']['maxKotlin'], '2.4.22')
        self.assertEqual(updated['runtime']['0.9.0']['maxKotlin'], '2.4.20')


if __name__ == '__main__':
    unittest.main()

"""Release planning, runner handoff and publication authorization boundaries."""

from copy import deepcopy
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import registry
import release
from test_compatibility import published

CATALOG = ['2.2.0', '2.3.0', '2.3.20', '2.3.21', '2.4.20', '2.4.21', '2.5.0']


class FixtureCertification:
    revision = 'approved-commit'
    calls = []
    failures = set()
    different = set()

    def __init__(self, java_installations):
        pass

    def runtime(self, compilers, runtime):
        self.calls.append(('runtime', runtime, compilers))
        if (runtime, compilers[0]) in self.failures:
            raise RuntimeError('runtime failed')

    def analysis_version(self, kotlin, runtime, analysis, metadata=False):
        self.calls.append(('analysis', runtime, analysis, kotlin, metadata))
        if (runtime, analysis, kotlin) in self.failures:
            raise RuntimeError('analysis failed')
        return {'fixture.json': {'contractVersion': 6, 'producer': {'analysisVersion': analysis},
                                 'moduleId': 'fixture', 'sourceSet': 'main', 'complete': True,
                                 'payload': {'module': 'different' if kotlin in self.different else 'same'}}}, {}

    def resolve(self, *args):
        return {}

    def installation(self, versions, runtime, analysis, artifacts, changes=False, changes_only=False):
        self.calls.append(('installation', runtime, analysis, versions, changes, changes_only))
        if changes and 'cross' in self.failures:
            raise RuntimeError('cross-compiler failed')


class ParallelReleaseTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.directory = self.root / 'prepared'
        self.evidence = self.root / 'evidence'
        self.evidence.mkdir()
        self.reports = self.root / 'reports'
        self.addCleanup(patch.stopall)
        patch('release.certify.Certification', FixtureCertification).start()
        patch('release.certify.REPORTS', self.reports).start()
        FixtureCertification.calls = []
        FixtureCertification.failures = set()
        FixtureCertification.different = set()
        self.plan = self.configure()

    def configure(self, train='analysis', data=None, **bounds):
        data = data or published()
        policy = registry.release_policy(data, train, CATALOG, **bounds)
        plan = {'train': train, 'version': '1.1.0', 'revision': FixtureCertification.revision,
                'registry': data, 'catalog': CATALOG, 'policy': policy, 'checkpoint': '2026-10-08T09:25:15Z'}
        jar = self.directory / f'repository/org/buildmosaic/mosaic-gradle-plugin/{plan["version"]}/plugin.jar'
        jar.parent.mkdir(parents=True, exist_ok=True)
        jar.write_bytes(b'immutable candidate')
        plan['files'] = release.inventory(self.directory)
        release.write(self.directory / 'plan.json', plan)
        self.plan = plan
        return plan

    def workers(self):
        for kotlin in release.matrix(self.plan):
            release.worker(self.directory, kotlin, self.evidence)

    def row(self, kotlin):
        return json.loads((self.evidence / f'{kotlin}.json').read_text())

    def test_preparation_resolves_policy_once_and_builds_shared_inputs_once(self):
        source = self.root / 'source'
        source.mkdir()
        policy_file = source / 'registry.json'
        release.write(policy_file, published())
        (source / 'kotlin-checkpoint.txt').write_text('2026-10-08T09:25:15Z')

        class PreparingCertification(FixtureCertification):
            def build(self, scope, version):
                self.calls.append(('build', scope, version))
                jar = self.repository / 'candidate.jar'
                jar.parent.mkdir(parents=True)
                jar.write_bytes(b'candidate')

            def prepare_fixtures(self):
                self.calls.append(('fixtures',))
                for module in release.MODULES:
                    classes = source / module / 'build/classes/kotlin/test/Fixture.class'
                    classes.parent.mkdir(parents=True)
                    classes.write_bytes(b'compiled fixture')

        patch('release.registry.PATH', policy_file).start()
        patch('release.registry.load', return_value=published()).start()
        patch('release.certify.ROOT', source).start()
        patch('release.certify.Certification', PreparingCertification).start()
        patch('release.certify.central_versions', return_value=CATALOG + ['2.9.0']).start()
        patch('maintain.publications', return_value=[{'kotlin': v, 'published': '2026-10-08T09:25:15Z'} for v in CATALOG]).start()
        available = patch('maintain.available', return_value=True).start()
        outputs = self.root / 'github-output'
        patch.dict('os.environ', {'GITHUB_OUTPUT': str(outputs)}).start()
        plan = release.prepare(self.root / 'new-preparation', 'analysis', '1.1.0')
        self.assertEqual(plan['catalog'], CATALOG)
        self.assertEqual(plan['policy'], registry.release_policy(published(), 'analysis', CATALOG))
        self.assertEqual(available.call_count, len(CATALOG[1:]))
        self.assertEqual(PreparingCertification.calls, [('build', 'analysis', '1.1.0'), ('fixtures',)])
        self.assertEqual(json.loads(outputs.read_text().split('=', 1)[1]), release.matrix(plan))
        self.assertEqual(len(plan['files']), 1 + len(release.MODULES))

    def test_matrix_is_exact_union_of_planned_intersections(self):
        self.assertEqual(release.matrix(self.plan), CATALOG[1:-1])
        data = published()
        data['runtime']['1.0.1'] = {'minKotlin': '2.4.21', 'maxKotlin': '2.5.0'}
        self.configure(data=data, maxRuntime='1.0.1')
        self.assertEqual(release.matrix(self.plan), CATALOG[1:])
        self.workers()
        for kotlin in CATALOG[1:]:
            expected = [r for r, _, compilers in release.checks(self.plan)[1] if kotlin in compilers]
            self.assertEqual([r['runtime'] for r in self.row(kotlin)['analysis']], expected)
        self.assertFalse(any(c[0] == 'runtime' for c in FixtureCertification.calls))

    def test_complete_evidence_compares_summaries_and_runs_cross_compiler_regressions_once(self):
        self.workers()
        result = release.aggregate(self.directory, self.evidence)
        self.assertEqual(result['result'], 'pass')
        self.assertEqual(result['registry']['analysis']['1.1.0'], self.plan['policy'])
        self.assertEqual(result['registry']['runtime'], self.plan['registry']['runtime'])
        installs = [c for c in FixtureCertification.calls if c[0] == 'installation']
        self.assertEqual(len(installs), len(release.matrix(self.plan)) + 1)
        self.assertEqual(installs[-1][-2:], (True, True))
        self.assertEqual(installs[-1][3], release.matrix(self.plan))
        metadata = [c for c in FixtureCertification.calls if c[0] == 'analysis' and c[-1]]
        self.assertEqual(len(metadata), 1)
        self.assertEqual(result['registryBefore'], registry.fingerprint(self.plan['registry']))
        self.assertEqual(result['checkpointBefore'], result['checkpoint'])

    def test_required_failure_has_evidence_but_cannot_authorize_publication(self):
        kotlin = release.matrix(self.plan)[0]
        FixtureCertification.failures = {('1.0.0', '1.1.0', kotlin)}
        for version in release.matrix(self.plan):
            if version == kotlin:
                with self.assertRaisesRegex(RuntimeError, 'Required Kotlin'):
                    release.worker(self.directory, version, self.evidence)
            else:
                release.worker(self.directory, version, self.evidence)
        self.assertEqual(self.row(kotlin)['analysis'][0]['result'], 'fail')
        with self.assertRaisesRegex(ValueError, 'Required Analysis'):
            release.aggregate(self.directory, self.evidence)
        self.assertFalse((self.reports / 'release-analysis.json').exists())

    def test_missing_malformed_foreign_and_incomplete_evidence_is_rejected(self):
        self.workers()
        path = self.evidence / f'{release.matrix(self.plan)[0]}.json'
        original = path.read_text()
        row = json.loads(original)
        mutations = [None, '{bad json', json.dumps({**row, 'plan': 'foreign'}),
                     json.dumps({**row, 'kotlin': '2.9.0'}), json.dumps({**row, 'analysis': []}),
                     json.dumps({**row, 'analysis': row['analysis'] * 2}),
                     json.dumps({**row, 'analysis': [{**row['analysis'][0], 'summaries': {}}]}),
                     json.dumps({**row, 'analysis': [{**row['analysis'][0], 'result': 'unknown'}]})]
        for contents in mutations:
            with self.subTest(contents=contents):
                path.unlink()
                if contents is not None:
                    path.write_text(contents)
                with self.assertRaises((ValueError, KeyError, TypeError)):
                    release.aggregate(self.directory, self.evidence)
                self.assertFalse((self.reports / 'release-analysis.json').exists())
                path.write_text(original)
        release.write(self.evidence / 'unexpected.json', row)
        with self.assertRaisesRegex(ValueError, 'unexpected'):
            release.aggregate(self.directory, self.evidence)

    def test_changed_commit_or_candidate_bytes_are_rejected(self):
        self.plan['revision'] = 'different commit'
        release.write(self.directory / 'plan.json', self.plan)
        with self.assertRaisesRegex(ValueError, 'immutable build outputs'):
            release.worker(self.directory, release.matrix(self.plan)[0], self.evidence)
        self.plan['revision'] = FixtureCertification.revision
        release.write(self.directory / 'plan.json', self.plan)
        (self.directory / next(iter(self.plan['files']))).write_bytes(b'changed')
        with self.assertRaisesRegex(ValueError, 'immutable build outputs'):
            release.worker(self.directory, release.matrix(self.plan)[0], self.evidence)

    def test_summary_disagreement_and_cross_compiler_failure_block_analysis(self):
        for different, failures in [({'2.4.21'}, set()), (set(), {'cross'})]:
            with self.subTest(different=different):
                FixtureCertification.different = different
                FixtureCertification.failures = failures
                self.workers()
                with self.assertRaisesRegex(RuntimeError, 'summaries differ|cross-compiler failed'):
                    release.aggregate(self.directory, self.evidence)
                self.assertFalse((self.reports / 'release-analysis.json').exists())

    def test_runtime_optional_extension_failure_leaves_analysis_bound_unchanged(self):
        self.configure(train='runtime')
        FixtureCertification.failures = {('1.1.0', '1.0.0', '2.4.21')}
        self.workers()
        result = release.aggregate(self.directory, self.evidence)
        self.assertEqual(result['registry']['runtime']['1.1.0'], self.plan['policy'])
        self.assertEqual(result['registry']['analysis'], self.plan['registry']['analysis'])
        self.assertEqual([c[2][0] for c in FixtureCertification.calls if c[0] == 'runtime'], CATALOG)

    def test_failed_runtime_worker_blocks_result_even_when_analysis_is_optional(self):
        self.configure(train='runtime')
        kotlin = release.matrix(self.plan)[0]
        FixtureCertification.failures = {('1.1.0', kotlin)}
        for version in release.matrix(self.plan):
            if version == kotlin:
                with self.assertRaisesRegex(RuntimeError, 'Required Kotlin'):
                    release.worker(self.directory, version, self.evidence)
            else:
                release.worker(self.directory, version, self.evidence)
        with self.assertRaisesRegex(ValueError, 'Required Runtime'):
            release.aggregate(self.directory, self.evidence)
        self.assertFalse((self.reports / 'release-runtime.json').exists())

    def test_optional_extensions_are_aggregated_independently(self):
        data = published()
        data['analysis']['1.0.1'] = deepcopy(data['analysis']['1.0.0'])
        self.configure(train='runtime', data=data)
        FixtureCertification.failures = {('1.1.0', '1.0.0', '2.4.21')}
        self.workers()
        result = release.aggregate(self.directory, self.evidence)
        self.assertEqual(result['registry']['analysis']['1.0.0']['maxRuntime'], '1.0.0')
        self.assertEqual(result['registry']['analysis']['1.0.1']['maxRuntime'], '1.1.0')

    def test_runtime_existing_analysis_claim_is_mandatory(self):
        data = published()
        data['runtime']['1.2.0'] = deepcopy(data['runtime']['1.0.0'])
        data['analysis']['1.0.0']['maxRuntime'] = '1.2.0'
        self.configure(train='runtime', data=data)
        kotlin = '2.4.21'
        FixtureCertification.failures = {('1.1.0', '1.0.0', kotlin)}
        with self.assertRaisesRegex(RuntimeError, 'Required Kotlin'):
            release.worker(self.directory, kotlin, self.evidence)

    def test_runtime_without_analysis_has_no_analysis_checks_or_fixtures(self):
        data = published()
        data['analysis'] = {}
        self.configure(train='runtime', data=data)
        self.workers()
        result = release.aggregate(self.directory, self.evidence)
        self.assertEqual(result['registry']['analysis'], {})
        self.assertTrue(all(c[0] == 'runtime' for c in FixtureCertification.calls))

    def test_empty_intersections_require_no_workers_and_preserve_existing_bound_behavior(self):
        data = published()
        data['runtime']['1.0.0'] = {'minKotlin': '2.2.0', 'maxKotlin': '2.2.0'}
        self.configure(data=data, minKotlin='2.5.0', maxKotlin='2.5.0')
        self.assertEqual(release.matrix(self.plan), [])
        self.assertEqual(release.aggregate(self.directory, self.evidence)['result'], 'pass')
        self.configure(train='runtime', minKotlin='2.2.0', maxKotlin='2.2.0')
        self.workers()
        result = release.aggregate(self.directory, self.evidence)
        self.assertEqual(result['registry']['analysis']['1.0.0']['maxRuntime'], '1.1.0')


if __name__ == '__main__':
    unittest.main()

import copy
from contextlib import redirect_stdout
from io import StringIO
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from types import SimpleNamespace

from certify import (AREAS, Harness, compare_summaries, compiler_profiles, exact_version, junit_results,
                     main, release_matrix, required_areas, scope_passed)


class CertificationEvidenceTest(unittest.TestCase):
    def test_release_matrix_uses_every_measured_compiler_and_identical_runtime_bytes(self):
        default, profiles = compiler_profiles()
        self.assertEqual("2.4.20", default)
        self.assertEqual(12, len(profiles))
        harnesses = []

        def candidate(version, scope, java_installations):
            harness = SimpleNamespace(
                version=version, scope=scope, directory=Path(version),
                report={"runtimeArtifacts": [{"coordinate": "runtime:one", "sha256": "identical"}]},
                finish=lambda: 0,
            )
            harnesses.append(harness)
            return harness

        with patch("certify.Harness", side_effect=candidate), patch("certify.certify", return_value=0) as certify:
            with redirect_stdout(StringIO()):
                self.assertEqual(0, release_matrix("runtime", "jdk17"))
            self.assertEqual(set(profiles), {h.version for h in harnesses})
            self.assertEqual(12, certify.call_count)
            self.assertEqual("pass", harnesses[-1].report["releaseCertification"]["result"])

        with patch("certify.Harness", side_effect=candidate), patch("certify.certify", side_effect=[0, 1]) as certify:
            self.assertEqual(1, release_matrix("runtime", "jdk17"))
            self.assertEqual(2, certify.call_count)

        def changed(harness):
            harness.report["runtimeArtifacts"][0]["sha256"] = harness.version
            return 0

        with patch("certify.Harness", side_effect=candidate), patch("certify.certify", side_effect=changed):
            with redirect_stdout(StringIO()):
                self.assertEqual(1, release_matrix("runtime", "jdk17"))

    def test_release_analysis_requires_unskipped_production_installation(self):
        def candidate(version, scope, java_installations):
            return SimpleNamespace(
                directory=Path(version), repository=Path("certified-runtime"),
                report={"runtimeArtifacts": [{"coordinate": "runtime:one", "sha256": "identical"}]},
                run=lambda name, command: commands.append(command), finish=lambda: 0,
            )

        for result in ({"tests": 1, "failed": 0, "skipped": 0},
                       {"tests": 0, "failed": 0, "skipped": 0},
                       {"tests": 1, "failed": 0, "skipped": 1}):
            commands = []
            with patch("certify.Harness", side_effect=candidate), patch("certify.certify", return_value=0), \
                    patch("certify.junit_results", return_value=result), redirect_stdout(StringIO()):
                if result["tests"] and not result["skipped"]:
                    self.assertEqual(0, release_matrix("analysis", "jdk17"))
                else:
                    with self.assertRaisesRegex(RuntimeError, "failed, skipped or absent"):
                        release_matrix("analysis", "jdk17")
            self.assertIn(":mosaic-gradle-plugin:releaseCompatibilityTest", commands[0])
            self.assertIn("-Pmosaic.test.runtimeRepository=certified-runtime", commands[0])
            matrix = next(arg.split("=", 1)[1] for arg in commands[0] if arg.startswith("-Pmosaic.test.kotlinVersions="))
            self.assertEqual(set(compiler_profiles()[1]), set(matrix.split(',')))

    def test_requires_exact_stable_version(self):
        for version in ("2.4.20", "2.3.21", "2.2.0"):
            self.assertEqual(version, exact_version(version))
        for version in ("", "latest", "2.+", "2.4", "[2.3,2.4]", "2.4.20-RC2", "2.4.20-Beta1",
                        "2.4.20-SNAPSHOT", "2.4.20-dev-123", "../2.4.20", "2.4.20 ", "２.４.２０"):
            with self.assertRaisesRegex(ValueError, "exact stable Kotlin version.*major.minor.patch"):
                exact_version(version)

    def test_invalid_version_reports_diagnostic_before_artifact_preparation(self):
        with tempfile.TemporaryDirectory() as directory:
            output = StringIO()
            with patch("certify.ROOT", Path(directory)), patch("sys.argv", ["certify.py", "--kotlin", "2.4.20-RC2"]):
                with redirect_stdout(output):
                    self.assertEqual(1, main())
            self.assertIn("exact stable Kotlin version in major.minor.patch format", output.getvalue())
            report = json.loads(next(Path(directory).rglob("report.json")).read_text())
            self.assertEqual([], report["commands"])
            self.assertEqual("fail", report["harnessResult"])
            self.assertFalse(report["harnessComplete"])
            self.assertTrue(all(area["result"] == "not-run" for area in report["areas"].values()))

    def test_partial_or_not_run_area_never_passes_all(self):
        report = {"scope": "all", "areas": {name: {"result": "pass"} for name in AREAS}}
        self.assertTrue(scope_passed(report))
        for area in AREAS:
            for result in ("fail", "not-run"):
                partial = copy.deepcopy(report)
                partial["areas"][area]["result"] = result
                self.assertFalse(scope_passed(partial))
        report["scope"] = "runtime"
        report["areas"]["metadata"]["result"] = "not-run"
        self.assertTrue(scope_passed(report))

    def test_reports_scope_success_and_complete_harness_coverage_separately(self):
        for scope in ("all", "runtime", "analysis"):
            for outcome in ("pass", "fail", "not-run"):
                with self.subTest(scope=scope, required_area_result=outcome), tempfile.TemporaryDirectory() as directory:
                    harness = Harness.__new__(Harness)
                    harness.directory = Path(directory)
                    harness.version, harness.scope = "2.4.20", scope
                    harness.report = {
                        "scope": scope, "actualCompilerVersion": "2.4.20", "commands": [],
                        "environment": {}, "failureDiagnostics": [],
                        "areas": {name: {"result": "pass" if name in required_areas(scope) else "not-run", "suites": []}
                                  for name in AREAS},
                    }
                    area = next(name for name in AREAS if name in required_areas(scope))
                    harness.report["areas"][area]["result"] = outcome
                    with redirect_stdout(StringIO()):
                        self.assertEqual(0 if outcome == "pass" else 1, harness.finish())
                    report = json.loads((harness.directory / "report.json").read_text())
                    self.assertEqual("pass" if outcome == "pass" else "fail", report["harnessResult"])
                    self.assertEqual(scope == "all" and outcome == "pass", report["harnessComplete"])
                    for name in set(AREAS) - required_areas(scope):
                        self.assertEqual("not-run", report["areas"][name]["result"])
                    self.assertIn("not full Mosaic compatibility certification", (harness.directory / "report.md").read_text())

    def test_comparison_preserves_semantics_locations_binary_identity_and_unknowns(self):
        summary = {
            "producer": {"analysisVersion": "test-analysis", "compilerVersion": "2.4.20"},
            "payloadHash": "old", "contractVersion": 6,
            "payload": {"effects": ["lookup", "unknown"], "site": {"path": "Source.kt", "line": 7, "column": 3},
                        "binaryLocators": [{"id": "method", "locator": "Class#method(I)V"}]},
        }
        with tempfile.TemporaryDirectory() as directory:
            before, after = Path(directory) / "before", Path(directory) / "after"
            before.mkdir()
            after.mkdir()
            self.assertEqual("fail", compare_summaries(before, after)["result"])
            (before / "fixture.json").write_text(json.dumps(summary))
            candidate = copy.deepcopy(summary)
            candidate["producer"]["compilerVersion"] = "2.3.21"
            candidate["payloadHash"] = "new"
            (after / "fixture.json").write_text(json.dumps(candidate))
            self.assertEqual("pass", compare_summaries(before, after)["result"])
            changes = [
                lambda s: s["payload"]["effects"].reverse(),
                lambda s: s["payload"]["site"].update(line=8),
                lambda s: s["payload"]["site"].update(column=4),
                lambda s: s["payload"]["binaryLocators"][0].update(locator="Class#method(J)V"),
                lambda s: s.update(contractVersion=7),
                lambda s: s["producer"].update(analysisVersion="another-analysis"),
            ]
            for change in changes:
                changed = copy.deepcopy(candidate)
                change(changed)
                (after / "fixture.json").write_text(json.dumps(changed))
                self.assertEqual(["fixture.json"], compare_summaries(before, after)["changed"])
            (after / "fixture.json").unlink()
            self.assertEqual(["fixture.json"], compare_summaries(before, after)["missing"])

    def test_junit_skipped_and_failed_cases_remain_visible(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory)
            (path / "TEST-jupiter.xml").write_text(
                '<testsuite><testcase classname="Codec"/><testcase classname="Extractor"><failure/></testcase>'
                '<testcase classname="Extractor"><skipped/></testcase></testsuite>'
            )
            self.assertEqual({"tests": 3, "failed": 1, "skipped": 1, "suites": ["Codec", "Extractor"]}, junit_results(path))

    def test_failed_compiler_prevents_comparison_even_with_matching_partial_outputs(self):
        # Matching available outputs cannot make an incomplete compiler corpus pass.
        harness = Harness.__new__(Harness)
        with tempfile.TemporaryDirectory() as directory:
            harness.directory = Path(directory)
            for scope in ("control", "selected"):
                path = harness.directory / scope / "summaries"
                path.mkdir(parents=True)
                (path / "partial.json").write_text('{}')
            harness.report = {"areas": {"directCompiler": {"result": "fail"}, "semanticComparison": {}},
                              "failureDiagnostics": []}
            with self.assertRaisesRegex(RuntimeError, "incomplete/failed"):
                harness.compare()
            self.assertEqual("not-run", harness.report["semanticComparison"]["result"])
            with redirect_stdout(StringIO()):
                harness.area("semanticComparison", harness.compare)
            self.assertEqual("not-run", harness.report["areas"]["semanticComparison"]["result"])


if __name__ == "__main__":
    unittest.main()

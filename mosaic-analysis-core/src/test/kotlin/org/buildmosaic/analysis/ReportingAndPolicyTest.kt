package org.buildmosaic.analysis

import org.buildmosaic.analysis.AnalysisFixtures.binding
import org.buildmosaic.analysis.AnalysisFixtures.compose
import org.buildmosaic.analysis.AnalysisFixtures.entry
import org.buildmosaic.analysis.AnalysisFixtures.lookup
import org.buildmosaic.analysis.AnalysisFixtures.metrics
import org.buildmosaic.analysis.AnalysisFixtures.report
import org.buildmosaic.analysis.AnalysisFixtures.request
import org.buildmosaic.analysis.AnalysisFixtures.site
import org.buildmosaic.analysis.AnalysisFixtures.tile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@Suppress("FunctionMaxLength", "LargeClass", "MaxLineLength")
class ReportingAndPolicyTest {
  @Test
  fun `findings retain dependency Canvas and selected binding provenance paths`() {
    val metricsBinding = binding(metrics, "selected-metrics")
    val canvas =
      CanvasExpression.Layer(
        "request-layer",
        CanvasExpression.Layer(
          "platform-layer",
          CanvasExpression.Empty,
          listOf(metricsBinding),
          site = site("platform-layer"),
        ),
        listOf(binding(request, "request-binding")),
        site = site("request-layer"),
      )
    val leaf = tile("LeafTile", lookup("metrics", metrics, owner = "leaf-source"))
    val parent = tile("ParentTile", compose("leaf-compose", CanvasExpression.Current, leaf.id))
    val module =
      ModuleContract(
        "app",
        tiles = listOf(parent, leaf),
        callables = listOf(entry("entry", compose("root-compose", canvas, parent.id))),
      )
    val finding = report(module).findings.single()

    assertEquals(listOf("COMPOSE:ParentTile", "COMPOSE:LeafTile"), finding.dependencyPath.map { it.label })
    assertEquals(listOf("request-layer", "platform-layer"), finding.canvasPath.map { it.layerId })
    assertEquals(metricsBinding.site, finding.bindingSite)
    assertEquals(site("leaf-source"), finding.site)
  }

  @Test
  fun `default warns on unknown while strict rejects it`() {
    val unknown = Effect.Unknown("unknown", "unsupported dispatch", site("unknown"))
    val module = ModuleContract("app", callables = listOf(entry("entry", unknown)))
    val default = report(module)
    val strict = report(module, policy = AnalysisPolicy.STRICT)

    assertTrue(default.policyDecision.passed)
    assertEquals(default.findings, default.policyDecision.warnings)
    assertFalse(strict.policyDecision.passed)
    assertEquals(strict.findings, strict.policyDecision.errors)
  }

  @Test
  fun `general suppression accepts uncertainty from any cause without changing certainty`() {
    val suppression = MosaicSuppression(MosaicSuppression.UNVERIFIED, site("suppression"))
    val alternatives =
      CanvasExpression.Choice(
        Guard.Opaque("runtime choice", site("choice")),
        CanvasExpression.Empty,
        CanvasExpression.Layer("provided", CanvasExpression.Empty, listOf(binding(metrics)), site = site("provided")),
      )
    val effects =
      listOf(
        Effect.Unknown("unknown", "unsupported dispatch", site("unknown")),
        lookup("lookup", metrics, canvas = CanvasExpression.Unknown("runtime Canvas", site("canvas"))),
        lookup("alternatives", metrics, canvas = alternatives),
        compose("recursive", CanvasExpression.Current, "UncertainTile"),
      )
    for (effect in effects) {
      val tile = tile("UncertainTile", effect).copy(multi = true)
      val module =
        ModuleContract(
          "app",
          tiles = listOf(tile),
          callables = listOf(entry("entry", compose("compose", CanvasExpression.Empty, tile.id))),
        )
      val strict = report(module, policy = AnalysisPolicy.STRICT)
      assertFalse(strict.policyDecision.passed, effect.toString())
      for (policy in AnalysisPolicy.entries) {
        val suppressed =
          report(module.copy(tiles = listOf(tile.copy(suppressions = listOf(suppression)))), policy = policy)
        assertTrue(suppressed.policyDecision.passed, suppressed.toString())
        assertTrue(suppressed.policyDecision.warnings.isEmpty())
        assertTrue(suppressed.findings.all { it.suppressedAt == listOf(suppression.site) })
        assertTrue(suppressed.findings.all { it.certainty == Certainty.UNVERIFIED })
        assertEquals(strict.findings, suppressed.findings.map { it.copy(suppressedAt = emptyList()) })
      }
    }
  }

  @Test
  fun `general suppression leaves proven absence and uncertainty outside its scope enforcing`() {
    val suppression = MosaicSuppression(MosaicSuppression.UNVERIFIED, site("suppression"))
    val unknown = Effect.Unknown("unknown", "unsupported dispatch", site("unknown"))
    val tile = tile("SuppressedTile", unknown, lookup("missing", metrics)).copy(suppressions = listOf(suppression))
    val module =
      ModuleContract(
        "app",
        tiles = listOf(tile),
        callables = listOf(entry("entry", compose("compose", CanvasExpression.Empty, tile.id), unknown)),
      )
    val result = report(module, policy = AnalysisPolicy.STRICT)

    assertFalse(result.policyDecision.passed)
    assertEquals(listOf(Certainty.MISSING, Certainty.UNVERIFIED), result.policyDecision.errors.map { it.certainty })
    assertTrue(result.policyDecision.errors.all { it.suppressedAt.isEmpty() })
    assertEquals(1, result.findings.count { it.suppressedAt == listOf(suppression.site) })
    val graph = MosaicGraph.render(AnalysisRequest(module, roots = listOf(SelectedRoot("root", "entry"))))
    assertTrue(graph.contains("UNVERIFIED"))
    assertTrue(graph.contains("Suppressed at suppression.kt:1"))
  }

  @Test
  fun `labeled positive assumptions require explicit strict approval and never prove absence`() {
    val assumption = ExternalAssumption("managed-canvas", setOf(metrics), site("assumption"))
    val assumedCanvas = CanvasExpression.Assumption(assumption.id, site("handler"))
    val both = tile("BothTile", lookup("metrics", metrics), lookup("request", request))
    val module =
      ModuleContract(
        "app",
        tiles = listOf(both),
        callables = listOf(entry("entry", compose("compose", assumedCanvas, both.id))),
      )
    val default = report(module, assumptions = listOf(assumption))
    val strict = report(module, policy = AnalysisPolicy.STRICT, assumptions = listOf(assumption))
    val approved =
      report(
        module,
        policy = AnalysisPolicy.STRICT,
        assumptions = listOf(assumption),
        approvals = setOf(assumption.id),
      )

    val guaranteed = default.findings.single { it.key == metrics }
    val remainder = default.findings.single { it.key == request }
    assertEquals(Certainty.VERIFIED, guaranteed.certainty)
    assertEquals(setOf(assumption.id), guaranteed.assumptionIds)
    assertTrue(EvidenceKind.EXTERNAL_ASSUMPTION in guaranteed.evidence)
    assertEquals(Certainty.UNVERIFIED, remainder.certainty)
    assertTrue(default.policyDecision.passed)
    assertFalse(strict.policyDecision.passed)
    assertFalse(approved.policyDecision.passed)
    assertEquals(1, approved.policyDecision.errors.size)
    assertEquals(request, approved.policyDecision.errors.single().key)
  }

  @Test
  fun `approved assumption passes strict when every selected requirement is guaranteed`() {
    val assumption = ExternalAssumption("managed-canvas", setOf(metrics), site("assumption"))
    val requested = tile("MetricsTile", lookup("metrics", metrics))
    val module =
      ModuleContract(
        "app",
        tiles = listOf(requested),
        callables =
          listOf(
            entry(
              "entry",
              compose("compose", CanvasExpression.Assumption(assumption.id, site("handler")), requested.id),
            ),
          ),
      )
    val result =
      report(
        module,
        policy = AnalysisPolicy.STRICT,
        assumptions = listOf(assumption),
        approvals = setOf(assumption.id),
      )
    val unapproved = report(module, policy = AnalysisPolicy.STRICT, assumptions = listOf(assumption))

    assertTrue(result.policyDecision.passed)
    assertFalse(unapproved.policyDecision.passed)
    assertEquals(Certainty.VERIFIED, unapproved.policyDecision.errors.single().certainty)
  }

  @Test
  fun `report is deterministic across irrelevant selected registry order`() {
    val requested = tile("MetricsTile", lookup("metrics", metrics))
    val app =
      ModuleContract(
        "app",
        tiles = listOf(requested),
        callables =
          listOf(
            entry(
              "entry",
              compose(
                "compose",
                CanvasExpression.Layer(
                  "metrics",
                  CanvasExpression.Empty,
                  listOf(binding(metrics)),
                  site = site("metrics"),
                ),
                requested.id,
              ),
            ),
          ),
      )
    val alpha = ModuleContract("alpha", tiles = listOf(tile("AlphaTile")))
    val omega =
      ModuleContract(
        "omega",
        canvases = listOf(CanvasContract("omegaCanvas", result = CanvasExpression.Empty, site = site("omega"))),
      )

    assertEquals(report(app, dependencies = listOf(alpha, omega)), report(app, dependencies = listOf(omega, alpha)))
  }

  @Test
  fun `missing and invalid construction fail default policy`() {
    val duplicate =
      CanvasExpression.Layer(
        "duplicate",
        CanvasExpression.Empty,
        listOf(binding(metrics, "first"), binding(metrics, "second")),
        site = site("duplicate"),
      )
    val module =
      ModuleContract(
        "app",
        callables = listOf(entry("entry", Effect.ConstructCanvas("construct", duplicate, site("construct")))),
      )

    assertFalse(report(module).policyDecision.passed)
  }
}

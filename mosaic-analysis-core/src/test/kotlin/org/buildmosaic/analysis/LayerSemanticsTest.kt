package org.buildmosaic.analysis

import org.buildmosaic.analysis.AnalysisFixtures.binding
import org.buildmosaic.analysis.AnalysisFixtures.compose
import org.buildmosaic.analysis.AnalysisFixtures.entry
import org.buildmosaic.analysis.AnalysisFixtures.lookup
import org.buildmosaic.analysis.AnalysisFixtures.metrics
import org.buildmosaic.analysis.AnalysisFixtures.report
import org.buildmosaic.analysis.AnalysisFixtures.service
import org.buildmosaic.analysis.AnalysisFixtures.site
import org.buildmosaic.analysis.AnalysisFixtures.tile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@Suppress("FunctionMaxLength", "LargeClass", "MaxLineLength")
class LayerSemanticsTest {
  private val repository = CanvasKeyIdentity("example.Repository")

  @Test
  fun `ancestor construction and nearest child override use exact binding provenance`() {
    val grandparentMetrics = binding(metrics, "grandparent-metrics")
    val parentMetrics = binding(metrics, "parent-metrics")
    val canvas =
      CanvasExpression.Layer(
        "child",
        CanvasExpression.Layer(
          "parent",
          CanvasExpression.Layer(
            "grandparent",
            CanvasExpression.Empty,
            listOf(grandparentMetrics),
            site = site("grandparent"),
          ),
          listOf(parentMetrics),
          site = site("parent"),
        ),
        listOf(
          binding(
            repository,
            "repository",
            listOf(lookup("repository-construction", metrics, LookupKind.CONSTRUCTION)),
          ),
        ),
        site = site("child"),
      )
    val result = analyzeCanvas(canvas, tile("RepositoryTile", lookup("repository", repository)))
    val construction = result.findings.single { it.kind == FindingKind.CONSTRUCTION_LOOKUP }

    assertEquals(site("parent-metrics"), construction.bindingSite)
    assertEquals(listOf("child", "parent"), construction.canvasPath.map { it.layerId })
  }

  @Test
  fun `late local registration wins over parent for eager construction`() {
    val parentMetrics = binding(metrics, "parent-metrics")
    val lateMetrics = binding(metrics, "late-local-metrics")
    val canvas =
      CanvasExpression.Layer(
        "child",
        CanvasExpression.Layer(
          "parent",
          CanvasExpression.Empty,
          listOf(parentMetrics),
          site = site("parent"),
        ),
        listOf(
          binding(service, "consumer", listOf(lookup("construction", metrics, LookupKind.CONSTRUCTION))),
          lateMetrics,
        ),
        site = site("child"),
      )
    val result = analyzeCanvas(canvas, tile("ServiceTile", lookup("service", service)))

    assertEquals(
      site("late-local-metrics"),
      result.findings.single {
        it.kind == FindingKind.CONSTRUCTION_LOOKUP
      }.bindingSite,
    )
  }

  @Test
  fun `child override does not rewire parent constructor`() {
    val parentMetrics = binding(metrics, "old-metrics")
    val parent =
      CanvasExpression.Layer(
        "parent",
        CanvasExpression.Empty,
        listOf(
          parentMetrics,
          binding(
            repository,
            "parent-repository",
            listOf(lookup("parent-construction", metrics, LookupKind.CONSTRUCTION)),
          ),
        ),
        site = site("parent"),
      )
    val child =
      CanvasExpression.Layer(
        "child",
        parent,
        listOf(binding(metrics, "new-metrics")),
        site = site("child"),
      )
    val result =
      analyzeCanvas(
        child,
        tile("BothTile", lookup("repository", repository), lookup("metrics", metrics)),
      )

    assertEquals(site("old-metrics"), result.findings.single { it.kind == FindingKind.CONSTRUCTION_LOOKUP }.bindingSite)
    assertEquals(site("new-metrics"), result.findings.single { it.obligationId.endsWith(":metrics") }.bindingSite)
  }

  @Test
  fun `future child cannot repair failed eager parent construction`() {
    val parent =
      CanvasExpression.Layer(
        "parent",
        CanvasExpression.Empty,
        listOf(binding(repository, "repository", listOf(lookup("construction", metrics, LookupKind.CONSTRUCTION)))),
        site = site("parent"),
      )
    val child =
      CanvasExpression.Layer(
        "child",
        parent,
        listOf(binding(metrics, "too-late")),
        site = site("child"),
      )
    val result = analyzeCanvas(child, tile("RepositoryTile", lookup("repository", repository)))

    assertEquals(metrics, result.findings.single { it.certainty == Certainty.MISSING }.key)
    assertFalse(result.findings.any { it.kind == FindingKind.REQUIRED_LOOKUP })
  }

  @Test
  fun `unused singleton constructors are checked eagerly`() {
    val invalid =
      CanvasExpression.Layer(
        "invalid",
        CanvasExpression.Empty,
        listOf(
          binding(service, "unused-service", listOf(lookup("unused-construction", metrics, LookupKind.CONSTRUCTION))),
        ),
        site = site("invalid"),
      )
    val module =
      ModuleContract(
        "app",
        callables =
          listOf(
            entry(
              "entry",
              Effect.ConstructCanvas("construct", invalid, site("construct")),
            ),
          ),
      )
    val result = report(module)

    assertEquals(FindingKind.CONSTRUCTION_LOOKUP, result.findings.single().kind)
    assertFalse(result.policyDecision.passed)
  }

  @Test
  fun `definite duplicate local keys are invalid construction`() {
    val duplicate =
      CanvasExpression.Layer(
        "duplicate",
        CanvasExpression.Empty,
        listOf(binding(metrics, "first"), binding(metrics, "second")),
        site = site("duplicate"),
      )
    val result = analyzeCanvas(duplicate, tile("MetricsTile", lookup("metrics", metrics)))
    val finding = result.findings.single()

    assertEquals(FindingKind.DUPLICATE_BINDING, finding.kind)
    assertEquals(site("first"), finding.bindingSite)
    assertTrue(finding.reason.contains("duplicate"))
  }

  @Test
  fun `all eager constructors are checked after independent failures`() {
    val first = CanvasKeyIdentity("example.First")
    val second = CanvasKeyIdentity("example.Second")
    val canvas =
      CanvasExpression.Layer(
        "invalid",
        CanvasExpression.Empty,
        listOf(
          binding(first, "first", listOf(lookup("first-construction", metrics, LookupKind.CONSTRUCTION))),
          binding(second, "second", listOf(lookup("second-construction", repository, LookupKind.CONSTRUCTION))),
        ),
        site = site("invalid"),
      )
    val module =
      ModuleContract(
        "app",
        callables = listOf(entry("entry", Effect.ConstructCanvas("construct", canvas, site("construct")))),
      )

    assertEquals(setOf(metrics, repository), report(module).findings.mapNotNull { it.key }.toSet())
  }

  @Test
  fun `failed selected local construction never falls back to parent`() {
    val childMetrics =
      binding(
        metrics,
        "child-metrics",
        listOf(lookup("child-failure", CanvasKeyIdentity("example.Platform"), LookupKind.CONSTRUCTION)),
      )
    val canvas =
      CanvasExpression.Layer(
        "child",
        CanvasExpression.Layer(
          "parent",
          CanvasExpression.Empty,
          listOf(binding(metrics, "parent-metrics")),
          site = site("parent"),
        ),
        listOf(
          childMetrics,
          binding(service, "consumer", listOf(lookup("consumer-construction", metrics, LookupKind.CONSTRUCTION))),
        ),
        site = site("child"),
      )
    val module =
      ModuleContract(
        "app",
        callables = listOf(entry("entry", Effect.ConstructCanvas("construct", canvas, site("construct")))),
      )
    val result = report(module)

    assertEquals(site("child-metrics"), result.findings.single { it.key == metrics }.bindingSite)
    assertEquals(Certainty.MISSING, result.findings.single { it.key?.classId == "example.Platform" }.certainty)
  }

  @Test
  fun `Canvas alias does not repeat eager construction`() {
    val canvas =
      CanvasExpression.Alias(
        "shared-canvas",
        CanvasExpression.Layer(
          "shared",
          CanvasExpression.Empty,
          listOf(
            binding(metrics),
            binding(service, "service", listOf(lookup("construction", metrics, LookupKind.CONSTRUCTION))),
          ),
          site = site("shared"),
        ),
      )
    val requested = tile("ServiceTile", lookup("service", service))
    val module =
      ModuleContract(
        "app",
        tiles = listOf(requested),
        callables =
          listOf(
            entry(
              "entry",
              compose("first", canvas, requested.id),
              compose("second", canvas, requested.id),
            ),
          ),
      )
    val result = report(module)

    assertEquals(1, result.findings.count { it.kind == FindingKind.CONSTRUCTION_LOOKUP })
    assertEquals(2, result.findings.count { it.kind == FindingKind.REQUIRED_LOOKUP })
  }

  private fun analyzeCanvas(
    canvas: CanvasExpression,
    tile: TileContract,
  ): AnalysisReport =
    report(
      ModuleContract(
        "app",
        tiles = listOf(tile),
        callables = listOf(entry("entry", compose("compose", canvas, tile.id))),
      ),
    )
}

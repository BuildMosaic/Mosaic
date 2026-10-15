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
class RootScopeTest {
  private val parent = ContractParameter("services", "parent", ParameterKind.CANVAS)

  @Test
  fun `public reusable helper is deferred and does not fail a good selected entry`() {
    val helper = servicesContract()
    val goodCanvas = layerWith(metrics, "good-parent")
    val goodEntry = entry("entry", compose("compose", goodCanvas, "MetricsTile"))
    val module =
      ModuleContract(
        "app",
        canvases = listOf(helper),
        tiles = listOf(tile("MetricsTile", lookup("metrics", metrics))),
        callables = listOf(goodEntry),
      )
    val result = report(module)

    assertEquals(RootStatus.VERIFIED, result.roots.single().status)
    assertTrue("services" in result.deferredContracts)
    assertFalse("services" in result.roots.single().specializedContracts)
  }

  @Test
  fun `same helper specializes independently with good and bad parents`() {
    val helper = servicesContract()
    val good =
      SelectedRoot(
        "good",
        helper.id,
        CallArguments(mapOf(parent to ArgumentExpression.Canvas(layerWith(metrics, "good-parent")))),
      )
    val bad =
      SelectedRoot(
        "bad",
        helper.id,
        CallArguments(mapOf(parent to ArgumentExpression.Canvas(CanvasExpression.Empty))),
      )
    val result = report(ModuleContract("app", canvases = listOf(helper)), roots = listOf(good, bad))

    assertEquals(RootStatus.FAILED, result.roots.single { it.root.id == "bad" }.status)
    assertEquals(RootStatus.VERIFIED, result.roots.single { it.root.id == "good" }.status)
  }

  @Test
  fun `selected helper with unresolved Canvas input is unverified`() {
    val result =
      report(
        ModuleContract("app", canvases = listOf(servicesContract())),
        roots = listOf(SelectedRoot("handler", "services")),
      )

    assertEquals(RootStatus.UNVERIFIED, result.roots.single().status)
    assertTrue(result.findings.any { it.kind == FindingKind.CONSTRUCTION_LOOKUP })
  }

  @Test
  fun `no roots is unconfigured with no verified application roots`() {
    val result = report(ModuleContract("library", tiles = listOf(tile("DeferredTile"))), roots = emptyList())

    assertEquals(ConfigurationStatus.UNCONFIGURED, result.configurationStatus)
    assertTrue(result.roots.isEmpty())
    assertFalse(result.policyDecision.passed)
    assertEquals(listOf("DeferredTile"), result.deferredContracts)
  }

  @Test
  fun `unrelated provider contract is never added to selected Canvas`() {
    val other =
      CanvasContract(
        "otherCanvas",
        result = layerWith(metrics, "other"),
        site = site("otherCanvas"),
      )
    val requested = tile("MetricsTile", lookup("metrics", metrics))
    val module =
      ModuleContract(
        "app",
        canvases = listOf(other),
        tiles = listOf(requested),
        callables = listOf(entry("entry", compose("compose", CanvasExpression.Empty, requested.id))),
      )

    assertEquals(Certainty.MISSING, report(module).findings.single().certainty)
  }

  @Test
  fun `helper aliases remain independent across invocation contexts`() {
    val helper =
      servicesContract().copy(
        result = CanvasExpression.Alias("services-result", servicesContract().result),
      )
    val goodCall =
      CanvasExpression.RuntimeCall(
        helper.id,
        CallArguments(mapOf(parent to ArgumentExpression.Canvas(layerWith(metrics, "good-parent")))),
        site("good-call"),
      )
    val badCall =
      CanvasExpression.RuntimeCall(
        helper.id,
        CallArguments(mapOf(parent to ArgumentExpression.Canvas(CanvasExpression.Empty))),
        site("bad-call"),
      )
    val module =
      ModuleContract(
        "app",
        canvases = listOf(helper),
        callables =
          listOf(
            entry(
              "entry",
              Effect.ConstructCanvas("good", goodCall, site("good")),
              Effect.ConstructCanvas("bad", badCall, site("bad")),
            ),
          ),
      )
    val result = report(module)

    assertTrue(result.findings.any { it.certainty == Certainty.VERIFIED })
    assertTrue(result.findings.any { it.certainty == Certainty.MISSING })
  }

  private fun servicesContract() =
    CanvasContract(
      "services",
      listOf(parent),
      CanvasExpression.Layer(
        "services-layer",
        CanvasExpression.ParameterValue(parent),
        listOf(binding(service, "service", listOf(lookup("construction", metrics, LookupKind.CONSTRUCTION)))),
        site = site("services"),
      ),
      site("services"),
    )

  private fun layerWith(
    key: CanvasKeyIdentity,
    id: String,
  ) = CanvasExpression.Layer(id, CanvasExpression.Empty, listOf(binding(key, "$id-binding")), site = site(id))
}

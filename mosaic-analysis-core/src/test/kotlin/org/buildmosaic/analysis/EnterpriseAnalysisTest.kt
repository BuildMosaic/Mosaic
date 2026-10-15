package org.buildmosaic.analysis

import org.buildmosaic.analysis.AnalysisFixtures.binding
import org.buildmosaic.analysis.AnalysisFixtures.compose
import org.buildmosaic.analysis.AnalysisFixtures.entry
import org.buildmosaic.analysis.AnalysisFixtures.global
import org.buildmosaic.analysis.AnalysisFixtures.lookup
import org.buildmosaic.analysis.AnalysisFixtures.metrics
import org.buildmosaic.analysis.AnalysisFixtures.platform
import org.buildmosaic.analysis.AnalysisFixtures.report
import org.buildmosaic.analysis.AnalysisFixtures.request
import org.buildmosaic.analysis.AnalysisFixtures.service
import org.buildmosaic.analysis.AnalysisFixtures.site
import org.buildmosaic.analysis.AnalysisFixtures.tile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@Suppress("FunctionMaxLength", "LargeClass", "MaxLineLength")
class EnterpriseAnalysisTest {
  private val applicationParent = ContractParameter("applicationLayer", "parent", ParameterKind.CANVAS)

  @Test
  fun `enterprise composition verifies transitive sources and eager construction`() {
    val result = enterpriseReport(setOf(global, metrics, platform))

    assertEquals(RootStatus.VERIFIED, result.roots.single().status)
    assertEquals(6, result.findings.count { it.certainty == Certainty.VERIFIED })
    assertTrue(result.policyDecision.passed)
    assertTrue("applicationLayer" in result.deferredContracts)
    assertTrue("applicationLayer" in result.roots.single().specializedContracts)
  }

  @Test
  fun `removing Tile-only platform dependency reports exact missing source`() {
    val result = enterpriseReport(setOf(global, metrics))
    val missing = result.findings.single { it.certainty == Certainty.MISSING }

    assertEquals(platform, missing.key)
    assertEquals(FindingKind.REQUIRED_LOOKUP, missing.kind)
    assertEquals(listOf("request", "application", "platform"), missing.canvasPath.map { it.layerId })
  }

  @Test
  fun `missing constructor dependency blocks request composition at earlier construction`() {
    val result = enterpriseReport(setOf(global, platform))
    val missing = result.findings.single { it.certainty == Certainty.MISSING }

    assertEquals(metrics, missing.key)
    assertEquals(FindingKind.CONSTRUCTION_LOOKUP, missing.kind)
    assertFalse(result.findings.any { it.key == platform && it.kind == FindingKind.REQUIRED_LOOKUP })
  }

  private fun enterpriseReport(platformKeys: Set<CanvasKeyIdentity>): AnalysisReport {
    val platformContract =
      CanvasContract(
        "platformCanvas",
        result =
          CanvasExpression.Layer(
            "platform",
            CanvasExpression.Empty,
            platformKeys.sortedBy { it.classId }.map { binding(it, "platform-${it.classId}") },
            site = site("platformCanvas"),
          ),
        site = site("platformCanvas"),
      )
    val applicationContract =
      CanvasContract(
        "applicationLayer",
        listOf(applicationParent),
        CanvasExpression.Layer(
          "application",
          CanvasExpression.ParameterValue(applicationParent),
          listOf(binding(service, "service-binding", listOf(lookup("init", metrics, LookupKind.CONSTRUCTION)))),
          site = site("applicationLayer"),
        ),
        site("applicationLayer"),
      )
    val platformCall = CanvasExpression.RuntimeCall("platformCanvas", site = site("platform-call"))
    val applicationCall =
      CanvasExpression.RuntimeCall(
        "applicationLayer",
        CallArguments(mapOf(applicationParent to ArgumentExpression.Canvas(platformCall))),
        site("application-call"),
      )
    val requestCanvas =
      CanvasExpression.Layer(
        "request",
        applicationCall,
        listOf(binding(request, "request-binding")),
        site = site("request-layer"),
      )
    val enterpriseTile =
      tile(
        "EnterpriseTile",
        lookup("global", global),
        lookup("metrics", metrics),
        lookup("platform", platform),
        lookup("service", service),
        lookup("request", request),
      )
    val module =
      ModuleContract(
        "app",
        canvases = listOf(platformContract, applicationContract),
        tiles = listOf(enterpriseTile),
        callables = listOf(entry("entry", compose("enterprise", requestCanvas, enterpriseTile.id))),
      )
    return report(module, policy = AnalysisPolicy.STRICT)
  }
}

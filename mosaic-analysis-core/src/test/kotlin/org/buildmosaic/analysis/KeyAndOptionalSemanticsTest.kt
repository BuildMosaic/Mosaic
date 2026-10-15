package org.buildmosaic.analysis

import org.buildmosaic.analysis.AnalysisFixtures.binding
import org.buildmosaic.analysis.AnalysisFixtures.compose
import org.buildmosaic.analysis.AnalysisFixtures.entry
import org.buildmosaic.analysis.AnalysisFixtures.report
import org.buildmosaic.analysis.AnalysisFixtures.site
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@Suppress("FunctionMaxLength", "LargeClass", "MaxLineLength")
class KeyAndOptionalSemanticsTest {
  @Test
  fun `qualifier identity distinguishes null empty and named values`() {
    val primary = CanvasKeyIdentity("example.Metrics", "primary")
    val secondary = CanvasKeyIdentity("example.Metrics", "secondary")
    val namedResult = analyze(listOf(binding(primary)), Fact.Known(secondary))
    val emptyResult =
      analyze(
        listOf(binding(CanvasKeyIdentity("example.Metrics", ""))),
        Fact.Known(CanvasKeyIdentity("example.Metrics", null)),
      )

    assertEquals(Certainty.MISSING, namedResult.findings.single().certainty)
    assertEquals(secondary, namedResult.findings.single().key)
    assertEquals(Certainty.MISSING, emptyResult.findings.single().certainty)
  }

  @Test
  fun `unknown required qualifier remains unverified`() {
    val unknown = Fact.Unknown("computed qualifier", site("dynamic-qualifier"))
    val result = analyze(listOf(binding(CanvasKeyIdentity("example.Metrics", "primary"))), unknown)

    assertEquals(Certainty.UNVERIFIED, result.findings.single().certainty)
  }

  @Test
  fun `optional absence has no obligation and unknown key stays unverified`() {
    val knownAbsent = analyze(emptyList(), Fact.Known(CanvasKeyIdentity("example.Metrics")), LookupKind.OPTIONAL)
    val unknown = analyze(emptyList(), Fact.Unknown("computed", site("computed")), LookupKind.OPTIONAL)

    assertEquals(Certainty.VERIFIED, knownAbsent.findings.single().certainty)
    assertEquals(Certainty.UNVERIFIED, unknown.findings.single().certainty)
    assertNull(unknown.findings.single().key)
  }

  @Test
  fun `exact class identity never applies subtype matching`() {
    val interfaceResult =
      analyze(
        listOf(binding(CanvasKeyIdentity("example.ConcreteService"))),
        Fact.Known(CanvasKeyIdentity("example.ServiceInterface")),
      )

    assertEquals(Certainty.MISSING, interfaceResult.findings.single().certainty)
  }

  private fun analyze(
    bindings: List<Binding>,
    key: Fact<CanvasKeyIdentity>,
    kind: LookupKind = LookupKind.REQUIRED,
  ): AnalysisReport {
    val canvas = CanvasExpression.Layer("layer", CanvasExpression.Empty, bindings, site = site("layer"))
    val effect = Effect.Lookup("lookup", CanvasExpression.Current, key, kind, site("lookup"))
    val tile = TileContract("Tile", listOf(effect), site("Tile"))
    return report(
      ModuleContract(
        "app",
        tiles = listOf(tile),
        callables = listOf(entry("entry", compose("compose", canvas, tile.id))),
      ),
    )
  }
}

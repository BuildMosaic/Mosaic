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
class IdentityAndDiscoveryTest {
  @Test
  fun `distinct same-result Tiles and aliases retain declaration identity`() {
    val first = tile("FirstStringTile", lookup("metrics", metrics))
    val second = tile("SecondStringTile", lookup("request", request))
    val canvas = layerWith(metrics)
    val effects =
      listOf(
        composeWith("first", canvas, TileReference.Alias(TileReference.Stable(first.id))),
        composeWith("second", canvas, TileReference.Stable(second.id)),
      )
    val result =
      report(
        ModuleContract(
          "app",
          tiles = listOf(first, second),
          callables = listOf(entry("entry", *effects.toTypedArray())),
        ),
      )

    assertEquals(Certainty.VERIFIED, result.findings.single { it.key == metrics }.certainty)
    assertEquals(Certainty.MISSING, result.findings.single { it.key == request }.certainty)
    assertTrue(result.findings.any { it.obligationId.startsWith("FirstStringTile:") })
  }

  @Test
  fun `fresh allocation invocations and receiver contexts never share finding identity`() {
    val template = tile("StringTileTemplate", lookup("metrics", metrics))
    val effects =
      listOf(
        composeWith("fresh-one", CanvasExpression.Empty, TileReference.Fresh(template.id, "allocation", "one")),
        composeWith("fresh-two", CanvasExpression.Empty, TileReference.Fresh(template.id, "allocation", "two")),
        composeWith("receiver-one", CanvasExpression.Empty, TileReference.Stable(template.id, "receiver-one")),
        composeWith("receiver-two", CanvasExpression.Empty, TileReference.Stable(template.id, "receiver-two")),
      )
    val result =
      report(
        ModuleContract("app", tiles = listOf(template), callables = listOf(entry("entry", *effects.toTypedArray()))),
      )

    assertEquals(4, result.findings.map { it.obligationId }.toSet().size)
    assertTrue(result.findings.all { it.certainty == Certainty.MISSING })
  }

  @Test
  fun `same fresh factory callsite in separate callable activations has separate runtime identity`() {
    val template = tile("LocalTemplate", lookup("metrics", metrics))
    val helper =
      entry(
        "helper",
        composeWith("fresh", CanvasExpression.Empty, TileReference.Fresh(template.id, "factory", "helper")),
      )
    val root =
      entry(
        "entry",
        Effect.Call("first", helper.id, site = site("first")),
        Effect.Call("second", helper.id, site = site("second")),
      )
    val result = report(ModuleContract("app", tiles = listOf(template), callables = listOf(root, helper)))
    val identities = result.findings.map { it.dependencyPath.last().label }

    assertEquals(2, identities.size, result.toString())
    assertEquals(2, identities.toSet().size, result.toString())
    assertTrue(result.findings.all { it.certainty == Certainty.MISSING })
  }

  @Test
  fun `known-empty MultiTile imports no body while unknown execution stays conditional`() {
    val multi = tile("MetricsMultiTile", lookup("metrics", metrics))
    val empty = compose("empty", CanvasExpression.Empty, multi.id, MultiTileExecution.KNOWN_EMPTY)
    val unknown = compose("unknown", CanvasExpression.Empty, multi.id, MultiTileExecution.UNKNOWN)
    val nonEmpty = compose("non-empty", CanvasExpression.Empty, multi.id, MultiTileExecution.KNOWN_NON_EMPTY)
    val emptyResult = report(ModuleContract("app", tiles = listOf(multi), callables = listOf(entry("entry", empty))))
    val unknownResult =
      report(ModuleContract("app", tiles = listOf(multi), callables = listOf(entry("entry", unknown))))
    val nonEmptyResult =
      report(ModuleContract("app", tiles = listOf(multi), callables = listOf(entry("entry", nonEmpty))))

    assertTrue(emptyResult.findings.isEmpty())
    assertEquals(Certainty.UNVERIFIED, unknownResult.findings.single().certainty)
    assertEquals(Certainty.MISSING, nonEmptyResult.findings.single().certainty)
  }

  @Test
  fun `Tile can compose dependency on separately constructed Canvas`() {
    val leaf = tile("LeafTile", lookup("metrics", metrics))
    val outer =
      tile(
        "OuterTile",
        compose("inner", layerWith(metrics), leaf.id),
      )
    val result =
      report(
        ModuleContract(
          "app",
          tiles = listOf(leaf, outer),
          callables = listOf(entry("entry", compose("outer", CanvasExpression.Empty, outer.id))),
        ),
      )

    assertEquals(Certainty.VERIFIED, result.findings.single().certainty)
    assertEquals(
      listOf("COMPOSE:OuterTile", "COMPOSE:LeafTile"),
      result.findings.single().dependencyPath.map { it.label },
    )
  }

  @Test
  fun `recursive async discovery terminates without fabricated deadlock diagnosis`() {
    val composeB = compose("to-b", CanvasExpression.Current, "TileB", discovery = DiscoveryKind.COMPOSE_ASYNC)
    val composeA = compose("to-a", CanvasExpression.Current, "TileA", discovery = DiscoveryKind.COMPOSE_ASYNC)
    val tileA = tile("TileA", composeB)
    val tileB = tile("TileB", composeA)
    val result =
      report(
        ModuleContract(
          "app",
          tiles = listOf(tileA, tileB),
          callables = listOf(entry("entry", compose("start", CanvasExpression.Empty, tileA.id))),
        ),
      )

    assertEquals(MosaicRule.MOSAIC_RECURSIVE_TILE, result.findings.single().rule)
    assertTrue(result.findings.single().reason.contains("Recursive Tile structure"))
    assertFalse(result.findings.single().reason.contains("deadlock", ignoreCase = true))
  }

  @Test
  fun `same Tile on fresh Canvas is structural recursion without same Mosaic proof`() {
    val freshCanvas =
      CanvasExpression.Layer(
        "fresh",
        CanvasExpression.Empty,
        listOf(binding(metrics)),
        site = site("fresh"),
      )
    val recursive = tile("RecursiveTile", compose("fresh-call", freshCanvas, "RecursiveTile"))
    val result =
      report(
        ModuleContract(
          "app",
          tiles = listOf(recursive),
          callables = listOf(entry("entry", compose("start", CanvasExpression.Empty, recursive.id))),
        ),
        limits = AnalysisLimits(expansionDepth = 2),
      )

    assertEquals(MosaicRule.MOSAIC_RECURSIVE_TILE, result.findings.single().rule)
    assertTrue(result.findings.single().reason.contains("not proven"))
  }

  private fun composeWith(
    id: String,
    canvas: CanvasExpression,
    reference: TileReference,
  ) = Effect.Compose(id, canvas, reference, site = site(id))

  private fun layerWith(key: CanvasKeyIdentity) =
    CanvasExpression.Layer("layer", CanvasExpression.Empty, listOf(binding(key)), site = site("layer"))
}

package org.buildmosaic.analysis

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@Suppress("FunctionMaxLength")
class SummaryWireValidationTest {
  @Test
  fun `unsupported contract versions and legacy formats are rejected with regeneration guidance`() {
    val text = SummaryCodec.encode(ModuleContract("sample"), coreAnalysisRevisions = setOf(1)).decodeToString()
    for (header in listOf("\"contractVersion\":5", "\"contractVersion\":999", "\"formatVersion\":5")) {
      val unsupported = text.replace("\"contractVersion\":6", header)
      val error = assertFailsWith<IllegalArgumentException> { SummaryCodec.decode(unsupported.toByteArray()) }
      assertTrue(error.message.orEmpty().contains("Unsupported Mosaic metadata contract version"))
      assertTrue(error.message.orEmpty().contains("expected 6."))
      assertTrue(
        error.message.orEmpty().contains("Regenerate dependency summaries with the matching Mosaic analysis version"),
      )
      if (header.startsWith("\"formatVersion\"")) assertTrue(error.message.orEmpty().contains("legacy format 5"))
    }
  }

  @Test
  fun `unordered declarations canonicalize while effect sequence remains significant`() {
    val site = SourceLocation("sample", "Sample.kt", 1, 1)
    val first =
      TileContract("a", listOf(Effect.Unknown("first", "reason", site), Effect.Unknown("second", "reason", site)), site)
    val second = TileContract("b", emptyList(), site)
    val ordered =
      SummaryCodec.encode(
        ModuleContract("sample", tiles = listOf(first, second)),
        coreAnalysisRevisions = setOf(1),
      )
    val reverseDeclarations =
      SummaryCodec.encode(
        ModuleContract("sample", tiles = listOf(second, first)),
        coreAnalysisRevisions = setOf(1),
      )
    val reverseEffects =
      SummaryCodec.encode(
        ModuleContract("sample", tiles = listOf(first.copy(effects = first.effects.reversed()), second)),
        coreAnalysisRevisions = setOf(1),
      )
    assertTrue(ordered.contentEquals(reverseDeclarations))
    assertTrue(!ordered.contentEquals(reverseEffects))
  }

  @Test
  fun `codec round trip preserves representative analyzer findings`() {
    val site = SourceLocation("entry", "Entry.kt", 2, 1)
    val key = CanvasKeyIdentity("example.Required")
    val tile =
      TileContract(
        "tile",
        listOf(Effect.Lookup("lookup", CanvasExpression.Current, Fact.Known(key, site), LookupKind.REQUIRED, site)),
        site,
      )
    val entry =
      CallableContract(
        "entry",
        effects = listOf(Effect.Compose("compose", CanvasExpression.Empty, TileReference.Stable("tile"), site = site)),
        site = site,
      )
    val module = ModuleContract("sample", tiles = listOf(tile), callables = listOf(entry))
    val root = SelectedRoot("root", "entry")
    val before = MosaicAnalyzer().analyze(AnalysisRequest(module, roots = listOf(root)))
    val after =
      MosaicAnalyzer().analyze(
        AnalysisRequest(
          SummaryCodec.decode(SummaryCodec.encode(module, coreAnalysisRevisions = setOf(1))).module,
          roots = listOf(root),
        ),
      )
    assertEquals(before, after)
    assertTrue(before.findings.isNotEmpty())
  }

  @Test
  fun `unknown effect variants fail semantic decoding with a valid checksum`() {
    val site = SourceLocation("tile", "Tile.kt", 1, 1)
    val module =
      ModuleContract(
        "sample",
        tiles = listOf(TileContract("tile", listOf(Effect.Unknown("unknown", "reason", site)), site)),
      )
    val text = SummaryCodec.encode(module, coreAnalysisRevisions = setOf(1)).decodeToString()
    val mutated = withPayloadHash(text.replace("\"kind\":\"unknown\"", "\"kind\":\"futureEffect\""))
    val error = assertFailsWith<IllegalArgumentException> { SummaryCodec.decode(mutated.toByteArray()) }
    assertTrue(error.message.orEmpty().contains("futureEffect"), error.message)
  }

  @Test
  fun `checksum corruption and truncated JSON are rejected`() {
    val text = SummaryCodec.encode(ModuleContract("sample"), coreAnalysisRevisions = setOf(1)).decodeToString()
    val corrupt = text.replace(Regex("\"payloadHash\":\"[0-9a-f]+\""), "\"payloadHash\":\"invalid\"")
    val error = assertFailsWith<IllegalArgumentException> { SummaryCodec.decode(corrupt.toByteArray()) }
    assertTrue(error.message.orEmpty().contains("payload hash mismatch"))
    assertFailsWith<IllegalArgumentException> { SummaryCodec.decode(text.dropLast(4).toByteArray()) }
  }
}

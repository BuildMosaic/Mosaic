package org.buildmosaic.analysis

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@Suppress("FunctionMaxLength")
class SummaryWireValidationTest {
  @Test
  fun `metadata declares separate format semantics producer and payload boundary`() {
    val text = SummaryCodec.encode(ModuleContract("sample")).decodeToString()
    for (field in listOf(
      "formatVersion", "semanticsVersion", "toolVersion", "kotlinCompilerVersion", "moduleId",
      "sourceSet", "complete", "payloadHash", "payload",
    )) {
      assertTrue(text.contains("\"$field\":"), field)
    }
  }

  @Test
  fun `legacy metadata is rejected with regeneration guidance`() {
    val prototype =
      """
      {"schemaMajor":1,"schemaMinor":1,"toolVersion":"prototype-7","kotlinCompilerVersion":"2.4.20",
      "moduleId":"sample","sourceSet":"main","complete":true,"payloadHash":"unused",
      "module":{"id":"sample","canvases":[],"tiles":[],"callables":[],"overrides":[]}}
      """.trimIndent()
    val error = assertFailsWith<IllegalArgumentException> { SummaryCodec.decode(prototype.toByteArray()) }
    assertTrue(error.message.orEmpty().contains("Unsupported Mosaic metadata format"))
    assertTrue(
      error.message.orEmpty().contains("Regenerate dependency summaries with the matching Mosaic analysis version"),
    )
  }

  @Test
  fun `unordered declarations canonicalize while effect sequence remains significant`() {
    val site = SourceLocation("sample", "Sample.kt", 1, 1)
    val first =
      TileContract("a", listOf(Effect.Unknown("first", "reason", site), Effect.Unknown("second", "reason", site)), site)
    val second = TileContract("b", emptyList(), site)
    val ordered = SummaryCodec.encode(ModuleContract("sample", tiles = listOf(first, second)))
    val reverseDeclarations = SummaryCodec.encode(ModuleContract("sample", tiles = listOf(second, first)))
    val reverseEffects =
      SummaryCodec.encode(
        ModuleContract("sample", tiles = listOf(first.copy(effects = first.effects.reversed()), second)),
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
        AnalysisRequest(SummaryCodec.decode(SummaryCodec.encode(module)).module, roots = listOf(root)),
      )
    assertEquals(before, after)
    assertTrue(before.findings.isNotEmpty())
  }

  @Test
  fun `malformed kinds and damaged payload are rejected`() {
    val site = SourceLocation("tile", "Tile.kt", 1, 1)
    val module =
      ModuleContract(
        "sample",
        tiles = listOf(TileContract("tile", listOf(Effect.Unknown("unknown", "reason", site)), site)),
      )
    val text = SummaryCodec.encode(module).decodeToString()
    for (mutated in listOf(
      text.replace("\"kind\":\"unknown\"", "\"kind\":\"futureEffect\""),
      text.replace("\"reason\":\"reason\"", "\"reason\":\"other\""),
      text.replace("\"complete\":true", "\"complete\":false"),
      text.dropLast(4),
    )) {
      assertFailsWith<IllegalArgumentException> { SummaryCodec.decode(mutated.toByteArray()) }
    }
  }
}

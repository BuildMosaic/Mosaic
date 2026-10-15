package org.buildmosaic.analysis

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@Suppress("FunctionMaxLength")
class SummaryWireCorpusTest {
  @Test
  fun `closed wire corpus preserves variants provenance and binary exports`() {
    val site = SourceLocation("owner", "File.kt", 7, 3)
    val key = CanvasKeyIdentity("example.Service", "")
    val canvasParameter = ContractParameter("helper", "canvas", ParameterKind.CANVAS)
    val booleanParameter = ContractParameter("helper", "enabled", ParameterKind.BOOLEAN)
    val constantParameter = ContractParameter("helper", "constant", ParameterKind.BOOLEAN)
    val opaqueParameter = ContractParameter("helper", "opaque", ParameterKind.BOOLEAN)
    val inline = CaptureOrigin.Inline("helper", "producer.jar", "hash")
    val constant = CaptureOrigin.Constant("literal", "producer.jar", "value")
    val known = Fact.Known(key, site, EvidenceKind.CAPTURED_FACT, inline)
    val unknown = Fact.Unknown("dynamic key", site)
    val arguments =
      CallArguments(
        linkedMapOf(
          canvasParameter to ArgumentExpression.Canvas(CanvasExpression.ParameterValue(canvasParameter)),
          booleanParameter to ArgumentExpression.BooleanValue(BooleanExpression.ParameterValue(booleanParameter)),
          constantParameter to ArgumentExpression.BooleanValue(BooleanExpression.Constant(true)),
          opaqueParameter to ArgumentExpression.BooleanValue(BooleanExpression.Opaque("dynamic", site)),
        ),
      )
    val context = CorpusContext(site, canvasParameter, booleanParameter, known, unknown, inline, constant, arguments)
    val canvases = corpusCanvases(context)
    val effects = corpusEffects(context, canvases)
    val module = corpusModule(context, canvases, effects)
    val bytes =
      SummaryCodec.encode(
        module,
        limitations = listOf("external const origin", "capture uncertain"),
        binaryLocators = mapOf("export" to "example/Exports#tile"),
        coreAnalysisRevisions = setOf(1),
      )
    val restored = SummaryCodec.decode(bytes)
    assertEquals(6, restored.contractVersion)
    assertEquals(ANALYSIS_KOTLIN_VERSION, restored.producer.compilerVersion)
    assertTrue(restored.producer.analysisVersion.isNotBlank())
    assertTrue(bytes.decodeToString().contains("\"lookupKind\":\"CONSTRUCTION\""))
    assertEquals(module, restored.module)
    assertEquals(mapOf("export" to "example/Exports#tile"), restored.binaryLocators)
    assertEquals(listOf("capture uncertain", "external const origin"), restored.limitations)
    assertTrue(
      bytes.contentEquals(
        SummaryCodec.encode(
          module,
          limitations = listOf("capture uncertain", "external const origin"),
          binaryLocators = restored.binaryLocators,
          coreAnalysisRevisions = setOf(1),
        ),
      ),
    )
  }
}

private fun corpusModule(
  context: CorpusContext,
  canvases: List<CanvasExpression>,
  effects: List<Effect>,
): ModuleContract =
  with(context) {
    ModuleContract(
      "corpus",
      canvases =
        canvases.mapIndexed {
            index,
            canvas,
          ->
          CanvasContract("canvas$index", listOf(canvasParameter, booleanParameter), canvas, site, false)
        }.sortedBy { it.id },
      tiles =
        listOf(
          TileContract(
            "tile",
            effects,
            site,
            false,
            true,
            listOf(MosaicSuppression("MOSAIC_RECURSIVE_MULTITILE", site)),
          ),
        ),
      callables = listOf(CallableContract("helper", listOf(canvasParameter, booleanParameter), effects, site, false)),
      overrides =
        listOf(
          ResolvedOverride(
            "Owner",
            "base",
            "impl",
            listOf(OverrideSlot(canvasParameter, ContractParameter("impl", "renamed", ParameterKind.CANVAS), 0)),
          ),
        ),
    )
  }

private fun corpusCanvases(context: CorpusContext): List<CanvasExpression> =
  with(context) {
    listOf<CanvasExpression>(
      CanvasExpression.Empty, CanvasExpression.Current, CanvasExpression.ParameterValue(canvasParameter),
      CanvasExpression.Layer(
        "layer",
        CanvasExpression.Empty,
        listOf(Binding(known, listOf(Effect.Unknown("init", "unknown", site)), site)),
        listOf(UnknownRegistration("registration", site)),
        site,
      ),
      CanvasExpression.Choice(
        Guard.BooleanParameter(booleanParameter, false),
        CanvasExpression.Current,
        CanvasExpression.Empty,
      ),
      CanvasExpression.Choice(Guard.Opaque("guard", site), CanvasExpression.Empty, CanvasExpression.Current),
      CanvasExpression.Choice(Guard.Constant(true), CanvasExpression.Current, CanvasExpression.Empty),
      CanvasExpression.RuntimeCall(
        "helper",
        arguments,
        site,
        listOf(Effect.Unknown("argument", "unknown", site)),
        DispatchReceiver.Concrete("Owner"),
        true,
      ),
      CanvasExpression.ValueReference("value", site),
      CanvasExpression.WithEffects(listOf(Effect.Unknown("effect", "unknown", site)), CanvasExpression.Current),
      CanvasExpression.Captured("capture", constant, CanvasExpression.Current, false, site),
      CanvasExpression.Assumption("assumption", site),
      CanvasExpression.Alias("alias", CanvasExpression.Empty),
      CanvasExpression.Unknown("canvas", site),
    )
  }

private fun corpusEffects(
  context: CorpusContext,
  canvases: List<CanvasExpression>,
): List<Effect> =
  with(context) {
    val tiles =
      listOf<TileReference>(
        TileReference.Stable("stable", "receiver"),
        TileReference.ExportedProperty("export", site),
        TileReference.Alias(TileReference.Stable("aliased")),
        TileReference.Fresh("template", "allocation", "invocation"),
        TileReference.Unknown("tile", site),
      )
    listOf<Effect>(
      Effect.Lookup("required", canvases[3], known, LookupKind.REQUIRED, site),
      Effect.Lookup("optional", CanvasExpression.Current, unknown, LookupKind.OPTIONAL, site),
      Effect.Lookup("construction", CanvasExpression.Current, known, LookupKind.CONSTRUCTION, site),
      Effect.Lookup(
        "runtime", CanvasExpression.Current,
        Fact.Known(
          known.value, site,
          EvidenceKind.RUNTIME_MODEL,
        ),
        LookupKind.REQUIRED, site,
      ),
      Effect.Lookup(
        "assumed", CanvasExpression.Current,
        Fact.Known(
          known.value, site,
          EvidenceKind.EXTERNAL_ASSUMPTION,
        ),
        LookupKind.REQUIRED, site,
      ),
      Effect.EstablishMosaic("fresh", CanvasExpression.Empty, site),
      Effect.ConstructCanvas("construct", canvases[7], site),
      Effect.Call("call", "helper", arguments, site, DispatchReceiver.Forwarded, true),
      Effect.Call("unknownReceiver", "helper", site = site, receiver = DispatchReceiver.Unknown("receiver")),
      Effect.Branch(
        "branch", Guard.Opaque("condition", site), listOf(Effect.Unknown("true", "unknown", site)),
        listOf(Effect.Unknown("false", "unknown", site)), site,
      ),
      Effect.Captured("captured", "owner", inline, listOf(Effect.Unknown("inside", "unknown", site)), true, site),
      Effect.Unknown("unknown", "effect", site),
    ) +
      tiles.mapIndexed {
          index,
          tile,
        ->
        Effect.Compose(
          "compose$index", CanvasExpression.Current, tile, DiscoveryKind.COMPOSE_ASYNC,
          MultiTileExecution.entries[index % MultiTileExecution.entries.size], site,
          listOf(MosaicProvenance.Current, MosaicProvenance.Established("fresh"), MosaicProvenance.Unknown)[index % 3],
        )
      }
  }

private data class CorpusContext(
  val site: SourceLocation,
  val canvasParameter: ContractParameter,
  val booleanParameter: ContractParameter,
  val known: Fact.Known<CanvasKeyIdentity>,
  val unknown: Fact.Unknown,
  val inline: CaptureOrigin.Inline,
  val constant: CaptureOrigin.Constant,
  val arguments: CallArguments,
)

package org.buildmosaic.analysis

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@Suppress("FunctionMaxLength")
class SummaryMetadataTest {
  @Test
  fun `H3 absent required metadata cannot acquire optimistic defaults`() {
    val bytes = SummaryCodec.encode(ModuleContract("sample"), coreAnalysisRevisions = setOf(1)).decodeToString()
    for (field in listOf(
      "complete",
      "analysisVersion",
      "contractVersion",
      "moduleId",
      "payloadHash",
      "compilerVersion",
      "sourceSet",
    )) {
      val partial = bytes.replace(Regex("\"$field\":(?:\"[^\"]*\"|true|[0-9]+),?"), "").replace(",}", "}")
      assertFailsWith<IllegalArgumentException>(field) { SummaryCodec.decode(partial.toByteArray()) }
    }
    assertFailsWith<IllegalArgumentException> {
      SummaryCodec.decode(bytes.replace("\"producer\":", "\"missingProducer\":").toByteArray())
    }
    assertFailsWith<IllegalArgumentException> {
      SummaryCodec.decode(bytes.replace("\"payload\":", "\"missingPayload\":").toByteArray())
    }
  }

  @Test
  fun `H4 ordered effects actuals and evaluated value references round trip across producer versions`() {
    val site = SourceLocation("entry", "Sample.kt", 1, 1)
    val second = ContractParameter("callee", "second", ParameterKind.CANVAS)
    val first = ContractParameter("callee", "first", ParameterKind.CANVAS)
    val arguments =
      CallArguments(
        linkedMapOf(
          second to ArgumentExpression.Canvas(CanvasExpression.Empty),
          first to ArgumentExpression.Canvas(CanvasExpression.ValueReference("allocation", site)),
        ),
      )
    val initialize =
      Effect.ConstructCanvas(
        "allocation",
        CanvasExpression.Alias("allocation", CanvasExpression.Empty),
        site,
      )
    val call =
      CanvasExpression.RuntimeCall(
        "callee",
        arguments,
        site,
        receiver = DispatchReceiver.Forwarded,
        virtualDispatch = true,
      )
    val expression = CanvasExpression.WithEffects(listOf(initialize, Effect.Unknown("later", "unknown", site)), call)
    val contract = CanvasContract("entry", result = expression, site = site)
    val module = ModuleContract("ordered", canvases = listOf(contract))
    val bytes = SummaryCodec.encode(module, coreAnalysisRevisions = setOf(1)).decodeToString()
    val original = SummaryCodec.decode(bytes.toByteArray())
    for (producer in listOf(
      original.producer.copy(compilerVersion = "2.2.10"),
      original.producer.copy(compilerVersion = "2.4.20-release-1"),
      original.producer.copy(analysisVersion = "other-analysis-build"),
      SummaryProducer(" other-analysis-build ", " other-compiler-build "),
    )) {
      val metadata =
        bytes.replace(Regex("\"analysisVersion\":\"[^\"]+\""), "\"analysisVersion\":\"${producer.analysisVersion}\"")
          .replace(Regex("\"compilerVersion\":\"[^\"]+\""), "\"compilerVersion\":\"${producer.compilerVersion}\"")
      assertEquals(original.copy(producer = producer), SummaryCodec.decode(metadata.toByteArray()))
    }
    val restored = original.module
    assertEquals(module, restored)
    val plan = restored.canvases.single().result as CanvasExpression.WithEffects
    assertEquals(listOf("allocation", "later"), plan.effects.map { it.id })
    assertEquals(listOf(second, first), (plan.result as CanvasExpression.RuntimeCall).arguments.values.keys.toList())
  }

  @Test
  fun `semantic requirements are required hash covered and independently admitted`() {
    val module = ModuleContract("sample")
    val bytes = SummaryCodec.encode(module, coreAnalysisRevisions = setOf(1)).decodeToString()
    assertEquals(setOf(1), SummaryCodec.decode(bytes.toByteArray()).coreAnalysisRevisions)
    assertFailsWith<IllegalArgumentException> {
      SummaryCodec.decode(bytes.replace("\"coreAnalysisRevisions\":[1]", "\"coreAnalysisRevisions\":[2]").toByteArray())
    }
    for (requirements in listOf(emptySet(), setOf(2), setOf(1, 2))) {
      val failure =
        assertFailsWith<IllegalArgumentException> {
          SummaryCodec.decode(SummaryCodec.encode(module, coreAnalysisRevisions = requirements))
        }
      kotlin.test.assertTrue(failure.message.orEmpty().contains("Mosaic Core"), failure.message)
    }
    assertFailsWith<IllegalArgumentException> {
      SummaryCodec.decode(bytes.replace("coreAnalysisRevisions", "missingRevisions").toByteArray())
    }
  }

  @Test
  fun `invalid summaries are rejected`() {
    val bytes = SummaryCodec.encode(ModuleContract("sample"), coreAnalysisRevisions = setOf(1)).decodeToString()
    assertFailsWith<IllegalArgumentException> {
      SummaryCodec.decode(bytes.replace("\"complete\":true", "\"complete\":false").toByteArray())
    }
    assertFailsWith<IllegalArgumentException> {
      SummaryCodec.decode(
        bytes.replace("\"contractVersion\":6", "\"contractVersion\":999").toByteArray(),
      )
    }
    for (field in listOf("analysisVersion", "compilerVersion")) {
      for (blank in listOf("", " ")) {
        assertFailsWith<IllegalArgumentException>(field) {
          SummaryCodec.decode(bytes.replace(Regex("\"$field\":\"[^\"]+\""), "\"$field\":\"$blank\"").toByteArray())
        }
      }
    }
    assertFailsWith<IllegalArgumentException> {
      SummaryCodec.decode(bytes.replace("\"moduleId\":\"sample\"", "\"moduleId\":\"changed\"").toByteArray())
    }
  }
}

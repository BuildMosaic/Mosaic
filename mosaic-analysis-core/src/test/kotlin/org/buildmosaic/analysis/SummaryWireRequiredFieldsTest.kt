package org.buildmosaic.analysis

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@Suppress("FunctionMaxLength")
class SummaryWireRequiredFieldsTest {
  @Test
  fun `argument order changes canonical payload`() {
    val site = SourceLocation("call", "Call.kt", 1, 1)
    val first = ContractParameter("target", "first", ParameterKind.CANVAS)
    val second = ContractParameter("target", "second", ParameterKind.CANVAS)

    fun encoded(parameters: List<ContractParameter>): ByteArray {
      val arguments =
        CallArguments(
          parameters.associateWithTo(linkedMapOf()) {
            ArgumentExpression.Canvas(CanvasExpression.Empty)
          },
        )
      val call = Effect.Call("invoke", "target", arguments, site)
      return SummaryCodec.encode(
        ModuleContract("sample", callables = listOf(CallableContract("call", effects = listOf(call), site = site))),
        coreAnalysisRevisions = setOf(1),
      )
    }
    assertTrue(!encoded(listOf(first, second)).contentEquals(encoded(listOf(second, first))))
  }

  @Test
  fun `bindings targets and effects are required`() {
    val site = SourceLocation("sample", "Sample.kt", 1, 1)
    val layer = CanvasExpression.Layer("layer", CanvasExpression.Empty, site = site)
    val module =
      ModuleContract(
        "sample",
        canvases = listOf(CanvasContract("canvas", result = layer, site = site)),
        tiles = listOf(TileContract("tile", emptyList(), site)),
        callables =
          listOf(
            CallableContract("call", effects = listOf(Effect.Call("invoke", "target", site = site)), site = site),
          ),
      )
    val text = SummaryCodec.encode(module, coreAnalysisRevisions = setOf(1)).decodeToString()
    for (field in listOf("bindings", "effects", "target")) {
      val missing = withoutPayloadField(text, field)
      val error = assertFailsWith<IllegalArgumentException>(field) { SummaryCodec.decode(missing.toByteArray()) }
      assertTrue(error.message.orEmpty().contains(field), error.message)
    }
  }

  @Test
  fun `duplicate actual parameters are rejected even with a valid checksum`() {
    val site = SourceLocation("sample", "Sample.kt", 1, 1)
    val parameter = ContractParameter("call", "canvas", ParameterKind.CANVAS)
    val module =
      ModuleContract(
        "sample",
        callables =
          listOf(
            CallableContract(
              "call",
              effects =
                listOf(
                  Effect.Call(
                    "invoke",
                    "target",
                    CallArguments(linkedMapOf(parameter to ArgumentExpression.Canvas(CanvasExpression.Empty))),
                    site,
                  ),
                ),
              site = site,
            ),
          ),
      )
    val text = SummaryCodec.encode(module, coreAnalysisRevisions = setOf(1)).decodeToString()
    val argumentEntry =
      """{"parameter":{"owner":"call","name":"canvas","kind":"CANVAS"},"value":""" +
        """{"kind":"canvas","expression":{"kind":"empty"}}}"""
    assertTrue(text.contains(argumentEntry))
    val duplicate = text.replace(argumentEntry, "$argumentEntry,$argumentEntry")
    val error =
      assertFailsWith<IllegalArgumentException> { SummaryCodec.decode(withPayloadHash(duplicate).toByteArray()) }
    assertTrue(error.message.orEmpty().contains("Duplicate argument"))
  }
}

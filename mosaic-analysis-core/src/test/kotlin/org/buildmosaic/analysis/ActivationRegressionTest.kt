package org.buildmosaic.analysis

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@Suppress("MaxLineLength", "LongParameterList", "LargeClass")
class ActivationRegressionTest {
  private fun site(
    n: String,
    line: Int = 1,
  ) = SourceLocation(n, "RereviewProbes.kt", line, 1)

  private val metrics = CanvasKeyIdentity("Metrics")
  private val service = CanvasKeyIdentity("Service")

  private fun binding(
    k: CanvasKeyIdentity,
    effects: List<Effect> = emptyList(),
  ) = Binding(Fact.Known(k), effects, site(k.classId))

  private fun layer(
    id: String,
    bindings: List<Binding>,
  ) = CanvasExpression.Layer(
    id,
    CanvasExpression.Empty,
    bindings,
    site = site(id),
  )

  private fun lookup(
    id: String,
    canvas: CanvasExpression = CanvasExpression.Current,
    key: CanvasKeyIdentity = metrics,
    kind: LookupKind = LookupKind.REQUIRED,
  ) = Effect.Lookup(
    id,
    canvas,
    Fact.Known(key),
    kind,
    site(id),
  )

  private val full = layer("full", listOf(binding(metrics)))
  private val empty = CanvasExpression.Empty

  private fun analyze(
    effects: List<Effect>,
    extra: List<CallableContract> = emptyList(),
    canvases: List<CanvasContract> = emptyList(),
    tiles: List<TileContract> = emptyList(),
    policy: AnalysisPolicy = AnalysisPolicy.STRICT,
    limits: AnalysisLimits = AnalysisLimits(),
    root: SelectedRoot =
      SelectedRoot(
        "root",
        "entry",
      ),
    parameters: List<ContractParameter> = emptyList(),
  ): AnalysisReport =
    MosaicAnalyzer().analyze(
      AnalysisRequest(
        ModuleContract(
          "app",
          canvases = canvases,
          tiles = tiles,
          callables = listOf(CallableContract("entry", parameters, effects, site("entry"))) + extra,
        ),
        roots = listOf(root),
        policy = policy,
        limits = limits,
      ),
    )

  private fun show(
    name: String,
    report: AnalysisReport,
    expected: (AnalysisReport) -> Boolean,
  ) {
    assertTrue(expected(report), "$name: ${report.findings}")
  }

  @Test
  fun `boolean swap`() {
    val a = ContractParameter("swap", "a", ParameterKind.BOOLEAN)
    val z = ContractParameter("swap", "binding", ParameterKind.BOOLEAN)
    val recurse =
      Effect.Call(
        "recurse",
        "swap",
        CallArguments(
          mapOf(
            a to ArgumentExpression.BooleanValue(BooleanExpression.ParameterValue(z)),
            z to ArgumentExpression.BooleanValue(BooleanExpression.ParameterValue(a)),
          ),
        ),
        site("recurse"),
      )
    val body =
      Effect.Branch(
        "if-a",
        Guard.BooleanParameter(a),
        listOf(
          Effect.Branch(
            "if-binding",
            Guard.BooleanParameter(z),
            emptyList(),
            listOf(lookup("should-be-missing", empty)),
            site("if-binding"),
          ),
        ),
        listOf(recurse),
        site("if-a"),
      )
    val swap = CallableContract("swap", listOf(a, z), listOf(body), site("swap"))
    val initial =
      Effect.Call(
        "start",
        "swap",
        CallArguments(
          mapOf(
            a to ArgumentExpression.BooleanValue(BooleanExpression.Constant(false)),
            z to ArgumentExpression.BooleanValue(BooleanExpression.Constant(true)),
          ),
        ),
        site("start"),
      )
    show(
      "F2 argument binding overwrites caller Boolean environment",
      analyze(listOf(initial), extra = listOf(swap)),
    ) { r ->
      r.findings.any {
        it.key == metrics && it.certainty == Certainty.MISSING
      } && !r.policyDecision.passed
    }
  }

  @Test
  fun `canvas swap`() {
    val x = ContractParameter("swapCanvas", "x", ParameterKind.CANVAS)
    val y = ContractParameter("swapCanvas", "y", ParameterKind.CANVAS)
    val done = ContractParameter("swapCanvas", "done", ParameterKind.BOOLEAN)
    val recurseCanvas =
      Effect.Call(
        "recurseCanvas",
        "swapCanvas",
        CallArguments(
          mapOf(
            x to ArgumentExpression.Canvas(CanvasExpression.ParameterValue(y)),
            y to ArgumentExpression.Canvas(CanvasExpression.ParameterValue(x)),
            done to ArgumentExpression.BooleanValue(BooleanExpression.Constant(true)),
          ),
        ),
        site("recurseCanvas"),
      )
    val swapCanvas =
      CallableContract(
        "swapCanvas",
        listOf(x, y, done),
        listOf(
          Effect.Branch(
            "done",
            Guard.BooleanParameter(done),
            listOf(lookup("read-original-x", CanvasExpression.ParameterValue(y))),
            listOf(recurseCanvas),
            site("done"),
          ),
        ),
        site("swapCanvas"),
      )
    val initialCanvas =
      Effect.Call(
        "startCanvas",
        "swapCanvas",
        CallArguments(
          mapOf(
            x to ArgumentExpression.Canvas(empty),
            y to ArgumentExpression.Canvas(full),
            done to ArgumentExpression.BooleanValue(BooleanExpression.Constant(false)),
          ),
        ),
        site("startCanvas"),
      )
    show(
      "F2b argument binding overwrites caller Canvas environment",
      analyze(listOf(initialCanvas), extra = listOf(swapCanvas)),
    ) { r ->
      r.findings.any {
        it.key == metrics && it.certainty == Certainty.MISSING
      } && !r.policyDecision.passed
    }
  }

  @Test
  fun `unused input`() {
    val unused = ContractParameter("entry", "canvas", ParameterKind.CANVAS)
    show(
      "F3 unused unknown selected-root input",
      analyze(emptyList(), parameters = listOf(unused)),
    ) { r -> r.policyDecision.passed && r.findings.isEmpty() }
  }

  @Test
  fun `alias branch refinement`() {
    val flag = ContractParameter("entry", "flag", ParameterKind.BOOLEAN)
    val alias =
      CanvasExpression.Alias(
        "shared",
        layer(
          "shared",
          listOf(binding(metrics), binding(service, listOf(lookup("init", kind = LookupKind.CONSTRUCTION)))),
        ),
      )
    val branch =
      Effect.Branch(
        "unrelated",
        Guard.BooleanParameter(flag),
        listOf(lookup("yes", alias)),
        listOf(lookup("no", alias)),
        site("unrelated"),
      )
    val aliasReport =
      analyze(listOf(Effect.ConstructCanvas("let", alias, site("let")), branch), parameters = listOf(flag))
    show("F4 already allocated alias revisited after unrelated Boolean branch", aliasReport) { r ->
      r.findings.count {
        it.kind == FindingKind.CONSTRUCTION_LOOKUP
      } == 1
    }
    val consumers = aliasReport.findings.filter { it.kind == FindingKind.REQUIRED_LOOKUP }
    assertEquals(2, consumers.size)
    assertEquals(setOf(listOf("flag=false"), listOf("flag=true")), consumers.map { it.pathCondition }.toSet())
    assertTrue(aliasReport.policyDecision.passed)
  }
}

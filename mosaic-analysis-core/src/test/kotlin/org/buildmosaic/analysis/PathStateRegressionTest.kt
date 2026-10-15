package org.buildmosaic.analysis

import org.buildmosaic.analysis.AnalysisFixtures.binding
import org.buildmosaic.analysis.AnalysisFixtures.compose
import org.buildmosaic.analysis.AnalysisFixtures.entry
import org.buildmosaic.analysis.AnalysisFixtures.lookup
import org.buildmosaic.analysis.AnalysisFixtures.metrics
import org.buildmosaic.analysis.AnalysisFixtures.platform
import org.buildmosaic.analysis.AnalysisFixtures.report
import org.buildmosaic.analysis.AnalysisFixtures.service
import org.buildmosaic.analysis.AnalysisFixtures.site
import org.buildmosaic.analysis.AnalysisFixtures.tile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@Suppress("FunctionMaxLength", "LargeClass", "TooManyFunctions")
class PathStateRegressionTest {
  @Test
  fun `F1 opaque Boolean actual retains registration and lookup correlation`() {
    val result = correlatedReport(BooleanExpression.Opaque("runtime flag", site("flag")))

    assertTrue(result.policyDecision.passed)
    assertEquals(listOf(Certainty.VERIFIED), result.findings.map { it.certainty })
  }

  @Test
  fun `F2 aborting opaque alternative leaves conditional continuation`() {
    listOf(false, true).forEach { asChoice ->
      invalidCanvases().forEach { (invalid, kind) ->
        listOf(invalid to CanvasExpression.Empty, CanvasExpression.Empty to invalid).forEach { (yes, no) ->
          assertConditionalContinuation(continuationReport(yes, no, asChoice), kind)
        }
      }
    }
  }

  @Test
  fun `F3 unknown MultiTile execution retains zero execution continuation`() {
    invalidCanvases().forEach { (invalid, kind) ->
      val result = multiReport(MultiTileExecution.UNKNOWN, invalid = invalid)
      val later = result.findings.single { it.key == platform }

      assertEquals(Certainty.UNVERIFIED, later.certainty)
      assertTrue(later.pathCondition.isNotEmpty())
      assertTrue(result.findings.any { it.kind == kind })
      if (kind == FindingKind.CONSTRUCTION_LOOKUP) {
        assertEquals(Certainty.UNVERIFIED, result.findings.single { it.kind == kind }.certainty)
      }
      assertFalse(result.findings.any { it.certainty == Certainty.MISSING })
    }
  }

  @Test
  fun `known and free Boolean values preserve correlated availability`() {
    listOf(null, BooleanExpression.Constant(false), BooleanExpression.Constant(true)).forEach { actual ->
      val result = correlatedReport(actual)
      assertTrue(result.policyDecision.passed)
      assertTrue(result.findings.all { it.certainty == Certainty.VERIFIED })
    }
  }

  @Test
  fun `separate opaque evaluations with identical text and location stay independent`() {
    val guard = Guard.Opaque("same text", site("same location"))
    val canvas = CanvasExpression.Choice(guard, populated(), CanvasExpression.Empty)
    val use = Effect.Branch("use", guard, listOf(lookup("metrics", metrics, canvas = canvas)), site = site("use"))
    val result = report(ModuleContract("app", callables = listOf(entry("entry", use))))

    assertEquals(listOf(Certainty.UNVERIFIED), result.findings.map { it.certainty })
  }

  @Test
  fun `both opaque alternatives continuing preserve independent missing witness`() {
    val result = continuationReport(CanvasExpression.Empty)
    val later = result.findings.single { it.key == platform }

    assertEquals(Certainty.MISSING, later.certainty)
    assertTrue(later.pathCondition.isEmpty())
  }

  @Test
  fun `known empty MultiTile skips invalid body and continues`() {
    val result = multiReport(MultiTileExecution.KNOWN_EMPTY)

    assertEquals(listOf(platform), result.findings.map { it.key })
    assertEquals(Certainty.MISSING, result.findings.single().certainty)
  }

  @Test
  fun `known nonempty MultiTile failure blocks synchronous continuation`() {
    val result = multiReport(MultiTileExecution.KNOWN_NON_EMPTY)

    assertEquals(listOf(FindingKind.DUPLICATE_BINDING), result.findings.map { it.kind })
    assertEquals(Certainty.MISSING, result.findings.single().certainty)
  }

  @Test
  fun `async body failure preserves launcher continuation`() {
    val result = multiReport(MultiTileExecution.KNOWN_NON_EMPTY, DiscoveryKind.COMPOSE_ASYNC)

    assertEquals(Certainty.MISSING, result.findings.single { it.key == platform }.certainty)
  }

  @Test
  fun `missing construction follows MultiTile execution and launch semantics`() {
    val empty = multiReport(MultiTileExecution.KNOWN_EMPTY, invalid = missingConstruction())
    assertEquals(listOf(platform), empty.findings.map { it.key })
    assertEquals(Certainty.MISSING, empty.findings.single().certainty)

    val nonEmpty = multiReport(MultiTileExecution.KNOWN_NON_EMPTY, invalid = missingConstruction())
    assertEquals(listOf(FindingKind.CONSTRUCTION_LOOKUP), nonEmpty.findings.map { it.kind })
    assertEquals(Certainty.MISSING, nonEmpty.findings.single().certainty)

    val async =
      multiReport(MultiTileExecution.KNOWN_NON_EMPTY, DiscoveryKind.COMPOSE_ASYNC, invalid = missingConstruction())
    assertEquals(Certainty.MISSING, async.findings.single { it.key == platform }.certainty)
  }

  @Test
  fun `unresolved construction does not assert a definite construction abort`() {
    val unknownConstruction =
      Effect.Lookup(
        "unknown-construction",
        CanvasExpression.Current,
        Fact.Unknown("dynamic key", site("dynamic-key")),
        LookupKind.CONSTRUCTION,
        site("unknown-construction"),
      )
    val invalid =
      CanvasExpression.Layer(
        "unknown-construction-layer",
        CanvasExpression.Empty,
        listOf(binding(service, "unknown-service", listOf(unknownConstruction))),
        site = site("unknown-construction-layer"),
      )
    val result = continuationReport(invalid, CanvasExpression.Empty)

    assertEquals(Certainty.UNVERIFIED, result.findings.single { it.kind == FindingKind.CONSTRUCTION_LOOKUP }.certainty)
    assertEquals(Certainty.MISSING, result.findings.single { it.key == platform }.certainty)
  }

  @Test
  fun `known receiver construction failure blocks invocation even for empty or unknown execution`() {
    MultiTileExecution.entries.forEach { execution ->
      val result = multiReport(execution, receiver = duplicate())
      assertEquals(listOf(FindingKind.DUPLICATE_BINDING), result.findings.map { it.kind })
      assertEquals(Certainty.MISSING, result.findings.single().certainty)
    }
  }

  @Test
  fun `eager Canvas argument failure precedes all MultiTile execution alternatives`() {
    val ignored = ContractParameter("factory", "ignored", ParameterKind.CANVAS)
    val factory = CanvasContract("factory", listOf(ignored), CanvasExpression.Empty, site("factory"))
    val receiver =
      CanvasExpression.RuntimeCall(
        factory.id,
        CallArguments(mapOf(ignored to ArgumentExpression.Canvas(duplicate()))),
        site("receiver"),
      )
    MultiTileExecution.entries.forEach { execution ->
      listOf(DiscoveryKind.COMPOSE, DiscoveryKind.COMPOSE_ASYNC).forEach { discovery ->
        val result = multiReport(execution, discovery, receiver, listOf(factory))
        assertEquals(listOf(FindingKind.DUPLICATE_BINDING), result.findings.map { it.kind })
        assertEquals(Certainty.MISSING, result.findings.single().certainty)
      }
    }
  }

  @Test
  fun `forwarded immutable Boolean retains identity including negated guards`() {
    listOf(
      null,
      BooleanExpression.Constant(false),
      BooleanExpression.Constant(true),
      BooleanExpression.Opaque("runtime", site("runtime")),
    ).forEach { actual ->
      listOf(false, true).forEach { expected ->
        val result = correlatedReport(actual, expected, forward = true)
        assertTrue(result.policyDecision.passed)
        assertTrue(result.findings.all { it.certainty == Certainty.VERIFIED })
      }
    }
  }

  @Test
  fun `opaque actual expressions evaluated separately never share an identity`() {
    val provider = ContractParameter("helper", "provider", ParameterKind.BOOLEAN)
    val consumer = ContractParameter("helper", "consumer", ParameterKind.BOOLEAN)
    val canvas = CanvasExpression.Choice(Guard.BooleanParameter(provider), populated(), CanvasExpression.Empty)
    val helper =
      entry(
        "helper",
        Effect.Branch(
          "use",
          Guard.BooleanParameter(consumer),
          listOf(lookup("metrics", metrics, canvas = canvas)),
          site = site("use"),
        ),
        parameters = listOf(provider, consumer),
      )
    val sameExpression = ArgumentExpression.BooleanValue(BooleanExpression.Opaque("same", site("same")))
    val caller =
      entry(
        "entry",
        Effect.Call(
          "call",
          helper.id,
          CallArguments(mapOf(provider to sameExpression, consumer to sameExpression)),
          site("call"),
        ),
      )
    val result = report(ModuleContract("app", callables = listOf(helper, caller)))

    assertEquals(listOf(Certainty.UNVERIFIED), result.findings.map { it.certainty })
  }

  @Test
  fun `conditional Canvas argument stays conditional throughout callee effects`() {
    val parameter = ContractParameter("helper", "canvas", ParameterKind.CANVAS)
    val helper =
      entry(
        "helper",
        Effect.Branch("unrelated", Guard.Opaque("unrelated", site("unrelated")), emptyList(), site = site("unrelated")),
        lookup("metrics", metrics, canvas = CanvasExpression.ParameterValue(parameter)),
        parameters = listOf(parameter),
      )
    val selected =
      CanvasExpression.Choice(
        Guard.Opaque("selection", site("selection")),
        populated(),
        CanvasExpression.Empty,
      )
    val caller =
      entry(
        "entry",
        Effect.Call(
          "call",
          helper.id,
          CallArguments(mapOf(parameter to ArgumentExpression.Canvas(selected))),
          site("call"),
        ),
        later(),
      )
    val result = report(ModuleContract("app", callables = listOf(helper, caller)))

    assertEquals(Certainty.UNVERIFIED, result.findings.single { it.key == metrics }.certainty)
    assertEquals(Certainty.MISSING, result.findings.single { it.key == platform }.certainty)
  }

  @Test
  fun `nested branch cannot join away condition on surviving continuation`() {
    val abort = Effect.ConstructCanvas("abort", duplicate(), site("abort"))
    val inner = Effect.Branch("inner", Guard.Opaque("inner", site("inner")), listOf(abort), site = site("inner"))
    val outer = Effect.Branch("outer", Guard.Opaque("outer", site("outer")), listOf(inner), site = site("outer"))
    val result = report(ModuleContract("app", callables = listOf(entry("entry", outer, later()))))

    assertEquals(Certainty.UNVERIFIED, result.findings.single { it.key == platform }.certainty)
    assertTrue(result.findings.single { it.key == platform }.pathCondition.isNotEmpty())
  }

  @Test
  fun `incomplete opaque expansion is not a proven runtime termination`() {
    val branch =
      Effect.Branch(
        "limited",
        Guard.Opaque("limited", site("limited")),
        listOf(Effect.ConstructCanvas("abort", duplicate(), site("abort"))),
        site = site("limited"),
      )
    val result =
      report(
        ModuleContract("app", callables = listOf(entry("entry", branch, later()))),
        limits = AnalysisLimits(alternativeBudget = 1),
      )

    assertTrue(result.findings.any { it.kind == FindingKind.INCOMPLETE_ANALYSIS })
    assertEquals(Certainty.MISSING, result.findings.single { it.key == platform }.certainty)
  }

  @Test
  fun `unknown execution rejoins normally or asynchronously and respects expansion limit`() {
    val normalTile = tile("NormalTile", lookup("metrics", metrics))
    val normal =
      report(
        ModuleContract(
          "app",
          tiles = listOf(normalTile),
          callables =
            listOf(
              entry(
                "entry",
                compose("compose", CanvasExpression.Empty, normalTile.id, MultiTileExecution.UNKNOWN),
                later(),
              ),
            ),
        ),
      )
    val async = multiReport(MultiTileExecution.UNKNOWN, DiscoveryKind.COMPOSE_ASYNC)
    val limited =
      report(
        ModuleContract(
          "app",
          tiles = listOf(normalTile),
          callables =
            listOf(
              entry(
                "entry",
                compose("compose", CanvasExpression.Empty, normalTile.id, MultiTileExecution.UNKNOWN),
                later(),
              ),
            ),
        ),
        limits = AnalysisLimits(alternativeBudget = 1),
      )

    assertEquals(Certainty.UNVERIFIED, normal.findings.single { it.key == metrics }.certainty)
    listOf(normal, async, limited).forEach { result ->
      assertEquals(Certainty.MISSING, result.findings.single { it.key == platform }.certainty)
    }
    assertTrue(limited.findings.any { it.kind == FindingKind.INCOMPLETE_ANALYSIS })
  }

  private fun correlatedReport(
    actual: BooleanExpression?,
    expected: Boolean = true,
    forward: Boolean = false,
  ): AnalysisReport {
    val flag = ContractParameter("helper", "flag", ParameterKind.BOOLEAN)
    val canvas =
      CanvasExpression.Alias(
        "selected",
        CanvasExpression.Choice(Guard.BooleanParameter(flag, expected), populated(), CanvasExpression.Empty),
      )
    val helper =
      entry(
        "helper",
        Effect.ConstructCanvas("construct", canvas, site("construct")),
        Effect.Branch(
          "use",
          Guard.BooleanParameter(flag, expected),
          listOf(lookup("metrics", metrics, canvas = canvas)),
          site = site("use"),
        ),
        parameters = listOf(flag),
      )
    val outer = ContractParameter("entry", "outer", ParameterKind.BOOLEAN)
    val caller =
      entry(
        "entry",
        Effect.Call(
          "forward",
          helper.id,
          CallArguments(mapOf(flag to ArgumentExpression.BooleanValue(BooleanExpression.ParameterValue(outer)))),
          site("forward"),
        ),
        parameters = listOf(outer),
      )
    val input = if (forward) outer else flag
    val target = if (forward) caller.id else helper.id
    val arguments =
      if (actual == null) {
        CallArguments()
      } else {
        CallArguments(mapOf(input to ArgumentExpression.BooleanValue(actual)))
      }
    return report(
      ModuleContract("app", callables = listOf(helper, caller)),
      roots = listOf(SelectedRoot("root", target, arguments)),
      policy = AnalysisPolicy.STRICT,
      limits = AnalysisLimits(alternativeBudget = 2),
    )
  }

  private fun continuationReport(
    whenTrue: CanvasExpression,
    whenFalse: CanvasExpression = CanvasExpression.Empty,
    asChoice: Boolean = false,
  ): AnalysisReport {
    val guard = Guard.Opaque("runtime branch", site("branch"))
    val branch =
      if (asChoice) {
        Effect.ConstructCanvas("construct", CanvasExpression.Choice(guard, whenTrue, whenFalse), site("construct"))
      } else {
        Effect.Branch(
          "branch",
          guard,
          listOf(Effect.ConstructCanvas("construct-true", whenTrue, site("construct-true"))),
          listOf(Effect.ConstructCanvas("construct-false", whenFalse, site("construct-false"))),
          site("branch"),
        )
      }
    return report(ModuleContract("app", callables = listOf(entry("entry", branch, later()))))
  }

  private fun multiReport(
    execution: MultiTileExecution,
    discovery: DiscoveryKind = DiscoveryKind.COMPOSE,
    receiver: CanvasExpression = CanvasExpression.Empty,
    canvases: List<CanvasContract> = emptyList(),
    invalid: CanvasExpression = duplicate(),
  ): AnalysisReport {
    val invalidTile = tile("InvalidTile", Effect.ConstructCanvas("invalid", invalid, site("invalid")))
    return report(
      ModuleContract(
        "app",
        tiles = listOf(invalidTile),
        canvases = canvases,
        callables = listOf(entry("entry", compose("compose", receiver, invalidTile.id, execution, discovery), later())),
      ),
    )
  }

  private fun later() = lookup("later", platform, canvas = CanvasExpression.Empty)

  private fun assertConditionalContinuation(
    result: AnalysisReport,
    kind: FindingKind,
  ) {
    val later = result.findings.single { it.key == platform }
    assertEquals(Certainty.UNVERIFIED, later.certainty)
    assertTrue(later.pathCondition.isNotEmpty())
    assertFalse(result.findings.any { it.certainty == Certainty.MISSING })
    if (kind == FindingKind.CONSTRUCTION_LOOKUP) {
      assertEquals(Certainty.UNVERIFIED, result.findings.single { it.kind == kind }.certainty)
    }
  }

  private fun populated() =
    CanvasExpression.Layer(
      "populated",
      CanvasExpression.Empty,
      listOf(binding(metrics)),
      site = site("populated"),
    )

  private fun duplicate() =
    CanvasExpression.Layer(
      "duplicate",
      CanvasExpression.Empty,
      listOf(binding(metrics, "first"), binding(metrics, "second")),
      site = site("duplicate"),
    )

  private fun missingConstruction() =
    CanvasExpression.Layer(
      "missing-construction",
      CanvasExpression.Empty,
      listOf(binding(service, "service", listOf(lookup("construction", metrics, LookupKind.CONSTRUCTION)))),
      site = site("missing-construction"),
    )

  private fun invalidCanvases() =
    listOf(duplicate() to FindingKind.DUPLICATE_BINDING, missingConstruction() to FindingKind.CONSTRUCTION_LOOKUP)
}

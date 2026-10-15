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

@Suppress("FunctionMaxLength", "LargeClass", "MaxLineLength")
class CorrectnessRegressionTest {
  @Test
  fun `ignored callable Canvas argument is still constructed eagerly`() {
    val parameter = ContractParameter("ignore", "canvas", ParameterKind.CANVAS)
    val ignore = entry("ignore", parameters = listOf(parameter))
    val call =
      Effect.Call(
        "call",
        ignore.id,
        CallArguments(mapOf(parameter to ArgumentExpression.Canvas(invalidCanvas()))),
        site("call"),
      )
    val result = report(ModuleContract("app", callables = listOf(ignore, entry("entry", call))))

    assertEquals(Certainty.MISSING, result.findings.single().certainty)
    assertEquals(FindingKind.CONSTRUCTION_LOOKUP, result.findings.single().kind)
  }

  @Test
  fun `ignored Canvas factory argument is still constructed eagerly`() {
    val parameter = ContractParameter("ignoreCanvas", "canvas", ParameterKind.CANVAS)
    val ignore =
      CanvasContract(
        "ignoreCanvas",
        listOf(parameter),
        CanvasExpression.Empty,
        site("ignoreCanvas"),
      )
    val call =
      CanvasExpression.RuntimeCall(
        ignore.id,
        CallArguments(mapOf(parameter to ArgumentExpression.Canvas(invalidCanvas()))),
        site("call"),
      )
    val result =
      report(
        ModuleContract(
          "app",
          canvases = listOf(ignore),
          callables = listOf(entry("entry", Effect.ConstructCanvas("construct", call, site("construct")))),
        ),
      )

    assertEquals(Certainty.MISSING, result.findings.single().certainty)
    assertEquals(FindingKind.CONSTRUCTION_LOOKUP, result.findings.single().kind)
  }

  @Test
  fun `unknown unused Canvas value creates no required key obligation`() {
    val parameter = ContractParameter("ignore", "canvas", ParameterKind.CANVAS)
    val ignore = entry("ignore", parameters = listOf(parameter))
    val call =
      Effect.Call(
        "call",
        ignore.id,
        CallArguments(
          mapOf(
            parameter to
              ArgumentExpression.Canvas(
                CanvasExpression.Unknown("existing external Canvas", site("external")),
              ),
          ),
        ),
        site("call"),
      )
    val result = report(ModuleContract("app", callables = listOf(ignore, entry("entry", call))))

    assertFalse(result.findings.any { it.kind in lookupKinds })
  }

  @Test
  fun `existing aliased Canvas constructs once across helper calls`() {
    val parameter = ContractParameter("consume", "canvas", ParameterKind.CANVAS)
    val requested = tile("ServiceTile", lookup("service", service))
    val consume =
      entry(
        "consume",
        compose("consume-compose", CanvasExpression.ParameterValue(parameter), requested.id),
        parameters = listOf(parameter),
      )
    val shared = observableAlias("shared")
    val first = callWithCanvas("first", consume.id, parameter, shared)
    val second = callWithCanvas("second", consume.id, parameter, shared)
    val caller =
      entry(
        "entry",
        Effect.ConstructCanvas("construct-shared", shared, site("construct-shared")),
        first,
        second,
      )
    val result =
      report(
        ModuleContract(
          "app",
          tiles = listOf(requested),
          callables = listOf(consume, caller),
        ),
      )

    assertEquals(1, result.findings.count { it.kind == FindingKind.CONSTRUCTION_LOOKUP })
    assertEquals(2, result.findings.count { it.kind == FindingKind.REQUIRED_LOOKUP })
    assertEquals(
      2,
      result.findings.filter {
        it.kind == FindingKind.REQUIRED_LOOKUP
      }.map { it.dependencyPath }.toSet().size,
    )
  }

  @Test
  fun `genuinely distinct Canvas constructions remain distinct`() {
    val first = observableAlias("first")
    val second = observableAlias("second")
    val caller =
      entry(
        "entry",
        Effect.ConstructCanvas("first", first, site("first")),
        Effect.ConstructCanvas("second", second, site("second")),
      )
    val result = report(ModuleContract("app", callables = listOf(caller)))

    assertEquals(2, result.findings.count { it.kind == FindingKind.CONSTRUCTION_LOOKUP })
  }

  @Test
  fun `opaque aliased alternatives preserve uncertainty in both orders`() {
    listOf(
      populatedCanvas("yes") to CanvasExpression.Empty,
      CanvasExpression.Empty to populatedCanvas("no"),
    ).forEachIndexed { index, (whenTrue, whenFalse) ->
      val result = opaqueAliasReport("selected-$index", whenTrue, whenFalse)
      val lookup = result.findings.single { it.kind == FindingKind.REQUIRED_LOOKUP }

      assertEquals(RootStatus.UNVERIFIED, result.roots.single().status)
      assertEquals(Certainty.UNVERIFIED, lookup.certainty)
      assertFalse(result.findings.any { it.certainty == Certainty.MISSING })
    }
  }

  @Test
  fun `repeated opaque alias reads and helper transfer retain alternatives`() {
    val selected =
      CanvasExpression.Alias(
        "selected",
        CanvasExpression.Choice(
          Guard.Opaque("runtime selection", site("selection")),
          populatedCanvas("populated"),
          CanvasExpression.Empty,
        ),
      )
    val parameter = ContractParameter("consume", "canvas", ParameterKind.CANVAS)
    val requested = tile("MetricsTile", lookup("metrics", metrics))
    val consume =
      entry(
        "consume",
        compose("helper-compose", CanvasExpression.ParameterValue(parameter), requested.id),
        parameters = listOf(parameter),
      )
    val caller =
      entry(
        "entry",
        Effect.ConstructCanvas("construct", selected, site("construct")),
        compose("first-read", selected, requested.id),
        compose("second-read", selected, requested.id),
        callWithCanvas("helper", consume.id, parameter, selected),
      )
    val result =
      report(
        ModuleContract(
          "app",
          tiles = listOf(requested),
          callables = listOf(consume, caller),
        ),
      )

    assertEquals(3, result.findings.count { it.kind == FindingKind.REQUIRED_LOOKUP })
    assertTrue(
      result.findings.filter {
        it.kind == FindingKind.REQUIRED_LOOKUP
      }.all { it.certainty == Certainty.UNVERIFIED },
    )
  }

  @Test
  fun `opaque alternatives that both supply key still verify`() {
    val result =
      opaqueAliasReport(
        "both",
        populatedCanvas("first"),
        populatedCanvas("second"),
      )

    assertEquals(RootStatus.VERIFIED, result.roots.single().status)
    assertTrue(
      result.findings.filter { it.kind == FindingKind.REQUIRED_LOOKUP }.all { it.certainty == Certainty.VERIFIED },
    )
  }

  @Test
  fun `free and specialized aliases preserve supported correlation`() {
    val flag = ContractParameter("entry", "flag", ParameterKind.BOOLEAN)
    val selected =
      CanvasExpression.Alias(
        "selected",
        CanvasExpression.Choice(
          Guard.BooleanParameter(flag),
          populatedCanvas("populated"),
          CanvasExpression.Empty,
        ),
      )
    val requested = tile("MetricsTile", lookup("metrics", metrics))
    val caller =
      entry(
        "entry",
        Effect.ConstructCanvas("construct", selected, site("construct")),
        compose("lookup", selected, requested.id),
        parameters = listOf(flag),
      )
    val module = ModuleContract("app", tiles = listOf(requested), callables = listOf(caller))
    val free = report(module)
    val knownTrue = report(module, roots = listOf(booleanRoot(flag, true)))
    val knownFalse = report(module, roots = listOf(booleanRoot(flag, false)))

    assertEquals(setOf(Certainty.MISSING, Certainty.VERIFIED), free.findings.map { it.certainty }.toSet())
    assertEquals(Certainty.VERIFIED, knownTrue.findings.single().certainty)
    assertEquals(Certainty.MISSING, knownFalse.findings.single().certainty)
  }

  @Test
  fun `async Tile failure does not block independent caller continuation`() {
    val invalidTile =
      tile(
        "InvalidTile",
        Effect.ConstructCanvas("invalid", invalidCanvas(), site("invalid")),
      )
    val caller =
      entry(
        "entry",
        compose(
          "launch",
          CanvasExpression.Empty,
          invalidTile.id,
          discovery = DiscoveryKind.COMPOSE_ASYNC,
        ),
        lookup("independent", platform, canvas = CanvasExpression.Empty),
      )
    val result = report(ModuleContract("app", tiles = listOf(invalidTile), callables = listOf(caller)))

    assertEquals(setOf(metrics, platform), result.findings.mapNotNull { it.key }.toSet())
    assertEquals(2, result.findings.count { it.certainty == Certainty.MISSING })
  }

  @Test
  fun `synchronous Tile failure still blocks dependent continuation`() {
    val invalidTile =
      tile(
        "InvalidTile",
        Effect.ConstructCanvas("invalid", invalidCanvas(), site("invalid")),
      )
    val caller =
      entry(
        "entry",
        compose("compose", CanvasExpression.Empty, invalidTile.id),
        lookup("dependent", platform, canvas = CanvasExpression.Empty),
      )
    val result = report(ModuleContract("app", tiles = listOf(invalidTile), callables = listOf(caller)))

    assertEquals(listOf(metrics), result.findings.mapNotNull { it.key })
  }

  @Test
  fun `exhausted alternative budget preserves unconditional continuation`() {
    val flag = ContractParameter("entry", "flag", ParameterKind.BOOLEAN)
    val caller =
      entry(
        "entry",
        Effect.Branch(
          "limited",
          Guard.BooleanParameter(flag),
          emptyList(),
          emptyList(),
          site("limited"),
        ),
        lookup("unconditional", metrics, canvas = CanvasExpression.Empty),
        parameters = listOf(flag),
      )
    val result =
      report(
        ModuleContract("app", callables = listOf(caller)),
        limits = AnalysisLimits(alternativeBudget = 1),
      )

    assertTrue(result.findings.any { it.kind == FindingKind.INCOMPLETE_ANALYSIS })
    assertTrue(result.findings.any { it.kind == FindingKind.REQUIRED_LOOKUP && it.certainty == Certainty.MISSING })
  }

  @Test
  fun `duplicate certainty follows opaque known and free guards`() {
    val opaque =
      guardedDuplicateReport(
        Guard.Opaque("runtime condition", site("opaque")),
      )
    val knownTrue = guardedDuplicateReport(Guard.Constant(true))
    val knownFalse = guardedDuplicateReport(Guard.Constant(false))
    val flag = ContractParameter("entry", "flag", ParameterKind.BOOLEAN)
    val free = guardedDuplicateReport(Guard.BooleanParameter(flag), listOf(flag))

    assertEquals(Certainty.UNVERIFIED, opaque.findings.single().certainty)
    assertEquals(FindingKind.DUPLICATE_BINDING, opaque.findings.single().kind)
    assertEquals(Certainty.MISSING, knownTrue.findings.single().certainty)
    assertTrue(knownFalse.findings.isEmpty())
    assertEquals(Certainty.MISSING, free.findings.single().certainty)
    assertEquals(listOf("flag=true"), free.findings.single().pathCondition)
  }

  @Test
  fun `captured effect origin attaches only to dependent finding`() {
    val origin = CaptureOrigin.Inline("capturedHelper", "library-v1", "hash-v1")
    val capturedTile = tile("CapturedTile", lookup("metrics", metrics))
    val siblingTile = tile("SiblingTile", lookup("platform", platform))
    val captured =
      Effect.Captured(
        "captured",
        "entry",
        origin,
        listOf(compose("captured-compose", populatedCanvas("captured"), capturedTile.id)),
        faithfullyCaptured = true,
        site = site("captured"),
      )
    val siblingCanvas =
      CanvasExpression.Layer(
        "sibling",
        CanvasExpression.Empty,
        listOf(binding(platform)),
        site = site("sibling"),
      )
    val caller = entry("entry", captured, compose("sibling-compose", siblingCanvas, siblingTile.id))
    val result =
      report(
        ModuleContract(
          "app",
          tiles = listOf(capturedTile, siblingTile),
          callables = listOf(caller),
        ),
      )
    val capturedFinding = result.findings.single { it.key == metrics }
    val siblingFinding = result.findings.single { it.key == platform }

    assertEquals(setOf(origin), capturedFinding.capturedOrigins)
    assertTrue(EvidenceKind.CAPTURED_FACT in capturedFinding.evidence)
    assertTrue(siblingFinding.capturedOrigins.isEmpty())
    assertFalse(EvidenceKind.CAPTURED_FACT in siblingFinding.evidence)
  }

  @Test
  fun `captured Canvas origin reaches selected provider finding`() {
    val origin = CaptureOrigin.Inline("capturedCanvas", "library-v1", "hash-v1")
    val capturedCanvas =
      CanvasExpression.Captured(
        "capturedCanvas",
        origin,
        populatedCanvas("captured-provider"),
        faithfullyCaptured = true,
        site = site("capturedCanvas"),
      )
    val requested = tile("MetricsTile", lookup("metrics", metrics))
    val result =
      report(
        ModuleContract(
          "app",
          tiles = listOf(requested),
          callables = listOf(entry("entry", compose("compose", capturedCanvas, requested.id))),
        ),
      )
    val finding = result.findings.single()

    assertEquals(setOf(origin), finding.capturedOrigins)
    assertTrue(EvidenceKind.CAPTURED_FACT in finding.evidence)
  }

  @Test
  fun `nested capture preserves origins while runtime call stays registry resolved`() {
    val outer = CaptureOrigin.Inline("outer", "library-v1", "outer-hash")
    val inner = CaptureOrigin.Inline("inner", "library-v1", "inner-hash")
    val helper = entry("selectedHelper", lookup("missing", metrics, canvas = CanvasExpression.Empty))
    val nested =
      Effect.Captured(
        "outer",
        "entry",
        outer,
        listOf(
          Effect.Captured(
            "inner",
            "entry",
            inner,
            listOf(Effect.Call("runtime-call", helper.id, site = site("runtime-call"))),
            faithfullyCaptured = true,
            site = site("inner"),
          ),
        ),
        faithfullyCaptured = true,
        site = site("outer"),
      )
    val result = report(ModuleContract("app", callables = listOf(helper, entry("entry", nested))))
    val finding = result.findings.single()

    assertEquals(setOf(outer, inner), finding.capturedOrigins)
    assertTrue(EvidenceKind.CAPTURED_FACT in finding.evidence)
    assertTrue(finding.dependencyPath.any { it.label == "call:${helper.id}" })
  }

  @Test
  fun `optional lookup does not depend on unapproved positive assumption`() {
    val assumption = ExternalAssumption("managed", setOf(metrics), site("assumption"))
    val optionalTile = tile("OptionalTile", lookup("optional", metrics, LookupKind.OPTIONAL))
    val requiredTile = tile("RequiredTile", lookup("required", metrics))
    val assumed = CanvasExpression.Assumption(assumption.id, site("handler"))
    val optionalModule =
      ModuleContract(
        "optional",
        tiles = listOf(optionalTile),
        callables = listOf(entry("entry", compose("compose", assumed, optionalTile.id))),
      )
    val requiredModule =
      ModuleContract(
        "required",
        tiles = listOf(requiredTile),
        callables = listOf(entry("entry", compose("compose", assumed, requiredTile.id))),
      )
    val optional = report(optionalModule, policy = AnalysisPolicy.STRICT, assumptions = listOf(assumption))
    val required = report(requiredModule, policy = AnalysisPolicy.STRICT, assumptions = listOf(assumption))

    assertTrue(optional.policyDecision.passed)
    assertEquals(metrics, optional.findings.single().key)
    assertTrue(optional.findings.single().assumptionIds.isEmpty())
    assertFalse(EvidenceKind.EXTERNAL_ASSUMPTION in optional.findings.single().evidence)
    assertFalse(required.policyDecision.passed)
    assertEquals(setOf(assumption.id), required.findings.single().assumptionIds)
  }

  private fun invalidCanvas() =
    CanvasExpression.Layer(
      "invalid",
      CanvasExpression.Empty,
      listOf(binding(service, "service", listOf(lookup("construction", metrics, LookupKind.CONSTRUCTION)))),
      site = site("invalid"),
    )

  private fun observableAlias(id: String) =
    CanvasExpression.Alias(
      id,
      CanvasExpression.Layer(
        "$id-layer",
        CanvasExpression.Empty,
        listOf(
          binding(metrics, "$id-metrics"),
          binding(service, "$id-service", listOf(lookup("$id-construction", metrics, LookupKind.CONSTRUCTION))),
        ),
        site = site("$id-layer"),
      ),
    )

  private fun populatedCanvas(id: String) =
    CanvasExpression.Layer(
      id,
      CanvasExpression.Empty,
      listOf(binding(metrics, "$id-metrics")),
      site = site(id),
    )

  private fun callWithCanvas(
    id: String,
    target: String,
    parameter: ContractParameter,
    canvas: CanvasExpression,
  ) = Effect.Call(
    id,
    target,
    CallArguments(mapOf(parameter to ArgumentExpression.Canvas(canvas))),
    site(id),
  )

  private fun opaqueAliasReport(
    id: String,
    whenTrue: CanvasExpression,
    whenFalse: CanvasExpression,
  ): AnalysisReport {
    val selected =
      CanvasExpression.Alias(
        id,
        CanvasExpression.Choice(
          Guard.Opaque("runtime selection", site("selection")),
          whenTrue,
          whenFalse,
        ),
      )
    val requested = tile("MetricsTile", lookup("metrics", metrics))
    val caller =
      entry(
        "entry",
        Effect.ConstructCanvas("construct", selected, site("construct")),
        compose("lookup", selected, requested.id),
      )
    return report(ModuleContract("app", tiles = listOf(requested), callables = listOf(caller)))
  }

  private fun guardedDuplicateReport(
    guard: Guard,
    parameters: List<ContractParameter> = emptyList(),
  ): AnalysisReport {
    val duplicate =
      CanvasExpression.Layer(
        "duplicate",
        CanvasExpression.Empty,
        listOf(binding(metrics, "first"), binding(metrics, "second")),
        site = site("duplicate"),
      )
    val selected = CanvasExpression.Choice(guard, duplicate, CanvasExpression.Empty)
    val caller =
      entry(
        "entry",
        Effect.ConstructCanvas("construct", selected, site("construct")),
        parameters = parameters,
      )
    return report(ModuleContract("app", callables = listOf(caller)))
  }

  private fun booleanRoot(
    parameter: ContractParameter,
    value: Boolean,
  ) = SelectedRoot(
    "root",
    "entry",
    CallArguments(mapOf(parameter to ArgumentExpression.BooleanValue(BooleanExpression.Constant(value)))),
  )

  private companion object {
    val lookupKinds =
      setOf(
        FindingKind.REQUIRED_LOOKUP,
        FindingKind.OPTIONAL_LOOKUP,
        FindingKind.CONSTRUCTION_LOOKUP,
      )
  }
}

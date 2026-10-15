package org.buildmosaic.analysis

import org.buildmosaic.analysis.AnalysisFixtures.binding
import org.buildmosaic.analysis.AnalysisFixtures.entry
import org.buildmosaic.analysis.AnalysisFixtures.lookup
import org.buildmosaic.analysis.AnalysisFixtures.metrics
import org.buildmosaic.analysis.AnalysisFixtures.platform
import org.buildmosaic.analysis.AnalysisFixtures.report
import org.buildmosaic.analysis.AnalysisFixtures.service
import org.buildmosaic.analysis.AnalysisFixtures.site
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@Suppress("FunctionMaxLength", "LargeClass")
class ActivationInvariantTest {
  @Test
  fun `A6 result wrappers respect blocked empty successful and unknown prefixes`() {
    val aborted =
      layer(
        "abort",
        listOf(binding(service, effects = listOf(lookup("abort-construction", metrics, LookupKind.CONSTRUCTION)))),
      )
    val result =
      layer(
        "result",
        listOf(binding(service, effects = listOf(lookup("result-construction", platform, LookupKind.CONSTRUCTION)))),
      )
    val prefixes =
      listOf(
        emptyList(),
        listOf(Effect.Unknown("opaque", "Opaque prefix", site("opaque"))),
        listOf(Effect.ConstructCanvas("good", CanvasExpression.Empty, site("good"))),
        listOf(Effect.ConstructCanvas("abort", aborted, site("abort"))),
      )
    prefixes.forEachIndexed { index, prefix ->
      val expression = CanvasExpression.WithEffects(prefix, result)
      val module =
        ModuleContract("app", canvases = listOf(CanvasContract("entry", result = expression, site = site("entry"))))
      val findings = report(module).findings
      assertEquals(index != 3, findings.any { it.key == platform }, findings.toString())
      assertEquals(index == 3, findings.any { it.key == metrics }, findings.toString())
    }
  }

  @Test
  fun `missing conflicting and limited callees preserve eager argument failures`() {
    for (factory in listOf(false, true)) {
      for (boundary in listOf("missing", "conflict", "limit")) {
        val parameter = ContractParameter("helper", "canvas", ParameterKind.CANVAS)
        val broken =
          layer(
            "broken",
            listOf(binding(service, effects = listOf(lookup("construction", metrics, LookupKind.CONSTRUCTION)))),
          )
        val arguments = CallArguments(mapOf(parameter to ArgumentExpression.Canvas(broken)))
        val effect = call(factory, arguments)
        val helper = CallableContract("helper", listOf(parameter), emptyList(), site("helper"))
        val canvas = CanvasContract("helper", listOf(parameter), CanvasExpression.Empty, site("helper"))
        val dependencies =
          if (boundary == "conflict") {
            listOf("one", "two").map { owner ->
              ModuleContract(owner, canvases = listOf(canvas), callables = listOf(helper))
            }
          } else {
            emptyList()
          }
        val result =
          report(
            ModuleContract("app", callables = listOf(entry("entry", effect))),
            dependencies = dependencies,
            limits = AnalysisLimits(expansionDepth = if (boundary == "limit") 0 else 20),
          )
        assertFalse(result.policyDecision.passed, "$factory $boundary")
        assertEquals(
          Certainty.MISSING,
          result.findings.single { it.kind == FindingKind.CONSTRUCTION_LOOKUP }.certainty,
        )
      }
    }
  }

  @Test
  fun `unknown callees retain localized uncertainty after successful argument evaluation`() {
    for (factory in listOf(false, true)) {
      val parameter = ContractParameter("helper", "canvas", ParameterKind.CANVAS)
      val constructed =
        layer(
          "constructed",
          listOf(
            binding(metrics),
            binding(service, effects = listOf(lookup("init", metrics, LookupKind.CONSTRUCTION))),
          ),
        )
      val result =
        report(
          ModuleContract(
            "app",
            callables =
              listOf(
                entry(
                  "entry",
                  call(factory, CallArguments(mapOf(parameter to ArgumentExpression.Canvas(constructed)))),
                ),
              ),
          ),
        )
      assertEquals(Certainty.VERIFIED, result.findings.single { it.kind == FindingKind.CONSTRUCTION_LOOKUP }.certainty)
      assertEquals(1, result.findings.count { it.kind == FindingKind.UNKNOWN_BOUNDARY })
    }
  }

  @Test
  fun `unknown selected input becomes an obligation only when required`() {
    val parameter = ContractParameter("entry", "canvas", ParameterKind.CANVAS)
    for (kind in listOf(LookupKind.OPTIONAL, LookupKind.REQUIRED)) {
      val result =
        report(
          ModuleContract(
            "app",
            callables =
              listOf(
                entry(
                  "entry",
                  lookup("read", metrics, kind, CanvasExpression.ParameterValue(parameter)),
                  parameters = listOf(parameter),
                ),
              ),
          ),
          policy = AnalysisPolicy.STRICT,
        )
      assertEquals(kind == LookupKind.OPTIONAL, result.policyDecision.passed)
      assertEquals(
        if (kind == LookupKind.OPTIONAL) Certainty.VERIFIED else Certainty.UNVERIFIED,
        result.findings.single().certainty,
      )
    }
  }

  @Test
  fun `missing mismatched and extra actuals remain malformed contracts`() {
    val parameter = ContractParameter("helper", "canvas", ParameterKind.CANVAS)
    val extra = ContractParameter("helper", "extra", ParameterKind.BOOLEAN)
    for (arguments in listOf(
      CallArguments(),
      CallArguments(mapOf(parameter to ArgumentExpression.BooleanValue(BooleanExpression.Constant(true)))),
      CallArguments(
        mapOf(
          parameter to ArgumentExpression.Canvas(CanvasExpression.Empty),
          extra to ArgumentExpression.BooleanValue(BooleanExpression.Constant(true)),
        ),
      ),
    )) {
      val result =
        report(
          ModuleContract(
            "app",
            callables = listOf(entry("entry", call(false, arguments)), entry("helper", parameters = listOf(parameter))),
          ),
          policy = AnalysisPolicy.STRICT,
        )
      assertFalse(result.policyDecision.passed)
      assertTrue(result.findings.all { it.kind == FindingKind.UNKNOWN_BOUNDARY })
    }
  }

  @Test
  fun `actual order follows supplied expressions and stops on construction failure`() {
    val first = ContractParameter("helper", "first", ParameterKind.CANVAS)
    val second = ContractParameter("helper", "second", ParameterKind.CANVAS)
    val good =
      layer(
        "good",
        listOf(
          binding(metrics),
          binding(service, effects = listOf(lookup("first-init", metrics, LookupKind.CONSTRUCTION))),
        ),
      )
    val bad =
      layer(
        "bad",
        listOf(binding(service, effects = listOf(lookup("second-init", metrics, LookupKind.CONSTRUCTION)))),
      )
    for (reverse in listOf(false, true)) {
      val pairs = listOf(first to ArgumentExpression.Canvas(good), second to ArgumentExpression.Canvas(bad))
      val arguments = CallArguments((if (reverse) pairs.reversed() else pairs).toMap())
      val result =
        report(
          ModuleContract(
            "app",
            callables =
              listOf(
                entry("entry", call(false, arguments)),
                entry("helper", parameters = listOf(second, first)),
              ),
          ),
        )
      assertEquals(
        if (reverse) {
          listOf(
            Certainty.MISSING,
          )
        } else {
          listOf(Certainty.VERIFIED, Certainty.MISSING)
        },
        result.findings.map {
          it.certainty
        },
      )
    }
  }

  @Test
  fun `same call site creates separate alias allocations per invocation`() {
    val parameter = ContractParameter("helper", "canvas", ParameterKind.CANVAS)
    val alias = CanvasExpression.Alias("value", CanvasExpression.ParameterValue(parameter))
    val helper = entry("helper", lookup("read", metrics, canvas = alias), parameters = listOf(parameter))
    val populated = layer("populated", listOf(binding(metrics)))
    val calls =
      listOf(populated, CanvasExpression.Empty).map {
          canvas ->
        call(false, CallArguments(mapOf(parameter to ArgumentExpression.Canvas(canvas))))
      }
    val result = report(ModuleContract("app", callables = listOf(entry("entry", *calls.toTypedArray()), helper)))
    assertEquals(setOf(Certainty.VERIFIED, Certainty.MISSING), result.findings.map { it.certainty }.toSet())
  }

  @Test
  fun `provider and lookup capture provenance remain separate and do not leak to siblings`() {
    val providerOrigin = CaptureOrigin.Constant("platform.KEY", "platform:v1", "primary")
    val lookupOrigin = CaptureOrigin.Constant("consumer.KEY", "consumer:v1", "primary")
    val key = CanvasKeyIdentity("Metrics", "primary")
    val providerSite = site("provider-key")
    val lookupSite = site("lookup-key")
    val canvas =
      CanvasExpression.Alias(
        "shared",
        layer(
          "layer",
          listOf(
            Binding(Fact.Known(key, providerSite, EvidenceKind.CAPTURED_FACT, providerOrigin), site = site("provider")),
            binding(service),
          ),
        ),
      )
    val capturedLookup =
      Effect.Lookup(
        "captured",
        canvas,
        Fact.Known(key, lookupSite, EvidenceKind.CAPTURED_FACT, lookupOrigin),
        LookupKind.REQUIRED,
        site("captured"),
      )
    val result =
      report(
        ModuleContract(
          "app",
          callables = listOf(entry("entry", capturedLookup, lookup("sibling", service, canvas = canvas))),
        ),
      )
    val finding = result.findings.single { it.key == key }
    assertEquals(setOf(providerOrigin, lookupOrigin), finding.capturedOrigins)
    assertEquals(setOf(providerOrigin), finding.bindingCapturedOrigins)
    assertEquals(providerSite, finding.bindingFactProvenance)
    assertEquals(lookupSite, finding.factProvenance)
    assertTrue(EvidenceKind.CAPTURED_FACT in finding.evidence)
    val sibling = result.findings.single { it.key == service }
    assertTrue(sibling.capturedOrigins.isEmpty())
    assertTrue(sibling.bindingCapturedOrigins.isEmpty())
    assertFalse(EvidenceKind.CAPTURED_FACT in sibling.evidence)
  }

  private fun layer(
    id: String,
    bindings: List<Binding>,
  ) = CanvasExpression.Layer(
    id,
    CanvasExpression.Empty,
    bindings,
    site = site(id),
  )

  private fun call(
    factory: Boolean,
    arguments: CallArguments,
  ): Effect =
    if (factory) {
      Effect.ConstructCanvas("call", CanvasExpression.RuntimeCall("helper", arguments, site("call")), site("call"))
    } else {
      Effect.Call("call", "helper", arguments, site("call"))
    }
}

@file:Suppress("LongMethod", "FunctionMaxLength", "LargeClass", "MaxLineLength")

package org.buildmosaic.analysis

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RootSelectionResolverTest {
  private val site = SourceLocation("fixture", "Fixture.kt", 1, 1)
  private val metrics = Fact.Known(CanvasKeyIdentity("fixture.Metrics"))
  private val other = Fact.Known(CanvasKeyIdentity("fixture.Other"))

  private fun compose(
    id: String,
    canvas: CanvasExpression = CanvasExpression.Empty,
  ) = Effect.Compose(id, canvas, TileReference.Stable("work"), site = site)

  private fun callable(
    id: String,
    vararg effects: Effect,
    parameters: List<ContractParameter> = emptyList(),
  ) = CallableContract(id, parameters, effects.toList(), site)

  private fun roots(
    local: ModuleContract,
    vararg dependencies: ModuleContract,
    explicit: List<String> = emptyList(),
  ) = RootSelectionResolver.resolve(local, dependencies.toList(), explicit)

  @Test fun `simple helper chain and independent callers choose outermost activations`() {
    val local =
      ModuleContract(
        "app",
        callables =
          listOf(
            callable("simple", compose("simple-compose")),
            callable("entry", Effect.Call("delegate", "helper", site = site)),
            callable("helper", compose("helper-compose")),
            callable("second", compose("second-compose")),
          ),
      )
    assertEquals(listOf("entry", "second", "simple"), roots(local).map { it.id })
    assertTrue(roots(local).all { it.selection == RootSelection.AUTOMATIC })
  }

  @Test fun `explicit selection is exact even when automatic discovery would reject the program`() {
    val canvas = ContractParameter("needsCanvas", "canvas", ParameterKind.CANVAS)
    val local =
      ModuleContract(
        "app",
        callables =
          listOf(
            callable(
              "needsCanvas",
              compose("execute", CanvasExpression.ParameterValue(canvas)),
              parameters = listOf(canvas),
            ),
          ),
      )
    assertEquals(listOf("needsCanvas"), roots(local, explicit = listOf("needsCanvas")).map { it.id })
    assertEquals(RootSelection.EXPLICIT, roots(local, explicit = listOf("needsCanvas")).single().selection)
    assertFailsWith<IllegalArgumentException> { roots(local) }
  }

  @Test fun `unrelated unknown and isolated lookup do not create roots`() {
    val local =
      ModuleContract(
        "app",
        callables =
          listOf(
            callable("entry", compose("execute")),
            callable("opaque", Effect.Unknown("unknown", "unrelated", site)),
            callable("lookupOnly", Effect.Lookup("read", CanvasExpression.Empty, metrics, LookupKind.REQUIRED, site)),
          ),
      )
    assertEquals(listOf("entry"), roots(local).map { it.id })
    val report = MosaicAnalyzer().analyze(AnalysisRequest(local, roots = roots(local)))
    assertFalse(report.findings.any { it.obligationId.contains("unknown") || it.obligationId.contains("read") })
  }

  @Test fun `cycle needs a selected-contract entry but does not recurse during discovery`() {
    val a = callable("a", Effect.Call("to-b", "b", site = site))
    val b = callable("b", Effect.Call("to-a", "a", site = site), compose("execute"))
    assertFailsWith<IllegalArgumentException> { roots(ModuleContract("app", callables = listOf(a, b))) }
    val entered =
      ModuleContract("app", callables = listOf(a, b, callable("entry", Effect.Call("start", "a", site = site))))
    assertEquals(listOf("entry"), roots(entered).map { it.id })
  }

  @Test fun `dependency Canvas template is anchored and specialized by application override`() {
    val baseCanvas = ContractParameter("Base.respond", "canvas", ParameterKind.CANVAS)
    val appCanvas = ContractParameter("App.respond", "canvas", ParameterKind.CANVAS)
    val layer =
      CanvasExpression.Layer(
        "provided",
        CanvasExpression.Empty,
        listOf(Binding(metrics, site = site)),
        site = site,
      )
    val dependency =
      ModuleContract(
        "platform",
        callables =
          listOf(
            callable(
              "Base.handle",
              Effect.ConstructCanvas("base", CanvasExpression.Alias("base", layer), site),
              Effect.Call(
                "respond",
                "Base.respond",
                CallArguments(
                  mapOf(baseCanvas to ArgumentExpression.Canvas(CanvasExpression.ValueReference("base", site))),
                ),
                site,
                DispatchReceiver.Forwarded,
                true,
              ),
            ),
            callable(
              "Base.respond",
              Effect.Unknown("abstract", "body unavailable", site),
              parameters = listOf(baseCanvas),
            ),
          ),
      )
    val local =
      ModuleContract(
        "app",
        callables =
          listOf(
            callable(
              "App.respond",
              compose("execute", CanvasExpression.ParameterValue(appCanvas)),
              parameters = listOf(appCanvas),
            ),
          ),
        overrides =
          listOf(
            ResolvedOverride("App", "Base.respond", "App.respond", listOf(OverrideSlot(baseCanvas, appCanvas, 0))),
          ),
        tiles =
          listOf(
            TileContract(
              "work",
              listOf(Effect.Lookup("need", CanvasExpression.Current, metrics, LookupKind.REQUIRED, site)),
              site,
            ),
          ),
      )
    val selected = roots(local, dependency)
    assertEquals(listOf("Base.handle @ App"), selected.map { it.id })
    assertEquals("App", selected.single().receiverType)
    val report = MosaicAnalyzer().analyze(AnalysisRequest(local, listOf(dependency), selected))
    assertEquals(RootStatus.VERIFIED, report.roots.single().status)
    assertTrue(report.roots.single().specializedContracts.contains("App.respond"))
  }

  @Test fun `local Canvas template is anchored and local caller supersedes it`() {
    val baseCanvas = ContractParameter("Base.respond", "canvas", ParameterKind.CANVAS)
    val appCanvas = ContractParameter("App.respond", "canvas", ParameterKind.CANVAS)
    val layer =
      CanvasExpression.Layer(
        "provided",
        CanvasExpression.Empty,
        listOf(Binding(metrics, site = site)),
        site = site,
      )
    val local =
      ModuleContract(
        "app",
        callables =
          listOf(
            callable(
              "Base.handle",
              Effect.ConstructCanvas("base", CanvasExpression.Alias("base", layer), site),
              Effect.Call(
                "respond",
                "Base.respond",
                CallArguments(
                  mapOf(baseCanvas to ArgumentExpression.Canvas(CanvasExpression.ValueReference("base", site))),
                ),
                site,
                DispatchReceiver.Forwarded,
                true,
              ),
            ),
            callable(
              "Base.respond",
              Effect.Unknown("abstract", "body unavailable", site),
              parameters = listOf(baseCanvas),
            ),
            callable(
              "App.respond",
              compose("execute", CanvasExpression.ParameterValue(appCanvas)),
              parameters = listOf(appCanvas),
            ),
          ),
        overrides =
          listOf(
            ResolvedOverride("App", "Base.respond", "App.respond", listOf(OverrideSlot(baseCanvas, appCanvas, 0))),
          ),
        tiles =
          listOf(
            TileContract(
              "work",
              listOf(Effect.Lookup("need", CanvasExpression.Current, metrics, LookupKind.REQUIRED, site)),
              site,
            ),
          ),
      )
    val selected = roots(local)
    assertEquals(listOf("Base.handle @ App"), selected.map { it.id })
    assertEquals("App", selected.single().receiverType)
    val report = MosaicAnalyzer().analyze(AnalysisRequest(local, roots = selected))
    assertEquals(RootStatus.VERIFIED, report.roots.single().status)
    assertTrue(report.findings.any { it.kind == FindingKind.REQUIRED_LOOKUP && it.certainty == Certainty.VERIFIED })

    val entry =
      callable(
        "entry",
        Effect.Call("start", "Base.handle", site = site, receiver = DispatchReceiver.Concrete("App")),
      )
    val entered = local.copy(callables = local.callables + entry)
    assertEquals(listOf("entry"), roots(entered).map { it.id })
  }

  @Test fun `receiver activations have independent incoming callers and independent outcomes`() {
    val baseCanvas = ContractParameter("Base.respond", "canvas", ParameterKind.CANVAS)
    val firstCanvas = ContractParameter("First.respond", "canvas", ParameterKind.CANVAS)
    val secondCanvas = ContractParameter("Second.respond", "canvas", ParameterKind.CANVAS)
    val dependency =
      ModuleContract(
        "platform",
        callables =
          listOf(
            callable(
              "Base.handle",
              Effect.Call(
                "respond",
                "Base.respond",
                CallArguments(
                  mapOf(
                    baseCanvas to
                      ArgumentExpression.Canvas(
                        CanvasExpression.Layer(
                          "metrics",
                          CanvasExpression.Empty,
                          listOf(Binding(metrics, site = site)),
                          site = site,
                        ),
                      ),
                  ),
                ),
                site,
                DispatchReceiver.Forwarded,
                true,
              ),
            ),
          ),
      )
    val local =
      ModuleContract(
        "app",
        callables =
          listOf(
            callable(
              "entry",
              Effect.Call("start", "Base.handle", site = site, receiver = DispatchReceiver.Concrete("First")),
            ),
            callable(
              "First.respond",
              Effect.Compose(
                "first-compose",
                CanvasExpression.ParameterValue(firstCanvas),
                TileReference.Stable("first-work"),
                site = site,
              ),
              parameters = listOf(firstCanvas),
            ),
            callable(
              "Second.respond",
              Effect.Compose(
                "second-compose",
                CanvasExpression.ParameterValue(secondCanvas),
                TileReference.Stable("second-work"),
                site = site,
              ),
              parameters = listOf(secondCanvas),
            ),
          ),
        overrides =
          listOf(
            ResolvedOverride(
              "First",
              "Base.respond",
              "First.respond",
              listOf(OverrideSlot(baseCanvas, firstCanvas, 0)),
            ),
            ResolvedOverride(
              "Second",
              "Base.respond",
              "Second.respond",
              listOf(OverrideSlot(baseCanvas, secondCanvas, 0)),
            ),
          ),
        tiles =
          listOf(
            TileContract(
              "first-work",
              listOf(Effect.Lookup("need-metrics", CanvasExpression.Current, metrics, LookupKind.REQUIRED, site)),
              site,
            ),
            TileContract(
              "second-work",
              listOf(Effect.Lookup("need-other", CanvasExpression.Current, other, LookupKind.REQUIRED, site)),
              site,
            ),
          ),
      )
    val selected = roots(local, dependency)
    assertEquals(listOf("Base.handle @ Second", "entry"), selected.map { it.id })
    val report = MosaicAnalyzer().analyze(AnalysisRequest(local, listOf(dependency), selected))
    assertEquals(RootStatus.VERIFIED, report.roots.single { it.root.target == "entry" }.status)
    assertEquals(RootStatus.FAILED, report.roots.single { it.root.receiverType == "Second" }.status)
    assertEquals(
      setOf("First", "Second"),
      report.roots.mapNotNull { root ->
        root.root.receiverType ?: if (root.root.target == "entry") "First" else null
      }.toSet(),
    )
    val externalOnly = local.copy(callables = local.callables.filterNot { it.id == "entry" })
    val externalRoots = roots(externalOnly, dependency)
    assertEquals(listOf("Base.handle @ First", "Base.handle @ Second"), externalRoots.map { it.id })
    val externalReport = MosaicAnalyzer().analyze(AnalysisRequest(externalOnly, listOf(dependency), externalRoots))
    assertEquals(RootStatus.VERIFIED, externalReport.roots.single { it.root.receiverType == "First" }.status)
    assertEquals(RootStatus.FAILED, externalReport.roots.single { it.root.receiverType == "Second" }.status)
  }

  @Test fun `forwarded receiver does not cross a receiverless relay`() {
    val local =
      ModuleContract(
        "app",
        callables =
          listOf(
            callable(
              "entry",
              Effect.Call("relay", "relay", site = site, receiver = DispatchReceiver.None),
              compose("direct"),
            ),
            callable(
              "relay",
              Effect.Call(
                "dispatch",
                "Base.respond",
                site = site,
                receiver = DispatchReceiver.Forwarded,
                virtualDispatch = true,
              ),
            ),
            callable("Bad.respond", compose("bad")),
          ),
        overrides = listOf(ResolvedOverride("Bad", "Base.respond", "Bad.respond")),
        tiles = listOf(TileContract("work", emptyList(), site)),
      )
    val selected = roots(local)
    assertEquals(listOf("entry", "relay @ Bad"), selected.map { it.id })
    val report = MosaicAnalyzer().analyze(AnalysisRequest(local, roots = selected))
    val receiverless = report.roots.single { it.root.target == "entry" }
    assertEquals(RootStatus.UNVERIFIED, receiverless.status)
    assertTrue(receiverless.findings.any { it.reason.contains("Virtual receiver is unresolved") })
    assertFalse("Bad.respond" in receiverless.specializedContracts)
    assertEquals(RootStatus.VERIFIED, report.roots.single { it.root.receiverType == "Bad" }.status)
  }

  @Test fun `dependency caller above an anchored template becomes the receiver-specific root`() {
    val local =
      ModuleContract(
        "app",
        callables = listOf(callable("App.respond", compose("execute"))),
        overrides = listOf(ResolvedOverride("App", "Base.respond", "App.respond")),
      )
    val dependency =
      ModuleContract(
        "platform",
        callables =
          listOf(
            callable(
              "Base.handle",
              Effect.Call(
                "dispatch",
                "Base.respond",
                site = site,
                receiver = DispatchReceiver.Forwarded,
                virtualDispatch = true,
              ),
            ),
            callable(
              "Base.outer",
              Effect.Call("inner", "Base.handle", site = site, receiver = DispatchReceiver.Forwarded),
            ),
          ),
      )
    assertEquals(listOf("Base.outer @ App"), roots(local, dependency).map { it.id })
  }

  @Test fun `symbolic Boolean inputs are safe but duplicate selected contracts are not`() {
    val enabled = ContractParameter("entry", "enabled", ParameterKind.BOOLEAN)
    val local =
      ModuleContract(
        "app",
        callables =
          listOf(
            callable(
              "entry",
              Effect.Branch("branch", Guard.BooleanParameter(enabled), listOf(compose("execute")), site = site),
              parameters = listOf(enabled),
            ),
          ),
      )
    assertEquals(listOf("entry"), roots(local).map { it.id })
    val conflict =
      assertFailsWith<IllegalArgumentException> {
        roots(local, ModuleContract("other", callables = listOf(callable("entry", compose("other-execute")))))
      }
    assertTrue(conflict.message.orEmpty().contains("conflicting selected contracts"))
  }
}

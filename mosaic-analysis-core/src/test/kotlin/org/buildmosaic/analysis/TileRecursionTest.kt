package org.buildmosaic.analysis

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@Suppress("LargeClass", "FunctionMaxLength")
class TileRecursionTest {
  private val site = SourceLocation("tiles", "Tiles.kt", 14, 1)
  private val recursive = MosaicRule.MOSAIC_RECURSIVE_TILE
  private val keyed = MosaicRule.MOSAIC_RECURSIVE_MULTITILE
  private val cyclic = MosaicRule.MOSAIC_CYCLIC_TILE_DEPENDENCY

  private fun compose(
    target: String,
    mosaic: MosaicProvenance = MosaicProvenance.Current,
    discovery: DiscoveryKind = DiscoveryKind.COMPOSE,
    edgeSite: SourceLocation = site,
  ) = Effect.Compose(
    target,
    CanvasExpression.Current,
    TileReference.Stable(target),
    discovery,
    site = edgeSite,
    mosaic = mosaic,
  )

  private fun module(
    tiles: List<TileContract>,
    start: String = "A",
  ): ModuleContract =
    ModuleContract(
      "app",
      tiles = tiles,
      callables =
        listOf(
          CallableContract(
            "entry",
            effects =
              listOf(
                Effect.EstablishMosaic("request", CanvasExpression.Empty, site),
                compose(start, MosaicProvenance.Established("request")).copy(canvas = CanvasExpression.Empty),
              ),
            site = site,
          ),
        ),
    )

  private fun analyze(
    module: ModuleContract,
    policy: AnalysisPolicy = AnalysisPolicy.DEFAULT,
    severities: Map<String, MosaicRuleSeverity> = emptyMap(),
    dependencies: List<ModuleContract> = emptyList(),
  ) = MosaicAnalyzer().analyze(
    AnalysisRequest(
      module,
      dependencies,
      listOf(SelectedRoot("root", "entry")),
      policy = policy,
      ruleSeverities = severities,
    ),
  )

  @Test
  fun `stable mandatory cycles retain complete closed witness`() {
    for (names in listOf(listOf("A"), listOf("A", "B"), listOf("A", "B", "C"))) {
      val tiles =
        names.mapIndexed {
            index,
            name,
          ->
          TileContract(
            name,
            listOf(
              compose(
                names[(index + 1) % names.size],
                edgeSite = SourceLocation(name, "${name}Tiles.kt", index + 10, 1),
              ),
            ),
            site,
          )
        }
      for (policy in AnalysisPolicy.entries) {
        val report = analyze(module(tiles), policy)
        val finding = report.findings.single()
        assertEquals(cyclic, finding.rule)
        assertEquals(names + "A", finding.dependencyPath.map { it.label })
        assertEquals(
          listOf(site) + tiles.flatMap { it.effects }.map { it.site },
          finding.dependencyPath.map { it.site },
        )
        assertFalse(report.policyDecision.passed)
      }
    }
  }

  @Test
  fun `unknown steps cannot prove mandatory cycle edges`() {
    val a = TileContract("A", listOf(Effect.Unknown("control", "Unsupported control flow", site), compose("A")), site)
    val report = analyze(module(listOf(a)), AnalysisPolicy.STRICT, mapOf(recursive.id to MosaicRuleSeverity.WARNING))
    assertEquals(recursive, report.findings.single { it.rule != null }.rule)
    assertTrue(report.policyDecision.errors.all { it.rule == null })
    assertEquals(recursive, report.policyDecision.warnings.single().rule)
    val conditional =
      a.copy(
        effects =
          listOf(
            Effect.Branch(
              "opaque",
              Guard.Opaque("unknown", site),
              listOf(Effect.Unknown("control", "Unknown exit", site)),
              site = site,
            ),
            compose("A"),
          ),
      )
    assertEquals(recursive, analyze(module(listOf(conditional))).findings.single { it.rule != null }.rule)
    val launcher =
      a.copy(
        effects =
          listOf(
            Effect.Unknown("control", "Unknown launcher", site),
            compose("B", discovery = DiscoveryKind.COMPOSE_ASYNC),
          ),
      )
    val b = TileContract("B", listOf(compose("C")), site)
    val c = TileContract("C", listOf(compose("B")), site)
    assertEquals(cyclic, analyze(module(listOf(launcher, b, c))).findings.single { it.rule != null }.rule)
  }

  @Test
  fun `equal Canvas does not imply equal Mosaic`() {
    val tile =
      TileContract(
        "A",
        listOf(
          Effect.EstablishMosaic("fresh", CanvasExpression.Current, site),
          compose("A", MosaicProvenance.Established("fresh")),
        ),
        site,
      )
    val report = analyze(module(listOf(tile)))
    assertEquals(recursive, report.findings.single().rule)
    assertTrue(report.findings.single().reason.contains("not proven"))
  }

  @Test
  fun `async closed edge is policy but async launcher permits independent internal proof`() {
    val a = TileContract("A", listOf(compose("B", discovery = DiscoveryKind.COMPOSE_ASYNC)), site)
    val b = TileContract("B", listOf(compose("A")), site)
    assertEquals(recursive, analyze(module(listOf(a, b))).findings.single().rule)
    val internalB = b.copy(effects = listOf(compose("C")))
    val c = TileContract("C", listOf(compose("B")), site)
    val finding = analyze(module(listOf(a, internalB, c))).findings.single()
    assertEquals(cyclic, finding.rule)
    assertEquals(listOf("B", "C", "B"), finding.dependencyPath.map { it.label })
  }

  @Test
  fun `uncertain provenance opaque paths and pure keyed recursion are policy`() {
    val a = TileContract("A", listOf(compose("A", MosaicProvenance.Unknown)), site)
    assertEquals(recursive, analyze(module(listOf(a))).findings.single().rule)
    val opaque =
      a.copy(
        effects = listOf(Effect.Branch("branch", Guard.Opaque("unknown", site), listOf(compose("A")), site = site)),
      )
    assertEquals(recursive, analyze(module(listOf(opaque))).findings.single().rule)
    val multi = a.copy(multi = true, effects = listOf(compose("A")))
    assertEquals(keyed, analyze(module(listOf(multi))).findings.single().rule)
    val b = TileContract("B", listOf(compose("A")), site, multi = true)
    assertEquals(keyed, analyze(module(listOf(multi.copy(effects = listOf(compose("B"))), b))).findings.single().rule)
    assertEquals(recursive, analyze(module(listOf(a.copy(effects = listOf(compose("B"))), b))).findings.single().rule)
  }

  @Test
  fun `severity and suppression remain independent of global enforcement`() {
    val tile = TileContract("A", listOf(compose("A")), site, multi = true)
    for (policy in AnalysisPolicy.entries) {
      for (severity in MosaicRuleSeverity.entries) {
        val report = analyze(module(listOf(tile)), policy, mapOf(keyed.id to severity))
        assertEquals(severity != MosaicRuleSeverity.ERROR, report.policyDecision.passed)
        assertEquals(if (severity == MosaicRuleSeverity.WARNING) 1 else 0, report.policyDecision.warnings.size)
        assertEquals(if (severity == MosaicRuleSeverity.OFF) 0 else 1, report.findings.size)
      }
      val suppressed = tile.copy(suppressions = listOf(MosaicSuppression(keyed.id, site)))
      val report = analyze(module(listOf(suppressed)), policy)
      assertTrue(report.policyDecision.passed)
      assertEquals(listOf(site), report.findings.single().suppressedAt)
      assertTrue(report.policyDecision.warnings.isEmpty())
      val hard = tile.copy(multi = false, suppressions = listOf(MosaicSuppression(cyclic.id, site)))
      assertFalse(analyze(module(listOf(hard)), policy).policyDecision.passed)
      assertTrue(analyze(module(listOf(hard)), policy).findings.single().suppressedAt.isEmpty())
    }
  }

  @Test
  fun `binary participants own suppression and unrelated declarations cannot suppress`() {
    val a = TileContract("A", listOf(compose("B")), site)
    val b =
      TileContract(
        "B",
        listOf(compose("A", MosaicProvenance.Unknown)),
        site,
        suppressions = listOf(MosaicSuppression(recursive.id, site)),
      )
    val program = module(listOf(a))
    val dependency =
      SummaryCodec.decode(
        SummaryCodec.encode(ModuleContract("lib", tiles = listOf(b)), coreAnalysisRevisions = setOf(1)),
      ).module
    val sourceReport = analyze(module(listOf(a, b)))
    val binaryReport = analyze(program, dependencies = listOf(dependency))
    assertEquals(sourceReport.findings, binaryReport.findings)
    assertTrue(binaryReport.policyDecision.passed)
    val unrelated = b.copy(id = "Other")
    assertFalse(analyze(module(listOf(a, b.copy(suppressions = emptyList()), unrelated))).policyDecision.passed)
    val graph = MosaicGraph.render(AnalysisRequest(program, listOf(dependency), listOf(SelectedRoot("root", "entry"))))
    assertTrue(graph.contains("SUPPRESSED"))
    assertTrue(graph.contains("Suppressed at Tiles.kt:14"))
  }

  @Test
  fun `hard correctness rules and unknown rule IDs reject severity overrides`() {
    val program = module(listOf(TileContract("A", listOf(compose("A")), site)))
    assertFailsWith<IllegalArgumentException> {
      analyze(
        program,
        severities = mapOf(cyclic.id to MosaicRuleSeverity.OFF),
      )
    }
    assertFailsWith<IllegalArgumentException> {
      analyze(
        program,
        severities = mapOf("TYPO" to MosaicRuleSeverity.ERROR),
      )
    }
  }
}

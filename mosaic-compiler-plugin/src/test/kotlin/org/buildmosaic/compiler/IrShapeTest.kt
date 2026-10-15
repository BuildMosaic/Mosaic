@file:Suppress("LargeClass", "LongMethod", "FunctionMaxLength", "MaxLineLength", "ktlint:standard:max-line-length")

package org.buildmosaic.compiler

import org.buildmosaic.analysis.AnalysisPolicy
import org.buildmosaic.analysis.AnalysisRequest
import org.buildmosaic.analysis.CanvasExpression
import org.buildmosaic.analysis.Certainty
import org.buildmosaic.analysis.DiscoveryKind
import org.buildmosaic.analysis.DispatchReceiver
import org.buildmosaic.analysis.Effect
import org.buildmosaic.analysis.LookupKind
import org.buildmosaic.analysis.MosaicAnalyzer
import org.buildmosaic.analysis.MultiTileExecution
import org.buildmosaic.analysis.SelectedRoot
import org.buildmosaic.analysis.SummaryCodec
import org.buildmosaic.analysis.TileReference
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IrShapeTest {
  @Test
  fun `Canvas actual is evaluated once before parameter substitution`() {
    val report =
      analyzeSource(
        """
        package regression
        import org.buildmosaic.core.*
        import org.buildmosaic.core.injection.*
        class Metrics
        val ExampleTile = singleTile { source<Metrics>(); "ok" }
        suspend fun consume(base: Canvas): String = base.withMosaic { compose(ExampleTile) }
        suspend fun entry(): String = consume(canvas { provide<Metrics> { Metrics() } })
        """.trimIndent(),
      )
    assertEquals(org.buildmosaic.analysis.RootStatus.VERIFIED, report.roots.single().status, report.toString())
    assertEquals(1, report.findings.count { it.key?.classId == "regression.Metrics" })
  }

  @Test
  fun `missing callee metadata retains known caller argument lookup`() {
    val directory = Files.createTempDirectory("mosaic-missing-callee").toFile()
    val producer =
      File(directory, "Producer.kt").apply {
        writeText("package producer\nclass Metrics\nfun consume(value: Metrics) = Unit")
      }
    val producerClasses = File(directory, "producer-classes")
    compile(producer, producerClasses)
    val jar = File(directory, "producer.jar")
    jarClasses(producerClasses, jar)
    val caller =
      File(directory, "Caller.kt").apply {
        writeText(
          """
          package regression
          import org.buildmosaic.core.*
          import org.buildmosaic.core.injection.*
          import producer.*
          val ExampleTile = singleTile { consume(source<Metrics>()) }
          suspend fun entry() = canvas { }.withMosaic { compose(ExampleTile) }
          """.trimIndent(),
        )
      }
    val probe = File(directory, "probe/facts.txt")
    compile(caller, File(directory, "caller-classes"), jar, probe, "regression")
    val module = SummaryCodec.decode(File(probe.parentFile, "summary.json").readBytes()).module
    val report =
      MosaicAnalyzer().analyze(
        AnalysisRequest(module, roots = listOf(SelectedRoot("entry", "regression.entry()"))),
      )
    assertTrue(
      report.findings.any {
        it.certainty == Certainty.MISSING && it.key?.classId == "producer.Metrics"
      },
      report.toString(),
    )
    assertTrue(
      report.findings.any {
        it.certainty == Certainty.UNVERIFIED && it.reason.contains("callable target")
      },
      report.toString(),
    )
  }

  @Test
  fun `Canvas helper evaluates ordinary actual before delivering result`() {
    val report =
      analyzeSource(
        """
        package regression
        import org.buildmosaic.core.*
        import org.buildmosaic.core.injection.*
        class Metrics
        val ExampleTile = singleTile { "ok" }
        suspend fun helper(ignored: Metrics): Canvas = canvas { }
        suspend fun entry() = helper(canvas { }.withMosaic { source<Metrics>() }).withMosaic { compose(ExampleTile) }
        """.trimIndent(),
      )
    assertTrue(
      report.findings.any {
        it.certainty == Certainty.MISSING && it.key?.classId == "regression.Metrics"
      },
      report.toString(),
    )
    assertTrue(report.findings.none { it.certainty == Certainty.UNVERIFIED }, report.toString())
  }

  @Test
  fun `Canvas helper retains work before its returned value`() {
    val report =
      analyzeSource(
        """
        package regression
        import org.buildmosaic.core.*
        import org.buildmosaic.core.injection.*
        class Metrics
        val ExampleTile = singleTile { "ok" }
        suspend fun helper(): Canvas {
          canvas { }.withMosaic {
            val separate = this
            separate.source<Metrics>()
          }
          return canvas { }
        }
        suspend fun entry() = helper().withMosaic { compose(ExampleTile) }
        """.trimIndent(),
      )
    assertTrue(
      report.findings.any {
        it.certainty == Certainty.MISSING && it.key?.classId == "regression.Metrics"
      },
      report.toString(),
    )
  }

  @Test
  fun `constructor capability work cannot disappear`() {
    val report =
      analyzeSource(
        """
        package regression
        import org.buildmosaic.core.*
        import org.buildmosaic.core.injection.*
        class Metrics
        class ReadsDuringConstruction(m: Mosaic) { init { m.source<Metrics>() } }
        suspend fun entry() { canvas { }.withMosaic { ReadsDuringConstruction(this) } }
        """.trimIndent(),
      )
    assertTrue(
      report.findings.any {
        it.certainty == Certainty.UNVERIFIED && it.reason.contains("Constructor initialization may have Mosaic effects")
      },
      report.toString(),
    )
  }

  @Test
  fun `getter capability work is summarized`() {
    val (module, report) =
      extractSource(
        """
        package regression
        import org.buildmosaic.core.*
        import org.buildmosaic.core.injection.*
        class Metrics
        class Reads(val base: Canvas) { val metrics: Metrics get() = base.source<Metrics>() }
        suspend fun entry() { Reads(canvas { }).metrics }
        """.trimIndent(),
      )
    assertTrue(
      module.callables.any {
        it.id.contains("<get-metrics>") &&
          it.effects.any {
              effect ->
            effect is Effect.Lookup
          }
      },
    )
    assertTrue(
      report.findings.any {
        it.certainty == Certainty.UNVERIFIED && it.key?.classId == "regression.Metrics"
      },
      report.toString(),
    )
  }

  @Test
  fun `lookup follows current and separate Mosaic receivers`() {
    val (module, separateReport) =
      extractSource(
        """
        package regression
        import org.buildmosaic.core.*
        import org.buildmosaic.core.injection.*
        class Metrics
        val SeparateTile = singleTile { canvas { }.withMosaic { source<Metrics>() } }
        val CurrentTile = singleTile { val same = this; same.source<Metrics>() }
        val OwnCanvasTile = singleTile { canvas { provide<Metrics> { Metrics() } }.withMosaic { source<Metrics>() } }
        suspend fun entry() = canvas { provide<Metrics> { Metrics() } }.withMosaic { compose(SeparateTile) }
        suspend fun currentEntry() = canvas { provide<Metrics> { Metrics() } }.withMosaic { compose(CurrentTile) }
        suspend fun ownCanvasEntry() = canvas { }.withMosaic { compose(OwnCanvasTile) }
        """.trimIndent(),
      )
    assertTrue(
      separateReport.findings.any {
        it.certainty == Certainty.MISSING && it.key?.classId == "regression.Metrics"
      },
      separateReport.toString(),
    )
    listOf("regression.currentEntry()", "regression.ownCanvasEntry()").forEach { target ->
      val report = analyzeModule(module, target)
      assertEquals(org.buildmosaic.analysis.RootStatus.VERIFIED, report.roots.single().status, "$target: $report")
      assertTrue(report.policyDecision.passed, "$target: $report")
      assertTrue(
        report.findings.any { it.certainty == Certainty.VERIFIED && it.key?.classId == "regression.Metrics" },
        "$target: $report",
      )
    }
  }

  @Test
  fun `MultiTile execution follows known and unknown key requests`() {
    val (module, emptyReport) =
      extractSource(
        """
        package regression
        import org.buildmosaic.core.*
        import org.buildmosaic.core.injection.*
        class Metrics
        val BatchTile = multiTile<String, String> { source<Metrics>(); emptyMap() }
        suspend fun empty() = canvas { }.withMosaic { compose(BatchTile, emptyList()) }
        suspend fun emptyAsync() = canvas { }.withMosaic { composeAsync(BatchTile, emptyList()) }
        suspend fun emptyLiteral() = canvas { }.withMosaic { composeAsync(BatchTile, listOf<String>()) }
        suspend fun nonemptyAsync() = canvas { }.withMosaic { composeAsync(BatchTile, listOf("x")) }
        suspend fun unknown(keys: Collection<String>) = canvas { }.withMosaic { composeAsync(BatchTile, keys) }
        """.trimIndent(),
        rootTarget = "regression.empty()",
      )
    assertTrue(module.tiles.single().multi)
    assertTrue(module.tiles.single().effects.any { it is Effect.Lookup && it.key.toString().contains("Metrics") })

    fun execution(target: String): MultiTileExecution =
      module.callables.single { it.id == target }.effects.filterIsInstance<Effect.Compose>().single().execution

    assertEquals(MultiTileExecution.KNOWN_EMPTY, execution("regression.empty()"))
    assertEquals(
      org.buildmosaic.analysis.RootStatus.VERIFIED,
      emptyReport.roots.single().status,
      emptyReport.toString(),
    )
    assertTrue(emptyReport.policyDecision.passed, emptyReport.toString())
    listOf("regression.emptyAsync()", "regression.emptyLiteral()").forEach { target ->
      assertEquals(MultiTileExecution.KNOWN_EMPTY, execution(target))
      val report = analyzeModule(module, target)
      assertEquals(org.buildmosaic.analysis.RootStatus.VERIFIED, report.roots.single().status, "$target: $report")
      assertTrue(report.policyDecision.passed, "$target: $report")
    }

    val nonemptyTarget = "regression.nonemptyAsync()"
    assertEquals(MultiTileExecution.KNOWN_NON_EMPTY, execution(nonemptyTarget))
    val nonemptyReport = analyzeModule(module, nonemptyTarget)
    assertTrue(
      nonemptyReport.findings.any { it.certainty == Certainty.MISSING && it.key?.classId == "regression.Metrics" },
      nonemptyReport.toString(),
    )

    val unknownTarget = "regression.unknown(kotlin.collections.Collection)"
    assertEquals(MultiTileExecution.UNKNOWN, execution(unknownTarget))
    val unknownReport = analyzeModule(module, unknownTarget)
    assertTrue(unknownReport.roots.single().specializedContracts.contains(unknownTarget), unknownReport.toString())
    assertTrue(
      unknownReport.findings.any {
        it.certainty == Certainty.UNVERIFIED && it.key?.classId == "regression.Metrics" &&
          it.pathCondition.any { condition -> condition.contains("multi-tile executes") }
      },
      unknownReport.toString(),
    )
    assertEquals(org.buildmosaic.analysis.RootStatus.UNVERIFIED, unknownReport.roots.single().status)
  }

  @Test
  fun `MultiTile key expressions and receiver construction execute`() {
    val keysReport =
      analyzeSource(
        """
        package regression
        import org.buildmosaic.core.*
        import org.buildmosaic.core.injection.*
        class Metrics
        val BatchTile = multiTile<String, String> { source<Metrics>(); emptyMap() }
        suspend fun entry() {
          canvas { }.withMosaic {
            val mosaic = this
            mosaic.compose(BatchTile, listOf(mosaic.source<String>()))
          }
        }
        """.trimIndent(),
      )
    assertTrue(
      keysReport.findings.any {
        it.certainty == Certainty.MISSING && it.key?.classId == "kotlin.String"
      },
      keysReport.toString(),
    )
    assertTrue(
      keysReport.findings.any {
        it.certainty == Certainty.MISSING && it.key?.classId == "regression.Metrics"
      },
      keysReport.toString(),
    )
    val receiverReport =
      analyzeSource(
        """
        package regression
        import org.buildmosaic.core.*
        import org.buildmosaic.core.injection.*
        class Metrics
        class Service(val metrics: Metrics)
        val BatchTile = multiTile<String, String> { emptyMap() }
        suspend fun entry() = canvas { provide<Service> { Service(source<Metrics>()) } }.withMosaic { compose(BatchTile, emptyList()) }
        """.trimIndent(),
      )
    assertTrue(
      receiverReport.findings.any {
        it.certainty == Certainty.MISSING && it.kind == org.buildmosaic.analysis.FindingKind.CONSTRUCTION_LOOKUP
      },
      receiverReport.toString(),
    )
  }

  @Test
  fun `extracted collection and array keys follow runtime class identity`() {
    val (module, report) =
      extractSource(
        """
        package regression
        import org.buildmosaic.core.*
        import org.buildmosaic.core.injection.*
        val StringsTile = singleTile { source<Array<String>>() }
        val ExampleTile = singleTile { source<List<String>>(); source<Set<String>>(); source<Map<String, String>>() }
        suspend fun entry() = canvas {
          provide<MutableList<String>> { mutableListOf() }
          provide<MutableSet<String>> { mutableSetOf() }
          provide<MutableMap<String, String>> { mutableMapOf() }
        }.withMosaic { compose(ExampleTile) }
        suspend fun mismatch() = canvas { provide<Array<Int>> { arrayOf(1) } }.withMosaic { compose(StringsTile) }
        suspend fun match() = canvas { provide<Array<String>> { arrayOf("one") } }.withMosaic { compose(StringsTile) }
        val NestedTile = singleTile { source<Array<List<String>>>() }
        suspend fun nestedMatch() = canvas { provide<Array<List<Int>>> { arrayOf(listOf(1)) } }.withMosaic { compose(NestedTile) }
        """.trimIndent(),
      )
    assertEquals(org.buildmosaic.analysis.RootStatus.VERIFIED, report.roots.single().status, report.toString())
    assertTrue(report.policyDecision.passed, report.toString())
    val mismatchTarget = "regression.mismatch()"
    assertTrue(module.callables.any { it.id == mismatchTarget }, module.toString())
    assertTrue(
      module.callables.single { it.id == mismatchTarget }.effects.any { it is Effect.Compose },
      module.toString(),
    )
    val mismatch = analyzeModule(module, mismatchTarget)
    val stringKey =
      module.tiles.single { it.id == "regression.StringsTile" }.effects.filterIsInstance<Effect.Lookup>().single().key
    val canvasEffect =
      module.callables.single { it.id == mismatchTarget }.effects.filterIsInstance<Effect.ConstructCanvas>().first()
    val mismatchLayer = (canvasEffect.canvas as CanvasExpression.Alias).expression as CanvasExpression.Layer
    assertEquals("[Ljava/lang/String;", (stringKey as org.buildmosaic.analysis.Fact.Known).value.classId)
    assertEquals(
      "[Ljava/lang/Integer;",
      (mismatchLayer.bindings.single().key as org.buildmosaic.analysis.Fact.Known).value.classId,
    )
    val nestedKey =
      module.tiles.single { it.id == "regression.NestedTile" }.effects.filterIsInstance<Effect.Lookup>().single().key
    assertEquals("[Ljava/util/List;", (nestedKey as org.buildmosaic.analysis.Fact.Known).value.classId)
    assertTrue(
      mismatch.findings.any { it.certainty == Certainty.MISSING && it.key?.classId?.contains("String") == true },
      "$module\n$mismatch",
    )
    listOf("regression.match()", "regression.nestedMatch()").forEach { target ->
      val matching = analyzeModule(module, target)
      assertEquals(org.buildmosaic.analysis.RootStatus.VERIFIED, matching.roots.single().status, "$target: $matching")
      assertTrue(matching.policyDecision.passed, "$target: $matching")
    }
  }

  @Test
  fun `renamed override slots follow positions with a non Canvas parameter between`() {
    val (module, report) =
      extractSource(
        """
        package regression
        import org.buildmosaic.core.*
        import org.buildmosaic.core.injection.*
        class Metrics
        val NeedsMetrics = singleTile { source<Metrics>() }
        abstract class Base {
          suspend fun handle() = respond(canvas { }, "marker", canvas { provide<Metrics> { Metrics() } })
          protected abstract suspend fun respond(left: Canvas, marker: String, right: Canvas): Metrics
        }
        class Impl : Base() {
          override suspend fun respond(right: Canvas, marker: String, left: Canvas): Metrics = right.withMosaic { compose(NeedsMetrics) }
        }
        suspend fun entry() = Impl().handle()
        """.trimIndent(),
      )
    assertEquals(listOf(0, 2), module.overrides.single().slots.map { it.position })
    assertTrue(
      report.findings.any {
        it.certainty == Certainty.MISSING && it.key?.classId == "regression.Metrics"
      },
      report.toString(),
    )
  }

  @Test
  fun `unrelated relay receiver cannot inherit enclosing concrete type`() {
    val (module, report) =
      extractSource(
        """
        package regression
        import org.buildmosaic.core.*
        import org.buildmosaic.core.injection.*
        class Metrics
        val NeedsMetrics = singleTile { source<Metrics>() }
        abstract class Base { abstract suspend fun respond(base: Canvas) }
        class Good : Base() {
          suspend fun start() { relay(Bad(), canvas { }) }
          override suspend fun respond(base: Canvas) { }
        }
        class Bad : Base() {
          override suspend fun respond(base: Canvas) { base.withMosaic { compose(NeedsMetrics) } }
        }
        suspend fun relay(other: Base, base: Canvas) { other.respond(base) }
        suspend fun entry() { Good().start() }
        """.trimIndent(),
      )
    val relayCall =
      module.callables.single {
        it.id.startsWith("regression.relay(")
      }.effects.filterIsInstance<Effect.Call>().single()
    assertTrue(relayCall.receiver is DispatchReceiver.Unknown)
    assertEquals(org.buildmosaic.analysis.RootStatus.UNVERIFIED, report.roots.single().status, report.toString())
  }

  private fun analyzeSource(sourceText: String): org.buildmosaic.analysis.AnalysisReport {
    return extractSource(sourceText).second
  }

  private fun extractSource(
    sourceText: String,
    rootTarget: String = "regression.entry()",
  ): Pair<org.buildmosaic.analysis.ModuleContract, org.buildmosaic.analysis.AnalysisReport> {
    val directory = Files.createTempDirectory("mosaic-ir-regression").toFile()
    val source = File(directory, "Regression.kt").apply { writeText(sourceText) }
    val probe = File(directory, "probe/facts.txt")
    compile(source, File(directory, "classes"), output = probe, moduleId = "regression")
    val module = SummaryCodec.decode(File(probe.parentFile, "summary.json").readBytes()).module
    return module to analyzeModule(module, rootTarget)
  }

  private fun analyzeModule(
    module: org.buildmosaic.analysis.ModuleContract,
    rootTarget: String,
  ): org.buildmosaic.analysis.AnalysisReport =
    MosaicAnalyzer().analyze(
      AnalysisRequest(
        module,
        roots = listOf(SelectedRoot(rootTarget, rootTarget)),
        policy = AnalysisPolicy.STRICT,
      ),
    )

  @Test
  fun `unchanged binary caller retains external const literal after provider rebuild`() {
    val directory = Files.createTempDirectory("mosaic-const-binary").toFile()
    val producer = File(directory, "Producer.kt")

    fun producerSource(value: String) =
      """
      package consts
      import org.buildmosaic.core.injection.*
      const val QUALIFIER = "$value"
      class Metrics
      suspend fun base(): Canvas = canvas { provide<Metrics>(QUALIFIER) { Metrics() } }
      """.trimIndent()
    producer.writeText(producerSource("old"))
    val firstClasses = File(directory, "first-classes")
    val firstFacts = File(directory, "first/facts.txt")
    compile(producer, firstClasses, output = firstFacts, moduleId = "consts")
    val firstJar = File(directory, "first.jar")
    jarClasses(firstClasses, firstJar)

    val caller = File(directory, "Caller.kt")
    caller.writeText(
      """
      package consumer
      import org.buildmosaic.core.*
      import org.buildmosaic.core.injection.*
      import consts.*
      val CapturedTile = singleTile { source<Metrics>(QUALIFIER) }
      suspend fun entry(): Metrics = base().withMosaic { compose(CapturedTile) }
      """.trimIndent(),
    )
    val callerFacts = File(directory, "caller/facts.txt")
    compile(caller, File(directory, "caller-classes"), firstJar, callerFacts, "consumer")
    val callerBytes = File(callerFacts.parentFile, "summary.json").readBytes()
    val callerSummary = SummaryCodec.decode(callerBytes)
    assertTrue(callerSummary.limitations.any { it.contains("external const origin") })
    assertTrue(callerBytes.decodeToString().contains("\"qualifier\":\"old\""))

    producer.writeText(producerSource("new"))
    val secondFacts = File(directory, "second/facts.txt")
    compile(producer, File(directory, "second-classes"), output = secondFacts, moduleId = "consts")
    val secondSummary = SummaryCodec.decode(File(secondFacts.parentFile, "summary.json").readBytes())
    assertTrue(
      (secondSummary.module.canvases.single().result.evaluatedResult() as CanvasExpression.Layer).bindings.single().key.toString().contains(
        "new",
      ),
    )
    val report =
      MosaicAnalyzer().analyze(
        AnalysisRequest(
          callerSummary.module,
          listOf(secondSummary.module),
          listOf(SelectedRoot("consumer.entry()", "consumer.entry()")),
          policy = AnalysisPolicy.STRICT,
        ),
      )
    assertTrue(report.findings.any { it.certainty == Certainty.MISSING && it.key?.qualifier == "old" })
    assertTrue(callerBytes.contentEquals(File(callerFacts.parentFile, "summary.json").readBytes()))
  }

  @Test
  fun `external const is substituted but inline helper body is absent before IR lowering`() {
    val directory = Files.createTempDirectory("mosaic-ir-binary").toFile()
    val producer = File(directory, "Producer.kt")
    producer.writeText(
      """
      package producer
      import org.buildmosaic.core.Mosaic
      import org.buildmosaic.core.source
      const val QUALIFIER = "old"
      class Metrics
      inline fun Mosaic.helper(): Metrics = source<Metrics>()
      inline val Mosaic.inlineMetrics: Metrics get() = source<Metrics>()
      """.trimIndent(),
    )
    val producerClasses = File(directory, "producer-classes")
    val producerFacts = File(directory, "producer/facts.txt")
    compile(producer, producerClasses, output = producerFacts, moduleId = "producer")
    val producerJar = File(directory, "producer.jar")
    jarClasses(producerClasses, producerJar)
    val caller = File(directory, "Caller.kt")
    caller.writeText(
      """
      package consumer
      import org.buildmosaic.core.*
      import org.buildmosaic.core.injection.*
      import producer.*
      val Tile = singleTile { source<Metrics>(QUALIFIER); helper() }
      val AccessorTile = singleTile { inlineMetrics }
      suspend fun accessorEntry() = canvas { }.withMosaic { compose(AccessorTile) }
      """.trimIndent(),
    )
    val output = File(directory, "caller-facts.txt")
    compile(caller, File(directory, "caller-classes"), producerJar, output)
    val callerBytes = File(output.parentFile, "summary.json").readBytes()
    val summary = SummaryCodec.decode(callerBytes)
    assertTrue(summary.module.tiles.single { it.id == "consumer.Tile" }.effects.any { it is Effect.Unknown })
    val accessorTarget = "consumer.accessorEntry()"
    assertTrue(summary.module.callables.any { it.id == accessorTarget }, summary.module.toString())
    assertTrue(summary.module.callables.single { it.id == accessorTarget }.effects.any { it is Effect.Compose })
    assertTrue(
      summary.module.tiles.single { it.id == "consumer.AccessorTile" }.effects.any {
        it is Effect.Unknown && it.reason.contains("inline")
      },
      summary.module.toString(),
    )
    producer.writeText(
      """
      package producer
      import org.buildmosaic.core.Mosaic
      import org.buildmosaic.core.source
      const val QUALIFIER = "new"
      class Metrics
      inline fun Mosaic.helper(): Metrics = Metrics()
      inline val Mosaic.inlineMetrics: Metrics get() = Metrics()
      """.trimIndent(),
    )
    val newProducerFacts = File(directory, "new-producer/facts.txt")
    compile(producer, File(directory, "new-producer-classes"), output = newProducerFacts, moduleId = "producer")
    val newProducer = SummaryCodec.decode(File(newProducerFacts.parentFile, "summary.json").readBytes()).module
    val accessorReport =
      MosaicAnalyzer().analyze(
        AnalysisRequest(
          summary.module,
          listOf(newProducer),
          listOf(SelectedRoot(accessorTarget, accessorTarget)),
          policy = AnalysisPolicy.STRICT,
        ),
      )
    assertTrue(
      accessorReport.findings.any { it.certainty == Certainty.UNVERIFIED && it.reason.contains("inline") },
      "${summary.module}\n$accessorReport",
    )
    assertTrue(accessorReport.roots.single().specializedContracts.contains(accessorTarget), accessorReport.toString())
    assertEquals(org.buildmosaic.analysis.RootStatus.UNVERIFIED, accessorReport.roots.single().status)
    assertTrue(callerBytes.contentEquals(File(output.parentFile, "summary.json").readBytes()))
  }

  @Test
  fun `DSL extraction retains lookup execution and binary locator fidelity`() {
    val directory = Files.createTempDirectory("mosaic-ir-shape").toFile()
    val source = File(directory, "Fixture.kt")
    source.writeText(
      """
      package fixture
      import org.buildmosaic.core.*
      import org.buildmosaic.core.injection.*

      class GlobalContext
      class Metrics
      class Service(val metrics: Metrics)
      fun overloaded(value: String): String = value
      fun overloaded(value: Int): Int = value
      const val QUALIFIER = "qualified"
      val MetricsTile = singleTile { source<Metrics>() }
      val OtherTile = perKeyTile<String, String> { sourceOrNull<Metrics>(); it }
      val BatchTile = multiTile<String, String> { keys -> keys.associateWith { source<GlobalContext>(); it } }
      val ConditionalTile = singleTile { if (System.nanoTime() > 0) source<Metrics>() else null }
      val ExplicitKeyTile = singleTile { source(CanvasKey(Metrics::class, "dynamic")) }
      suspend fun platformCanvas(): Canvas = canvas { provide<GlobalContext> { GlobalContext() }; provide<Metrics>(QUALIFIER) { Metrics() } }
      suspend fun layer(parent: Canvas): Canvas = parent.withLayer { provide<Service> { Service(source<Metrics>()) } }
      suspend fun entry() {
        val base = platformCanvas()
        val aliased = base
        val tile = MetricsTile
        aliased.withMosaic { compose(tile) }
        aliased.withMosaic { composeAsync(OtherTile, "one") }
        aliased.withMosaic { composeAsync(BatchTile, listOf("one")) }
      }
      abstract class PlatformComponent {
        suspend fun handle(id: String): String = respond(platformCanvas(), id)
        protected abstract suspend fun respond(base: Canvas, id: String): String
      }
      class ApplicationComponent : PlatformComponent() {
        override suspend fun respond(base: Canvas, id: String): String { layer(base).withMosaic { compose(MetricsTile) }; return id }
      }
      """.trimIndent(),
    )
    val output = File(directory, "facts.txt")
    compile(source, File(directory, "classes"), output = output)
    val summary = SummaryCodec.decode(File(directory, "summary.json").readBytes())
    val tiles = summary.module.tiles.associateBy { it.id }
    assertTrue(
      tiles.getValue("fixture.MetricsTile").effects.any { it is Effect.Lookup && it.kind == LookupKind.REQUIRED },
    )
    assertTrue(
      tiles.getValue("fixture.OtherTile").effects.any { it is Effect.Lookup && it.kind == LookupKind.OPTIONAL },
    )
    assertTrue(tiles.getValue("fixture.BatchTile").effects.any { it is Effect.Unknown })
    assertTrue(
      tiles.getValue("fixture.ExplicitKeyTile").effects.any {
        it is Effect.Lookup && it.key is org.buildmosaic.analysis.Fact.Known
      },
    )
    val platform =
      summary.module.canvases.single {
        it.id == "fixture.platformCanvas()"
      }.result.evaluatedResult() as CanvasExpression.Layer
    assertTrue(platform.bindings.any { it.key.toString().contains("qualifier=qualified") })
    val layer =
      summary.module.canvases.single {
        it.id.startsWith("fixture.layer(")
      }.result.evaluatedResult() as CanvasExpression.Layer
    assertTrue(
      layer.bindings.single().constructorEffects.any { it is Effect.Lookup && it.kind == LookupKind.CONSTRUCTION },
    )
    val entryEffects = summary.module.callables.single { it.id == "fixture.entry()" }.effects
    assertTrue(
      entryEffects.any {
        it is Effect.Compose && it.discovery == DiscoveryKind.COMPOSE && it.tile == TileReference.Stable("fixture.MetricsTile") && it.canvas is CanvasExpression.ValueReference
      },
    )
    assertEquals(
      2,
      entryEffects.filterIsInstance<Effect.Compose>().count { it.discovery == DiscoveryKind.COMPOSE_ASYNC },
    )
    assertTrue(
      summary.module.overrides.any {
        it.receiverType == "fixture.ApplicationComponent" && it.baseId.startsWith("fixture.PlatformComponent.respond(")
      },
    )
    assertTrue(
      summary.module.tiles.single {
        it.id == "fixture.ConditionalTile"
      }.effects.any { it is org.buildmosaic.analysis.Effect.Unknown },
    )
    assertTrue(
      summary.binaryLocators.getValue(
        "fixture.platformCanvas()",
      ).contains("#platformCanvas(Lkotlin/coroutines/Continuation;)Ljava/lang/Object;"),
    )
    assertTrue(
      summary.binaryLocators.keys.containsAll(
        listOf("fixture.overloaded(kotlin.String)", "fixture.overloaded(kotlin.Int)"),
      ),
    )
    assertTrue(
      summary.binaryLocators.getValue(
        "fixture.PlatformComponent.handle(kotlin.String)",
      ).contains("#handle(Ljava/lang/String;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;"),
    )
  }
}

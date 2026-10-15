package org.buildmosaic.compiler

import org.buildmosaic.analysis.AnalysisPolicy
import org.buildmosaic.analysis.AnalysisRequest
import org.buildmosaic.analysis.CanvasExpression
import org.buildmosaic.analysis.Certainty
import org.buildmosaic.analysis.Effect
import org.buildmosaic.analysis.ModuleContract
import org.buildmosaic.analysis.MosaicAnalyzer
import org.buildmosaic.analysis.RootStatus
import org.buildmosaic.analysis.SUMMARY_PATH
import org.buildmosaic.analysis.SelectedRoot
import org.buildmosaic.analysis.SummaryCodec
import java.io.File
import java.nio.file.Files
import java.util.jar.JarFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MosaicReceiverBoundaryTest {
  @Test
  fun `Mosaic extension helper uses its actual receiver`() {
    val directory = Files.createTempDirectory("mosaic-extension-probe").toFile()
    val source =
      File(directory, "Regression.kt").apply {
        writeText(extensionReceiverFixtureSource())
      }
    val module = compileAndExtract(listOf(source), directory, "regression")
    val report = analyze(module)
    assertTrue(
      module.tiles.single { it.id == "regression.ExampleTile" }.effects.any {
        it is Effect.Unknown && it.reason.contains("Mosaic extension receiver")
      },
      module.toString(),
    )
    assertEquals(RootStatus.UNVERIFIED, report.roots.single().status, report.toString())
    assertTrue(
      report.findings.any {
        it.certainty == Certainty.UNVERIFIED && it.reason.contains("Mosaic extension receiver")
      },
    )
    val suppliedReport = analyze(module, target = "regression.suppliedEntry()")
    assertEquals(RootStatus.UNVERIFIED, suppliedReport.roots.single().status, suppliedReport.toString())
    val parameterReport = analyze(module, target = "regression.parameterEntry()")
    assertEquals(RootStatus.UNVERIFIED, parameterReport.roots.single().status, parameterReport.toString())
    assertTrue(
      parameterReport.findings.none {
        it.certainty == Certainty.VERIFIED && it.key?.classId == "regression.Metrics"
      },
    )
    val accessorTarget = "regression.accessorEntry()"
    assertTrue(module.callables.any { it.id == accessorTarget }, module.toString())
    assertTrue(
      module.callables.single { it.id == accessorTarget }.effects.any { it is Effect.Compose },
      module.toString(),
    )
    assertTrue(
      module.tiles.single { it.id == "regression.AccessorTile" }.effects.any {
        it is Effect.Unknown && it.reason.contains("Mosaic extension receiver")
      },
      module.toString(),
    )
    val accessorReport = analyze(module, target = accessorTarget)
    assertTrue(
      accessorReport.findings.any {
        it.certainty == Certainty.UNVERIFIED && it.reason.contains("Mosaic extension receiver")
      },
      "$module\n$accessorReport",
    )
  }

  @Test
  fun `member extension remains unknown`() {
    val directory = Files.createTempDirectory("mosaic-member-extension").toFile()
    val source =
      File(directory, "Regression.kt").apply {
        writeText(
          """
          package regression
          import org.buildmosaic.core.*
          import org.buildmosaic.core.injection.*
          class Metrics
          class Reader {
            fun Mosaic.read(): Metrics = source<Metrics>()
            fun invoke(receiver: Mosaic) = receiver.read()
          }
          val ExampleTile = singleTile { canvas { }.withMosaic { Reader().invoke(this) } }
          suspend fun entry() = canvas { provide<Metrics> { Metrics() } }.withMosaic { compose(ExampleTile) }
          """.trimIndent(),
        )
      }
    val module = compileAndExtract(listOf(source), directory, "regression")
    assertTrue(
      module.callables.single { it.id.contains("Reader.invoke") }.effects.any {
        it is Effect.Unknown && it.reason.contains("Mosaic extension receiver")
      },
    )
    assertEquals(RootStatus.UNVERIFIED, analyze(module).roots.single().status)
  }

  @Test
  fun `Canvas Mosaic extension is unknown`() {
    val directory = Files.createTempDirectory("mosaic-canvas-extension").toFile()
    val source =
      File(directory, "Regression.kt").apply {
        writeText(
          """
          package regression
          import org.buildmosaic.core.*
          import org.buildmosaic.core.injection.*
          suspend fun Mosaic.expose(): Canvas = canvas { }
          suspend fun entry(): Canvas = canvas { }.withMosaic { expose() }
          """.trimIndent(),
        )
      }
    val module = compileAndExtract(listOf(source), directory, "regression")
    val result = module.canvases.single { it.id == "regression.entry()" }.result as CanvasExpression.WithEffects
    val report = analyze(module)
    assertEquals(RootStatus.UNVERIFIED, report.roots.single().status, result.toString())
    assertTrue(report.findings.any { it.reason.contains("Mosaic extension receiver") }, report.toString())
  }
}

class DefaultBoundaryTest {
  @Test
  fun `used default and explicit actual stay distinct`() {
    val directory = Files.createTempDirectory("mosaic-default-probe").toFile()
    val source =
      File(directory, "Regression.kt").apply {
        writeText(localDefaultFixtureSource())
      }
    val module = compileAndExtract(listOf(source), directory, "regression")
    val report = analyze(module)
    val effects = module.callables.single { it.id == "regression.entry()" }.effects
    assertTrue(effects.any { it is Effect.Unknown && it.reason.contains("default expression") }, module.toString())
    assertTrue(effects.indexOfFirst { it is Effect.ConstructCanvas } < effects.indexOfFirst { it is Effect.Unknown })
    assertTrue(effects.indexOfFirst { it is Effect.Unknown } < effects.indexOfLast { it is Effect.Call })
    assertEquals(RootStatus.UNVERIFIED, report.roots.single().status, report.toString())
    val explicit = analyze(module, target = "regression.explicitEntry()")
    assertEquals(RootStatus.VERIFIED, explicit.roots.single().status, explicit.toString())
    assertTrue(explicit.policyDecision.passed, explicit.toString())
    assertTrue(module.callables.single { it.id == "regression.explicitEntry()" }.effects.none { it is Effect.Unknown })
    val supplied = analyze(module, target = "regression.suppliedEntry()")
    assertEquals(RootStatus.UNVERIFIED, supplied.roots.single().status, supplied.toString())
    assertTrue(supplied.findings.none { it.certainty == Certainty.MISSING })
    val constructorTarget = "regression.constructorEntry()"
    assertTrue(module.callables.any { it.id == constructorTarget }, module.toString())
    val constructorEffects = module.callables.single { it.id == constructorTarget }.effects
    assertTrue(constructorEffects.any { it is Effect.Call }, module.toString())
    val defaultIndex =
      constructorEffects.indexOfFirst { it is Effect.Unknown && it.reason.contains("default expression") }
    assertTrue(
      defaultIndex > constructorEffects.indexOfFirst { it is Effect.ConstructCanvas },
      constructorEffects.toString(),
    )
    assertTrue(defaultIndex < constructorEffects.indexOfLast { it is Effect.Call }, constructorEffects.toString())
    val constructorReport = analyze(module, target = constructorTarget)
    assertTrue(
      constructorReport.findings.any {
        it.certainty == Certainty.UNVERIFIED && it.reason.contains("default expression")
      },
      "$module\n$constructorReport",
    )
    val explicitConstructor = analyze(module, target = "regression.explicitConstructorEntry()")
    assertEquals(RootStatus.VERIFIED, explicitConstructor.roots.single().status, explicitConstructor.toString())
    assertTrue(explicitConstructor.policyDecision.passed, explicitConstructor.toString())
    val explicitEffects = module.callables.single { it.id == "regression.explicitConstructorEntry()" }.effects
    assertTrue(
      explicitEffects.none { it is Effect.Unknown },
    )
  }

  @Test
  fun `binary defaults and Tile exports stay explicit`() {
    val directory = Files.createTempDirectory("mosaic-default-binary").toFile()
    val producer =
      File(directory, "Producer.kt").apply {
        writeText(binaryDefaultProducerSource())
      }
    compileAndExtract(listOf(producer), File(directory, "producer"), "producer")
    val jar = File(directory, "producer.jar")
    jarClasses(File(directory, "producer/classes"), jar, File(directory, "producer/summary.json"))
    val consumer =
      File(directory, "Entry.kt").apply {
        writeText(binaryDefaultConsumerSource())
      }
    val consumerModule = compileAndExtract(listOf(consumer), File(directory, "consumer"), "regression", jar)
    val producerModule = readJarSummary(jar)
    assertTrue(
      consumerModule.callables.single { it.id == "regression.entry()" }.effects.any {
        it is Effect.Unknown && it.reason.contains("default expression", ignoreCase = true)
      },
      consumerModule.toString(),
    )
    assertBinaryTileProperties(consumerModule, producerModule)
    val withMetadata = analyze(consumerModule, listOf(producerModule))
    val withoutMetadata = analyze(consumerModule)
    assertTrue(
      withMetadata.findings.any {
        it.reason.contains("Default expression is unavailable")
      },
      withMetadata.toString(),
    )
    assertTrue(
      withoutMetadata.findings.any {
        it.reason.contains("Default expression is unavailable")
      },
      withoutMetadata.toString(),
    )
    assertEquals(RootStatus.UNVERIFIED, withMetadata.roots.single().status)
    assertEquals(RootStatus.UNVERIFIED, withoutMetadata.roots.single().status)
    val constructorTarget = "regression.constructorEntry()"
    assertTrue(consumerModule.callables.any { it.id == constructorTarget }, consumerModule.toString())
    val constructorEffects = consumerModule.callables.single { it.id == constructorTarget }.effects
    assertTrue(constructorEffects.any { it is Effect.Call })
    assertTrue(
      constructorEffects.any { it is Effect.Unknown && it.reason.contains("Default expression is unavailable") },
    )
    listOf(listOf(producerModule), emptyList()).forEach { dependencies ->
      val report = analyze(consumerModule, dependencies, constructorTarget)
      assertTrue(
        report.findings.any {
          it.certainty == Certainty.UNVERIFIED && it.reason.contains("Default expression is unavailable")
        },
        "$consumerModule\n$report",
      )
    }
  }
}

class InitializationBoundaryTest {
  @Test
  fun `stored property initializer cannot disappear`() {
    val directory = Files.createTempDirectory("mosaic-property-probe").toFile()
    val state =
      File(directory, "State.kt").apply {
        writeText(
          """
          package regression
          import kotlinx.coroutines.runBlocking
          import org.buildmosaic.core.*
          import org.buildmosaic.core.injection.*
          class Metrics
          val startupMetrics = runBlocking { canvas { }.withMosaic { source<Metrics>() } }
          """.trimIndent(),
        )
      }
    val entry = File(directory, "Entry.kt").apply { writeText("package regression\nfun entry() { startupMetrics }") }
    val module = compileAndExtract(listOf(state, entry), directory, "regression")
    val report = analyze(module)
    assertTrue(
      module.callables.single {
        it.id.contains("get-startupMetrics")
      }.effects.any { it is Effect.Unknown },
      module.toString(),
    )
    assertEquals(RootStatus.UNVERIFIED, report.roots.single().status, report.toString())
    assertTrue(report.findings.any { it.reason.contains("callback") }, report.toString())
  }

  @Test
  fun `backing field read retains initializer boundary`() {
    val directory = Files.createTempDirectory("mosaic-field-probe").toFile()
    val source =
      File(directory, "State.kt").apply {
        writeText(
          """
          package regression
          import kotlinx.coroutines.runBlocking
          import org.buildmosaic.core.*
          import org.buildmosaic.core.injection.*
          class Metrics
          val startupMetrics: Metrics = runBlocking { canvas { }.withMosaic { source<Metrics>() } }
            get() = field
          fun entry() { startupMetrics }
          """.trimIndent(),
        )
      }
    val module = compileAndExtract(listOf(source), directory, "regression")
    val getter = module.callables.single { it.id.contains("get-startupMetrics") }
    assertTrue(getter.effects.any { it is Effect.Unknown }, getter.toString())
    assertEquals(RootStatus.UNVERIFIED, analyze(module).roots.single().status)
  }

  @Test
  fun `constant and domain constructor are harmless`() {
    val directory = Files.createTempDirectory("mosaic-harmless-init").toFile()
    val source =
      File(directory, "Regression.kt").apply {
        writeText(
          """
          package regression
          const val STARTUP = 7
          class Domain(val name: String)
          val domain = Domain("ok")
          fun entry() { STARTUP; domain }
          """.trimIndent(),
        )
      }
    val module = compileAndExtract(listOf(source), directory, "regression")
    assertTrue(module.callables.single { it.id == "regression.Domain.<init>(kotlin.String)" }.effects.isEmpty())
    assertEquals(RootStatus.VERIFIED, analyze(module).roots.single().status)
  }

  @Test
  fun `binary constructor metadata stays conservative`() {
    val directory = Files.createTempDirectory("mosaic-constructor-probe").toFile()
    val producer =
      File(directory, "Boot.kt").apply {
        writeText(
          """
          package producer
          import kotlinx.coroutines.runBlocking
          import org.buildmosaic.core.*
          import org.buildmosaic.core.injection.*
          class Metrics
          class Boot {
            init { runBlocking { canvas { }.withMosaic { source<Metrics>() } } }
          }
          """.trimIndent(),
        )
      }
    compileAndExtract(listOf(producer), File(directory, "producer"), "producer")
    val jar = File(directory, "producer.jar")
    jarClasses(File(directory, "producer/classes"), jar, File(directory, "producer/summary.json"))
    val consumer =
      File(directory, "Entry.kt").apply {
        writeText("package regression\nimport producer.Boot\nfun entry() { Boot() }")
      }
    val consumerModule = compileAndExtract(listOf(consumer), File(directory, "consumer"), "regression", jar)
    val producerModule = readJarSummary(jar)
    val withMetadata = analyze(consumerModule, listOf(producerModule))
    // The unchanged consumer keeps its symbolic reference when selected metadata disappears.
    val withoutMetadata = analyze(consumerModule)
    assertTrue(
      producerModule.callables.single {
        it.id == "producer.Boot.<init>()"
      }.effects.any { it is Effect.Unknown },
      producerModule.toString(),
    )
    assertTrue(
      consumerModule.callables.single().effects.any { it is Effect.Call && it.target == "producer.Boot.<init>()" },
    )
    assertTrue(
      withMetadata.findings.any {
        it.certainty == Certainty.UNVERIFIED && it.reason.contains("Constructor initialization")
      },
      withMetadata.toString(),
    )
    assertTrue(
      withoutMetadata.findings.any {
        it.certainty == Certainty.UNVERIFIED && it.reason.contains("target producer.Boot.<init>() is missing")
      },
      withoutMetadata.toString(),
    )
  }
}

class StoredFieldBoundaryTest {
  @Test
  fun `JvmField read retains initializer`() {
    val directory = Files.createTempDirectory("mosaic-jvm-field-probe").toFile()
    val source =
      File(directory, "State.kt").apply {
        writeText(
          """
          package regression
          import kotlinx.coroutines.runBlocking
          import org.buildmosaic.core.*
          import org.buildmosaic.core.injection.*
          class Metrics
          @JvmField val startupMetrics = runBlocking { canvas { }.withMosaic { source<Metrics>() } }
          fun entry() { startupMetrics }
          """.trimIndent(),
        )
      }
    val module = compileAndExtract(listOf(source), directory, "regression")
    val report = analyze(module)
    assertTrue(
      module.callables.single { it.id.contains("get-startupMetrics") }.effects.any { it is Effect.Unknown },
      module.toString(),
    )
    assertEquals(RootStatus.UNVERIFIED, report.roots.single().status, report.toString())
  }
}

private fun extensionReceiverFixtureSource(): String =
  """
  package regression
  import org.buildmosaic.core.*
  import org.buildmosaic.core.injection.*
  class Metrics
  fun Mosaic.readMetrics(): Metrics = source<Metrics>()
  val Mosaic.metricsValue: Metrics get() = source<Metrics>()
  fun parameterRead(receiver: Mosaic): Metrics = receiver.source<Metrics>()
  val ExampleTile = singleTile {
    canvas { }.withMosaic {
      val other = this
      other.readMetrics()
    }
  }
  val SuppliedTile = singleTile { canvas { provide<Metrics> { Metrics() } }.withMosaic { readMetrics() } }
  val ParameterTile = singleTile { canvas { }.withMosaic { parameterRead(this) } }
  val AccessorTile = singleTile { canvas { }.withMosaic { metricsValue } }
  suspend fun entry() = canvas { provide<Metrics> { Metrics() } }.withMosaic { compose(ExampleTile) }
  suspend fun suppliedEntry() = canvas { provide<Metrics> { Metrics() } }.withMosaic { compose(SuppliedTile) }
  suspend fun parameterEntry() = canvas { provide<Metrics> { Metrics() } }.withMosaic { compose(ParameterTile) }
  suspend fun accessorEntry() = canvas { provide<Metrics> { Metrics() } }.withMosaic { compose(AccessorTile) }
  """.trimIndent()

private fun assertBinaryTileProperties(
  consumer: ModuleContract,
  producer: ModuleContract,
) {
  val stable = analyze(consumer, listOf(producer), "regression.stableTileEntry()")
  assertEquals(RootStatus.VERIFIED, stable.roots.single().status, stable.toString())
  assertTrue(stable.policyDecision.passed)
  val computed = analyze(consumer, listOf(producer), "regression.computedTileEntry()")
  assertTrue(computed.roots.single().specializedContracts.contains("regression.computedTileEntry()"))
  assertTrue(
    computed.findings.any {
      it.certainty == Certainty.UNVERIFIED && it.reason.contains("exported stable Tile contract")
    },
    computed.toString(),
  )
  assertTrue(computed.findings.none { it.reason.contains("Selected root target is missing") })
  assertTrue(!computed.policyDecision.passed)
  assertTrue(producer.tiles.none { it.id == "producer.ExposedTile" }, producer.toString())
  val withoutExport = analyze(consumer, listOf(producer.copy(tiles = emptyList())), "regression.stableTileEntry()")
  assertTrue(
    withoutExport.findings.any {
      it.certainty == Certainty.UNVERIFIED && it.reason.contains("exported stable Tile contract")
    },
    withoutExport.toString(),
  )
}

private fun binaryDefaultProducerSource(): String =
  """
  package producer
  import org.buildmosaic.core.*
  import org.buildmosaic.core.injection.*
  val NeedsMetrics = singleTile { source<Metrics>(); "required" }
  val ExposedTile = singleTile { "backing" }
      get() { field; return NeedsMetrics }
  class Metrics
  fun consume(base: Canvas, ignored: Metrics = base.source<Metrics>()) = Unit
  class Holder(receiver: Mosaic, val metrics: Metrics = receiver.source<Metrics>())
  """.trimIndent()

private fun localDefaultFixtureSource(): String =
  """
  package regression
  import org.buildmosaic.core.*
  import org.buildmosaic.core.injection.*
  class Metrics
  fun consume(base: Canvas, ignored: Metrics = base.source<Metrics>()) = Unit
  class Holder(receiver: Mosaic, val metrics: Metrics = receiver.source<Metrics>())
  suspend fun entry() { consume(canvas { }) }
  suspend fun explicitEntry() { consume(canvas { }, ignored = Metrics()) }
  suspend fun suppliedEntry() { consume(canvas { provide<Metrics> { Metrics() } }) }
  suspend fun constructorEntry() { canvas { }.withMosaic { Holder(this) } }
  suspend fun explicitConstructorEntry() { canvas { }.withMosaic { Holder(this, metrics = Metrics()) } }
  """.trimIndent()

private fun binaryDefaultConsumerSource(): String =
  """
  package regression
  import org.buildmosaic.core.*
  import org.buildmosaic.core.injection.*
  import producer.*
  suspend fun stableTileEntry() { val alias = NeedsMetrics; canvas { provide<Metrics> { Metrics() } }.withMosaic { compose(alias) } }
  suspend fun computedTileEntry() = canvas {}.withMosaic { compose(ExposedTile) }
  suspend fun entry() { consume(canvas { }) }
  suspend fun constructorEntry() { canvas { }.withMosaic { Holder(this) } }
  """.trimIndent()

private fun analyze(
  module: ModuleContract,
  dependencies: List<ModuleContract> = emptyList(),
  target: String = "regression.entry()",
) = MosaicAnalyzer().analyze(
  AnalysisRequest(module, dependencies, listOf(SelectedRoot("entry", target)), policy = AnalysisPolicy.STRICT),
)

private fun readJarSummary(jar: File): ModuleContract =
  JarFile(jar).use { archive ->
    SummaryCodec.decode(archive.getInputStream(archive.getJarEntry(SUMMARY_PATH)).readBytes()).module
  }

/** Inspect the computation that initialized the returned value, without executing it twice. */
internal fun CanvasExpression.evaluatedResult(): CanvasExpression {
  if (this !is CanvasExpression.WithEffects || result !is CanvasExpression.ValueReference) return this
  val reference = result as CanvasExpression.ValueReference
  val initialization = effects.filterIsInstance<Effect.ConstructCanvas>().single { it.id == reference.id }
  return (initialization.canvas as CanvasExpression.Alias).expression
}

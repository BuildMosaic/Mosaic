@file:Suppress("FunctionMaxLength", "LongMethod", "MaxLineLength", "ktlint:standard:max-line-length")

package org.buildmosaic.compiler

import org.buildmosaic.analysis.AnalysisRequest
import org.buildmosaic.analysis.CanvasExpression
import org.buildmosaic.analysis.Certainty
import org.buildmosaic.analysis.Effect
import org.buildmosaic.analysis.MosaicAnalyzer
import org.buildmosaic.analysis.MosaicProvenance
import org.buildmosaic.analysis.MosaicRule
import org.buildmosaic.analysis.RootSelectionResolver
import org.buildmosaic.analysis.SelectedRoot
import org.buildmosaic.analysis.SummaryCodec
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScopedAnalysisTest {
  private companion object {
    val directory = Files.createTempDirectory("mosaic-scoped-analysis").toFile()
    val module by lazy {
      val source = File(directory, "Tiles.kt").apply { writeText(scopedSource) }
      val fileScope = File(directory, "FileScope.kt").apply { writeText(fileSource) }
      compileAndExtract(listOf(source, fileScope), File(directory, "producer"), "scoped")
    }
  }

  private fun report(name: String) =
    MosaicAnalyzer().analyze(
      AnalysisRequest(module, roots = listOf(SelectedRoot(name, "scoped.$name()"))),
    )

  @Test
  fun `direct scoped operations aliases and existing instances preserve fidelity`() {
    for (root in listOf(
      "direct",
      "qualified",
      "inferredQualified",
      "keyed",
      "strings",
      "aliased",
      "child",
      "optional",
    )) {
      assertTrue(report(root).policyDecision.passed, "$root: ${report(root)}")
      assertTrue(report(root).findings.none { it.certainty == Certainty.UNVERIFIED }, "$root: ${report(root)}")
    }
    val direct = module.callables.single { it.id == "scoped.direct()" }
    val establish = direct.effects.filterIsInstance<Effect.EstablishMosaic>().single()
    val compose = direct.effects.filterIsInstance<Effect.Compose>().single()
    assertEquals(MosaicProvenance.Established(establish.id), compose.mosaic)
    val tile = module.tiles.single { it.id == "scoped.A" }
    assertEquals(MosaicProvenance.Current, tile.effects.filterIsInstance<Effect.Compose>().single().mosaic)
    val discovered = RootSelectionResolver.resolve(module, emptyList(), emptyList())
    assertTrue(discovered.any { it.target == "scoped.direct()" })
    assertEquals(module, SummaryCodec.decode(SummaryCodec.encode(module)).module)
  }

  @Test
  fun `instance expression executes at registration before providers`() {
    val finding =
      report("registrationOrder").findings.single {
        it.key?.classId == "scoped.Service" && it.certainty == Certainty.MISSING
      }
    assertEquals(Certainty.MISSING, finding.certainty)
    val contract = module.canvases.single { it.id == "scoped.registerBeforeProvider()" }
    val layer = contract.result.evaluatedResult() as CanvasExpression.Layer
    assertTrue(layer.bindings.last().constructorEffects.isEmpty())
    assertTrue(report("dynamicInstance").findings.any { it.certainty == Certainty.UNVERIFIED })
    assertTrue(report("duplicateInstance").findings.any { it.certainty == Certainty.MISSING })
  }

  @Test
  fun `qualified and keyed instance arguments execute eagerly in source order`() {
    for ((root, first) in listOf("qualifiedArgumentOrder" to "readQualifier", "keyedArgumentOrder" to "readKey")) {
      val expression = module.canvases.single { it.id == "scoped.$root(org.buildmosaic.core.injection.Canvas)" }.result
      val effects = (expression as CanvasExpression.WithEffects).effects
      val calls = effects.filterIsInstance<Effect.Call>().map { it.target }
      assertEquals(
        listOf(
          "scoped.$first(org.buildmosaic.core.injection.Canvas)",
          "scoped.readValue(org.buildmosaic.core.injection.Canvas)",
        ),
        calls,
      )
      val layer = expression.evaluatedResult() as CanvasExpression.Layer
      assertTrue(layer.bindings.single().constructorEffects.isEmpty())
    }
  }

  @Test
  fun `current and separately created Mosaic receive different classifications`() {
    assertEquals(MosaicRule.MOSAIC_CYCLIC_TILE_DEPENDENCY, report("hard").findings.single().rule)
    assertEquals(MosaicRule.MOSAIC_RECURSIVE_TILE, report("fresh").findings.single().rule)
    assertEquals(MosaicRule.MOSAIC_RECURSIVE_TILE, report("guarded").findings.single { it.rule != null }.rule)
    assertTrue(report("guarded").findings.any { it.rule == null && it.certainty == Certainty.UNVERIFIED })
    assertTrue(
      report("helperBoundary").findings.any {
        it.rule == null && it.certainty == Certainty.UNVERIFIED && it.reason.contains("Mosaic helper parameter transfer")
      },
    )
    assertTrue(report("escapeBoundary").findings.any { it.certainty == Certainty.UNVERIFIED })
    assertEquals(MosaicRule.MOSAIC_RECURSIVE_MULTITILE, report("keyRecursion").findings.single().rule)
  }

  @Test
  fun `declaration class object and file suppressions survive export`() {
    for (root in listOf("propertySuppressed", "fileSuppressed")) {
      val report = report(root)
      val finding = report.findings.single()
      assertEquals(MosaicRule.MOSAIC_RECURSIVE_MULTITILE, finding.rule, report.toString())
      assertTrue(finding.suppressedAt.isNotEmpty(), report.toString())
      assertTrue(report.policyDecision.passed, report.toString())
    }
    // Class/object scopes attach to Tile templates declared inside them. They cannot
    // suppress a separate top-level participant reached by a class method.
    val scopedTemplates = module.tiles.filter { it.id.contains("Group.local") || it.id.contains("Catalog.local") }
    assertEquals(2, scopedTemplates.size)
    val localTemplates = module.tiles.filter { it.id.contains("localAnnotated") || it.id.contains("localDelegated") }
    assertEquals(2, localTemplates.size)
    assertTrue(localTemplates.all { it.suppressions.isNotEmpty() })
    assertTrue(scopedTemplates.all { it.suppressions.isNotEmpty() })
    assertFalse(report("classSuppressed").policyDecision.passed)
    assertTrue(report("objectSuppressed").findings.any { it.certainty == Certainty.UNVERIFIED })
    val hard = report("unsuppressible")
    assertFalse(hard.policyDecision.passed)
    assertTrue(hard.findings.single().suppressedAt.isEmpty())
    assertTrue(module.tiles.single { it.id == "scoped.Suppressed" }.suppressions.isNotEmpty())
  }

  @Test
  fun `selected binary Tile cycle and suppressions agree with source`() {
    val producer = module
    val jar = File(directory, "producer.jar")
    jarClasses(File(directory, "producer/classes"), jar, File(directory, "producer/summary.json"))
    val source =
      File(directory, "Consumer.kt").apply {
        writeText(
          """
          package consumer
          import scoped.*
          import org.buildmosaic.core.injection.*
          suspend fun hard() = canvas {}.withMosaic { compose(A) }
          suspend fun suppressed() = canvas {}.withMosaic { compose(Suppressed, 1) }
          """.trimIndent(),
        )
      }
    val consumer = compileAndExtract(listOf(source), File(directory, "consumer"), "consumer", jar)
    for ((root, expected) in listOf("hard" to "hard", "suppressed" to "propertySuppressed")) {
      val report =
        MosaicAnalyzer().analyze(
          AnalysisRequest(consumer, listOf(producer), listOf(SelectedRoot(root, "consumer.$root()"))),
        )
      assertEquals(report(expected).findings.single().rule, report.findings.single().rule)
      assertEquals(report(expected).policyDecision.passed, report.policyDecision.passed)
      assertEquals(report(expected).findings.single().suppressedAt, report.findings.single().suppressedAt)
    }
  }
}

private val scopedSource =
  """
  package scoped
  import org.buildmosaic.core.*
  import org.buildmosaic.core.injection.*
  interface Client
  class Service : Client
  val OrderKey = CanvasKey(String::class, "order")
  val ClientTile by singleTile { source<Client>("primary"); 1 }
  val Ready by singleTile { source<Service>(); 1 }
  val Qualified by singleTile { source<Service>("primary"); 1 }
  val A: Tile<Int> by singleTile { val current = this; current.compose(A) }
  fun stop() = false
  val Guarded: Tile<Int> by singleTile { if (stop()) return@singleTile 1; compose(Guarded) }
  val Fresh: Tile<Int> by singleTile { canvas.create().compose(Fresh) }
  val Recursive: MultiTile<Int, Int> by perKeyTile { key -> compose(Recursive, key - 1) }
  @Suppress("MOSAIC_RECURSIVE_MULTITILE")
  val Suppressed: MultiTile<Int, Int> by perKeyTile { key -> compose(Suppressed, key - 1) }
  @Suppress("MOSAIC_CYCLIC_TILE_DEPENDENCY")
  val Hard: Tile<Int> by singleTile { compose(Hard) }
  @Suppress("MOSAIC_RECURSIVE_MULTITILE")
  class Group {
    fun local() { val tile = perKeyTile<Int, Int> { it }; }
    suspend fun entry() = canvas {}.withMosaic { compose(GroupTile, 1) }
  }
  @Suppress("MOSAIC_RECURSIVE_MULTITILE")
  object Catalog {
    fun local() { val tile = perKeyTile<Int, Int> { it }; }
    val ObjectTile: MultiTile<Int, Int> by perKeyTile { key -> compose(ObjectTile, key) }
  }
  val GroupTile: MultiTile<Int, Int> by perKeyTile { key -> compose(GroupTile, key) }
  fun localAnnotated() {
    @Suppress("MOSAIC_RECURSIVE_MULTITILE")
    val tile = perKeyTile<Int, Int> { it }
  }
  fun localDelegated() {
    @Suppress("MOSAIC_RECURSIVE_MULTITILE")
    val tile by perKeyTile<Int, Int> { it }
  }
  suspend fun direct() = canvas { instance(Service()) }.withMosaic { val m = this; m.compose(Ready) }
  suspend fun qualified() = canvas { instance<Client>("primary", Service()) }.withMosaic { compose(ClientTile) }
  suspend fun inferredQualified() = canvas { instance("primary", Service()) }.withMosaic { compose(Qualified) }
  suspend fun keyed() = canvas { val k = CanvasKey(Service::class, "primary"); instance(k, Service()) }.withMosaic { compose(Qualified) }
  suspend fun strings() = canvas { val orderId = "order-1"; instance(OrderKey, orderId); instance("primary", "value"); instance("plain") }.withMosaic { source(OrderKey); source<String>("primary"); source<String>() }
  suspend fun aliased() { val s = Service(); canvas { val b = this; b.instance(s) }.withMosaic { compose(Ready) } }
  suspend fun child() = canvas {}.withLayer { instance(Service()) }.withMosaic { compose(Ready) }
  suspend fun optional() = canvas {}.withMosaic { sourceOr<Service>(); 1 }
  fun existing(parent: Canvas): Service { parent.source<Service>(); return Service() }
  suspend fun registerBeforeProvider(): Canvas { val p = canvas {}; return canvas { single<Service> { Service() }; instance("primary", existing(p)) } }
  suspend fun registrationOrder() = registerBeforeProvider().withMosaic { compose(Qualified) }
  fun readQualifier(parent: Canvas): String { parent.source(String::class, "qualifier"); return "primary" }
  fun readKey(parent: Canvas): CanvasKey<String> { parent.source(String::class, "key"); return OrderKey }
  fun readValue(parent: Canvas): String { parent.source(String::class, "value"); return "value" }
  suspend fun qualifiedArgumentOrder(parent: Canvas) = canvas { instance(readQualifier(parent), readValue(parent)) }
  suspend fun keyedArgumentOrder(parent: Canvas) = canvas { instance(readKey(parent), readValue(parent)) }
  fun qualifier() = "primary"
  suspend fun dynamicInstance() = canvas { instance(qualifier(), Service()) }.withMosaic { compose(Qualified) }
  suspend fun duplicateInstance() = canvas { instance(Service()); instance(Service()) }.withMosaic { compose(Ready) }
  suspend fun hard() = canvas {}.withMosaic { compose(A) }
  suspend fun guarded() = canvas {}.withMosaic { compose(Guarded) }
  suspend fun fresh() = canvas {}.withMosaic { compose(Fresh) }
  suspend fun handler(m: Mosaic) = m.compose(A)
  suspend fun helperBoundary() = canvas {}.withMosaic { handler(this) }
  suspend fun escapeBoundary() { val m = canvas {}.withMosaic { this }; m.compose(A) }
  suspend fun keyRecursion() = canvas {}.withMosaic { compose(Recursive, 1) }
  suspend fun propertySuppressed() = canvas {}.withMosaic { compose(Suppressed, 1) }
  suspend fun classSuppressed() = Group().entry()
  suspend fun objectSuppressed() = canvas {}.withMosaic { compose(Catalog.ObjectTile, 1) }
  suspend fun unsuppressible() = canvas {}.withMosaic { compose(Hard) }
  """.trimIndent()

private val fileSource =
  """
  @file:Suppress("MOSAIC_RECURSIVE_MULTITILE")
  package scoped
  import org.buildmosaic.core.*
  import org.buildmosaic.core.injection.*
  val FileTile: MultiTile<Int, Int> by perKeyTile { key -> compose(FileTile, key) }
  suspend fun fileSuppressed() = canvas {}.withMosaic { compose(FileTile, 1) }
  """.trimIndent()

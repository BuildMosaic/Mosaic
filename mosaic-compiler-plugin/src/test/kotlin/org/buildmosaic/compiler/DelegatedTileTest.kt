@file:Suppress("MaxLineLength", "ktlint:standard:max-line-length")

package org.buildmosaic.compiler

import org.buildmosaic.analysis.AnalysisPolicy
import org.buildmosaic.analysis.AnalysisRequest
import org.buildmosaic.analysis.Certainty
import org.buildmosaic.analysis.Effect
import org.buildmosaic.analysis.LookupKind
import org.buildmosaic.analysis.ModuleContract
import org.buildmosaic.analysis.MosaicAnalyzer
import org.buildmosaic.analysis.RootStatus
import org.buildmosaic.analysis.SelectedRoot
import org.buildmosaic.analysis.SummaryCodec
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DelegatedTileTest {
  @Test
  fun `factory delegation matches ordinary declarations`() {
    val directory = Files.createTempDirectory("mosaic-delegated-tiles").toFile()
    listOf("=", "by").forEach { declaration ->
      val source = File(directory, "Tiles.kt").apply { writeText(tileSource("regression", declaration)) }
      val probe = File(directory, "facts.txt")
      compile(source, File(directory, "classes"), output = probe, moduleId = "regression")
      val summary = SummaryCodec.decode(File(directory, "summary.json").readBytes())
      assertFactories(summary.module, "regression")
      assertRoots(summary.module)
      listOf("Single", "Batch", "PerKey", "Chunked").forEach { name ->
        assertTrue(summary.binaryLocators.containsKey("regression.$name"))
      }
    }
  }

  @Test
  fun `binary delegation requires stable exports`() {
    val directory = Files.createTempDirectory("mosaic-binary-delegated-tiles").toFile()
    val producerSource = File(directory, "Tiles.kt").apply { writeText(tileSource("producer", "by")) }
    val producerDirectory = File(directory, "producer")
    val producer = compileAndExtract(listOf(producerSource), producerDirectory, "producer")
    val jar = File(directory, "producer.jar")
    jarClasses(File(producerDirectory, "classes"), jar, File(producerDirectory, "summary.json"))
    val source = File(directory, "Entry.kt").apply { writeText(roots("regression", "import producer.*")) }
    val consumer = compileAndExtract(listOf(source), File(directory, "consumer"), "regression", jar)
    assertFactories(producer, "producer")
    assertRoots(consumer, listOf(producer))
    val missingExport = report(consumer, "singleMissing", listOf(producer.copy(tiles = emptyList())))
    assertTrue(
      missingExport.findings.any {
        it.certainty == Certainty.UNVERIFIED && it.reason.contains("exported stable Tile contract")
      },
      missingExport.toString(),
    )
    assertTrue(report(consumer, "singleMissing").findings.any { it.certainty == Certainty.UNVERIFIED })
  }

  @Test
  fun `local identities and conservative boundaries`() {
    val directory = Files.createTempDirectory("mosaic-local-delegated-tiles").toFile()
    val source = File(directory, "Local.kt").apply { writeText(localDelegationSource()) }
    val probe = File(directory, "facts.txt")
    compile(source, File(directory, "classes"), output = probe, moduleId = "regression")
    val module = SummaryCodec.decode(File(directory, "summary.json").readBytes()).module
    assertEquals(RootStatus.VERIFIED, report(module, "localSupplied").roots.single().status)
    assertTrue(report(module, "localMissing").findings.any { it.certainty == Certainty.MISSING }, module.toString())
    val calls = module.callables.single { it.id == "regression.localMissing()" }.effects.filterIsInstance<Effect.Compose>()
    assertEquals(3, calls.size)
    assertTrue(calls.all { it.tile == calls.first().tile })
    listOf("arbitrary", "foreign", "referenceDelegate", "member", "captured", "manual").forEach { root ->
      assertEquals(RootStatus.UNVERIFIED, report(module, root).roots.single().status, report(module, root).toString())
    }
    assertTrue(
      module.tiles.none {
        it.id in setOf("regression.Arbitrary", "regression.Foreign", "regression.Holder.Member")
      },
    )
  }
}

private fun tileSource(
  packageName: String,
  declaration: String,
): String =
  """
  package $packageName
  import org.buildmosaic.core.*
  import org.buildmosaic.core.injection.*
  class Required
  val Single $declaration singleTile { source<Required>() }
  val Batch $declaration multiTile<String, Required> { source<Required>(); emptyMap() }
  val PerKey $declaration perKeyTile<String, Required> { source<Required>() }
  val Chunked $declaration chunkedMultiTile<String, Required>(2) { source<Required>(); emptyMap() }
  """.trimIndent() + "\n" + roots(packageName)

private fun roots(
  packageName: String,
  imports: String = "",
): String =
  """
  ${if (imports.isNotEmpty()) "package $packageName\nimport org.buildmosaic.core.*\nimport org.buildmosaic.core.injection.*\n$imports" else ""}
  suspend fun singleMissing() = canvas {}.withMosaic { compose(Single) }
  suspend fun batchMissing() = canvas {}.withMosaic { compose(Batch, "key") }
  suspend fun perKeyMissing() = canvas {}.withMosaic { compose(PerKey, "key") }
  suspend fun chunkedMissing() = canvas {}.withMosaic { compose(Chunked, "key") }
  suspend fun supplied() {
    canvas { provide<Required> { Required() } }.withMosaic {
      val mosaic = this
      mosaic.compose(Single)
      mosaic.compose(Batch, "key")
      mosaic.compose(PerKey, "key")
      mosaic.compose(Chunked, "key")
    }
  }
  suspend fun emptyBatch() = canvas {}.withMosaic { compose(Batch, emptyList<String>()) }
  suspend fun metadata() { Single.name; Batch.name }
  """.trimIndent()

private fun assertFactories(
  module: ModuleContract,
  packageName: String,
) {
  listOf("Single", "Batch", "PerKey", "Chunked").forEach { name ->
    val tile = module.tiles.single { it.id == "$packageName.$name" }
    assertEquals(name != "Single", tile.multi)
    assertTrue(tile.effects.any { it is Effect.Lookup && it.kind == LookupKind.REQUIRED }, tile.toString())
  }
}

private fun assertRoots(
  module: ModuleContract,
  dependencies: List<ModuleContract> = emptyList(),
) {
  listOf("singleMissing", "batchMissing", "perKeyMissing", "chunkedMissing").forEach { root ->
    val result = report(module, root, dependencies)
    assertTrue(result.findings.any { it.certainty == Certainty.MISSING }, result.toString())
    assertTrue(result.findings.none { it.certainty == Certainty.UNVERIFIED }, result.toString())
  }
  listOf("supplied", "emptyBatch", "metadata").forEach { root ->
    val result = report(module, root, dependencies)
    assertEquals(RootStatus.VERIFIED, result.roots.single().status, result.toString())
  }
}

private fun report(
  module: ModuleContract,
  root: String,
  dependencies: List<ModuleContract> = emptyList(),
) = MosaicAnalyzer().analyze(
  AnalysisRequest(
    module,
    dependencies,
    listOf(SelectedRoot(root, "regression.$root()")),
    policy = AnalysisPolicy.STRICT,
  ),
)

private fun localDelegationSource(): String =
  """
  package regression
  import org.buildmosaic.core.*
  import org.buildmosaic.core.injection.*
  import kotlin.reflect.KProperty
  class Required
  suspend fun localMissing() {
    val tile by singleTile { source<Required>() }
    val alias by tile
    val ordinaryAlias = alias
    canvas {}.withMosaic {
      val mosaic = this
      mosaic.compose(tile)
      mosaic.compose(alias)
      mosaic.compose(ordinaryAlias)
    }
  }
  suspend fun localSupplied() {
    val batch by multiTile<String, Required> { source<Required>(); emptyMap() }
    val perKey by perKeyTile<String, Required> { source<Required>() }
    val chunked by chunkedMultiTile<String, Required>(2) { source<Required>(); emptyMap() }
    canvas { provide<Required> { Required() } }.withMosaic {
      val mosaic = this
      mosaic.compose(batch, "key")
      mosaic.compose(perKey, "key")
      mosaic.compose(chunked, "key")
    }
  }
  suspend fun captured() {
    val dependency by singleTile { source<Required>() }
    val wrapper by singleTile { compose(dependency) }
    canvas {}.withMosaic { compose(wrapper) }
  }
  suspend fun manual(property: KProperty<*>) {
    val tile = singleTile { source<Required>() }.provideDelegate(null, property)
    canvas {}.withMosaic { compose(tile) }
  }
  val ReferenceTarget = singleTile { source<Required>() }
  suspend fun referenceDelegate() {
    val reference by ::ReferenceTarget
    canvas {}.withMosaic { compose(reference) }
  }
  val Arbitrary by lazy { singleTile { source<Required>() } }
  class ForeignDelegate {
    operator fun provideDelegate(thisRef: Any?, property: KProperty<*>) = this
    operator fun getValue(thisRef: Any?, property: KProperty<*>) = singleTile { source<Required>() }
  }
  val Foreign by ForeignDelegate()
  class Holder(val required: Required) { val Member by singleTile { required } }
  suspend fun arbitrary() = canvas {}.withMosaic { compose(Arbitrary) }
  suspend fun foreign() = canvas {}.withMosaic { compose(Foreign) }
  suspend fun member() = canvas {}.withMosaic { compose(Holder(Required()).Member) }
  """.trimIndent()

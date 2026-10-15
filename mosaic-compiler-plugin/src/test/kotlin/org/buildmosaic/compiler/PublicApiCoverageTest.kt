@file:Suppress("FunctionMaxLength", "LongMethod", "MaxLineLength", "ktlint:standard:max-line-length")

package org.buildmosaic.compiler

import org.buildmosaic.analysis.AnalysisPolicy
import org.buildmosaic.analysis.AnalysisRequest
import org.buildmosaic.analysis.Certainty
import org.buildmosaic.analysis.MosaicAnalyzer
import org.buildmosaic.analysis.RootStatus
import org.buildmosaic.analysis.SelectedRoot
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** One K2 compilation and independently selected public-API roots. */
class PublicApiCoverageTest {
  private companion object {
    val module by lazy {
      val directory = Files.createTempDirectory("mosaic-public-api").toFile()
      val keys = File(directory, "Keys.kt")
      keys.writeText(
        requireNotNull(PublicApiCoverageTest::class.java.getResource("/fixtures/public-api-keys.kt")).readText(),
      )
      val roots = File(directory, "Roots.kt")
      roots.writeText(
        requireNotNull(PublicApiCoverageTest::class.java.getResource("/fixtures/public-api-roots.kt")).readText(),
      )
      compileAndExtract(listOf(keys, roots), directory, "publicapi")
    }
  }

  private fun report(name: String) =
    MosaicAnalyzer().analyze(
      AnalysisRequest(
        module,
        roots =
          listOf(
            SelectedRoot(
              name,
              "publicapi.${if (name.startsWith("dynamicType")) {
                "dynamicType(kotlin.reflect.KClass)"
              } else if (name in setOf("dynamicKey", "dynamicOptionalKey", "dynamicRegistrationKey", "dynamicConstructionKey", "dynamicKeyCapture")) {
                "$name(org.buildmosaic.core.injection.CanvasKey)"
              } else if (name.startsWith("dynamicQualifier") || name.startsWith("dynamicRegistration") || name == "dynamicConstructedKeyCapture") {
                "$name(kotlin.String)"
              } else {
                "$name()"
              }}",
            ),
          ),
        policy = AnalysisPolicy.STRICT,
      ),
    )

  private fun missing(name: String) {
    val report = report(name)
    assertTrue(
      report.findings.any {
        it.certainty == Certainty.MISSING && it.key?.classId == "publicapi.Metrics"
      },
      "$name: $report",
    )
  }

  private fun exactMissing(
    name: String,
    classId: String = "publicapi.Metrics",
  ) {
    val result = report(name)
    assertTrue(
      result.findings.any { it.certainty == Certainty.MISSING && it.key?.classId == classId },
      "$name: $result",
    )
    assertTrue(result.findings.none { it.certainty == Certainty.UNVERIFIED }, "$name: $result")
  }

  private fun exactNamedMissing(name: String) {
    val result = report(name)
    assertTrue(
      result.findings.any {
        it.certainty == Certainty.MISSING && it.key?.classId == "publicapi.Metrics" && it.key?.qualifier == "named"
      },
      "$name: $result",
    )
    assertTrue(result.findings.none { it.certainty == Certainty.UNVERIFIED }, "$name: $result")
  }

  private fun verified(name: String) {
    val report = report(name)
    assertEquals(RootStatus.VERIFIED, report.roots.single().status, "$name: $report")
  }

  private fun unknown(name: String) {
    val report = report(name)
    assertTrue(report.findings.any { it.certainty == Certainty.UNVERIFIED }, "$name: $report")
    assertTrue(report.findings.none { it.certainty == Certainty.MISSING }, "$name: $report")
  }

  @Test fun `lookup forms share exact key identity`() {
    listOf(
      "qualifiedReifiedMissing",
      "kclassMissing",
      "kclassAliasMissing",
      "keyMissing",
      "aliasMissing",
      "crossFileMissing",
      "emptyDistinct",
      "nullDistinct",
      "mosaicKey",
    ).forEach(::missing)
    listOf("optionalKclass", "optionalKey", "optionalReified", "qualifiedOptionalReified", "qualifiedReified", "qualifiedExplicit", "keyReuse", "emptyMatched", "nullMatched", "mosaicOptionalKey").forEach(
      ::verified,
    )
  }

  @Test fun `Mosaic canvas and layer construction preserve ownership`() {
    listOf(
      "mosaicPropertyMissing",
      "directPropertyMissing",
      "currentMosaicMissing",
      "parentWorkFirst",
    ).forEach(::missing)
    listOf(
      "mosaicPropertySupplied",
      "currentMosaicSupplied",
      "constructionExplicit",
      "constructionReified",
      "parentNamed",
      "parentPositional",
      "layerFallback",
    ).forEach(::verified)
    val both = report("bothLookups").findings.filter { it.key?.classId == "publicapi.Metrics" }
    assertEquals(2, both.size)
    assertEquals(1, both.map { it.bindingSite }.distinct().size)
  }

  @Test fun `compose families preserve execution distinctions`() {
    listOf("composeSync", "composeAsync", "manySingle", "manySingleAsync", "manyNonempty", "manyNonemptyAsync", "perKeySingle", "chunkSingle", "directTile", "directMultiTile").forEach(
      ::missing,
    )
    listOf("manyEmpty", "manyEmptyAsync").forEach(::verified)
  }

  @Test fun `dynamic keys and qualifiers stay named unknown`() {
    listOf(
      "dynamicType",
      "dynamicKey",
      "dynamicOptionalKey",
      "dynamicQualifier",
      "dynamicRegistration",
      "dynamicRegistrationKey",
      "dynamicConstructionKey",
      "dynamicKeyCapture",
      "dynamicConstructedKeyCapture",
    ).forEach(::unknown)
    listOf("dynamicKeyCapture", "dynamicConstructedKeyCapture").forEach { name ->
      assertTrue(report(name).findings.any { it.reason.contains("capture", ignoreCase = true) })
    }
  }

  @Test fun `fresh Tile factories retain deferred bodies and allocation aliases`() {
    listOf(
      "localSingle",
      "directFresh",
      "localDirectConstructor",
      "localMulti",
      "localTileAlias",
      "localPerKey",
      "localChunked",
      "localScalarCapture",
    ).forEach(::exactMissing)
    listOf(
      "localKnownKeyCapture",
      "localKeyAliasCapture",
      "localTypeQualifierCapture",
      "localExportedKeyTile",
    ).forEach(::exactNamedMissing)
    exactMissing("localChunkedCreationWork", "kotlin.Int")
    verified("localMultiEmpty")
    verified("localCapturedMultiEmpty")
    unknown("localCanvasCapture")
    assertTrue(report("localCanvasCapture").findings.any { it.reason.contains("capture", ignoreCase = true) })
    val separate = report("twoLocalTilesAsync").findings.filter { it.key?.classId == "publicapi.Metrics" }
    assertEquals(2, separate.size)
    assertEquals(2, separate.map { it.dependencyPath.last().label }.toSet().size)
  }
}

class BinaryCanvasKeyExportTest {
  @Test fun `binary immutable key resolves only through producer export`() {
    val directory = Files.createTempDirectory("mosaic-binary-key").toFile()
    val producer = File(directory, "Export.kt")
    producer.writeText(
      """
      package producer
      import org.buildmosaic.core.injection.*
      class Metrics
      val SharedKey = CanvasKey(Metrics::class, "named")
      """.trimIndent(),
    )
    val producerDirectory = File(directory, "producer")
    val producerModule = compileAndExtract(listOf(producer), producerDirectory, "producer")
    val jar = File(directory, "producer.jar")
    jarClasses(File(producerDirectory, "classes"), jar, File(producerDirectory, "summary.json"))
    val consumer = File(directory, "Consumer.kt")
    consumer.writeText(
      """
      package consumer
      import producer.*
      import org.buildmosaic.core.*
      import org.buildmosaic.core.injection.*
      suspend fun entry() {
        val c = canvas {
          provide(SharedKey) { Metrics() }
          provide<String> { source(SharedKey); "ok" }
        }
        c.source(SharedKey)
        val tile = singleTile { source(SharedKey); "ok" }
        c.withMosaic { compose(tile) }
      }
      """.trimIndent(),
    )
    val consumerModule = compileAndExtract(listOf(consumer), File(directory, "consumer"), "consumer", jar)

    fun analyze(dependencies: List<org.buildmosaic.analysis.ModuleContract>) =
      MosaicAnalyzer().analyze(
        AnalysisRequest(
          consumerModule,
          selectedDependencies = dependencies,
          roots = listOf(SelectedRoot("entry", "consumer.entry()")),
          policy = AnalysisPolicy.STRICT,
        ),
      )
    val withExport = analyze(listOf(producerModule))
    assertEquals(RootStatus.VERIFIED, withExport.roots.single().status, withExport.toString())
    val withoutExport = analyze(emptyList())
    assertTrue(
      withoutExport.findings.any {
        it.certainty == Certainty.UNVERIFIED && it.reason.contains("exported stable CanvasKey")
      },
      withoutExport.toString(),
    )
  }
}

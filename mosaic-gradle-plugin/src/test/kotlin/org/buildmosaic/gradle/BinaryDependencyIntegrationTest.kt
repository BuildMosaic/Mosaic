@file:Suppress(
  "LargeClass",
  "LongMethod",
  "FunctionMaxLength",
  "NestedBlockDepth",
  "MaxLineLength",
  "ktlint:standard:max-line-length",
)

package org.buildmosaic.gradle

import org.buildmosaic.analysis.AnalysisPolicy
import org.buildmosaic.analysis.AnalysisRequest
import org.buildmosaic.analysis.CanvasExpression
import org.buildmosaic.analysis.Certainty
import org.buildmosaic.analysis.MosaicAnalyzer
import org.buildmosaic.analysis.RootSelectionResolver
import org.buildmosaic.analysis.RootStatus
import org.buildmosaic.analysis.SUMMARY_PATH
import org.buildmosaic.analysis.SelectedRoot
import org.buildmosaic.analysis.SummaryCodec
import org.gradle.testkit.runner.TaskOutcome
import java.io.File
import java.nio.file.Files
import java.util.jar.JarFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class BinaryDependencyIntegrationTest {
  @Test
  fun `separate binary artifacts verify and body only platform change invalidates application`() {
    val root = Files.createTempDirectory("mosaic-binary-integration").toFile()
    val repository = File(System.getProperty("user.dir")).parentFile
    val pluginJar = File(repository, "mosaic-compiler-plugin/build/libs/mosaic-compiler-plugin-${mosaicVersion()}.jar")
    val coreJar = File(repository, "mosaic-core/build/libs/mosaic-core-${mosaicVersion()}.jar")
    assertTrue(pluginJar.isFile, pluginJar.absolutePath)
    assertTrue(coreJar.isFile, coreJar.absolutePath)
    val platform = project(root, "platform", pluginJar, listOf(coreJar), role = "LIBRARY")
    val platformSource = File(platform, "src/main/kotlin/Platform.kt")
    val originalPlatform =
      """
      package platform
      import org.buildmosaic.core.injection.*
      class GlobalContext { init { error("platform initialization executed") } }
      class Metrics
      class PlatformConfig
      suspend fun platformCanvas(): Canvas = canvas {
        provide<GlobalContext> { GlobalContext() }
        provide<Metrics> { Metrics() }
        provide<PlatformConfig> { PlatformConfig() }
      }
      abstract class PlatformComponent {
        suspend fun handle(requestId: String): String = respond(platformCanvas(), requestId)
        protected abstract suspend fun respond(base: Canvas, requestId: String): String
      }
      abstract class SlotComponent {
        suspend fun slotHandle(): String = slotRespond(platformCanvas(), "marker", canvas { })
        protected abstract suspend fun slotRespond(first: Canvas, marker: String, second: Canvas): String
      }
      """.trimIndent()
    platformSource.apply {
      parentFile.mkdirs()
      writeText(originalPlatform)
    }
    val platformBuild = run(platform, "build")
    val platformJar = jar(platform)
    assertEquals(TaskOutcome.SUCCESS, platformBuild.task(":extractMosaicMain")?.outcome)
    assertEquals(TaskOutcome.SUCCESS, platformBuild.task(":verifyMosaicMain")?.outcome)
    assertTrue(File(platform, "build/reports/mosaic-analysis/main.txt").readText().contains("EXPORT_ONLY"))
    val firstPlatformHash = sha256(platformJar)
    val firstPlatformSummaryHash = summaryHash(platformJar)
    JarFile(platformJar).use { assertTrue(it.getJarEntry(SUMMARY_PATH) != null) }
    val adapter = project(root, "adapter", pluginJar, listOf(coreJar, platformJar))
    File(adapter, "src/main/kotlin/Adapter.kt").apply {
      parentFile.mkdirs()
      writeText(
        """
        package adapter
        import org.buildmosaic.core.injection.Canvas
        import platform.platformCanvas
        suspend fun applicationBase(): Canvas = platformCanvas()
        """.trimIndent(),
      )
    }
    run(adapter, "jar")
    val adapterJar = jar(adapter)
    val adapterHash = sha256(adapterJar)
    JarFile(adapterJar).use { archive ->
      val summary = SummaryCodec.decode(archive.getInputStream(archive.getJarEntry(SUMMARY_PATH)).readBytes())
      val result = summary.module.canvases.single { it.id.startsWith("adapter.applicationBase") }.result
      assertTrue(result is CanvasExpression.WithEffects)
      val initialization = result.effects.filterIsInstance<org.buildmosaic.analysis.Effect.ConstructCanvas>().single()
      val reference = (initialization.canvas as CanvasExpression.Alias).expression
      assertTrue(reference is CanvasExpression.RuntimeCall)
      assertTrue(reference.target.startsWith("platform.platformCanvas"))
    }

    val tiles = project(root, "tiles", pluginJar, listOf(coreJar, platformJar))
    File(tiles, "src/main/kotlin/Tiles.kt").apply {
      parentFile.mkdirs()
      writeText(
        """
        package tilelib
        import org.buildmosaic.core.*
        import platform.*
        class Service(val metrics: Metrics)
        class RequestContext(val id: String)
        val EnterpriseTile = singleTile {
          source<GlobalContext>()
          source<Metrics>()
          source<PlatformConfig>()
          source<Service>()
          source<RequestContext>().id
        }
        val MetricsOnlyTile = singleTile { source<Metrics>(); "ok" }
        """.trimIndent(),
      )
    }
    run(tiles, "jar")
    val tileJar = jar(tiles)

    val app =
      project(
        root,
        "app",
        pluginJar,
        listOf(coreJar, platformJar, adapterJar, tileJar),
        enforcement = "STRICT",
      )
    val appBuildFile = File(app, "build.gradle.kts")
    val strictBuildText = appBuildFile.readText()
    val explicitBuildText =
      strictBuildText + "\n" +
        """
        mosaicAnalysis {
          roots.add("app.entry()")
          roots.add("app.adapterEntry()")
          roots.add("app.slotEntry()")
        }
        """.trimIndent()
    File(app, "src/main/kotlin/App.kt").apply {
      parentFile.mkdirs()
      writeText(
        """
        package app
        import org.buildmosaic.core.*
        import org.buildmosaic.core.injection.*
        import platform.*
        import tilelib.*
        import adapter.applicationBase
        suspend fun applicationLayer(parent: Canvas): Canvas = parent.withLayer {
          provide<Service> { Service(source<Metrics>()) }
        }
        class ApplicationComponent : PlatformComponent() {
          override suspend fun respond(base: Canvas, requestId: String): String {
            val application = applicationLayer(base)
            val request = application.withLayer { provide<RequestContext> { RequestContext(requestId) } }
            return request.withMosaic { compose(EnterpriseTile) }
          }
        }
        class FrameworkApplication : PlatformComponent() {
          override suspend fun respond(base: Canvas, requestId: String): String {
            val application = applicationLayer(base)
            val request = application.withLayer { provide<RequestContext> { RequestContext(requestId) } }
            return request.withMosaic { compose(EnterpriseTile) }
          }
        }
        class SlotApplication : SlotComponent() {
          override suspend fun slotRespond(second: Canvas, marker: String, first: Canvas): String =
            second.withMosaic { compose(MetricsOnlyTile) }
        }
        suspend fun entry(): String = ApplicationComponent().handle("r")
        suspend fun slotEntry(): String = SlotApplication().slotHandle()
        suspend fun adapterEntry(): String {
          val application = applicationLayer(applicationBase())
          val request = application.withLayer { provide<RequestContext> { RequestContext("r") } }
          return request.withMosaic { compose(EnterpriseTile) }
        }
        """.trimIndent(),
      )
    }
    val initial = run(app, "build")
    val initialReport = File(app, "build/reports/mosaic-analysis/main.txt").readText()
    val initialSummaryBytes = File(app, "build/mosaic-analysis/main/summary.json").readBytes()
    assertEquals(TaskOutcome.SUCCESS, initial.task(":verifyMosaicMain")?.outcome)
    assertTrue(initialReport.contains("Root selection: AUTOMATIC"), initialReport)
    assertTrue(initialReport.contains("Root app.entry(): VERIFIED"), initialReport)
    assertTrue(initialReport.contains("Root app.adapterEntry(): VERIFIED"), initialReport)
    assertTrue(initialReport.contains("Root app.slotEntry(): VERIFIED"), initialReport)
    assertTrue(
      initialReport.contains("@ app.FrameworkApplication: VERIFIED; receiver app.FrameworkApplication"),
      initialReport,
    )
    assertTrue(initialReport.contains("CONSTRUCTION_LOOKUP"), initialReport)
    assertFalse(initialReport.contains("Dependency metadata:"), initialReport)
    listOf(
      "platform.GlobalContext",
      "platform.Metrics",
      "platform.PlatformConfig",
      "tilelib.Service",
      "tilelib.RequestContext",
    ).forEach { type ->
      assertTrue(initialReport.contains("VERIFIED REQUIRED_LOOKUP CanvasKeyIdentity(classId=$type,"), initialReport)
    }
    val appSummary =
      JarFile(jar(app)).use {
        SummaryCodec.decode(it.getInputStream(it.getJarEntry(SUMMARY_PATH)).readBytes()).module
      }
    val selectedDependencies =
      listOf(platformJar, adapterJar, tileJar).map { dependency ->
        JarFile(
          dependency,
        ).use { SummaryCodec.decode(it.getInputStream(it.getJarEntry(SUMMARY_PATH)).readBytes()).module }
      }
    val frameworkRoot =
      RootSelectionResolver.resolve(appSummary, selectedDependencies, emptyList())
        .single { it.receiverType == "app.FrameworkApplication" }
    assertTrue(frameworkRoot.target.startsWith("platform.PlatformComponent.handle("))
    val frameworkReport =
      MosaicAnalyzer().analyze(AnalysisRequest(appSummary, selectedDependencies, listOf(frameworkRoot)))
    assertEquals(RootStatus.VERIFIED, frameworkReport.roots.single().status)
    assertTrue(
      frameworkReport.findings.any {
        it.key?.classId == "platform.Metrics" && it.certainty == Certainty.VERIFIED
      },
    )
    val standaloneOverride =
      MosaicAnalyzer().analyze(
        AnalysisRequest(
          appSummary,
          selectedDependencies,
          listOf(
            SelectedRoot(
              "standalone-override",
              "app.ApplicationComponent.respond(org.buildmosaic.core.injection.Canvas,kotlin.String)",
            ),
          ),
          policy = AnalysisPolicy.STRICT,
        ),
      )
    assertEquals(RootStatus.UNVERIFIED, standaloneOverride.roots.single().status)
    platformSource.writeText(
      originalPlatform.replace("platform initialization executed", "platform initialization updated"),
    )
    run(platform, "jar")
    assertNotEquals(firstPlatformHash, sha256(platformJar))
    assertEquals(firstPlatformSummaryHash, summaryHash(platformJar))
    val implementationOnly = run(app, "build")
    assertEquals(TaskOutcome.UP_TO_DATE, implementationOnly.task(":extractMosaicMain")?.outcome)
    assertEquals(TaskOutcome.UP_TO_DATE, implementationOnly.task(":verifyMosaicMain")?.outcome)
    assertFalse(implementationOnly.output.contains("Mosaic K2 extraction launched"))
    assertTrue(initialSummaryBytes.contentEquals(File(app, "build/mosaic-analysis/main/summary.json").readBytes()))

    platformSource.writeText(
      originalPlatform
        .replace("platform initialization executed", "platform initialization updated")
        .replace("  provide<PlatformConfig> { PlatformConfig() }\n", ""),
    )
    val changedPlatform = run(platform, "jar")
    assertEquals(TaskOutcome.SUCCESS, changedPlatform.task(":extractMosaicMain")?.outcome)
    assertNotEquals(firstPlatformHash, sha256(platformJar))
    assertNotEquals(firstPlatformSummaryHash, summaryHash(platformJar))
    JarFile(platformJar).use { archive ->
      val summary = SummaryCodec.decode(archive.getInputStream(archive.getJarEntry(SUMMARY_PATH)).readBytes())
      val result =
        summary.module.canvases.single {
          it.id.startsWith("platform.platformCanvas")
        }.result as CanvasExpression.WithEffects
      val initialization = result.effects.filterIsInstance<org.buildmosaic.analysis.Effect.ConstructCanvas>().single()
      val bindings = ((initialization.canvas as CanvasExpression.Alias).expression as CanvasExpression.Layer).bindings
      assertFalse(bindings.any { it.key.toString().contains("PlatformConfig") })
    }
    assertEquals(adapterHash, sha256(adapterJar))
    val changedSummary =
      JarFile(platformJar).use { archive ->
        archive.getInputStream(archive.getJarEntry(SUMMARY_PATH)).readBytes()
      }
    val broken = run(app, "build", expectFailure = true)
    val brokenReport = File(app, "build/reports/mosaic-analysis/main.txt").readText()
    assertEquals(TaskOutcome.FAILED, broken.task(":verifyMosaicMain")?.outcome)
    assertEquals(
      TaskOutcome.UP_TO_DATE,
      broken.task(":extractMosaicMain")?.outcome,
      broken.output.lines().filter {
        it.contains("Input property") || it.contains("not up-to-date")
      }.joinToString("\n"),
    )
    assertFalse(broken.output.contains("Mosaic K2 extraction launched"))
    assertTrue(
      brokenReport.contains("MISSING REQUIRED_LOOKUP CanvasKeyIdentity(classId=platform.PlatformConfig"),
      brokenReport,
    )
    assertTrue(brokenReport.contains("Tiles.kt"), brokenReport)
    assertTrue(brokenReport.contains("platform.platformCanvas"), brokenReport)
    assertFalse(
      brokenReport.contains("VERIFIED REQUIRED_LOOKUP CanvasKeyIdentity(classId=platform.PlatformConfig"),
      brokenReport,
    )

    platformSource.writeText(originalPlatform)
    run(platform, "jar")
    val restored = run(app, "build")
    assertEquals(TaskOutcome.SUCCESS, restored.task(":verifyMosaicMain")?.outcome)
    assertEquals(TaskOutcome.UP_TO_DATE, restored.task(":extractMosaicMain")?.outcome)
    assertEquals(firstPlatformSummaryHash, summaryHash(platformJar))
    assertEquals(initialReport, File(app, "build/reports/mosaic-analysis/main.txt").readText())

    val completeBytes = platformJar.readBytes()
    run(app, "mosaicGraph")
    val originalGraph = File(app, "build/reports/mosaic-analysis/graph.md").readText()
    rewriteSummary(platformJar, changedSummary)
    val metadataOnly = run(app, "mosaicGraph verifyMosaicMain", expectFailure = true)
    assertEquals(TaskOutcome.UP_TO_DATE, metadataOnly.task(":compileKotlin")?.outcome)
    assertEquals(TaskOutcome.UP_TO_DATE, metadataOnly.task(":extractMosaicMain")?.outcome)
    assertEquals(TaskOutcome.SUCCESS, metadataOnly.task(":mosaicGraph")?.outcome)
    assertEquals(TaskOutcome.FAILED, metadataOnly.task(":verifyMosaicMain")?.outcome)
    assertNotEquals(originalGraph, File(app, "build/reports/mosaic-analysis/graph.md").readText())
    platformJar.writeBytes(completeBytes)
    appBuildFile.writeText(explicitBuildText)
    rewriteSummary(platformJar, null)
    val missingMetadata = run(app, "verifyMosaicMain", expectFailure = true)
    val missingReport = File(app, "build/reports/mosaic-analysis/main.txt").readText()
    assertEquals(TaskOutcome.FAILED, missingMetadata.task(":verifyMosaicMain")?.outcome)
    assertEquals(TaskOutcome.UP_TO_DATE, missingMetadata.task(":extractMosaicMain")?.outcome)
    assertTrue(missingReport.contains("UNVERIFIED"), missingReport)
    assertFalse(
      missingReport.contains("MISSING REQUIRED_LOOKUP CanvasKeyIdentity(classId=platform.PlatformConfig"),
      missingReport,
    )
    appBuildFile.writeText(
      explicitBuildText.replace(
        "enforcement = org.buildmosaic.gradle.MosaicAnalysisEnforcement.STRICT",
        "enforcement = org.buildmosaic.gradle.MosaicAnalysisEnforcement.STANDARD",
      ),
    )
    val standardUnknown = run(app, "verifyMosaicMain")
    val standardReport = File(app, "build/reports/mosaic-analysis/main.txt").readText()
    assertEquals(TaskOutcome.SUCCESS, standardUnknown.task(":verifyMosaicMain")?.outcome)
    assertTrue(standardUnknown.output.contains("Mosaic verification passed with warnings"), standardUnknown.output)
    assertTrue(standardReport.contains("PASSED WITH WARNINGS"), standardReport)
    assertTrue(standardReport.contains("UNVERIFIED"), standardReport)
    appBuildFile.writeText(explicitBuildText)
    rewriteSummary(platformJar, "{malformed".toByteArray())
    val malformed = run(app, "verifyMosaicMain", expectFailure = true)
    assertEquals(TaskOutcome.FAILED, malformed.task(":extractMosaicMain")?.outcome)
    assertEquals(TaskOutcome.UP_TO_DATE, malformed.task(":compileKotlin")?.outcome)
    assertEquals(null, malformed.task(":verifyMosaicMain")?.outcome)
    assertTrue(malformed.output.contains("invalid Mosaic summary"), malformed.output)
    platformJar.writeBytes(completeBytes)
  }
}

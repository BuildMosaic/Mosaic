@file:Suppress("LongMethod", "FunctionMaxLength", "MaxLineLength", "ktlint:standard:max-line-length")

package org.buildmosaic.gradle

import org.gradle.testkit.runner.TaskOutcome
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RuleConfigurationIntegrationTest {
  private val repository = File(System.getProperty("user.dir")).parentFile
  private val compiler =
    File(repository, "mosaic-compiler-plugin/build/libs/mosaic-compiler-plugin-${mosaicVersion()}.jar")
  private val core = File(repository, "mosaic-core/build/libs/mosaic-core-${mosaicVersion()}.jar")

  @Test
  fun `default errors graph visibility and named severities invalidate verification independently`() {
    val root = Files.createTempDirectory("mosaic-rules").toFile()
    val app = project(root, "app", compiler, listOf(core), enforcement = "STRICT")
    source(
      app,
      """
      package app
      import org.buildmosaic.core.*
      import org.buildmosaic.core.injection.*
      val Recursive: MultiTile<Int, Int> by perKeyTile { key -> compose(Recursive, key - 1) }
      suspend fun entry() = canvas {}.withMosaic { compose(Recursive, 1) }
    """,
    )
    val build = File(app, "build.gradle.kts")
    val original = build.readText()
    val report = File(app, "build/reports/mosaic-analysis/main.txt")
    val graph = File(app, "build/reports/mosaic-analysis/graph.md")
    run(app, "verifyMosaic", expectFailure = true, configurationCache = true)
    assertTrue(report.readText().contains("MOSAIC_RECURSIVE_MULTITILE: Recursive MultiTile dependency: ERROR"))
    run(app, "mosaicGraph", configurationCache = true)
    assertTrue(graph.readText().contains("MOSAIC_RECURSIVE_MULTITILE: ERROR"))
    for (severity in listOf("WARNING", "OFF", "ERROR")) {
      build.writeText(
        original +
          """

          mosaicAnalysis {
            rules { severity("MOSAIC_RECURSIVE_MULTITILE", org.buildmosaic.analysis.MosaicRuleSeverity.$severity) }
          }
          """.trimIndent(),
      )
      val result =
        run(
          app,
          "verifyMosaic mosaicGraph",
          expectFailure = severity == "ERROR",
          configurationCache = true,
          buildCache = true,
        )
      assertEquals(TaskOutcome.UP_TO_DATE, result.task(":compileKotlin")?.outcome)
      if (severity != "ERROR") assertEquals(TaskOutcome.SUCCESS, result.task(":verifyMosaicMain")?.outcome)
      assertEquals(severity != "OFF", report.readText().contains("MOSAIC_RECURSIVE_MULTITILE"))
      if (severity == "WARNING") {
        assertTrue(report.readText().contains("PASSED WITH WARNINGS"))
        assertTrue(graph.readText().contains("MOSAIC_RECURSIVE_MULTITILE: WARNING"))
        assertFreshEquivalent(app, report.readText())
      }
    }
  }

  @Test
  fun `invalid and hard rule IDs fail configuration with registry guidance`() {
    val root = Files.createTempDirectory("mosaic-invalid-rules").toFile()
    val app = project(root, "app", compiler, listOf(core))
    val build = File(app, "build.gradle.kts")
    val original = build.readText()
    for (id in listOf("TYPO", "MOSAIC_CYCLIC_TILE_DEPENDENCY")) {
      build.writeText(
        original + "\nmosaicAnalysis { rules { severity(\"$id\", org.buildmosaic.analysis.MosaicRuleSeverity.OFF) } }\n",
      )
      val result = run(app, "help", expectFailure = true)
      assertTrue(result.output.contains("unknown or non-configurable"))
      assertTrue(result.output.contains("Valid configurable IDs: MOSAIC_RECURSIVE_TILE, MOSAIC_RECURSIVE_MULTITILE"))
    }
  }

  @Test
  fun `library exports binary recursion suppression and hard correctness stays enforcing`() {
    val root = Files.createTempDirectory("mosaic-binary-cycles").toFile()
    val library = project(root, "library", compiler, listOf(core), role = "LIBRARY")
    source(
      library,
      """
      package library
      import org.buildmosaic.core.*
      @Suppress("MOSAIC_RECURSIVE_MULTITILE")
      val Recursive: MultiTile<Int, Int> by perKeyTile { key -> compose(Recursive, key) }
      @Suppress("MOSAIC_CYCLIC_TILE_DEPENDENCY")
      val A: Tile<Int> by singleTile { compose(B) }
      val B: Tile<Int> by singleTile { compose(A) }
    """,
    )
    run(library, "build", buildCache = true)
    assertTrue(File(library, "build/reports/mosaic-analysis/main.txt").readText().contains("EXPORT_ONLY"))
    val app = project(root, "app", compiler, listOf(core, jar(library)), enforcement = "STRICT")
    source(
      app,
      """
      package app
      import library.*
      import org.buildmosaic.core.injection.*
      suspend fun entry() = canvas {}.withMosaic { compose(Recursive, 1) }
    """,
    )
    run(app, "build mosaicGraph", buildCache = true)
    val report = File(app, "build/reports/mosaic-analysis/main.txt").readText()
    assertTrue(report.contains("SUPPRESSED"))
    assertTrue(report.contains("Suppressed at App.kt:4"))
    source(
      app,
      """
      package app
      import library.*
      import org.buildmosaic.core.injection.*
      suspend fun entry() = canvas {}.withMosaic { compose(A) }
    """,
    )
    run(app, "verifyMosaic", expectFailure = true, buildCache = true)
    val failed = File(app, "build/reports/mosaic-analysis/main.txt").readText()
    assertTrue(failed.contains("Cyclic Tile dependency: ERROR"))
    assertTrue(failed.contains("library.A"))
    assertTrue(failed.contains("library.B"))
    assertFalse(failed.contains("SUPPRESSED"))
    assertFreshEquivalent(app, failed, expectFailure = true)
  }

  private fun source(
    project: File,
    text: String,
  ) {
    File(project, "src/main/kotlin/App.kt").apply {
      parentFile.mkdirs()
      writeText(text.trimIndent())
    }
  }
}

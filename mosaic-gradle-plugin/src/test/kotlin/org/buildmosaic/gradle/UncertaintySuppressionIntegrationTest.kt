@file:Suppress("LongMethod", "FunctionMaxLength", "MaxLineLength", "ktlint:standard:max-line-length")

package org.buildmosaic.gradle

import org.gradle.testkit.runner.TaskOutcome
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UncertaintySuppressionIntegrationTest {
  private val repository = File(System.getProperty("user.dir")).parentFile
  private val compiler =
    File(repository, "mosaic-compiler-plugin/build/libs/mosaic-compiler-plugin-${mosaicVersion()}.jar")
  private val core = File(repository, "mosaic-core/build/libs/mosaic-core-${mosaicVersion()}.jar")

  @Test
  fun `general uncertainty suppression passes strict without warnings and preserves missing errors`() {
    val root = Files.createTempDirectory("mosaic-unverified-suppression").toFile()
    val app = project(root, "app", compiler, listOf(core), enforcement = "STRICT")
    val original =
      """
      package app
      import org.buildmosaic.core.*
      import org.buildmosaic.core.injection.*
      class Service
      fun qualifier() = "runtime"
      val Uncertain by singleTile { source<Service>(qualifier()); 1 }
      suspend fun entry() = canvas {}.withMosaic { compose(Uncertain) }
      """.trimIndent()
    val report = File(app, "build/reports/mosaic-analysis/main.txt")
    source(app, original)
    run(app, "verifyMosaic", expectFailure = true)
    assertTrue(report.readText().contains("UNVERIFIED"))
    assertTrue(report.readText().contains("Result: FAILED"))

    val suppressed = original.replace("val Uncertain", "@Suppress(\"MOSAIC_UNVERIFIED\")\nval Uncertain")
    source(app, suppressed)
    val result = run(app, "verifyMosaic")
    val text = report.readText()
    assertEquals(TaskOutcome.SUCCESS, result.task(":verifyMosaicMain")?.outcome)
    assertFalse(result.output.contains("Mosaic verification passed with warnings"), result.output)
    assertTrue(text.contains("Root app.entry(): UNVERIFIED"), text)
    assertTrue(text.contains("UNVERIFIED REQUIRED_LOOKUP"), text)

    source(app, suppressed.replace("source<Service>(qualifier())", "source<Service>()"))
    run(app, "verifyMosaic", expectFailure = true)
    val missing = report.readText()
    assertTrue(missing.contains("MISSING REQUIRED_LOOKUP"), missing)
    assertTrue(missing.contains("Result: FAILED"), missing)
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

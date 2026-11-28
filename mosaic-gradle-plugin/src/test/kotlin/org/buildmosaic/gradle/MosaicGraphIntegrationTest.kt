package org.buildmosaic.gradle

import org.gradle.testkit.runner.TaskOutcome
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MosaicGraphIntegrationTest {
  @Test fun `graph discovers and overrides roots`() {
    val root = Files.createTempDirectory("mosaic-graph-").toFile()
    val repository = File(System.getProperty("user.dir")).parentFile
    val compiler = File(repository, "mosaic-compiler-plugin/build/libs/mosaic-compiler-plugin-${mosaicVersion()}.jar")
    val core = File(repository, "mosaic-core/build/libs/mosaic-core-${mosaicVersion()}.jar")
    val app = project(root, "app", compiler, listOf(core), enforcement = "STRICT")
    File(app, "src/main/kotlin/App.kt").apply {
      parentFile.mkdirs()
      writeText(
        """
        package app
        import org.buildmosaic.core.*
        import org.buildmosaic.core.injection.*
        val MissingTile = singleTile { source<String>() }
        val IdleTile = singleTile { "idle" }
        suspend fun entry(): String = canvas { }.withMosaic { compose(MissingTile) }
        """.trimIndent(),
      )
    }
    val output = File(app, "build/reports/mosaic-analysis/graph.md")
    val automatic = run(app, "mosaicGraph")
    assertEquals(TaskOutcome.SUCCESS, automatic.task(":mosaicGraph")?.outcome)
    val overview = output.readText()
    assertTrue(overview.contains("Tile: app.IdleTile"), overview)
    assertTrue(overview.contains("## Root: app.entry()"), overview)
    assertTrue(overview.contains("Selection: AUTOMATIC; receiver: none"), overview)
    assertTrue(overview.contains("Status: FAILED"), overview)

    File(app, "build.gradle.kts").appendText("\nmosaicAnalysis { roots.add(\"app.entry()\") }\n")
    val focused = run(app, "mosaicGraph")
    assertEquals(TaskOutcome.SUCCESS, focused.task(":mosaicGraph")?.outcome)
    val report = output.readText()
    assertTrue(report.contains("## Root: app.entry()"), report)
    assertTrue(report.contains("Selection: EXPLICIT; receiver: none"), report)
    assertTrue(report.contains("Tile: app.IdleTile"), report)
    assertTrue(report.contains("Status: FAILED"), report)
    assertTrue(report.contains("MISSING"), report)
    assertFalse(report.contains("Result: FULLY VERIFIED"), report)
    val unchanged = run(app, "mosaicGraph")
    assertEquals(TaskOutcome.UP_TO_DATE, unchanged.task(":mosaicGraph")?.outcome)
    assertEquals(report, File(app, "build/reports/mosaic-analysis/graph.md").readText())

    val buildFile = File(app, "build.gradle.kts")
    buildFile.writeText(buildFile.readText().replace("roots.add(\"app.entry()\")", "roots.add(\"app.absent()\")"))
    val invalidRoot = run(app, "mosaicGraph", expectFailure = true)
    assertEquals(TaskOutcome.FAILED, invalidRoot.task(":mosaicGraph")?.outcome)
    assertTrue(invalidRoot.output.contains("Selected root 'app.absent()' does not resolve"))
    assertFalse(output.exists())
  }

  @Test fun `graph without safe roots remains structural`() {
    val root = Files.createTempDirectory("mosaic-graph-overview-").toFile()
    val repository = File(System.getProperty("user.dir")).parentFile
    val compiler = File(repository, "mosaic-compiler-plugin/build/libs/mosaic-compiler-plugin-${mosaicVersion()}.jar")
    val core = File(repository, "mosaic-core/build/libs/mosaic-core-${mosaicVersion()}.jar")
    val app = project(root, "app", compiler, listOf(core), enforcement = "STRICT")
    File(app, "src/main/kotlin/App.kt").apply {
      parentFile.mkdirs()
      writeText(
        """
        package app
        import org.buildmosaic.core.*
        import org.buildmosaic.core.injection.*
        val Greeting = singleTile { "hello" }
        suspend fun respond(context: Canvas): String = context.withMosaic { compose(Greeting) }
        """.trimIndent(),
      )
    }
    val generated = run(app, "mosaicGraph")
    assertEquals(TaskOutcome.SUCCESS, generated.task(":mosaicGraph")?.outcome)
    val document = File(app, "build/reports/mosaic-analysis/graph.md").readText()
    assertTrue(document.contains("Tile: app.Greeting"), document)
    assertTrue(document.contains("no application roots were verified"), document)
    assertFalse(document.contains("## Root:"), document)
    assertFalse(document.contains("Status: VERIFIED"), document)

    val verification = run(app, "verifyMosaicMain", expectFailure = true)
    assertEquals(TaskOutcome.FAILED, verification.task(":verifyMosaicMain")?.outcome)
    assertTrue(verification.output.contains("no safe outermost execution context"), verification.output)
  }
}

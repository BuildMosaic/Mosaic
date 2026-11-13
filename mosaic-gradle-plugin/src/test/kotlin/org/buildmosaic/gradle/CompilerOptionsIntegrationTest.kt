package org.buildmosaic.gradle

import org.gradle.testkit.runner.TaskOutcome
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CompilerOptionsIntegrationTest {
  @Test
  fun `compiler settings preserve graph generation`() {
    val root = Files.createTempDirectory("mosaic-compiler-options-").toFile()
    val repository = File(System.getProperty("user.dir")).parentFile
    val compiler = File(repository, "mosaic-compiler-plugin/build/libs/mosaic-compiler-plugin-${mosaicVersion()}.jar")
    val core = File(repository, "mosaic-core/build/libs/mosaic-core-${mosaicVersion()}.jar")
    val app = project(root, "app", compiler, listOf(core))
    File(app, "build.gradle.kts").appendText(
      "\n" +
        """

        kotlin.compilerOptions {
          freeCompilerArgs.addAll("-Xjsr305=strict", "-Xno-param-assertions")
          optIn.add("app.ExperimentalProductApi")
          progressiveMode.set(true)
          jvmDefault.set(org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode.NO_COMPATIBILITY)
        }
        """.trimIndent(),
    )
    File(app, "src/main/kotlin/App.kt").apply {
      parentFile.mkdirs()
      writeText(
        """
        package app
        import org.buildmosaic.core.*
        import org.buildmosaic.core.injection.*
        @RequiresOptIn(level = RequiresOptIn.Level.ERROR)
        annotation class ExperimentalProductApi
        interface ProductLabel { fun label(): String = "Desk" }
        @ExperimentalProductApi
        fun productName(): String = object : ProductLabel {}.label()
        val ProductTile = singleTile { productName() }
        suspend fun entry() = canvas {}.withMosaic { compose(ProductTile) }
        """.trimIndent(),
      )
    }
    val result = run(app, "mosaicGraph")
    assertEquals(TaskOutcome.SUCCESS, result.task(":compileKotlin")?.outcome)
    assertEquals(TaskOutcome.SUCCESS, result.task(":extractMosaicMain")?.outcome)
    val graph = File(app, "build/reports/mosaic-analysis/graph.md").readText()
    assertTrue(graph.contains("Tile: app.ProductTile"), graph)
    assertTrue(graph.contains("## Root: app.entry()"), graph)
  }
}

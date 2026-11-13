package org.buildmosaic.gradle

import org.gradle.testkit.runner.TaskOutcome
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CompilerPluginCoexistenceIntegrationTest {
  @Test
  fun `serialization and Mosaic generate outputs in the same compilation`() {
    val root = Files.createTempDirectory("mosaic-serialization-").toFile()
    val repository = File(System.getProperty("user.dir")).parentFile
    val compiler = File(repository, "mosaic-compiler-plugin/build/libs/mosaic-compiler-plugin-${mosaicVersion()}.jar")
    val core = File(repository, "mosaic-core/build/libs/mosaic-core-${mosaicVersion()}.jar")
    val app = project(root, "app", compiler, listOf(core))
    val build = File(app, "build.gradle.kts")
    build.writeText(
      build.readText().replace(
        "kotlin(\"jvm\") version \"2.4.21\"",
        "kotlin(\"jvm\") version \"2.4.21\"\nkotlin(\"plugin.serialization\") version \"2.4.21\"",
      ) + "\ndependencies { implementation(\"org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0\") }\n",
    )
    File(app, "src/main/kotlin/App.kt").apply {
      parentFile.mkdirs()
      writeText(
        """
        package app
        import kotlinx.serialization.Serializable
        import kotlinx.serialization.json.Json
        import org.buildmosaic.core.*
        import org.buildmosaic.core.injection.*
        @Serializable data class Product(val name: String)
        val ProductTile = singleTile { Product("Desk") }
        suspend fun entry() = canvas {}.withMosaic { compose(ProductTile) }
        fun encode(product: Product) = Json.encodeToString(Product.serializer(), product)
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

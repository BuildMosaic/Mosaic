@file:Suppress(
  "LongMethod",
  "FunctionMaxLength",
  "NestedBlockDepth",
  "MaxLineLength",
  "ktlint:standard:max-line-length",
)

package org.buildmosaic.gradle

import org.buildmosaic.analysis.SummaryCodec
import org.gradle.testkit.runner.TaskOutcome
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConfiguredSourcesIntegrationTest {
  @Test
  fun `configured and generated sources stay current`() {
    val root = Files.createTempDirectory("mosaic-source-layout").toFile()
    val repository = File(System.getProperty("user.dir")).parentFile
    val pluginJar = File(repository, "mosaic-compiler-plugin/build/libs/mosaic-compiler-plugin-${mosaicVersion()}.jar")
    val coreJar = File(repository, "mosaic-core/build/libs/mosaic-core-${mosaicVersion()}.jar")
    val project = project(root, "layout", pluginJar, listOf(coreJar))
    val buildFile = File(project, "build.gradle.kts")
    buildFile.appendText("\n")
    buildFile.appendText(
      """

      val generateTiles by tasks.registering(Sync::class) {
        from("templates")
        into(layout.buildDirectory.dir("generated/tiles"))
      }
      kotlin.sourceSets.named("main") {
        kotlin.srcDir("custom")
        kotlin.srcDir(generateTiles)
      }
      tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>("compileKotlin") {
        exclude("**/Excluded.kt")
      }
      """.trimIndent(),
    )
    val custom =
      File(project, "custom/Tile.kt").apply {
        parentFile.mkdirs()
        writeText("package custom\nimport org.buildmosaic.core.*\nval CustomTile = singleTile { source<String>() }")
      }
    File(project, "custom/Excluded.kt").writeText("package custom\nclass Excluded")
    val template =
      File(project, "templates/Tile.kt").apply {
        parentFile.mkdirs()
        writeText("package generated\nimport org.buildmosaic.core.*\nval GeneratedTile = singleTile { source<Int>() }")
      }

    fun summary() = SummaryCodec.decode(File(project, "build/mosaic-analysis/main/summary.json").readBytes())
    val first = run(project, "extractMosaicMain", configurationCache = true)
    assertEquals(TaskOutcome.SUCCESS, first.task(":generateTiles")?.outcome)
    assertEquals(setOf("custom.CustomTile", "generated.GeneratedTile"), summary().module.tiles.map { it.id }.toSet())
    val moved = File(project, "custom/Renamed.kt")
    Files.move(custom.toPath(), moved.toPath())
    run(project, "extractMosaicMain", configurationCache = true)
    assertFalse(File(project, "build/mosaic-analysis/main/shards/custom/Tile.kt.shard.json").exists())
    Files.move(template.toPath(), File(project, "templates/Generated.kt").toPath())
    run(project, "extractMosaicMain", configurationCache = true)
    assertFalse(File(project, "build/mosaic-analysis/main/shards/build/generated/tiles/Tile.kt.shard.json").exists())
    val incremental = summary()
    run(project, "clean extractMosaicMain", configurationCache = true, buildCache = true)
    assertEquals(incremental, summary())
    val restored = run(project, "clean extractMosaicMain", configurationCache = true, buildCache = true)
    assertEquals(TaskOutcome.FROM_CACHE, restored.task(":compileKotlin")?.outcome)
    assertEquals(incremental, summary())
    buildFile.writeText(buildFile.readText().replace("kotlin.srcDir(\"custom\")", ""))
    run(project, "extractMosaicMain", configurationCache = true)
    assertTrue(moved.isFile)
    assertEquals(listOf("generated.GeneratedTile"), summary().module.tiles.map { it.id })
  }
}

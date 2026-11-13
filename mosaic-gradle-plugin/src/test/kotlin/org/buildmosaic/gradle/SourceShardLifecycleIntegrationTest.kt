@file:Suppress(
  "LongMethod",
  "FunctionMaxLength",
  "NestedBlockDepth",
  "MaxLineLength",
  "ktlint:standard:max-line-length",
)

package org.buildmosaic.gradle

import org.buildmosaic.analysis.SourceShardCodec
import org.buildmosaic.analysis.SummaryCodec
import org.gradle.testkit.runner.TaskOutcome
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SourceShardLifecycleIntegrationTest {
  @Test
  fun `fresh extraction drops deleted declarations`() {
    val root = Files.createTempDirectory("mosaic-deletion-integration").toFile()
    val repository = File(System.getProperty("user.dir")).parentFile
    val pluginJar = File(repository, "mosaic-compiler-plugin/build/libs/mosaic-compiler-plugin-${mosaicVersion()}.jar")
    val coreJar = File(repository, "mosaic-core/build/libs/mosaic-core-${mosaicVersion()}.jar")
    val project = project(root, "deletion", pluginJar, listOf(coreJar))

    fun shardBytes(workspace: File): Map<String, List<Byte>> {
      val directory = File(workspace, "build/mosaic-analysis/main/shards")
      return directory.walkTopDown().filter { it.isFile && it.name.endsWith(".shard.json") }
        .associate { it.relativeTo(directory).invariantSeparatorsPath to it.readBytes().toList() }
    }

    val originalBuild = File(project, "build.gradle.kts").readText()
    val source = File(project, "src/main/kotlin/Temporary.kt")
    source.parentFile.mkdirs()
    source.writeText(
      """
      package deletion
      import org.buildmosaic.core.*
      val TemporaryTile = singleTile { source<String>() }
      """.trimIndent(),
    )
    val stable = File(project, "src/main/kotlin/Stable.kt").apply { writeText("package deletion\nfun retained() = 1") }
    val dependent =
      File(project, "src/main/kotlin/Dependent.kt").apply {
        writeText("package deletion\nfun dependent() = retained()")
      }
    val duplicateBasenames =
      listOf("north" to "NorthTile", "south" to "SouthTile").map { (folder, tile) ->
        File(project, "src/main/kotlin/$folder/Foo.kt").apply {
          parentFile.mkdirs()
          writeText("package $folder\nimport org.buildmosaic.core.*\nval $tile = singleTile { source<String>() }")
        }
      }
    File(project, "src/test/kotlin/TestOnly.kt").apply {
      parentFile.mkdirs()
      writeText("package deletion\nimport org.buildmosaic.core.*\nval TestOnlyTile = singleTile { source<Int>() }")
    }
    run(project, "extractMosaicMain", configurationCache = true)
    val summaryFile = File(project, "build/mosaic-analysis/main/summary.json")
    assertTrue(SummaryCodec.decode(summaryFile.readBytes()).module.tiles.any { it.id == "deletion.TemporaryTile" })
    val initial = SummaryCodec.decode(summaryFile.readBytes())
    stable.writeText("package deletion\nfun retained() = 2")
    val ordinary = run(project, "extractMosaicMain", configurationCache = true)
    assertEquals(TaskOutcome.SUCCESS, ordinary.task(":compileKotlin")?.outcome)
    assertEquals(initial, SummaryCodec.decode(summaryFile.readBytes()))
    source.writeText(source.readText().replace("= singleTile { source<String>()", "by singleTile { source<Int>()"))
    run(project, "extractMosaicMain", configurationCache = true)
    assertNotEquals(initial, SummaryCodec.decode(summaryFile.readBytes()))
    val dependentShard = File(project, "build/mosaic-analysis/main/shards/src/main/kotlin/Dependent.kt.shard.json")
    val previousDependent = dependentShard.readText()
    stable.writeText("package deletion\nfun retained(value: Int = 1) = value")
    val signature = run(project, "extractMosaicMain", configurationCache = true)
    assertEquals(TaskOutcome.SUCCESS, signature.task(":compileKotlin")?.outcome)
    assertNotEquals(previousDependent, dependentShard.readText())
    assertTrue(SummaryCodec.decode(summaryFile.readBytes()).module.callables.any { it.id == "deletion.dependent()" })
    val moved = File(source.parentFile, "Moved.kt")
    moved.writeText(source.readText())
    source.writeText("package deletion\nfun noLongerMosaic() = 1")
    run(project, "extractMosaicMain", configurationCache = true)
    val movedSummary = SummaryCodec.decode(summaryFile.readBytes()).module
    assertEquals(1, movedSummary.tiles.count { it.id == "deletion.TemporaryTile" })
    val emptyShard = File(project, "build/mosaic-analysis/main/shards/src/main/kotlin/Temporary.kt.shard.json")
    assertTrue(SourceShardCodec.decode(emptyShard.readBytes()).module.tiles.isEmpty())
    assertFalse(emptyShard.readText().contains(project.absolutePath))
    assertTrue(File(project, "build/mosaic-analysis/main/shards/src/main/kotlin/north/Foo.kt.shard.json").isFile)
    assertTrue(File(project, "build/mosaic-analysis/main/shards/src/main/kotlin/south/Foo.kt.shard.json").isFile)
    val renamed = File(source.parentFile, "Renamed.kt")
    renamed.writeText(moved.readText().replace("TemporaryTile", "RenamedTile"))
    moved.delete()
    val second = run(project, "extractMosaicMain", configurationCache = true)
    assertEquals(TaskOutcome.SUCCESS, second.task(":extractMosaicMain")?.outcome)
    assertTrue(second.output.contains("Configuration cache entry reused"), second.output)
    val refreshed = SummaryCodec.decode(summaryFile.readBytes()).module
    assertEquals(1, refreshed.tiles.count { it.id == "deletion.RenamedTile" })
    assertTrue(refreshed.tiles.none { it.id == "deletion.TemporaryTile" })
    assertTrue(refreshed.callables.any { it.id == "deletion.retained(kotlin.Int)" })
    val shardDirectory = File(project, "build/mosaic-analysis/main/shards")
    assertFalse(File(shardDirectory, "src/main/kotlin/Moved.kt.shard.json").exists())
    val incrementalShards = shardBytes(project)
    assertEquals(
      setOf(
        "src/main/kotlin/Temporary.kt.shard.json",
        "src/main/kotlin/Stable.kt.shard.json",
        "src/main/kotlin/Dependent.kt.shard.json",
        "src/main/kotlin/Renamed.kt.shard.json",
        "src/main/kotlin/north/Foo.kt.shard.json",
        "src/main/kotlin/south/Foo.kt.shard.json",
      ),
      incrementalShards.keys,
    )
    val incrementalSummary = SummaryCodec.decode(summaryFile.readBytes())
    File(shardDirectory, "obsolete/Ghost.kt.shard.json").apply {
      parentFile.mkdirs()
      writeText("ignored stale shard")
    }
    val ignoredStale = run(project, "extractMosaicMain -x compileKotlin", configurationCache = true)
    assertEquals(TaskOutcome.UP_TO_DATE, ignoredStale.task(":extractMosaicMain")?.outcome)
    assertEquals(incrementalSummary, SummaryCodec.decode(summaryFile.readBytes()))

    val cleanProject = project(File(root, "clean-history"), "deletion", pluginJar, listOf(coreJar))
    val sourceRoot = File(project, "src/main/kotlin")
    sourceRoot.walkTopDown().filter(File::isFile).forEach { currentSource ->
      File(cleanProject, "src/main/kotlin/${currentSource.relativeTo(sourceRoot).invariantSeparatorsPath}").apply {
        parentFile.mkdirs()
        currentSource.copyTo(this)
      }
    }
    run(cleanProject, "extractMosaicMain", configurationCache = true)
    assertEquals(incrementalShards, shardBytes(cleanProject))
    assertEquals(
      incrementalSummary,
      SummaryCodec.decode(File(cleanProject, "build/mosaic-analysis/main/summary.json").readBytes()),
    )
    renamed.delete()
    stable.delete()
    dependent.delete()
    source.delete()
    duplicateBasenames.forEach(File::delete)
    val unconfigured = run(project, "verifyMosaicMain", expectFailure = true)
    val empty = SummaryCodec.decode(summaryFile.readBytes())
    assertTrue(empty.complete)
    assertTrue(empty.module.tiles.isEmpty())
    assertTrue(empty.module.callables.isEmpty())
    assertEquals(TaskOutcome.FAILED, unconfigured.task(":verifyMosaicMain")?.outcome)
    assertTrue(unconfigured.output.contains("Automatic Mosaic root discovery"))
    val javaSource = File(project, "src/main/java/Extra.java")
    javaSource.parentFile.mkdirs()
    javaSource.writeText("public class Extra {}")
    val javaOnly = run(project, "classes extractMosaicMain")
    assertEquals(TaskOutcome.SUCCESS, javaOnly.task(":compileJava")?.outcome)
    assertEquals(empty, SummaryCodec.decode(summaryFile.readBytes()))
    javaSource.delete()
    val roleChangedBuild =
      File(project, "build.gradle.kts").apply {
        writeText(originalBuild)
        appendText("\nmosaicAnalysis { role = org.buildmosaic.gradle.MosaicAnalysisRole.LIBRARY }\n")
      }
    val exportFirst = run(project, "verifyMosaicMain", configurationCache = true)
    assertTrue(exportFirst.output.contains("EXPORT_ONLY"), exportFirst.output)
    roleChangedBuild.appendText("\ntasks.named(\"extractMosaicMain\") { enabled = false }\n")
    summaryFile.writeBytes(byteArrayOf(1, 2, 3))
    val invalidLocal = run(project, "verifyMosaicMain", expectFailure = true)
    assertTrue(invalidLocal.output.contains("Invalid local Mosaic summary"), invalidLocal.output)
    assertTrue(
      File(project, "build/reports/mosaic-analysis/main.txt").readText().contains("Local metadata error"),
    )
  }
}

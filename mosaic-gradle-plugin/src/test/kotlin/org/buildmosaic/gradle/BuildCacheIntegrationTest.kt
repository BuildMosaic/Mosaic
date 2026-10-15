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
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class BuildCacheIntegrationTest {
  @Test
  fun `relocatable extraction cache and classpath controls`() {
    val root = Files.createTempDirectory("mosaic-cache-integration").toFile()
    val repository = File(System.getProperty("user.dir")).parentFile
    val pluginJar = File(repository, "mosaic-compiler-plugin/build/libs/mosaic-compiler-plugin-${mosaicVersion()}.jar")
    val coreJar = File(repository, "mosaic-core/build/libs/mosaic-core-${mosaicVersion()}.jar")
    val producer = project(root, "producer", pluginJar, listOf(coreJar), role = "LIBRARY")
    val producerSource = File(producer, "src/main/kotlin/Producer.kt")
    producerSource.parentFile.mkdirs()

    fun producerText(
      qualifier: String,
      inlineValue: Int = 1,
      selected: String = "First",
    ) = """
      package producer
      import org.buildmosaic.core.injection.*
      const val QUALIFIER = "$qualifier"
      class Metrics
      class First
      class Second
      typealias Selected = $selected
      suspend fun base(): Canvas = canvas { provide<Metrics>(QUALIFIER) { Metrics() } }
      inline fun ordinaryInline(): Int = $inlineValue
      fun api(): Int = 1
      """.trimIndent()
    producerSource.writeText(producerText("old"))
    run(producer, "jar")
    val producerJar = jar(producer)

    fun consumer(parent: File): File =
      project(
        parent,
        "consumer",
        pluginJar,
        listOf(coreJar, producerJar),
        listOf("consumer.entry()"),
      )

    fun writeConsumerSource(
      consumer: File,
      fileName: String = "Consumer.kt",
    ) {
      File(consumer, "src/main/kotlin/$fileName").apply {
        parentFile.mkdirs()
        writeText(
          """
          package consumer
          import org.buildmosaic.core.*
          import org.buildmosaic.core.injection.*
          import producer.*
          val CapturedTile = singleTile { source<Metrics>(QUALIFIER) }
          val SelectedKey = CanvasKey(Selected::class)
          suspend fun entry(): Metrics = base().withMosaic { compose(CapturedTile) }
          fun inlineControl(): Int = ordinaryInline()
          fun apiUse(): Number = api()
          """.trimIndent(),
        )
      }
    }
    val workspaceA = consumer(File(root, "workspace-a"))
    writeConsumerSource(workspaceA, "BeforeRename.kt")
    val cacheDirectory = File(root, "local-build-cache")

    fun useSharedCache(workspace: File) {
      File(workspace, "settings.gradle.kts").appendText(
        "\nbuildCache { local { directory = file(\"${cacheDirectory.invariantSeparatorsPath}\") } }\n",
      )
    }
    useSharedCache(workspaceA)
    val initial = run(workspaceA, "build", buildCache = true)
    assertEquals(TaskOutcome.SUCCESS, initial.task(":extractMosaicMain")?.outcome)
    val sourceRootA = File(workspaceA, "src/main/kotlin")
    File(sourceRootA, "BeforeRename.kt").copyTo(File(sourceRootA, "Consumer.kt"))
    File(sourceRootA, "BeforeRename.kt").delete()
    val renamed = run(workspaceA, "build", buildCache = true)
    assertEquals(TaskOutcome.SUCCESS, renamed.task(":compileKotlin")?.outcome)
    assertFalse(File(workspaceA, "build/mosaic-analysis/main/shards/BeforeRename.kt.shard.json").exists())
    val original = SummaryCodec.decode(File(workspaceA, "build/mosaic-analysis/main/summary.json").readBytes())
    assertEquals("producer.First", original.module.keys.single { it.id == "consumer.SelectedKey" }.key.classId)
    val workspaceB = consumer(File(root, "workspace-b"))
    writeConsumerSource(workspaceB)
    useSharedCache(workspaceB)
    val restored = run(workspaceB, "clean build", buildCache = true)
    assertEquals(TaskOutcome.FROM_CACHE, restored.task(":compileKotlin")?.outcome)
    assertTrue(File(workspaceB, "build/mosaic-analysis/main/shards/Consumer.kt.shard.json").isFile)
    assertEquals(original, SummaryCodec.decode(File(workspaceB, "build/mosaic-analysis/main/summary.json").readBytes()))

    producerSource.writeText(producerText("new"))
    run(producer, "jar")
    val constant = run(workspaceA, "build")
    assertEquals(TaskOutcome.SUCCESS, constant.task(":compileKotlin")?.outcome)
    assertEquals(TaskOutcome.SUCCESS, constant.task(":extractMosaicMain")?.outcome)
    val changed = SummaryCodec.decode(File(workspaceA, "build/mosaic-analysis/main/summary.json").readBytes())
    assertNotEquals(original, changed)
    assertTrue(File(workspaceA, "build/mosaic-analysis/main/summary.json").readText().contains("\"qualifier\":\"new\""))

    producerSource.writeText(producerText("new", inlineValue = 2))
    run(producer, "jar")
    val inline = run(workspaceA, "build")
    assertTrue(inline.task(":compileKotlin")?.outcome in setOf(TaskOutcome.SUCCESS, TaskOutcome.UP_TO_DATE))
    assertEquals(changed, SummaryCodec.decode(File(workspaceA, "build/mosaic-analysis/main/summary.json").readBytes()))

    producerSource.writeText(producerText("new", inlineValue = 2, selected = "Second"))
    run(producer, "jar")
    val alias = run(workspaceA, "build")
    assertEquals(TaskOutcome.SUCCESS, alias.task(":extractMosaicMain")?.outcome)
    val aliasSummary = SummaryCodec.decode(File(workspaceA, "build/mosaic-analysis/main/summary.json").readBytes())
    assertEquals("producer.Second", aliasSummary.module.keys.single { it.id == "consumer.SelectedKey" }.key.classId)
    assertFreshEquivalent(workspaceA, File(workspaceA, "build/reports/mosaic-analysis/main.txt").readText())

    val beforeFailure = SummaryCodec.decode(File(workspaceA, "build/mosaic-analysis/main/summary.json").readBytes())
    val consumerSource = File(workspaceA, "src/main/kotlin/Consumer.kt")
    val validSource = consumerSource.readText()
    consumerSource.writeText(
      validSource.replace("fun apiUse(): Number = api()", "fun apiUse(): Number = missingSymbol()")
        .replace("source<Metrics>(QUALIFIER)", "source<Metrics>(\"broken\")"),
    )
    val failed = run(workspaceA, "build", expectFailure = true)
    assertEquals(TaskOutcome.FAILED, failed.task(":compileKotlin")?.outcome)
    assertEquals(null, failed.task(":extractMosaicMain")?.outcome)
    consumerSource.writeText(validSource)
    run(workspaceA, "build")
    assertEquals(
      beforeFailure,
      SummaryCodec.decode(File(workspaceA, "build/mosaic-analysis/main/summary.json").readBytes()),
    )
  }
}

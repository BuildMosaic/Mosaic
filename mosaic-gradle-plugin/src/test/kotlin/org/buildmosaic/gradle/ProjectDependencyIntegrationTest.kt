@file:Suppress(
  "LongMethod",
  "FunctionMaxLength",
  "NestedBlockDepth",
  "MaxLineLength",
  "ktlint:standard:max-line-length",
)

package org.buildmosaic.gradle

import org.gradle.testkit.runner.TaskOutcome
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProjectDependencyIntegrationTest {
  @Test
  fun `project dependency jar variant supplies its Mosaic summary`() {
    val root = Files.createTempDirectory("mosaic-project-dependency").toFile()
    val repository = File(System.getProperty("user.dir")).parentFile
    val pluginJar = File(repository, "mosaic-compiler-plugin/build/libs/mosaic-compiler-plugin-${mosaicVersion()}.jar")
    val coreJar = File(repository, "mosaic-core/build/libs/mosaic-core-${mosaicVersion()}.jar")
    File(root, "settings.gradle.kts").writeText(
      """
      pluginManagement {
        repositories { gradlePluginPortal(); mavenCentral() }
        resolutionStrategy.eachPlugin {
          if (requested.id.id == "org.jetbrains.kotlin.jvm") useModule("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.21")
        }
      }
      rootProject.name = "project-dependency"
      include("producer", "consumer")
      """.trimIndent(),
    )
    listOf("producer", "consumer").forEach { name ->
      val module = File(root, name).apply { mkdirs() }
      File(module, "build.gradle.kts").writeText(
        """
        plugins { kotlin("jvm") version "2.4.21"; id("org.buildmosaic.analysis") }
        group = "fixture"
        ${fixtureRepositories(pluginJar)}
        dependencies {
          implementation(files("${coreJar.invariantSeparatorsPath}"))
          implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
          ${if (name == "consumer") "implementation(project(\":producer\"))" else ""}
        }
        mosaicAnalysis {
          ${if (name == "producer") "role = org.buildmosaic.gradle.MosaicAnalysisRole.LIBRARY" else "roots.add(\"consumer.entry()\")"}
        }
        """.trimIndent(),
      )
    }
    File(root, "producer/src/main/kotlin/Producer.kt").apply {
      parentFile.mkdirs()
      writeText(
        """
        package producer
        import org.buildmosaic.core.*
        import org.buildmosaic.core.injection.*
        class Metrics
        val Single by singleTile { source<Metrics>() }
        val Batch by multiTile<String, Metrics> { source<Metrics>(); emptyMap() }
        val PerKey by perKeyTile<String, Metrics> { source<Metrics>() }
        val Chunked by chunkedMultiTile<String, Metrics>(2) { source<Metrics>(); emptyMap() }
        suspend fun base(): Canvas = canvas { provide<Metrics> { Metrics() } }
        """.trimIndent(),
      )
    }
    File(root, "consumer/src/main/kotlin/Consumer.kt").apply {
      parentFile.mkdirs()
      writeText(
        """
        package consumer
        import org.buildmosaic.core.*
        import org.buildmosaic.core.injection.*
        import producer.*
        val MetricsTile by singleTile { source<Metrics>() }
        suspend fun entry(): Metrics {
          return base().withMosaic {
            val mosaic = this
            mosaic.compose(Single)
            mosaic.compose(Batch, "key")
            mosaic.compose(PerKey, "key")
            mosaic.compose(Chunked, "key")
            mosaic.compose(MetricsTile)
          }
        }
        """.trimIndent(),
      )
    }
    val result = run(root, ":consumer:verifyMosaicMain", configurationCache = true)
    assertEquals(TaskOutcome.SUCCESS, result.task(":consumer:verifyMosaicMain")?.outcome)
    assertTrue(File(root, "consumer/build/reports/mosaic-analysis/main.txt").readText().contains("FULLY VERIFIED"))
  }
}

@file:Suppress("LongMethod")

package org.buildmosaic.gradle

import org.gradle.testkit.runner.TaskOutcome
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CompositeCoreIntegrationTest {
  @Test
  fun `included core build supplies packaged revision`() {
    val root = Files.createTempDirectory("mosaic-composite-core").toFile()
    val repository = File(System.getProperty("user.dir")).parentFile
    val pluginJar = File(repository, "mosaic-compiler-plugin/build/libs/mosaic-compiler-plugin-${mosaicVersion()}.jar")
    val coreJar = File(repository, "mosaic-core/build/libs/mosaic-core-${mosaicVersion()}.jar")
    val consumer = project(root, "consumer", pluginJar, emptyList(), roots = listOf("sample.entry()"))
    File(consumer, "settings.gradle.kts").appendText("\nincludeBuild(\"../mosaic-core\")\n")
    File(consumer, "build.gradle.kts").appendText(
      "\ndependencies { implementation(\"org.buildmosaic:mosaic-core:${mosaicVersion()}\") }\n",
    )
    val core = File(root, "mosaic-core").apply { mkdirs() }
    File(core, "settings.gradle.kts").writeText("rootProject.name = \"mosaic-core\"")
    File(core, "build.gradle.kts").writeText(
      """
      plugins { `java-library` }
      group = "org.buildmosaic"
      version = "${mosaicVersion()}"
      val unpackCore = tasks.register<Sync>("unpackCore") {
        from(zipTree("${coreJar.invariantSeparatorsPath}")) { exclude("META-INF/MANIFEST.MF") }
        into(layout.buildDirectory.dir("core-classes"))
      }
      sourceSets.main { java.destinationDirectory.set(layout.buildDirectory.dir("core-classes")) }
      tasks.compileJava { dependsOn(unpackCore) }
      tasks.jar {
        val coreManifest = zipTree("${coreJar.invariantSeparatorsPath}").matching {
          include("META-INF/MANIFEST.MF")
        }
        manifest.from(coreManifest.singleFile)
      }
      """.trimIndent(),
    )
    File(consumer, "src/main/kotlin/Entry.kt").apply {
      parentFile.mkdirs()
      writeText(
        """
        package sample
        import org.buildmosaic.core.*
        import org.buildmosaic.core.injection.*
        val Greeting by singleTile { "hello" }
        suspend fun entry(): String = canvas {}.withMosaic { compose(Greeting) }
        """.trimIndent(),
      )
    }
    val first = run(consumer, "verifyMosaicMain mosaicGraph", configurationCache = true)
    assertEquals(TaskOutcome.SUCCESS, first.task(":verifyMosaicMain")?.outcome)
    assertEquals(TaskOutcome.SUCCESS, first.task(":mosaicGraph")?.outcome)
    assertTrue(File(consumer, "build/reports/mosaic-analysis/main.txt").readText().contains("FULLY VERIFIED"))
    val repeated = run(consumer, "verifyMosaicMain mosaicGraph", configurationCache = true)
    assertTrue(repeated.output.contains("Reusing configuration cache"))
  }
}

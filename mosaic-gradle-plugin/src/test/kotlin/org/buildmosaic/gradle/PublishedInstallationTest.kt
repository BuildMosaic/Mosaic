@file:Suppress("LongMethod", "MaxLineLength", "ktlint:standard:max-line-length")

package org.buildmosaic.gradle

import org.buildmosaic.analysis.CompilerVersionAdmission
import org.buildmosaic.analysis.SUMMARY_PATH
import org.buildmosaic.analysis.SummaryCodec
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import java.io.File
import java.nio.file.Files
import java.util.jar.JarFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Verifies real publications, their metadata, and installation into Kotlin DSL consumers. */
@Suppress("LargeClass")
class PublishedInstallationTest {
  @Test
  fun `published plugin supports Gradle consumers`() {
    val workspace = Files.createTempDirectory("mosaic-published-install-").toFile()
    val version = mosaicVersion()
    val maven = publishInstallation(workspace)

    val marker =
      maven.resolve(
        "org/buildmosaic/analysis/org.buildmosaic.analysis.gradle.plugin/$version/org.buildmosaic.analysis.gradle.plugin-$version.pom",
      ).readText()
    assertTrue(marker.contains("<groupId>org.buildmosaic</groupId>"))
    assertTrue(marker.contains("<artifactId>mosaic-gradle-plugin</artifactId>"))
    assertTrue(marker.contains("<version>$version</version>"))
    for (module in listOf(
      "mosaic-core",
      "mosaic-test",
      "mosaic-opentelemetry",
      "mosaic-compiler-plugin",
      "mosaic-gradle-plugin",
    )) {
      val artifact = maven.resolve("org/buildmosaic/$module/$version/$module-$version")
      assertTrue(artifact.resolveSibling("$module-$version.jar").isFile)
      assertTrue(artifact.resolveSibling("$module-$version-sources.jar").isFile)
      assertTrue(artifact.resolveSibling("$module-$version-javadoc.jar").isFile)
      val pom = artifact.resolveSibling("$module-$version.pom").readText()
      assertTrue(pom.contains("<url>https://github.com/BuildMosaic/Mosaic/tree/main/$module</url>"), pom)
      assertTrue(pom.contains("<name>The Apache License, Version 2.0</name>"), pom)
    }
    val bom = maven.resolve("org/buildmosaic/mosaic-bom/$version/mosaic-bom-$version.pom").readText()
    assertTrue(!maven.resolve("org/buildmosaic/mosaic-analysis-core").exists())
    assertTrue(bom.contains("<artifactId>mosaic-core</artifactId>"), bom)
    assertTrue(bom.contains("<artifactId>mosaic-test</artifactId>"), bom)
    assertTrue(bom.contains("<artifactId>mosaic-opentelemetry</artifactId>"), bom)
    val tracingPom =
      maven.resolve(
        "org/buildmosaic/mosaic-opentelemetry/$version/mosaic-opentelemetry-$version.pom",
      ).readText()
    assertTrue(tracingPom.contains("<artifactId>opentelemetry-api</artifactId>"), tracingPom)
    assertTrue(!tracingPom.contains("opentelemetry-sdk"), tracingPom)
    assertTrue(!tracingPom.contains("opentelemetry-extension-kotlin"), tracingPom)
    assertTrue(!bom.contains("<artifactId>mosaic-analysis-core</artifactId>"), bom)
    val pluginPom =
      maven.resolve("org/buildmosaic/mosaic-gradle-plugin/$version/mosaic-gradle-plugin-$version.pom").readText()
    assertTrue(!pluginPom.contains("<artifactId>mosaic-analysis-core</artifactId>"), pluginPom)
    assertTrue(!pluginPom.contains("kotlin-gradle-plugin"), pluginPom)
    val compilerPom =
      maven.resolve("org/buildmosaic/mosaic-compiler-plugin/$version/mosaic-compiler-plugin-$version.pom").readText()
    assertTrue(!compilerPom.contains("<artifactId>mosaic-analysis-core</artifactId>"), compilerPom)
    assertTrue(!compilerPom.contains("kotlin-compiler-embeddable"), compilerPom)

    for ((gradle, kotlin) in listOf("8.2.1" to "2.4.21", "9.0.0" to "2.3.0", "9.7.1" to "2.4.21")) {
      verifyConsumer(workspace, maven, version, gradle, kotlin)
    }
  }

  private fun verifyConsumer(
    workspace: File,
    maven: File,
    version: String,
    gradle: String,
    kotlin: String,
  ) {
    val app = workspace.resolve("consumer-$gradle").apply { mkdirs() }
    app.resolve("settings.gradle.kts").writeText(
      """
      pluginManagement { repositories { maven(url = uri("${maven.invariantSeparatorsPath}")); mavenCentral(); gradlePluginPortal() } }
      dependencyResolutionManagement { repositories { maven(url = uri("${maven.invariantSeparatorsPath}")); mavenCentral() } }
      rootProject.name = "app"
      """.trimIndent(),
    )
    app.resolve("gradle.properties").writeText("org.gradle.jvmargs=-Xmx1g -XX:MaxMetaspaceSize=1g\n")
    app.resolve("build.gradle.kts").writeText(
      """
      plugins {
        kotlin("jvm") version "$kotlin"
        id("org.buildmosaic.analysis") version "$version"
        application
      }
      check(JavaVersion.current() == JavaVersion.VERSION_17)
      version = "1.0"
      kotlin { jvmToolchain(17) }
      dependencies { implementation("org.buildmosaic:mosaic-core:$version") }
      application { mainClass.set("app.MainKt") }
      mosaicAnalysis {
        roots.add("app.safe()")
        roots.add("app.unknown()")
        if (providers.gradleProperty("fixture.missing").isPresent) roots.add("app.missing()")
      }
      """.trimIndent(),
    )
    app.resolve("src/main/kotlin/Main.kt").apply {
      parentFile.mkdirs()
      writeText(
        """
        package app
        import org.buildmosaic.core.*
        import org.buildmosaic.core.injection.*
        import kotlinx.coroutines.runBlocking
        suspend fun safe(): String = canvas { instance("ready") }.withMosaic { source<String>() }
        suspend fun missing(): Int = canvas { }.withMosaic { source<Int>() }
        class Reader {
          fun Mosaic.read(): String = source<String>()
          fun invoke(receiver: Mosaic): String = receiver.read()
        }
        suspend fun unknown(): String = canvas { instance("ready") }.withMosaic { Reader().invoke(this) }
        fun main() = runBlocking { check(safe() == "ready"); println("MOSAIC_CONSUMER_PASSED") }
        """.trimIndent(),
      )
    }
    val arguments =
      arrayOf(
        "build",
        "run",
        "mosaicGraph",
        "dependencies",
        "--configuration",
        "kotlinCompilerPluginClasspathMain",
        "--configuration-cache",
        "--configuration-cache-problems=fail",
      )
    val javaHome = File(System.getProperty("java.home"))
    val result = runPublished(app, *arguments, gradleVersion = gradle, javaHome = javaHome)
    assertEquals(TaskOutcome.SUCCESS, result.task(":compileKotlin")?.outcome, result.output)
    assertEquals(TaskOutcome.SUCCESS, result.task(":extractMosaicMain")?.outcome, result.output)
    assertEquals(TaskOutcome.SUCCESS, result.task(":verifyMosaicMain")?.outcome, result.output)
    assertTrue(result.output.contains("MOSAIC_CONSUMER_PASSED"), result.output)
    assertTrue(result.output.contains("Configuration cache entry stored"), result.output)
    val artifact = CompilerVersionAdmission.artifactId(CompilerVersionAdmission.compilerApi(kotlin))
    assertTrue(result.output.contains("org.buildmosaic:$artifact:$version"), result.output)
    val summary = SummaryCodec.decode(app.resolve("build/mosaic-analysis/main/summary.json").readBytes())
    assertEquals(kotlin, summary.producer.compilerVersion)
    assertEquals(version, summary.producer.analysisVersion)
    val report = app.resolve("build/reports/mosaic-analysis/main.txt").readText()
    assertTrue(report.contains("Root app.safe(): VERIFIED"), report)
    assertTrue(report.contains("Root app.unknown(): UNVERIFIED"), report)
    val graph = app.resolve("build/reports/mosaic-analysis/graph.md").readText()
    assertTrue(graph.contains("## Root: app.safe()") && graph.contains("Status: VERIFIED"), graph)
    assertTrue(graph.contains("## Root: app.unknown()") && graph.contains("Status: UNVERIFIED"), graph)
    JarFile(app.resolve("build/libs/app-1.0.jar")).use { archive ->
      assertEquals(summary, SummaryCodec.decode(archive.getInputStream(archive.getJarEntry(SUMMARY_PATH)).readBytes()))
    }

    val repeated = runPublished(app, *arguments, gradleVersion = gradle, javaHome = javaHome)
    assertTrue(repeated.output.contains("Configuration cache entry reused"), repeated.output)
    assertEquals(TaskOutcome.UP_TO_DATE, repeated.task(":compileKotlin")?.outcome, repeated.output)
    assertEquals(TaskOutcome.UP_TO_DATE, repeated.task(":verifyMosaicMain")?.outcome, repeated.output)
    assertEquals(summary, SummaryCodec.decode(app.resolve("build/mosaic-analysis/main/summary.json").readBytes()))
    assertEquals(report, app.resolve("build/reports/mosaic-analysis/main.txt").readText())

    val failed =
      runPublished(
        app,
        "verifyMosaicMain",
        "-Pfixture.missing=true",
        "--configuration-cache",
        "--configuration-cache-problems=fail",
        expectFailure = true,
        gradleVersion = gradle,
        javaHome = javaHome,
      )
    assertEquals(TaskOutcome.FAILED, failed.task(":verifyMosaicMain")?.outcome, failed.output)
    assertTrue(app.resolve("build/reports/mosaic-analysis/main.txt").readText().contains("MISSING REQUIRED_LOOKUP"))
  }
}

internal fun runPublished(
  project: File,
  vararg arguments: String,
  expectFailure: Boolean = false,
  gradleVersion: String? = null,
  javaHome: File? = null,
): org.gradle.testkit.runner.BuildResult {
  return GradleRunner.create().withProjectDir(project)
    .withArguments(
      *arguments,
      "--stacktrace",
      "--console=plain",
      "--max-workers=2",
      "-Porg.gradle.java.installations.paths=${System.getProperty("mosaic.test.javaInstallations", "")}",
      "--gradle-user-home",
      File(System.getProperty("user.home"), ".gradle").absolutePath,
    )
    .apply {
      if (gradleVersion != null) withGradleVersion(gradleVersion)
      if (javaHome != null) withArguments(this.arguments + "-Dorg.gradle.java.home=${javaHome.absolutePath}")
    }
    .let { if (expectFailure) it.buildAndFail() else it.build() }
}

internal fun publishInstallation(workspace: File): File {
  val maven = workspace.resolve("maven")
  val version = mosaicVersion()
  runPublished(
    File(System.getProperty("user.dir")).parentFile,
    *listOf(
      "mosaic-core",
      "mosaic-test",
      "mosaic-opentelemetry",
      "mosaic-bom",
      "mosaic-compiler-plugin",
      "mosaic-gradle-plugin",
    )
      .map { ":$it:publishAllPublicationsToInstallTestRepository" }.toTypedArray(),
    "-Pmosaic.runtime.version=$version",
    "-Pmosaic.analysis.version=$version",
    "-Pmosaic.installTestRepository=${maven.absolutePath}",
    "--no-configuration-cache",
  )
  return maven
}

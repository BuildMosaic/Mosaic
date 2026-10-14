@file:Suppress("LongMethod", "MaxLineLength", "ktlint:standard:max-line-length")

package org.buildmosaic.gradle

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

/** Proves that the real publications install through the external plugins DSL. */
class PublishedInstallationTest {
  @Test
  fun `published plugin verifies Canvas root`() {
    val repositoryRoot = File(System.getProperty("user.dir")).parentFile
    val version =
      repositoryRoot.resolve("gradle.properties").readLines()
        .first { it.startsWith("mosaic.version=") }.substringAfter('=')
    val workspace = Files.createTempDirectory("mosaic-published-install-").toFile()
    val maven = workspace.resolve("maven")
    run(
      repositoryRoot,
      ":mosaic-core:publishAllPublicationsToInstallTestRepository",
      ":mosaic-test:publishAllPublicationsToInstallTestRepository",
      ":mosaic-opentelemetry:publishAllPublicationsToInstallTestRepository",
      ":mosaic-bom:publishAllPublicationsToInstallTestRepository",
      ":mosaic-compiler-plugin:publishAllPublicationsToInstallTestRepository",
      ":mosaic-gradle-plugin:publishAllPublicationsToInstallTestRepository",
      "-Pmosaic.installTestRepository=${maven.absolutePath}",
    )

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
    JarFile(maven.resolve("org/buildmosaic/mosaic-gradle-plugin/$version/mosaic-gradle-plugin-$version.jar")).use {
        jar ->
      assertTrue(jar.getEntry("org/buildmosaic/analysis/SummaryCodec.class") != null)
      assertTrue(jar.getEntry("kotlinx/serialization/json/Json.class") != null)
    }
    val compilerPom =
      maven.resolve("org/buildmosaic/mosaic-compiler-plugin/$version/mosaic-compiler-plugin-$version.pom").readText()
    assertTrue(!compilerPom.contains("<artifactId>mosaic-analysis-core</artifactId>"), compilerPom)
    assertTrue(!compilerPom.contains("kotlin-compiler-embeddable"), compilerPom)
    JarFile(maven.resolve("org/buildmosaic/mosaic-compiler-plugin/$version/mosaic-compiler-plugin-$version.jar")).use {
        jar ->
      for ((service, implementation) in listOf(
        "CompilerPluginRegistrar" to "MosaicCompilerRegistrar",
        "CommandLineProcessor" to "MosaicCommandLineProcessor",
      )) {
        val entry = jar.getJarEntry("META-INF/services/org.jetbrains.kotlin.compiler.plugin.$service")
        assertTrue(entry != null)
        assertEquals(
          "org.buildmosaic.compiler.$implementation",
          jar.getInputStream(entry).bufferedReader().readText().trim(),
        )
      }
      assertTrue(jar.getEntry("org/buildmosaic/analysis/SummaryCodec.class") != null)
      assertTrue(jar.getEntry("kotlinx/serialization/json/Json.class") != null)
      assertTrue(jar.entries().asSequence().none { it.name.startsWith("com/fasterxml/jackson/") })
    }

    val consumer = workspace.resolve("app").apply { mkdirs() }
    consumer.resolve("settings.gradle.kts").writeText(
      """
      pluginManagement { repositories { maven(url = uri("${maven.invariantSeparatorsPath}")); gradlePluginPortal() } }
      dependencyResolutionManagement { repositories { maven(url = uri("${maven.invariantSeparatorsPath}")); mavenCentral() } }
      rootProject.name = "app"
      """.trimIndent(),
    )
    consumer.resolve("build.gradle.kts").writeText(
      """
      plugins {
        kotlin("jvm") version "2.4.20"
        id("org.buildmosaic.analysis") version "$version"
      }
      version = "99.0.0"
      dependencies { implementation("org.buildmosaic:mosaic-core:$version") }
      mosaicAnalysis {
        role = org.buildmosaic.gradle.MosaicAnalysisRole.APPLICATION
        enforcement = org.buildmosaic.gradle.MosaicAnalysisEnforcement.STANDARD
        roots.add("app.entry()")
      }
      """.trimIndent(),
    )
    consumer.resolve("src/main/kotlin/Main.kt").apply {
      parentFile.mkdirs()
      writeText(
        """
        package app
        import org.buildmosaic.core.*
        import org.buildmosaic.core.injection.*
        interface Service
        class ExistingService : Service
        val OrderKey = CanvasKey(String::class, "order")
        suspend fun entry(): String = canvas {
          instance("ready")
          instance<Service>("primary", ExistingService())
          instance("secondary", ExistingService())
          instance(OrderKey, "order-1")
        }.withMosaic {
          source<Service>("primary"); source<ExistingService>("secondary")
          source(OrderKey); source<String>()
        }
        """.trimIndent(),
      )
    }
    val result =
      run(consumer, "build", "mosaicGraph", "dependencies", "--configuration", "kotlinCompilerPluginClasspathMain")
    assertEquals(TaskOutcome.SUCCESS, result.task(":verifyMosaicMain")?.outcome)
    assertEquals(TaskOutcome.SUCCESS, result.task(":mosaicGraph")?.outcome)
    assertTrue(result.output.contains("org.buildmosaic:mosaic-compiler-plugin:$version"), result.output)
    val report = consumer.resolve("build/reports/mosaic-analysis/main.txt").readText()
    assertTrue(report.contains("Result: FULLY VERIFIED"), report)
    assertTrue(report.contains("VERIFIED REQUIRED_LOOKUP"), report)
    assertTrue(consumer.resolve("build/reports/mosaic-analysis/graph.md").readText().contains("## Root: app.entry()"))
    val summary = SummaryCodec.decode(consumer.resolve("build/mosaic-analysis/main/summary.json").readBytes())
    assertEquals(4, summary.formatVersion)
    assertEquals("analysis-contract-3", summary.semanticsVersion)
    assertEquals("prototype-11", summary.toolVersion)
    assertTrue(summary.module.callables.any { it.id == "app.entry()" && it.effects.isNotEmpty() })
    JarFile(consumer.resolve("build/libs/app-99.0.0.jar")).use { assertTrue(it.getEntry(SUMMARY_PATH) != null) }

    verifyRuntimeConsumer(workspace, maven, version, useBom = false, kotlinVersion = "2.3.0")
    verifyRuntimeConsumer(workspace, maven, version, useBom = true)
  }
}

private fun verifyRuntimeConsumer(
  workspace: File,
  maven: File,
  version: String,
  useBom: Boolean,
  kotlinVersion: String = "2.4.20",
) {
  val consumer = workspace.resolve(if (useBom) "bom-consumer" else "runtime-consumer").apply { mkdirs() }
  consumer.resolve("settings.gradle.kts").writeText(
    """
    pluginManagement { repositories { gradlePluginPortal() } }
    dependencyResolutionManagement { repositories { maven(url = uri("${maven.invariantSeparatorsPath}")); mavenCentral() } }
    rootProject.name = "consumer"
    """.trimIndent(),
  )
  val core = if (useBom) "org.buildmosaic:mosaic-core" else "org.buildmosaic:mosaic-core:$version"
  val test = if (useBom) "org.buildmosaic:mosaic-test" else "org.buildmosaic:mosaic-test:$version"
  val tracing = if (useBom) "org.buildmosaic:mosaic-opentelemetry" else "org.buildmosaic:mosaic-opentelemetry:$version"
  val platform = if (useBom) "implementation(platform(\"org.buildmosaic:mosaic-bom:$version\"))" else ""
  consumer.resolve("build.gradle.kts").writeText(
    """
    plugins { kotlin("jvm") version "$kotlinVersion" }
    dependencies {
      $platform
      implementation("$core")
      implementation("$tracing")
      testImplementation("$test")
      testImplementation(kotlin("test"))
    }
    tasks.test { useJUnitPlatform() }
    """.trimIndent(),
  )
  consumer.resolve("src/test/kotlin/TileTest.kt").apply {
    parentFile.mkdirs()
    writeText(
      """
      import kotlinx.coroutines.test.runTest
      import org.buildmosaic.core.singleTile
      import org.buildmosaic.test.TestMosaicBuilder
      import org.buildmosaic.core.injection.canvas
      import org.buildmosaic.core.injection.create
      import org.buildmosaic.opentelemetry.tracing
      import io.opentelemetry.api.OpenTelemetry
      import kotlin.test.Test

      class TileTest {
        @Test fun composes() = runTest {
          val input by singleTile { "original" }
          val response by singleTile { compose(input).uppercase() }
          val mosaic = TestMosaicBuilder(this).withMockTile(input, "published").build()
          mosaic.assertEquals(response, "PUBLISHED")
          kotlin.test.assertEquals("response", response.name)
        }
        @Test fun tracingInstalls() = runTest {
          val configured = canvas { tracing { OpenTelemetry.noop() } }
          kotlin.test.assertEquals("published", configured.create().compose(singleTile { "published" }))
        }
      }
      """.trimIndent(),
    )
  }
  val result = run(consumer, "test", "dependencies", "--configuration", "testRuntimeClasspath")
  assertEquals(TaskOutcome.SUCCESS, result.task(":test")?.outcome)
  assertTrue(result.output.contains("org.buildmosaic:mosaic-core:$version"), result.output)
  assertTrue(result.output.contains("org.buildmosaic:mosaic-test:$version"), result.output)
  assertTrue(result.output.contains("org.buildmosaic:mosaic-opentelemetry:$version"), result.output)
  assertTrue(result.output.contains("io.opentelemetry:opentelemetry-api:"), result.output)
  assertTrue(!result.output.contains("io.opentelemetry:opentelemetry-sdk:"), result.output)
  assertTrue(!result.output.contains("io.opentelemetry:opentelemetry-extension-kotlin:"), result.output)
}

private fun run(
  project: File,
  vararg arguments: String,
): org.gradle.testkit.runner.BuildResult =
  GradleRunner.create().withProjectDir(project)
    .withArguments(
      *arguments,
      "--stacktrace",
      "--gradle-user-home",
      File(System.getProperty("user.home"), ".gradle").absolutePath,
    )
    .build()

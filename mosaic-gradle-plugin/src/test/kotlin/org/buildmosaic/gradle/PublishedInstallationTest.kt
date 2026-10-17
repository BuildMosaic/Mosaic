@file:Suppress("LongMethod", "MaxLineLength", "ktlint:standard:max-line-length")

package org.buildmosaic.gradle

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Tag
import java.io.File
import java.nio.file.Files
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
    runPublished(
      repositoryRoot,
      ":mosaic-core:publishAllPublicationsToInstallTestRepository",
      ":mosaic-test:publishAllPublicationsToInstallTestRepository",
      ":mosaic-opentelemetry:publishAllPublicationsToInstallTestRepository",
      ":mosaic-bom:publishAllPublicationsToInstallTestRepository",
      ":mosaic-compiler-plugin:publishAllPublicationsToInstallTestRepository",
      ":mosaic-gradle-plugin:publishAllPublicationsToInstallTestRepository",
      "-Pmosaic.runtime.version=$version",
      "-Pmosaic.analysis.version=$version",
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
    val compilerPom =
      maven.resolve("org/buildmosaic/mosaic-compiler-plugin/$version/mosaic-compiler-plugin-$version.pom").readText()
    assertTrue(!compilerPom.contains("<artifactId>mosaic-analysis-core</artifactId>"), compilerPom)
    assertTrue(!compilerPom.contains("kotlin-compiler-embeddable"), compilerPom)
    verifyAnalysisConsumers(workspace, maven, version, version, listOf("2.4.21"))
    verifyRuntimeConsumer(workspace, maven, version, useBom = false)
    verifyRuntimeConsumer(workspace, maven, version, useBom = true)
  }
}

/** Exact published Runtime consumer, shared with release certification. */
@Tag("release-compatibility")
class RuntimeInstallationTest {
  @Test
  fun `Runtime consumers run on Java 17`() {
    verifyRuntimeConsumer(
      Files.createTempDirectory("mosaic-runtime-").toFile(),
      File(System.getProperty("mosaic.test.repository")),
      System.getProperty("mosaic.test.runtimeVersion"),
      useBom = false,
      kotlinVersion = System.getProperty("mosaic.test.kotlinVersions"),
    )
  }
}

internal fun verifyRuntimeConsumer(
  workspace: File,
  maven: File,
  version: String,
  useBom: Boolean,
  kotlinVersion: String = "2.4.21",
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
    plugins { kotlin("jvm") version "$kotlinVersion"; application }
    kotlin { jvmToolchain(17) }
    application { mainClass.set("certification.RuntimeConsumerKt") }
    dependencies {
      $platform
      implementation("$core")
      implementation("$tracing")
      implementation("$test")
    }
    """.trimIndent(),
  )
  val repositoryRoot = File(System.getProperty("user.dir")).parentFile
  val sources = consumer.resolve("src/main/kotlin").apply { mkdirs() }
  repositoryRoot.resolve("compatibility/fixtures/RuntimeConsumer.kt").copyTo(sources.resolve("RuntimeConsumer.kt"))
  for (name in listOf("observer-composite.kt", "public-api-keys.kt", "public-api-roots.kt", "execution-catalog.kt")) {
    repositoryRoot.resolve("mosaic-compiler-plugin/src/test/resources/fixtures/$name").copyTo(sources.resolve(name))
  }
  val result = runPublished(consumer, "run", "dependencies", "--configuration", "runtimeClasspath")
  assertEquals(TaskOutcome.SUCCESS, result.task(":run")?.outcome)
  assertTrue(result.output.contains("Runtime public API consumer passed on JVM 17"), result.output)
  assertTrue(result.output.contains("org.buildmosaic:mosaic-core:$version"), result.output)
  assertTrue(result.output.contains("org.buildmosaic:mosaic-test:$version"), result.output)
  assertTrue(result.output.contains("org.buildmosaic:mosaic-opentelemetry:$version"), result.output)
  assertTrue(result.output.contains("io.opentelemetry:opentelemetry-api:"), result.output)
  assertTrue(!result.output.contains("io.opentelemetry:opentelemetry-sdk:"), result.output)
  assertTrue(!result.output.contains("io.opentelemetry:opentelemetry-extension-kotlin:"), result.output)
}

internal fun runPublished(
  project: File,
  vararg arguments: String,
  expectFailure: Boolean = false,
): org.gradle.testkit.runner.BuildResult {
  println("MOSAIC_PUBLISHED_GRADLE_INVOCATION: ${arguments.joinToString(" ")}")
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
    .let { if (expectFailure) it.buildAndFail() else it.build() }
}

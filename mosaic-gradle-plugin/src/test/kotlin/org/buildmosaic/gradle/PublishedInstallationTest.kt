@file:Suppress("LongMethod", "MaxLineLength", "ktlint:standard:max-line-length")

package org.buildmosaic.gradle

import org.gradle.testkit.runner.GradleRunner
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue

/** Verifies the artifacts and dependency metadata that consumers receive. */
class PublishedInstallationTest {
  @Test
  fun `publications contain artifacts and metadata`() {
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
  }
}

internal fun runPublished(
  project: File,
  vararg arguments: String,
  expectFailure: Boolean = false,
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

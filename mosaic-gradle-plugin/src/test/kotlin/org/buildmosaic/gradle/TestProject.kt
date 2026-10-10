@file:Suppress(
  "LongMethod",
  "FunctionMaxLength",
  "NestedBlockDepth",
  "MaxLineLength",
  "ktlint:standard:max-line-length",
)

package org.buildmosaic.gradle

import org.buildmosaic.analysis.SUMMARY_PATH
import org.buildmosaic.analysis.SummaryCodec
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
import java.io.File
import java.security.MessageDigest
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import kotlin.test.assertEquals

internal fun project(
  root: File,
  name: String,
  pluginJar: File,
  jars: List<File>,
  roots: List<String> = emptyList(),
  role: String = "APPLICATION",
  enforcement: String = "STANDARD",
): File =
  File(root, name).apply {
    mkdirs()
    File(this, "settings.gradle.kts").writeText(
      """
      pluginManagement {
        repositories { gradlePluginPortal(); mavenCentral() }
        resolutionStrategy.eachPlugin {
          if (requested.id.id == "org.jetbrains.kotlin.jvm") useModule("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.21")
        }
      }
      rootProject.name = "$name"
      """.trimIndent(),
    )
    val dependencies = jars.joinToString("\n") { "implementation(files(\"${it.invariantSeparatorsPath}\"))" }
    val configuredRoots = roots.joinToString("\n") { "roots.add(\"$it\")" }
    File(this, "build.gradle.kts").writeText(
      """
      plugins {
        kotlin("jvm") version "2.4.21"
        id("org.buildmosaic.analysis")
      }
      group = "fixture"
      ${fixtureRepositories(pluginJar)}
      dependencies {
        $dependencies
        implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
      }
      mosaicAnalysis {
        role = org.buildmosaic.gradle.MosaicAnalysisRole.$role
        enforcement = org.buildmosaic.gradle.MosaicAnalysisEnforcement.$enforcement
        $configuredRoots
      }
      """.trimIndent(),
    )
  }

// A flat directory alone loses to Maven metadata for the same published version.
// Keep compiler coordinates exclusive to the checkout; runtime JARs use files(...).
internal fun fixtureRepositories(pluginJar: File): String =
  """
  repositories {
    exclusiveContent {
      forRepository {
        flatDir { dirs("${pluginJar.parentFile.invariantSeparatorsPath}") }
      }
      filter { includeModule("org.buildmosaic", "mosaic-compiler-plugin") }
    }
    mavenCentral()
  }
  """.trimIndent()

internal fun assertFreshEquivalent(
  project: File,
  incrementalReport: String,
  expectFailure: Boolean = false,
) {
  val summary = SummaryCodec.decode(File(project, "build/mosaic-analysis/main/summary.json").readBytes())
  val fresh = run(project, "clean build", expectFailure = expectFailure)
  assertEquals(TaskOutcome.SUCCESS, fresh.task(":extractMosaicMain")?.outcome)
  assertEquals(summary, SummaryCodec.decode(File(project, "build/mosaic-analysis/main/summary.json").readBytes()))
  assertEquals(incrementalReport, File(project, "build/reports/mosaic-analysis/main.txt").readText())
}

internal fun run(
  project: File,
  task: String,
  expectFailure: Boolean = false,
  configurationCache: Boolean = false,
  buildCache: Boolean = false,
  pluginClasspath: List<File>? = null,
): org.gradle.testkit.runner.BuildResult {
  // Count actual builds, including expected failures, in the captured JUnit output.
  System.err.println("MOSAIC_TESTKIT_INVOCATION")
  val runner =
    GradleRunner.create()
      .withProjectDir(project)
      .withArguments(
        task.split(' ') +
          listOf(
            "--offline",
            if (configurationCache) "--configuration-cache" else "--no-configuration-cache",
            if (buildCache) "--build-cache" else "--no-build-cache",
            "--stacktrace",
            "--gradle-user-home",
            File(System.getProperty("user.home"), ".gradle").absolutePath,
            "--info",
          ),
      )
      .withPluginClasspath()
  if (pluginClasspath != null) runner.withPluginClasspath(pluginClasspath)
  val result = if (expectFailure) runner.buildAndFail() else runner.build()
  val launches = result.output.lineSequence().count { it.contains("Mosaic K2 extraction launched") }
  assertEquals(0, launches, "Production integration launched a separate Mosaic K2 compiler")
  return result
}

internal fun jar(project: File): File = File(project, "build/libs/${project.name}.jar")

internal fun sha256(file: File): String =
  MessageDigest.getInstance(
    "SHA-256",
  ).digest(file.readBytes()).joinToString("") {
    "%02x".format(it)
  }

internal fun summaryHash(jar: File): String =
  JarFile(jar).use { archive ->
    val bytes = archive.getInputStream(archive.getJarEntry(SUMMARY_PATH)).readBytes()
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
  }

internal fun rewriteSummary(
  jar: File,
  replacement: ByteArray?,
) {
  val copy = File(jar.parentFile, "rewritten-${jar.name}")
  JarFile(jar).use { input ->
    JarOutputStream(copy.outputStream()).use { output ->
      var found = false
      input.entries().asSequence().forEach { entry ->
        if (entry.name == SUMMARY_PATH && replacement == null) return@forEach
        if (entry.name == SUMMARY_PATH) found = true
        output.putNextEntry(JarEntry(entry.name))
        if (!entry.isDirectory) {
          if (entry.name == SUMMARY_PATH) {
            output.write(
              replacement,
            )
          } else {
            input.getInputStream(entry).use { it.copyTo(output) }
          }
        }
        output.closeEntry()
      }
      if (!found && replacement != null) {
        output.putNextEntry(JarEntry(SUMMARY_PATH))
        output.write(replacement)
        output.closeEntry()
      }
    }
  }
  copy.copyTo(jar, overwrite = true)
  copy.delete()
}

internal fun mosaicVersion(): String =
  File(System.getProperty("user.dir")).parentFile.resolve("gradle.properties").readLines()
    .first { it.startsWith("mosaic.version=") }.substringAfter('=')

internal fun dependencySummary(
  jar: File,
  revisions: Set<Int>,
) {
  JarOutputStream(jar.outputStream()).use { output ->
    output.putNextEntry(JarEntry(org.buildmosaic.analysis.SUMMARY_PATH))
    output.write(
      SummaryCodec.encode(org.buildmosaic.analysis.ModuleContract("dependency"), coreAnalysisRevisions = revisions),
    )
    output.closeEntry()
  }
}

internal fun coreManifest(
  original: File,
  destination: File,
  revision: String?,
  release: String = "fixture",
) {
  destination.parentFile.mkdirs()
  JarFile(original).use { input ->
    val manifest = java.util.jar.Manifest(input.manifest)
    manifest.mainAttributes.putValue("Implementation-Version", release)
    manifest.mainAttributes.remove(java.util.jar.Attributes.Name("Mosaic-Core-Analysis-Revision"))
    revision?.let { manifest.mainAttributes.putValue("Mosaic-Core-Analysis-Revision", it) }
    JarOutputStream(destination.outputStream(), manifest).use { output ->
      input.entries().asSequence().filter { it.name != "META-INF/MANIFEST.MF" }.forEach { entry ->
        output.putNextEntry(JarEntry(entry.name))
        input.getInputStream(entry).use { it.copyTo(output) }
        output.closeEntry()
      }
    }
  }
}

/** Compile a private analyzer artifact from the production policy source with a two-revision support set. */
internal fun fixturePolicy(
  root: File,
  repository: File,
): File {
  val production =
    File(
      repository,
      "mosaic-analysis-core/src/main/kotlin/org/buildmosaic/analysis/CoreAnalysisRevision.kt",
    ).readText()
  val fixture = production.replace("setOf(1)", "setOf(1, 2)")
  check(fixture != production) { "Fixture must declare its independent support set" }
  val source =
    File(root, "policy/CoreAnalysisRevision.kt").apply {
      parentFile.mkdirs()
      writeText(fixture)
    }
  val classes = File(root, "policy/classes")
  assertEquals(
    ExitCode.OK,
    K2JVMCompiler().exec(
      System.err,
      "-no-stdlib",
      "-no-reflect",
      "-classpath",
      File(Unit::class.java.protectionDomain.codeSource.location.toURI()).absolutePath,
      "-d",
      classes.absolutePath,
      source.absolutePath,
    ),
  )
  return classes
}

internal fun analyzerFixture(
  original: File,
  destination: File,
  policyClasses: File,
) {
  destination.parentFile.mkdirs()
  JarFile(original).use { input ->
    JarOutputStream(destination.outputStream(), input.manifest).use { output ->
      input.entries().asSequence().filter { it.name != "META-INF/MANIFEST.MF" }.forEach { entry ->
        output.putNextEntry(JarEntry(entry.name))
        val replacement = File(policyClasses, entry.name)
        if (replacement.isFile && entry.name.endsWith(".class")) {
          output.write(replacement.readBytes())
        } else {
          input.getInputStream(entry).use { it.copyTo(output) }
        }
        output.closeEntry()
      }
    }
  }
}

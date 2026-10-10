@file:Suppress("LongMethod", "LargeClass", "MaxLineLength", "ktlint:standard:max-line-length")

package org.buildmosaic.gradle

import org.buildmosaic.analysis.CompilerVersionAdmission
import org.buildmosaic.analysis.SourceShardCodec
import org.buildmosaic.analysis.SummaryCodec
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Tag
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Compact production installation matrix: real plugin marker and Maven artifacts, without TestKit injection. */
@Tag("release-compatibility")
class IntrospectorSelectionInstallationTest {
  @Test
  fun `published plugin selects all measured compilers`() {
    val repository = File(System.getProperty("user.dir")).parentFile
    val workspace =
      repository.resolve(
        "mosaic-gradle-plugin/build/reports/introspector-installation/${System.currentTimeMillis()}",
      ).apply {
        mkdirs()
      }
    val maven = workspace.resolve("maven")
    val analysisVersion = "0.0.0-install-analysis"
    val existingRuntime = System.getProperty("mosaic.test.runtimeRepository")?.let(::File)
    val runtimeVersion =
      existingRuntime?.resolve("org/buildmosaic/mosaic-core")?.listFiles()?.single { it.isDirectory }?.name
        ?: "0.0.0-install-runtime"
    val runtimeTasks =
      if (existingRuntime == null) {
        listOf(
          ":mosaic-core:publishAllPublicationsToInstallTestRepository",
          ":mosaic-test:publishAllPublicationsToInstallTestRepository",
          ":mosaic-opentelemetry:publishAllPublicationsToInstallTestRepository",
        )
      } else {
        emptyList()
      }
    val publication =
      runPublished(
        repository,
        *runtimeTasks.toTypedArray(),
        "publishAnalysisToInstallTestRepository",
        "-Pmosaic.runtime.version=$runtimeVersion",
        "-Pmosaic.analysis.version=$analysisVersion",
        "-Pmosaic.installTestRepository=${maven.absolutePath}",
        "--no-configuration-cache",
      )
    workspace.resolve("publication.log").writeText(publication.output)
    existingRuntime?.copyRecursively(maven, overwrite = true)
    val artifacts =
      listOf(
        "mosaic-gradle-plugin",
        "mosaic-compiler-plugin",
        "mosaic-compiler-plugin-kotlin-2.3.0",
        "mosaic-compiler-plugin-kotlin-2.3.20",
      )
    val hashes =
      artifacts.associateWith {
          id ->
        sha256(maven.resolve("org/buildmosaic/$id/$analysisVersion/$id-$analysisVersion.jar"))
      }
    artifacts.forEach { id ->
      val dir = maven.resolve("org/buildmosaic/$id/$analysisVersion")
      listOf("jar", "pom", "sources.jar", "javadoc.jar").forEach { suffix ->
        val name = if (suffix.contains('.')) "$id-$analysisVersion-$suffix" else "$id-$analysisVersion.$suffix"
        assertTrue(dir.resolve(name).isFile, name)
      }
    }
    val versions = installationCompilers()
    workspace.resolve("results.txt").writeText("")
    versions.forEach { compiler ->
      val app = workspace.resolve("consumer-$compiler").apply { mkdirs() }
      // Twelve KGP classloaders need more metaspace than TestKit's single-version default.
      app.resolve("gradle.properties").writeText("org.gradle.jvmargs=-Xmx1g -XX:MaxMetaspaceSize=1g\n")
      app.resolve("settings.gradle.kts").writeText(
        """
        pluginManagement { repositories { maven(url = uri("${maven.invariantSeparatorsPath}")); gradlePluginPortal() } }
        dependencyResolutionManagement {
          repositories {
            exclusiveContent {
              forRepository { maven(url = uri("${maven.invariantSeparatorsPath}")) }
              filter { includeGroup("org.buildmosaic") }
            }
            mavenCentral()
          }
        }
        rootProject.name = "app"
        """.trimIndent(),
      )
      app.resolve("build.gradle.kts").writeText(
        """
        plugins {
          kotlin("jvm") version "$compiler"
          id("org.buildmosaic.analysis") version "$analysisVersion"
          application
        }
        kotlin { jvmToolchain(17) }
        dependencies { implementation("org.buildmosaic:mosaic-core:$runtimeVersion") }
        application { mainClass.set("app.MainKt") }
        mosaicAnalysis {
          roots.add("app.safe()")
          roots.add("app.unknown()")
          if (providers.gradleProperty("fixture.missing").isPresent) roots.add("app.missing()")
        }
        tasks.register("selectedIntrospector") {
          doLast {
            val selected = configurations.getByName("kotlinCompilerPluginClasspathMain").resolvedConfiguration.resolvedArtifacts
              .filter { it.moduleVersion.id.group == "org.buildmosaic" }
            check(selected.size == 1)
            selected.forEach { println("MOSAIC_SELECTED=" + it.moduleVersion.id + ":" + it.file.absolutePath) }
          }
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
          fun main() = runBlocking { check(safe() == "ready"); println("MOSAIC_JAVA17=" + System.getProperty("java.version")) }
          """.trimIndent(),
        )
      }
      val result = runPublished(app, "build", "run", "mosaicGraph", "selectedIntrospector", "--no-configuration-cache")
      app.resolve("installation.log").writeText(result.output)
      assertEquals(TaskOutcome.SUCCESS, result.task(":verifyMosaicMain")?.outcome)
      assertTrue(result.output.contains("MOSAIC_JAVA17=17."), result.output)
      assertFalse(result.output.contains("MOSAIC_COMPATIBILITY_PROBE"), result.output)
      val api = CompilerVersionAdmission.compilerApi(compiler)
      val id = CompilerVersionAdmission.artifactId(api)
      val selected = result.output.lines().single { it.startsWith("MOSAIC_SELECTED=") }
      assertTrue(selected.startsWith("MOSAIC_SELECTED=org.buildmosaic:$id:$analysisVersion:"), selected)
      assertEquals(hashes.getValue(id), sha256(File(selected.substringAfter("$analysisVersion:"))))
      val summary = SummaryCodec.decode(app.resolve("build/mosaic-analysis/main/summary.json").readBytes())
      assertEquals(compiler, summary.producer.compilerVersion)
      assertEquals(analysisVersion, summary.producer.analysisVersion)
      val graph = app.resolve("build/reports/mosaic-analysis/graph.md").readText()
      assertTrue(graph.contains("## Root: app.safe()") && graph.contains("Status: VERIFIED"), graph)
      assertTrue(graph.contains("## Root: app.unknown()") && graph.contains("Status: UNVERIFIED"), graph)
      val failed =
        GradleRunner.create().withProjectDir(app).withArguments(
          "verifyMosaicMain",
          "-Pfixture.missing=true",
          "--no-configuration-cache",
          "--max-workers=2",
          "-Porg.gradle.java.installations.paths=${System.getProperty("mosaic.test.javaInstallations", "")}",
          "--gradle-user-home",
          File(System.getProperty("user.home"), ".gradle").absolutePath,
        ).buildAndFail()
      app.resolve("missing.log").writeText(failed.output)
      assertEquals(TaskOutcome.FAILED, failed.task(":verifyMosaicMain")?.outcome)
      assertTrue(app.resolve("build/reports/mosaic-analysis/main.txt").readText().contains("MISSING REQUIRED_LOOKUP"))
      workspace.resolve("results.txt").appendText("$compiler $api $id ${hashes.getValue(id)} PASS\n")
    }
    if ("2.4.20" in versions) verifyCompilerChanges(workspace.resolve("consumer-2.4.20"), analysisVersion)
    if ("2.3.0" in versions) {
      verifyRuntimeConsumer(
        workspace,
        maven,
        runtimeVersion,
        useBom = false,
        kotlinVersion = "2.3.0",
      )
    }
  }

  private fun installationCompilers(): List<String> {
    val boundaries = listOf("2.2.0", "2.3.0", "2.3.20", "2.4.20", "2.4.21")
    val requested =
      System.getProperty("mosaic.test.kotlinVersions")?.split(',')
        ?: CompilerVersionAdmission.supportedCompilers.toList()
    return boundaries.filter { it in requested } + (requested - boundaries)
  }

  private fun verifyCompilerChanges(
    app: File,
    analysisVersion: String,
  ) {
    app.resolve("settings.gradle.kts").appendText(
      "\nbuildCache { local { directory = file(\"${app.parentFile.resolve(
        "build-cache",
      ).invariantSeparatorsPath}\") } }\n",
    )
    val build = app.resolve("build.gradle.kts")
    val original = build.readText()
    build.appendText(
      """

      @OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class, org.jetbrains.kotlin.buildtools.api.ExperimentalBuildToolsApi::class)
      fun selectCompiler() {
        kotlin.compilerVersion.set(providers.gradleProperty("fixture.compiler").getOrElse("2.4.20"))
      }
      selectCompiler()
      """.trimIndent(),
    )
    app.resolve("src/main/kotlin/Other.kt").writeText("package app\nclass Other")
    val selectableBuild = build.readText()
    // KGP owns its scripting plugin; cross-profile changes use matching KGP and compiler versions.
    for (compiler in listOf("2.4.20", "2.3.20", "2.3.21")) {
      build.writeText(
        selectableBuild.replace("kotlin(\"jvm\") version \"2.4.20\"", "kotlin(\"jvm\") version \"$compiler\""),
      )
      val result =
        runPublished(
          app,
          "verifyMosaicMain",
          "run",
          "-Pfixture.compiler=$compiler",
          "--build-cache",
          "--configuration-cache",
        )
      assertEquals(TaskOutcome.SUCCESS, result.task(":compileKotlin")?.outcome)
      assertCompilerProvenance(app, compiler)
      app.resolve("switch-$compiler.log").writeText(result.output)
    }
    build.writeText(selectableBuild.replace("kotlin(\"jvm\") version \"2.4.20\"", "kotlin(\"jvm\") version \"2.3.20\""))
    val restored =
      runPublished(
        app,
        "clean",
        "verifyMosaicMain",
        "-Pfixture.compiler=2.3.20",
        "--build-cache",
        "--configuration-cache",
      )
    assertEquals(TaskOutcome.FROM_CACHE, restored.task(":compileKotlin")?.outcome)
    assertCompilerProvenance(app, "2.3.20")
    app.resolve("cache-restore.log").writeText(restored.output)
    build.writeText(selectableBuild)
    val effective =
      runPublished(
        app,
        "verifyMosaicMain",
        "run",
        "-Pfixture.compiler=2.4.21",
        "--build-cache",
        "--configuration-cache",
      )
    assertCompilerProvenance(app, "2.4.21")
    app.resolve("effective-compiler.log").writeText(effective.output)
    val repeated =
      runPublished(
        app,
        "verifyMosaicMain",
        "run",
        "-Pfixture.compiler=2.4.21",
        "--build-cache",
        "--configuration-cache",
      )
    assertTrue(repeated.output.contains("Configuration cache entry reused"), repeated.output)
    assertCompilerProvenance(app, "2.4.21")
    val unsupported = failedBuild(app, "-Pfixture.compiler=2.4.22")
    assertTrue(unsupported.contains("has not verified Kotlin compiler 2.4.22"), unsupported)
    build.writeText(selectableBuild.replace("kotlin(\"jvm\") version \"2.4.20\"", "kotlin(\"jvm\") version \"2.3.20\""))
    build.appendText(
      """

      configurations.named("kotlinCompilerPluginClasspathMain") {
        resolutionStrategy.dependencySubstitution {
          substitute(module("org.buildmosaic:mosaic-compiler-plugin-kotlin-2.3.20"))
            .using(module("org.buildmosaic:mosaic-compiler-plugin:$analysisVersion"))
        }
      }
      """.trimIndent(),
    )
    val wrongProfile = failedBuild(app, "-Pfixture.compiler=2.3.20")
    assertTrue(wrongProfile.contains("compiler API 2.4.20 does not support Kotlin compiler 2.3.20"), wrongProfile)
    app.resolve("wrong-profile.log").writeText(wrongProfile)
    build.writeText(original)
  }

  private fun assertCompilerProvenance(
    app: File,
    compiler: String,
  ) {
    assertEquals(
      compiler,
      SummaryCodec.decode(app.resolve("build/mosaic-analysis/main/summary.json").readBytes()).producer.compilerVersion,
    )
    val shards = app.resolve("build/mosaic-analysis/main/shards").walkTopDown().filter { it.isFile }.toList()
    assertEquals(2, shards.size)
    shards.forEach { assertEquals(compiler, SourceShardCodec.decode(it.readBytes()).compilerVersion) }
  }

  private fun failedBuild(
    app: File,
    option: String,
  ): String =
    GradleRunner.create().withProjectDir(app).withArguments(
      "verifyMosaicMain",
      option,
      "--no-configuration-cache",
      "--max-workers=2",
      "-Porg.gradle.java.installations.paths=${System.getProperty("mosaic.test.javaInstallations", "")}",
      "--gradle-user-home",
      File(System.getProperty("user.home"), ".gradle").absolutePath,
    ).buildAndFail().output
}

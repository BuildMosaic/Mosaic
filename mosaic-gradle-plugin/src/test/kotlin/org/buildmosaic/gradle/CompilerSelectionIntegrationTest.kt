@file:Suppress("LongMethod", "MaxLineLength", "ktlint:standard:max-line-length")

package org.buildmosaic.gradle

import org.buildmosaic.analysis.SourceShardCodec
import org.buildmosaic.analysis.SummaryCodec
import org.gradle.testkit.runner.TaskOutcome
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CompilerSelectionIntegrationTest {
  @Test
  fun `compiler switching and ABI selection`() {
    val workspace = Files.createTempDirectory("mosaic-compiler-selection-").toFile()
    val maven = publishInstallation(workspace)
    val version = mosaicVersion()
    val app = workspace.resolve("app").apply { mkdirs() }
    app.resolve("settings.gradle.kts").writeText(
      """
      pluginManagement { repositories { maven(url = uri("${maven.invariantSeparatorsPath}")); mavenCentral(); gradlePluginPortal() } }
      dependencyResolutionManagement { repositories { maven(url = uri("${maven.invariantSeparatorsPath}")); mavenCentral() } }
      buildCache { local { directory = file("${workspace.resolve("cache").invariantSeparatorsPath}") } }
      rootProject.name = "app"
      """.trimIndent(),
    )
    app.resolve("gradle.properties").writeText("org.gradle.jvmargs=-Xmx1g -XX:MaxMetaspaceSize=1g\n")
    val build = app.resolve("build.gradle.kts")

    fun select(kgp: String) {
      build.writeText(
        """
        plugins {
          kotlin("jvm") version "$kgp"
          id("org.buildmosaic.analysis") version "$version"
        }
        dependencies { implementation("org.buildmosaic:mosaic-core:$version") }
        mosaicAnalysis { roots.add("app.entry()") }
        @OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class, org.jetbrains.kotlin.buildtools.api.ExperimentalBuildToolsApi::class)
        fun selectCompiler() { kotlin.compilerVersion.set(providers.gradleProperty("fixture.compiler").get()) }
        selectCompiler()
        """.trimIndent(),
      )
    }
    app.resolve("src/main/kotlin/Main.kt").apply {
      parentFile.mkdirs()
      writeText(
        """
        package app
        import org.buildmosaic.core.*
        import org.buildmosaic.core.injection.*
        suspend fun entry(): String = canvas { instance("ready") }.withMosaic { source<String>() }
        """.trimIndent(),
      )
    }
    app.resolve("src/main/kotlin/Other.kt").writeText("package app\nclass Other")

    fun verify(
      compiler: String,
      clean: Boolean = false,
    ): org.gradle.testkit.runner.BuildResult {
      val result =
        runPublished(
          app,
          *if (clean) arrayOf("clean", "verifyMosaicMain") else arrayOf("verifyMosaicMain"),
          "-Pfixture.compiler=$compiler",
          "--build-cache",
          "--configuration-cache",
        )
      val summary = SummaryCodec.decode(app.resolve("build/mosaic-analysis/main/summary.json").readBytes())
      assertEquals(compiler, summary.producer.compilerVersion)
      val shards = app.resolve("build/mosaic-analysis/main/shards").walkTopDown().filter { it.isFile }.toList()
      assertEquals(2, shards.size)
      shards.forEach { assertEquals(compiler, SourceShardCodec.decode(it.readBytes()).compilerVersion) }
      return result
    }
    for (compiler in listOf("2.4.20", "2.3.20", "2.3.21")) {
      select(compiler)
      assertEquals(TaskOutcome.SUCCESS, verify(compiler).task(":compileKotlin")?.outcome)
    }
    select("2.3.20")
    assertEquals(TaskOutcome.FROM_CACHE, verify("2.3.20", clean = true).task(":compileKotlin")?.outcome)
    select("2.4.20")
    verify("2.4.21")
    assertTrue(verify("2.4.21").output.contains("Configuration cache entry reused"))
    val unsupported = runPublished(app, "verifyMosaicMain", "-Pfixture.compiler=999.0.0", expectFailure = true)
    assertTrue(unsupported.output.contains("does not support Kotlin compiler 999.0.0"), unsupported.output)
    select("2.3.20")
    build.appendText(
      """

      configurations.named("kotlinCompilerPluginClasspathMain") {
        resolutionStrategy.dependencySubstitution {
          substitute(module("org.buildmosaic:mosaic-compiler-plugin-kotlin-2.3.20"))
            .using(module("org.buildmosaic:mosaic-compiler-plugin:$version"))
        }
      }
      """.trimIndent(),
    )
    val wrong = runPublished(app, "verifyMosaicMain", "-Pfixture.compiler=2.3.20", expectFailure = true)
    assertTrue(wrong.output.contains("compiler API 2.4.20 does not support Kotlin compiler 2.3.20"), wrong.output)
  }
}

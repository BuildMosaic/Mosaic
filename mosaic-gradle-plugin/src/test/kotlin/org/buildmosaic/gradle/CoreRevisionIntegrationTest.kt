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
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@Suppress("LargeClass") // One cache lifecycle proves restore, switch-back, and failure recovery without repeated setup.
class CoreRevisionIntegrationTest {
  @Test
  fun `manifest semantics invalidate every source with stable analyzer bytes`() {
    val root = Files.createTempDirectory("mosaic-semantic-cache").toFile()
    val repository = File(System.getProperty("user.dir")).parentFile
    val productionCompiler =
      File(repository, "mosaic-compiler-plugin/build/libs/mosaic-compiler-plugin-${mosaicVersion()}.jar")
    val compiler = File(root, "analyzer/${productionCompiler.name}")
    val fixturePolicy = fixturePolicy(root, repository)
    analyzerFixture(productionCompiler, compiler, fixturePolicy)
    val pluginClasspath =
      Properties().apply {
        this@CoreRevisionIntegrationTest.javaClass.classLoader.getResourceAsStream(
          "plugin-under-test-metadata.properties",
        )!!.use(::load)
      }.getProperty("implementation-classpath").split(File.pathSeparator).map(::File).map { artifact ->
        if (artifact.name == "mosaic-gradle-plugin-${mosaicVersion()}.jar") {
          File(root, "analyzer/${artifact.name}").also { analyzerFixture(artifact, it, fixturePolicy) }
        } else {
          artifact
        }
      }
    val productionCore = File(repository, "mosaic-core/build/libs/mosaic-core-${mosaicVersion()}.jar")
    val core = File(root, "selected-core.jar")
    coreManifest(productionCore, core, "1", "first-release")
    val cache = File(root, "cache")

    fun workspace(parent: File): File =
      project(parent, "app", compiler, listOf(core)).apply {
        File(
          this,
          "settings.gradle.kts",
        ).appendText("\nbuildCache { local { directory = file(\"${cache.invariantSeparatorsPath}\") } }\n")
        File(this, "build.gradle.kts").appendText(
          "\n" +
            """
            tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile>("compileKotlin") {
              pluginOptions.add(org.jetbrains.kotlin.gradle.plugin.CompilerPluginConfig().apply {
                addPluginArgument("org.buildmosaic.analysis", org.jetbrains.kotlin.gradle.plugin.FilesSubpluginOption(
                  "probe", listOf(layout.buildDirectory.file("mosaic-analysis/main/probe.txt").get().asFile)
                ))
              })
            }
            """.trimIndent(),
        )
        listOf("Tile.kt", "Unrelated.kt", "Caller.kt").forEach { name ->
          File(this, "src/main/kotlin/$name").apply {
            parentFile.mkdirs()
            writeText(
              when (name) {
                "Tile.kt" -> "package app\nimport org.buildmosaic.core.*\nimport org.buildmosaic.core.injection.*\nval Tile = singleTile { 1 }\nsuspend fun entry() = canvas {}.withMosaic { compose(Tile) }"
                "Unrelated.kt" -> "package app\nfun unrelated() = 1"
                else -> "package app\nfun caller() = unrelated()"
              },
            )
          }
        }
      }
    val app = workspace(File(root, "original"))

    fun build(
      workspace: File = app,
      task: String = "jar verifyMosaicMain mosaicGraph",
      failure: Boolean = false,
      cached: Boolean = false,
    ) = run(
      workspace,
      task,
      expectFailure = failure,
      configurationCache = true,
      buildCache = cached,
      pluginClasspath = pluginClasspath,
    )

    fun summary(workspace: File = app) =
      SummaryCodec.decode(
        File(workspace, "build/mosaic-analysis/main/summary.json").readBytes(),
      )

    fun observed(workspace: File = app): Set<String> =
      File(workspace, "build/mosaic-analysis/main/probe.txt").readLines()
        .filter { it.startsWith("FILE|") }.map { File(it.removePrefix("FILE|")).name }.toSet()

    fun clearProbe(workspace: File = app) {
      File(workspace, "build/mosaic-analysis/main/probe.txt").delete()
    }

    val initial = build(cached = true)
    assertEquals(TaskOutcome.SUCCESS, initial.task(":compileKotlin")?.outcome)
    assertEquals(setOf("Tile.kt", "Unrelated.kt", "Caller.kt"), observed())
    val firstSummary = summary()
    val analyzers = (pluginClasspath + compiler).filter { it.parentFile == compiler.parentFile }
    val analyzerHashes = analyzers.associateWith(::sha256)
    clearProbe()
    val identical = build()
    assertEquals(TaskOutcome.UP_TO_DATE, identical.task(":compileKotlin")?.outcome)
    assertTrue(identical.output.contains("Configuration cache entry reused"), identical.output)
    coreManifest(productionCore, core, "1", "different-release")
    val release = build()
    assertEquals(TaskOutcome.UP_TO_DATE, release.task(":compileKotlin")?.outcome)
    assertEquals(TaskOutcome.UP_TO_DATE, release.task(":extractMosaicMain")?.outcome)
    assertEquals(TaskOutcome.UP_TO_DATE, release.task(":verifyMosaicMain")?.outcome)
    assertEquals(TaskOutcome.UP_TO_DATE, release.task(":mosaicGraph")?.outcome)
    assertTrue(release.output.contains("Configuration cache entry reused"), release.output)
    assertFalse(File(app, "build/mosaic-analysis/main/probe.txt").exists())
    assertEquals(firstSummary, summary())

    // Ordinary body edits are a control: Kotlin normally leaves both unrelated files untouched.
    File(app, "src/main/kotlin/Unrelated.kt").writeText("package app\nfun unrelated() = 2")
    build(cached = true)
    assertEquals(setOf("Unrelated.kt"), observed())
    clearProbe()
    val sourceHashes = File(app, "src/main/kotlin").walkTopDown().filter { it.isFile }.associateWith(::sha256)
    coreManifest(productionCore, core, "2", "different-release")
    val switched = build(cached = true)
    assertEquals(TaskOutcome.SUCCESS, switched.task(":compileKotlin")?.outcome)
    assertEquals(TaskOutcome.SUCCESS, switched.task(":extractMosaicMain")?.outcome)
    assertEquals(TaskOutcome.SUCCESS, switched.task(":verifyMosaicMain")?.outcome)
    assertEquals(TaskOutcome.SUCCESS, switched.task(":mosaicGraph")?.outcome)
    assertEquals(analyzerHashes, analyzers.associateWith(::sha256))
    assertEquals(sourceHashes, sourceHashes.keys.associateWith(::sha256))
    assertEquals(setOf("Tile.kt", "Unrelated.kt", "Caller.kt"), observed())
    val secondSummary = File(app, "build/mosaic-analysis/main/summary.json").readBytes()
    assertTrue(secondSummary.decodeToString().contains("\"coreAnalysisRevisions\":[2]"))
    val secondReport = File(app, "build/reports/mosaic-analysis/main.txt").readText()
    assertTrue(secondReport.contains("FULLY VERIFIED"), secondReport)
    val clean = build(task = "clean jar verifyMosaicMain mosaicGraph")
    assertEquals(TaskOutcome.SUCCESS, clean.task(":compileKotlin")?.outcome)
    assertTrue(secondSummary.contentEquals(File(app, "build/mosaic-analysis/main/summary.json").readBytes()))
    assertEquals(secondReport, File(app, "build/reports/mosaic-analysis/main.txt").readText())

    val relocated = workspace(File(root, "relocated"))
    File(relocated, "src/main/kotlin/Unrelated.kt").writeText("package app\nfun unrelated() = 2")
    val restored = build(relocated, cached = true)
    assertEquals(TaskOutcome.FROM_CACHE, restored.task(":compileKotlin")?.outcome)
    assertEquals(TaskOutcome.FROM_CACHE, restored.task(":extractMosaicMain")?.outcome)
    assertEquals(TaskOutcome.SUCCESS, restored.task(":verifyMosaicMain")?.outcome)
    assertEquals(TaskOutcome.SUCCESS, restored.task(":mosaicGraph")?.outcome)
    assertTrue(secondSummary.contentEquals(File(relocated, "build/mosaic-analysis/main/summary.json").readBytes()))
    assertEquals(secondReport, File(relocated, "build/reports/mosaic-analysis/main.txt").readText())

    coreManifest(productionCore, core, "1", "first-release")
    clearProbe()
    val back = build(cached = true)
    assertEquals(TaskOutcome.FROM_CACHE, back.task(":compileKotlin")?.outcome)
    assertFalse(File(app, "build/mosaic-analysis/main/probe.txt").exists())
    File(app, "build/mosaic-analysis/main/shards").walkTopDown().filter { it.isFile }.forEach { shard ->
      assertEquals(1, org.buildmosaic.analysis.SourceShardCodec.decode(shard.readBytes()).coreAnalysisRevision)
    }
    assertEquals(firstSummary, summary())
    val buildFile = File(app, "build.gradle.kts")
    val originalBuild = buildFile.readText()
    val runtimeCore = File(root, "runtime-core.jar")
    coreManifest(productionCore, runtimeCore, "2")
    buildFile.writeText(
      originalBuild.replace("implementation(files", "compileOnly(files") +
        "\ndependencies { runtimeOnly(files(\"${runtimeCore.invariantSeparatorsPath}\")) }\n",
    )
    val runtimeConflict = build(failure = true)
    assertTrue(
      runtimeConflict.output.contains("Conflicting selected Mosaic Core analysis revisions"),
      runtimeConflict.output,
    )
    buildFile.writeText(originalBuild)
    // A failed compilation never permits assembly; recovery must produce the complete current context.
    coreManifest(productionCore, core, "2", "first-release")
    val caller = File(app, "src/main/kotlin/Caller.kt")
    caller.writeText("package app\nfun caller() = missingSymbol()")
    val failed = build(failure = true)
    assertEquals(TaskOutcome.FAILED, failed.task(":compileKotlin")?.outcome)
    assertEquals(null, failed.task(":extractMosaicMain")?.outcome)
    assertEquals(null, failed.task(":jar")?.outcome)
    caller.writeText("package app\nfun caller() = unrelated()")
    build()
    assertEquals(setOf("Tile.kt", "Unrelated.kt", "Caller.kt"), observed())
    assertTrue(secondSummary.contentEquals(File(app, "build/mosaic-analysis/main/summary.json").readBytes()))

    coreManifest(productionCore, core, "3", "first-release")
    val unsupported = build(failure = true)
    assertTrue(unsupported.output.contains("Unsupported Mosaic Core analysis revision 3"), unsupported.output)
    assertEquals(null, unsupported.task(":jar")?.outcome)
    File(app, "src/main/kotlin").deleteRecursively()
    val noSourceFailure = build(failure = true)
    assertTrue(noSourceFailure.output.contains("Unsupported Mosaic Core analysis revision 3"), noSourceFailure.output)
    coreManifest(productionCore, core, "1", "first-release")
    File(
      app,
      "build.gradle.kts",
    ).appendText("\nmosaicAnalysis { role = org.buildmosaic.gradle.MosaicAnalysisRole.LIBRARY }\n")
    val noSource = build()
    assertTrue(noSource.task(":compileKotlin")?.outcome in setOf(TaskOutcome.NO_SOURCE, TaskOutcome.SUCCESS))
    assertTrue(summary().module.tiles.isEmpty())
    assertTrue(summary().module.callables.isEmpty())
    assertEquals(setOf(1), summary().coreAnalysisRevisions)

    // An older selected Core cannot erase requirements retained from dependency contracts.
    val dependency = File(root, "revision-two-contracts.jar")
    dependencySummary(dependency, setOf(2))
    buildFile.appendText("\ndependencies { implementation(files(\"${dependency.invariantSeparatorsPath}\")) }\n")
    build()
    val retained = File(app, "build/mosaic-analysis/main/summary.json").readText()
    assertTrue(retained.contains("\"coreAnalysisRevisions\":[1,2]"), retained)
    val consumer = project(root, "production-consumer", productionCompiler, listOf(core, jar(app)), role = "LIBRARY")
    val rejected = run(consumer, "jar", expectFailure = true, configurationCache = true)
    assertTrue(rejected.output.contains("Unsupported Mosaic Core analysis revision 2"), rejected.output)
    assertEquals(null, rejected.task(":jar")?.outcome)
  }

  @Test
  fun `production packaging and consumers reject incompatible semantics even without sources`() {
    val root = Files.createTempDirectory("mosaic-semantic-consumers").toFile()
    val repository = File(System.getProperty("user.dir")).parentFile
    val compiler = File(repository, "mosaic-compiler-plugin/build/libs/mosaic-compiler-plugin-${mosaicVersion()}.jar")
    val originalCore = File(repository, "mosaic-core/build/libs/mosaic-core-${mosaicVersion()}.jar")
    val core = File(root, "selected.jar")
    coreManifest(originalCore, core, "1")
    val dependency = File(root, "dependency.jar")

    dependencySummary(dependency, setOf(1))
    val app = project(root, "consumer", compiler, listOf(core, dependency), role = "LIBRARY")
    val initial = run(app, "jar verifyMosaicMain mosaicGraph", configurationCache = true)
    assertEquals(TaskOutcome.NO_SOURCE, initial.task(":compileKotlin")?.outcome)
    for (revision in listOf(null, "invalid", "2")) {
      coreManifest(originalCore, core, revision)
      for (task in listOf("jar", "verifyMosaicMain -x extractMosaicMain", "mosaicGraph -x extractMosaicMain")) {
        val failure = run(app, task, expectFailure = true, configurationCache = true)
        assertTrue(failure.output.contains("revision", ignoreCase = true), failure.output)
        assertFalse(
          failure.task(":jar")?.outcome in setOf(TaskOutcome.SUCCESS, TaskOutcome.UP_TO_DATE, TaskOutcome.FROM_CACHE),
        )
      }
    }
    coreManifest(originalCore, core, "1")
    dependencySummary(dependency, setOf(2))
    for (enforcement in listOf("STANDARD", "STRICT")) {
      File(
        app,
        "build.gradle.kts",
      ).appendText("\nmosaicAnalysis { enforcement = org.buildmosaic.gradle.MosaicAnalysisEnforcement.$enforcement }\n")
      for (task in listOf("jar", "verifyMosaicMain -x extractMosaicMain", "mosaicGraph -x extractMosaicMain")) {
        val failure = run(app, task, expectFailure = true, configurationCache = true)
        assertTrue(failure.output.contains("Unsupported Mosaic Core analysis revision 2"), failure.output)
      }
    }
    dependencySummary(dependency, setOf(1))
    run(app, "jar verifyMosaicMain mosaicGraph", configurationCache = true)
    assertEquals(
      setOf(1),
      SummaryCodec.decode(File(app, "build/mosaic-analysis/main/summary.json").readBytes()).coreAnalysisRevisions,
    )
  }
}

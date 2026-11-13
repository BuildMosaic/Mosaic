@file:Suppress("LongMethod", "MaxLineLength", "ktlint:standard:max-line-length")

package org.buildmosaic.gradle

import org.buildmosaic.analysis.SummaryCodec
import org.gradle.testkit.runner.TaskOutcome
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KspIntegrationTest {
  @Test
  fun `KSP outputs participate in analysis`() {
    val root = Files.createTempDirectory("mosaic-ksp-").toFile()
    val repository = File(System.getProperty("user.dir")).parentFile
    val compiler = File(repository, "mosaic-compiler-plugin/build/libs/mosaic-compiler-plugin-${mosaicVersion()}.jar")
    val core = File(repository, "mosaic-core/build/libs/mosaic-core-${mosaicVersion()}.jar")
    val app = project(root, "app", compiler, listOf(core), roots = listOf("app.entry()"))
    File(app, "settings.gradle.kts").appendText("\ninclude(\":processor\")\n")
    val build = File(app, "build.gradle.kts")
    build.writeText(
      build.readText().replace(
        "kotlin(\"jvm\") version \"2.4.21\"",
        "kotlin(\"jvm\") version \"2.4.21\"\nid(\"com.google.devtools.ksp\")",
      ) + "\ndependencies { ksp(project(\":processor\")) }\n",
    )
    writeProcessor(app)
    writeSource(
      app,
      "src/main/kotlin/App.kt",
      """
      package app
      import org.buildmosaic.core.*
      import org.buildmosaic.core.injection.*
      annotation class GenerateTile
      val PageTile = singleTile { compose(GeneratedTile) + 1 }
      suspend fun entry() = canvas {}.withMosaic { compose(PageTile) }
      """.trimIndent(),
    )
    writeSource(app, "src/main/kotlin/Trigger.kt", "package app\n@GenerateTile class Generated")
    val discarded = writeSource(app, "src/main/kotlin/Discarded.kt", "package app\n@GenerateTile class Discarded")

    fun summary() = SummaryCodec.decode(File(app, "build/mosaic-analysis/main/summary.json").readBytes())
    val first = run(app, "build mosaicGraph", configurationCache = true)
    for (task in listOf(":kspKotlin", ":compileKotlin", ":extractMosaicMain")) {
      assertEquals(TaskOutcome.SUCCESS, first.task(task)?.outcome, first.output)
    }
    val order = first.tasks.map { it.path }
    assertTrue(order.indexOf(":kspKotlin") < order.indexOf(":compileKotlin"))
    assertTrue(order.indexOf(":compileKotlin") < order.indexOf(":extractMosaicMain"))
    assertEquals(
      setOf("app.PageTile", "app.GeneratedTile", "app.DiscardedTile"),
      summary().module.tiles.map {
        it.id
      }.toSet(),
    )
    val graphFile = File(app, "build/reports/mosaic-analysis/graph.md")
    assertTrue(graphFile.readText().contains("Tile: app.GeneratedTile"))
    assertTrue(File(app, "build/reports/mosaic-analysis/main.txt").readText().contains("Root app.entry(): VERIFIED;"))
    val unchanged = run(app, "build mosaicGraph", configurationCache = true)
    assertEquals(TaskOutcome.UP_TO_DATE, unchanged.task(":kspKotlin")?.outcome)
    discarded.delete()
    run(app, "build mosaicGraph", configurationCache = true)
    assertFalse(File(app, "build/generated/ksp/main/kotlin/app/DiscardedTile.kt").exists())
    assertFalse(
      File(
        app,
        "build/mosaic-analysis/main/shards/build/generated/ksp/main/kotlin/app/DiscardedTile.kt.shard.json",
      ).exists(),
    )
    assertEquals(setOf("app.PageTile", "app.GeneratedTile"), summary().module.tiles.map { it.id }.toSet())
    val incremental = summary()
    val graph = graphFile.readText()
    run(app, "clean build mosaicGraph", configurationCache = true)
    assertEquals(incremental, summary())
    assertEquals(graph, graphFile.readText())
  }

  private fun writeProcessor(app: File) {
    writeSource(
      app,
      "processor/build.gradle.kts",
      """
      plugins { java }
      repositories { mavenCentral() }
      dependencies { implementation("com.google.devtools.ksp:symbol-processing-api:2.3.12") }
      """.trimIndent(),
    )
    writeSource(
      app,
      "processor/src/main/resources/META-INF/services/com.google.devtools.ksp.processing.SymbolProcessorProvider",
      "fixture.TileProcessorProvider",
    )
    writeSource(app, "processor/src/main/java/fixture/TileProcessorProvider.java", processorSource())
  }

  private fun writeSource(
    app: File,
    path: String,
    source: String,
  ): File =
    File(app, path).apply {
      parentFile.mkdirs()
      writeText(source)
    }

  private fun processorSource(): String =
    """
    package fixture;
    import com.google.devtools.ksp.processing.*;
    import com.google.devtools.ksp.symbol.*;
    import java.io.*;
    import java.nio.charset.StandardCharsets;
    import java.util.*;

    public final class TileProcessorProvider implements SymbolProcessorProvider {
      public SymbolProcessor create(SymbolProcessorEnvironment environment) {
        return new SymbolProcessor() {
          private final Set<String> generated = new HashSet<>();
          public List<KSAnnotated> process(Resolver resolver) {
            Iterator<KSAnnotated> symbols = resolver.getSymbolsWithAnnotation("app.GenerateTile", false).iterator();
            while (symbols.hasNext()) {
              KSClassDeclaration declaration = (KSClassDeclaration) symbols.next();
              String name = declaration.getSimpleName().asString() + "Tile";
              if (!generated.add(name)) continue;
              Dependencies dependency = new Dependencies(false, declaration.getContainingFile());
              try (OutputStream output = environment.getCodeGenerator().createNewFile(dependency, "app", name, "kt")) {
                String source = "package app\nimport org.buildmosaic.core.singleTile\nval " + name + " = singleTile { 41 }\n";
                output.write(source.getBytes(StandardCharsets.UTF_8));
              } catch (IOException failure) {
                throw new UncheckedIOException(failure);
              }
            }
            return Collections.emptyList();
          }
          public void finish() {}
          public void onError() {}
        };
      }
    }
    """.trimIndent()
}

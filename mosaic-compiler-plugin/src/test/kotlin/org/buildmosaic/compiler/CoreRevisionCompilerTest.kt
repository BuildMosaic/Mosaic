@file:Suppress(
  "LongMethod",
  "NestedBlockDepth",
  "FunctionMaxLength",
  "MaxLineLength",
  "ktlint:standard:max-line-length",
)

package org.buildmosaic.compiler

import org.buildmosaic.analysis.CoreAnalysisRevision
import org.buildmosaic.analysis.SummaryCodec
import java.io.File
import java.nio.file.Files
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CoreRevisionCompilerTest {
  @Test
  fun `direct K2 admits actual Core semantics and rejects spoofed compiler input`() {
    val root = Files.createTempDirectory("mosaic-core-admission").toFile()
    val source =
      File(root, "Tile.kt").apply {
        writeText("package fixture\nimport org.buildmosaic.core.*\nval Tile = singleTile { 1 }")
      }
    val core =
      System.getProperty("mosaic.fixture.classpath").split(File.pathSeparator).map(::File).single {
        it.isFile && it.extension == "jar" &&
          JarFile(it).use {
              jar ->
            jar.getJarEntry("org/buildmosaic/core/injection/Canvas.class") != null
          }
      }

    fun candidate(
      revision: String?,
      release: String,
    ): File =
      File(root, "$release.jar").apply {
        JarFile(core).use { input ->
          val manifest = Manifest(input.manifest)
          manifest.mainAttributes.remove(java.util.jar.Attributes.Name(CoreAnalysisRevision.MANIFEST_ATTRIBUTE))
          revision?.let { manifest.mainAttributes.putValue(CoreAnalysisRevision.MANIFEST_ATTRIBUTE, it) }
          manifest.mainAttributes.putValue("Implementation-Version", release)
          JarOutputStream(outputStream(), manifest).use { output ->
            input.entries().asSequence().filter { it.name != "META-INF/MANIFEST.MF" }.forEach { entry ->
              output.putNextEntry(JarEntry(entry.name))
              input.getInputStream(entry).use { it.copyTo(output) }
              output.closeEntry()
            }
          }
        }
      }
    for (release in listOf("first-release", "later-release")) {
      val destination = File(root, release)
      compileAndExtract(listOf(source), destination, "fixture", coreArtifact = candidate("1", release))
      assertEquals(setOf(1), SummaryCodec.decode(File(destination, "summary.json").readBytes()).coreAnalysisRevisions)
    }
    for ((index, revision) in listOf(null, "bad", "2").withIndex()) {
      val destination = File(root, "invalid-$index")
      val failure =
        assertFailsWith<AssertionError> {
          compileAndExtract(
            listOf(source),
            destination,
            "fixture",
            coreArtifact = candidate(revision, "invalid-$index"),
          )
        }
      assertTrue(failure.message.orEmpty().contains("revision", ignoreCase = true), failure.message)
      assertFalse(File(destination, "summary.json").exists())
    }
    val spoof =
      assertFailsWith<AssertionError> {
        compileAndExtract(
          listOf(source),
          File(root, "spoof"),
          "fixture",
          coreArtifact = candidate("1", "actual"),
          expectedRevision = "2",
        )
      }
    assertTrue(spoof.message.orEmpty().contains("disagrees with actual selected Core"), spoof.message)
  }

  @Test
  fun `new conditional provide semantics cannot produce old trusted contracts`() {
    val root = Files.createTempDirectory("mosaic-conditional-core").toFile()
    val workingDirectory = File(System.getProperty("user.dir"))
    val repository =
      workingDirectory.takeIf { File(it, "mosaic-core/src/main/kotlin").isDirectory }
        ?: workingDirectory.parentFile
    val sourceRoot = File(repository, "mosaic-core/src/main/kotlin")
    val sources =
      sourceRoot.walkTopDown().filter { it.isFile && it.extension == "kt" }.map { original ->
        if (original.name == "CanvasBuilder.kt") {
          File(root, original.name).apply {
            writeText(
              original.readText().replace(
                "  internal val bindings",
                """
                inline fun <reified T : Any> provide(enabled: Boolean, noinline ctor: suspend CanvasFactory.() -> T) {
                  if (enabled) provide<T>(ctor = ctor)
                }
                internal val bindings
                """.trimIndent().prependIndent("  "),
              ),
            )
          }
        } else {
          original
        }
      }.toList()
    val classes = File(root, "runtime-classes")
    compileSources(sources, classes)
    val core = File(root, "conditional-core.jar")
    val manifest =
      Manifest().apply {
        mainAttributes.putValue("Manifest-Version", "1.0")
        mainAttributes.putValue(CoreAnalysisRevision.MANIFEST_ATTRIBUTE, "2")
      }
    jarClasses(classes, core, manifest = manifest)
    val source =
      File(root, "Application.kt").apply {
        writeText(
          """
          package fixture
          import org.buildmosaic.core.*
          import org.buildmosaic.core.injection.*
          class Service
          val Tile = singleTile { source<Service>() }
          suspend fun entry() = canvas { provide<Service>(false) { Service() } }.withMosaic { compose(Tile) }
          """.trimIndent(),
        )
      }
    val output = File(root, "analysis")
    val failure =
      assertFailsWith<AssertionError> {
        compileAndExtract(listOf(source), output, "fixture", coreArtifact = core)
      }
    assertTrue(failure.message.orEmpty().contains("Unsupported Mosaic Core analysis revision 2"), failure.message)
    assertFalse(File(output, "summary.json").exists())
  }
}

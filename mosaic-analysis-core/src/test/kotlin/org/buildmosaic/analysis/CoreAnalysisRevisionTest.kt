package org.buildmosaic.analysis

import java.io.File
import java.nio.file.Files
import java.util.jar.Attributes
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CoreAnalysisRevisionTest {
  @Test
  fun `Core revisions ignore release numbers`() {
    val root = Files.createTempDirectory("mosaic-core-revisions").toFile()
    val first = core(root, "0.7.0", "1")
    val later = core(root, "9.0.0", "1")
    assertEquals(1, CoreAnalysisRevision.selected(listOf(first)))
    assertEquals(1, CoreAnalysisRevision.selectedContext(listOf(first), listOf(later)))
    assertFailsWith<IllegalArgumentException> { CoreAnalysisRevision.selected(listOf(first, later)) }
    for (value in listOf(null, "", "x", "01", "1,2", "2147483648", "2")) {
      val failure =
        assertFailsWith<IllegalArgumentException> {
          CoreAnalysisRevision.selected(listOf(core(root, "candidate", value)))
        }
      assertTrue(failure.message.orEmpty().contains("revision", ignoreCase = true), failure.message)
    }
    assertFailsWith<IllegalArgumentException> { CoreAnalysisRevision.selected(emptyList()) }
  }

  private fun core(
    root: File,
    release: String,
    revision: String?,
  ): File {
    val manifest =
      Manifest().apply {
        mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
        mainAttributes.putValue("Implementation-Version", release)
        revision?.let { mainAttributes.putValue(CoreAnalysisRevision.MANIFEST_ATTRIBUTE, it) }
      }
    return File(root, "$release.jar").apply {
      JarOutputStream(outputStream(), manifest).use {
        it.putNextEntry(JarEntry("org/buildmosaic/core/injection/Canvas.class"))
        it.write(byteArrayOf(0))
        it.closeEntry()
      }
    }
  }
}

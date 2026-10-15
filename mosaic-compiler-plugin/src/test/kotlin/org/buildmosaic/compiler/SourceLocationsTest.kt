package org.buildmosaic.compiler

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class SourceLocationsTest {
  @Test
  fun `declarations exclude annotations and modifiers`() {
    val root = Files.createTempDirectory("mosaic-source-locations").toFile()
    val text =
      """
      package sites
      import org.buildmosaic.core.*
      import org.buildmosaic.core.injection.*
      @Suppress("fun val var", "unused")
      private val Tile by singleTile { 1 }
      @Suppress(
        "fun val var",
        "unused"
      )
      private /* fun val */ suspend fun entry() = canvas {}.withMosaic { compose(Tile) }
      open class Parent
      class Child : Parent()
      fun local() {
        @Suppress("MOSAIC_RECURSIVE_MULTITILE")
        val nested by perKeyTile<Int, Int> { it }
      }
      """.trimIndent()
    val source = File(root, "Sites.kt").apply { writeText(text) }
    val module = compileAndExtract(listOf(source), root, "sites")
    val tile = module.tiles.single { it.id == "sites.Tile" }
    val entry = module.callables.single { it.id == "sites.entry()" }
    assertEquals(5, tile.site.line)
    assertEquals(9, tile.site.column)
    assertEquals(10, entry.site.line)
    assertEquals(text.lines()[9].indexOf("fun entry") + 1, entry.site.column)
    val accessor = module.callables.single { it.id == "sites.<get-Tile>()" }
    assertEquals(tile.site.copy(owner = accessor.id), accessor.site)
    val child = module.callables.single { it.id == "sites.Child.<init>()" }
    assertEquals(child.site, child.effects.single().site)
    val local = module.tiles.single { it.id.startsWith("sites.local():fresh:") }
    assertEquals(15, local.suppressions.single().site.line)
    assertEquals(3, local.suppressions.single().site.column)
  }
}

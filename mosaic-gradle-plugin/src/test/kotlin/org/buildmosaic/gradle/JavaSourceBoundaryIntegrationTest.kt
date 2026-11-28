package org.buildmosaic.gradle

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JavaSourceBoundaryIntegrationTest {
  @Test
  fun `Java calls preserve unknown analysis boundaries`() {
    val root = Files.createTempDirectory("mosaic-java-boundary-").toFile()
    val repository = File(System.getProperty("user.dir")).parentFile
    val compiler = File(repository, "mosaic-compiler-plugin/build/libs/mosaic-compiler-plugin-${mosaicVersion()}.jar")
    val core = File(repository, "mosaic-core/build/libs/mosaic-core-${mosaicVersion()}.jar")
    val app = project(root, "app", compiler, listOf(core), roots = listOf("app.entry()"))
    writeMixedApp(app)
    run(app, "classes mosaicGraph verifyMosaicMain")
    val graph = File(app, "build/reports/mosaic-analysis/graph.md").readText()
    assertTrue(graph.contains("Tile: app.ProductTile"), graph)
    assertTrue(graph.contains("Unknown callable: app.JavaBridge"), graph)
    assertFalse(graph.contains("Virtual call: app.JavaBridge"), graph)
    val report = File(app, "build/reports/mosaic-analysis/main.txt").readText()
    assertTrue(report.contains("UNVERIFIED"), report)
    assertTrue(report.contains("JavaBridge"), report)
    assertFalse(report.contains("MISSING REQUIRED_LOOKUP"), report)
  }

  private fun writeMixedApp(app: File) {
    File(app, "src/main/java/app/JavaBridge.java").apply {
      parentFile.mkdirs()
      writeText(
        """
        package app;
        import org.buildmosaic.core.injection.Canvas;
        public final class JavaBridge {
            public static String name() { return "Desk"; }
            public static Canvas forward(Canvas canvas) { return canvas; }
        }
        """.trimIndent(),
      )
    }
    File(app, "src/main/kotlin/App.kt").apply {
      parentFile.mkdirs()
      writeText(
        """
        package app
        import org.buildmosaic.core.*
        import org.buildmosaic.core.injection.*
        class ProductService
        val ProductTile = singleTile { source<ProductService>(); JavaBridge.name() }
        suspend fun entry() {
            val original = canvas { instance(ProductService()) }
            val forwarded = JavaBridge.forward(original)
            forwarded.withMosaic { compose(ProductTile) }
        }
        """.trimIndent(),
      )
    }
  }
}

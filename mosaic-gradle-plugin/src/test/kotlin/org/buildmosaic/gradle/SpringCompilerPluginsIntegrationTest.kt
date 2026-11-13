package org.buildmosaic.gradle

import org.gradle.testkit.runner.TaskOutcome
import java.io.File
import java.lang.reflect.Modifier
import java.net.URLClassLoader
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SpringCompilerPluginsIntegrationTest {
  @Test
  fun `Spring and JPA plugins preserve graph generation`() {
    val root = Files.createTempDirectory("mosaic-spring-jpa-").toFile()
    val repository = File(System.getProperty("user.dir")).parentFile
    val compiler = File(repository, "mosaic-compiler-plugin/build/libs/mosaic-compiler-plugin-${mosaicVersion()}.jar")
    val core = File(repository, "mosaic-core/build/libs/mosaic-core-${mosaicVersion()}.jar")
    val app = project(root, "app", compiler, listOf(core))
    writeSpringApp(app)
    val result = run(app, "mosaicGraph")
    assertEquals(TaskOutcome.SUCCESS, result.task(":compileKotlin")?.outcome)
    assertEquals(TaskOutcome.SUCCESS, result.task(":extractMosaicMain")?.outcome)
    val graph = File(app, "build/reports/mosaic-analysis/graph.md").readText()
    assertTrue(graph.contains("Tile: app.ProductTile"), graph)
    assertTrue(graph.contains("## Root: app.entry()"), graph)
    URLClassLoader(arrayOf(File(app, "build/classes/kotlin/main").toURI().toURL()), javaClass.classLoader).use {
        loader ->
      val service = loader.loadClass("app.ProductService")
      assertFalse(Modifier.isFinal(service.modifiers))
      assertFalse(Modifier.isFinal(service.getMethod("name").modifiers))
      val custom = loader.loadClass("app.CustomProductService").getDeclaredConstructor().newInstance()
      assertEquals("Chair", service.getMethod("name").invoke(custom))
      val entity = loader.loadClass("app.ProductRecord")
      assertEquals(entity, entity.getDeclaredConstructor().newInstance().javaClass)
    }
  }

  private fun writeSpringApp(app: File) {
    val build = File(app, "build.gradle.kts")
    build.writeText(
      build.readText().replace(
        "kotlin(\"jvm\") version \"2.4.21\"",
        "kotlin(\"jvm\") version \"2.4.21\"\n" +
          "kotlin(\"plugin.spring\") version \"2.4.21\"\n" +
          "kotlin(\"plugin.jpa\") version \"2.4.21\"",
      ) + "\n" +
        """
        dependencies {
          implementation("org.springframework:spring-context:6.2.1")
          implementation("jakarta.persistence:jakarta.persistence-api:3.1.0")
        }
        """.trimIndent(),
    )
    File(app, "src/main/kotlin/App.kt").apply {
      parentFile.mkdirs()
      writeText(
        """
        package app
        import org.springframework.stereotype.Component
        import jakarta.persistence.Entity
        import jakarta.persistence.Id
        import org.buildmosaic.core.*
        import org.buildmosaic.core.injection.*
        @Component
        class ProductService { fun name(): String = "Desk" }
        class CustomProductService : ProductService() {
            override fun name(): String = "Chair"
        }
        @Entity
        class ProductRecord(@Id val id: Long)
        val ProductTile = singleTile { ProductService().name() }
        suspend fun entry() = canvas {}.withMosaic { compose(ProductTile) }
        """.trimIndent(),
      )
    }
  }
}

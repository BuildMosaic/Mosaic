@file:Suppress(
  "LongMethod",
  "FunctionMaxLength",
  "NestedBlockDepth",
  "MaxLineLength",
  "ktlint:standard:max-line-length",
)

package org.buildmosaic.gradle

import java.io.File
import java.nio.file.Files
import java.util.jar.JarOutputStream
import kotlin.test.Test
import kotlin.test.assertTrue

class SupportedBoundaryIntegrationTest {
  @Test
  fun `unsupported production configurations fail before extraction`() {
    val root = Files.createTempDirectory("mosaic-boundary-integration").toFile()
    val repository = File(System.getProperty("user.dir")).parentFile
    val pluginJar = File(repository, "mosaic-compiler-plugin/build/libs/mosaic-compiler-plugin-${mosaicVersion()}.jar")
    val coreJar = File(repository, "mosaic-core/build/libs/mosaic-core-${mosaicVersion()}.jar")
    val project = project(root, "boundary", pluginJar, listOf(coreJar))
    val buildFile = File(project, "build.gradle.kts")
    val validBuild = buildFile.readText()
    buildFile.writeText("plugins { java; id(\"org.buildmosaic.analysis\") }")
    assertTrue(run(project, "tasks", expectFailure = true).output.contains("requires a pure Kotlin/JVM project"))
    buildFile.writeText(validBuild)
    val source = File(project, "src/main/kotlin/Simple.kt")
    source.parentFile.mkdirs()
    source.writeText("package boundary\nclass Simple")
    val unsupportedOptions =
      """
      tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile>("compileKotlin") {
        compilerOptions.freeCompilerArgs.add("-Xno-param-assertions")
      }
      """.trimIndent()
    buildFile.writeText(validBuild + "\n" + unsupportedOptions)
    assertTrue(
      run(project, "extractMosaicMain", expectFailure = true).output.contains("does not support compiler options"),
    )
    val extraJar = File(project, "extra-plugin.jar")
    JarOutputStream(extraJar.outputStream()).use { }
    buildFile.writeText(
      validBuild +
        "\ntasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile>(\"compileKotlin\") { pluginClasspath.from(file(\"extra-plugin.jar\")) }",
    )
    assertTrue(
      run(
        project,
        "extractMosaicMain",
        expectFailure = true,
      ).output.contains("does not support additional Kotlin compiler plugins"),
    )
    buildFile.writeText(validBuild)
    source.delete()
    File(project, "src/main/kotlin/Script.kts").writeText("println(1)")
    assertTrue(
      run(project, "extractMosaicMain", expectFailure = true).output.contains("does not support Kotlin scripts"),
    )
    File(project, "src/main/kotlin/Script.kts").delete()
    buildFile.writeText(
      validBuild + "\ntasks.named<org.buildmosaic.gradle.ExtractMosaicTask>(\"extractMosaicMain\") { productionCompilerVersion.set(\"2.4.22\") }",
    )
    assertTrue(
      run(
        project,
        "extractMosaicMain",
        expectFailure = true,
      ).output.contains("has not verified Kotlin compiler 2.4.22"),
    )
    buildFile.writeText(
      validBuild + "\ntasks.named<org.buildmosaic.gradle.ExtractMosaicTask>(\"extractMosaicMain\") { selectedJavaVersion.set(\"999\") }",
    )
    assertTrue(run(project, "extractMosaicMain", expectFailure = true).output.contains("toolchain mismatch"))
  }
}

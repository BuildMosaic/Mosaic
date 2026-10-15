package org.buildmosaic.analysis

import java.util.Properties

/** Bounded compiler ABI compatibility, shared by Gradle selection and compiler admission. */
object CompilerVersionAdmission {
  private val profiles =
    Properties().apply {
      CompilerVersionAdmission::class.java.getResourceAsStream("compiler-profiles.properties")!!.use(::load)
    }
  val defaultCompilerApi: String = profiles.getProperty("default")
  private val boundsByApi =
    profiles.stringPropertyNames().filter { it != "default" }.sorted().associateWith {
      profiles.getProperty(it).split(',').map(::version)
    }

  private fun version(value: String): List<Int> {
    require(Regex("(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)").matches(value)) {
      "Expected a stable Kotlin compiler version: $value"
    }
    return value.split('.').map(String::toInt)
  }

  private fun compare(
    left: List<Int>,
    right: List<Int>,
  ): Int = left.zip(right).firstOrNull { (a, b) -> a != b }?.let { (a, b) -> a.compareTo(b) } ?: 0

  private fun accepts(
    actual: String,
    bounds: List<List<Int>>,
  ): Boolean {
    val compiler = version(stableVersion(actual))
    return compare(compiler, bounds[0]) >= 0 && compare(compiler, bounds[1]) < 0
  }

  fun stableVersion(actualCompilerVersion: String): String =
    actualCompilerVersion.replace(Regex("-release-[0-9]+$"), "")

  fun compilerApi(actualCompilerVersion: String): String =
    boundsByApi.entries.singleOrNull { accepts(actualCompilerVersion, it.value) }?.key
      ?: error(
        "Mosaic analysis does not support Kotlin compiler $actualCompilerVersion. " +
          "Compatible compiler ABI ranges: ${boundsByApi.keys.joinToString { profiles.getProperty(it) }}",
      )

  fun artifactId(compilerApi: String): String {
    require(compilerApi in boundsByApi) { "Unknown Mosaic introspector compiler API $compilerApi" }
    return if (compilerApi == defaultCompilerApi) {
      "mosaic-compiler-plugin"
    } else {
      "mosaic-compiler-plugin-kotlin-$compilerApi"
    }
  }

  fun requireSupported(
    actualCompilerVersion: String,
    compilerApi: String = defaultCompilerApi,
  ) {
    require(boundsByApi[compilerApi]?.let { accepts(actualCompilerVersion, it) } == true) {
      "Mosaic introspector compiler API $compilerApi does not support Kotlin compiler $actualCompilerVersion. " +
        "Compatible compiler ABI range: ${profiles.getProperty(compilerApi)}"
    }
  }
}

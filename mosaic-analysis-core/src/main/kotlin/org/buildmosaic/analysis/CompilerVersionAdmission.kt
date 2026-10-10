package org.buildmosaic.analysis

import java.util.Properties

/** Internal measured compiler mapping, shared by Gradle selection and compiler admission. */
object CompilerVersionAdmission {
  const val COMPATIBILITY_PROBE_PROPERTY = "mosaic.analysis.compatibilityProbe"

  private val profiles =
    Properties().apply {
      CompilerVersionAdmission::class.java.getResourceAsStream("compiler-profiles.properties")!!.use(::load)
    }
  val defaultCompilerApi: String = profiles.getProperty("default")
  private val compilersByApi =
    profiles.stringPropertyNames().filter { it != "default" }.sorted().associateWith {
      profiles.getProperty(it).split(',').toSet()
    }
  val supportedCompilers: Set<String> = compilersByApi.values.flatten().toSet()

  fun stableVersion(actualCompilerVersion: String): String =
    actualCompilerVersion.replace(Regex("-release-[0-9]+$"), "")

  fun compilerApi(actualCompilerVersion: String): String =
    compilersByApi.entries.singleOrNull { stableVersion(actualCompilerVersion) in it.value }?.key
      ?: error(
        "Mosaic analysis has not verified Kotlin compiler $actualCompilerVersion. " +
          "Supported compilers: ${compilersByApi.values.flatten().joinToString()}",
      )

  fun artifactId(compilerApi: String): String {
    require(compilerApi in compilersByApi) { "Unknown Mosaic introspector compiler API $compilerApi" }
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
    if (System.getProperty(COMPATIBILITY_PROBE_PROPERTY) == "true") {
      System.err.println("MOSAIC_COMPATIBILITY_PROBE: compiler-version admission bypassed for $actualCompilerVersion")
      return
    }
    require(stableVersion(actualCompilerVersion) in compilersByApi[compilerApi].orEmpty()) {
      "Mosaic introspector compiler API $compilerApi does not support Kotlin compiler $actualCompilerVersion. " +
        "Verified compilers: ${compilersByApi[compilerApi].orEmpty().joinToString()}"
    }
  }
}

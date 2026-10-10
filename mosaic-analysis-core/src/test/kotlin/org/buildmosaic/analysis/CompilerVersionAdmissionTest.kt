package org.buildmosaic.analysis

import kotlin.test.Test
import kotlin.test.assertFailsWith

class CompilerVersionAdmissionTest {
  @Test
  fun `production admission remains exact`() =
    withProbe(null) {
      CompilerVersionAdmission.requireSupported("2.4.20")
      CompilerVersionAdmission.requireSupported("2.4.20-release-123")
      for (version in listOf("2.3.0", "2.4.30", "2.4.20-RC1", "")) {
        assertFailsWith<IllegalArgumentException> { CompilerVersionAdmission.requireSupported(version) }
      }
    }

  @Test
  fun `measured compilers admit only their profile`() {
    val versions =
      mapOf(
        "2.3.0" to listOf("2.2.0", "2.2.10", "2.2.20", "2.2.21", "2.3.0", "2.3.10"),
        "2.3.20" to listOf("2.3.20", "2.3.21"),
        "2.4.20" to listOf("2.4.0", "2.4.10", "2.4.20", "2.4.21"),
      )
    withProbe(null) {
      versions.forEach { (api, compilers) ->
        compilers.forEach { compiler ->
          kotlin.test.assertEquals(api, CompilerVersionAdmission.compilerApi(compiler))
          val artifact = if (api == "2.4.20") "mosaic-compiler-plugin" else "mosaic-compiler-plugin-kotlin-$api"
          kotlin.test.assertEquals(artifact, CompilerVersionAdmission.artifactId(api))
          CompilerVersionAdmission.requireSupported(compiler, api)
          CompilerVersionAdmission.requireSupported("$compiler-release-123", api)
          (versions.keys - api).forEach { wrong ->
            assertFailsWith<IllegalArgumentException> { CompilerVersionAdmission.requireSupported(compiler, wrong) }
          }
        }
      }
      assertFailsWith<IllegalArgumentException> { CompilerVersionAdmission.artifactId("unknown") }
      for (version in listOf("2.2.1", "2.3.30", "2.4.22", "2.4.20-RC1", "2.4.+", "")) {
        assertFailsWith<IllegalStateException> { CompilerVersionAdmission.compilerApi(version) }
      }
    }
  }

  @Test
  fun `probing requires explicit true`() {
    for (value in listOf("false", "TRUE", "1", "")) {
      withProbe(value) {
        assertFailsWith<IllegalArgumentException> { CompilerVersionAdmission.requireSupported("2.3.0") }
      }
    }
    withProbe("true") { CompilerVersionAdmission.requireSupported("2.3.0") }
  }

  private fun withProbe(
    value: String?,
    block: () -> Unit,
  ) {
    val property = CompilerVersionAdmission.COMPATIBILITY_PROBE_PROPERTY
    val original = System.getProperty(property)
    try {
      if (value == null) System.clearProperty(property) else System.setProperty(property, value)
      block()
    } finally {
      if (original == null) System.clearProperty(property) else System.setProperty(property, original)
    }
  }
}

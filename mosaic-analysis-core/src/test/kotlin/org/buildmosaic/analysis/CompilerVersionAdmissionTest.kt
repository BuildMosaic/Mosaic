package org.buildmosaic.analysis

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CompilerVersionAdmissionTest {
  @Test
  fun `compatible future patches are admitted`() {
    CompilerVersionAdmission.requireSupported("2.4.99")
    CompilerVersionAdmission.requireSupported("2.4.99-release-123")
    assertEquals("2.4.20", CompilerVersionAdmission.compilerApi("2.4.99"))
    for (version in listOf("2.5.0", "999.0.0", "2.4.20-RC1", "2.4.+", "02.4.20", "")) {
      assertFailsWith<IllegalArgumentException> { CompilerVersionAdmission.requireSupported(version) }
    }
  }

  @Test
  fun `ABI boundaries preserve profile selection`() {
    val versions =
      mapOf(
        "2.3.0" to listOf("2.2.0", "2.2.99", "2.3.0", "2.3.19"),
        "2.3.20" to listOf("2.3.20", "2.3.99"),
        "2.4.20" to listOf("2.4.0", "2.4.99"),
      )
    versions.forEach { (api, compilers) ->
      compilers.forEach { compiler ->
        assertEquals(api, CompilerVersionAdmission.compilerApi(compiler))
        val artifact =
          if (api == CompilerVersionAdmission.defaultCompilerApi) {
            "mosaic-compiler-plugin"
          } else {
            "mosaic-compiler-plugin-kotlin-$api"
          }
        assertEquals(artifact, CompilerVersionAdmission.artifactId(api))
        CompilerVersionAdmission.requireSupported(compiler, api)
        CompilerVersionAdmission.requireSupported("$compiler-release-123", api)
        (versions.keys - api).forEach { wrong ->
          assertFailsWith<IllegalArgumentException> { CompilerVersionAdmission.requireSupported(compiler, wrong) }
        }
      }
    }
    assertFailsWith<IllegalArgumentException> { CompilerVersionAdmission.artifactId("unknown") }
    for (version in listOf("2.1.99", "2.5.0", "3.0.0", "999.0.0")) {
      assertFailsWith<IllegalStateException> { CompilerVersionAdmission.compilerApi(version) }
    }
  }
}

package org.buildmosaic.analysis

import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertFailsWith

class CompilerVersionAdmissionTest {
  @Test
  fun `production admission remains exact`() {
    CompilerVersionAdmission.requireSupported("2.4.20")
    CompilerVersionAdmission.requireSupported("2.4.20-release-123")
    for (version in listOf("999.0.0", "2.4.20-RC1", "")) {
      assertFailsWith<IllegalArgumentException> { CompilerVersionAdmission.requireSupported(version) }
    }
  }

  @Test
  fun `measured compilers admit only their profile`() {
    val mapping =
      Properties().apply {
        CompilerVersionAdmission::class.java.getResourceAsStream("compiler-profiles.properties")!!.use(::load)
      }
    val versions =
      mapping.stringPropertyNames().filter { it != "default" }.associateWith {
        mapping.getProperty(it).split(',')
      }
    versions.forEach { (api, compilers) ->
      compilers.forEach { compiler ->
        kotlin.test.assertEquals(api, CompilerVersionAdmission.compilerApi(compiler))
        val artifact =
          if (api == CompilerVersionAdmission.defaultCompilerApi) {
            "mosaic-compiler-plugin"
          } else {
            "mosaic-compiler-plugin-kotlin-$api"
          }
        kotlin.test.assertEquals(artifact, CompilerVersionAdmission.artifactId(api))
        CompilerVersionAdmission.requireSupported(compiler, api)
        CompilerVersionAdmission.requireSupported("$compiler-release-123", api)
        (versions.keys - api).forEach { wrong ->
          assertFailsWith<IllegalArgumentException> { CompilerVersionAdmission.requireSupported(compiler, wrong) }
        }
      }
    }
    assertFailsWith<IllegalArgumentException> { CompilerVersionAdmission.artifactId("unknown") }
    for (version in listOf("999.0.0", "2.4.20-RC1", "2.4.+", "")) {
      assertFailsWith<IllegalStateException> { CompilerVersionAdmission.compilerApi(version) }
    }
  }
}

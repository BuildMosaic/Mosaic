package org.buildmosaic.compiler

import org.buildmosaic.analysis.Effect
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InheritedAccessorTest {
  @Test
  fun `exception subclasses do not claim inherited accessors`() {
    val directory = Files.createTempDirectory("mosaic-exception-accessors").toFile()
    val source =
      File(directory, "Failures.kt").apply {
        writeText(
          """
          package regression
          class FirstFailure : RuntimeException("first")
          class SecondFailure : RuntimeException("second")
          class DetailedFailure : RuntimeException() {
            override val message: String get() = "details"
            override val cause: Throwable? get() = null
          }
          """.trimIndent(),
        )
      }
    val module = compileAndExtract(listOf(source), directory, "regression")
    val accessors = module.callables.filter { "<get-" in it.id }
    assertEquals(
      setOf("regression.DetailedFailure.<get-message>()", "regression.DetailedFailure.<get-cause>()"),
      accessors.map { it.id }.toSet(),
    )
    assertTrue(accessors.all { it.effects.isEmpty() }, accessors.toString())
  }

  @Test
  fun `inherited Kotlin properties retain base contracts and explicit overrides`() {
    val directory = Files.createTempDirectory("mosaic-inherited-properties").toFile()
    val base =
      File(directory, "Base.kt").apply {
        writeText(
          """
          package regression
          fun readValue(): String = "base"
          fun writeValue(value: String) {}
          open class Base {
            open var value: String
              get() = readValue()
              set(value) { writeValue(value) }
          }
          """.trimIndent(),
        )
      }
    val derived =
      File(directory, "Derived.kt").apply {
        writeText(
          """
          package regression
          class Inherited : Base()
          class Overriding : Base() {
            override var value: String
              get() = readValue()
              set(value) { writeValue(value) }
          }
          """.trimIndent(),
        )
      }
    val module = compileAndExtract(listOf(base, derived), directory, "regression")
    for (owner in listOf("Base", "Overriding")) {
      val getter = module.callables.single { it.id == "regression.$owner.<get-value>()" }
      val setter = module.callables.single { it.id == "regression.$owner.<set-value>(kotlin.String)" }
      assertTrue(getter.effects.filterIsInstance<Effect.Call>().any { it.target == "regression.readValue()" })
      assertTrue(setter.effects.filterIsInstance<Effect.Call>().any { it.target == "regression.writeValue(kotlin.String)" })
    }
  }
}

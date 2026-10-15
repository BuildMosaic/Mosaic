package org.buildmosaic.core.injection

import kotlinx.coroutines.test.runTest
import org.buildmosaic.core.exception.MosaicMissingKeyException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@Suppress("FunctionMaxLength")
class CanvasTest {
  // Test interfaces for dependency injection
  interface TestService {
    fun getValue(): String
  }

  // Simple implementations
  class TestServiceImpl(private val value: String) : TestService {
    override fun getValue(): String = value
  }

  @Test
  fun `required and optional source overloads return the registered instance`() =
    runTest {
      val value = TestServiceImpl("direct")
      val unqualified = TestServiceImpl("unqualified")
      val key = CanvasKey(TestService::class, "direct-key")
      val testCanvas =
        canvas {
          provide(key) { value }
          provide<TestService> { unqualified }
        }
      assertSame(value, testCanvas.source(TestService::class, "direct-key"))
      assertSame(value, testCanvas.source(key))
      assertSame(unqualified, testCanvas.source<TestService>())
      assertSame(value, testCanvas.source<TestService>("direct-key"))
      assertSame(value, testCanvas.sourceOrNull(key))
      assertSame(unqualified, testCanvas.sourceOrNull<TestService>())
      assertSame(value, testCanvas.sourceOrNull<TestService>("direct-key"))
    }

  @Test
  fun `required source failures identify the requested type and qualifier`() =
    runTest {
      val testCanvas = canvas {}
      val key = CanvasKey(TestService::class, "missing")
      val failure = assertFailsWith<MosaicMissingKeyException> { testCanvas.source(key) }
      assertEquals(key, failure.key)
      assertTrue(failure.message.orEmpty().contains("TestService"))
      assertTrue(failure.message.orEmpty().contains("missing"))
      assertFailsWith<MosaicMissingKeyException> { testCanvas.source<String>() }
      assertFailsWith<MosaicMissingKeyException> { testCanvas.source(String::class) }
    }

  @Test
  fun `should return null when source is not found in sourceOrNull`() =
    runTest {
      val testCanvas: Canvas =
        canvas {
          provide<TestService>("direct-key") { TestServiceImpl("direct-test") }
        }

      assertNull(testCanvas.sourceOrNull<String>())
      assertNull(testCanvas.sourceOrNull<TestService>("missing"))
      assertNull(testCanvas.sourceOrNull(CanvasKey(String::class)))
    }
}

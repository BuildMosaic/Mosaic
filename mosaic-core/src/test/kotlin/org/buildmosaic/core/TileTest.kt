package org.buildmosaic.core

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.buildmosaic.core.injection.canvas
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TileTest {
  private val testValue: String = "test-value"
  private lateinit var mosaic: Mosaic

  @BeforeTest
  fun setUp() {
    val emptyCanvas =
      runBlocking { canvas {} }
    mosaic = MosaicImpl(emptyCanvas)
  }

  @Test
  fun `should retrieve single value`() =
    runTest {
      val testTile = singleTile { testValue }
      val result = testTile.block(mosaic)
      assertEquals("test-value", result)
    }

  @Test
  fun `should retrieve multi values`() =
    runTest {
      val map = mapOf("a" to "foo", "b" to "bar")
      val testTile = multiTile { map }
      val result = mosaic.compose(testTile, map.keys)
      assertEquals(map, result)
    }

  @Test
  fun `should handle retrieve errors gracefully`() =
    runTest {
      val errorMessage = "Retrieve failed"

      val testTile = singleTile<String> { throw RuntimeException(errorMessage) }
      val exception =
        assertFailsWith<RuntimeException> {
          testTile.block(mosaic)
        }
      assertEquals(errorMessage, exception.message)

      val multiTestTile = multiTile<String, String> { throw RuntimeException(errorMessage) }
      val multiException =
        assertFailsWith<RuntimeException> {
          mosaic.compose(multiTestTile, setOf("a"))
        }
      assertEquals(errorMessage, multiException.message)
    }
}

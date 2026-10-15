/*
 * Copyright 2025 Nicholas Abbott
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.buildmosaic.test

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.buildmosaic.core.injection.CanvasKey
import org.buildmosaic.core.multiTile
import org.buildmosaic.core.singleTile
import org.buildmosaic.core.source
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("LargeClass", "FunctionMaxLength")
class TestMosaicBuilderTest {
  // Test tiles for single tile operations
  private val testSingleTile = singleTile { "original" }

  // Test tiles for multi tile operations
  private val testMultiTile = multiTile<String, String> { keys -> keys.associateWith { "original-$it" } }

  @Test
  fun `failed SingleTile mocks preserve the supplied exception type and message`() =
    runTest {
      val failure = IllegalStateException("mock failure")
      mosaicBuilder().withFailedTile(testSingleTile, failure).withMosaic {
        assertEquals(
          failure.message,
          kotlin.test.assertFailsWith<IllegalStateException> { compose(testSingleTile) }.message,
        )
      }
    }

  @Test
  fun `failed MultiTile mocks preserve the supplied exception type and message`() =
    runTest {
      val failure = IllegalStateException("mock failure")
      mosaicBuilder().withFailedTile(testMultiTile, failure).withMosaic {
        assertEquals(
          failure.message,
          kotlin.test.assertFailsWith<IllegalStateException> { compose(testMultiTile, "a") }.message,
        )
      }
    }

  @Test
  fun `delayed SingleTile mocks complete at the requested virtual time`() =
    runTest {
      mosaicBuilder().withDelayedTile(testSingleTile, "delayed", 50L).withMosaic {
        val result = composeAsync(testSingleTile)
        testScheduler.runCurrent()
        testScheduler.advanceTimeBy(49)
        kotlin.test.assertFalse(result.isCompleted)
        testScheduler.advanceTimeBy(1)
        testScheduler.runCurrent()
        assertEquals("delayed", result.await())
      }
    }

  @Test
  fun `delayed MultiTile mocks complete at the requested virtual time`() =
    runTest {
      val values = mapOf("x" to "X", "y" to "Y")
      mosaicBuilder().withDelayedTile(testMultiTile, values, 100L).withMosaic {
        val results = composeAsync(testMultiTile, values.keys)
        testScheduler.runCurrent()
        testScheduler.advanceTimeBy(99)
        kotlin.test.assertTrue(results.values.none { it.isCompleted })
        testScheduler.advanceTimeBy(1)
        testScheduler.runCurrent()
        assertEquals(values, results.mapValues { it.value.await() })
      }
    }

  @Test
  fun `custom MultiTile mocks receive the requested keys`() =
    runTest {
      mosaicBuilder().withCustomTile(testMultiTile) { keys ->
        keys.associateWith { it.uppercase() }
      }.withMosaic {
        assertEquals(testMultiTile, setOf("a", "b"), mapOf("a" to "A", "b" to "B"))
      }
    }

  @Test
  fun `empty MultiTile mocks never fall back to the real provider`() =
    runTest {
      mosaicBuilder().withMockTile(testMultiTile, emptyMap()).withMosaic {
        assertThrows(testMultiTile, listOf("a"), NoSuchElementException::class)
      }
    }

  @Test
  fun `custom SingleTile mocks compose substituted dependencies`() =
    runTest {
      val baseTile = singleTile { "base" }
      val composedTile =
        singleTile {
          val base = compose(baseTile)
          "composed-$base"
        }

      mosaicBuilder()
        .withMockTile(baseTile, "mocked-base")
        .withCustomTile(composedTile) {
          val base = compose(baseTile)
          "custom-composed-$base"
        }
        .withMosaic {
          assertEquals(composedTile, "custom-composed-mocked-base")
        }
    }

  @Test fun `execution snapshots all configuration and later reuse sees mutations`() =
    runTest {
      val single = singleTile { "real single" }
      val multi = multiTile<Int, String> { keys -> keys.associateWith { "real multi" } }
      val nested = singleTile { "real nested" }
      val customMulti = multiTile<Int, String> { error("substitute") }
      val realMulti = multiTile<Int, String> { keys -> keys.associateWith { compose(nested) } }
      val subject = singleTile { compose(realMulti, 1) + compose(customMulti, 1) + source<String>() }
      val builder =
        mosaicBuilder()
          .withMockTile(single, "old single")
          .withMockTile(multi, mapOf(1 to "old multi"))
          .withCanvasSource("old source")
          .withCustomTile(nested) { compose(single) }
          .withCustomTile(customMulti) { keys -> keys.associateWith { compose(multi, it) } }
      builder.withMosaic {
        builder.withMockTile(single, "new single")
          .withMockTile(multi, mapOf(1 to "new multi"))
          .withCanvasSource("new source")
        // No cache has been populated: nested real and custom providers must use the snapshot.
        assertEquals(subject, "old singleold multiold source")
      }
      builder.withMosaic { assertEquals(subject, "new singlenew multinew source") }
    }

  private class BorrowedSource : AutoCloseable {
    var closes = 0

    override fun close() {
      closes++
    }
  }

  @Test fun `Canvas sources stay caller owned across all overloads and failed executions`() =
    runTest {
      val resource = BorrowedSource()
      val key = CanvasKey(AutoCloseable::class, "key")
      val builder =
        mosaicBuilder()
          .withCanvasSource(resource)
          .withCanvasSource(AutoCloseable::class, resource)
          .withCanvasSource("inferred", resource)
          .withCanvasSource(AutoCloseable::class, "explicit", resource)
          .withCanvasSource(key, resource)
      kotlin.test.assertFailsWith<IllegalStateException> {
        builder.withMosaic {
          kotlin.test.assertSame(resource, source<BorrowedSource>())
          kotlin.test.assertSame(resource, source<AutoCloseable>())
          kotlin.test.assertSame(resource, source<BorrowedSource>("inferred"))
          kotlin.test.assertSame(resource, source<AutoCloseable>("explicit"))
          kotlin.test.assertSame(resource, source(key))
          assertEquals(0, resource.closes)
          error("test failure")
        }
      }
      assertEquals(0, resource.closes)
      builder.withMosaic { kotlin.test.assertSame(resource, source(key)) }
      assertEquals(0, resource.closes)
      resource.close()
      assertEquals(1, resource.closes)
    }
}

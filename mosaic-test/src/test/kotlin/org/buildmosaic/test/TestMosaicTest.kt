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

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.Job
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.buildmosaic.core.multiTile
import org.buildmosaic.core.singleTile
import kotlin.coroutines.ContinuationInterceptor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Public scoped testing assertions, facade routing, and ownership guarantees.
 */
@Suppress("LargeClass", "FunctionMaxLength")
class TestMosaicTest {
  // Test tiles for testing
  private val testSingleTile = singleTile { "test-data" }
  private val testMultiTile =
    multiTile<String, String> { keys ->
      keys.associateWith { "data-for-$it" }
    }
  private val testErrorTile =
    singleTile<String> {
      throw TestException("Test error")
    }
  private val testErrorMultiTile =
    multiTile<String, String> { _ ->
      throw TestException("Multi tile error")
    }

  @Test
  fun `SingleTile equality helpers compose values and report mismatches`() =
    runTest {
      mosaicBuilder().withMockTile(testSingleTile, "expected").withMosaic {
        assertEquals(testSingleTile, "expected")
        assertEquals(testSingleTile, "expected", "success message")
        assertFailsWith<AssertionError> { assertEquals(testSingleTile, "wrong") }
        val failure = assertFailsWith<AssertionError> { assertEquals(testSingleTile, "wrong", "single mismatch") }
        assertTrue(failure.message.orEmpty().contains("single mismatch"))
      }
    }

  @Test
  fun `MultiTile equality helpers support Collection and List overloads and report mismatches`() =
    runTest {
      val keys = listOf("a", "b")
      val collection: Collection<String> = keys
      val expected = mapOf("a" to "A", "b" to "B")
      mosaicBuilder().withMockTile(testMultiTile, expected).withMosaic {
        assertEquals(testMultiTile, collection, expected)
        assertEquals(testMultiTile, keys, expected, "success message")
        assertFailsWith<AssertionError> { assertEquals(testMultiTile, collection, emptyMap()) }
        val failure =
          assertFailsWith<AssertionError> {
            assertEquals(testMultiTile, keys, emptyMap(), "multi mismatch")
          }
        assertTrue(failure.message.orEmpty().contains("multi mismatch"))
      }
    }

  @Test
  fun `SingleTile exception helpers accept the expected type with and without a message`() =
    runTest {
      mosaicBuilder().withFailedTile(testErrorTile, TestException("failure")).withMosaic {
        assertThrows(testErrorTile, TestException::class)
        assertThrows(testErrorTile, TestException::class, "expected failure")
      }
    }

  @Test
  fun `SingleTile exception helpers reject successful results and mismatched exception types`() =
    runTest {
      mosaicBuilder()
        .withMockTile(testSingleTile, "success")
        .withFailedTile(testErrorTile, TestException("different type"))
        .withMosaic {
          assertFailsWith<AssertionError> { assertThrows(testSingleTile, IllegalArgumentException::class) }
          val failure =
            assertFailsWith<AssertionError> {
              assertThrows(testErrorTile, IllegalArgumentException::class, "wrong exception type")
            }
          assertTrue(failure.message.orEmpty().contains("wrong exception type"))
        }
    }

  @Test
  fun `MultiTile exception helper accepts the expected type`() =
    runTest {
      val keys = listOf("key1")

      mosaicBuilder()
        .withFailedTile(testErrorMultiTile, TestException("Multi error"))
        .withMosaic {
          assertThrows(testErrorMultiTile, keys, TestException::class)
        }
    }

  @Test
  fun `MultiTile exception helper rejects successful results and mismatched exception types`() =
    runTest {
      mosaicBuilder()
        .withMockTile(testMultiTile, mapOf("a" to "A"))
        .withFailedTile(testErrorMultiTile, TestException("different type"))
        .withMosaic {
          assertFailsWith<AssertionError> { assertThrows(testMultiTile, listOf("a"), IllegalArgumentException::class) }
          assertFailsWith<AssertionError> {
            assertThrows(
              testErrorMultiTile,
              listOf("a"),
              IllegalArgumentException::class,
            )
          }
        }
    }

  @Test
  fun `null SingleTile mocks override non-null real values`() =
    runTest {
      val nullableTile = singleTile<String?> { "real value" }

      mosaicBuilder()
        .withMockTile(nullableTile, null)
        .withMosaic {
          assertEquals(nullableTile, null)
        }
    }

  @Test
  fun `failed mocks leave real subject composition usable`() =
    runTest {
      val subject = singleTile { compose(testSingleTile).uppercase() }
      mosaicBuilder()
        .withMockTile(testSingleTile, "success")
        .withFailedTile(testErrorTile, TestException("failure"))
        .withMosaic {
          assertThrows(testErrorTile, TestException::class)
          assertEquals(subject, "SUCCESS")
        }
    }

  @Test
  fun `SingleTile asynchronous composition forwards substitutions`() =
    runTest {
      val testData = "async-data"

      mosaicBuilder()
        .withMockTile(testSingleTile, testData)
        .withMosaic {
          val deferred = composeAsync(testSingleTile)
          val result = deferred.await()
          assertEquals(testData, result)
        }
    }

  @Test
  fun `MultiTile asynchronous composition forwards substitutions`() =
    runTest {
      val keys = listOf("key1", "key2")
      val expected = mapOf("key1" to "value1", "key2" to "value2")

      mosaicBuilder()
        .withMockTile(testMultiTile, expected)
        .withMosaic {
          val deferredMap = composeAsync(testMultiTile, keys)
          val result = deferredMap.mapValues { it.value.await() }
          assertEquals(expected, result)
        }
    }

  @Test
  fun `real subjects compose mocked and unmocked SingleTile dependencies`() =
    runTest {
      val realTile = singleTile { "real-data" }
      val mockedTile = singleTile { "mocked-data" }

      val composedTile =
        singleTile {
          val realResult = compose(realTile)
          val mockedResult = compose(mockedTile)
          "$realResult + $mockedResult"
        }

      mosaicBuilder()
        .withMockTile(mockedTile, "test-mocked-data")
        // realTile is not mocked, should use original implementation
        .withMosaic {
          val result = compose(composedTile)
          assertEquals("real-data + test-mocked-data", result)
        }
    }

  @Test
  fun `real subjects compose mocked and unmocked MultiTile dependencies`() =
    runTest {
      val realMultiTile =
        multiTile<String, String> { keys ->
          keys.associateWith { "real-$it" }
        }
      val mockedMultiTile =
        multiTile<String, String> { keys ->
          keys.associateWith { "mocked-$it" }
        }

      val composedTile =
        singleTile {
          val realResults = compose(realMultiTile, listOf("a", "b"))
          val mockedResults = compose(mockedMultiTile, listOf("x", "y"))
          "Real: ${realResults.values.joinToString()}, Mocked: ${mockedResults.values.joinToString()}"
        }

      mosaicBuilder()
        .withMockTile(mockedMultiTile, mapOf("x" to "test-x", "y" to "test-y"))
        // realMultiTile is not mocked, should use original implementation
        .withMosaic {
          val result = compose(composedTile)
          assertEquals("Real: real-a, real-b, Mocked: test-x, test-y", result)
        }
    }

  @Test
  fun `scoped receiver exposes its configured Canvas`() =
    runTest {
      data class TestService(val name: String)
      val testService = TestService("test-service")

      mosaicBuilder()
        .withCanvasSource(testService)
        .withMosaic {
          assertEquals(testService, canvas.source(TestService::class))
        }
    }

  @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
  @Test
  fun `execution inherits caller context and virtual time`() =
    runTest {
      val ambient = ThreadLocal<String>()
      withContext(CoroutineName("test request") + ambient.asContextElement("tenant")) {
        val caller = currentCoroutineContext()
        val start = testScheduler.currentTime
        mosaicBuilder().withMosaic {
          assertSame(caller[Job], currentCoroutineContext()[Job])
          compose(
            singleTile {
              val producer = currentCoroutineContext()
              assertSame(caller[ContinuationInterceptor], producer[ContinuationInterceptor])
              assertEquals("test request", producer[CoroutineName]?.name)
              assertEquals("tenant", ambient.get())

              fun descendants(job: Job): Sequence<Job> = job.children.flatMap { sequenceOf(it) + descendants(it) }
              assertTrue(descendants(caller[Job]!!).any { it === producer[Job] })
              delay(200)
            },
          )
        }
        assertEquals(200L, testScheduler.currentTime - start)
        assertTrue(caller[Job]!!.children.none())
      }
    }

  @Test fun `cancelling one facade waiter preserves the shared producer`() =
    runTest {
      var calls = 0
      val gate = CompletableDeferred<Unit>()
      val dependency = singleTile<Int> { error("use substitute") }
      val subject = singleTile { compose(dependency) }
      mosaicBuilder().withCustomTile(dependency) {
        calls++
        gate.await()
        7
      }.withMosaic {
        val waiter = launch { compose(subject) }
        testScheduler.runCurrent()
        waiter.cancelAndJoin()
        assertFalse(composeAsync(subject).isCancelled)
        gate.complete(Unit)
        assertEquals(subject, 7)
        assertEquals(1, calls)
      }
    }

  @Test fun `each builder execution has a fresh cache and reuse within it is cached`() =
    runTest {
      var calls = 0
      val dependency = singleTile<Int> { error("use substitute") }
      val subject = singleTile { compose(dependency) }
      val builder = mosaicBuilder().withCustomTile(dependency) { ++calls }
      repeat(2) { index ->
        builder.withMosaic {
          assertEquals(subject, index + 1)
          assertEquals(subject, index + 1)
        }
      }
      assertEquals(2, calls)
    }
}

// Test exception class for testing error scenarios
class TestException(message: String) : Exception(message)

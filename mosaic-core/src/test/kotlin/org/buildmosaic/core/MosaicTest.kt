package org.buildmosaic.core

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.buildmosaic.core.injection.canvas
import org.buildmosaic.core.injection.withMosaic
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.measureTime

@Suppress("LargeClass", "FunctionMaxLength")
class MosaicTest {
  @Test
  fun `should retrieve a singleTile asynchronously`() =
    runTest {
      val testDispatcher = StandardTestDispatcher(testScheduler)
      withContext(testDispatcher) {
        canvas { }.withMosaic {
          val mosaic = this

          val resultValue = "test-result"
          val delayLength = 100L
          val testTile =
            singleTile {
              delay(delayLength)
              resultValue
            }

          var result: Deferred<String>? = null
          launch {
            val workDuration =
              testScheduler.timeSource.measureTime {
                result = mosaic.composeAsync(testTile)
              }
            assertEquals(0.milliseconds, workDuration)
          }

          testScheduler.runCurrent()
          assertNotNull(result)
          assertFalse(result.isCompleted)
          testScheduler.advanceTimeBy(delayLength.milliseconds)
          testScheduler.runCurrent()
          assertTrue(result.isCompleted)
          assertEquals(resultValue, result.await())
        }
      }
    }

  @Test
  fun `should retrieve a singleTile synchronously`() =
    runTest {
      val testDispatcher = StandardTestDispatcher(testScheduler)
      withContext(testDispatcher) {
        canvas { }.withMosaic {
          val mosaic = this

          val resultValue = "test-result"
          val delayLength = 100L
          val testTile =
            singleTile {
              delay(delayLength)
              resultValue
            }

          var result: String? = null
          launch {
            val workDuration =
              testScheduler.timeSource.measureTime {
                result = mosaic.compose(testTile)
              }
            assertEquals(delayLength.milliseconds, workDuration)
          }

          testScheduler.runCurrent()
          assertNull(result)
          testScheduler.advanceTimeBy(delayLength.milliseconds)
          testScheduler.runCurrent()
          assertEquals(resultValue, result)
        }
      }
    }

  @Test
  fun `multi Tile asynchronous composition returns before its producer finishes`() =
    runTest {
      val testDispatcher = StandardTestDispatcher(testScheduler)
      withContext(testDispatcher) {
        canvas { }.withMosaic {
          val mosaic = this

          val resultMap = mapOf("a" to "foo", "b" to "bar", "c" to "baz")
          val delayLength = 100L
          val testTile =
            multiTile {
              delay(delayLength)
              resultMap
            }

          var result: Map<String, Deferred<String>>? = null
          launch {
            val workDuration =
              testScheduler.timeSource.measureTime {
                result = mosaic.composeAsync(testTile, resultMap.keys)
              }
            assertEquals(0.milliseconds, workDuration)
          }

          testScheduler.runCurrent()
          assertNotNull(result)
          assertTrue(result.filterValues { it.isCompleted }.isEmpty())
          testScheduler.advanceTimeBy(delayLength.milliseconds)
          testScheduler.runCurrent()
          assertTrue(result.filterValues { !it.isCompleted }.isEmpty())
          assertEquals(resultMap, result.mapValues { it.value.await() })
        }
      }
    }

  @Test
  fun `should retrieve a multiTile synchronously`() =
    runTest {
      val testDispatcher = StandardTestDispatcher(testScheduler)
      withContext(testDispatcher) {
        canvas { }.withMosaic {
          val mosaic = this

          val resultMap = mapOf("a" to "foo", "b" to "bar", "c" to "baz")
          val delayLength = 100L
          val testTile =
            multiTile {
              delay(delayLength)
              resultMap
            }

          var result: Map<String, String>? = null
          launch {
            val workDuration =
              testScheduler.timeSource.measureTime {
                result = mosaic.compose(testTile, resultMap.keys)
              }
            assertEquals(delayLength.milliseconds, workDuration)
          }

          testScheduler.runCurrent()
          assertNull(result)
          testScheduler.advanceTimeBy(delayLength.milliseconds)
          testScheduler.runCurrent()
          assertEquals(resultMap, result)
        }
      }
    }

  @Test
  fun `should receive a single key from a multiTile asynchronously`() =
    runTest {
      val testDispatcher = StandardTestDispatcher(testScheduler)
      withContext(testDispatcher) {
        canvas { }.withMosaic {
          val mosaic = this

          val resultMap = mapOf("a" to "foo", "b" to "bar", "c" to "baz")
          val delayLength = 100L
          val testTile =
            multiTile {
              delay(delayLength)
              resultMap
            }

          var result: Deferred<String>? = null
          launch {
            val workDuration =
              testScheduler.timeSource.measureTime {
                result = mosaic.composeAsync(testTile, "a")
              }
            assertEquals(0.milliseconds, workDuration)
          }

          testScheduler.runCurrent()
          assertNotNull(result)
          assertFalse(result.isCompleted)
          testScheduler.advanceTimeBy(delayLength.milliseconds)
          testScheduler.runCurrent()
          assertTrue(result.isCompleted)
          assertEquals(resultMap["a"], result.await())
        }
      }
    }

  @Test
  fun `should receive a single key from a multiTile synchronously`() =
    runTest {
      val testDispatcher = StandardTestDispatcher(testScheduler)
      withContext(testDispatcher) {
        canvas { }.withMosaic {
          val mosaic = this

          val resultMap = mapOf("a" to "foo", "b" to "bar", "c" to "baz")
          val delayLength = 100L
          val testTile =
            multiTile {
              delay(delayLength)
              resultMap
            }

          var result: String? = null
          launch {
            val workDuration =
              testScheduler.timeSource.measureTime {
                result = mosaic.compose(testTile, "a")
              }
            assertEquals(delayLength.milliseconds, workDuration)
          }

          testScheduler.runCurrent()
          assertNull(result)
          testScheduler.advanceTimeBy(delayLength.milliseconds)
          testScheduler.runCurrent()
          assertEquals(resultMap["a"], result)
        }
      }
    }

  @Test
  fun `dependency failures are retained without retry and unrelated work remains usable`() =
    runTest {
      val failure = IllegalStateException("dependency")
      var calls = 0
      val failed =
        singleTile<String> {
          calls++
          throw failure
        }
      val subject = singleTile { compose(failed) }
      canvas {}.withMosaic {
        repeat(2) {
          assertEquals(failure.message, assertFailsWith<IllegalStateException> { compose(subject) }.message)
          assertEquals(failure.message, assertFailsWith<IllegalStateException> { compose(failed) }.message)
        }
        assertEquals(1, calls)
        assertEquals("healthy", compose(singleTile { "healthy" }))
      }
    }

  @Test
  fun `should return empty response if no keys are provided`() =
    runTest {
      val testDispatcher = StandardTestDispatcher(testScheduler)
      withContext(testDispatcher) {
        canvas { }.withMosaic {
          val mosaic = this
          val tile = multiTile<String, String> { error("empty requests must not execute the provider") }
          assertEquals(emptyMap(), mosaic.compose(tile, emptyList()))
        }
      }
    }
}

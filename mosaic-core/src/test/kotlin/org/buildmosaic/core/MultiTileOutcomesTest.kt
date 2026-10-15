package org.buildmosaic.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.buildmosaic.core.injection.canvas
import org.buildmosaic.core.injection.withMosaic
import org.buildmosaic.core.observation.RecordingObserver
import org.buildmosaic.core.observation.installExecutionObserver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@Suppress("LargeClass") // Exercise retained outcomes and physical failure boundaries with observation parity.
class MultiTileOutcomesTest {
  @Test fun nullableBulkRetainsPresentAndMissingOutcomes() =
    runTest {
      for (observed in listOf(false, true)) {
        canvas { if (observed) installExecutionObserver { RecordingObserver() } }.withMosaic {
          var calls = 0
          val tile =
            multiTile<Int, String?> {
              calls++
              mapOf(1 to "one", 2 to null)
            }
          val values = composeAsync(tile, listOf(1, 2, 3, 4))
          assertEquals("one", values.getValue(1).await())
          assertNull(values.getValue(2).await())
          for (key in listOf(3, 4)) {
            val failure = assertFailsWith<NoSuchElementException> { values.getValue(key).await() }
            assertEquals("Batch result missing key $key", failure.message)
            assertSame(values.getValue(key), composeAsync(tile, key))
          }
          assertFailsWith<NoSuchElementException> { compose(tile, listOf(1, 2, 3)) }
          assertEquals(mapOf(1 to "one", 2 to null), compose(tile, listOf(1, 2)))
          assertEquals(1, calls)
        }
      }
    }

  @Test fun throwingBulkInvocationRetainsFailureForAllItsKeys() =
    runTest {
      for (observed in listOf(false, true)) {
        canvas { if (observed) installExecutionObserver { RecordingObserver() } }.withMosaic {
          var calls = 0
          val tile =
            multiTile<Int, Int> {
              calls++
              error("provider failed")
            }
          val values = composeAsync(tile, listOf(1, 2, 3))
          for (key in values.keys) {
            assertFailsWith<IllegalStateException> { values.getValue(key).await() }
            assertFailsWith<IllegalStateException> { compose(tile, key) }
            assertSame(values.getValue(key), composeAsync(tile, key))
          }
          assertEquals(1, calls)
        }
      }
    }

  @Test fun overlappingPerKeyCallersRetainOutcomes() =
    runTest {
      for (observed in listOf(false, true)) {
        canvas { if (observed) installExecutionObserver { RecordingObserver() } }.withMosaic {
          val gates = (1..3).associateWith { CompletableDeferred<Unit>() }
          val calls = mutableListOf<Int>()
          val tile =
            perKeyTile<Int, Int> { key ->
              calls += key
              gates.getValue(key).await()
              if (key == 2) error("key failed")
              key * 10
            }
          val first = composeAsync(tile, listOf(1, 2))
          val overlapping = composeAsync(tile, listOf(2, 3))
          assertSame(first.getValue(2), overlapping.getValue(2))
          val strict = async { runCatching { compose(tile, listOf(1, 2)) } }
          testScheduler.runCurrent()
          gates.getValue(2).complete(Unit)
          testScheduler.runCurrent()
          assertFailsWith<IllegalStateException> { overlapping.getValue(2).await() }
          assertFalse(first.getValue(1).isCompleted)
          assertFalse(overlapping.getValue(3).isCompleted)
          gates.getValue(3).complete(Unit)
          assertEquals(30, compose(tile, 3))
          gates.getValue(1).complete(Unit)
          assertTrue(strict.await().exceptionOrNull() is IllegalStateException)
          assertEquals(mapOf(1 to 10, 3 to 30), compose(tile, listOf(1, 3)))
          assertFailsWith<IllegalStateException> { compose(tile, 2) }
          assertEquals(listOf(1, 2, 3), calls.sorted())
        }
      }
    }

  @Test fun chunkFailureAndPartialMapsOnlyAffectTheirOwnKeys() =
    runTest {
      for (observed in listOf(false, true)) {
        canvas { if (observed) installExecutionObserver { RecordingObserver() } }.withMosaic {
          val successful = CompletableDeferred<Unit>()
          val calls = mutableListOf<List<Int>>()
          val tile =
            chunkedMultiTile<Int, String?>(2) { chunk ->
              calls += chunk
              when (chunk.first()) {
                1 -> {
                  successful.await()
                  mapOf(1 to "one", 2 to null)
                }
                3 -> error("chunk failed")
                else -> mapOf(5 to "five")
              }
            }
          val first = composeAsync(tile, listOf(1, 2, 3, 4, 5, 6))
          val overlap = composeAsync(tile, listOf(2, 4, 6))
          for (key in overlap.keys) assertSame(first.getValue(key), overlap.getValue(key))
          testScheduler.runCurrent()
          for (key in listOf(3, 4)) assertFailsWith<IllegalStateException> { first.getValue(key).await() }
          assertEquals("five", first.getValue(5).await())
          assertFailsWith<NoSuchElementException> { first.getValue(6).await() }
          assertFalse(first.getValue(1).isCompleted)
          successful.complete(Unit)
          assertEquals(mapOf(1 to "one", 2 to null, 5 to "five"), compose(tile, listOf(1, 2, 5)))
          assertFailsWith<IllegalStateException> { compose(tile, listOf(1, 3)) }
          assertFailsWith<NoSuchElementException> { compose(tile, 6) }
          assertEquals(listOf(listOf(1, 2), listOf(3, 4), listOf(5, 6)), calls)
        }
      }
    }

  @Test fun requestCancellationCleansKeysAndChunksBeforeExit() =
    runTest {
      for (observed in listOf(false, true)) {
        for (chunked in listOf(false, true)) {
          val leaveBlock = CompletableDeferred<Unit>()
          var started = 0
          var cleaned = 0
          val owner =
            launch {
              canvas { if (observed) installExecutionObserver { RecordingObserver() } }.withMosaic {
                val tile =
                  if (chunked) {
                    chunkedMultiTile<Int, Int>(1) {
                      started++
                      try {
                        awaitCancellation()
                      } finally {
                        cleaned++
                      }
                    }
                  } else {
                    perKeyTile<Int, Int> {
                      started++
                      try {
                        awaitCancellation()
                      } finally {
                        cleaned++
                      }
                    }
                  }
                try {
                  compose(tile, listOf(1, 2, 3))
                } finally {
                  withContext(NonCancellable) { leaveBlock.await() }
                }
              }
            }
          try {
            testScheduler.runCurrent()
            assertEquals(3, started)
            owner.cancel()
            testScheduler.runCurrent()
            assertEquals(3, cleaned)
            assertFalse(owner.isCompleted)
          } finally {
            leaveBlock.complete(Unit)
            owner.cancelAndJoin()
          }
        }
      }
    }

  @Test fun attachedChildFailureIsConfinedToPhysicalInvocation() =
    runTest {
      for (observed in listOf(false, true)) {
        for (chunked in listOf(false, true)) {
          canvas { if (observed) installExecutionObserver { RecordingObserver() } }.withMosaic {
            suspend fun fetch(key: Int): Int {
              if (key == 2) {
                CoroutineScope(currentCoroutineContext()).launch { error("child failed") }
                awaitCancellation()
              }
              return key
            }
            val tile =
              if (chunked) {
                chunkedMultiTile<Int, Int>(1) { keys -> keys.associateWith { fetch(it) } }
              } else {
                perKeyTile<Int, Int> { fetch(it) }
              }
            val values = composeAsync(tile, listOf(1, 2, 3))
            assertEquals(1, values.getValue(1).await())
            assertFailsWith<IllegalStateException> { values.getValue(2).await() }
            assertEquals(3, values.getValue(3).await())
            assertEquals(mapOf(1 to 1, 3 to 3), compose(tile, listOf(1, 3)))
          }
        }
      }
    }

  @Test fun waiterCancellationPreservesKeysAndChunks() =
    runTest {
      for (observed in listOf(false, true)) {
        for (chunked in listOf(false, true)) {
          canvas { if (observed) installExecutionObserver { RecordingObserver() } }.withMosaic {
            val gate = CompletableDeferred<Unit>()
            var calls = 0
            val tile =
              if (chunked) {
                chunkedMultiTile<Int, Int>(1) { keys ->
                  calls++
                  gate.await()
                  keys.associateWith { it }
                }
              } else {
                perKeyTile<Int, Int> { key ->
                  calls++
                  gate.await()
                  key
                }
              }
            val waiter = launch { compose(tile, 1) }
            testScheduler.runCurrent()
            waiter.cancelAndJoin()
            val cached = composeAsync(tile, 1)
            assertFalse(cached.isCancelled)
            gate.complete(Unit)
            assertEquals(1, compose(tile, 1))
            assertEquals(1, calls)
          }
        }
      }
    }

  @Test fun publishedKeysSurviveLateChildFailure() =
    runTest {
      for (observed in listOf(false, true)) {
        for (chunked in listOf(false, true)) {
          canvas { if (observed) installExecutionObserver { RecordingObserver() } }.withMosaic {
            val gate = CompletableDeferred<Unit>()

            suspend fun fetch(key: Int): Int {
              CoroutineScope(currentCoroutineContext()).launch {
                gate.await()
                error("late child failure")
              }
              return key
            }
            val tile =
              if (chunked) {
                chunkedMultiTile<Int, Int>(2) { keys -> keys.associateWith { fetch(it) } }
              } else {
                perKeyTile<Int, Int> { fetch(it) }
              }
            assertEquals(mapOf(1 to 1, 2 to 2), compose(tile, listOf(1, 2)))
            gate.complete(Unit)
            testScheduler.runCurrent()
            assertEquals(mapOf(1 to 1, 2 to 2), compose(tile, listOf(1, 2)))
          }
        }
      }
    }
}

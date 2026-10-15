package org.buildmosaic.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.buildmosaic.core.injection.Canvas
import org.buildmosaic.core.injection.canvas
import org.buildmosaic.core.injection.withMosaic
import org.buildmosaic.core.observation.RecordingObserver
import org.buildmosaic.core.observation.installExecutionObserver
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

@Suppress("LargeClass") // Lifetime cases share the public scoped entry point and observation parity matrix.
class MosaicLifetimeTest {
  private suspend fun configured(observed: Boolean): Canvas =
    canvas { if (observed) installExecutionObserver { RecordingObserver() } }

  @Test fun normalExitCancelsSpeculationAndWaitsForCleanup() =
    runTest {
      for (observed in listOf(false, true)) {
        val cleanup = CompletableDeferred<Unit>()
        var finished = false
        lateinit var shared: Deferred<Int>
        val owner =
          async {
            configured(observed).withMosaic {
              shared =
                composeAsync(
                  singleTile {
                    try {
                      awaitCancellation()
                    } finally {
                      withContext(NonCancellable) {
                        cleanup.await()
                        finished = true
                      }
                    }
                  },
                )
              testScheduler.runCurrent()
              "response"
            }
          }
        testScheduler.runCurrent()
        try {
          assertTrue(shared.isCancelled)
          assertFalse(owner.isCompleted)
        } finally {
          cleanup.complete(Unit)
        }
        assertEquals("response", owner.await())
        assertTrue(finished)
      }
    }

  @Test fun exceptionalExitPreservesFailureAfterCleanup() =
    runTest {
      for (observed in listOf(false, true)) {
        var cleaned = false
        val original = IllegalArgumentException("handler failed")
        val failure =
          assertFailsWith<IllegalArgumentException> {
            configured(observed).withMosaic {
              composeAsync(
                singleTile {
                  try {
                    awaitCancellation()
                  } finally {
                    cleaned = true
                  }
                },
              )
              testScheduler.runCurrent()
              throw original
            }
          }
        assertSame(original, failure)
        assertTrue(cleaned)
      }
    }

  @Test fun requestCancellationReachesProducerBeforeTeardown() =
    runTest {
      for (observed in listOf(false, true)) {
        val leaveBlock = CompletableDeferred<Unit>()
        var cleaned = false
        lateinit var shared: Deferred<Nothing>
        val owner =
          launch {
            configured(observed).withMosaic {
              shared =
                composeAsync(
                  singleTile {
                    try {
                      awaitCancellation()
                    } finally {
                      withContext(NonCancellable) {
                        kotlinx.coroutines.delay(10)
                        cleaned = true
                      }
                    }
                  },
                )
              try {
                shared.await()
              } finally {
                // Keep the block open: withMosaic's finally cannot cause the observed cancellation.
                withContext(NonCancellable) { leaveBlock.await() }
              }
            }
          }
        try {
          testScheduler.runCurrent()
          owner.cancel()
          testScheduler.advanceUntilIdle()
          assertTrue(shared.isCancelled)
          assertTrue(cleaned)
          assertFalse(owner.isCompleted)
        } finally {
          leaveBlock.complete(Unit)
          owner.cancelAndJoin()
        }
        assertTrue(owner.children.none())
      }
    }

  @Test fun inheritsDispatcherSchedulerAndRequestContext() =
    runTest {
      for (observed in listOf(false, true)) {
        val ambient = ThreadLocal<String>()
        withContext(CoroutineName("request") + ambient.asContextElement("tenant")) {
          configured(observed).withMosaic {
            var started = false
            val result =
              composeAsync(
                singleTile {
                  started = true
                  assertEquals("request", currentCoroutineContext()[CoroutineName]?.name)
                  assertEquals("tenant", ambient.get())
                  kotlinx.coroutines.delay(100)
                  7
                },
              )
            assertFalse(started)
            testScheduler.runCurrent()
            assertTrue(started)
            assertEquals(7, result.await())
          }
        }
      }
    }

  @Test fun cancelBeforeDispatchSettlesAllStrategies() =
    runTest {
      for (observed in listOf(false, true)) {
        val results = mutableListOf<Deferred<*>>()
        configured(observed).withMosaic {
          results += composeAsync(singleTile { error("must not start") })
          results += composeAsync(multiTile<Int, Int> { error("must not start") }, listOf(1, 2)).values
          results += composeAsync(perKeyTile<Int, Int> { error("must not start") }, listOf(1, 2)).values
          results += composeAsync(chunkedMultiTile<Int, Int>(1) { error("must not start") }, listOf(1, 2)).values
        }
        assertTrue(results.all { it.isCompleted && it.isCancelled })
      }
    }

  @Test fun cancellationInsideCaptureSettlesReservations() =
    runTest {
      for (multi in listOf(false, true)) {
        val observer = RecordingObserver()
        val canvas = canvas { installExecutionObserver { observer } }
        lateinit var result: Deferred<Int>
        val owner =
          launch {
            val ownerJob = currentCoroutineContext()[Job]!!
            canvas.withMosaic {
              observer.captureHook = { ownerJob.cancel() }
              result =
                if (multi) {
                  composeAsync(multiTile<Int, Int> { error("must not start") }, 1)
                } else {
                  composeAsync(singleTile { error("must not start") })
                }
            }
          }
        owner.join()
        assertTrue(result.isCompleted && result.isCancelled)
        assertTrue(observer.executions.isEmpty())
      }
    }

  @Test fun escapedMosaicRejectsProducerStartup() =
    runTest {
      for (observed in listOf(false, true)) {
        val escaped = configured(observed).withMosaic { this }
        val single = escaped.composeAsync(singleTile { error("shutdown") })
        val multi = escaped.composeAsync(perKeyTile<Int, Int> { error("shutdown") }, 1)
        testScheduler.runCurrent()
        assertTrue(single.isCompleted && single.isCancelled)
        assertTrue(multi.isCompleted && multi.isCancelled)
      }
    }

  @Test fun admissionCancellationRacesSettleResults() =
    runTest {
      for (observed in listOf(false, true)) {
        repeat(100) {
          val results = mutableListOf<Deferred<*>>()
          val owner =
            launch {
              configured(observed).withMosaic {
                val mosaic = this
                val requestJob = currentCoroutineContext()[Job]!!
                // Independent admission can finish returning accepted results after request cancellation.
                withContext(NonCancellable + Dispatchers.Default) {
                  val admission =
                    async {
                      results += mosaic.composeAsync(singleTile { 1 })
                      results +=
                        mosaic.composeAsync(
                          multiTile<Int, Int> {
                              keys ->
                            keys.associateWith { it }
                          },
                          listOf(1, 2),
                        ).values
                      results += mosaic.composeAsync(perKeyTile<Int, Int> { it }, listOf(1, 2)).values
                      val chunked = chunkedMultiTile<Int, Int>(1) { keys -> keys.associateWith { it } }
                      results += mosaic.composeAsync(chunked, listOf(1, 2)).values
                    }
                  val cancellation = async { requestJob.cancel() }
                  admission.await()
                  cancellation.await()
                }
              }
            }
          owner.join()
          assertTrue(results.all { it.isCompleted })
          assertTrue(owner.children.none())
        }
      }
    }

  @Test fun childFailuresPreserveSiblingAndPublishedResults() =
    runTest {
      for (observed in listOf(false, true)) {
        configured(observed).withMosaic {
          val siblingGate = CompletableDeferred<Unit>()
          val sibling =
            composeAsync(
              singleTile {
                siblingGate.await()
                9
              },
            )
          val failed =
            composeAsync(
              singleTile<Int> {
                CoroutineScope(currentCoroutineContext()).launch { error("child failure") }
                awaitCancellation()
              },
            )
          testScheduler.runCurrent()
          assertFailsWith<IllegalStateException> { failed.await() }
          siblingGate.complete(Unit)
          assertEquals(9, sibling.await())
          val childGate = CompletableDeferred<Unit>()
          val cleaned = AtomicInteger()
          val publishedTile =
            singleTile {
              CoroutineScope(currentCoroutineContext()).launch {
                try {
                  childGate.await()
                  error("late child failure")
                } finally {
                  cleaned.incrementAndGet()
                }
              }
              42
            }
          assertEquals(42, compose(publishedTile))
          childGate.complete(Unit)
          testScheduler.runCurrent()
          assertEquals(42, compose(publishedTile))
          assertEquals(1, cleaned.get())
        }
      }
    }

  @Test fun eachInvocationGetsAFreshCache() =
    runTest {
      val canvas = configured(false)
      var calls = 0
      val tile = singleTile { ++calls }
      repeat(2) { index ->
        canvas.withMosaic {
          assertEquals(index + 1, compose(tile))
          assertEquals(index + 1, compose(tile))
        }
      }
      assertEquals(2, calls)
    }
}

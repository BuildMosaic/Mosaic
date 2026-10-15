package org.buildmosaic.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.buildmosaic.core.injection.canvas
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

@Suppress("LargeClass")
class MultiTileCoalescingTest {
  private val emptyCanvas =
    runBlocking { canvas {} }

  @Test fun pendingSiblingsCoalesceAndSharePlaceholders() =
    runTest {
      val mosaic = MosaicImpl(emptyCanvas, StandardTestDispatcher(testScheduler))
      val calls = mutableListOf<Set<String>>()
      val tile =
        multiTile<String, String> { keys ->
          calls += keys
          keys.associateWith { it }
        }
      val first = mosaic.composeAsync(tile, listOf("A", "B", "C"))
      val second = mosaic.composeAsync(tile, listOf("C", "D", "E"))
      assertSame(first.getValue("C"), second.getValue("C"))
      testScheduler.runCurrent()
      assertEquals(listOf(setOf("A", "B", "C", "D", "E")), calls)
      assertEquals("C", first.getValue("C").await())
      assertEquals("C", mosaic.compose(tile, "C"))
      assertEquals(1, calls.size)
    }

  @Test fun startedBatchIsFixedAndLaterBatchCanOverlap() =
    runTest {
      val mosaic = MosaicImpl(emptyCanvas, StandardTestDispatcher(testScheduler))
      val firstGate = CompletableDeferred<Unit>()
      val calls = mutableListOf<Set<String>>()
      val tile =
        multiTile<String, String> { keys ->
          calls += keys
          if ("A" in keys) firstGate.await()
          keys.associateWith { it }
        }
      val first = mosaic.composeAsync(tile, listOf("A", "B"))
      testScheduler.runCurrent()
      val second = mosaic.composeAsync(tile, listOf("C", "D"))
      assertSame(first.getValue("A"), mosaic.composeAsync(tile, "A"))
      testScheduler.runCurrent()
      assertEquals(listOf(setOf("A", "B"), setOf("C", "D")), calls)
      assertFalse(first.getValue("A").isCompleted)
      assertEquals("D", second.getValue("D").await())
      firstGate.complete(Unit)
      testScheduler.runCurrent()
      assertEquals("A", first.getValue("A").await())
    }

  @Test fun delayedDeeperConsumerReusesKeysAndStartsLaterBatch() =
    runTest {
      val mosaic = MosaicImpl(emptyCanvas, StandardTestDispatcher(testScheduler))
      val gate = CompletableDeferred<Unit>()
      val calls = mutableListOf<Set<String>>()
      val products =
        multiTile<String, String> { keys ->
          calls += keys
          keys.associateWith { it }
        }
      val first = singleTile { compose(products, listOf("A", "B", "C")) }
      val deeper = singleTile { compose(products, listOf("C", "D", "E")) }
      val later =
        singleTile {
          gate.await()
          compose(deeper)
        }
      val firstResult = mosaic.composeAsync(first)
      val laterResult = mosaic.composeAsync(later)
      testScheduler.runCurrent()
      assertEquals(setOf("A", "B", "C"), firstResult.await().keys)
      assertFalse(laterResult.isCompleted)
      gate.complete(Unit)
      testScheduler.runCurrent()
      assertEquals(setOf("C", "D", "E"), laterResult.await().keys)
      assertEquals(listOf(setOf("A", "B", "C"), setOf("D", "E")), calls)
    }

  @Test fun unawaitedBatchAndPerRequestStateStillProgress() =
    runTest {
      val dispatcher = StandardTestDispatcher(testScheduler)
      val a = MosaicImpl(emptyCanvas, dispatcher)
      val b = MosaicImpl(emptyCanvas, dispatcher)
      val calls = mutableListOf<Set<String>>()
      val tile =
        multiTile<String, String> { keys ->
          calls += keys
          keys.associateWith { it }
        }
      a.composeAsync(tile, "A") // deliberately never awaited
      b.composeAsync(tile, "A")
      testScheduler.runCurrent()
      assertEquals(listOf(setOf("A"), setOf("A")), calls)
    }

  @Test fun chunkingRunsAfterCoalescing() =
    runTest {
      val mosaic = MosaicImpl(emptyCanvas, StandardTestDispatcher(testScheduler))
      val chunks = mutableListOf<List<String>>()
      val chunked =
        chunkedMultiTile<String, String>(3) { keys ->
          chunks += keys
          keys.associateWith { it }
        }
      mosaic.composeAsync(chunked, listOf("A", "B", "C"))
      mosaic.composeAsync(chunked, listOf("C", "D", "E"))
      testScheduler.runCurrent()
      assertEquals(listOf(listOf("A", "B", "C"), listOf("D", "E")), chunks)
    }

  @Test fun cancellationSettlesPendingAndExecutingPlaceholders() =
    runTest {
      val dispatcher = StandardTestDispatcher(testScheduler)
      val pendingMosaic = MosaicImpl(emptyCanvas, dispatcher)
      val tile = multiTile<String, String> { keys -> keys.associateWith { it } }
      val pending = pendingMosaic.composeAsync(tile, listOf("A", "B"))
      (pendingMosaic as CoroutineScope).cancel()
      testScheduler.runCurrent()
      assertTrue(pending.values.all { it.isCancelled })

      val executingMosaic = MosaicImpl(emptyCanvas, dispatcher)
      val gate = CompletableDeferred<Unit>()
      val slow =
        multiTile<String, String> { keys ->
          gate.await()
          keys.associateWith { it }
        }
      val executing = executingMosaic.composeAsync(slow, listOf("A", "B"))
      testScheduler.runCurrent()
      val next = executingMosaic.composeAsync(slow, "C")
      (executingMosaic as CoroutineScope).cancel()
      testScheduler.runCurrent()
      assertTrue(executing.values.all { it.isCancelled })
      assertTrue(next.isCancelled)
      assertFailsWith<CancellationException> { executing.getValue("A").await() }
    }

  @Test fun concurrentBatchesNeverLoseOrDuplicateKeys() {
    repeat(50) {
      val mosaic = MosaicImpl(emptyCanvas, Dispatchers.Default)
      val calls = java.util.concurrent.ConcurrentLinkedQueue<Set<Int>>()
      val tile =
        multiTile<Int, Int> { keys ->
          calls += keys
          keys.associateWith { it }
        }
      val ready = CountDownLatch(1)
      val start = CountDownLatch(1)
      val results = arrayOfNulls<Map<Int, kotlinx.coroutines.Deferred<Int>>>(2)
      val producer =
        thread {
          ready.countDown()
          start.await()
          results[0] = mosaic.composeAsync(tile, (0 until 20).toList())
        }
      assertTrue(ready.await(5, TimeUnit.SECONDS))
      start.countDown()
      results[1] = mosaic.composeAsync(tile, (10 until 30).toList())
      producer.join(5_000)
      assertFalse(producer.isAlive)
      runBlocking {
        withTimeout(5_000) { results.filterNotNull().flatMap { it.values }.forEach { it.await() } }
      }
      assertEquals((0 until 30).toSet(), calls.flatMap { it }.toSet())
      assertEquals(30, calls.sumOf { it.size })
      assertSame(results[0]!!.getValue(10), results[1]!!.getValue(10))
    }
  }

  private class CallbackKey(val id: Int, val onFirstHash: (() -> Unit)? = null) {
    private val firstHash = AtomicBoolean(true)

    override fun hashCode(): Int {
      if (firstHash.getAndSet(false)) onFirstHash?.invoke()
      return id
    }

    override fun equals(other: Any?): Boolean = other is CallbackKey && id == other.id
  }

  @Test fun keyCodeDoesNotHoldPendingMonitor() {
    val mosaic = MosaicImpl(emptyCanvas, Dispatchers.Default)
    val calls = java.util.concurrent.ConcurrentLinkedQueue<Set<Int>>()
    val tile =
      multiTile<CallbackKey, Int> { keys ->
        calls += keys.map { it.id }.toSet()
        keys.associateWith { it.id }
      }
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    val a = CallbackKey(1)
    val b =
      CallbackKey(2) {
        entered.countDown()
        check(release.await(5, TimeUnit.SECONDS))
      }
    val c = CallbackKey(3)
    val error = AtomicReference<Throwable?>()
    val producer =
      thread {
        try {
          mosaic.composeAsync(tile, listOf(a, b))
        } catch (failure: Throwable) {
          error.set(failure)
        }
      }
    assertTrue(entered.await(5, TimeUnit.SECONDS))
    val cResult = mosaic.composeAsync(tile, c)
    runBlocking { withTimeout(5_000) { assertEquals(3, cResult.await()) } }
    release.countDown()
    producer.join(5_000)
    assertFalse(producer.isAlive)
    assertEquals(null, error.get())
    runBlocking { withTimeout(5_000) { mosaic.compose(tile, listOf(a, b)) } }
    assertEquals(setOf(1, 2, 3), calls.flatMap { it }.toSet())
    assertEquals(3, calls.sumOf { it.size })
  }
}

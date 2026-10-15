package org.buildmosaic.core.observation

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.buildmosaic.core.MosaicImpl
import org.buildmosaic.core.injection.canvas
import org.buildmosaic.core.multiTile
import org.buildmosaic.core.singleTile
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@Suppress("LargeClass") // Related batch/concurrency scenarios share the same recording fixtures.
class MultiTileObservationTest {
  @Test fun reservationRaceLoserReleasesPlaceholder() =
    runTest {
      val observer = RecordingObserver()
      val mosaic = MosaicImpl(canvas { installExecutionObserver { observer } }, StandardTestDispatcher(testScheduler))
      val barrier = CyclicBarrier(2)
      val hashes = ThreadLocal<Int>()
      val key =
        object {
          override fun hashCode(): Int {
            hashes.get()?.let { count ->
              hashes.set(count + 1)
              // Both initial reads miss before either putIfAbsent can publish its candidate.
              if (count == 1) barrier.await(5, TimeUnit.SECONDS)
            }
            return 1
          }
        }
      val tile = multiTile<Any, Int> { keys -> keys.associateWith { 9 } }
      val first = AtomicReference<Deferred<Int>>()
      val second = AtomicReference<Deferred<Int>>()
      val failure = AtomicReference<Throwable?>()
      val workers =
        listOf(first, second).map { result ->
          thread {
            hashes.set(0)
            runCatching { result.set(mosaic.composeAsync(tile, key)) }.onFailure { failure.set(it) }
          }
        }
      workers.forEach { it.join(5_000) }
      assertTrue(workers.none { it.isAlive })
      assertNull(failure.get())
      assertSame(first.get(), second.get())
      assertEquals(1, observer.captures)
      testScheduler.runCurrent()
      assertEquals(9, first.get().await())
      // Neither the winning reservation nor its race loser leaves a parked child.
      assertTrue(checkNotNull(mosaic.coroutineContext[Job]).children.none())
      assertEquals(1, observer.executions.size)
      assertEquals(1, observer.executions.single().start.contributors.totalCount)
      assertTrue(observer.failures.isEmpty())
    }

  @Test fun overlappingReservationsPublishBeforeCapture() =
    runTest {
      val observer = RecordingObserver()
      val mosaic = MosaicImpl(canvas { installExecutionObserver { observer } }, StandardTestDispatcher(testScheduler))
      val calls = mutableListOf<Set<Int>>()
      val tile =
        multiTile<Int, Int> { keys ->
          calls += keys
          keys.associateWith { it }
        }
      var nested: Map<Int, Deferred<Int>>? = null
      observer.captureHook = {
        observer.captureHook = null
        nested = mosaic.composeAsync(tile, listOf(2, 3))
      }
      val outer = mosaic.composeAsync(tile, listOf(1, 2))
      assertSame(outer.getValue(2), checkNotNull(nested).getValue(2))
      testScheduler.runCurrent()
      assertEquals(listOf(setOf(1, 2, 3)), calls)
      val start = observer.executions.single().start
      assertEquals(3, start.batchSize)
      assertEquals(2, start.contributors.totalCount)
      // The reentrant group was accepted first, so it is the batch initiator.
      assertEquals(2, assertIs<TestCaller>(start.contributors.initiating.context).number)
      assertEquals(1, assertIs<TestCaller>(start.contributors.additional.single().context).number)
    }

  @Test fun callerProjectionPreservesImmutableMetadata() {
    val originalCaller = CallerSnapshot(TestIdentity(1), TestCaller(1))
    val input = MutableList(63) { originalCaller }
    val original =
      ExecutionStart(ExecutionKind.MULTI, 100, Contributors(originalCaller, input, 100), 123, "Products")
    input.clear()
    val projectedIdentity = TestIdentity(2)
    val projected = original.mapCallers { it.copy(execution = projectedIdentity, context = null) }
    assertEquals("Products", projected.tileName)
    assertEquals(original.kind, projected.kind)
    assertEquals(original.batchSize, projected.batchSize)
    assertEquals(original.startedAtNanos, projected.startedAtNanos)
    assertEquals(100, projected.contributors.totalCount)
    assertTrue(projected.contributors.truncated)
    assertEquals(63, projected.contributors.additional.size)
    (listOf(projected.contributors.initiating) + projected.contributors.additional).forEach {
      assertSame(projectedIdentity, it.execution)
      assertNull(it.context)
    }
    assertSame(originalCaller, original.contributors.initiating)
    assertEquals(63, original.contributors.additional.size)
    assertFailsWith<UnsupportedOperationException> {
      (projected.contributors.additional as MutableList).clear()
    }
  }

  @Test fun reservationGroupsResolveToOneBatchIdentity() =
    runTest {
      val observer = RecordingObserver()
      val mosaic = MosaicImpl(canvas { installExecutionObserver { observer } }, StandardTestDispatcher(testScheduler))
      val tile = multiTile<Int, Int> { keys -> keys.associateWith { it } }
      val a = mosaic.composeAsync(singleTile { compose(tile, listOf(1, 2)) })
      val b = mosaic.composeAsync(singleTile { compose(tile, listOf(2, 3)) })
      testScheduler.runCurrent()
      assertEquals(setOf(1, 2), a.await().keys)
      assertEquals(setOf(2, 3), b.await().keys)
      val batch = observer.executions.single { it.start.kind == ExecutionKind.MULTI }
      val first = observer.executions[0].dependencies[0]
      val second = observer.executions[1].dependencies[1]
      assertNotSame(first, second)
      assertSame(first, observer.executions[1].dependencies[0])
      assertSame(batch.identity, assertIs<ProducerResolution.Published>(first.resolution).identity)
      assertSame(batch.identity, assertIs<ProducerResolution.Published>(second.resolution).identity)
      assertSame(observer.executions[0].identity, batch.start.contributors.initiating.execution)
      assertSame(observer.executions[1].identity, batch.start.contributors.additional.single().execution)
      val nested = mosaic.composeAsync(singleTile { compose(tile, listOf(1, 3)) })
      testScheduler.runCurrent()
      nested.await()
      assertSame(first, observer.executions.last().dependencies[0])
      assertSame(second, observer.executions.last().dependencies[1])
    }

  @Test fun retentionCapsSnapshotsWithoutDroppingWork() =
    runTest {
      for (count in listOf(64, 65, 128)) {
        val observer = RecordingObserver()
        val mosaic = MosaicImpl(canvas { installExecutionObserver { observer } }, StandardTestDispatcher(testScheduler))
        val tile = multiTile<Int, Int> { keys -> keys.associateWith { it } }
        val results = (1..count).map { mosaic.composeAsync(tile, it) }
        // Existing-only requests do not contribute or capture.
        mosaic.composeAsync(tile, 1)
        assertEquals(count, observer.captures)
        testScheduler.runCurrent()
        assertEquals((1..count).toList(), results.map { it.await() })
        val start = observer.executions.single().start
        assertEquals(count, start.batchSize)
        assertEquals(count.toLong(), start.contributors.totalCount)
        assertEquals(count > 64, start.contributors.truncated)
        assertEquals(63, start.contributors.additional.size)
        assertEquals(1, assertIs<TestCaller>(start.contributors.initiating.context).number)
        assertEquals((2..64).toList(), start.contributors.additional.map { assertIs<TestCaller>(it.context).number })
        @Suppress("UNCHECKED_CAST")
        val mutable = start.contributors.additional as MutableList<CallerSnapshot>
        kotlin.test.assertFailsWith<UnsupportedOperationException> { mutable.clear() }
      }
    }

  @Test fun batchCallerUsesActualExecutionIdentity() =
    runTest {
      val observer = RecordingObserver()
      val mosaic = MosaicImpl(canvas { installExecutionObserver { observer } }, StandardTestDispatcher(testScheduler))
      val child = singleTile { 1 }
      val tile =
        multiTile<Int, Int> { keys ->
          compose(child)
          keys.associateWith { it }
        }
      mosaic.composeAsync(tile, 1)
      mosaic.composeAsync(tile, 2)
      testScheduler.runCurrent()
      val batch = observer.executions[0]
      assertSame(batch.identity, observer.executions[1].start.contributors.initiating.execution)
      assertEquals(2, batch.start.contributors.totalCount)
      assertEquals(1, batch.dependencies.size)
    }

  @Test fun captureDoesNotHoldUpAnAlreadyPendingBatch() =
    runTest {
      val observer = RecordingObserver()
      val mosaic = MosaicImpl(canvas { installExecutionObserver { observer } }, StandardTestDispatcher(testScheduler))
      val entered = CountDownLatch(1)
      val release = CountDownLatch(1)
      val tile = multiTile<Int, Int> { keys -> keys.associateWith { it } }
      val first = mosaic.composeAsync(tile, 1)
      observer.captureHook = {
        entered.countDown()
        check(release.await(5, TimeUnit.SECONDS))
      }
      val second = AtomicReference<Deferred<Int>>()
      val worker = thread { second.set(mosaic.composeAsync(tile, 2)) }
      try {
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        testScheduler.runCurrent()
        assertTrue(first.isCompleted)
        assertTrue(worker.isAlive)
        assertEquals(1, first.await())
      } finally {
        release.countDown()
        worker.join(5_000)
      }
      assertFalse(worker.isAlive)
      testScheduler.runCurrent()
      assertEquals(2, second.get().await())
      assertTrue(observer.failures.isEmpty())
      mosaic.cancel()
    }

  @Test fun crossBatchCaptureReentryHasNoLockInversion() =
    runBlocking {
      val observer = RecordingObserver()
      val mosaic = MosaicImpl(canvas { installExecutionObserver { observer } }, Dispatchers.Default)
      val a = multiTile<Int, Int> { keys -> keys.associateWith { it } }
      val b = multiTile<Int, Int> { keys -> keys.associateWith { it } }
      val arrived = CountDownLatch(2)
      val active = ThreadLocal<Boolean>()
      observer.captureHook = {
        if (active.get() != true) {
          active.set(true)
          arrived.countDown()
          check(arrived.await(5, TimeUnit.SECONDS))
          if (Thread.currentThread().name == "reserve-a") mosaic.composeAsync(b, 2) else mosaic.composeAsync(a, 2)
        }
      }
      val failures = AtomicReference<Throwable?>()
      val one =
        thread(name = "reserve-a", isDaemon = true) {
          runCatching { mosaic.composeAsync(a, 1) }.onFailure { failures.set(it) }
        }
      val two =
        thread(name = "reserve-b", isDaemon = true) {
          runCatching { mosaic.composeAsync(b, 1) }.onFailure { failures.set(it) }
        }
      one.join(5_000)
      two.join(5_000)
      assertFalse(one.isAlive)
      assertFalse(two.isAlive)
      assertNull(failures.get())
      assertTrue(observer.failures.isEmpty())
      observer.captureHook = null
      withTimeout(5_000) {
        assertEquals(setOf(1, 2), mosaic.compose(a, listOf(1, 2)).keys)
        assertEquals(setOf(1, 2), mosaic.compose(b, listOf(1, 2)).keys)
      }
      mosaic.cancel()
    }

  @Test fun cancellationAbandonsNeverStartedProducers() =
    runTest {
      val observer = RecordingObserver()
      val mosaic = MosaicImpl(canvas { installExecutionObserver { observer } }, Dispatchers.Unconfined)
      val tile = multiTile<Int, Int> { keys -> keys.associateWith { it } }
      observer.captureHook = {
        observer.captureHook = null
        mosaic.composeAsync(
          singleTile {
            composeAsync(tile, 1)
            "independent"
          },
        )
        mosaic.cancel()
      }
      val pending = mosaic.composeAsync(tile, listOf(1, 2))
      assertTrue(pending.values.all { it.isCancelled })
      val producer = observer.executions.single().dependencies.single()
      assertEquals(
        UnavailableReason.NEVER_STARTED,
        assertIs<ProducerResolution.Unavailable>(producer.resolution).reason,
      )
      assertTrue(observer.executions.all { it.start.kind == ExecutionKind.SINGLE })
    }

  @Test fun partialResultsRetainValuesAndReportBatchFailure() =
    runTest {
      val observer = RecordingObserver()
      val mosaic = MosaicImpl(canvas { installExecutionObserver { observer } }, StandardTestDispatcher(testScheduler))
      val tile = multiTile<Int, String> { _: Set<Int> -> mapOf(1 to "private value") }
      val results = mosaic.composeAsync(tile, listOf(1, 2))
      testScheduler.runCurrent()
      assertEquals("private value", results.getValue(1).await())
      kotlin.test.assertFailsWith<NoSuchElementException> { results.getValue(2).await() }
      assertEquals(ExecutionOutcome.FAILURE, observer.executions.single().completion?.outcome)
      assertEquals(NoSuchElementException::class.java.name, observer.executions.single().completion?.exceptionClassName)
    }

  @Test fun cancellationAfterAdmissionKeepsBatchOrigins() =
    runTest {
      val observer = RecordingObserver()
      val mosaic = MosaicImpl(canvas { installExecutionObserver { observer } }, StandardTestDispatcher(testScheduler))
      val tile = multiTile<Int, Int> { keys -> keys.associateWith { it } }
      observer.startHook = { if (it.kind == ExecutionKind.MULTI) mosaic.cancel() }
      val a = mosaic.composeAsync(singleTile { compose(tile, 1) })
      val b = mosaic.composeAsync(singleTile { compose(tile, 2) })
      testScheduler.runCurrent()
      assertTrue(a.isCancelled)
      assertTrue(b.isCancelled)
      val batch = observer.executions.single { it.start.kind == ExecutionKind.MULTI }
      assertEquals(ExecutionOutcome.CANCELLATION, batch.completion?.outcome)
      observer.executions.take(2).forEach {
        assertSame(batch.identity, assertIs<ProducerResolution.Published>(it.dependencies.single().resolution).identity)
      }
    }
}

package org.buildmosaic.core.observation

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.buildmosaic.core.MosaicImpl
import org.buildmosaic.core.injection.canvas
import org.buildmosaic.core.multiTile
import org.buildmosaic.core.singleTile
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ObservationWorkFailureTest {
  private class Key(val number: Int, var fail: Boolean = false) {
    override fun hashCode(): Int {
      check(!fail) { "private key failure" }
      return number
    }

    override fun equals(other: Any?): Boolean = other is Key && number == other.number
  }

  @Test fun inlinePreparationDoesNotBorrowCallerExecution() =
    runTest {
      val observer = RecordingObserver()
      val dispatcher =
        object : CoroutineDispatcher() {
          override fun dispatch(
            context: CoroutineContext,
            block: Runnable,
          ) = block.run()
        }
      val mosaic = MosaicImpl(canvas { installExecutionObserver { observer } }, dispatcher)
      val leaf = singleTile { 7 }
      val preparation = singleTile { compose(leaf) }
      var prepare = false
      val key =
        object {
          override fun hashCode(): Int {
            if (prepare) {
              prepare = false
              mosaic.composeAsync(preparation)
            }
            return 1
          }
        }
      val batch = multiTile<Any, Int> { keys -> keys.associateWith { 42 } }
      observer.captureHook = { if (observer.captures == 2) prepare = true }
      assertEquals(42, mosaic.compose(singleTile { compose(batch, key) }))
      val parent = observer.executions.first()
      val prepared = observer.executions[1]
      val nested = observer.executions[2]
      assertNull(prepared.start.contributors.initiating.execution)
      assertSame(prepared.identity, nested.start.contributors.initiating.execution)
      assertEquals(1, parent.dependencies.size)
      assertEquals(1, prepared.dependencies.size)
      assertTrue(observer.failures.isEmpty())
    }

  @Test fun keyPreparationFailureAbandonsOrigin() =
    runTest {
      val observer = RecordingObserver()
      val mosaic = MosaicImpl(canvas { installExecutionObserver { observer } }, StandardTestDispatcher(testScheduler))
      val key = Key(1)
      val tile = multiTile<Key, Int> { error("must not execute") }
      var value: Deferred<Int>? = null
      observer.captureHook = { if (observer.captures == 2) key.fail = true }
      val caller =
        mosaic.composeAsync(
          singleTile {
            value = composeAsync(tile, listOf(key)).values.single()
            1
          },
        )
      testScheduler.runCurrent()
      assertEquals(1, caller.await())
      assertFailsWith<IllegalStateException> { checkNotNull(value).await() }
      val producer = observer.executions.single().dependencies.single()
      assertEquals(
        UnavailableReason.NEVER_STARTED,
        assertIs<ProducerResolution.Unavailable>(producer.resolution).reason,
      )
      assertEquals(ExecutionKind.SINGLE, observer.executions.single().start.kind)
    }

  @Test fun laterKeyFailurePreservesEarlierReservations() =
    runTest {
      for (observed in listOf(false, true)) {
        val observer = RecordingObserver()
        val mosaic =
          MosaicImpl(
            canvas { if (observed) installExecutionObserver { observer } },
            StandardTestDispatcher(testScheduler),
          )
        val calls = mutableListOf<Set<Int>>()
        val tile =
          multiTile<Key, Int> { keys ->
            calls += keys.map { it.number }.toSet()
            keys.associateWith { it.number }
          }
        val first = Key(1)
        assertFailsWith<IllegalStateException> { mosaic.composeAsync(tile, listOf(first, Key(2, true))) }
        testScheduler.runCurrent()
        assertEquals(listOf(setOf(1)), calls)
        assertEquals(1, mosaic.compose(tile, first))
        assertEquals(listOf(setOf(1)), calls)
        if (observed) assertEquals(1, observer.executions.single().start.batchSize)
      }
    }

  @Test fun failingResultLookupKeepsPriorSuccessfulValue() =
    runTest {
      for (observed in listOf(false, true)) {
        val observer = RecordingObserver()
        val mosaic =
          MosaicImpl(
            canvas { if (observed) installExecutionObserver { observer } },
            StandardTestDispatcher(testScheduler),
          )
        val tile =
          multiTile<Int, Int> { _: Set<Int> ->
            object : AbstractMap<Int, Int>() {
              override val entries: Set<Map.Entry<Int, Int>> = emptySet()

              override fun get(key: Int): Int = if (key == 2) error("private lookup") else key
            }
          }
        val results = mosaic.composeAsync(tile, listOf(1, 2, 3))
        testScheduler.runCurrent()
        assertTrue(results.values.all { it.isCompleted })
        assertEquals(1, results.getValue(1).await())
        assertEquals("private lookup", assertFailsWith<IllegalStateException> { results.getValue(2).await() }.message)
        assertEquals("private lookup", assertFailsWith<IllegalStateException> { results.getValue(3).await() }.message)
        if (observed) assertEquals(ExecutionOutcome.FAILURE, observer.executions.single().completion?.outcome)
      }
    }
}

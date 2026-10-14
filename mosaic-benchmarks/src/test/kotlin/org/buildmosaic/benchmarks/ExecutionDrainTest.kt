package org.buildmosaic.benchmarks

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.buildmosaic.core.Mosaic
import org.buildmosaic.core.singleTile
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExecutionDrainTest {
  @Test fun drainWaitsForAttachedChildrenAfterResult() = runBlocking {
    val entered = CompletableDeferred<Unit>()
    val cleaning = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    var finished = false
    lateinit var mosaic: Mosaic
    val root = singleTile {
      mosaic = this
      CoroutineScope(currentCoroutineContext()).launch {
        try {
          entered.complete(Unit)
          awaitCancellation()
        } finally {
          withContext(NonCancellable) {
            cleaning.complete(Unit)
            release.await()
            finished = true
          }
        }
      }
      entered.await()
      42
    }
    val drained = async { composeAndDrain(emptyCanvas, root) }
    cleaning.await()
    assertEquals(42, mosaic.compose(root))
    assertFalse(drained.isCompleted)
    release.complete(Unit)
    assertEquals(42, drained.await())
    assertTrue(finished)
    val job = checkNotNull((mosaic as CoroutineScope).coroutineContext[Job])
    assertTrue(job.isCancelled)
    assertTrue(job.isCompleted)
    assertTrue(job.children.none())
  }

  @Test fun graphResultsSurviveFullDrain() = runBlocking {
    val executions = AtomicInteger()
    val diamond = GraphFixtures.diamond(4, executions)
    val coalescing = CoalescingFixtures.graph(4, 0)
    repeat(2) {
      assertEquals(GraphFixtures.diamondResult(4), composeAndDrain(emptyCanvas, diamond))
      assertEquals(CoalescingFixtures.expected(4), composeAndDrain(emptyCanvas, coalescing))
    }
    assertEquals(2, executions.get())
  }
}

package org.buildmosaic.benchmarks

import kotlinx.coroutines.runBlocking
import org.buildmosaic.core.injection.withMosaic
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.test.Test
import kotlin.test.assertEquals

class CoalescingFixturesTest {
  @Test fun everyDistinctKeyFetchedOnceAcrossShapes() = runBlocking {
    for (fanOut in listOf(2, 4, 8, 16)) {
      for (depth in listOf(0, 1, 2, 5, 10)) {
        for (suspension in listOf(false, true)) {
          val batches = ConcurrentLinkedQueue<Set<Int>>()
          val graph = CoalescingFixtures.graph(fanOut, depth, suspension) { batches.add(it.toSet()) }
          repeat(2) {
            batches.clear()
            assertEquals(CoalescingFixtures.expected(fanOut), emptyCanvas.withMosaic { compose(graph) })
            assertEquals(CoalescingFixtures.keys(fanOut).flatten().distinct().sorted(),
              batches.flatMap { it }.sorted())
          }
        }
      }
    }
  }
}

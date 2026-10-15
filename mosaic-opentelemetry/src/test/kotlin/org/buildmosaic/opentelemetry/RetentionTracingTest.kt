package org.buildmosaic.opentelemetry

import kotlinx.coroutines.test.runTest
import org.buildmosaic.core.injection.canvas
import org.buildmosaic.core.injection.withMosaic
import org.buildmosaic.core.singleTile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RetentionTracingTest {
  @Test fun fullLinkBudgetReleasesRemainingUnresolvedOrigin() =
    runTest {
      TelemetryFixture().use { otel ->
        val dispatcher = QueuedDispatcher()
        otel.withMosaic(dispatcher) {
          val mosaic = this
          val known = List(64) { singleTile { it } }
          known.forEach { mosaic.composeAsync(it) }
          dispatcher.drain()
          val unresolved = singleTile { 9 }
          val result =
            mosaic.composeAsync(
              singleTile {
                composeAsync(unresolved)
                known.forEach { compose(it) }
                7
              },
            )
          dispatcher.next()
          assertEquals(7, result.await())
          val consumer = otel.spans.single { it.links.isNotEmpty() }
          assertEquals(64, consumer.dependencies().size)
          assertTrue(consumer.flag("mosaic.dependencies.truncated")!!)
          assertEquals(65, otel.spans.size)
          // The unresolved origin cannot add a link, so it no longer retains this completed span.
          dispatcher.drain()
          assertEquals(66, otel.spans.size)
          assertEquals(64, consumer.dependencies().size)
        }
      }
    }

  @Test fun linksAndUnresolvedSubscriptionsStopAt64() =
    runTest {
      TelemetryFixture().use { otel ->
        val dispatcher = QueuedDispatcher()
        otel.withMosaic(dispatcher) {
          val mosaic = this
          val producers = List(65) { singleTile { it } }
          val result =
            mosaic.composeAsync(
              singleTile {
                producers.forEach { composeAsync(it) }
                "complete"
              },
            )
          dispatcher.next()
          assertEquals("complete", result.await())
          assertTrue(otel.spans.isEmpty())
          repeat(64) { dispatcher.next() }
          val consumer = otel.spans.single { it.links.isNotEmpty() }
          assertEquals(64, consumer.dependencies().size)
          assertTrue(consumer.flag("mosaic.dependencies.truncated")!!)
          assertEquals(65, otel.spans.size)
          dispatcher.drain()
          assertEquals(66, otel.spans.size)
          assertEquals((0..64).toList(), producers.map { mosaic.compose(it) })
        }
      }
    }

  @Test fun installationBoundsCompletedSpansAwaitingProducers() =
    runTest {
      TelemetryFixture().use { otel ->
        val dispatcher = QueuedDispatcher()
        otel.withMosaic(dispatcher) {
          val mosaic = this
          val results =
            List(1025) {
              val producer = singleTile { 1 }
              mosaic.composeAsync(
                singleTile {
                  composeAsync(producer)
                  2
                },
              )
            }
          repeat(1025) { dispatcher.next() }
          assertTrue(results.all { it.isCompleted })
          assertEquals(1, otel.spans.size)
          assertTrue(otel.spans.single().flag("mosaic.dependencies.truncated")!!)
          dispatcher.drain()
          assertEquals(2050, otel.spans.size)
          assertEquals(1024, otel.spans.count { it.dependencies().size == 1 })
          // Resolved subscriptions release the shared budget for subsequent requests.
          val last =
            mosaic.composeAsync(
              singleTile {
                composeAsync(singleTile { 3 })
                4
              },
            )
          dispatcher.next()
          assertEquals(4, last.await())
          assertEquals(2050, otel.spans.size)
          dispatcher.drain()
          assertEquals(1025, otel.spans.count { it.dependencies().size == 1 })
        }
      }
    }

  @Test fun cancellationOfNeverStartedProducerReleasesConsumer() =
    runTest {
      TelemetryFixture().use { otel ->
        val dispatcher = QueuedDispatcher()
        lateinit var result: kotlinx.coroutines.Deferred<Int>
        dispatcher.run {
          canvas { tracing { otel.telemetry } }.withMosaic {
            result =
              composeAsync(
                singleTile {
                  composeAsync(singleTile { 1 })
                  2
                },
              )
            dispatcher.next()
            assertEquals(2, result.await())
            assertTrue(otel.spans.isEmpty())
          }
        }
        assertEquals(1, otel.spans.size)
        assertTrue(otel.spans.single().links.isEmpty())
        assertEquals(2, result.await())
      }
    }

  @Test fun resolvedLinksAlsoStopAt64AndDeduplicateReuse() =
    runTest {
      TelemetryFixture().use { otel ->
        val dispatcher = QueuedDispatcher()
        otel.withMosaic(dispatcher) {
          val mosaic = this
          val producers = List(65) { singleTile { it } }
          producers.forEach { mosaic.composeAsync(it) }
          dispatcher.drain()
          val consumer =
            mosaic.composeAsync(
              singleTile {
                producers.forEach { tile -> repeat(3) { compose(tile) } }
                7
              },
            )
          dispatcher.drain()
          assertEquals(7, consumer.await())
          val span = otel.spans.single { it.links.isNotEmpty() }
          assertEquals(64, span.dependencies().size)
          assertEquals(64, span.dependencies().toSet().size)
          assertTrue(span.flag("mosaic.dependencies.truncated")!!)
        }
      }
    }
}

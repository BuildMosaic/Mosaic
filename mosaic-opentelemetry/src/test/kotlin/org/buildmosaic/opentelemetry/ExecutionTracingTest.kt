package org.buildmosaic.opentelemetry

import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.StatusCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.buildmosaic.core.chunkedMultiTile
import org.buildmosaic.core.injection.withMosaic
import org.buildmosaic.core.multiTile
import org.buildmosaic.core.perKeyTile
import org.buildmosaic.core.singleTile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExecutionTracingTest {
  @Test fun delegatedNamesPreserveExecutionAndReuse() =
    runTest {
      TelemetryFixture().use { otel ->
        otel.withMosaic(StandardTestDispatcher(testScheduler)) {
          val mosaic = this
          val orderTile by singleTile { 7 }
          val alias by orderTile
          val products by multiTile<Int, Int> { keys -> keys.associateWith { it } }
          val perKey by perKeyTile<Int, Int> { it }
          val chunked by chunkedMultiTile<Int, Int>(1) { keys -> keys.associateWith { it } }
          val unnamed = singleTile { 9 }
          val unnamedMulti = multiTile<Int, Int> { keys -> keys.associateWith { it } }
          assertEquals(7, mosaic.compose(alias))
          assertEquals(7, mosaic.compose(orderTile))
          assertEquals(1, mosaic.compose(products, 1))
          assertEquals(1, mosaic.compose(products, 1))
          assertEquals(2, mosaic.compose(perKey, 2))
          assertEquals(mapOf(3 to 3, 4 to 4), mosaic.compose(chunked, listOf(3, 4)))
          assertEquals(9, mosaic.compose(unnamed))
          assertEquals(5, mosaic.compose(unnamedMulti, 5))
          testScheduler.runCurrent()
          assertEquals(
            setOf("orderTile", "products", "perKey", "chunked", "Mosaic single", "Mosaic multi"),
            otel.spans.map { it.name }.toSet(),
          )
          assertEquals(6, otel.spans.size)
          assertEquals("single", otel.spans.single { it.name == "orderTile" }.attribute("mosaic.execution.kind"))
          assertEquals(2, otel.spans.single { it.name == "chunked" }.count("mosaic.batch.size"))
        }
      }
    }

  @Test fun singleExecutionParentsAndReuseRelationships() =
    runTest {
      TelemetryFixture().use { otel ->
        otel.withMosaic(StandardTestDispatcher(testScheduler)) {
          val mosaic = this
          val gate = CompletableDeferred<Unit>()
          var producerId = ""
          val shared =
            singleTile {
              producerId = Span.current().spanContext.spanId
              gate.await()
              7
            }
          val parent = singleTile { compose(shared) }
          val reuse = singleTile { compose(shared) }
          otel.root { request ->
            val first = mosaic.composeAsync(parent)
            testScheduler.runCurrent()
            val second = mosaic.composeAsync(reuse)
            testScheduler.runCurrent()
            gate.complete(Unit)
            testScheduler.runCurrent()
            assertEquals(7, first.await())
            assertEquals(7, second.await())
            assertEquals(7, mosaic.compose(shared))
            val cached = mosaic.composeAsync(singleTile { compose(shared) })
            testScheduler.runCurrent()
            assertEquals(7, cached.await())
            val producer = otel.spans.single { it.spanId == producerId }
            val initiating = otel.spans.single { it.spanId == producer.parentSpanId }
            assertEquals(request.spanContext.spanId, initiating.parentSpanId)
            val consumers = otel.spans.filter { it.spanId != producerId }
            assertEquals(3, consumers.size)
            consumers.forEach { assertEquals(listOf(producerId), it.dependencies()) }
            assertTrue(otel.spans.all { it.kind == SpanKind.INTERNAL && it.status.statusCode == StatusCode.UNSET })
          }
          assertEquals(5, otel.spans.size)
        }
      }
    }

  @Test fun lateProducerUsesOriginalCompletionTime() =
    runTest {
      TelemetryFixture().use { otel ->
        val dispatcher = QueuedDispatcher()
        otel.withMosaic(dispatcher) {
          val mosaic = this
          var consumerId = ""
          val producer = singleTile { 9 }
          val result =
            mosaic.composeAsync(
              singleTile {
                consumerId = Span.current().spanContext.spanId
                composeAsync(producer)
                "published"
              },
            )
          dispatcher.next()
          assertEquals("published", result.await())
          assertTrue(otel.spans.isEmpty())
          // Synthetic unrelated work advances wall time without delaying any Mosaic execution.
          val marker = otel.tracer.spanBuilder("after completion").startSpan()
          marker.end()
          val markerStart = otel.spans.single().startEpochNanos
          dispatcher.drain()
          val consumer = otel.spans.single { it.spanId == consumerId }
          val produced = otel.spans.single { it.name == "Mosaic single" && it.spanId != consumerId }
          assertEquals(listOf(produced.spanId), consumer.dependencies())
          assertTrue(consumer.endEpochNanos <= markerStart)
          assertTrue(consumer.endEpochNanos <= produced.startEpochNanos)
          assertTrue(consumer.endEpochNanos >= consumer.startEpochNanos)
        }
      }
    }

  @Test fun multiGroupsShareOneBatchProducer() =
    runTest {
      TelemetryFixture().use { otel ->
        otel.withMosaic(StandardTestDispatcher(testScheduler)) {
          val mosaic = this
          val tile = multiTile<Int, Int> { keys -> keys.associateWith { it } }
          otel.root {
            val a = mosaic.composeAsync(singleTile { compose(tile, listOf(1, 2)) })
            val b = mosaic.composeAsync(singleTile { compose(tile, listOf(2, 3)) })
            testScheduler.runCurrent()
            assertEquals(mapOf(1 to 1, 2 to 2), a.await())
            assertEquals(mapOf(2 to 2, 3 to 3), b.await())
            val batch = otel.spans.single { it.name == "Mosaic multi" }
            val consumers = otel.spans.filter { it.name == "Mosaic single" }
            assertEquals(3, batch.count("mosaic.batch.size"))
            assertEquals(2, batch.count("mosaic.contributors.count"))
            assertFalse(batch.flag("mosaic.contributors.truncated")!!)
            consumers.forEach { assertEquals(listOf(batch.spanId), it.dependencies()) }
            assertEquals(1, batch.links.size)
            assertEquals(
              "contributor",
              batch.links.single().attributes.get(
                io.opentelemetry.api.common.AttributeKey.stringKey("mosaic.link.type"),
              ),
            )
            assertEquals(
              consumers.single { it.spanId != batch.parentSpanId }.spanId,
              batch.links.single().spanContext.spanId,
            )
            assertEquals(1, otel.spans.count { it.name == "Mosaic multi" })
          }
        }
      }
    }
}

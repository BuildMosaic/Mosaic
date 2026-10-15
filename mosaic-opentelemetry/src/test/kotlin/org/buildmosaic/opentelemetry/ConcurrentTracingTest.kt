package org.buildmosaic.opentelemetry

import io.opentelemetry.api.trace.Span
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.buildmosaic.core.injection.withMosaic
import org.buildmosaic.core.singleTile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConcurrentTracingTest {
  @Test fun concurrentReuseAndPublicationFinalizeEachSpanOnce() =
    runBlocking {
      repeat(10) {
        TelemetryFixture().use { otel ->
          val gate = CompletableDeferred<Unit>()
          var producerId = ""
          val producer =
            singleTile {
              producerId = Span.current().spanContext.spanId
              gate.await()
              7
            }
          val consumers =
            List(24) {
              singleTile {
                repeat(10) { composeAsync(producer) }
                compose(producer)
              }
            }
          withTimeout(10_000) {
            otel.root {
              otel.withMosaic(Dispatchers.Default) {
                val mosaic = this
                val results = consumers.map { async { mosaic.compose(it) } }
                gate.complete(Unit)
                assertTrue(results.awaitAll().all { it == 7 })
              }
            }
          }
          assertEquals(26, otel.spans.size)
          assertEquals(26, otel.spans.map { it.spanId }.toSet().size)
          otel.spans.filter { it.name == "Mosaic single" && it.spanId != producerId }.forEach {
            assertEquals(listOf(producerId), it.dependencies())
          }
        }
      }
    }
}

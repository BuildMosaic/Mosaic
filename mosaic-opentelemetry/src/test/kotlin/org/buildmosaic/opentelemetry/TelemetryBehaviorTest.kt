package org.buildmosaic.opentelemetry

import io.opentelemetry.api.trace.Span
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.common.Clock
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.buildmosaic.core.injection.canvas
import org.buildmosaic.core.injection.withMosaic
import org.buildmosaic.core.multiTile
import org.buildmosaic.core.singleTile
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TelemetryBehaviorTest {
  @Test fun cachedKeysKeepDistinctBatchProducers() =
    runTest {
      TelemetryFixture().use { otel ->
        otel.withMosaic(StandardTestDispatcher(testScheduler)) {
          val mosaic = this
          val tile = multiTile<Int, Int> { keys -> keys.associateWith { it } }
          assertEquals(mapOf(1 to 1, 2 to 2), mosaic.compose(tile, listOf(1, 2)))
          assertEquals(3, mosaic.compose(tile, 3))
          assertEquals(mapOf(1 to 1, 2 to 2, 3 to 3), mosaic.compose(singleTile { compose(tile, listOf(1, 2, 3)) }))
          testScheduler.runCurrent()
          val batches = otel.spans.filter { it.name == "Mosaic multi" }
          assertEquals(2, batches.size)
          assertEquals(setOf(1L, 2L), batches.map { it.count("mosaic.batch.size") }.toSet())
          assertEquals(
            batches.map { it.spanId }.toSet(),
            otel.spans.single { it.name == "Mosaic single" }.dependencies().toSet(),
          )
        }
      }
    }

  @Test fun applicationEnrichmentBelongsToCurrentExecution() =
    runTest {
      TelemetryFixture().use { otel ->
        otel.withMosaic(StandardTestDispatcher(testScheduler)) {
          val mosaic = this
          val inner =
            singleTile {
              Span.current().setAttribute("app.inner", "explicit")
              7
            }
          val outer =
            singleTile {
              Span.current().setAttribute("app.outer", "before")
              val value = compose(inner)
              Span.current().setAttribute("app.outer", "after")
              value
            }
          assertEquals(7, mosaic.compose(outer))
          testScheduler.runCurrent()
          val nested = otel.spans.single { it.attribute("app.inner") != null }
          val parent = otel.spans.single { it.attribute("app.outer") != null }
          assertEquals("explicit", nested.attribute("app.inner"))
          assertEquals("after", parent.attribute("app.outer"))
          assertEquals(parent.spanId, nested.parentSpanId)
        }
      }
    }

  @Test fun timestampsDoNotBorrowCustomSdkClock() =
    runTest {
      val fakeClock =
        object : Clock {
          override fun now(): Long = 123

          override fun nanoTime(): Long = 456
        }
      val exporter = InMemorySpanExporter.create()
      val provider =
        SdkTracerProvider.builder().setClock(fakeClock)
          .addSpanProcessor(SimpleSpanProcessor.create(exporter)).build()
      OpenTelemetrySdk.builder().setTracerProvider(provider).build().use { telemetry ->
        val dispatcher = QueuedDispatcher()
        dispatcher.run {
          canvas { tracing { telemetry } }.withMosaic {
            val mosaic = this

            val before = Instant.now().let { TimeUnit.SECONDS.toNanos(it.epochSecond) + it.nano }
            val result = mosaic.composeAsync(singleTile { 7 })
            dispatcher.drain()
            assertEquals(7, result.await())
            val span = exporter.finishedSpanItems.single()
            assertTrue(span.startEpochNanos >= before)
            assertTrue(span.endEpochNanos >= span.startEpochNanos)
            assertTrue(span.endEpochNanos > fakeClock.now())
          }
        }
      }
    }
}

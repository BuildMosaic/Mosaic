package org.buildmosaic.opentelemetry

import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.context.Context
import io.opentelemetry.sdk.trace.data.LinkData
import io.opentelemetry.sdk.trace.samplers.Sampler
import io.opentelemetry.sdk.trace.samplers.SamplingResult
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.buildmosaic.core.injection.canvas
import org.buildmosaic.core.injection.withMosaic
import org.buildmosaic.core.multiTile
import org.buildmosaic.core.singleTile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SamplingTracingTest {
  @Test fun contributorsAreVisibleAtSamplingAndRetainedAt64() =
    runTest {
      val sampledLinks = mutableListOf<List<LinkData>>()
      val sampler =
        object : Sampler {
          override fun getDescription(): String = "capture links"

          override fun shouldSample(
            parentContext: Context,
            traceId: String,
            name: String,
            spanKind: SpanKind,
            attributes: Attributes,
            parentLinks: List<LinkData>,
          ): SamplingResult {
            if (name == "Mosaic multi") sampledLinks.add(parentLinks)
            return SamplingResult.recordAndSample()
          }
        }
      TelemetryFixture(sampler).use { otel ->
        otel.withMosaic(StandardTestDispatcher(testScheduler)) {
          val mosaic = this
          val tile = multiTile<Int, Int> { keys -> keys.associateWith { it } }
          val callers = (1..65).map { otel.tracer.spanBuilder("contributor $it").setNoParent().startSpan() }
          val results =
            callers.mapIndexed { index, caller ->
              caller.makeCurrent().use { mosaic.composeAsync(tile, index) }
            }
          testScheduler.runCurrent()
          assertEquals((0..64).toList(), results.map { it.await() })
          val batch = otel.spans.single()
          assertEquals(callers.first().spanContext.spanId, batch.parentSpanId)
          assertEquals(callers.subList(1, 64).map { it.spanContext }, sampledLinks.single().map { it.spanContext })
          assertEquals(sampledLinks.single(), batch.links)
          assertEquals(65, batch.count("mosaic.batch.size"))
          assertEquals(65, batch.count("mosaic.contributors.count"))
          assertTrue(batch.flag("mosaic.contributors.truncated")!!)
          callers.forEach { it.end() }
        }
      }
    }

  @Test fun unsampledExecutionsKeepTheirOwnIdentity() =
    runTest {
      val sampler =
        object : Sampler {
          override fun getDescription(): String = "sample request and later consumer"

          override fun shouldSample(
            parentContext: Context,
            traceId: String,
            name: String,
            spanKind: SpanKind,
            attributes: Attributes,
            parentLinks: List<LinkData>,
          ): SamplingResult =
            if (!Span.fromContext(
                parentContext,
              ).spanContext.isValid
            ) {
              SamplingResult.recordAndSample()
            } else {
              SamplingResult.drop()
            }
        }
      TelemetryFixture(sampler).use { otel ->
        otel.withMosaic(StandardTestDispatcher(testScheduler)) {
          val mosaic = this
          var unsampled = Span.getInvalid().spanContext
          val tile =
            singleTile {
              unsampled = Span.current().spanContext
              7
            }
          otel.root { request ->
            assertEquals(7, mosaic.compose(tile))
            assertTrue(unsampled.isValid)
            assertFalse(unsampled.isSampled)
            assertTrue(unsampled.spanId != request.spanContext.spanId)
          }
          val consumer = mosaic.composeAsync(singleTile { compose(tile) })
          testScheduler.runCurrent()
          assertEquals(7, consumer.await())
          val recorded = otel.spans.single { it.name == "Mosaic single" }
          assertEquals(listOf(unsampled.spanId), recorded.dependencies())
          assertFalse(recorded.links.single().spanContext.isSampled)
        }
      }
    }

  @Test fun noopDoesNotPublishBorrowedParentAsProducer() =
    runTest {
      TelemetryFixture().use { otel ->
        var starts = 0
        val noop = OpenTelemetry.noop()
        val custom =
          object : OpenTelemetry by noop {
            override fun getTracer(name: String): io.opentelemetry.api.trace.Tracer {
              val delegate = noop.getTracer(name)
              return object : io.opentelemetry.api.trace.Tracer by delegate {
                override fun spanBuilder(spanName: String): io.opentelemetry.api.trace.SpanBuilder {
                  starts++
                  // First execution uses no-op propagation; a later execution records and reuses it.
                  return if (starts == 1) delegate.spanBuilder(spanName) else otel.tracer.spanBuilder(spanName)
                }
              }
            }
          }
        withContext(StandardTestDispatcher(testScheduler)) {
          canvas { tracing { custom } }.withMosaic {
            val mosaic = this
            val tile = singleTile { Span.current().spanContext }

            otel.root { parent ->
              assertEquals(parent.spanContext, mosaic.compose(tile))
              val reused = mosaic.composeAsync(singleTile { compose(tile) })
              testScheduler.runCurrent()
              assertEquals(parent.spanContext, reused.await())
              assertTrue(otel.spans.single().links.isEmpty())
            }
            assertEquals(2, starts)
          }
        }
      }
    }
}

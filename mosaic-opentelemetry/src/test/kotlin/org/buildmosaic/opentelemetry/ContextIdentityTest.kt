package org.buildmosaic.opentelemetry

import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanContext
import io.opentelemetry.api.trace.TraceFlags
import io.opentelemetry.api.trace.TraceState
import io.opentelemetry.sdk.trace.samplers.Sampler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.buildmosaic.core.injection.canvas
import org.buildmosaic.core.injection.withMosaic
import org.buildmosaic.core.multiTile
import org.buildmosaic.core.singleTile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ContextIdentityTest {
  @Test fun contributorIdentityIgnoresRemoteAndSamplingFlags() =
    runTest {
      TelemetryFixture().use { otel ->
        otel.withMosaic(StandardTestDispatcher(testScheduler)) {
          val mosaic = this
          val first = otel.tracer.spanBuilder("initiator").startSpan()
          val extra = otel.tracer.spanBuilder("extra").setNoParent().startSpan()
          val original = extra.spanContext
          val variants =
            listOf(
              original,
              SpanContext.create(original.traceId, original.spanId, TraceFlags.getDefault(), TraceState.getDefault()),
              SpanContext.createFromRemoteParent(
                original.traceId,
                original.spanId,
                original.traceFlags,
                original.traceState,
              ),
            )
          val tile = multiTile<Int, Int> { keys -> keys.associateWith { it } }
          first.makeCurrent().use { mosaic.composeAsync(tile, 0) }
          variants.forEachIndexed { index, context ->
            Span.wrap(context).makeCurrent().use { mosaic.composeAsync(tile, index + 1) }
          }
          testScheduler.runCurrent()
          val batch = otel.spans.single()
          assertEquals(1, batch.links.size)
          assertEquals(original.spanId, batch.links.single().spanContext.spanId)
          assertEquals(4, batch.count("mosaic.contributors.count"))
          first.end()
          extra.end()
        }
      }
    }

  @Test fun nonRecordingContextStillParentsDownstreamWork() =
    runTest {
      TelemetryFixture(Sampler.alwaysOff()).use { unsampled ->
        TelemetryFixture().use { downstream ->
          for (telemetry in listOf(unsampled.telemetry, OpenTelemetry.noop())) {
            withContext(StandardTestDispatcher(testScheduler)) {
              canvas { tracing { telemetry } }.withMosaic {
                val mosaic = this

                downstream.root { parent ->
                  val id =
                    mosaic.compose(
                      singleTile {
                        val own = Span.current().spanContext
                        assertFalse(Span.current().isRecording)
                        yield()
                        withContext(Dispatchers.Default) {
                          assertEquals(own, Span.current().spanContext)
                          downstream.tracer.spanBuilder("downstream").startSpan().end()
                        }
                        own
                      },
                    )
                  val child = downstream.spans.last { it.name == "downstream" }
                  assertEquals(id.spanId, child.parentSpanId)
                  assertEquals(parent.spanContext.traceId, child.traceId)
                  assertTrue(id.isValid)
                }
              }
            }
          }
          assertTrue(unsampled.spans.isEmpty())
        }
      }
    }
}

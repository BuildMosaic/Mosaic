package org.buildmosaic.opentelemetry

import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanBuilder
import io.opentelemetry.api.trace.SpanContext
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.api.trace.Tracer
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.buildmosaic.core.injection.canvas
import org.buildmosaic.core.injection.withMosaic
import org.buildmosaic.core.singleTile
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ProviderFailureTest {
  @Test fun startAndPostCreationFailureDoNotChangeResult() =
    runTest {
      TelemetryFixture().use { otel ->
        for (postCreation in listOf(false, true)) {
          val telemetry =
            wrapping(otel.telemetry) { builder ->
              object : SpanBuilder by builder {
                override fun startSpan(): Span {
                  if (!postCreation) error("private SDK message")
                  val span = builder.startSpan()
                  return object : Span by span {
                    override fun isRecording(): Boolean = error("private SDK message")
                  }
                }
              }
            }
          withContext(StandardTestDispatcher(testScheduler)) {
            canvas { tracing { telemetry } }.withMosaic {
              val mosaic = this

              assertEquals(7, mosaic.compose(singleTile { 7 }))
            }
          }
        }
        // Post-creation failure rolls back its span; startSpan failure returns no handle to clean up.
        assertEquals(1, otel.spans.size)
        assertTrue(otel.spans.single().endEpochNanos >= otel.spans.single().startEpochNanos)
      }
    }

  @Test fun linkStatusAndEndFailuresRemainOutsideApplication() =
    runTest {
      TelemetryFixture().use { otel ->
        var callbacks = 0
        val telemetry =
          wrapping(otel.telemetry) { builder ->
            object : SpanBuilder by builder {
              override fun startSpan(): Span {
                val span = builder.startSpan()
                return object : Span by span {
                  override fun addLink(
                    context: SpanContext,
                    attributes: Attributes,
                  ): Span {
                    callbacks++
                    error("private link message")
                  }

                  override fun setStatus(status: StatusCode): Span {
                    callbacks++
                    error("private status message")
                  }

                  override fun end(
                    timestamp: Long,
                    unit: TimeUnit,
                  ) {
                    span.end(timestamp, unit)
                    callbacks++
                    error("private end message")
                  }
                }
              }
            }
          }
        withContext(StandardTestDispatcher(testScheduler)) {
          canvas { tracing { telemetry } }.withMosaic {
            val mosaic = this

            val producer = singleTile { 7 }
            assertEquals(7, mosaic.compose(singleTile { compose(producer) }))
            val failed = mosaic.composeAsync(singleTile { throw IllegalArgumentException("private application") })
            testScheduler.runCurrent()
            assertFailsWith<IllegalArgumentException> { failed.await() }
            assertTrue(callbacks >= 5)
            assertEquals(3, otel.spans.size)
            assertTrue(otel.spans.none { it.toString().contains("private") })
          }
        }
      }
    }
}

private fun wrapping(
  telemetry: OpenTelemetry,
  transform: (SpanBuilder) -> SpanBuilder,
): OpenTelemetry =
  object : OpenTelemetry by telemetry {
    override fun getTracer(name: String): Tracer {
      val tracer = telemetry.getTracer(name)
      return object : Tracer by tracer {
        override fun spanBuilder(spanName: String): SpanBuilder {
          val builder = transform(tracer.spanBuilder(spanName))
          // A provider wrapper must preserve fluent builder identity, as the public API requires.
          return object : SpanBuilder by builder {
            override fun setParent(context: io.opentelemetry.context.Context): SpanBuilder =
              apply { builder.setParent(context) }

            override fun setSpanKind(kind: io.opentelemetry.api.trace.SpanKind): SpanBuilder =
              apply { builder.setSpanKind(kind) }

            override fun setStartTimestamp(
              timestamp: Long,
              unit: TimeUnit,
            ): SpanBuilder = apply { builder.setStartTimestamp(timestamp, unit) }

            override fun setAttribute(
              key: String,
              value: String?,
            ): SpanBuilder = apply { builder.setAttribute(key, value) }

            override fun setAttribute(
              key: String,
              value: Long,
            ): SpanBuilder = apply { builder.setAttribute(key, value) }

            override fun setAttribute(
              key: String,
              value: Boolean,
            ): SpanBuilder = apply { builder.setAttribute(key, value) }

            override fun addLink(
              context: SpanContext,
              attributes: Attributes,
            ): SpanBuilder = apply { builder.addLink(context, attributes) }
          }
        }
      }
    }
  }

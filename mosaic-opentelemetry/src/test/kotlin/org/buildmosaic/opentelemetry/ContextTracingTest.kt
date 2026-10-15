package org.buildmosaic.opentelemetry

import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.context.Context
import io.opentelemetry.context.ContextKey
import io.opentelemetry.extension.kotlin.asContextElement
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.buildmosaic.core.injection.canvas
import org.buildmosaic.core.injection.withMosaic
import org.buildmosaic.core.singleTile
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ContextTracingTest {
  @Test fun officialContextSurvivesNestedExecutionAndDispatch() =
    runTest {
      TelemetryFixture().use { otel ->
        otel.withMosaic(StandardTestDispatcher(testScheduler)) {
          val mosaic = this
          val key = ContextKey.named<String>("test-framework-context")
          val outerIds = ConcurrentLinkedQueue<String>()
          var innerId = ""
          val inner =
            singleTile {
              innerId = Span.current().spanContext.spanId
              assertEquals("framework value", Context.current().get(key))
              yield()
              assertEquals(innerId, Span.current().spanContext.spanId)
              7
            }
          val outer =
            singleTile {
              val own = Span.current().spanContext.spanId
              outerIds.add(own)
              yield()
              coroutineScope {
                List(8) {
                  async(Dispatchers.Default) {
                    yield()
                    outerIds.add(Span.current().spanContext.spanId)
                    assertEquals("framework value", Context.current().get(key))
                    compose(inner)
                    outerIds.add(Span.current().spanContext.spanId)
                  }
                }.awaitAll()
              }
              assertEquals(own, Span.current().spanContext.spanId)
              7
            }
          otel.root { request ->
            val framework = Context.current().with(key, "framework value")
            withContext(framework.asContextElement() + Dispatchers.Default) {
              assertEquals(7, mosaic.compose(outer))
              assertEquals(framework, Context.current())
              assertEquals(request.spanContext, Span.current().spanContext)
            }
            assertEquals(request.spanContext, Span.current().spanContext)
          }
          testScheduler.runCurrent()
          val outerSpan = otel.spans.single { it.spanId == outerIds.first() }
          assertEquals(1, outerIds.toSet().size)
          assertEquals(outerSpan.spanId, otel.spans.single { it.spanId == innerId }.parentSpanId)
          assertFalse(Span.current().spanContext.isValid)
        }
      }
    }

  @Test fun attachedChildKeepsContextUntilItsScopeFinishes() =
    runTest {
      TelemetryFixture().use { otel ->
        otel.withMosaic(StandardTestDispatcher(testScheduler)) {
          val mosaic = this
          val gate = CompletableDeferred<Unit>()
          var executionId = ""
          var childId = ""
          val child =
            singleTile {
              childId = Span.current().spanContext.spanId
              1
            }
          val result =
            mosaic.composeAsync(
              singleTile {
                executionId = Span.current().spanContext.spanId
                CoroutineScope(currentCoroutineContext()).launch {
                  gate.await()
                  assertEquals(executionId, Span.current().spanContext.spanId)
                  compose(child)
                  error("private child failure")
                }
                42
              },
            )
          testScheduler.runCurrent()
          assertEquals(42, result.await())
          assertTrue(otel.spans.isEmpty())
          val marker = otel.tracer.spanBuilder("child release").startSpan()
          marker.end()
          gate.complete(Unit)
          testScheduler.runCurrent()
          val span = otel.spans.single { it.spanId == executionId }
          assertEquals(StatusCode.ERROR, span.status.statusCode)
          assertEquals("java.lang.IllegalStateException", span.attribute("error.type"))
          assertEquals(executionId, otel.spans.single { it.spanId == childId }.parentSpanId)
          assertTrue(span.endEpochNanos >= otel.spans.single { it.name == "child release" }.endEpochNanos)
          assertEquals(42, result.await())
        }
      }
    }

  @Test fun inheritedInstallationServesIndependentRequests() =
    runTest {
      TelemetryFixture().use { otel ->
        var factories = 0
        val application =
          canvas {
            tracing {
              factories++
              otel.telemetry
            }
          }
        val request = application.withLayer { provide<String> { "private request data" } }
        withContext(StandardTestDispatcher(testScheduler)) {
          application.withMosaic {
            val a = this
            withContext(StandardTestDispatcher(testScheduler)) {
              request.withMosaic {
                val b = this
                val tile = singleTile { 3 }
                try {
                  val parentA = otel.tracer.spanBuilder("request A").setNoParent().startSpan()
                  val parentB = otel.tracer.spanBuilder("request B").setNoParent().startSpan()
                  val first = parentA.makeCurrent().use { a.composeAsync(tile) }
                  val second = parentB.makeCurrent().use { b.composeAsync(tile) }
                  testScheduler.runCurrent()
                  assertEquals(3, first.await())
                  assertEquals(3, second.await())
                  parentA.end()
                  parentB.end()
                  assertEquals(1, factories)
                  val spans = otel.spans.filter { it.name == "Mosaic single" }
                  assertEquals(2, spans.size)
                  assertEquals(2, spans.map { it.spanId }.toSet().size)
                  assertEquals(
                    setOf(parentA.spanContext.spanId, parentB.spanContext.spanId),
                    spans.map { it.parentSpanId }.toSet(),
                  )
                  assertEquals(2, spans.map { it.traceId }.toSet().size)
                } finally {
                  request.close()
                  application.close()
                }
              }
            }
          }
        }
      }
    }
}

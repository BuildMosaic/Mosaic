package org.buildmosaic.opentelemetry

import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.trace.Span
import io.opentelemetry.extension.kotlin.asContextElement
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.SpanProcessor
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import io.opentelemetry.sdk.trace.samplers.Sampler
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import org.buildmosaic.core.Mosaic
import org.buildmosaic.core.injection.canvas
import org.buildmosaic.core.injection.withMosaic
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.coroutines.CoroutineContext

internal class TelemetryFixture(
  sampler: Sampler = Sampler.alwaysOn(),
  processor: SpanProcessor? = null,
) : AutoCloseable {
  private val exporter = InMemorySpanExporter.create()
  private val provider =
    SdkTracerProvider.builder()
      .setSampler(sampler)
      .addSpanProcessor(SimpleSpanProcessor.create(exporter))
      .apply { processor?.let { addSpanProcessor(it) } }
      .build()
  val telemetry: OpenTelemetrySdk = OpenTelemetrySdk.builder().setTracerProvider(provider).build()
  val tracer = telemetry.getTracer("test")
  val spans: List<SpanData> get() = exporter.finishedSpanItems

  suspend fun <T> withMosaic(
    dispatcher: CoroutineDispatcher,
    block: suspend Mosaic.() -> T,
  ): T =
    if (dispatcher is QueuedDispatcher) {
      dispatcher.run { canvas { tracing { telemetry } }.withMosaic(block) }
    } else {
      withContext(dispatcher) { canvas { tracing { telemetry } }.withMosaic(block) }
    }

  suspend fun <T> root(block: suspend (Span) -> T): T {
    val span = tracer.spanBuilder("request").setNoParent().startSpan()
    return try {
      withContext(span.asContextElement()) { block(span) }
    } finally {
      span.end()
    }
  }

  override fun close() {
    telemetry.close()
  }
}

/** Steps real Mosaic launches without sleeping or exposing adapter state. */
internal class QueuedDispatcher : CoroutineDispatcher() {
  private val queue = ConcurrentLinkedQueue<Runnable>()

  override fun dispatch(
    context: CoroutineContext,
    block: Runnable,
  ) {
    queue.add(block)
  }

  suspend fun <T> run(block: suspend () -> T): T =
    kotlinx.coroutines.coroutineScope {
      val execution = async(this@QueuedDispatcher) { block() }
      while (!execution.isCompleted) {
        drain()
        kotlinx.coroutines.yield()
      }
      execution.await()
    }

  fun next() = checkNotNull(queue.poll()) { "Expected scheduled Mosaic execution" }.run()

  fun drain() {
    while (true) (queue.poll() ?: return).run()
  }
}

internal fun SpanData.attribute(name: String): String? = attributes.get(AttributeKey.stringKey(name))

internal fun SpanData.flag(name: String): Boolean? = attributes.get(AttributeKey.booleanKey(name))

internal fun SpanData.count(name: String): Long? = attributes.get(AttributeKey.longKey(name))

internal fun SpanData.dependencies(): List<String> =
  links.filter {
    it.attributes.get(AttributeKey.stringKey("mosaic.link.type")) == "dependency"
  }.map { it.spanContext.spanId }

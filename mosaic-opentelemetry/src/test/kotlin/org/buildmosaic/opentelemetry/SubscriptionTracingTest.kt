package org.buildmosaic.opentelemetry

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.buildmosaic.core.injection.canvas
import org.buildmosaic.core.injection.withMosaic
import org.buildmosaic.core.observation.ExecutionObservation
import org.buildmosaic.core.observation.ExecutionObserver
import org.buildmosaic.core.observation.ExecutionStart
import org.buildmosaic.core.observation.ProducerReference
import org.buildmosaic.core.observation.ProducerResolution
import org.buildmosaic.core.observation.StartedObservation
import org.buildmosaic.core.observation.installExecutionObserver
import org.buildmosaic.core.singleTile
import kotlin.test.Test
import kotlin.test.assertEquals

class SubscriptionTracingTest {
  @Test fun resolutionBeforeSubscribeReturnsClosesItsHandle() =
    runTest {
      TelemetryFixture().use { otel ->
        val observer = OpenTelemetryObserver(otel.tracer)
        var closed = 0
        val immediate =
          object : ExecutionObserver by observer {
            override fun onStart(start: ExecutionStart): StartedObservation {
              val started = observer.onStart(start)
              val callbacks =
                object : ExecutionObservation by started.callbacks {
                  override fun onDependency(producer: ProducerReference) {
                    // Reproduce the allowed race: the first read sees pending, subscribe sees terminal.
                    val racing =
                      object : ProducerReference by producer {
                        override val resolution: ProducerResolution? get() = null

                        override fun subscribe(listener: (ProducerResolution) -> Unit): AutoCloseable {
                          val handle = producer.subscribe(listener)
                          return AutoCloseable {
                            closed++
                            handle.close()
                          }
                        }
                      }
                    started.callbacks.onDependency(racing)
                  }
                }
              return StartedObservation(callbacks, started.identity, started.context)
            }
          }
        withContext(StandardTestDispatcher(testScheduler)) {
          canvas { installExecutionObserver { immediate } }.withMosaic {
            val mosaic = this

            val producer = singleTile { 7 }
            assertEquals(7, mosaic.compose(producer))
            assertEquals(7, mosaic.compose(singleTile { compose(producer) }))
            testScheduler.runCurrent()
            assertEquals(1, closed)
            assertEquals(2, otel.spans.size)
            val consumer = otel.spans.single { it.links.isNotEmpty() }
            assertEquals(listOf(otel.spans.single { it.links.isEmpty() }.spanId), consumer.dependencies())
          }
        }
      }
    }
}

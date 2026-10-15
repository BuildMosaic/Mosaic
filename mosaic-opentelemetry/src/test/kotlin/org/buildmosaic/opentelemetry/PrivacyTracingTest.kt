package org.buildmosaic.opentelemetry

import io.opentelemetry.api.trace.StatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.buildmosaic.core.injection.canvas
import org.buildmosaic.core.injection.withMosaic
import org.buildmosaic.core.multiTile
import org.buildmosaic.core.singleTile
import org.buildmosaic.core.source
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PrivacyTracingTest {
  @Test fun payloadAndExceptionContentsAreNeverExported() =
    runTest {
      TelemetryFixture().use { otel ->
        val payload = "private result request user canvas key message stack cause"
        withContext(StandardTestDispatcher(testScheduler)) {
          canvas {
            tracing { otel.telemetry }
            provide<String> { payload }
          }.withMosaic {
            val mosaic = this

            val multi = multiTile<String, String> { it.associateWith { source<String>() } }
            val values = mosaic.composeAsync(multi, payload)
            val failed =
              mosaic.composeAsync(
                singleTile {
                  throw IllegalStateException(payload, IllegalArgumentException(payload)).also {
                    it.addSuppressed(IllegalArgumentException(payload))
                  }
                },
              )
            testScheduler.runCurrent()
            assertEquals(payload, values.await())
            assertFailsWith<IllegalStateException> { failed.await() }
            val span = otel.spans.single { it.status.statusCode == StatusCode.ERROR }
            assertEquals("java.lang.IllegalStateException", span.attribute("error.type"))
            assertEquals("", span.status.description)
            assertTrue(otel.spans.all { it.events.isEmpty() })
            assertTrue(otel.spans.none { it.toString().contains(payload) })
          }
        }
      }
    }

  @Test fun cancellationIsStructuralAndNotAnApplicationError() =
    runTest {
      TelemetryFixture().use { otel ->
        lateinit var result: kotlinx.coroutines.Deferred<Int>
        val request =
          launch {
            otel.withMosaic(StandardTestDispatcher(testScheduler)) {
              result = composeAsync(singleTile<Int> { kotlinx.coroutines.awaitCancellation() })
              kotlinx.coroutines.awaitCancellation()
            }
          }
        testScheduler.runCurrent()
        request.cancel(CancellationException("private cancellation"))
        request.join()
        assertFailsWith<CancellationException> { result.await() }
        val span = otel.spans.single()
        assertEquals(StatusCode.UNSET, span.status.statusCode)
        assertTrue(span.flag("mosaic.execution.cancelled")!!)
        assertTrue(span.events.isEmpty())
        assertTrue(!span.toString().contains("private cancellation"))
      }
    }
}

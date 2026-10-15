package org.buildmosaic.core.observation

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.buildmosaic.core.MosaicImpl
import org.buildmosaic.core.injection.canvas
import org.buildmosaic.core.multiTile
import org.buildmosaic.core.singleTile
import org.buildmosaic.core.source
import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ObservationPrivacyTest {
  @Test fun everyCallbackContainsOnlyStructuralData() =
    runTest {
      val recording = RecordingObserver()
      val inspected = mutableListOf<Any>()
      val observer =
        object : ExecutionObserver {
          override fun captureCaller(): CallerContext = recording.captureCaller()

          override fun onStart(start: ExecutionStart): StartedObservation {
            inspected.add(start)
            val returned = recording.onStart(start)
            return StartedObservation(
              object : ExecutionObservation {
                override fun onDependency(producer: ProducerReference) {
                  inspected.add(producer)
                  producer.subscribe { inspected.add(it) }
                  error("secret-provider-message")
                }

                override fun onComplete(completion: ExecutionCompletion) {
                  inspected.add(completion)
                }
              },
              returned.identity,
              returned.context,
            )
          }

          override fun onCallbackFailure(failure: CallbackFailure) {
            inspected.add(failure)
          }
        }
      val mosaic =
        MosaicImpl(
          canvas {
            provide<String> { "secret-canvas-value" }
            installExecutionObserver { observer }
          },
          StandardTestDispatcher(testScheduler),
        )
      val batch = multiTile<String, String> { _: Set<String> -> mapOf("secret-key" to "secret-result") }
      val failure = IllegalArgumentException("secret-message", IllegalStateException("secret-cause"))
      failure.addSuppressed(IllegalStateException("secret-suppressed"))
      val result =
        mosaic.composeAsync(
          singleTile {
            assertEquals("secret-canvas-value", source<String>())
            assertEquals("secret-result", compose(batch, "secret-key"))
            throw failure
          },
        )
      testScheduler.runCurrent()
      assertTrue(result.isCompleted)
      val strings = inspected.flatMap(::structuralStrings)
      assertFalse(strings.any { "secret" in it })
      assertTrue(strings.contains(IllegalArgumentException::class.java.name))
      assertTrue(inspected.any { it is CallbackFailure })
    }

  @Test fun completionFollowsResultSettlementAndContextExit() =
    runTest {
      val observer = RecordingObserver()
      val mosaic = MosaicImpl(canvas { installExecutionObserver { observer } }, StandardTestDispatcher(testScheduler))
      val result = mosaic.composeAsync(singleTile { 3 })
      var notifications = 0
      observer.completionHook = { completion ->
        assertTrue(result.isCompleted)
        assertNull(observer.ambient.get())
        assertNull(ObservedExecution.current())
        assertTrue(completion.completedAtNanos >= observer.executions.single().start.startedAtNanos)
        notifications++
      }
      testScheduler.runCurrent()
      assertEquals(3, result.await())
      assertEquals(1, notifications)
    }

  @Test fun cancellationDuringStartFinishesObservation() =
    runTest {
      val observer = RecordingObserver()
      val mosaic = MosaicImpl(canvas { installExecutionObserver { observer } }, Dispatchers.Unconfined)
      observer.startHook = { mosaic.cancel() }
      val result = mosaic.composeAsync(singleTile { error("must not run") })
      assertTrue(result.isCancelled)
      assertEquals(ExecutionOutcome.CANCELLATION, observer.executions.single().completion?.outcome)
    }

  private fun structuralStrings(value: Any?): List<String> =
    when (value) {
      null, is CallerContext, is ExecutionIdentity -> emptyList()
      is String -> listOf(value)
      is Number, is Enum<*> -> emptyList()
      is ProducerReference -> structuralStrings(value.resolution)
      is Iterable<*> -> value.flatMap(::structuralStrings)
      else -> {
        assertFalse(value is Throwable)
        assertTrue(value.javaClass.packageName == ExecutionObserver::class.java.packageName)
        value.javaClass.declaredFields.filterNot { Modifier.isStatic(it.modifiers) }.flatMap {
          it.isAccessible = true
          structuralStrings(it.get(value))
        }
      }
    }
}

package org.buildmosaic.core.observation

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.buildmosaic.core.injection.canvas
import org.buildmosaic.core.injection.withMosaic
import org.buildmosaic.core.singleTile
import org.buildmosaic.core.source
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ObservationConfigurationTest {
  @Test fun hierarchySharesConfigurationOutsideDI() =
    runBlocking {
      val recording = RecordingObserver()
      var shutdowns = 0
      val observer =
        object : ExecutionObserver by recording, AutoCloseable {
          override fun close() {
            shutdowns++
          }
        }
      val parent = canvas { installExecutionObserver { observer } }
      val child = parent.withLayer { provide<String> { "private input" } }
      val grandchild = canvas(child) {}
      assertNull(child.sourceOrNull(ExecutionObserver::class))
      assertEquals("private input", grandchild.withMosaic { compose(singleTile { source<String>() }) })
      assertEquals(1, recording.executions.size)
      grandchild.close()
      child.close()
      parent.close()
      assertEquals(0, shutdowns)
      assertEquals(2, parent.withMosaic { compose(singleTile { 2 }) })
    }

  @Test fun duplicatesRejectFactoriesLocallyAndAcrossAncestors() =
    runTest {
      val observer = RecordingObserver()
      var secondFactories = 0
      val parent =
        canvas {
          installExecutionObserver { observer }
          assertFailsWith<IllegalStateException> {
            installExecutionObserver {
              secondFactories++
              RecordingObserver()
            }
          }
        }
      assertFailsWith<IllegalStateException> {
        parent.withLayer {
          installExecutionObserver {
            secondFactories++
            RecordingObserver()
          }
        }
      }
      val child = parent.withLayer {}
      assertFailsWith<IllegalStateException> {
        canvas(child) {
          installExecutionObserver {
            secondFactories++
            RecordingObserver()
          }
        }
      }
      assertEquals(0, secondFactories)
    }

  @Test fun reentrantFactoryIsRejectedAndFailedSetupRollsBack() =
    runTest {
      var nestedFactories = 0
      val observer = RecordingObserver()
      val configured =
        canvas {
          assertFailsWith<IllegalArgumentException> {
            installExecutionObserver {
              throw IllegalArgumentException(
                "setup",
              )
            }
          }
          installExecutionObserver {
            assertFailsWith<IllegalStateException> {
              installExecutionObserver {
                nestedFactories++
                RecordingObserver()
              }
            }
            observer
          }
        }
      assertEquals(0, nestedFactories)
      assertEquals(42, configured.withMosaic { compose(singleTile { 42 }) })
      assertEquals(1, observer.executions.size)
    }

  @Test fun diRegistrationDoesNotInstallObserver() =
    runTest {
      val observer = RecordingObserver()
      val parent = canvas { provide<ExecutionObserver> { observer } }
      assertEquals(1, parent.withMosaic { compose(singleTile { 1 }) })
      assertTrue(observer.executions.isEmpty())
      val child = parent.withLayer { installExecutionObserver { observer } }
      assertEquals(2, child.withMosaic { compose(singleTile { 2 }) })
      assertEquals(1, observer.executions.size)
    }
}

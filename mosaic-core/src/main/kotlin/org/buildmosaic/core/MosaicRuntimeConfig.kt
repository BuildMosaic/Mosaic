package org.buildmosaic.core

import org.buildmosaic.core.injection.CanvasDependencyResolver
import org.buildmosaic.core.observation.ExecutionObserver

/** Durable runtime choices, separate from Canvas dependencies and request execution state. */
internal data class MosaicRuntimeConfig(
  val executionObserver: ExecutionObserver? = null,
  val dependencyResolver: CanvasDependencyResolver? = null,
) {
  companion object {
    val EMPTY = MosaicRuntimeConfig()
  }
}

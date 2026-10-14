package org.buildmosaic.benchmarks

import org.buildmosaic.core.Tile
import org.buildmosaic.core.injection.Canvas
import org.buildmosaic.core.injection.withMosaic

/** Compose in a fresh request Mosaic, cancelling unfinished work and waiting for cleanup on exit. */
internal suspend fun composeAndDrain(canvas: Canvas, tile: Tile<Int>): Int =
  canvas.withMosaic { compose(tile) }

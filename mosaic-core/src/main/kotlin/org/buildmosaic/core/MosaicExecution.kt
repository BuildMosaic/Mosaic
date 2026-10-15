package org.buildmosaic.core

/** Runs the block with the engine, then awaits producer and attached-child cleanup on every exit. */
internal suspend fun <R> MosaicImpl.withMosaicExecution(block: suspend Mosaic.() -> R): R {
  try {
    return block(this)
  } finally {
    shutdown()
  }
}

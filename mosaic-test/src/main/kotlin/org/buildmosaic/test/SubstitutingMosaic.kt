package org.buildmosaic.test

import kotlinx.coroutines.Deferred
import org.buildmosaic.core.Mosaic
import org.buildmosaic.core.MosaicImpl
import org.buildmosaic.core.MultiTile
import org.buildmosaic.core.Tile
import org.buildmosaic.core.injection.Canvas
import org.buildmosaic.core.withMosaicExecution
import kotlin.coroutines.CoroutineContext

/**
 * Resolves frozen test substitutions before entering the ordinary cache and batching engine.
 *
 * Deliberately isolated friend-module seam. IntelliJ may show false internal-visibility
 * errors here even though Kotlin/Gradle compilation is valid.
 */
internal class SubstitutingMosaic(
  canvas: Canvas,
  context: CoroutineContext,
  private val tiles: Map<Tile<*>, Tile<*>>,
  private val multiTiles: Map<MultiTile<*, *>, MultiTile<*, *>>,
) : MosaicImpl(canvas, context) {
  internal suspend fun <R> execute(block: suspend Mosaic.() -> R): R = withMosaicExecution(block)

  @Suppress("UNCHECKED_CAST")
  override fun <V> composeAsync(tile: Tile<V>): Deferred<V> = super.composeAsync(tiles[tile] as Tile<V>? ?: tile)

  @Suppress("UNCHECKED_CAST")
  override fun <K : Any, V> composeAsync(
    tile: MultiTile<K, V>,
    keys: Collection<K>,
  ): Map<K, Deferred<V>> = super.composeAsync(multiTiles[tile] as MultiTile<K, V>? ?: tile, keys)
}

package org.buildmosaic.benchmarks

import org.buildmosaic.core.Tile
import org.buildmosaic.core.MultiTile
import org.buildmosaic.core.injection.Canvas
import org.buildmosaic.core.injection.CanvasKey
import org.buildmosaic.core.injection.canvas
import org.buildmosaic.core.injection.withMosaic
import org.buildmosaic.core.multiTile
import org.buildmosaic.core.singleTile
import java.util.concurrent.atomic.AtomicInteger

internal val emptyCanvas: Canvas = runBlockingCanvas()

private fun runBlockingCanvas(): Canvas = kotlinx.coroutines.runBlocking { canvas {} }

internal object GraphFixtures {
  fun width(width: Int): Tile<Int> {
    val children = (1..width).map { value -> singleTile { value } }
    return singleTile {
      children.map { composeAsync(it) }.sumOf { it.await() }
    }
  }

  fun depth(depth: Int): Tile<Int> {
    var node = singleTile { 1 }
    repeat(depth - 1) {
      val child = node
      node = singleTile { compose(child) + 1 }
    }
    return node
  }

  fun diamond(fanOut: Int, executions: AtomicInteger? = null): Tile<Int> {
    val shared = singleTile {
      executions?.incrementAndGet()
      1
    }
    val branches = (1..fanOut).map { index ->
      singleTile { compose(shared) + index }
    }
    return singleTile {
      branches.map { composeAsync(it) }.sumOf { it.await() }
    }
  }

  fun widthResult(width: Int): Int = width * (width + 1) / 2

  fun diamondResult(fanOut: Int): Int = fanOut + widthResult(fanOut)
}

internal object MultiTileFixtures {
  fun tile(onBatch: ((Set<Int>) -> Unit)? = null): MultiTile<Int, Int> =
    multiTile { keys ->
      onBatch?.invoke(keys)
      keys.associateWith { it * 2 + 1 }
    }

  val tile = tile()

  fun keys(count: Int): List<Int> = (0 until count).toList()

  fun cachedKeys(count: Int, cachedPercent: Int): List<Int> = keys(count).take(count * cachedPercent / 100)

  suspend fun <R> withRequest(
    count: Int,
    cachedPercent: Int,
    tile: MultiTile<Int, Int> = this.tile,
    block: suspend org.buildmosaic.core.Mosaic.() -> R,
  ): R = emptyCanvas.withMosaic {
    val cached = cachedKeys(count, cachedPercent)
    if (cached.isNotEmpty()) compose(tile, cached)
    block()
  }

  fun expectedSum(count: Int): Int = count * count
}

internal object CanvasFixtures {
  val lookupKey = CanvasKey(Int::class, "lookup")

  suspend fun lookupCanvas(depth: Int): Canvas {
    var layer: Canvas = canvas { provide(lookupKey) { 17 } }
    repeat(depth) { layer = layer.withLayer {} }
    return layer
  }

  fun bindingKeys(count: Int): List<CanvasKey<Int>> =
    (0 until count).map { CanvasKey(Int::class, "binding-$it") }

  suspend fun child(parent: Canvas, keys: List<CanvasKey<Int>>): Canvas =
    parent.withLayer {
      keys.forEachIndexed { index, key -> provide(key) { index } }
    }
}

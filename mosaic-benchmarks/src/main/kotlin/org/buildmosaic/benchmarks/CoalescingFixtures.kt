package org.buildmosaic.benchmarks

import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.buildmosaic.core.Tile
import org.buildmosaic.core.injection.withMosaic
import org.buildmosaic.core.multiTile
import org.buildmosaic.core.singleTile
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors

/** Stable graph objects; each branch discovers keys only when its own Tile executes. */
internal object CoalescingFixtures {
  fun keys(fanOut: Int): List<List<Int>> =
    (0 until fanOut).map { branch -> List(3) { branch * 2 + it } }

  fun graph(
    fanOut: Int,
    depthSeparation: Int = 0,
    externalSuspension: Boolean = false,
    onBatch: ((Set<Int>) -> Unit)? = null,
  ): Tile<Int> {
    val products = multiTile<Int, Int> { keys ->
      onBatch?.invoke(keys)
      keys.associateWith { it * 2 + 1 }
    }
    val branches = keys(fanOut).mapIndexed { index, ids ->
      var branch = singleTile {
        if (externalSuspension && index > 0) delay(1)
        val values = compose(products, ids)
        ids.sumOf { values.getValue(it) }
      }
      // Separate later consumers from the first by additional Tile dependencies.
      if (index > 0) repeat(depthSeparation) {
        val child = branch
        branch = singleTile { compose(child) }
      }
      branch
    }
    return singleTile { branches.map(::composeAsync).sumOf { it.await() } }
  }

  fun expected(fanOut: Int): Int = keys(fanOut).flatten().sumOf { it * 2 + 1 }
}

/** Non-timed scheduler evidence, deliberately without a batch-count contract. */
object CoalescingDiagnostic {
  @JvmStatic fun main(args: Array<String>) = runBlocking {
    val shapes = listOf(Triple("same-depth-ABC-CDE", 2, 0)) +
      listOf(2, 4, 8, 16).map { Triple("fan-out-$it", it, 0) } +
      listOf(1, 2, 5, 10).map { Triple("depth-$it", 2, it) } +
      listOf(Triple("external-suspension", 2, 0))
    val serial = "--serial" in args
    val dispatcher = if (serial) Executors.newSingleThreadExecutor().asCoroutineDispatcher() else null
    println("dispatcher=${if (serial) "serial FIFO" else "Dispatchers.Default"}")
    try {
      for ((name, fanOut, depth) in shapes) {
        val batches = ConcurrentLinkedQueue<Set<Int>>()
        val root = CoalescingFixtures.graph(fanOut, depth, name == "external-suspension") { batches.add(it.toSet()) }
        val result = withContext(dispatcher ?: Dispatchers.Default) { emptyCanvas.withMosaic { compose(root) } }
        check(result == CoalescingFixtures.expected(fanOut))
        val distinct = CoalescingFixtures.keys(fanOut).flatten().toSet()
        check(batches.flatMap { it }.sorted() == distinct.sorted())
        println("$name distinct=${distinct.sorted()} invocations=${batches.size} sizes=${batches.map { it.size }} keys=${batches.map { it.sorted() }}")
      }
    } finally { dispatcher?.close() }
  }
}

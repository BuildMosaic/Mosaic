package org.buildmosaic.core

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.buildmosaic.core.injection.Canvas
import org.buildmosaic.core.observation.CallerSnapshot
import org.buildmosaic.core.observation.Contributors
import org.buildmosaic.core.observation.ExecutionKind
import org.buildmosaic.core.observation.ExecutionStart
import org.buildmosaic.core.observation.ObservationCalls
import org.buildmosaic.core.observation.ObservedExecution
import org.buildmosaic.core.observation.ProducerPublication
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.CoroutineContext

/**
 * Request-scoped Tile execution, caching, and batching, with optional execution observation.
 *
 * @param canvas Dependency bindings and immutable runtime configuration.
 * Prefer [org.buildmosaic.core.injection.withMosaic] for request-owned execution.
 * The public dispatcher constructor retains unscoped execution for compatibility.
 */
@Suppress("LargeClass", "TooManyFunctions") // Keep request ownership and protected startup settlement together.
open class MosaicImpl internal constructor(
  override val canvas: Canvas,
  context: CoroutineContext,
) : Mosaic, CoroutineScope {
  constructor(
    canvas: Canvas,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
  ) : this(canvas, dispatcher as CoroutineContext)

  private val job = SupervisorJob(context[Job])
  override val coroutineContext: CoroutineContext = context + job

  /** Stop admission through the Job, then await producer and attached-child cleanup. */
  internal suspend fun shutdown() {
    job.cancel()
    withContext(NonCancellable) { job.join() }
  }

  private val observation = canvas.runtimeConfig.executionObserver?.let { ObservationCalls(it) }
  private val executionMonitor = Any()
  private val singleCache = ConcurrentHashMap<Tile<*>, CacheEntry<*>>()
  private val multiStates = ConcurrentHashMap<MultiTile<*, *>, MultiTileState<*, *>>()

  @Suppress("UNCHECKED_CAST")
  override fun <V> composeAsync(tile: Tile<V>): Deferred<V> {
    val cached = singleCache[tile] as CacheEntry<V>?
    if (cached != null) return consume(cached)
    val entry = newEntry<V>(observation?.let { ProducerPublication(it) })
    val previous = singleCache.putIfAbsent(tile, entry) as CacheEntry<V>?
    if (previous != null) {
      entry.result.cancel()
      entry.producer?.abandon()
      return consume(previous)
    }
    // A same-Tile capture reentry now sees the winning result and producer, without recapturing.
    val caller = if (job.isActive) observation?.capture() else null
    launchSingle(tile, entry, caller)
    return consume(entry)
  }

  @Suppress("UNCHECKED_CAST")
  override fun <K : Any, V> composeAsync(
    tile: MultiTile<K, V>,
    keys: Collection<K>,
  ): Map<K, Deferred<V>> {
    if (keys.isEmpty()) return emptyMap()
    val state = multiStates.computeIfAbsent(tile) { MultiTileState<K, V>() } as MultiTileState<K, V>
    val result = HashMap<K, Deferred<V>>(keys.size)
    val consumed = if (observation != null) ArrayList<ProducerPublication>() else null
    val group = ReservationGroup<K, V>()
    try {
      for (key in keys) {
        val entry =
          state.cache[key] ?: group.reserve(state.cache, key) {
            newEntry(group.producer ?: observation?.let { ProducerPublication(it) })
          }
        result[key] = entry.result
        entry.producer?.let { consumed?.add(it) }
      }
    } finally {
      // Earlier winners remain owned even when later application key code throws.
      acceptReservations(tile, state, group)
    }
    consumed?.forEach { ObservedExecution.current()?.dependency(it) }
    return result
  }

  private fun <K : Any, V> acceptReservations(
    tile: MultiTile<K, V>,
    state: MultiTileState<K, V>,
    group: ReservationGroup<K, V>,
  ) {
    if (group.isEmpty) {
      group.producer?.abandon()
      return
    }
    val caller = if (job.isActive) observation?.capture() else null
    val owner = state.enqueue(group, caller)
    if (owner != null) launchPending(tile, state, owner)
  }

  private fun <V> newEntry(producer: ProducerPublication?): CacheEntry<V> = CacheEntry(job, executionMonitor, producer)

  private fun <V> consume(entry: CacheEntry<V>): Deferred<V> {
    entry.producer?.let { ObservedExecution.current()?.dependency(it) }
    return entry.result
  }

  private fun <V> launchSingle(
    tile: Tile<V>,
    entry: CacheEntry<V>,
    caller: CallerSnapshot?,
  ) {
    var capturedCaller = caller
    schedule(
      fail = { failure ->
        capturedCaller = null
        entry.fail(failure)
        entry.producer?.abandon()
      },
    ) {
      val contributors = capturedCaller?.let { Contributors(it, emptyList(), 1) }
      capturedCaller = null
      executeWork(
        contributors?.let { ExecutionStart(ExecutionKind.SINGLE, null, it, System.nanoTime(), tile.name) },
        listOfNotNull(entry.producer),
        { entry.fail(it) },
      ) {
        entry.publish(tile.block(this@MosaicImpl))
        null
      }
    }
  }

  private fun <K : Any, V> launchPending(
    tile: MultiTile<K, V>,
    state: MultiTileState<K, V>,
    owner: PendingBatch<K, V>,
  ) {
    schedule(fail = { state.failPending(owner, it) }) {
      state.takePending(owner)?.let { executeBatch(tile, it) }
    }
  }

  /** Accepted work enters its failure-settlement region before checking request cancellation. */
  @OptIn(DelicateCoroutinesApi::class) // ATOMIC guarantees entry into the protected region after dispatch.
  @Suppress("TooGenericExceptionCaught")
  private inline fun schedule(
    crossinline fail: (Throwable) -> Unit,
    crossinline block: suspend () -> Unit,
  ) {
    try {
      launch(start = CoroutineStart.ATOMIC) {
        try {
          currentCoroutineContext().ensureActive()
          block()
        } catch (failure: Throwable) {
          fail(failure)
        }
      }
    } catch (failure: Throwable) {
      fail(failure)
      throw failure
    }
  }

  /** Key preparation can fail before execution admission, leaving all batch origins never-started. */
  @Suppress("TooGenericExceptionCaught")
  private suspend fun <K : Any, V> executeBatch(
    tile: MultiTile<K, V>,
    batch: PendingBatch<K, V>,
  ) {
    try {
      val keys = batch.prepareKeys()
      executeWork(
        batch.takeContributors()?.let {
          ExecutionStart(ExecutionKind.MULTI, keys.size, it, System.nanoTime(), tile.name)
        },
        batch.producers,
        batch::fail,
      ) {
        tile.execution.execute(this@MosaicImpl, keys, batch)
      }
    } catch (failure: Throwable) {
      batch.fail(failure)
    }
  }

  private suspend inline fun executeWork(
    start: ExecutionStart?,
    producers: List<ProducerPublication>,
    crossinline fail: (Throwable) -> Unit,
    crossinline block: suspend () -> Throwable?,
  ) {
    currentCoroutineContext().ensureActive()
    if (producers.isNotEmpty()) {
      // Fixed-batch admission and cancellation claim all group origins atomically.
      synchronized(executionMonitor) {
        job.ensureActive()
        producers.forEach { it.begin() }
      }
    }
    executeTile(observation?.start(start, producers), fail, block)
  }
}

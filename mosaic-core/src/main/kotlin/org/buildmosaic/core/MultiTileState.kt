package org.buildmosaic.core

import org.buildmosaic.core.observation.CallerSnapshot
import org.buildmosaic.core.observation.Contributors
import org.buildmosaic.core.observation.ObservedExecution
import org.buildmosaic.core.observation.ProducerPublication
import java.util.concurrent.ConcurrentHashMap

/** One compose call's winning reservations and shared origin, without caller snapshots or cache hits. */
internal class ReservationGroup<K : Any, V> {
  val winners = mutableListOf<Pair<K, CacheEntry<V>>>()
  var producer: ProducerPublication? = null
    private set
  val isEmpty: Boolean get() = winners.isEmpty()

  /** Unpublished candidates must leave the request Job even if application key code throws. */
  @Suppress("TooGenericExceptionCaught")
  fun reserve(
    cache: ConcurrentHashMap<K, CacheEntry<V>>,
    key: K,
    createEntry: () -> CacheEntry<V>,
  ): CacheEntry<V> {
    val candidate = createEntry()
    producer = candidate.producer
    val previous =
      try {
        cache.putIfAbsent(key, candidate)
      } catch (failure: Throwable) {
        candidate.result.cancel()
        throw failure
      }
    if (previous == null) winners.add(key to candidate) else candidate.result.cancel()
    return previous ?: candidate
  }
}

/** Only the matching owner can detach work; stale launch cleanup leaves newer batches alone. */
internal class MultiTileState<K : Any, V> {
  val cache = ConcurrentHashMap<K, CacheEntry<V>>()
  private val monitor = Any()
  private var pending: PendingBatch<K, V>? = null

  fun enqueue(
    group: ReservationGroup<K, V>,
    caller: CallerSnapshot?,
  ): PendingBatch<K, V>? =
    synchronized(monitor) {
      val batch = pending ?: PendingBatch<K, V>().also { pending = it }
      if (batch.accept(group, caller)) batch else null
    }

  fun takePending(expected: PendingBatch<K, V>): PendingBatch<K, V>? =
    synchronized(monitor) {
      if (pending !== expected) null else expected.also { pending = null }
    }

  fun failPending(
    expected: PendingBatch<K, V>,
    failure: Throwable,
  ) {
    takePending(expected)?.fail(failure)
  }
}

/** Accepts complete work under the pending lock; preparation and publication happen only after detachment. */
internal class PendingBatch<K : Any, V> {
  private val groups = mutableListOf<ReservationGroup<K, V>>()
  private var callers: MutableList<CallerSnapshot>? = null
  private var contributorCount = 0L
  val producers: List<ProducerPublication> get() = groups.mapNotNull { it.producer }

  // No keys or provider tokens are hashed/compared here. Return whether this group elected the owner.
  fun accept(
    group: ReservationGroup<K, V>,
    caller: CallerSnapshot?,
  ): Boolean {
    groups.add(group)
    contributorCount++
    if (caller != null) {
      val retained = callers ?: mutableListOf<CallerSnapshot>().also { callers = it }
      if (retained.size < Contributors.RETAINED_CONTRIBUTORS) retained.add(caller)
    }
    return contributorCount == 1L
  }

  fun prepareKeys(): Set<K> =
    ObservedExecution.withoutCaller {
      // An inline scheduled batch has not started an execution yet; it cannot borrow its caller.
      val keys = LinkedHashSet<K>()
      groups.forEach { group -> group.winners.forEach { keys.add(it.first) } }
      keys
    }

  fun takeContributors(): Contributors? {
    val retained = callers ?: return null
    callers = null
    return Contributors(retained.first(), retained.drop(1), contributorCount)
  }

  fun entries(): List<Pair<K, CacheEntry<V>>> = groups.flatMap { it.winners }

  /** Preserve already published values, and fail only missing keys or still-unsettled results. */
  fun publish(values: Map<K, V>): Throwable? {
    var missing: Throwable? = null
    groups.forEach { group ->
      missing = publishEntries(group.winners, values) ?: missing
    }
    return missing
  }

  fun fail(failure: Throwable) {
    callers = null
    groups.forEach { group -> group.winners.forEach { (_, entry) -> entry.fail(failure) } }
    groups.forEach { it.producer?.abandon() }
  }
}

/** A map entry containing null is present; lookup failures preserve values already published. */
internal fun <K : Any, V> publishEntries(
  entries: List<Pair<K, CacheEntry<V>>>,
  values: Map<K, V>,
): Throwable? {
  var missing: Throwable? = null
  entries.forEach { (key, entry) ->
    val value = values[key]
    if (value != null || values.containsKey(key)) {
      @Suppress("UNCHECKED_CAST")
      entry.publish(value as V)
    } else {
      val failure = NoSuchElementException("Batch result missing key $key")
      missing = missing ?: failure
      entry.fail(failure)
    }
  }
  return missing
}

package org.buildmosaic.core

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.supervisorScope

/** Physical provider boundaries settle the existing per-key cache, before attached-child cleanup. */
internal sealed interface MultiTileExecution<K : Any, V> {
  suspend fun execute(
    mosaic: Mosaic,
    keys: Set<K>,
    batch: PendingBatch<K, V>,
  ): Throwable?
}

internal class BulkExecution<K : Any, V>(
  private val fetch: suspend Mosaic.(Set<K>) -> Map<K, V>,
) : MultiTileExecution<K, V> {
  override suspend fun execute(
    mosaic: Mosaic,
    keys: Set<K>,
    batch: PendingBatch<K, V>,
  ): Throwable? = batch.publish(fetch(mosaic, keys))
}

internal class PerKeyExecution<K : Any, V>(private val fetch: suspend Mosaic.(K) -> V) : MultiTileExecution<K, V> {
  override suspend fun execute(
    mosaic: Mosaic,
    keys: Set<K>,
    batch: PendingBatch<K, V>,
  ): Throwable? =
    supervisorScope {
      batch.entries().map { (key, entry) ->
        async {
          invokeProvider({ entry.fail(it) }) {
            entry.publish(fetch(mosaic, key))
            null
          }
        }
      }.mapNotNull { it.await() }.firstOrNull()
    }
}

internal class ChunkedExecution<K : Any, V>(
  private val batchSize: Int,
  private val fetch: suspend Mosaic.(List<K>) -> Map<K, V>,
) : MultiTileExecution<K, V> {
  override suspend fun execute(
    mosaic: Mosaic,
    keys: Set<K>,
    batch: PendingBatch<K, V>,
  ): Throwable? =
    supervisorScope {
      batch.entries().chunked(batchSize).map { entries ->
        async {
          invokeProvider({ failure -> entries.forEach { it.second.fail(failure) } }) {
            publishEntries(entries, fetch(mosaic, entries.map { it.first }))
          }
        }
      }.mapNotNull { it.await() }.firstOrNull()
    }
}

/** Contain body and attached-child failures at one physical invocation, retaining published outcomes. */
@Suppress("TooGenericExceptionCaught")
private suspend inline fun invokeProvider(
  crossinline fail: (Throwable) -> Unit,
  crossinline block: suspend () -> Throwable?,
): Throwable? {
  var outcome: Throwable? = null
  try {
    coroutineScope { outcome = block() }
  } catch (failure: Throwable) {
    outcome = outcome ?: failure
    fail(failure)
  }
  return outcome
}

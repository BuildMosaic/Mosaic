package org.buildmosaic.core

import kotlinx.coroutines.Deferred
import org.buildmosaic.core.exception.MosaicMissingKeyException
import org.buildmosaic.core.injection.Canvas
import org.buildmosaic.core.injection.CanvasKey

/**
 * Per-request context used to execute tiles and access dependencies.
 */
interface Mosaic {
  val canvas: Canvas

  /**
   * Returns Mosaic-owned shared work for [tile]. Callers may await it freely.
   * To stop waiting, cancel your own coroutine or wait operation rather than cancelling
   * the shared [Deferred], which would affect other consumers.
   *
   * @param V the type of the [Tile] return value
   * @param tile the [Tile] to retrieve
   */
  fun <V> composeAsync(tile: Tile<V>): Deferred<V>

  /**
   * Await the value of a [Tile]
   *
   * @param V the type of the [Tile] return value
   * @param tile the [Tile] to retrieve
   */
  suspend fun <V> compose(tile: Tile<V>): V = composeAsync(tile).await()

  /**
   * Returns Mosaic-owned shared work for each equal key on [tile] within this Mosaic.
   * Each key retains one terminal success or failure, including successful nullable values.
   * An omitted provider map entry fails that key; provider failure affects unfinished keys
   * in that physical invocation. Per-key and chunk factories isolate their invocation failures.
   * Successful sibling keys remain cached. Exact physical batch boundaries are not guaranteed.
   * Callers may await results freely. To stop waiting, cancel your own coroutine or wait
   * operation rather than cancelling a shared [Deferred], which would affect other consumers.
   *
   * @param K the type of the [MultiTile] keys
   * @param V the type of the [MultiTile] return value
   * @param tile the [MultiTile] to retrieve
   * @param keys the keys to be retrieved
   */
  fun <K : Any, V> composeAsync(
    tile: MultiTile<K, V>,
    keys: Collection<K>,
  ): Map<K, Deferred<V>>

  /**
   * Awaits all requested values of a [MultiTile]. Throws if any requested key fails;
   * this strict operation does not return a partial map. Successful sibling outcomes
   * remain cached. Use [composeAsync] to handle each key's outcome separately.
   *
   * @param K the type of the [MultiTile] keys
   * @param V the type of the [MultiTile] return value
   * @param tile the [MultiTile] to retrieve
   * @param keys the keys to be retrieved
   */
  suspend fun <K : Any, V> compose(
    tile: MultiTile<K, V>,
    keys: Collection<K>,
  ): Map<K, V> = composeAsync(tile, keys).mapValues { it.value.await() }

  /**
   * Returns Mosaic-owned shared work for [key]. Callers may await it freely.
   * To stop waiting, cancel your own coroutine or wait operation rather than the shared
   * [Deferred], which would affect other consumers.
   *
   * @param K the type of the [MultiTile] keys
   * @param V the type of the [MultiTile] return value
   * @param tile the [MultiTile] to retrieve
   * @param key the single key to be retrieved
   */
  fun <K : Any, V> composeAsync(
    tile: MultiTile<K, V>,
    key: K,
  ): Deferred<V> = composeAsync(tile, listOf(key))[key]!!

  /**
   * Await a single value from a [MultiTile]
   *
   * @param K the type of the [MultiTile] keys
   * @param V the type of the [MultiTile] return value
   * @param tile the [MultiTile] to retrieve
   * @param key the single key to be retrieved
   */
  suspend fun <K : Any, V> compose(
    tile: MultiTile<K, V>,
    key: K,
  ): V = composeAsync(tile, key).await()
}

/**
 * Retrieves a dependency from the canvas using reified type parameters.
 *
 * @param T The type of the dependency to retrieve
 * @param qualifier Optional qualifier to distinguish between multiple instances of the same type
 * @return The dependency instance
 * @throws [MosaicMissingKeyException] if the dependency is not found
 */
inline fun <reified T : Any> Mosaic.source(qualifier: String? = null) = canvas.source(T::class, qualifier)

/**
 * Retrieves a dependency from the canvas using a [CanvasKey].
 *
 * @param T The type of the dependency to retrieve
 * @param key The canvas key identifying the dependency
 * @return The dependency instance
 * @throws [MosaicMissingKeyException] if the dependency is not found
 */
fun <T : Any> Mosaic.source(key: CanvasKey<T>) = canvas.source(key)

/**
 * Retrieves a dependency from the canvas using reified type parameters, returning null if not found.
 *
 * @param T The type of the dependency to retrieve
 * @param qualifier Optional qualifier to distinguish between multiple instances of the same type
 * @return The dependency instance or null if not found
 */
inline fun <reified T : Any> Mosaic.sourceOr(qualifier: String? = null) = canvas.sourceOr(T::class, qualifier)

/**
 * Retrieves a dependency from the canvas using a [CanvasKey], returning null if not found.
 *
 * @param T The type of the dependency to retrieve
 * @param key The canvas key identifying the dependency
 * @return The dependency instance or null if not found
 */
fun <T : Any> Mosaic.sourceOr(key: CanvasKey<T>) = canvas.sourceOr(key)

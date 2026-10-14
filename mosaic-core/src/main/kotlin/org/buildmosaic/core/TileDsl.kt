package org.buildmosaic.core

import kotlin.reflect.KProperty

/**
 * Representation of a single-value tile in Mosaic.
 *
 * A tile encapsulates a suspending function that produces a single value of type [T].
 * Tiles are the fundamental building blocks of the Mosaic DSL and support automatic
 * caching, concurrency, and dependency injection.
 */
class Tile<T>(internal val block: suspend Mosaic.() -> T) {
  /** The first delegated property name bound to this tile, or `null` if none has been bound. */
  @Volatile
  var name: String? = null
    private set

  /** Captures the property name once. Aliases preserve this instance and its first bound name. */
  @Synchronized
  operator fun provideDelegate(
    thisRef: Any?,
    property: KProperty<*>,
  ): Tile<T> {
    if (name == null) name = property.name
    return this
  }

  /** Returns this instance without allocating a wrapper or looking up the property. */
  @Suppress("NOTHING_TO_INLINE", "UnusedParameter")
  inline operator fun getValue(
    thisRef: Any?,
    property: KProperty<*>,
  ): Tile<T> = this
}

/**
 * Creates a single-value tile using the DSL.
 *
 * @param T The type of value this tile produces
 * @param block A suspending function that produces the tile's value
 * @return A new [Tile] instance
 *
 * ```kotlin
 * val UserTile by singleTile {
 *   userService.getCurrentUser()
 * }
 * ```
 */
fun <T> singleTile(block: suspend Mosaic.() -> T): Tile<T> = Tile(block)

/**
 * Representation of a multi-value tile in Mosaic.
 *
 * A multi-tile encapsulates a suspending function that produces multiple values
 * based on a set of keys. Multi-tiles support automatic batching, caching,
 * and parallel execution.
 *
 * Within one Mosaic, pending uncached keys for the same MultiTile may combine
 * before execution. There is no intentional wait; batch boundaries depend on scheduling.
 * The same MultiTile retains one terminal outcome per equal key per Mosaic. Present
 * nullable values succeed; omitted requested keys fail with [NoSuchElementException].
 * Bulk provider failure fails unfinished keys in that physical invocation, preserving
 * outcomes already published. Per-key and chunk factories isolate their provider failures.
 */
class MultiTile<K : Any, V> internal constructor(internal val execution: MultiTileExecution<K, V>) {
  constructor(block: suspend Mosaic.(Set<K>) -> Map<K, V>) : this(BulkExecution(block))

  /** The first delegated property name bound to this tile, or `null` if none has been bound. */
  @Volatile
  var name: String? = null
    private set

  /** Captures the property name once. Aliases preserve this instance and its first bound name. */
  @Synchronized
  operator fun provideDelegate(
    thisRef: Any?,
    property: KProperty<*>,
  ): MultiTile<K, V> {
    if (name == null) name = property.name
    return this
  }

  /** Returns this instance without allocating a wrapper or looking up the property. */
  @Suppress("NOTHING_TO_INLINE", "UnusedParameter")
  inline operator fun getValue(
    thisRef: Any?,
    property: KProperty<*>,
  ): MultiTile<K, V> = this
}

/**
 * Creates a multi-value tile using a bulk provider.
 * Present map entries succeed, including `null` for a nullable value type. Omitted requested
 * keys fail individually with [NoSuchElementException]. A thrown provider failure fails
 * unfinished keys in that physical invocation; successful outcomes remain cached.
 *
 * @param K The type of keys used to request values
 * @param V The type of values this tile produces
 * @param block A suspending function that produces a map of values for the given keys
 * @return A new [MultiTile] instance
 *
 * ```kotlin
 * val UsersTile by multiTile<String, User> { userIds ->
 *   userService.getUsers(userIds)
 * }
 * ```
 */
fun <K : Any, V> multiTile(block: suspend Mosaic.(Set<K>) -> Map<K, V>): MultiTile<K, V> = MultiTile(block)

/**
 * Creates a [MultiTile] from a per-key fetch function.
 *
 * Each key is fetched independently in parallel and retains its own success or failure.
 * A failed key does not discard successful siblings. Nullable return values are successful.
 * Equal keys share one terminal outcome within a Mosaic. Example:
 *
 * ```kotlin
 * val UserTile by perKeyTile<String, User> { id ->
 *   service.fetchUser(id)
 * }
 * ```
 */
fun <K : Any, V> perKeyTile(fetch: suspend Mosaic.(K) -> V): MultiTile<K, V> = MultiTile(PerKeyExecution(fetch))

/**
 * Creates a [MultiTile] that splits incoming keys into batches of [batchSize]
 * and retains results from each independent [fetch] invocation. A failed chunk only fails
 * its unfinished keys; a partial map fails omitted keys with [NoSuchElementException] while
 * preserving present values, including `null` for a nullable value type. Successful sibling
 * chunks remain cached. Chunks run concurrently; [batchSize] limits keys per invocation,
 * not concurrency. Exact chunk membership depends on pending keys and scheduling.
 *
 * ```kotlin
 * val ProductTile by chunkedMultiTile<String, Product>(50) { ids ->
 *   service.fetchProducts(ids)
 * }
 * ```
 */
fun <K : Any, V> chunkedMultiTile(
  batchSize: Int,
  fetch: suspend Mosaic.(List<K>) -> Map<K, V>,
): MultiTile<K, V> = MultiTile(ChunkedExecution(batchSize, fetch))

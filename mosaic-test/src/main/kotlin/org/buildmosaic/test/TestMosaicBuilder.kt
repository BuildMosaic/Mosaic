/*
 * Copyright 2025 Nicholas Abbott
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.buildmosaic.test

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import org.buildmosaic.core.Mosaic
import org.buildmosaic.core.MultiTile
import org.buildmosaic.core.Tile
import org.buildmosaic.core.injection.CanvasKey
import org.buildmosaic.core.injection.canvas
import org.buildmosaic.core.multiTile
import org.buildmosaic.core.singleTile
import kotlin.jvm.JvmName
import kotlin.reflect.KClass

/**
 * Fluent configuration for scoped [TestMosaic] execution with substituted dependencies.
 *
 * This class provides a fluent API for setting up test scenarios by configuring mock tiles
 * with various behaviors. It supports both [Tile] and [MultiTile] mocks with
 * different behaviors like success, failure, delays, and custom logic.
 * Each [withMosaic] invocation inherits its calling coroutine context, including the
 * Job, dispatcher, and scheduler when called inside `runTest`.
 *
 * ### Basic Usage
 * ```kotlin
 * mosaicBuilder()
 *   .withMockTile(MyTile, "test data")
 *   .withFailedTile(OtherTile, RuntimeException("Test error"))
 *   .withDelayedTile(SlowTile, "delayed data", 1000) // 1 second delay
 *   .withMosaic { /* compose and assert here */ }
 * ```
 *
 * ### MultiTile Usage
 * ```kotlin
 * mosaicBuilder()
 *   .withMockTile(UserTile, mapOf("user1" to user1, "user2" to user2))
 *   .withCustomTile(ProfileTile) { keys ->
 *     // Custom logic based on requested keys
 *     keys.associateWith { key -> createMockProfile(key) }
 *   }
 *   .withMosaic { /* compose and assert here */ }
 * ```
 */
@Suppress("LargeClass")
class TestMosaicBuilder {
  private val sources = mutableMapOf<CanvasKey<*>, Any>()

  private val tileSubstitutions: MutableMap<Tile<*>, Tile<*>> = mutableMapOf()
  private val multiTileSubstitutions: MutableMap<MultiTile<*, *>, MultiTile<*, *>> = mutableMapOf()

  /**
   * Adds a mock [Tile] that returns the specified response.
   *
   * @param V The type of data the tile returns
   * @param tile The [Tile] to mock
   * @param response The response to return when the tile is retrieved
   * @return This builder for method chaining
   *
   * ```kotlin
   * mosaicBuilder()
   *   .withMockTile(MyTile, "test data")
   *   .withMosaic { /* compose and assert here */ }
   * ```
   */
  fun <V> withMockTile(
    tile: Tile<V>,
    response: V,
  ): TestMosaicBuilder =
    apply {
      tileSubstitutions[tile] = singleTile { response }
    }

  /**
   * Adds a mock [Tile] that fails with the specified exception.
   *
   * @param tile The [Tile] to mock
   * @param throwable The exception to throw when the tile is retrieved
   * @return This builder for method chaining
   *
   * ```kotlin
   * mosaicBuilder()
   *   .withFailedTile(MyTile, RuntimeException("Test error"))
   *   .withMosaic { /* compose and assert here */ }
   * ```
   */
  fun withFailedTile(
    tile: Tile<*>,
    throwable: Throwable,
  ): TestMosaicBuilder =
    apply {
      tileSubstitutions[tile] = singleTile<Any> { throw throwable }
    }

  /**
   * Adds a mock [Tile] that delays before returning the response.
   *
   * @param V The type of data the tile returns
   * @param tile The [Tile] to mock
   * @param response The response to return after the delay
   * @param delayMs The delay in milliseconds before returning the response
   * @return This builder for method chaining
   *
   * ```kotlin
   * mosaicBuilder()
   *   .withDelayedTile(MyTile, "delayed data", 1000) // 1 second delay
   *   .withMosaic { /* compose and assert here */ }
   * ```
   */
  fun <V> withDelayedTile(
    tile: Tile<V>,
    response: V,
    delayMs: Long,
  ): TestMosaicBuilder =
    apply {
      tileSubstitutions[tile] =
        singleTile {
          delay(delayMs)
          response
        }
    }

  /**
   * Adds a mock [Tile] with custom behavior.
   *
   * @param V The type of data the tile returns
   * @param tile The [Tile] to mock
   * @param provider A suspending lambda that provides the response
   * @return This builder for method chaining
   *
   * ```kotlin
   * mosaicBuilder()
   *   .withCustomTile(MyTile) {
   *     // Custom logic here
   *     if (condition) "result1" else "result2"
   *   }
   *   .withMosaic { /* compose and assert here */ }
   * ```
   */
  fun <V> withCustomTile(
    tile: Tile<V>,
    provider: suspend Mosaic.() -> V,
  ): TestMosaicBuilder =
    apply {
      tileSubstitutions[tile] = singleTile(provider)
    }

  /**
   * Adds a mock [MultiTile] that returns the specified responses for given keys.
   *
   * @param K The type of keys in the response
   * @param V The type of individual values in the response
   * @param tile The [Tile] to mock
   * @param response Map of keys to their corresponding values
   * @return This builder for method chaining
   *
   * ```kotlin
   * mosaicBuilder()
   *   .withMockTile(UserTile, mapOf(
   *     "user1" to User("user1"),
   *     "user2" to User("user2")
   *   ))
   *   .withMosaic { /* compose and assert here */ }
   * ```
   */
  @JvmName("withMockMultiTile")
  fun <K : Any, V> withMockTile(
    tile: MultiTile<K, V>,
    response: Map<K, V>,
  ): TestMosaicBuilder =
    apply {
      multiTileSubstitutions[tile] = multiTile { response }
    }

  /**
   * Adds a mock [MultiTile] that fails with the specified exception.
   *
   * @param tile The [MultiTile] to mock
   * @param throwable The exception to throw when the tile's methods are called
   * @return This builder for method chaining
   *
   * ```kotlin
   * mosaicBuilder()
   *   .withFailedTile(UserTile, RuntimeException("User not found"))
   *   .withMosaic { /* compose and assert here */ }
   * ```
   */
  @JvmName("withFailedMultiTile")
  fun withFailedTile(
    tile: MultiTile<*, *>,
    throwable: Throwable,
  ): TestMosaicBuilder =
    apply {
      multiTileSubstitutions[tile] = multiTile<Any, Any> { throw throwable }
    }

  /**
   * Adds a mock [MultiTile] that delays before returning responses.
   *
   * @param K The type of keys in the response
   * @param V The type of values in the response
   * @param tile The [MultiTile] to mock
   * @param response Map of keys to their corresponding values
   * @param delayMs The delay in milliseconds before returning the response
   * @return This builder for method chaining
   *
   * ```kotlin
   * mosaicBuilder()
   *   .withDelayedTile(UserTile, mapOf("user1" to User("user1")), 500)
   *   .withMosaic { /* compose and assert here */ }
   * ```
   */
  @JvmName("withDelayedMultiTile")
  fun <K : Any, V> withDelayedTile(
    tile: MultiTile<K, V>,
    response: Map<K, V>,
    delayMs: Long,
  ): TestMosaicBuilder =
    apply {
      multiTileSubstitutions[tile] =
        multiTile {
          delay(delayMs)
          response
        }
    }

  /**
   * Adds a mock [MultiTile] with custom behavior.
   *
   * @param K The type of keys in the response
   * @param V The type of values in the response
   * @param tile The [MultiTile] to mock
   * @param provider A suspending lambda that provides responses based on requested keys
   * @return This builder for method chaining
   *
   * ```kotlin
   * mosaicBuilder()
   *   .withCustomTile(UserTile) { keys ->
   *     // Custom logic based on requested keys
   *     keys.associateWith { key ->
   *       if (key.startsWith("admin")) createAdminUser(key)
   *       else createRegularUser(key)
   *     }
   *   }
   *   .withMosaic { /* compose and assert here */ }
   * ```
   */
  @JvmName("withCustomMultiTile")
  fun <K : Any, V> withCustomTile(
    tile: MultiTile<K, V>,
    provider: suspend Mosaic.(Set<K>) -> Map<K, V>,
  ): TestMosaicBuilder =
    apply {
      multiTileSubstitutions[tile] = multiTile(provider)
    }

  /**
   * Borrows a caller-owned source for the canvas with the specified class and object.
   * Allows for passing in a superclass as the retrieval type.
   *
   * @param clazz The class of the object to register
   * @param obj The object to register
   * @return This builder for method chaining
   *
   * ```kotlin
   * mosaicBuilder()
   *   .withCanvasSource(User::class, User("test-user"))
   *   .withMockTile(MyTile, "test data")
   *   .withMosaic { /* compose and assert here */ }
   * ```
   */
  fun <T : Any, V : T> withCanvasSource(
    clazz: KClass<T>,
    obj: V,
  ): TestMosaicBuilder =
    apply {
      sources[CanvasKey(clazz)] = obj
    }

  /**
   * Borrows a caller-owned source for the canvas with the specified object.
   *
   * @param obj The object to register
   * @return This builder for method chaining
   *
   * ```kotlin
   * mosaicBuilder()
   *   .withCanvasSource(User("test-user"))
   *   .withMockTile(MyTile, "test data")
   *   .withMosaic { /* compose and assert here */ }
   * ```
   */
  inline fun <reified T : Any> withCanvasSource(obj: T): TestMosaicBuilder = withCanvasSource(T::class, obj)

  /**
   * Borrows a caller-owned source for the canvas with the specified class, qualifier, and object.
   *
   * @param clazz The class of the object to register
   * @param qualifier The qualifier to distinguish this instance
   * @param obj The object to register
   * @return This builder for method chaining
   *
   * ```kotlin
   * mosaicBuilder()
   *   .withCanvasSource(DatabaseService::class, "primary", primaryDb)
   *   .withCanvasSource(DatabaseService::class, "secondary", secondaryDb)
   *   .withMosaic { /* compose and assert here */ }
   * ```
   */
  fun <T : Any, V : T> withCanvasSource(
    clazz: KClass<T>,
    qualifier: String,
    obj: V,
  ): TestMosaicBuilder =
    apply {
      sources[CanvasKey(clazz, qualifier)] = obj
    }

  /**
   * Borrows a caller-owned source for the canvas with the specified object and qualifier.
   *
   * @param qualifier The qualifier to distinguish this instance
   * @param obj The object to register
   * @return This builder for method chaining
   *
   * ```kotlin
   * mosaicBuilder()
   *   .withCanvasSource("primary", primaryDb)
   *   .withCanvasSource("secondary", secondaryDb)
   *   .withMosaic { /* compose and assert here */ }
   * ```
   */
  inline fun <reified T : Any> withCanvasSource(
    qualifier: String,
    obj: T,
  ): TestMosaicBuilder = withCanvasSource(T::class, qualifier, obj)

  /**
   * Borrows a caller-owned source for the canvas using a CanvasKey.
   *
   * @param key The canvas key to register under
   * @param obj The object to register
   * @return This builder for method chaining
   *
   * ```kotlin
   * val dbKey = CanvasKey(DatabaseService::class, "primary")
   * mosaicBuilder()
   *   .withCanvasSource(dbKey, primaryDb)
   *   .withMosaic { /* compose and assert here */ }
   * ```
   */
  fun <T : Any> withCanvasSource(
    key: CanvasKey<T>,
    obj: T,
  ): TestMosaicBuilder =
    apply {
      sources[key] = obj
    }

  /**
   * Executes [block] with a fresh cache and a snapshot of this builder's configuration.
   * Inherits the calling coroutine's Job, dispatcher, scheduler, and other context elements.
   * Every exit cancels unfinished producers and waits for their attached children to clean up.
   * Supplied Canvas sources are borrowed: they remain owned by the caller and are never closed.
   * Configure this builder from one coroutine at a time; later changes affect only later executions.
   */
  suspend fun <R> withMosaic(block: suspend TestMosaic.() -> R): R {
    val tiles = tileSubstitutions.toMap()
    val multiTiles = multiTileSubstitutions.toMap()
    val canvasSources = sources.toMap()
    val builtCanvas =
      canvas {
        canvasSources.forEach { (key, value) ->
          @Suppress("UNCHECKED_CAST")
          instance(key as CanvasKey<Any>, value)
        }
      }
    return builtCanvas.use {
      SubstitutingMosaic(it, currentCoroutineContext(), tiles, multiTiles).execute {
        block(TestMosaic(this))
      }
    }
  }
}

/** Configures substitutions and borrowed Canvas sources for scoped test executions. */
fun mosaicBuilder() = TestMosaicBuilder()

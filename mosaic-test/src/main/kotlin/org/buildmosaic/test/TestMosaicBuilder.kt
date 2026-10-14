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

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
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
 * A builder for creating [TestMosaic] instances with mocked [Tile] implementations.
 *
 * This class provides a fluent API for setting up test scenarios by configuring mock tiles
 * with various behaviors. It supports both [Tile] and [MultiTile] mocks with
 * different behaviors like success, failure, delays, and custom logic.
 * Inside `runTest`, use [mosaicBuilder] to share its [TestScope] and scheduler.
 *
 * ### Basic Usage
 * ```kotlin
 * val testMosaic = mosaicBuilder()
 *   .withMockTile(MyTile, "test data")
 *   .withFailedTile(OtherTile, RuntimeException("Test error"))
 *   .withDelayedTile(SlowTile, "delayed data", 1000) // 1 second delay
 *   .build()
 * ```
 *
 * ### MultiTile Usage
 * ```kotlin
 * val testMosaic = mosaicBuilder()
 *   .withMockTile(UserTile, mapOf("user1" to user1, "user2" to user2))
 *   .withCustomTile(ProfileTile) { keys ->
 *     // Custom logic based on requested keys
 *     keys.associateWith { key -> createMockProfile(key) }
 *   }
 *   .build()
 * ```
 */
@Suppress("LargeClass")
class TestMosaicBuilder(testContext: TestScope) {
  private val sources = mutableMapOf<CanvasKey<*>, Any>()
  private var dispatcher = StandardTestDispatcher(testContext.testScheduler)

  private val mockTileCache: MutableMap<Tile<*>, Tile<*>> = mutableMapOf()
  private val mockMultiTileCache: MutableMap<MultiTile<*, *>, MultiTile<*, *>> = mutableMapOf()

  /**
   * Adds a mock [Tile] that returns the specified response.
   *
   * @param V The type of data the tile returns
   * @param tile The [Tile] to mock
   * @param response The response to return when the tile is retrieved
   * @return This builder for method chaining
   *
   * ```kotlin
   * val testMosaic = mosaicBuilder()
   *   .withMockTile(MyTile, "test data")
   *   .build()
   * ```
   */
  fun <V> withMockTile(
    tile: Tile<V>,
    response: V,
  ): TestMosaicBuilder =
    apply {
      mockTileCache[tile] = singleTile { response }
    }

  /**
   * Adds a mock [Tile] that fails with the specified exception.
   *
   * @param tile The [Tile] to mock
   * @param throwable The exception to throw when the tile is retrieved
   * @return This builder for method chaining
   *
   * ```kotlin
   * val testMosaic = mosaicBuilder()
   *   .withFailedTile(MyTile, RuntimeException("Test error"))
   *   .build()
   * ```
   */
  fun withFailedTile(
    tile: Tile<*>,
    throwable: Throwable,
  ): TestMosaicBuilder =
    apply {
      mockTileCache[tile] = singleTile<Any> { throw throwable }
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
   * val testMosaic = mosaicBuilder()
   *   .withDelayedTile(MyTile, "delayed data", 1000) // 1 second delay
   *   .build()
   * ```
   */
  fun <V> withDelayedTile(
    tile: Tile<V>,
    response: V,
    delayMs: Long,
  ): TestMosaicBuilder =
    apply {
      mockTileCache[tile] =
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
   * val testMosaic = mosaicBuilder()
   *   .withCustomTile(MyTile) {
   *     // Custom logic here
   *     if (condition) "result1" else "result2"
   *   }
   *   .build()
   * ```
   */
  fun <V> withCustomTile(
    tile: Tile<V>,
    provider: suspend Mosaic.() -> V,
  ): TestMosaicBuilder =
    apply {
      mockTileCache[tile] = singleTile(provider)
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
   * val testMosaic = mosaicBuilder()
   *   .withMockTile(UserTile, mapOf(
   *     "user1" to User("user1"),
   *     "user2" to User("user2")
   *   ))
   *   .build()
   * ```
   */
  @JvmName("withMockMultiTile")
  fun <K : Any, V> withMockTile(
    tile: MultiTile<K, V>,
    response: Map<K, V>,
  ): TestMosaicBuilder =
    apply {
      mockMultiTileCache[tile] = multiTile { response }
    }

  /**
   * Adds a mock [MultiTile] that fails with the specified exception.
   *
   * @param tile The [MultiTile] to mock
   * @param throwable The exception to throw when the tile's methods are called
   * @return This builder for method chaining
   *
   * ```kotlin
   * val testMosaic = mosaicBuilder()
   *   .withFailedTile(UserTile, RuntimeException("User not found"))
   *   .build()
   * ```
   */
  @JvmName("withFailedMultiTile")
  fun withFailedTile(
    tile: MultiTile<*, *>,
    throwable: Throwable,
  ): TestMosaicBuilder =
    apply {
      mockMultiTileCache[tile] = multiTile<Any, Any> { throw throwable }
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
   * val testMosaic = mosaicBuilder()
   *   .withDelayedTile(UserTile, mapOf("user1" to User("user1")), 500)
   *   .build()
   * ```
   */
  @JvmName("withDelayedMultiTile")
  fun <K : Any, V> withDelayedTile(
    tile: MultiTile<K, V>,
    response: Map<K, V>,
    delayMs: Long,
  ): TestMosaicBuilder =
    apply {
      mockMultiTileCache[tile] =
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
   * val testMosaic = mosaicBuilder()
   *   .withCustomTile(UserTile) { keys ->
   *     // Custom logic based on requested keys
   *     keys.associateWith { key ->
   *       if (key.startsWith("admin")) createAdminUser(key)
   *       else createRegularUser(key)
   *     }
   *   }
   *   .build()
   * ```
   */
  @JvmName("withCustomMultiTile")
  fun <K : Any, V> withCustomTile(
    tile: MultiTile<K, V>,
    provider: suspend Mosaic.(Set<K>) -> Map<K, V>,
  ): TestMosaicBuilder =
    apply {
      mockMultiTileCache[tile] = multiTile(provider)
    }

  /**
   * Adds a source to the canvas with the specified class and object.
   * Allows for passing in a superclass as the retrieval type.
   *
   * @param clazz The class of the object to register
   * @param obj The object to register
   * @return This builder for method chaining
   *
   * ```kotlin
   * val testMosaic = mosaicBuilder()
   *   .withCanvasSource(User::class, User("test-user"))
   *   .withMockTile(MyTile, "test data")
   *   .build()
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
   * Adds a source to the canvas with the specified object.
   *
   * @param obj The object to register
   * @return This builder for method chaining
   *
   * ```kotlin
   * val testMosaic = mosaicBuilder()
   *   .withCanvasSource(User("test-user"))
   *   .withMockTile(MyTile, "test data")
   *   .build()
   * ```
   */
  inline fun <reified T : Any> withCanvasSource(obj: T): TestMosaicBuilder = withCanvasSource(T::class, obj)

  /**
   * Adds a source to the canvas with the specified class, qualifier, and object.
   *
   * @param clazz The class of the object to register
   * @param qualifier The qualifier to distinguish this instance
   * @param obj The object to register
   * @return This builder for method chaining
   *
   * ```kotlin
   * val testMosaic = mosaicBuilder()
   *   .withCanvasSource(DatabaseService::class, "primary", primaryDb)
   *   .withCanvasSource(DatabaseService::class, "secondary", secondaryDb)
   *   .build()
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
   * Adds a source to the canvas with the specified object and qualifier.
   *
   * @param qualifier The qualifier to distinguish this instance
   * @param obj The object to register
   * @return This builder for method chaining
   *
   * ```kotlin
   * val testMosaic = mosaicBuilder()
   *   .withCanvasSource("primary", primaryDb)
   *   .withCanvasSource("secondary", secondaryDb)
   *   .build()
   * ```
   */
  inline fun <reified T : Any> withCanvasSource(
    qualifier: String,
    obj: T,
  ): TestMosaicBuilder = withCanvasSource(T::class, qualifier, obj)

  /**
   * Adds a source to the canvas using a CanvasKey.
   *
   * @param key The canvas key to register under
   * @param obj The object to register
   * @return This builder for method chaining
   *
   * ```kotlin
   * val dbKey = CanvasKey(DatabaseService::class, "primary")
   * val testMosaic = mosaicBuilder()
   *   .withCanvasSource(dbKey, primaryDb)
   *   .build()
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
   * Builds and returns a configured [TestMosaic] instance.
   *
   * This method finalizes the builder configuration and creates a new [TestMosaic]
   * with all the specified mock tiles and request setup.
   *
   * @return A new [TestMosaic] instance ready for testing
   *
   * ```kotlin
   * val testMosaic = mosaicBuilder()
   *   .withMockTile(MyTile, "test data")
   *   .build()
   * ```
   */
  fun build(): TestMosaic {
    // Supplied values need no suspending construction; keep the test builder API synchronous.
    val builtCanvas =
      runBlocking {
        canvas {
          sources.forEach { (key, value) ->
            @Suppress("UNCHECKED_CAST")
            single(key as CanvasKey<Any>) { value }
          }
        }
      }
    return TestMosaic(builtCanvas, mockTileCache, mockMultiTileCache, dispatcher)
  }
}

fun TestScope.mosaicBuilder() = TestMosaicBuilder(this)

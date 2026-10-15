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

package org.buildmosaic.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.buildmosaic.core.injection.canvas
import org.buildmosaic.core.injection.withMosaic
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals

@Suppress("FunctionMaxLength")
class MosaicConcurrencyTest {
  @Test
  fun `sync and async consumers share one Tile execution`() =
    runTest {
      withContext(Dispatchers.Default) {
        canvas {}.withMosaic {
          val calls = AtomicInteger()
          val tile =
            singleTile {
              calls.incrementAndGet()
              delay(10)
              "value"
            }
          val results =
            coroutineScope {
              (0 until 20).map { index ->
                async { if (index % 2 == 0) compose(tile) else composeAsync(tile).await() }
              }.awaitAll()
            }
          assertEquals(List(20) { "value" }, results)
          assertEquals(1, calls.get())
        }
      }
    }

  @Test
  fun `concurrent MultiTile entry points deduplicate keys`() =
    runTest {
      withContext(Dispatchers.Default) {
        canvas {}.withMosaic {
          val fetched = ConcurrentLinkedQueue<Set<String>>()
          val tile =
            multiTile<String, String> { keys ->
              fetched += keys
              delay(5)
              keys.associateWith { it.replace("key", "value") }
            }
          val results =
            coroutineScope {
              listOf(
                async { compose(tile, listOf("key1", "key2")) },
                async { composeAsync(tile, listOf("key2", "key3")).mapValues { it.value.await() } },
                async { mapOf("key1" to compose(tile, "key1")) },
                async { mapOf("key3" to composeAsync(tile, "key3").await()) },
              ).awaitAll()
            }
          assertEquals(
            listOf(
              mapOf("key1" to "value1", "key2" to "value2"),
              mapOf("key2" to "value2", "key3" to "value3"),
              mapOf("key1" to "value1"),
              mapOf("key3" to "value3"),
            ),
            results,
          )
          assertEquals(listOf("key1", "key2", "key3"), fetched.flatMap { it }.sorted())
        }
      }
    }
}

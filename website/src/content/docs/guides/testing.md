---
title: 'Test compositions'
description: 'Replace dependency Tiles with values, failures, and virtual-time delays.'
---

Test real response logic by replacing its dependency Tiles. `mosaic-test` uses the same runtime as production, with selected substitutions inside a scoped execution. `withMosaic` inherits the calling coroutine context, including the enclosing `runTest` Job, dispatcher, and virtual-time scheduler. Use Canvas sources for request input and service fakes.

The examples on `develop` use the unreleased scoped test API. For the published 0.7.0 artifact, use the [release test guide](https://github.com/BuildMosaic/Mosaic/blob/0.7.0/mosaic-test/README.md); see [scoped execution migration](/reference/releases/#scoped-execution-migration) when updating consumers.

## Quick Start

## Installation

In an existing Kotlin/JVM project with a configured test runner:

```kotlin
dependencies {
  testImplementation("org.buildmosaic:mosaic-test:0.7.0")
  testImplementation(kotlin("test"))
}
```

Use the same version of `mosaic-test` and `mosaic-core`. See [runtime compatibility](/reference/compatibility/) for supported Kotlin versions and Java requirements.

`mosaic-test` exposes Mosaic core and coroutine test APIs and must use the same Mosaic version as `mosaic-core`. Use the [BOM](https://github.com/BuildMosaic/Mosaic/blob/main/mosaic-bom/README.md) to keep runtime modules aligned and omit versions on Mosaic dependencies.

## Your first Tile test

Inside `runTest`, call `mosaicBuilder().withMosaic { ... }` to execute in the enclosing coroutine context. This complete test replaces a dependency while leaving the response Tile's formatting logic real:

```kotlin
import kotlinx.coroutines.test.runTest
import org.buildmosaic.core.singleTile
import org.buildmosaic.test.mosaicBuilder
import kotlin.test.Test

class GreetingTest {
  @Test
  fun `greeting formats the dependency result`() = runTest {
    val nameTile = singleTile { "Production name" }
    val greetingTile = singleTile { "Hello, ${compose(nameTile)}!" }

    mosaicBuilder()
      .withMockTile(nameTile, "Jane")
      .withMosaic {
        assertEquals(greetingTile, "Hello, Jane!")
      }
  }
}
```

Mock the same Tile instance that the subject composes. Unmocked tiles execute their real blocks. Avoid mocking the subject when you want to verify its logic. Place the following methods in your test class, using these same imports plus any shown locally.

## Provide Canvas input

Use `withCanvasSource` to exercise actual `source` lookups. No production Canvas construction is needed for this test:

```kotlin
import org.buildmosaic.core.injection.CanvasKey
import org.buildmosaic.core.source

@Test
fun `greeting reads request input`() = runTest {
  val userIdKey = CanvasKey(String::class, "userId")
  val greetingTile = singleTile { "Hello, ${source(userIdKey)}!" }

  mosaicBuilder()
    .withCanvasSource(userIdKey, "user-123")
    .withMosaic {
      assertEquals(greetingTile, "Hello, user-123!")
    }
}
```

You can also register a service fake by class with `withCanvasSource(Service::class, fakeService)` or by a qualified key. These values already belong to the caller. Test Canvas registers them with `instance`, so even `AutoCloseable` sources remain caller-owned and are never closed by Canvas, including during construction rollback. Only provide the inputs used by real blocks; replacing a Tile bypasses its own Canvas reads.

## Mock MultiTile results

Supply a result map for requested keys, then test the consumer's calculation:

```kotlin
import org.buildmosaic.core.multiTile

@Test
fun `total sums mocked prices for requested SKUs`() = runTest {
  val pricingTile = multiTile<String, Int> { error("External service") }
  val totalTile = singleTile {
    compose(pricingTile, listOf("SKU1", "SKU2")).values.sum()
  }

  mosaicBuilder()
    .withMockTile(pricingTile, mapOf("SKU1" to 100, "SKU2" to 250))
    .withMosaic {
      assertEquals(totalTile, 350)
    }
}
```

This verifies composition with mocked prices, not batching efficiency. To verify a Tile's batching behavior, leave that MultiTile real, provide a recording service fake through Canvas, and assert which keys reach the service. See the checked-in [ProductsByIdTile tests](https://github.com/BuildMosaic/Mosaic/blob/develop/examples/tile-library/src/test/kotlin/org/buildmosaic/library/tile/ProductsByIdTileTest.kt) for examples of exercising the real MultiTile.

## Verify exception propagation

```kotlin
@Test
fun `greeting propagates a dependency failure`() = runTest {
  val nameTile = singleTile { "Production name" }
  val greetingTile = singleTile { "Hello, ${compose(nameTile)}!" }

  mosaicBuilder()
    .withFailedTile(nameTile, IllegalStateException("Service unavailable"))
    .withMosaic {
      assertThrows(greetingTile, IllegalStateException::class)
    }
}
```

This asserts propagation, not graceful recovery. If your Tile catches a specific failure and returns a fallback, assert that fallback result in a separate test.

## Simulate delays with virtual time

`withDelayedTile` uses coroutine `delay` on the test scheduler. `runTest` advances virtual time while awaiting the result; elapsed wall-clock time is not the assertion target. This checks simulated timing behavior, not performance:

```kotlin
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.currentTime
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
@Test
fun `delayed mock advances virtual time before returning`() = runTest {
  val dataTile = singleTile { "Production data" }
  mosaicBuilder()
    .withDelayedTile(dataTile, "Test data", delayMs = 200)
    .withMosaic {
      val start = currentTime
      assertEquals(dataTile, "Test data")
      assertEquals(200L, currentTime - start)
    }
}
```

## Mock and assertion reference

All mock behaviors support both Tile and MultiTile dependencies:

| Builder method                             | Behavior                                                                                             |
| ------------------------------------------ | ---------------------------------------------------------------------------------------------------- |
| `withMockTile(tile, response)`             | Return a value, or a map for a MultiTile                                                             |
| `withFailedTile(tile, throwable)`          | Throw when composed                                                                                  |
| `withDelayedTile(tile, response, delayMs)` | Delay in virtual time, then return                                                                   |
| `withCustomTile(tile) { ... }`             | Execute a suspending provider with a Mosaic receiver; MultiTile providers also receive a set of keys |
| `withCanvasSource(key, value)`             | Make input or a service fake available to `source`                                                   |

A custom provider can use `source` and compose dependencies. For a MultiTile, return a map containing the requested keys. Each `withMosaic` invocation has a fresh cache and snapshots the configured Tile substitutions, MultiTile substitutions, and Canvas sources before execution starts. Reusing or changing the builder affects later executions, never an execution already in progress. Configure a builder from one coroutine at a time.

| Assertion                                                    | What it verifies                                 |
| ------------------------------------------------------------ | ------------------------------------------------ |
| `assertEquals(tile, expected)`                               | Tile result equality                             |
| `assertEquals(tile, keys, expectedMap)`                      | MultiTile result equality for the requested keys |
| `assertThrows(tile, ExceptionType::class)`                   | Tile throws the expected exception type          |
| `assertThrows(multiTile, listOf(key), ExceptionType::class)` | MultiTile throws for the requested keys          |

Inside `withMosaic`, use `compose(...)`, `composeAsync(...)`, and `canvas` with ordinary Kotlin test assertions for custom checks. Producer work belongs to this execution: cancelling one waiter preserves shared work, while enclosing coroutine cancellation cancels unfinished producers. Normal or exceptional block exit cancels speculative work and waits for producer and attached-child cleanup. No explicit shutdown is needed. See the runnable [order example tests](https://github.com/BuildMosaic/Mosaic/tree/develop/examples/tile-library/src/test/kotlin/org/buildmosaic/library/tile) for response composition, request inputs, and real Tile behavior.

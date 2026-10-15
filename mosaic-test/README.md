# Mosaic Test Framework

**Test Tile logic by replacing its dependencies.**

`mosaic-test` runs real compositions with selected Tile or MultiTile dependencies
replaced by values, failures, delays, or custom providers. It uses the same runtime
as production, with the enclosing coroutine TestScope and scheduler.

This guide follows the unreleased scoped API on `develop`. For the published
0.7.0 artifact, use the [release test guide](https://github.com/BuildMosaic/Mosaic/blob/0.7.0/mosaic-test/README.md).
See the [scoped execution migration](https://BuildMosaic.org/reference/releases/#scoped-execution-migration)
when updating consumers.

## Installation

Use the [installation guide](https://BuildMosaic.org/start/installation/) for
released dependencies. The scoped examples below require a source build until
1.0 is published; do not use them against the 0.7.0 artifact.

The module exposes core and coroutine test APIs. `mosaic-test` must use the same
Mosaic version as `mosaic-core`; use the [runtime BOM](../mosaic-bom/README.md) to
keep runtime modules aligned. See
[runtime compatibility](https://BuildMosaic.org/reference/compatibility/).

## Test the real composition

```kotlin
import kotlinx.coroutines.test.runTest
import org.buildmosaic.core.singleTile
import org.buildmosaic.test.mosaicBuilder
import kotlin.test.Test

class GreetingTest {
  @Test
  fun `greeting formats a dependency`() = runTest {
    val nameTile = singleTile { "Production name" }
    val greetingTile = singleTile { "Hello, ${compose(nameTile)}!" }
    mosaicBuilder().withMockTile(nameTile, "Jane").withMosaic {
      assertEquals(greetingTile, "Hello, Jane!")
    }
  }
}
```

Mock the same Tile instance the subject composes. Unmocked Tiles execute their
real blocks. Each `withMosaic` invocation creates a fresh cache, snapshots the
builder configuration, and uses the calling coroutine context. Inside `runTest`,
that includes its Job, dispatcher, and virtual-time scheduler. Every exit cancels
unfinished work and waits for producer and attached-child cleanup, just as in production.

The **[testing guide at BuildMosaic.org](https://BuildMosaic.org/guides/testing/)**
covers Canvas input, MultiTile substitution, failures, and virtual-time delays.
Use a recording service fake with a real MultiTile to test batching rather than
mocking that behavior away. [Kotlin API reference](https://BuildMosaic.org/api/mosaic-test/).

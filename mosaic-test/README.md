# Mosaic Test Framework

**Test Tile logic by replacing its dependencies.**

`mosaic-test` runs real compositions with selected Tile or MultiTile dependencies
replaced by values, failures, delays, or custom providers. It uses the same runtime
as production, with the enclosing coroutine TestScope and scheduler.

## Installation

In a Kotlin/JVM project with a configured test runner:

```kotlin
dependencies {
  testImplementation("org.buildmosaic:mosaic-test:0.7.0")
  testImplementation(kotlin("test"))
}
```

The module exposes core and coroutine test APIs. Use the same Mosaic version as
core, or the [runtime BOM](../mosaic-bom/README.md). See
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
    val testMosaic = mosaicBuilder().withMockTile(nameTile, "Jane").build()
    testMosaic.assertEquals(greetingTile, "Hello, Jane!")
  }
}
```

Mock the same Tile instance the subject composes. Unmocked Tiles execute their
real blocks. Create a fresh test Mosaic for each scenario.

The **[testing guide at BuildMosaic.org](https://BuildMosaic.org/guides/testing/)**
covers Canvas input, MultiTile substitution, failures, and virtual-time delays.
Use a recording service fake with a real MultiTile to test batching rather than
mocking that behavior away. [Kotlin API reference](https://BuildMosaic.org/api/mosaic-test/).

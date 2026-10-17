# Mosaic Core

**Compose backend responses from small, reusable Tiles.**

`mosaic-core` supplies Canvas, Mosaic, Tile, and MultiTile. Canvas binds services
and request input; a request Mosaic runs Tiles and shares their in-flight work
and results. MultiTile adds equal-key reuse and opportunistic batching.

## Installation

Use the [installation guide](https://BuildMosaic.org/start/installation/) for published dependencies and optional module alignment.

Runtime use needs no registration plugin or processor. Java 17 or later is required.
Choose a Kotlin version supported by your Runtime release; see [compatibility](https://BuildMosaic.org/reference/compatibility/) and the
[BOM](../mosaic-bom/README.md) for runtime alignment.

## Compose a response

Using the [shared order example](../examples/tile-library):

```kotlin
val OrderPageTile by singleTile {
  val summary = composeAsync(OrderSummaryTile)
  val logistics = composeAsync(LogisticsTile)
  OrderPage(summary.await(), logistics.await())
}
```

Use the scoped entry point once per request:

```kotlin
import org.buildmosaic.core.injection.withMosaic

suspend fun handle(canvas: Canvas): OrderPage =
  canvas.withMosaic { compose(OrderPageTile) }
```

Libraries can accept `Mosaic` directly: `canvas.withMosaic { handler(this) }`.
The calling coroutine owns producer work and supplies its dispatcher and context.
Every block exit cancels unfinished speculative work and waits for cleanup. Tile
failures are supervised; cancelling a consumer's coroutine stops its wait without
cancelling shared work. Await `composeAsync` results freely, but do not cancel the
shared Deferred to stop waiting. `withMosaic` is the supported execution boundary;
keep the complete handler invocation inside its block.

Reuse requires the same Mosaic and Tile instance;
Tile names are labels, not cache identities. MultiTile batch boundaries depend on
scheduling. Scope or close a Canvas when its local bindings own resources;
`withMosaic` does not close its Canvas. Use `provide { ... }` for Canvas-owned
values or `instance(existingValue)` for externally owned bindings that Canvas
must never close, including on construction failure.

MultiTile retains a terminal outcome per key. Present nullable values succeed;
omitted keys fail individually. A bulk provider exception fails that invocation's
unfinished keys, a `perKeyTile` failure affects its key, and a `chunkedMultiTile`
exception affects its chunk. Successful siblings remain cached. Strict
`compose(tile, keys)` throws if a requested key fails; `composeAsync` exposes each
key's shared Deferred.

The canonical user guide is at **[BuildMosaic.org](https://BuildMosaic.org/start/overview/)**:

- [Quick Start](https://BuildMosaic.org/start/quick-start/)
- [Tiles and composition](https://BuildMosaic.org/concepts/tiles/)
- [Canvas dependencies](https://BuildMosaic.org/concepts/canvas/) and [resource ownership](https://BuildMosaic.org/guides/resources/)
- [Shared work](https://BuildMosaic.org/concepts/shared-work/) and [MultiTile](https://BuildMosaic.org/concepts/multitile/)
- [Kotlin API reference](https://BuildMosaic.org/api/mosaic-core/)

KDoc owns API-level contracts, including the external dependency resolver and
execution observation SPIs.
Optional [analysis tooling](../mosaic-gradle-plugin/README.md) has its own narrower
compatibility boundary and is independent of runtime use.

---
title: 'Shared work and identity'
description: 'Understand request-scoped caching, in-flight reuse, and stable Tile identity.'
---

Reuse belongs to one Mosaic instance. Calls using the same Tile instance share its in-flight work and completed result. Create a Mosaic per request to keep that reuse scoped to the request.

## Share a Mosaic at the request boundary

The order page and total both reach `LineItemsTile`. Create one request Mosaic and use it for both results:

```kotlin
applicationCanvas.withLayer {
  instance(OrderKey, orderId)
}.withMosaic {
  val page = composeAsync(OrderPageTile)
  val total = composeAsync(OrderTotalTile)
  println("${page.await().summary.order.id}: ${total.await()}")
}
```

This excerpt belongs in a suspending handler, with the order example's declarations and `org.buildmosaic.core.injection.withMosaic` imported. One branch may start `LineItemsTile` before the other reaches it. Both reuse its producer and value.

`withMosaic` inherits the request coroutine's dispatcher and context. Producers are children of its Job and supervised from one another. Request cancellation cancels unfinished work. Normal or exceptional block exit cancels unfinished speculative work and waits for producer and attached-child cleanup.

The `Deferred` returned by `composeAsync` represents Mosaic-owned shared work. Await it freely. To stop waiting, cancel your own coroutine or wait operation rather than cancelling that shared Deferred; other consumers may still need it.

Libraries can keep Canvas out of a handler API:

```kotlin
suspend fun handler(mosaic: Mosaic): OrderPage = mosaic.compose(OrderPageTile)

// At the request boundary:
val page = requestCanvas.withMosaic { handler(this) }
```

`Canvas.withMosaic` is the supported execution boundary. Keep the complete handler call inside its block.

Calling `withMosaic` twice on that Canvas executes the work separately. Use one invocation per request to keep request input and reuse scoped correctly.

## Identity is not a name or source body

| Declaration                                       | Shares the original Tile's cache identity?  |
| ------------------------------------------------- | ------------------------------------------- |
| `val Alias = OrderTile`                           | Yes: the same instance                      |
| `val Alias by OrderTile`                          | Yes: the same instance; first name retained |
| Another `singleTile { ... }` with equivalent code | No: a new Tile instance                     |
| The same Tile used in another Mosaic              | No: a separate Mosaic cache                 |

Declare reusable Tiles as stable `val`s. Each `Canvas.withMosaic` invocation creates a fresh Mosaic and cache; sharing a Canvas does not share Tile results across Mosaics.

Keep shared dependencies as stable declarations. Constructing a Tile inside each caller prevents those callers from sharing it, even if the code is identical.

## Keyed reuse

MultiTile adds key equality to its instance identity. Requests for `A, B` followed by `B, C` reuse `B`; only uncached keys enter its fetch block. Pending new keys can coalesce before a batch starts, while a started batch is fixed. This is separate from the guarantee that equal keys share their work.

See [MultiTile](/concepts/multitile/) and [batching strategies](/concepts/batching/).

## Failures are retained too

A repeated composition in the same Mosaic sees its retained failed result. Repeating `compose` is not a retry policy. Keep deliberate retry/recovery logic in your service boundary or Tile, and test the intended behavior with [failed dependency Tiles](/guides/testing/#verify-exception-propagation).

## Property names

Use `by` to bind a property name to a reusable Tile. All four factories support this syntax:

```kotlin
val OrderTile by singleTile { source<OrderService>().getOrder(source(OrderKey)) }
val Products by multiTile<String, Product> { ids -> source<ProductService>().getProducts(ids.toList()) }
val PerKey by perKeyTile<String, Product> { id -> source<ProductService>().getProducts(listOf(id)).getValue(id) }
val Chunked by chunkedMultiTile<String, Product>(50) { ids -> source<ProductService>().getProducts(ids) }
```

`OrderTile.name` is `"OrderTile"`. Top-level, member, and local delegated properties bind a name and return the same Tile instance on every read.

The first delegated name is retained: `val Alias by OrderTile` shares the same instance and keeps the name `"OrderTile"`. An ordinary alias using `=` also shares its instance and name. Names are read-only labels for diagnostics and tracing; they are not unique identifiers and do not change caching or equality. The [OpenTelemetry adapter](/guides/tracing/) uses them as span names.

A Tile created with `val OrderTile = singleTile { ... }` has `name == null` until it is used as a delegate. The optional compiler plugin does not assign runtime names. For analysis of delegated declarations, see the [compiler's supported boundaries](https://github.com/BuildMosaic/Mosaic/blob/main/mosaic-compiler-plugin/README.md#extraction-guarantees).

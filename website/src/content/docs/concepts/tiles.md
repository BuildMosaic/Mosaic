---
title: 'Tiles and composition'
description: "Compose Kotlin backend work with Mosaic's dynamic DAG scheduler. Discover dependencies at runtime and share work within one request."
head:
  - tag: title
    content: 'Kotlin Tiles and dynamic DAG scheduling | Mosaic'
  - tag: meta
    attrs:
      property: og:title
      content: 'Kotlin Tiles and dynamic DAG scheduling'
  - tag: meta
    attrs:
      name: twitter:title
      content: 'Kotlin Tiles and dynamic DAG scheduling | Mosaic'
---

A Tile produces one value. Its suspending block has a Mosaic receiver, so it can compose dependencies and read Canvas input. Declare reusable Tiles as stable `val`s; each declaration supplies the identity used for request reuse.

The examples below use the models, services, and dependencies in the [shared order library](https://github.com/BuildMosaic/Mosaic/tree/runtime-1.0.0/examples/tile-library). Import `org.buildmosaic.core.*` and the corresponding example declarations.

`compose(tile)` suspends until a value is available. `composeAsync(tile)` returns `Deferred<V>` so independent work can start before you await it. Sequential `compose` calls remain sequential.

```kotlin
val OrderSummaryTile by singleTile {
  val order = composeAsync(OrderTile)
  val customer = composeAsync(CustomerTile)
  val lineItems = composeAsync(LineItemsTile)

  OrderSummary(order.await(), customer.await(), lineItems.await())
}
```

## Compose a dynamic dependency graph

Mosaic acts as a **dynamic DAG scheduler** for this composition. A directed acyclic graph (DAG) describes the dependency structure you compose. The graph emerges from the `compose` and `composeAsync` calls your Kotlin code reaches: a Tile can read a value, choose a branch, or discover new keys before requesting more work. Kotlin coroutines run that work, and branches in the same Mosaic can [share a Tile's in-flight execution and result](/concepts/shared-work/).

The checked-in [LineItemsTile](https://github.com/BuildMosaic/Mosaic/blob/runtime-1.0.0/examples/tile-library/src/main/kotlin/org/buildmosaic/library/tile/LineItemsTile.kt) gets product IDs and SKUs from `order.items`, starts both batches, then awaits each item's values:

```kotlin
val LineItemsTile by singleTile {
  val order = compose(OrderTile)
  val productIds = order.items.map { it.productId }
  val skus = order.items.map { it.sku }

  val products = composeAsync(ProductsByIdTile, productIds)
  val pricing = composeAsync(PricingBySkuTile, skus)

  order.items.map { item ->
    val product = products.getValue(item.productId).await()
    val price = pricing.getValue(item.sku).await()
    LineItemDetail(product, price, item.quantity)
  }
}
```

These batch calls return maps of deferred values, not a deferred map. The `LineItemDetail` model takes a non-null product, price, and quantity. Use ordinary Kotlin conditionals to choose dependencies. Exceptions propagate through `compose` or `await`; recovery requires an explicit handler, such as catching a specific service exception and composing a fallback Tile.

## Give work a useful name

Use a delegated declaration to capture the property name:

```kotlin
val OrderTile by singleTile {
  source<OrderService>().getOrder(source(OrderKey))
}
```

`OrderTile.name` is `"OrderTile"`. The same syntax works with MultiTile factories. An ordinary `=` declaration is valid but has no name until delegated. Names are read-only diagnostic labels; they are not cache identities. Aliases retain the same instance and first bound name.

## Keep boundaries useful

A response Tile describes how to assemble its result. A dependency Tile knows how to obtain its own inputs. Service calls stay ordinary Kotlin; Mosaic does not replace your HTTP client, persistence library, or domain models.

Choose [concurrent composition](/concepts/concurrency/) for independent branches, [MultiTile](/concepts/multitile/) for keyed work, and [Canvas](/concepts/canvas/) for application services and request values.

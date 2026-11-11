---
title: 'Dynamic DAG scheduling in Kotlin with Mosaic'
description: 'Use Mosaic as a dynamic DAG scheduler for Kotlin backends. Compose dependencies at runtime, execute with coroutines, and share work within one request.'
---

Mosaic is a **dynamic DAG scheduler for Kotlin backends**. You express the work needed for a response as reusable Tiles. Each Tile can ask for more work while it runs, and Mosaic schedules those dependencies with Kotlin coroutines.

A directed acyclic graph (DAG) describes the dependency structure: Tiles are units of work, and composition calls connect a caller to the results it needs. Build acyclic result dependencies, with each chain resolving from request inputs or service data.

## Let execution discover the dependencies

The graph emerges from ordinary Kotlin execution. A Tile can read a value before choosing which dependency to compose, and a MultiTile can receive keys discovered from an earlier result. Different request inputs can therefore lead to different work.

In the [order example](https://github.com/BuildMosaic/Mosaic/blob/main/examples/tile-library/src/main/kotlin/org/buildmosaic/library/tile/LineItemsTile.kt), the product IDs and pricing keys become known after the order arrives:

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

This snippet uses the order example's models and Tile declarations. Product and pricing work starts after its keys are available. Both branches start before either result is awaited, so their suspending work can progress concurrently. Use ordinary Kotlin conditionals to choose a branch when the request only needs one path.

Follow the [Kotlin API aggregation guide](/guides/kotlin-api-aggregation/) to run the complete response behind an HTTP endpoint.

## Schedule work with coroutines

`composeAsync` schedules a Tile's work and returns its shared `Deferred`. `compose` suspends until that result is available. A Tile can suspend for one dependency, resume with its value, and discover more dependencies from that value.

The request's coroutine context supplies the dispatcher. Independent branches started with `composeAsync` can overlap, while sequential `compose` calls preserve the order you write. See [concurrent composition](/concepts/concurrency/) for the call patterns.

## Share dependencies within the request

Two branches that ask for the same Tile instance in one Mosaic share its in-flight execution and result. With a MultiTile, equal keys share work within that same instance. This lets each branch declare its own needs while common dependencies execute once.

Keep reusable Tiles in stable declarations and create one Mosaic at the request boundary with `Canvas.withMosaic`. A new Mosaic starts with a fresh cache. Request cancellation cancels unfinished work, and leaving the block waits for cleanup. The [shared-work guide](/concepts/shared-work/) covers identity and coroutine ownership.

## See the graph and follow execution

Use [OpenTelemetry tracing](/guides/tracing/) to inspect the work that actually ran and the links between shared producers and callers. The optional [analysis plugin](/guides/analysis/) produces architecture diagrams from statically understood Tile relationships. Runtime scheduling comes from the composition code itself.

[Install Mosaic](/start/installation/) and follow the [Quick Start](/start/quick-start/) to build your first composition.

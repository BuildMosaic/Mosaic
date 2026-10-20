---
title: 'Concurrent composition in Kotlin'
description: 'Start independent Tile branches with composeAsync, await their results, and keep coroutine work within its owning scope.'
---

`compose(tile)` suspends until the result is available. `composeAsync(tile)` returns a `Deferred<V>` so you can start independent branches before awaiting their results. Sequential `compose` calls remain sequential.

## Start branches before awaiting

Using the shared order example's declarations:

```kotlin
val OrderPageTile by singleTile {
  val summary = composeAsync(OrderSummaryTile)
  val logistics = composeAsync(LogisticsTile)
  OrderPage(summary.await(), logistics.await())
}
```

Both branches can progress concurrently. Waiting for summary before reading logistics does not prevent logistics from starting.

This arrangement starts logistics only after summary finishes:

```kotlin
val OrderPageTile by singleTile {
  val summary = compose(OrderSummaryTile)
  val logistics = compose(LogisticsTile)
  OrderPage(summary, logistics)
}
```

Use the sequential form when the second operation needs the first value. Use ordinary Kotlin conditionals when only one dependency is needed on a path; starting speculative work is a decision made by your composition.

## Reuse across concurrent branches

Two callers of the same Tile instance in one Mosaic receive the same in-flight work. You do not need to pass a result from one sibling to another or coordinate which branch starts it. A fresh Mosaic has a fresh cache. See [shared work and identity](/concepts/shared-work/) for boundaries and failures.

For a MultiTile, `composeAsync(tile, keys)` returns a **map of deferred values**, not a deferred map. Await individual entries as they are needed:

```kotlin
val pending = composeAsync(ProductsByIdTile, productIds)
val product = pending.getValue(productId).await()
```

This excerpt belongs inside a Tile or suspending caller with the example's product IDs and MultiTile. [MultiTile](/concepts/multitile/) explains all return shapes.

## Failure and execution scope

Exceptions propagate through `compose` or `await`. Catch a specific service failure when your response has an intentional fallback; Mosaic does not silently substitute results or retry work. Failed results remain in that Mosaic's cache.

Execution observation includes attached child coroutines, so a result can become available before an execution span finishes. Read the [tracing semantics](/guides/tracing/#completion-and-retention) when interpreting spans.

Concurrency overlaps independent suspending work; it does not make blocking service clients non-blocking or guarantee a particular execution order.

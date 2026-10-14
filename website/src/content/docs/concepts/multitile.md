---
title: 'MultiTile and keyed work'
description: 'Reuse overlapping keys and compose batched or individual fetches through one API.'
---

A `MultiTile<K, V>` produces values for requested keys. Choose a fetch strategy; consumers keep the same composition API. These excerpts use the order example's services and models:

```kotlin
val PricingBySkuTile by multiTile<String, Price> { skus ->
  source<PricingService>().getPrices(skus.toList())
}

val PerKeyProductsTile by perKeyTile<String, Product> { productId ->
  source<ProductService>().getProducts(listOf(productId)).getValue(productId)
}

val ChunkedProductsTile by chunkedMultiTile<String, Product>(batchSize = 50) { ids ->
  source<ProductService>().getProducts(ids)
}
```

`multiTile` receives a set of uncached keys. Within one Mosaic, equal keys on the same MultiTile share in-flight work and cached results. When several calls have new keys pending before scheduled execution begins, Mosaic opportunistically combines those keys into one invocation. It does not deliberately delay ready work to collect more keys. A started batch is fixed; later new keys can form another batch, and batches may execute concurrently. Exact batch partitioning depends on scheduling and is not an API guarantee.

`chunkedMultiTile` splits the resulting coalesced invocation into lists and starts chunks concurrently; chunk size limits request size, not request rate or concurrency. `perKeyTile` fetches each key individually and concurrently. A map entry containing `null` is successful when the value type is nullable. Only an absent requested key fails with `NoSuchElementException`.

Each key retains its own terminal success or failure. A partial bulk map publishes present values and fails omitted keys individually. If a bulk provider throws, its unfinished keys fail. A `perKeyTile` failure affects only that key; a `chunkedMultiTile` failure affects only the keys in that physical chunk invocation. Successful siblings remain available to later callers without re-execution. Request cancellation cancels all unfinished work.

`compose(tile, keys)` throws if any requested key fails. Use `composeAsync` to await individual outcomes. These Deferreds represent Mosaic-owned shared work: cancel your own waiting coroutine to stop waiting, rather than cancelling the shared Deferred.

| Call                       | Return shape                                      |
| -------------------------- | ------------------------------------------------- |
| `compose(tile, keys)`      | `Map<K, V>` (suspends until values are available) |
| `composeAsync(tile, keys)` | `Map<K, Deferred<V>>`                             |
| `compose(tile, key)`       | `V` (suspends until available)                    |
| `composeAsync(tile, key)`  | `Deferred<V>`                                     |

Await a map entry with `pending.getValue(key).await()`, or all entries with `pending.mapValues { (_, value) -> value.await() }` in suspending code. The [LineItems example](/concepts/tiles/) preserves order by iterating `order.items`; do not rely on the result map's iteration order.

## Preserve domain order

A result map associates keys with values; it is not the ordering of your response. Iterate the original request or domain model to preserve order and repeated references. In the line-item example, `order.items` determines output order even when multiple items refer to the same product.

## Observe the actual calls

A shared key is fetched once in a Mosaic, but one invocation for all callers is not guaranteed. Consumers that discover keys after another batch has started can create later batches. External suspension and deeper dependencies affect which keys are pending together.

Test key completeness and reuse with a recording service fake. Avoid a test that requires an exact batch partition unless your test controls the scheduling needed to establish it. [Tracing](/guides/tracing/) records actual batch executions; [static graphs](/guides/analysis/) show dependencies, not batch boundaries.

[Choose a fetch strategy](/concepts/batching/) when your backend takes one key, all keys, or a bounded list.

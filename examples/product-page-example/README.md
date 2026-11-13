# Product-page BFF

One endpoint assembles an online store's product page from six downstream service
interfaces. The included local fixtures make it runnable without external services;
they are not real HTTP clients or a performance benchmark.

```sh
./gradlew -p examples :product-page-example:run
curl 'http://localhost:8081/products/headphones-123?customer=member'
```

The response contains `product` (details/images), `price`, `availability` (stock and
delivery date), `reviews`, and `recommendations` (product names and prices). Prices
are decimal strings to avoid representing money as binary floating point. Omit
`customer=member` to see the guest price. This parameter selects fixture data only;
a production application must derive identity from its authenticated principal.

Example member response (the demo delivery date is two days from today for the Phoenix member):

```json
{
  "product": {
    "id": "headphones-123",
    "name": "Wireless Headphones",
    "description": "Over-ear noise-cancelling headphones",
    "images": ["https://example.com/headphones.jpg"]
  },
  "price": { "amount": "179.99", "currency": "USD", "memberDiscountApplied": true },
  "availability": { "inStock": true, "estimatedDelivery": "2026-11-15" },
  "reviews": { "averageRating": 4.6, "count": 328 },
  "recommendations": [
    {
      "id": "case-456", "name": "Travel Case",
      "price": { "amount": "24.99", "currency": "USD", "memberDiscountApplied": false }
    },
    {
      "id": "stand-789", "name": "Headphone Stand",
      "price": { "amount": "19.99", "currency": "USD", "memberDiscountApplied": false }
    }
  ]
}
```

## Follow the response

Start with [`ProductPageTile`](src/main/kotlin/org/buildmosaic/example/product/ProductPageTiles.kt).
It starts each response section before awaiting results:

```kotlin
val ProductPageTile by singleTile {
  val product = composeAsync(ProductTile)
  val offer = composeAsync(ProductOffersTile, source(ProductId))
  val availability = composeAsync(AvailabilityTile)
  val reviews = composeAsync(ReviewsTile)
  val recommendations = composeAsync(RecommendationsTile)

  ProductPage(
    product = product.await(),
    price = offer.await().price,
    availability = availability.await(),
    reviews = reviews.await(),
    recommendations = recommendations.await(),
  )
}
```

```text
ProductPage
├── Product (validate requested product) ─── Products [catalog]
├── ProductOffers (requested ID) ──┬─────── Products [catalog, shared]
│                                 └─────── Prices ─── Customer [membership]
├── Availability [delivery] ───────────────────────── Customer [location]
├── Reviews [reviews]
└── Recommendations
    ├── RecommendationIds [recommendation service]
    │   ├── Product (shared with page)
    │   └── Customer (shared, location)
    └── ProductOffers (recommended IDs, shared assembly)
```

## Shared work

The graph lives in
[`ProductPageTiles.kt`](src/main/kotlin/org/buildmosaic/example/product/ProductPageTiles.kt),
root first. Both the main product and recommendation cards use `ProductOffersTile`
to join catalog details with member-aware prices. Pricing, delivery and
recommendations share `CustomerTile` for membership and location.

Recommendation selection returns ranked IDs; card assembly keeps that order and
requests their offers together. Repeated keys reuse the same catalog/pricing work.
Batching is opportunistic: later recommendation IDs need not join the main
product's backend call. Each HTTP request has its own Mosaic and cache.

## Services and errors

`Services.kt` provides six suspending service contracts and local fixtures.
`member` lives in Phoenix; `guest` in Seattle, where the travel case is out of
stock. Reviews, related products and recommendation ranking vary with the inputs.
Try `/products/case-456?customer=guest` too.

A missing requested product returns 404. Missing required data for a recommendation
or price returns 502. The endpoint requires all sections rather than returning a
partial page. Real service clients would own their transport timeouts and retry
policy.

## Tests

[`ProductTileTest.kt`](src/test/kotlin/org/buildmosaic/example/product/ProductTileTest.kt)
shows **mosaic-test** with substituted Tile dependencies and recording services:
location/membership forwarding, keyed offer assembly and ranked recommendations.
[`ProductPageTest.kt`](src/test/kotlin/org/buildmosaic/example/product/ProductPageTest.kt)
covers the HTTP contract and errors, location-sensitive results, and one
integration case exercising real keyed reuse and bulk lookup.

```sh
./gradlew -p examples :product-page-example:test :product-page-example:ktlintCheck
```

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
It starts the independent response sections before awaiting their results. The
primary product is awaited first, so its absence determines the page-level 404:

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

### Reuse at the data and composition layers

The page's Tile graph is in
[`ProductPageTiles.kt`](src/main/kotlin/org/buildmosaic/example/product/ProductPageTiles.kt),
starting with the root response and followed by its dependencies.

The main product and recommended cards both compose `ProductOffersTile`, which
joins catalog details with member-aware prices. Its catalog and pricing MultiTiles
share repeated keys. Product validation also reuses the same catalog result.

`RecommendationIdsTile` selects related products using the viewed product and the
customer's location. `RecommendationsTile` projects the shared offers into cards
in the selection service's ranked order.

Pricing, delivery and recommendation selection all compose `CustomerTile`,
sharing the same membership and location lookup within the request.

### Batching and request boundaries

Mosaic combines keys available before a pending batch starts. It does not wait to
collect a larger batch. Recommendation IDs are unknown until selection returns,
so main-product and recommendation lookups need not share one backend call.
`ProductOffersTile` requests each group of catalog and price keys together; their
physical backend batch boundaries remain opportunistic. Repeated keys share work
even when requested from a later branch.

`Application.kt` owns long-lived service clients and creates a new Mosaic for each
HTTP request. There is no cross-request cache. `Services.kt` separates the six
suspending service boundaries from composition; real clients belong there, with
transport timeouts and retry policy owned explicitly by those clients.

The fixtures exercise real input differences: `member` lives in Phoenix and
`guest` in Seattle. The travel case is not stocked in Seattle, recommendation
ranking prefers local stock, and reviews/related products vary by product ID.
Try `/products/case-456?customer=guest` as well as the headphones page. These are
small local fixtures, not a simulated distributed system or benchmark.

### Failure policy

The primary catalog result is awaited first. Only an absent **requested product** maps
to HTTP 404. A missing product referenced by a recommendation or a missing required
price maps to HTTP 502: the page exists, but a downstream lookup is inconsistent.
All sections remain required; other unexpected failures fail the response rather
than silently returning partial data.

## Testing the Tiles

[`ProductTileTest.kt`](src/test/kotlin/org/buildmosaic/example/product/ProductTileTest.kt)
uses **mosaic-test** to execute real Tiles with substituted Tile dependencies and
small recording services. It verifies the product/location/membership inputs and
key-based assembly, rather than booting HTTP for each composition assertion.
For example, the availability test substitutes `CustomerTile` but runs the real
`AvailabilityTile` against a recording delivery service.

[`ProductPageTest.kt`](src/test/kotlin/org/buildmosaic/example/product/ProductPageTest.kt)
keeps the real composition graph behind HTTP. It checks the wire response,
request isolation, shared lookups, recommendation order, location-sensitive
results and the page's 404/502 policy.

## Verify

```sh
./gradlew -p examples :product-page-example:test :product-page-example:ktlintCheck
```

Existing order examples are unchanged.

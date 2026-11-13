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

Example member response (the demo delivery date is three days from today):

```json
{
  "product": {
    "id": "headphones-123",
    "name": "Wireless Headphones",
    "description": "Over-ear noise-cancelling headphones",
    "images": ["https://example.com/headphones.jpg"]
  },
  "price": { "amount": "179.99", "currency": "USD", "memberDiscountApplied": true },
  "availability": { "inStock": true, "estimatedDelivery": "2026-11-16" },
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

Start in `ProductTiles.kt` at `ProductPageTile`. It starts the independent response
sections before awaiting their results:

```text
ProductPage
├── Product ────────────────────────── Catalog (by product ID)
├── Price ─────── Prices (by ID) ──┐
├── Availability ─────────────────┤── Customer (membership/location)
│                 └── Delivery   │
├── Reviews ───────── Review      │
└── Recommendations ──────────────┘
    ├── Product (shared with the page)
    ├── Recommendation service → related product IDs
    ├── Catalog (bulk product details)
    └── Prices (bulk member-aware prices)
```

Pricing, delivery, and recommendations independently compose `CustomerTile`. They
share its in-flight work and result inside one request. The main product and
recommendations similarly share `ProductTile`. `ProductsTile` and `PricesTile`
use MultiTile to request the recommendation IDs in bulk and share repeated keys.

Mosaic combines keys available before a pending batch starts. It does not wait to
collect a larger batch: the main product and recommendation lookups need not share
one backend call. Recommendations cannot even request their IDs until the
recommendation service returns them.

`Application.kt` owns long-lived service clients and creates a new Mosaic for each
HTTP request. There is no cross-request cache. `Services.kt` separates the six
suspending service boundaries from the composition; real clients belong there,
with transport timeouts and any retry policy owned explicitly by those clients.
All sections are required in this small example. A missing catalog product maps
to HTTP 404; other service failures fail the response rather than silently
returning partial data.

## Verify

```sh
./gradlew -p examples :product-page-example:test :product-page-example:ktlintCheck
```

Tests cover the response, shared customer/product calls, bulk recommendation
lookups, independent branch progress, per-request price isolation, and missing
product recovery. Existing order examples are unchanged.

package org.buildmosaic.example.product

import java.time.LocalDate

fun interface CatalogService {
  suspend fun getProducts(ids: Set<String>): Map<String, Product>
}

fun interface CustomerService {
  suspend fun getCustomer(id: String): Customer
}

fun interface PricingService {
  suspend fun getPrices(
    ids: Set<String>,
    member: Boolean,
  ): Map<String, Price>
}

fun interface DeliveryService {
  suspend fun getAvailability(
    productId: String,
    postalCode: String,
  ): Availability
}

fun interface ReviewService {
  suspend fun getReviews(productId: String): Reviews
}

fun interface RecommendationService {
  suspend fun getRecommendations(
    product: Product,
    postalCode: String,
  ): List<String>
}

/** Local fixtures stand in for suspending downstream clients; no external services are required. */
fun demoServices(): ProductServices {
  val products =
    listOf(
      Product(
        id = "headphones-123",
        name = "Wireless Headphones",
        description = "Over-ear noise-cancelling headphones",
        images = listOf("https://example.com/headphones.jpg"),
      ),
      Product("case-456", "Travel Case", "Protective carrying case", emptyList()),
      Product("stand-789", "Headphone Stand", "Desktop headphone stand", emptyList()),
    ).associateBy { it.id }
  val customers =
    mapOf(
      "member" to Customer(member = true, postalCode = "85001"),
      "guest" to Customer(member = false, postalCode = "98101"),
    )
  val amounts = mapOf("headphones-123" to "199.99", "case-456" to "24.99", "stand-789" to "19.99")
  val reviews =
    mapOf(
      "headphones-123" to Reviews(4.6, 328),
      "case-456" to Reviews(4.3, 72),
      "stand-789" to Reviews(4.8, 41),
    )
  val nearbyStock =
    mapOf(
      "85001" to setOf("headphones-123", "case-456", "stand-789"),
      "98101" to setOf("headphones-123", "stand-789"),
    )
  return ProductServices(
    catalog = CatalogService { ids -> ids.associateWith { products[it] ?: throw ProductNotFound(it) } },
    customers = CustomerService { id -> customers.getValue(id) },
    pricing =
      PricingService { ids, member ->
        ids.associateWith { id ->
          val amount = amounts[id] ?: throw ProductNotFound(id)
          val discounted = member && id == "headphones-123"
          Price(
            amount = if (discounted) "179.99" else amount,
            memberDiscountApplied = discounted,
          )
        }
      },
    delivery =
      DeliveryService { productId, postalCode ->
        if (productId !in products) throw ProductNotFound(productId)
        val inStock = productId in nearbyStock.getValue(postalCode)
        val shippingDays = if (postalCode == "85001") 2L else 5L
        Availability(
          inStock = inStock,
          estimatedDelivery = if (inStock) LocalDate.now().plusDays(shippingDays).toString() else null,
        )
      },
    reviews = ReviewService { id -> reviews[id] ?: throw ProductNotFound(id) },
    recommendations =
      RecommendationService { product, postalCode ->
        val related =
          when (product.id) {
            "headphones-123" -> listOf("case-456", "stand-789")
            "case-456" -> listOf("headphones-123", "stand-789")
            "stand-789" -> listOf("headphones-123", "case-456")
            else -> throw ProductNotFound(product.id)
          }
        related.sortedByDescending { it in nearbyStock.getValue(postalCode) }
      },
  )
}

data class ProductServices(
  val catalog: CatalogService,
  val customers: CustomerService,
  val pricing: PricingService,
  val delivery: DeliveryService,
  val reviews: ReviewService,
  val recommendations: RecommendationService,
)

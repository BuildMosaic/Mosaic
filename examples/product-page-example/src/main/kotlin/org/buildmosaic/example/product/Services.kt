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
fun demoServices() =
  ProductServices(
    catalog =
      CatalogService { ids ->
        val products =
          listOf(
            Product(
              "headphones-123",
              "Wireless Headphones",
              "Over-ear noise-cancelling headphones",
              listOf("https://example.com/headphones.jpg"),
            ),
            Product("case-456", "Travel Case", "Protective carrying case", emptyList()),
            Product("stand-789", "Headphone Stand", "Desktop headphone stand", emptyList()),
          ).associateBy { it.id }
        ids.associateWith { products[it] ?: throw ProductNotFound(it) }
      },
    customers = CustomerService { id -> Customer(member = id == "member", postalCode = "85001") },
    pricing =
      PricingService { ids, member ->
        val amounts = mapOf("headphones-123" to "199.99", "case-456" to "24.99", "stand-789" to "19.99")
        ids.associateWith { id ->
          val discounted = member && id == "headphones-123"
          Price(
            if (discounted) "179.99" else (amounts[id] ?: throw ProductNotFound(id)),
            memberDiscountApplied = discounted,
          )
        }
      },
    delivery = DeliveryService { _, _ -> Availability(true, LocalDate.now().plusDays(3).toString()) },
    reviews = ReviewService { Reviews(4.6, 328) },
    recommendations = RecommendationService { _, _ -> listOf("case-456", "stand-789") },
  )

data class ProductServices(
  val catalog: CatalogService,
  val customers: CustomerService,
  val pricing: PricingService,
  val delivery: DeliveryService,
  val reviews: ReviewService,
  val recommendations: RecommendationService,
)

package org.buildmosaic.example.product

import kotlinx.serialization.Serializable

@Serializable
data class Product(val id: String, val name: String, val description: String, val images: List<String>)

@Serializable
data class Price(val amount: String, val currency: String = "USD", val memberDiscountApplied: Boolean)

@Serializable
data class Availability(val inStock: Boolean, val estimatedDelivery: String?)

@Serializable
data class Reviews(val averageRating: Double, val count: Int)

@Serializable
data class Recommendation(val id: String, val name: String, val price: Price)

@Serializable
data class ProductPage(
  val product: Product,
  val price: Price,
  val availability: Availability,
  val reviews: Reviews,
  val recommendations: List<Recommendation>,
)

data class Customer(val member: Boolean, val postalCode: String)

class ProductNotFound(val id: String) : RuntimeException("Product not found: $id")

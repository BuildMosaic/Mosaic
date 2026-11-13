package org.buildmosaic.example.product

import org.buildmosaic.core.injection.CanvasKey
import org.buildmosaic.core.multiTile
import org.buildmosaic.core.singleTile
import org.buildmosaic.core.source

val ProductId = CanvasKey(String::class, "productId")
val CustomerId = CanvasKey(String::class, "customerId")

val CustomerTile by singleTile { source<CustomerService>().getCustomer(source(CustomerId)) }

val ProductsTile by multiTile { ids: Set<String> -> source<CatalogService>().getProducts(ids) }

val PricesTile by multiTile { ids: Set<String> ->
  val customer = compose(CustomerTile)
  source<PricingService>().getPrices(ids, customer.member)
}

val ProductTile by singleTile { compose(ProductsTile, source(ProductId)) }
val PriceTile by singleTile { compose(PricesTile, source(ProductId)) }

val AvailabilityTile by singleTile {
  val customer = compose(CustomerTile)
  source<DeliveryService>().getAvailability(source(ProductId), customer.postalCode)
}

val ReviewsTile by singleTile { source<ReviewService>().getReviews(source(ProductId)) }

val RecommendationsTile by singleTile {
  val product = composeAsync(ProductTile)
  val customer = composeAsync(CustomerTile)
  val ids = source<RecommendationService>().getRecommendations(product.await(), customer.await().postalCode)
  val products = composeAsync(ProductsTile, ids)
  val prices = composeAsync(PricesTile, ids)
  ids.map { id -> Recommendation(id, products.getValue(id).await().name, prices.getValue(id).await()) }
}

val ProductPageTile by singleTile {
  val product = composeAsync(ProductTile)
  val price = composeAsync(PriceTile)
  val availability = composeAsync(AvailabilityTile)
  val reviews = composeAsync(ReviewsTile)
  val recommendations = composeAsync(RecommendationsTile)
  ProductPage(product.await(), price.await(), availability.await(), reviews.await(), recommendations.await())
}

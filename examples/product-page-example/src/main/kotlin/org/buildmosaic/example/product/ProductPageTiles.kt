package org.buildmosaic.example.product

import org.buildmosaic.core.injection.CanvasKey
import org.buildmosaic.core.multiTile
import org.buildmosaic.core.singleTile
import org.buildmosaic.core.source

val ProductId = CanvasKey(String::class, "productId")
val CustomerId = CanvasKey(String::class, "customerId")

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

val CustomerTile by singleTile {
  source<CustomerService>().getCustomer(source(CustomerId))
}

val ProductsTile by multiTile { ids: Set<String> ->
  source<CatalogService>().getProducts(ids)
}

val ProductTile by singleTile {
  val id = source(ProductId)
  compose(ProductsTile, id) ?: throw ProductNotFound(id)
}

val PricesTile by multiTile { ids: Set<String> ->
  val customer = compose(CustomerTile)
  source<PricingService>().getPrices(ids, customer.member)
}

val ProductOffersTile by multiTile { ids: Set<String> ->
  val products = composeAsync(ProductsTile, ids)
  val prices = composeAsync(PricesTile, ids)
  ids.associateWith { id ->
    ProductOffer(
      product = products.getValue(id).await() ?: throw ProductLookupFailed(id),
      price = prices.getValue(id).await(),
    )
  }
}

val AvailabilityTile by singleTile {
  val customer = compose(CustomerTile)
  source<DeliveryService>().getAvailability(
    productId = source(ProductId),
    postalCode = customer.postalCode,
  )
}

val ReviewsTile by singleTile {
  source<ReviewService>().getReviews(source(ProductId))
}

val RecommendationIdsTile by singleTile {
  val product = composeAsync(ProductTile)
  val customer = composeAsync(CustomerTile)
  source<RecommendationService>().getRecommendations(
    product = product.await(),
    postalCode = customer.await().postalCode,
  )
}

val RecommendationsTile by singleTile {
  val ids = compose(RecommendationIdsTile)
  val offers = composeAsync(ProductOffersTile, ids)
  ids.map { id ->
    val offer = offers.getValue(id).await()
    Recommendation(
      id = offer.product.id,
      name = offer.product.name,
      price = offer.price,
    )
  }
}

package org.buildmosaic.example.product

import org.buildmosaic.core.singleTile
import org.buildmosaic.core.source

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

val ProductPageTile by singleTile {
  val product = composeAsync(ProductTile)
  val offer = composeAsync(ProductOffersTile, source(ProductId))
  val availability = composeAsync(AvailabilityTile)
  val reviews = composeAsync(ReviewsTile)
  val recommendations = composeAsync(RecommendationsTile)

  ProductPage(
    // Await the primary product first so only its absence determines a page-level 404.
    product = product.await(),
    price = offer.await().price,
    availability = availability.await(),
    reviews = reviews.await(),
    recommendations = recommendations.await(),
  )
}

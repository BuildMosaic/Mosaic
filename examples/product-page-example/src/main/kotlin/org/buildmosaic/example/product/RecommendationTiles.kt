package org.buildmosaic.example.product

import org.buildmosaic.core.singleTile
import org.buildmosaic.core.source

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

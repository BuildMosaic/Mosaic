package org.buildmosaic.example.product

import org.buildmosaic.core.injection.CanvasKey
import org.buildmosaic.core.multiTile
import org.buildmosaic.core.singleTile
import org.buildmosaic.core.source

val ProductId = CanvasKey(String::class, "productId")
val CustomerId = CanvasKey(String::class, "customerId")

val CustomerTile by singleTile {
  source<CustomerService>().getCustomer(source(CustomerId))
}

val ProductsTile by multiTile { ids: Set<String> ->
  source<CatalogService>().getProducts(ids)
}

/** Only absence of the requested page's product means that the page is not found. */
val ProductTile by singleTile {
  val id = source(ProductId)
  try {
    compose(ProductsTile, id)
  } catch (missing: ProductNotFound) {
    if (missing.id == id) throw PrimaryProductNotFound(id)
    throw missing
  }
}

val PricesTile by multiTile { ids: Set<String> ->
  val customer = compose(CustomerTile)
  source<PricingService>().getPrices(ids, customer.member)
}

/** The page and recommendation cards reuse the same member-aware product offer. */
val ProductOffersTile by multiTile { ids: Set<String> ->
  val products = composeAsync(ProductsTile, ids)
  val prices = composeAsync(PricesTile, ids)
  ids.associateWith { id ->
    ProductOffer(
      product = products.getValue(id).await(),
      price = prices.getValue(id).await(),
    )
  }
}

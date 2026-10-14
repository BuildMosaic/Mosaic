package org.buildmosaic.library.tile

import org.buildmosaic.core.singleTile
import org.buildmosaic.library.model.LineItemDetail

val LineItemsTile by
  singleTile {
    val order = compose(OrderTile)

    val productIds = order.items.map { it.productId }
    val skus = order.items.map { it.sku }

    val products = composeAsync(ProductsByIdTile, productIds)
    val pricing = composeAsync(PricingBySkuTile, skus)

    order.items.map { item ->
      val product = products.getValue(item.productId).await()
      val price = pricing.getValue(item.sku).await()
      LineItemDetail(product, price, item.quantity)
    }
  }

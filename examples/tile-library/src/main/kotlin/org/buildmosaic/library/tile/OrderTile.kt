package org.buildmosaic.library.tile

import org.buildmosaic.core.singleTile
import org.buildmosaic.core.source
import org.buildmosaic.library.OrderKey
import org.buildmosaic.library.exception.OrderNotFoundException
import org.buildmosaic.library.service.OrderService

val OrderTile by
  singleTile {
    val orderId = source(OrderKey)
    try {
      source<OrderService>().getOrder(orderId)
    } catch (e: NoSuchElementException) {
      throw OrderNotFoundException(orderId, e)
    }
  }

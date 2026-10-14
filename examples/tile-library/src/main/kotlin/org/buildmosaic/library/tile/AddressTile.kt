package org.buildmosaic.library.tile

import org.buildmosaic.core.singleTile
import org.buildmosaic.core.source
import org.buildmosaic.library.OrderKey
import org.buildmosaic.library.service.AddressService

val AddressTile by
  singleTile {
    val orderId = source(OrderKey)
    source<AddressService>().getAddress(orderId)
  }

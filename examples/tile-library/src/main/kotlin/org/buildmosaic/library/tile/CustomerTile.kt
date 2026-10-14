package org.buildmosaic.library.tile

import org.buildmosaic.core.singleTile
import org.buildmosaic.core.source
import org.buildmosaic.library.service.CustomerService

val CustomerTile by
  singleTile {
    val order = compose(OrderTile)
    source<CustomerService>().getCustomer(order.customerId)
  }

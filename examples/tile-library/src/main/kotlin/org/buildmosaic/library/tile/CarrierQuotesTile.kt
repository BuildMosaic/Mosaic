package org.buildmosaic.library.tile

import org.buildmosaic.core.multiTile
import org.buildmosaic.core.source
import org.buildmosaic.library.service.CarrierService

val CarrierQuotesTile by
  multiTile { keys ->
    val address = compose(AddressTile)
    source<CarrierService>().getQuotes(address, keys.toList())
  }

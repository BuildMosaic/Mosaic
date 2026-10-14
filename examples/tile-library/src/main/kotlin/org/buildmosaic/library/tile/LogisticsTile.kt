package org.buildmosaic.library.tile

import org.buildmosaic.core.singleTile
import org.buildmosaic.core.source
import org.buildmosaic.library.model.Logistics
import org.buildmosaic.library.service.CarrierService

val LogisticsTile by
  singleTile {
    val addressDeferred = composeAsync(AddressTile)
    val quotes = compose(CarrierQuotesTile, source<CarrierService>().getAvailableCarriers())

    Logistics(addressDeferred.await(), quotes)
  }

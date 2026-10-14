package org.buildmosaic.library.tile

import org.buildmosaic.core.multiTile
import org.buildmosaic.core.source
import org.buildmosaic.library.service.PricingService

val PricingBySkuTile by
  multiTile { keys ->
    source<PricingService>().getPrices(keys.toList())
  }

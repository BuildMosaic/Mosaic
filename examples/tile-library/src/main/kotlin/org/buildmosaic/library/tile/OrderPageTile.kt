package org.buildmosaic.library.tile

import org.buildmosaic.core.singleTile
import org.buildmosaic.library.model.OrderPage

val OrderPageTile by
  singleTile {
    val summaryTile = composeAsync(OrderSummaryTile)
    val logisticsTile = composeAsync(LogisticsTile)

    OrderPage(summaryTile.await(), logisticsTile.await())
  }

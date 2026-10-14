package org.buildmosaic.library.tile

import org.buildmosaic.core.singleTile

/**
 * Tile that calculates the total cost of an order by summing the line item prices.
 */
val OrderTotalTile by
  singleTile {
    val lineItemsTile = compose(LineItemsTile)

    lineItemsTile.sumOf { it.price.amount * it.quantity }
  }

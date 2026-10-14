package org.buildmosaic.spring.orders.web

import org.buildmosaic.core.injection.Canvas
import org.buildmosaic.core.injection.withMosaic
import org.buildmosaic.library.OrderKey
import org.buildmosaic.library.model.OrderPage
import org.buildmosaic.library.tile.OrderPageTile
import org.buildmosaic.library.tile.OrderTotalTile
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/orders")
class OrderController(private val canvas: Canvas) {
  @GetMapping("/{id}")
  suspend fun getOrder(
    @PathVariable("id") id: String,
  ): OrderPage =
    canvas.withLayer {
      instance(OrderKey, id)
    }.withMosaic { compose(OrderPageTile) }

  @GetMapping("/{id}/total")
  suspend fun getOrderTotal(
    @PathVariable("id") id: String,
  ): Double =
    canvas.withLayer {
      instance(OrderKey, id)
    }.withMosaic { compose(OrderTotalTile) }
}

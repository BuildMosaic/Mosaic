package org.buildmosaic.library.tile

import kotlinx.coroutines.test.runTest
import org.buildmosaic.library.OrderKey
import org.buildmosaic.library.exception.OrderNotFoundException
import org.buildmosaic.library.model.Order
import org.buildmosaic.library.model.OrderLineItem
import org.buildmosaic.library.service.OrderService
import org.buildmosaic.test.TestMosaicBuilder
import kotlin.test.Test

class OrderTileTest {
  @Test
  fun `order tile returns order from request`() =
    runTest {
      val expected =
        Order(
          id = "order-1",
          customerId = "customer-1",
          items =
            listOf(
              OrderLineItem("product-1", "sku-1", 2),
              OrderLineItem("product-2", "sku-2", 1),
            ),
        )
      val testMosaic =
        TestMosaicBuilder(this)
          .withCanvasSource(OrderService())
          .withCanvasSource(OrderKey, "order-1")
          .build()
      testMosaic.assertEquals(OrderTile, expected)
    }

  @Test
  fun `order tile throws custom exception when missing`() =
    runTest {
      val testMosaic =
        TestMosaicBuilder(this)
          .withCanvasSource(OrderService())
          .withCanvasSource(OrderKey, "missing")
          .build()
      testMosaic.assertThrows(OrderTile, OrderNotFoundException::class)
    }
}

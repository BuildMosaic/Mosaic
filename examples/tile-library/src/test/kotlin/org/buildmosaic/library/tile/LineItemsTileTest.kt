package org.buildmosaic.library.tile

import kotlinx.coroutines.test.runTest
import org.buildmosaic.library.model.LineItemDetail
import org.buildmosaic.library.model.Order
import org.buildmosaic.library.model.OrderLineItem
import org.buildmosaic.library.model.Price
import org.buildmosaic.library.model.Product
import org.buildmosaic.test.mosaicBuilder
import kotlin.test.Test

class LineItemsTileTest {
  @Test
  fun `line items tile combines other tiles`() =
    runTest {
      val order =
        Order(
          id = "order-1",
          customerId = "customer-1",
          items = listOf(OrderLineItem("product-1", "sku-1", 2)),
        )
      val products = mapOf("product-1" to Product("product-1", "Coffee Mug"))
      val prices = mapOf("sku-1" to Price("sku-1", 12.99))
      val expected =
        listOf(
          LineItemDetail(
            product = products.getValue("product-1"),
            price = prices.getValue("sku-1"),
            quantity = 2,
          ),
        )

      mosaicBuilder()
        .withMockTile(OrderTile, order)
        .withMockTile(ProductsByIdTile, products)
        .withMockTile(PricingBySkuTile, prices)
        .withMosaic {
          assertEquals(LineItemsTile, expected)
        }
    }

  @Test
  fun `line items tile fails when products tile fails`() =
    runTest {
      val order =
        Order(
          id = "order-1",
          customerId = "customer-1",
          items = listOf(OrderLineItem("product-1", "sku-1", 1)),
        )

      mosaicBuilder()
        .withMockTile(OrderTile, order)
        .withFailedTile(ProductsByIdTile, RuntimeException("boom"))
        .withMosaic {
          assertThrows(LineItemsTile, RuntimeException::class)
        }
    }
}

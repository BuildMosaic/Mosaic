package org.buildmosaic.library.tile

import kotlinx.coroutines.test.runTest
import org.buildmosaic.library.model.Customer
import org.buildmosaic.library.model.LineItemDetail
import org.buildmosaic.library.model.Order
import org.buildmosaic.library.model.OrderSummary
import org.buildmosaic.library.model.Price
import org.buildmosaic.library.model.Product
import org.buildmosaic.test.mosaicBuilder
import kotlin.test.Test

class OrderSummaryTileTest {
  @Test
  fun `order summary tile aggregates data`() =
    runTest {
      val order = Order("order-1", "customer-1", emptyList())
      val customer = Customer("customer-1", "Jane Doe")
      val lineItems =
        listOf(LineItemDetail(Product("product-1", "Coffee Mug"), Price("sku-1", 12.99), 2))
      val expected = OrderSummary(order, customer, lineItems)

      mosaicBuilder()
        .withMockTile(OrderTile, order)
        .withMockTile(CustomerTile, customer)
        .withMockTile(LineItemsTile, lineItems)
        .withMosaic {
          assertEquals(OrderSummaryTile, expected)
        }
    }

  @Test
  fun `order summary tile fails when order tile fails`() =
    runTest {
      mosaicBuilder()
        .withFailedTile(OrderTile, RuntimeException("boom"))
        .withMosaic {
          assertThrows(OrderSummaryTile, RuntimeException::class)
        }
    }
}

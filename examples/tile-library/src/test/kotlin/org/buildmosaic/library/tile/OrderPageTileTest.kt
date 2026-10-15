package org.buildmosaic.library.tile

import kotlinx.coroutines.test.runTest
import org.buildmosaic.library.model.Address
import org.buildmosaic.library.model.Customer
import org.buildmosaic.library.model.Logistics
import org.buildmosaic.library.model.Order
import org.buildmosaic.library.model.OrderPage
import org.buildmosaic.library.model.OrderSummary
import org.buildmosaic.test.mosaicBuilder
import kotlin.test.Test

class OrderPageTileTest {
  @Test
  fun `order page tile merges summary and logistics`() =
    runTest {
      val summary =
        OrderSummary(
          order = Order("order-1", "customer-1", emptyList()),
          customer = Customer("customer-1", "Jane Doe"),
          lineItems = emptyList(),
        )
      val logistics = Logistics(Address("123 Main St", "Springfield"), emptyMap())
      val expected = OrderPage(summary, logistics)

      mosaicBuilder()
        .withMockTile(OrderSummaryTile, summary)
        .withMockTile(LogisticsTile, logistics)
        .withMosaic {
          assertEquals(OrderPageTile, expected)
        }
    }

  @Test
  fun `order page tile fails when logistics tile fails`() =
    runTest {
      val summary =
        OrderSummary(
          order = Order("order-1", "customer-1", emptyList()),
          customer = Customer("customer-1", "Jane Doe"),
          lineItems = emptyList(),
        )

      mosaicBuilder()
        .withMockTile(OrderSummaryTile, summary)
        .withFailedTile(LogisticsTile, RuntimeException("boom"))
        .withMosaic {
          assertThrows(OrderPageTile, RuntimeException::class)
        }
    }
}

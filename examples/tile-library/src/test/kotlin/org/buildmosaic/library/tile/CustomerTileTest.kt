package org.buildmosaic.library.tile

import kotlinx.coroutines.test.runTest
import org.buildmosaic.library.model.Customer
import org.buildmosaic.library.model.Order
import org.buildmosaic.library.service.CustomerService
import org.buildmosaic.test.mosaicBuilder
import kotlin.test.Test

class CustomerTileTest {
  @Test
  fun `customer tile uses order tile`() =
    runTest {
      val order = Order("order-1", "customer-1", emptyList())
      val expected = Customer("customer-1", "Jane Doe")

      mosaicBuilder()
        .withCanvasSource(CustomerService())
        .withMockTile(OrderTile, order)
        .withMosaic {
          assertEquals(CustomerTile, expected)
        }
    }

  @Test
  fun `customer tile fails when order tile fails`() =
    runTest {
      mosaicBuilder()
        .withCanvasSource(CustomerService())
        .withFailedTile(OrderTile, RuntimeException("boom"))
        .withMosaic {
          assertThrows(CustomerTile, RuntimeException::class)
        }
    }
}

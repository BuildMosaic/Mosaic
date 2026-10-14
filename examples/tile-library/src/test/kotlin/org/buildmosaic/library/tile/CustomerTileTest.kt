package org.buildmosaic.library.tile

import kotlinx.coroutines.test.runTest
import org.buildmosaic.library.model.Customer
import org.buildmosaic.library.model.Order
import org.buildmosaic.library.service.CustomerService
import org.buildmosaic.test.TestMosaicBuilder
import kotlin.test.Test

class CustomerTileTest {
  @Test
  fun `customer tile uses order tile`() =
    runTest {
      val order = Order("order-1", "customer-1", emptyList())
      val expected = Customer("customer-1", "Jane Doe")
      val testMosaic =
        TestMosaicBuilder(this)
          .withCanvasSource(CustomerService())
          .withMockTile(OrderTile, order)
          .build()
      testMosaic.assertEquals(CustomerTile, expected)
    }

  @Test
  fun `customer tile fails when order tile fails`() =
    runTest {
      val testMosaic =
        TestMosaicBuilder(this)
          .withCanvasSource(CustomerService())
          .withFailedTile(OrderTile, RuntimeException("boom"))
          .build()
      testMosaic.assertThrows(CustomerTile, RuntimeException::class)
    }
}

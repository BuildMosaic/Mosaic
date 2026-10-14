package org.buildmosaic.library.tile

import kotlinx.coroutines.test.runTest
import org.buildmosaic.library.OrderKey
import org.buildmosaic.library.model.Address
import org.buildmosaic.library.service.AddressService
import org.buildmosaic.test.TestMosaicBuilder
import kotlin.test.Test

class AddressTileTest {
  @Test
  fun `address tile uses request id`() =
    runTest {
      val testMosaic =
        TestMosaicBuilder(this)
          .withCanvasSource(AddressService())
          .withCanvasSource(OrderKey, "order-1")
          .build()
      val expected = Address("123 Main St", "Springfield")
      testMosaic.assertEquals(AddressTile, expected)
    }

  @Test
  fun `address tile propagates failures`() =
    runTest {
      val testMosaic =
        TestMosaicBuilder(this)
          .withFailedTile(AddressTile, RuntimeException("boom"))
          .build()
      testMosaic.assertThrows(AddressTile, RuntimeException::class)
    }
}

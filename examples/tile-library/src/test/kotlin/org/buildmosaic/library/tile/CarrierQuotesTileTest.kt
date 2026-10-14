package org.buildmosaic.library.tile

import kotlinx.coroutines.test.runTest
import org.buildmosaic.library.model.Address
import org.buildmosaic.library.model.Quote
import org.buildmosaic.library.service.CarrierService
import org.buildmosaic.test.TestMosaicBuilder
import kotlin.test.Test

class CarrierQuotesTileTest {
  @Test
  fun `carrier quotes tile uses address tile`() =
    runTest {
      val address = Address("123 Main St", "Springfield")
      val carriers = listOf("UPS", "FEDEX")
      val quotes =
        mapOf(
          "UPS" to Quote("UPS", 5.99),
          "FEDEX" to Quote("FEDEX", 7.49),
        )
      val testMosaic =
        TestMosaicBuilder(this)
          .withCanvasSource(CarrierService())
          .withMockTile(AddressTile, address)
          .build()
      testMosaic.assertEquals(CarrierQuotesTile, carriers, quotes)
    }

  @Test
  fun `carrier quotes tile fails when address fails`() =
    runTest {
      val carriers = listOf("UPS", "FEDEX")
      val testMosaic =
        TestMosaicBuilder(this)
          .withFailedTile(AddressTile, RuntimeException("boom"))
          .build()
      testMosaic.assertThrows(CarrierQuotesTile, carriers, RuntimeException::class)
    }
}

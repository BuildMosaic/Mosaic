package org.buildmosaic.library.tile

import kotlinx.coroutines.test.runTest
import org.buildmosaic.library.model.Address
import org.buildmosaic.library.model.Logistics
import org.buildmosaic.library.model.Quote
import org.buildmosaic.library.service.CarrierService
import org.buildmosaic.test.mosaicBuilder
import kotlin.test.Test

class LogisticsTileTest {
  @Test
  fun `logistics tile combines address and quotes`() =
    runTest {
      val address = Address("123 Main St", "Springfield")
      val quotes =
        mapOf(
          "UPS" to Quote("UPS", 5.99),
          "FEDEX" to Quote("FEDEX", 7.49),
          "DHL" to Quote("DHL", 6.49),
        )
      val expected = Logistics(address, quotes)

      mosaicBuilder()
        .withCanvasSource(CarrierService())
        .withMockTile(AddressTile, address)
        .withMockTile(CarrierQuotesTile, quotes)
        .withMosaic {
          assertEquals(LogisticsTile, expected)
        }
    }

  @Test
  fun `logistics tile fails when quotes tile fails`() =
    runTest {
      val address = Address("123 Main St", "Springfield")

      mosaicBuilder()
        .withCanvasSource(CarrierService())
        .withMockTile(AddressTile, address)
        .withFailedTile(CarrierQuotesTile, RuntimeException("boom"))
        .withMosaic {
          assertThrows(LogisticsTile, RuntimeException::class)
        }
    }
}

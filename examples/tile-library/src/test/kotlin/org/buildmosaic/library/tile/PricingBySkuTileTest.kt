package org.buildmosaic.library.tile

import kotlinx.coroutines.test.runTest
import org.buildmosaic.library.model.Price
import org.buildmosaic.library.service.PricingService
import org.buildmosaic.test.TestMosaicBuilder
import kotlin.test.Test

class PricingBySkuTileTest {
  @Test
  fun `pricing tile fetches prices`() =
    runTest {
      val keys = listOf("sku-1", "sku-2")
      val expected =
        mapOf(
          "sku-1" to Price("sku-1", 12.99),
          "sku-2" to Price("sku-2", 29.99),
        )
      val testMosaic = TestMosaicBuilder(this).withCanvasSource(PricingService()).build()
      testMosaic.assertEquals(PricingBySkuTile, keys, expected)
    }

  @Test
  fun `pricing tile propagates failures`() =
    runTest {
      val keys = listOf("sku-1")
      val testMosaic =
        TestMosaicBuilder(this)
          .withFailedTile(PricingBySkuTile, RuntimeException("boom"))
          .build()
      testMosaic.assertThrows(PricingBySkuTile, keys, RuntimeException::class)
    }
}

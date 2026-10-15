package org.buildmosaic.library.tile

import kotlinx.coroutines.test.runTest
import org.buildmosaic.library.model.LineItemDetail
import org.buildmosaic.library.model.Price
import org.buildmosaic.library.model.Product
import org.buildmosaic.test.mosaicBuilder
import kotlin.test.Test

class OrderTotalTileTest {
  @Test
  fun `order total tile sums line item prices`() =
    runTest {
      val lineItems =
        listOf(
          LineItemDetail(Product("product-1", "Coffee Mug"), Price("sku-1", 12.99), 2),
          LineItemDetail(Product("product-2", "Tea Kettle"), Price("sku-2", 29.99), 1),
        )
      val expected = 55.97

      mosaicBuilder()
        .withMockTile(LineItemsTile, lineItems)
        .withMosaic {
          assertEquals(OrderTotalTile, expected)
        }
    }

  @Test
  fun `order total tile fails when line items fail`() =
    runTest {
      mosaicBuilder()
        .withFailedTile(LineItemsTile, RuntimeException("boom"))
        .withMosaic {
          assertThrows(OrderTotalTile, RuntimeException::class)
        }
    }
}

package org.buildmosaic.library.tile

import kotlinx.coroutines.test.runTest
import org.buildmosaic.library.model.Product
import org.buildmosaic.library.service.ProductService
import org.buildmosaic.test.mosaicBuilder
import kotlin.test.Test

class ProductsByIdTileTest {
  @Test
  fun `products tile fetches products`() =
    runTest {
      val keys = listOf("product-1", "product-2")
      val expected =
        mapOf(
          "product-1" to Product("product-1", "Coffee Mug"),
          "product-2" to Product("product-2", "Tea Kettle"),
        )
      mosaicBuilder().withCanvasSource(ProductService()).withMosaic {
        assertEquals(ProductsByIdTile, keys, expected)
      }
    }
}

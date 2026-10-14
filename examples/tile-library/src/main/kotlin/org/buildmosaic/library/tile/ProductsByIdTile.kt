package org.buildmosaic.library.tile

import org.buildmosaic.core.multiTile
import org.buildmosaic.core.source
import org.buildmosaic.library.service.ProductService

val ProductsByIdTile by
  multiTile { keys ->
    source<ProductService>().getProducts(keys.toList())
  }

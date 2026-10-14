package org.buildmosaic.micronaut.orders

import io.micronaut.context.annotation.Factory
import io.micronaut.http.HttpResponse
import io.micronaut.http.HttpStatus
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Error
import io.micronaut.http.annotation.Get
import io.micronaut.http.annotation.PathVariable
import io.micronaut.runtime.Micronaut
import jakarta.inject.Singleton
import kotlinx.coroutines.runBlocking
import org.buildmosaic.core.injection.Canvas
import org.buildmosaic.core.injection.canvas
import org.buildmosaic.core.injection.withMosaic
import org.buildmosaic.library.OrderKey
import org.buildmosaic.library.exception.OrderNotFoundException
import org.buildmosaic.library.model.OrderPage
import org.buildmosaic.library.service.AddressService
import org.buildmosaic.library.service.CarrierService
import org.buildmosaic.library.service.CustomerService
import org.buildmosaic.library.service.OrderService
import org.buildmosaic.library.service.PricingService
import org.buildmosaic.library.service.ProductService
import org.buildmosaic.library.tile.OrderPageTile
import org.buildmosaic.library.tile.OrderTotalTile

fun main(args: Array<String>) {
  Micronaut.run(MicronautExampleApplication::class.java, *args)
}

class MicronautExampleApplication

@Factory
class MosaicConfiguration {
  @Singleton
  fun orderService(): OrderService = OrderService()

  @Singleton
  fun customerService(): CustomerService = CustomerService()

  @Singleton
  fun productService(): ProductService = ProductService()

  @Singleton
  fun pricingService(): PricingService = PricingService()

  @Singleton
  fun addressService(): AddressService = AddressService()

  @Singleton
  fun carrierService(): CarrierService = CarrierService()

  // Micronaut invokes factories synchronously; bridge only during startup.
  @Singleton
  fun mosaicCanvas(
    orderService: OrderService,
    customerService: CustomerService,
    productService: ProductService,
    pricingService: PricingService,
    addressService: AddressService,
    carrierService: CarrierService,
  ): Canvas =
    runBlocking {
      canvas {
        instance(orderService)
        instance(customerService)
        instance(productService)
        instance(pricingService)
        instance(addressService)
        instance(carrierService)
      }
    }
}

@Controller("/orders")
class OrderController(private val canvas: Canvas) {
  @Get("/{id}")
  suspend fun getOrder(
    @PathVariable id: String,
  ): OrderPage =
    canvas.withLayer {
      instance(OrderKey, id)
    }.withMosaic { compose(OrderPageTile) }

  @Get("/{id}/total")
  suspend fun getOrderTotal(
    @PathVariable id: String,
  ): Map<String, Double> {
    val total =
      canvas.withLayer {
        instance(OrderKey, id)
      }.withMosaic { compose(OrderTotalTile) }
    return mapOf("total" to total)
  }

  @Error(exception = OrderNotFoundException::class)
  fun handleOrderNotFound(exception: OrderNotFoundException): HttpResponse<Map<String, String>> {
    return HttpResponse.status<Map<String, String>>(HttpStatus.NOT_FOUND)
      .body(mapOf("error" to (exception.message ?: "Order not found")))
  }
}

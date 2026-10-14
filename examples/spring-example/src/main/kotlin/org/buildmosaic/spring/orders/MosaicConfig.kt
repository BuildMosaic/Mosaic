package org.buildmosaic.spring.orders

import kotlinx.coroutines.runBlocking
import org.buildmosaic.core.injection.Canvas
import org.buildmosaic.core.injection.canvas
import org.buildmosaic.library.service.AddressService
import org.buildmosaic.library.service.CarrierService
import org.buildmosaic.library.service.CustomerService
import org.buildmosaic.library.service.OrderService
import org.buildmosaic.library.service.PricingService
import org.buildmosaic.library.service.ProductService
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class MosaicConfig {
  @Bean
  fun orderService(): OrderService = OrderService()

  @Bean
  fun customerService(): CustomerService = CustomerService()

  @Bean
  fun productService(): ProductService = ProductService()

  @Bean
  fun pricingService(): PricingService = PricingService()

  @Bean
  fun addressService(): AddressService = AddressService()

  @Bean
  fun carrierService(): CarrierService = CarrierService()

  // Spring invokes bean factories synchronously; bridge only during startup.
  @Bean
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

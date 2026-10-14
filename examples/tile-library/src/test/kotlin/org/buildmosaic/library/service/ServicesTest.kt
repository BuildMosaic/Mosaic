package org.buildmosaic.library.service

import org.buildmosaic.library.model.Address
import org.buildmosaic.library.model.Customer
import org.buildmosaic.library.model.Order
import org.buildmosaic.library.model.OrderLineItem
import org.buildmosaic.library.model.Price
import org.buildmosaic.library.model.Product
import org.buildmosaic.library.model.Quote
import kotlin.test.Test
import kotlin.test.assertEquals

class ServicesTest {
  @Test
  fun `services return expected data`() {
    val order = OrderService().getOrder("order-1")
    val expectedOrder =
      Order(
        id = "order-1",
        customerId = "customer-1",
        items =
          listOf(
            OrderLineItem("product-1", "sku-1", 2),
            OrderLineItem("product-2", "sku-2", 1),
          ),
      )
    assertEquals(expectedOrder, order)

    val customer = CustomerService().getCustomer("customer-1")
    assertEquals(Customer("customer-1", "Jane Doe"), customer)

    val products = ProductService().getProducts(listOf("product-1", "product-2"))
    val expectedProducts =
      mapOf(
        "product-1" to Product("product-1", "Coffee Mug"),
        "product-2" to Product("product-2", "Tea Kettle"),
      )
    assertEquals(expectedProducts, products)

    val prices = PricingService().getPrices(listOf("sku-1", "sku-2"))
    val expectedPrices =
      mapOf(
        "sku-1" to Price("sku-1", 12.99),
        "sku-2" to Price("sku-2", 29.99),
      )
    assertEquals(expectedPrices, prices)

    val address = AddressService().getAddress("order-1")
    assertEquals(Address("123 Main St", "Springfield"), address)

    val carriers = CarrierService().getAvailableCarriers()
    val expectedCarriers = listOf("UPS", "FEDEX", "DHL")
    assertEquals(expectedCarriers, carriers)

    val quotes = CarrierService().getQuotes(address, carriers)
    val expectedQuotes =
      mapOf(
        "UPS" to Quote("UPS", 5.99),
        "FEDEX" to Quote("FEDEX", 7.49),
        "DHL" to Quote("DHL", 6.49),
      )
    assertEquals(expectedQuotes, quotes)
  }
}

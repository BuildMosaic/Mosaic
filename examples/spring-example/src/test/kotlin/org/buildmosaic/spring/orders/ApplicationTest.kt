package org.buildmosaic.spring.orders

import com.fasterxml.jackson.databind.ObjectMapper
import org.buildmosaic.core.injection.Canvas
import org.buildmosaic.library.service.AddressService
import org.buildmosaic.library.service.CarrierService
import org.buildmosaic.library.service.CustomerService
import org.buildmosaic.library.service.OrderService
import org.buildmosaic.library.service.PricingService
import org.buildmosaic.library.service.ProductService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.context.ApplicationContext
import org.springframework.http.HttpStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApplicationTest {
  @Autowired
  lateinit var client: TestRestTemplate

  @Autowired
  lateinit var objectMapper: ObjectMapper

  @Autowired
  lateinit var context: ApplicationContext

  @Autowired
  lateinit var canvas: Canvas

  @Test
  fun `canvas borrows Spring services`() {
    listOf(
      OrderService::class,
      CustomerService::class,
      ProductService::class,
      PricingService::class,
      AddressService::class,
      CarrierService::class,
    ).forEach { type ->
      assertSame(context.getBean(type.java), canvas.source(type))
    }
  }

  @Test
  fun `get order returns order page`() {
    val response = client.getForEntity("/orders/order-1", String::class.java)
    assertEquals(HttpStatus.OK, response.statusCode)
    val page = objectMapper.readTree(response.body)
    assertEquals("order-1", page["summary"]["order"]["id"].asText())
    assertEquals("Jane Doe", page["summary"]["customer"]["name"].asText())
    assertEquals(2, page["summary"]["lineItems"].size())
    assertEquals(3, page["logistics"]["carrierQuotes"].size())
  }

  @Test
  fun `get order total returns total`() {
    val response = client.getForEntity("/orders/order-1/total", Double::class.java)
    assertEquals(HttpStatus.OK, response.statusCode)
    assertEquals(55.97, response.body!!, 0.001)
  }

  @Test
  fun `missing order does not affect the next request`() {
    val missing = client.getForEntity("/orders/missing", String::class.java)
    assertEquals(HttpStatus.NOT_FOUND, missing.statusCode)
    val found = client.getForEntity("/orders/order-1", String::class.java)
    assertEquals(HttpStatus.OK, found.statusCode)
  }
}

package org.buildmosaic.micronaut.orders

import io.micronaut.context.BeanContext
import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpStatus
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.http.client.exceptions.HttpClientResponseException
import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import jakarta.inject.Inject
import org.buildmosaic.core.injection.Canvas
import org.buildmosaic.library.model.OrderPage
import org.buildmosaic.library.service.AddressService
import org.buildmosaic.library.service.CarrierService
import org.buildmosaic.library.service.CustomerService
import org.buildmosaic.library.service.OrderService
import org.buildmosaic.library.service.PricingService
import org.buildmosaic.library.service.ProductService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

@MicronautTest
class ApplicationTest {
  @Inject
  @field:Client("/")
  lateinit var client: HttpClient

  @Inject
  lateinit var context: BeanContext

  @Inject
  lateinit var canvas: Canvas

  @Test
  fun `canvas borrows Micronaut services`() {
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
    val response =
      client.toBlocking().exchange(
        HttpRequest.GET<Any>("/orders/order-1"),
        OrderPage::class.java,
      )

    assertEquals(HttpStatus.OK, response.status)
    val orderPage = response.body()!!
    assertEquals("order-1", orderPage.summary.order.id)
    assertEquals("Jane Doe", orderPage.summary.customer.name)
    assertEquals(2, orderPage.summary.lineItems.size)
    assertEquals(3, orderPage.logistics.carrierQuotes.size)
  }

  @Test
  fun `get order total returns total`() {
    val response =
      client.toBlocking().exchange(
        HttpRequest.GET<Any>("/orders/order-1/total"),
        Map::class.java,
      )

    assertEquals(HttpStatus.OK, response.status)
    val totalResponse = response.body() as Map<String, Any>
    assertEquals(55.97, totalResponse["total"] as Double, 0.001)
  }

  @Test
  fun `get missing order returns 404`() {
    val response =
      assertFailsWith<HttpClientResponseException> {
        client.toBlocking().exchange(
          HttpRequest.GET<Any>("/orders/missing"),
          Map::class.java,
        )
      }

    assertEquals(HttpStatus.NOT_FOUND, response.status)
    val found = client.toBlocking().exchange(HttpRequest.GET<Any>("/orders/order-1"), OrderPage::class.java)
    assertEquals(HttpStatus.OK, found.status)
  }
}

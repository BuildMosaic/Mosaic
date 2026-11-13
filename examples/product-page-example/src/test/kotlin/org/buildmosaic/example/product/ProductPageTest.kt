package org.buildmosaic.example.product

import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProductPageTest {
  @Test
  fun `page returns product details and customer-specific prices`() =
    testApplication {
      application { productPageModule() }
      val client = createClient { install(ContentNegotiation) { json() } }
      val response = client.get("/products/headphones-123?customer=member")
      assertEquals(HttpStatusCode.OK, response.status)
      val payload = response.bodyAsText()
      val json = Json.parseToJsonElement(payload).jsonObject
      assertEquals("USD", json.getValue("price").jsonObject.getValue("currency").jsonPrimitive.content)
      val page = Json.decodeFromString<ProductPage>(payload)
      assertEquals("Wireless Headphones", page.product.name)
      assertEquals("179.99", page.price.amount)
      assertTrue(page.price.memberDiscountApplied)
      assertTrue(page.availability.inStock)
      assertEquals(328, page.reviews.count)
      assertEquals(listOf("case-456", "stand-789"), page.recommendations.map { it.id })
      assertEquals(listOf("24.99", "19.99"), page.recommendations.map { it.price.amount })
      val guest = client.get("/products/headphones-123?customer=guest").body<ProductPage>()
      assertEquals("199.99", guest.price.amount)
      assertFalse(guest.price.memberDiscountApplied)
    }

  @Test
  fun `overlapping recommendation keys reuse catalog and pricing work`() =
    testApplication {
      val fixture = demoServices()
      val catalogBatches = CopyOnWriteArrayList<Set<String>>()
      val priceBatches = CopyOnWriteArrayList<Set<String>>()
      application {
        productPageModule(
          fixture.copy(
            catalog =
              CatalogService { ids ->
                catalogBatches.add(ids)
                fixture.catalog.getProducts(ids)
              },
            pricing =
              PricingService { ids, member ->
                priceBatches.add(ids)
                fixture.pricing.getPrices(ids, member)
              },
            recommendations =
              RecommendationService {
                  _,
                  _,
                ->
                listOf("headphones-123", "case-456", "stand-789", "case-456")
              },
          ),
        )
      }
      val client = createClient { install(ContentNegotiation) { json() } }
      val page = client.get("/products/headphones-123").body<ProductPage>()
      assertEquals(listOf("headphones-123", "case-456", "stand-789", "case-456"), page.recommendations.map { it.id })
      for (batches in listOf(catalogBatches, priceBatches)) {
        assertEquals(listOf("case-456", "headphones-123", "stand-789"), batches.flatMap { it }.sorted())
        assertTrue(batches.any { it.containsAll(listOf("case-456", "stand-789")) })
      }
    }

  @Test
  fun `missing product returns 404 without poisoning later requests`() =
    testApplication {
      application { productPageModule() }
      assertEquals(HttpStatusCode.NotFound, client.get("/products/missing").status)
      assertEquals(HttpStatusCode.OK, client.get("/products/headphones-123").status)
    }

  @Test
  fun `missing recommendation is an upstream failure not a missing page`() =
    testApplication {
      val fixture = demoServices()
      application {
        productPageModule(
          fixture.copy(
            recommendations = RecommendationService { _, _ -> listOf("missing") },
          ),
        )
      }
      assertEquals(HttpStatusCode.BadGateway, client.get("/products/headphones-123").status)
    }

  @Test
  fun `fixture responses vary with viewed product and customer location`() =
    testApplication {
      application { productPageModule() }
      val client = createClient { install(ContentNegotiation) { json() } }
      val phoenixCase = client.get("/products/case-456?customer=member").body<ProductPage>()
      val seattleCase = client.get("/products/case-456?customer=guest").body<ProductPage>()
      assertTrue(phoenixCase.availability.inStock)
      assertFalse(seattleCase.availability.inStock)
      assertEquals(null, seattleCase.availability.estimatedDelivery)
      assertEquals(72, seattleCase.reviews.count)
      assertEquals(listOf("headphones-123", "stand-789"), seattleCase.recommendations.map { it.id })
      val seattleHeadphones = client.get("/products/headphones-123?customer=guest").body<ProductPage>()
      assertEquals(listOf("stand-789", "case-456"), seattleHeadphones.recommendations.map { it.id })
    }
}

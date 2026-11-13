package org.buildmosaic.example.product

import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProductPageTest {
  @Test
  fun `page shares dependencies and bulk loads recommendation keys`() =
    testApplication {
      val fixture = demoServices()
      val customerCalls = AtomicInteger()
      val catalogBatches = CopyOnWriteArrayList<Set<String>>()
      val priceBatches = CopyOnWriteArrayList<Set<String>>()
      val services =
        fixture.copy(
          customers =
            CustomerService { id ->
              customerCalls.incrementAndGet()
              fixture.customers.getCustomer(id)
            },
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
        )
      application { productPageModule(services) }
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
      assertEquals(1, customerCalls.get())
      // Batch boundaries can vary; each unique key is fetched once within this request.
      for (batches in listOf(catalogBatches, priceBatches)) {
        assertEquals(listOf("case-456", "headphones-123", "stand-789"), batches.flatMap { it }.sorted())
        assertTrue(batches.any { it.containsAll(listOf("case-456", "stand-789")) })
      }
      val guest = client.get("/products/headphones-123?customer=guest").body<ProductPage>()
      assertEquals("199.99", guest.price.amount)
      assertFalse(guest.price.memberDiscountApplied)
      assertEquals(2, customerCalls.get())
    }

  @Test
  fun `overlapping recommendation keys reuse catalog and pricing work`() =
    testApplication {
      val fixture = demoServices()
      val catalogKeys = CopyOnWriteArrayList<String>()
      val priceKeys = CopyOnWriteArrayList<String>()
      application {
        productPageModule(
          fixture.copy(
            catalog =
              CatalogService { ids ->
                catalogKeys.addAll(ids)
                fixture.catalog.getProducts(ids)
              },
            pricing =
              PricingService { ids, member ->
                priceKeys.addAll(ids)
                fixture.pricing.getPrices(ids, member)
              },
            recommendations = RecommendationService { _, _ -> listOf("headphones-123", "case-456", "case-456") },
          ),
        )
      }
      val client = createClient { install(ContentNegotiation) { json() } }
      val page = client.get("/products/headphones-123").body<ProductPage>()
      assertEquals(listOf("headphones-123", "case-456", "case-456"), page.recommendations.map { it.id })
      assertEquals(listOf("case-456", "headphones-123"), catalogKeys.sorted())
      assertEquals(listOf("case-456", "headphones-123"), priceKeys.sorted())
    }

  @Test
  fun `reviews and customer lookup can make progress independently`() =
    testApplication {
      val fixture = demoServices()
      val reviewsStarted = CompletableDeferred<Unit>()
      val customerStarted = CompletableDeferred<Unit>()
      application {
        productPageModule(
          fixture.copy(
            customers =
              CustomerService { id ->
                customerStarted.complete(Unit)
                withTimeout(5000) { reviewsStarted.await() }
                fixture.customers.getCustomer(id)
              },
            reviews =
              ReviewService { id ->
                reviewsStarted.complete(Unit)
                withTimeout(5000) { customerStarted.await() }
                fixture.reviews.getReviews(id)
              },
          ),
        )
      }
      assertEquals(HttpStatusCode.OK, client.get("/products/headphones-123").status)
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

  @Test
  fun `missing primary product remains 404 when pricing fails first`() =
    testApplication {
      val fixture = demoServices()
      val catalogStarted = CompletableDeferred<Unit>()
      val pricingFinished = CompletableDeferred<Unit>()
      val events = CopyOnWriteArrayList<String>()
      application {
        productPageModule(
          fixture.copy(
            catalog =
              CatalogService { ids ->
                catalogStarted.complete(Unit)
                withTimeout(5000) { pricingFinished.await() }
                events += "catalog missing"
                throw ProductNotFound(ids.single())
              },
            pricing =
              PricingService { _, _ ->
                withTimeout(5000) { catalogStarted.await() }
                try {
                  events += "pricing failed"
                  throw IllegalStateException("Pricing unavailable")
                } finally {
                  pricingFinished.complete(Unit)
                }
              },
          ),
        )
      }

      assertEquals(HttpStatusCode.NotFound, client.get("/products/missing").status)
      assertEquals(listOf("pricing failed", "catalog missing"), events.toList())
    }
}

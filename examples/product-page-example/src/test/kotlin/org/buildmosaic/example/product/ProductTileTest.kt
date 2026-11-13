package org.buildmosaic.example.product

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.buildmosaic.core.injection.canvas
import org.buildmosaic.core.injection.withMosaic
import org.buildmosaic.test.mosaicBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class ProductTileTest {
  private val headphones = Product("headphones", "Headphones", "Wireless headphones", emptyList())
  private val case = Product("case", "Travel case", "A protective case", emptyList())

  @Test
  fun `availability forwards the selected product and customer location`() =
    runTest {
      val expected = Availability(inStock = true, estimatedDelivery = "2026-12-01")
      var requestedProduct: String? = null
      var requestedPostalCode: String? = null
      val delivery =
        DeliveryService { productId, postalCode ->
          requestedProduct = productId
          requestedPostalCode = postalCode
          expected
        }

      mosaicBuilder()
        .withCanvasSource(ProductId, headphones.id)
        .withCanvasSource<DeliveryService>(delivery)
        .withMockTile(CustomerTile, Customer(member = true, postalCode = "10001"))
        .withMosaic {
          assertEquals(AvailabilityTile, expected)
        }

      assertEquals(headphones.id, requestedProduct)
      assertEquals("10001", requestedPostalCode)
    }

  @Test
  fun `recommendation discovery forwards the full product and customer location`() =
    runTest {
      var requestedProduct: Product? = null
      var requestedPostalCode: String? = null
      val recommendations =
        RecommendationService { product, postalCode ->
          requestedProduct = product
          requestedPostalCode = postalCode
          listOf(case.id)
        }

      mosaicBuilder()
        .withCanvasSource<RecommendationService>(recommendations)
        .withMockTile(ProductTile, headphones)
        .withMockTile(CustomerTile, Customer(member = false, postalCode = "98101"))
        .withMosaic {
          assertEquals(RecommendationIdsTile, listOf(case.id))
        }

      assertEquals(headphones, requestedProduct)
      assertEquals("98101", requestedPostalCode)
    }

  @Test
  fun `prices forward requested keys and membership for each customer`() =
    runTest {
      val keys = setOf(headphones.id, case.id)
      for (member in listOf(false, true)) {
        var requestedKeys: Set<String>? = null
        var requestedMembership: Boolean? = null
        val prices =
          keys.associateWith {
            Price(amount = if (member) "18.00" else "20.00", memberDiscountApplied = member)
          }
        val pricing =
          PricingService { ids, isMember ->
            requestedKeys = ids
            requestedMembership = isMember
            prices
          }

        mosaicBuilder()
          .withCanvasSource<PricingService>(pricing)
          .withMockTile(CustomerTile, Customer(member = member, postalCode = "85001"))
          .withMosaic {
            assertEquals(PricesTile, keys, prices)
          }

        assertEquals(keys, requestedKeys)
        assertEquals(member, requestedMembership)
      }
    }

  @Test
  fun `product offers join catalog and price by key rather than map order`() =
    runTest {
      val headphonePrice = Price(amount = "180.00", memberDiscountApplied = true)
      val casePrice = Price(amount = "25.00", memberDiscountApplied = false)
      val expected =
        mapOf(
          headphones.id to ProductOffer(product = headphones, price = headphonePrice),
          case.id to ProductOffer(product = case, price = casePrice),
        )

      mosaicBuilder()
        .withMockTile(ProductsTile, linkedMapOf(headphones.id to headphones, case.id to case))
        .withMockTile(PricesTile, linkedMapOf(case.id to casePrice, headphones.id to headphonePrice))
        .withMosaic {
          assertEquals(ProductOffersTile, listOf(headphones.id, case.id), expected)
        }
    }

  @Test
  fun `recommendation cards preserve ranked order and repeated ids`() =
    runTest {
      val headphonePrice = Price(amount = "180.00", memberDiscountApplied = true)
      val casePrice = Price(amount = "25.00", memberDiscountApplied = false)
      val expected =
        listOf(
          Recommendation(id = case.id, name = case.name, price = casePrice),
          Recommendation(id = headphones.id, name = headphones.name, price = headphonePrice),
          Recommendation(id = case.id, name = case.name, price = casePrice),
        )

      mosaicBuilder()
        .withMockTile(RecommendationIdsTile, listOf(case.id, headphones.id, case.id))
        .withMockTile(
          ProductOffersTile,
          mapOf(
            headphones.id to ProductOffer(product = headphones, price = headphonePrice),
            case.id to ProductOffer(product = case, price = casePrice),
          ),
        )
        .withMosaic {
          assertEquals(RecommendationsTile, expected)
        }
    }

  @OptIn(ExperimentalCoroutinesApi::class)
  @Test
  fun `a second catalog consumer shares work already in flight`() =
    runTest {
      val started = CompletableDeferred<Unit>()
      val release = CompletableDeferred<Unit>()
      val batches = mutableListOf<Set<String>>()
      val catalog =
        CatalogService { ids ->
          batches += ids.toSet()
          started.complete(Unit)
          release.await()
          ids.associateWith { headphones }
        }
      val requestCanvas = canvas { instance<CatalogService>(catalog) }

      requestCanvas.use {
        it.withMosaic {
          val first = composeAsync(ProductsTile, headphones.id)
          started.await()
          // The first provider is suspended, not merely cached after completion.
          val second = composeAsync(ProductsTile, headphones.id)
          runCurrent()
          assertEquals(listOf(setOf(headphones.id)), batches)
          assertFalse(first.isCompleted)
          assertFalse(second.isCompleted)

          release.complete(Unit)
          assertEquals(headphones, first.await())
          assertEquals(headphones, second.await())
          assertEquals(listOf(setOf(headphones.id)), batches)
        }
      }
    }
}

package org.buildmosaic.example.product

import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.buildmosaic.core.injection.canvas
import org.buildmosaic.core.injection.withMosaic

fun main() {
  embeddedServer(Netty, port = 8081, host = "127.0.0.1") { productPageModule() }.start(wait = true)
}

fun Application.productPageModule(services: ProductServices = demoServices()) {
  install(ContentNegotiation) { json(Json { encodeDefaults = true }) }
  install(StatusPages) {
    exception<PrimaryProductNotFound> { call, cause ->
      call.respond(HttpStatusCode.NotFound, mapOf("error" to cause.message))
    }
    exception<ProductNotFound> { call, _ ->
      call.respond(HttpStatusCode.BadGateway, mapOf("error" to "A required product lookup failed"))
    }
  }
  val applicationCanvas =
    runBlocking {
      canvas {
        instance(services.catalog)
        instance(services.customers)
        instance(services.pricing)
        instance(services.delivery)
        instance(services.reviews)
        instance(services.recommendations)
      }
    }
  routing {
    get("/products/{id}") {
      // Demo identity only. A real BFF obtains this from its authenticated principal.
      val customerId = call.request.queryParameters["customer"] ?: "guest"
      val page =
        applicationCanvas.withLayer {
          instance(ProductId, requireNotNull(call.parameters["id"]))
          instance(CustomerId, customerId)
        }.withMosaic { compose(ProductPageTile) }
      call.respond(page)
    }
  }
}

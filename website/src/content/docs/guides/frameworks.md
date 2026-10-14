---
title: 'Use your HTTP framework'
description: 'Run the shared order response behind Spring Boot, Ktor, or Micronaut.'
---

Mosaic composes application logic; your HTTP framework owns routing, serialization, status codes, and server lifecycle. The repository has runnable Spring Boot, Ktor, and Micronaut examples using the same order Tile library.

## Run an example

Clone [BuildMosaic/Mosaic](https://github.com/BuildMosaic/Mosaic), use JDK 21, and run one application from the repository root:

```bash
./gradlew -p examples :spring-example:run
./gradlew -p examples :ktor-example:run
./gradlew -p examples :micronaut-example:run
```

Choose one command; each example serves on port 8080. Request an order:

```bash
curl http://localhost:8080/orders/order-1
curl http://localhost:8080/orders/order-1/total
```

The services are in-memory example implementations. Inspect the [models and services](https://github.com/BuildMosaic/Mosaic/tree/0.7.0/examples/tile-library/src/main/kotlin/org/buildmosaic/library) for sample data and response shape.

## Keep the request boundary small

Inside a suspending handler:

```kotlin
val page = applicationCanvas.withLayer {
  instance(OrderKey, orderId)
}.withMosaic { compose(OrderPageTile) }
```

The example's declarations and `org.buildmosaic.core.injection.withMosaic` supply these names. The child Canvas binds the request input and inherits application services; the fresh Mosaic scopes Tile reuse to that request. Use one `withMosaic` block for all composition in a handler. Tile producers inherit the calling coroutine context, and the block cancels unfinished work and waits for cleanup on exit. Your handler returns or serializes `page`.

## Pick the example for your framework

| Example                                                                                  | Where to look                                         |
| ---------------------------------------------------------------------------------------- | ----------------------------------------------------- |
| [Spring Boot](https://github.com/BuildMosaic/Mosaic/tree/0.7.0/examples/spring-example)  | Canvas configuration bean and order controllers       |
| [Ktor](https://github.com/BuildMosaic/Mosaic/tree/0.7.0/examples/ktor-example)           | Coroutine routes and StatusPages error mapping        |
| [Micronaut](https://github.com/BuildMosaic/Mosaic/tree/0.7.0/examples/micronaut-example) | Canvas factory, dependency injection, and controllers |

Ktor composes directly in coroutine routes. Spring MVC and Micronaut use suspending controllers. Spring includes `kotlinx-coroutines-reactor` for its coroutine support. Each application uses `runBlocking` only to construct the application Canvas at its synchronous setup boundary.

The examples use Mosaic's runtime APIs directly; they do not require an adapter or analysis tooling.

## Supply application services

The shared services are ordinary Kotlin classes. Spring creates them through `@Bean` methods, Micronaut through `@Singleton` factory methods, and Ktor at application setup. Tiles read them using `source<ServiceType>()`:

```kotlin
val OrderTile by singleTile {
  val orderId = source(OrderKey)
  source<OrderService>().getOrder(orderId)
}
```

Delegated declarations capture property names for runtime tracing. The shared library keeps the same Tile instances and composition graph across all three applications.

Each framework registers its existing service instances in the application Canvas:

```kotlin
canvas {
  instance(orderService)
  instance(customerService)
  instance(productService)
  instance(pricingService)
  instance(addressService)
  instance(carrierService)
}
```

`instance` borrows a value: Canvas never closes it, including on construction failure. The framework or application keeps ownership. The request ID is also an existing value, so the child Canvas registers it with `instance(OrderKey, orderId)`. Neither Canvas owns closeable resources in these examples.

Use `single { ... }` when Canvas creates and owns a service. If the application Canvas owns `AutoCloseable` services, close it during framework shutdown. If a request child owns local resources, scope it explicitly around composition; `withMosaic` does not close Canvas. See [resource ownership](/guides/resources/).

Use [OpenTelemetry tracing](/guides/tracing/) with your framework's existing request context propagation. Test the response composition separately with [mosaic-test](/guides/testing/); keep framework-specific HTTP assertions in your application tests.

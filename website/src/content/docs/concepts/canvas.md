---
title: 'Canvas and dependencies'
description: 'Bind application services and request inputs under typed Canvas keys.'
---

A Canvas binds application services and request input. Tiles read those bindings using `source`.

## Providers and typed keys

A key is a value: `val UserIdKey = CanvasKey(String::class, "userId")`. Binding identity is **KClass + qualifier**. Generic type arguments do not create distinct binding identities; use qualifiers or wrapper classes when needed. Typed APIs check value types at call sites, but do not prove binding availability. Missing `source` lookups throw `MosaicMissingKeyException`; `sourceOrNull` returns `null` when no binding exists.

During construction, a `provide` provider has a `CanvasFactory` receiver. Use `source` to resolve another required binding there, including a provider registered later in the same builder. On a built Canvas or inside a Tile, `source` retrieves a required value and `sourceOrNull` retrieves an optional value. Continuing with `UserIdKey` and the imports from the [Quick Start](/start/quick-start/):

```kotlin
import org.buildmosaic.core.injection.Canvas

class GreetingService(private val prefix: String) {
  fun greet(userId: String): String = "$prefix, $userId!"
}

suspend fun createApplicationCanvas(): Canvas = canvas {
  provide<String>("greetingPrefix") { "Welcome" }
  provide<GreetingService> { GreetingService(source<String>("greetingPrefix")) }
}

val WelcomeTile by singleTile {
  source<GreetingService>().greet(source(UserIdKey))
}

suspend fun handleRequest(applicationCanvas: Canvas, userId: String): String =
  applicationCanvas.withLayer {
    instance(UserIdKey, userId)
  }.withMosaic { compose(WelcomeTile) }
```

`Canvas` is a final Mosaic-owned class, constructed with `canvas` or `withLayer`. It stores resolved dependencies. `provide { ... }` constructs Canvas-owned values; `instance(existing)` borrows externally owned values that Canvas never closes, including on construction failure. Use `instance<Service>(existing)` to bind an interface type, `instance<Service>("primary", existing)` for a qualified binding, or `instance(key, existing)` for a CanvasKey. The external owner remains responsible for borrowed resources.

`canvas` eagerly constructs bindings. A child layer resolves local bindings first, then falls back to its parent. Overrides do not rewire services already constructed by the parent. Concurrent constructor `source` calls share one construction; recursive construction fails with a circular-dependency error.

## Application and request scopes

Construct an application Canvas for stable services. Add request-specific values in a child layer, then use `withMosaic` to run a new Mosaic owned by that request. Bind only the request values that differ from application defaults.

For services that own closeable resources, read [resource ownership](/guides/resources/). The optional [analysis plugin](/guides/analysis/) checks bindings within supported static boundaries; it cannot guarantee every dynamic lookup.

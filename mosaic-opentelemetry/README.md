# Mosaic OpenTelemetry

See actual Tile executions, shared producers, and MultiTile batch callers in your
application's existing OpenTelemetry traces.

## Installation and setup

```kotlin
dependencies {
  implementation("org.buildmosaic:mosaic-opentelemetry:0.7.0")
}
```

The adapter includes core and OpenTelemetry API 1.66.0. Use the same Mosaic version
across runtime modules, or the [BOM](../mosaic-bom/README.md).
Your application owns the SDK or agent, sampling, export, and shutdown.

```kotlin
import org.buildmosaic.core.injection.canvas
import org.buildmosaic.opentelemetry.tracing

val applicationCanvas = canvas {
  tracing { openTelemetry }
}
```

`openTelemetry` is your application's configured instance. Request Canvas layers
inherit tracing. Use `tracing()` with an already registered global provider;
setup fails if none is registered. Closing a Canvas does not shut down telemetry.

Execution spans use delegated property names such as `val OrderTile by singleTile`.
Cache reads create no spans. Links describe shared dependencies and batch callers.
Automatic telemetry excludes keys, values, request IDs, and exception contents.
Callbacks run synchronously and must return promptly; use an asynchronous export
path for network exporters. Observation failures are isolated from Tile results.

The canonical **[tracing guide](https://BuildMosaic.org/guides/tracing/)** covers
context propagation, execution completion, bounded relationship retention,
privacy, and failure semantics. [Kotlin API](https://BuildMosaic.org/api/mosaic-opentelemetry/).

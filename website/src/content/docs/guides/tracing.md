---
title: 'Trace execution'
description: 'Add Tile executions, shared producers, and batch callers to OpenTelemetry traces.'
---

See which work ran and which callers shared it. The optional OpenTelemetry adapter adds Mosaic execution to your application's existing trace pipeline.

## Setup

Add the adapter to your Kotlin/JVM application:

```kotlin
repositories {
  mavenCentral()
}

dependencies {
  implementation("org.buildmosaic:mosaic-opentelemetry:0.7.0")
}
```

Use the same Mosaic version across runtime modules. With the [BOM](https://github.com/BuildMosaic/Mosaic/blob/main/mosaic-bom/README.md), omit the adapter's dependency version.

The adapter includes Mosaic core and OpenTelemetry API 1.66.0. Your application supplies the SDK or Java agent, sampling policy, processors, exporters, resources, and shutdown. Mosaic neither installs an SDK nor registers global telemetry. Provider callbacks run synchronously and must return promptly. Use an asynchronous export path, normally the SDK's `BatchSpanProcessor`, for network exporters; Mosaic does not create an export worker or make blocking exporters asynchronous.

## Configure tracing

Configure it once on your application Canvas. `openTelemetry` below is your application's configured `OpenTelemetry` instance:

```kotlin
import org.buildmosaic.core.injection.canvas
import org.buildmosaic.core.injection.withMosaic
import org.buildmosaic.opentelemetry.tracing

val applicationCanvas = canvas {
  tracing { openTelemetry }
  instance(orderService)
}

val page = applicationCanvas.withLayer {
  instance(OrderKey, orderId)
}.withMosaic { compose(OrderPageTile) }
```

Child Canvases inherit tracing. The provider factory runs once during Canvas setup; a duplicate installation fails before another factory executes. Each request Mosaic still owns its own cache and execution state. Closing a Canvas does not shut down your telemetry pipeline.

With an already registered global provider, including an OpenTelemetry Java agent:

```kotlin
val applicationCanvas = canvas {
  tracing()
}
```

`tracing()` fails at setup if no tracing provider is registered, with guidance to configure one. It does not initialize or freeze a global fallback. Use the explicit factory to bypass global discovery; explicitly supplied no-op providers are accepted.

## What the spans represent

| Mosaic work                              | OpenTelemetry behavior                                                                    |
| ---------------------------------------- | ----------------------------------------------------------------------------------------- |
| An actual SingleTile execution           | One `INTERNAL` span using its delegated property name, or `Mosaic single`                 |
| An actual MultiTile batch                | One `INTERNAL` span using its delegated property name, or `Mosaic multi`, with batch size |
| Initiating caller                        | Its captured OpenTelemetry context becomes the parent                                     |
| Additional retained batch callers        | Distinct contributor span links, available at creation for sampling                       |
| Composed producer, including cached work | A dependency link to its actual execution identity                                        |
| Unresolved producer                      | A subscription adds the dependency link when its identity becomes available               |

Use `val OrderTile by singleTile { ... }` to give executions a useful name. Names are labels, not cache identities; aliases preserve the first bound name. Ordinary `=` declarations use the generic fallback until delegated.

Repeated cache reads create no new spans. Multiple reservation groups produced by one batch link to that same batch span. Relationships deduplicate by trace ID and span ID; a contributor target also seen as a dependency keeps its first link. Valid unsampled executions propagate their own identity. A no-op span that borrows its parent's identity does not masquerade as a new producer.

Execution spans remain current throughout the execution scope, including attached child coroutines, suspension, and dispatcher changes. They preserve captured OpenTelemetry context, including baggage, and restore the preceding context. They work with the official OpenTelemetry Kotlin coroutine context element at the surrounding request boundary. Propagate your server context into the calling coroutine using your framework's integration or OpenTelemetry's `Context.asContextElement()`; declare `opentelemetry-extension-kotlin` directly if your application uses that extension. Ordinary OpenTelemetry instrumentation inside Tiles can use `Span.current()` and create downstream spans normally.

## Completion and retention

Span start and end timestamps describe Mosaic's actual execution scope. Results may publish before attached children finish; a later child failure affects the execution span without replacing an already published result. Unresolved dependencies can delay telemetry finalization, but do not delay application completion. When finalized later, the span ends at the recorded execution completion time. Export remains the application's responsibility.

Mosaic retains the initiating caller and at most 63 additional batch callers, while preserving exact contributor count and all work. The adapter retains at most 64 distinct dependency links and 64 unresolved producer subscriptions per recording span. Across one tracing installation, at most 1,024 unresolved subscriptions can exist, including completed spans awaiting producers. Overflow drops telemetry relationships, never application work, and sets `mosaic.dependencies.truncated`. Contributor truncation is separately exposed as `mosaic.contributors.truncated`; `mosaic.contributors.count` remains exact. Non-recording spans retain no dependency collections or subscriptions.

Known contributor links are visible to sampling; dependency links added after span creation cannot influence the original sampling decision. Your SDK's span limits can further restrict exported links.

## Privacy and failure behavior

Span names include delegated property names when available. Choose static names that contain no sensitive information. Automatic attributes contain execution kind, batch size, contributor counts, and truncation flags. Failures set `ERROR` and optional `error.type` with the exception class name. Cancellation sets `mosaic.execution.cancelled` and leaves status unset. Mosaic never calls `recordException` or records keys, results, Canvas values, request/user identifiers, exception messages, stacks, causes, or suppressed errors.

Application instrumentation and the existing parent context may contain information your application deliberately supplies; those remain your application's privacy responsibility. SDK/provider callback failures are isolated from Tile results and reported using only callback category and exception class. A provider that throws before returning a newly created span owns cleanup of any partial internal state.

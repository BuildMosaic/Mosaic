---
title: 'What is Mosaic?'
description: 'Compose backend application logic from reusable Kotlin Tiles.'
---

Mosaic is a Kotlin library for building backends one response at a time. Start with the data your endpoint returns, then compose the work that produces it. Mosaic works alongside any HTTP framework, including Spring Boot, Ktor, and Micronaut. Your framework still owns routing, serialization, and server lifecycle; see the [runnable framework examples](/guides/frameworks/).

A **Tile** is a reusable piece of suspending work. It can read dependencies and compose other Tiles. A **Mosaic** executes those Tiles, sharing repeated work within that instance. A **Canvas** supplies application services and request input. A **MultiTile** does keyed work with reuse and opportunistic batching.

```kotlin
val OrderPageTile by singleTile {
  val summary = composeAsync(OrderSummaryTile)
  val logistics = composeAsync(LogisticsTile)
  OrderPage(summary.await(), logistics.await())
}
```

The example uses the models and dependencies in the [shared order library](https://github.com/BuildMosaic/Mosaic/tree/0.7.0/examples/tile-library). Summary and logistics can run concurrently. Each branch asks for its own requirements; repeated requests for the same Tile share one execution.

## When it helps

Mosaic fits responses assembled from multiple services, graphs with shared dependencies, and keyed fetches used by independent branches. It keeps that orchestration close to the response logic and makes the same compositions easy to test in isolation.

For a single service call, ordinary suspending Kotlin may already be sufficient. Mosaic adds a runtime cost that depends on the graph; inspect the [performance evidence](/reference/performance/) for your workload.

## Runtime first, tooling optional

No registration plugin or processor is needed for runtime use. Add the core library and write ordinary Kotlin. Testing and OpenTelemetry support are optional runtime modules. Compiler analysis is separate build tooling with a narrower [compatibility boundary](/reference/compatibility/#optional-analysis).

[Install Mosaic](/start/installation/), then follow the [Quick Start](/start/quick-start/). For exact signatures, use the [Kotlin API](/api/).

## Evaluate adoption

Check [runtime and optional analysis requirements](/reference/compatibility/) against your application’s toolchain. Review the [release notes and 0.x upgrade guidance](/reference/releases/) before choosing a version. Mosaic is licensed under [Apache 2.0](https://github.com/BuildMosaic/Mosaic/blob/main/LICENSE).

## Maintainer and support

Mosaic is maintained by Nicholas Abbott ([nick@buildmosaic.org](mailto:nick@buildmosaic.org)). Use [GitHub Issues](https://github.com/BuildMosaic/Mosaic/issues) for usage questions, bug reports, and feature requests. The [contribution guide](https://github.com/BuildMosaic/Mosaic/blob/main/.github/CONTRIBUTING.md) explains contribution and discussion routes.

Report vulnerabilities privately to [security@buildmosaic.org](mailto:security@buildmosaic.org), following the [security policy](https://github.com/BuildMosaic/Mosaic/security/policy). Do not open a public issue. The policy limits security updates to the latest minor release.

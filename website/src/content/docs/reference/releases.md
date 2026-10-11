---
title: 'Releases'
description: 'Release notes and the maintained Mosaic changelog.'
---

Mosaic **0.7.0** is the public stable release. It adds request execution through `withMosaic`, externally owned Canvas bindings, isolated MultiTile outcomes, and analysis of scoped execution and Tile cycles.

- [0.7.0 release notes and migration](https://github.com/BuildMosaic/Mosaic/blob/0.7.0/docs/releases/0.7.0.md)
- [0.6.0 release notes and migration](https://github.com/BuildMosaic/Mosaic/blob/main/docs/releases/0.6.0.md)
- [0.5.0: opportunistic MultiTile coalescing](https://github.com/BuildMosaic/Mosaic/blob/main/docs/releases/0.5.0.md)
- [0.4.0: discovered analysis roots and architecture graphs](https://github.com/BuildMosaic/Mosaic/blob/main/docs/releases/0.4.0.md)
- [0.3.0: optional Canvas contract analysis](https://github.com/BuildMosaic/Mosaic/blob/main/docs/releases/0.3.0.md)

The [changelog](https://github.com/BuildMosaic/Mosaic/blob/main/CHANGELOG.md) owns release history. For current requirements use [compatibility](/reference/compatibility/); for installation use the [installation guide](/start/installation/).

## Upgrading within 0.x

The 0.x release history includes source and binary compatibility changes. For example, [0.6.0 changed Canvas construction and requires consumers to recompile](https://github.com/BuildMosaic/Mosaic/blob/0.6.0/docs/releases/0.6.0.md#compatibility-and-migration). Review the release notes for each intervening version, check [runtime and optional analysis requirements](/reference/compatibility/), and test your application before upgrading.

The [security policy](https://github.com/BuildMosaic/Mosaic/security/policy) supports only the latest minor release with security updates.

## Released and unreleased code

Use the [0.7.0 source and examples](https://github.com/BuildMosaic/Mosaic/tree/0.7.0) when evaluating the public stable release. The `develop` branch contains unreleased work; its APIs and examples can differ from published artifacts. Check [GitHub Releases](https://github.com/BuildMosaic/Mosaic/releases) for published versions.

## API reference

The [native Kotlin API reference](/api/) is generated from KDoc on canonical main. It covers `mosaic-core`, `mosaic-test`, and `mosaic-opentelemetry`. It describes the repository's current API, rather than offering versioned documentation.

## Scoped execution migration

The 1.0 API removes `Canvas.create()`, public `MosaicImpl` construction/subclassing, and `TestMosaicBuilder.build()`. These are intentional Kotlin source and JVM binary changes from 0.x; recompile consumers. The public `Mosaic` interface remains the abstraction passed to library code.

Move the complete request handler into `canvas.withMosaic { handler(this) }`. Tests mirror that boundary:

```kotlin
mosaicBuilder()
  .withMockTile(NameTile, "Jane")
  .withMosaic {
    assertEquals(GreetingTile, "Hello, Jane!")
  }
```

Use `mosaicBuilder()` or `TestMosaicBuilder()` without a stored TestScope. Execution inherits the coroutine context at the `withMosaic` call, including the enclosing `runTest` Job and scheduler. For a particular dispatcher, wrap the scoped call in `withContext(dispatcher)`. Every execution gets a fresh cache and a configuration snapshot, cancels unfinished work on exit, and waits for cleanup. Test Canvas sources are borrowed and remain caller-owned.

The 1.0 Canvas vocabulary changes Kotlin source and JVM binary compatibility from 0.x: replace `single` with `provide`, constructor `paint` with `source`, and optional `sourceOr` with `sourceOrNull`; recompile consumers. Register externally owned values with `instance(...)`; use `provide { ... }` for eager Canvas-owned construction and required `source` within constructors. Returned Deferreds remain shared and Mosaic-owned: cancel your own wait rather than the Deferred.

For historical requirements and migration details, read the [0.7.0 compatibility notes](https://github.com/BuildMosaic/Mosaic/blob/0.7.0/docs/releases/0.7.0.md#compatibility), the [0.6.0 Canvas migration](https://github.com/BuildMosaic/Mosaic/blob/0.6.0/docs/releases/0.6.0.md#compatibility-and-migration), and the [release history](/reference/releases/). Those releases do not establish 1.x compatibility baselines.

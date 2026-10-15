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

## Upcoming 1.0 releases

Runtime 1.0.0 and Analysis 1.0.0 are release candidates, not published artifacts. They release independently; the Runtime BOM aligns core, test, and tracing only. Analysis installation and compiler certification are separate.

- [Runtime 1.0.0 candidate notes](https://github.com/BuildMosaic/Mosaic/blob/develop/docs/releases/runtime-1.0.0.md): migration from 0.7.0 and stable Runtime compatibility.
- [Analysis 1.0.0 candidate notes](https://github.com/BuildMosaic/Mosaic/blob/develop/docs/releases/analysis-1.0.0.md): contract version 6, Core semantic admission, and provisional tooling compatibility.
- [Executable Runtime 1.0 preview](/start/quick-start/#preview-runtime-100).

Use published coordinates until the releases are available. The [compatibility tables](/reference/compatibility/) show only verified published combinations; candidate builds do not establish records.

## Upgrading within 0.x

The 0.x release history includes source and binary compatibility changes. For example, [0.6.0 changed Canvas construction and requires consumers to recompile](https://github.com/BuildMosaic/Mosaic/blob/0.6.0/docs/releases/0.6.0.md#compatibility-and-migration). Review the release notes for each intervening version, check [runtime and optional analysis requirements](/reference/compatibility/), and test your application before upgrading.

The [security policy](https://github.com/BuildMosaic/Mosaic/security/policy) supports only the latest minor release with security updates.

## Released and unreleased code

Use the [0.7.0 source and examples](https://github.com/BuildMosaic/Mosaic/tree/0.7.0) when evaluating the public stable release. The `develop` branch contains unreleased work; its APIs and examples can differ from published artifacts. Check [GitHub Releases](https://github.com/BuildMosaic/Mosaic/releases) for published versions.

## API reference

The [native Kotlin API reference](/api/) is generated from KDoc on canonical main. It covers `mosaic-core`, `mosaic-test`, and `mosaic-opentelemetry`. It describes the repository's current API, rather than offering versioned documentation.

## Scoped execution migration

When upgrading from 0.7.0 to Runtime 1.0, replace `Canvas.create()`, public `MosaicImpl` construction, and `TestMosaicBuilder.build()` with scoped `withMosaic { ... }` calls. Replace Canvas `single` with `provide`, constructor `paint` with `source`, and optional `sourceOr` with `sourceOrNull`. Recompile consumers after migrating.

The [Runtime candidate notes](https://github.com/BuildMosaic/Mosaic/blob/develop/docs/releases/runtime-1.0.0.md) cover the complete migration and ownership rules. See the [testing guide](/guides/testing/) for scoped test examples and the [Analysis candidate notes](https://github.com/BuildMosaic/Mosaic/blob/develop/docs/releases/analysis-1.0.0.md) for contract regeneration. Historical [0.7.0 requirements](https://github.com/BuildMosaic/Mosaic/blob/0.7.0/docs/releases/0.7.0.md#compatibility) and the [0.6.0 Canvas migration](https://github.com/BuildMosaic/Mosaic/blob/0.6.0/docs/releases/0.6.0.md#compatibility-and-migration) remain available; neither establishes the 1.x compatibility baseline.

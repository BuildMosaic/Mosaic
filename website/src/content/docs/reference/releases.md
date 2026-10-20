---
title: 'Mosaic releases and migration guides'
description: 'Find the latest stable Runtime and Analysis releases, release notes, and migration guidance for existing applications.'
---

<!-- current-releases:start -->

**Runtime 1.1.0 and Analysis 1.0.1** are the latest stable certified releases. Runtime provides composition, testing, tracing, and the BOM. Analysis provides optional verification and architecture graphs. The two are independently versioned.

- [Runtime 1.1.0 release](https://github.com/BuildMosaic/Mosaic/releases/tag/runtime-1.1.0)
- [Analysis 1.0.1 release](https://github.com/BuildMosaic/Mosaic/releases/tag/analysis-1.0.1)

<!-- current-releases:end -->

Use the [installation guide](/start/installation/) to get started and [compatibility](/reference/compatibility/) to select toolchains.

## Scoped execution migration

Upgrading from 0.7.0 requires API changes and recompilation. Follow the [Runtime migration table](https://github.com/BuildMosaic/Mosaic/blob/runtime-1.0.0/docs/releases/runtime-1.0.0.md#upgrade-from-070) and [Analysis regeneration instructions](https://github.com/BuildMosaic/Mosaic/blob/analysis-1.0.0/docs/releases/analysis-1.0.0.md#upgrade-from-070).

## Historical releases

- [Runtime 1.0.0 migration notes](https://github.com/BuildMosaic/Mosaic/blob/runtime-1.0.0/docs/releases/runtime-1.0.0.md)
- [Analysis 1.0.0 migration notes](https://github.com/BuildMosaic/Mosaic/blob/analysis-1.0.0/docs/releases/analysis-1.0.0.md)
- [0.7.0 notes](https://github.com/BuildMosaic/Mosaic/blob/0.7.0/docs/releases/0.7.0.md) and [versioned guides and examples](https://github.com/BuildMosaic/Mosaic/tree/0.7.0)
- [0.6.0: Canvas construction and tracing](https://github.com/BuildMosaic/Mosaic/blob/main/docs/releases/0.6.0.md)
- [0.5.0: MultiTile coalescing](https://github.com/BuildMosaic/Mosaic/blob/main/docs/releases/0.5.0.md)
- [0.4.0: analysis roots and graphs](https://github.com/BuildMosaic/Mosaic/blob/main/docs/releases/0.4.0.md)
- [0.3.0: Canvas contract analysis](https://github.com/BuildMosaic/Mosaic/blob/main/docs/releases/0.3.0.md)

The [changelog](https://github.com/BuildMosaic/Mosaic/blob/main/CHANGELOG.md) records release history. The [Kotlin API reference](/api/) describes the current Runtime API.

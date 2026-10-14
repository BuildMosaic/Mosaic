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

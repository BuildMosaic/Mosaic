# Mosaic website product context

<!-- impeccable:product-schema 1 -->

## Platform

web

## Stack

User-selected Astro and Starlight, native Astro/CSS homepage, Markdown/MDX guides,
native Dokka HTML, pnpm, Node 22, and GitHub Pages. No application server or CMS.

## Audience and job

Kotlin backend engineers evaluating Mosaic, then learning to compose responses,
share request work, batch keyed requests, test compositions, and inspect execution.
The first screen must explain the library and put the Quick Start within reach.

## Product truth

Mosaic is a Kotlin library for composable backend orchestration, not an HTTP
framework. Canvas binds application services and request input. A Mosaic executes
Tiles and owns request-scoped reuse. The same Tile instance shares in-flight work
and results within one Mosaic. MultiTile adds equal-key reuse and opportunistic
batching, with scheduling-dependent batch boundaries. Runtime use is independent
of optional compiler analysis. Spring Boot, Ktor, and Micronaut have runnable
examples; no published framework adapters are promised.

KDoc owns API descriptions. Website docs own user teaching. Root/module READMEs
remain GitHub adoption and module orientation pages. Repository benchmark reports
own detailed methodology and source-revision-specific evidence.

## Success and constraints

A mature framework website, fast and mostly static, responsive and accessible in
both themes, with documentation search and exact Kotlin API lookup. No tracking,
customer claims, speculative features, blog, or duplicated long-form manual.
Runtime behavior and publishing identities stay unchanged. Website PR is reviewed
before deployment from protected main; no release is published by this task.

## Voice and brand commitments

Concise, factual, technically specific. Use Mosaic, Tile, MultiTile, and Canvas.
Canonical identity and usage come from .impeccable/BRAND.md; logo SVGs live in assets/.
Positioning: Think from the response up, not the database down.
Descriptor: Composable backend orchestration for Kotlin.
Branded destination: BuildMosaic.org.
Mature framework sites Micronaut, Ktor, and Quarkus establish the professional bar,
without copying their wording or layouts. Code and architecture are the visual
material; no generated abstract imagery or SaaS card catalog.

## Evidence

README.md, runnable examples, runtime source/tests, module guides, public KDoc,
performance/README.md, docs/performance.md, and docs/releases/0.6.0.md.
Performance summaries distinguish CPU/request, HTTP latency, synthetic operation
time, allocation, and application behavior. Application benchmarks identify their
measured revision and are not a fresh measurement of 0.6.0.

## Reading density

The implementation uses a compact technical narrative with substantial real code
and diagrams, inferred from the explicit brief. An optional preference question
received no additional direction; it is not a pending approval.

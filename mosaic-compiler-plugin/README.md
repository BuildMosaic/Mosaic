# Mosaic compiler analysis plugin

## Purpose

This build-tooling module is a read-only Kotlin 2.4.20 K2 compiler plugin. It uses
`CompilerPluginRegistrar` and `IrGenerationExtension` before IR lowering. It
does not transform Kotlin or run Mosaic code. The Gradle plugin installs it into the normal Kotlin/JVM `main`
compilation through Kotlin’s compiler-subplugin API. It emits per-source internal
shards for the files Kotlin recompiles. `extractMosaicMain` assembles the complete
module summary without a second compiler invocation. Standalone complete-output
mode supports compiler fixtures.

## Supported semantic boundary

The supported extraction path covers public `Tile` and `MultiTile` creation,
reified, KClass, and CanvasKey lookup, `canvas`, `single`, `instance`, `withLayer`,
`paint`, `withMosaic`, `create`, `compose`, and `composeAsync` in the tested shapes;
immutable local Canvas, Mosaic, Tile, and CanvasKey aliases; caller-side
argument evaluation in source order; parameterized Canvas helpers; ordinary
resolved calls and getters; and the direct final template method to protected
abstract suspend override transfer by parameter position. `multiTile` and
`perKeyTile` execution is classified for known-empty, known-nonempty, and unknown
requests; nested callbacks are not generally interpreted. Fresh local Tile
values get deferred body templates and invocation-local
allocation identities. Immutable aliases share the value; unsupported
capability-bearing captures are named unknown when the body executes.
Mutable and read-only List/Set/Map keys normalize to their shared runtime
KClass identity. A user
constructor is exported as a callable: proven Mosaic-free initialization has
empty effects, while capability-bearing initialization is unknown. Referenced
stored-property initialization is extracted through its getter or field read;
ordinary constants remain harmless. This does not model every JVM class-load
trigger or exception. Used capability-bearing defaults and defaults whose binary
body is unavailable are unknown after explicit actuals are evaluated. Mosaic
extension-helper receiver transfer is not modeled, so those calls are unknown;
direct Tile `source()` and direct `other.source()` remain supported. Regular
Mosaic parameters cannot borrow the caller's current Canvas. Property accessors
use the same receiver and inline safeguards as functions. Omitted constructor
defaults use the same conservative boundary as function defaults. Reified
generic array keys use erased JVM array class identity: `Array<String>` and
`Array<Int>` differ, while arrays of List element types match. Unknown Mosaic
receiver provenance, unsupported control flow, and capability callbacks also
produce explicit unknown effects. This is a narrow extractor, not a general
Kotlin interpreter.

Direct `withMosaic` evaluates its receiver, establishes one fresh standard
Mosaic, and analyzes the direct lambda once in source order. Receiver aliases
retain identity. `create` establishes a separate standard Mosaic identity.
`instance` evaluates existing values during registration, with no deferred
constructor effects. Applicable Kotlin Mosaic `@Suppress` annotations travel
with Tile contracts. The canonical [analysis configuration reference](https://BuildMosaic.org/reference/analysis-configuration/)
explains scope ownership and remaining unknown boundaries.

## Known conservative boundaries

Array component mapping is limited to boxed primitives, `String`, `Any`,
List/Set/Map/Collection interfaces, primitive arrays, and nested supported
arrays. An unrecognized generic array component produces an unknown key.

An external const qualifier arrives in this phase as a literal. Its value is
retained in the caller's summary. The original external const declaration is
not reliably recoverable, so the summary records a provenance limitation.
External inline helper bodies are unavailable before backend inlining; a helper
that can affect Mosaic is explicitly unknown. The extractor never substitutes
the newest helper body into an already compiled caller.

## Incremental compiler integration and metadata

The Gradle plugin assembles deterministic UTF-8 JSON at
`META-INF/mosaic-analysis/v1/summary.json` for packaging. Source shards are
internal incremental state, keyed by paths relative to `src/main/kotlin`; they
are not a public metadata format. After an incremental IR invocation, the
compiler plugin removes obsolete shard files. A current-source manifest selects
only current shards for assembly, including when Kotlin has no sources and does
not invoke the plugin. The summary header determines compatibility; see the
[accepted metadata contract](../mosaic-analysis-core/README.md#binary-metadata).
Regenerate incompatible dependency summaries with the supported Mosaic analysis
toolchain before consuming them.

Only analysis-core applies the Kotlin serialization compiler plugin to generate
the serializers. The packaged compiler plugin includes the serialization
runtime through analysis-core; consuming projects do not apply that compiler
plugin. The compiler option names and symbol-locator strategy remain internal
and provisional. This module is resolved automatically by the same-version
Mosaic Gradle analysis plugin; it is not an application runtime dependency or
included in the BOM.

## Extraction guarantees

The [executable scenario index](src/test/SCENARIOS.md) fixes the supported
boundary and operation/context coverage. A single normalizer evaluates each IR
expression once, appends its effects, and returns an abstract value. Immutable
aliases read that value; Canvas computations initialize invocation-local slots.
Compiler temporaries preserve named-argument order, without source-offset sorting.
Functions, accessors, constructors and intrinsics all prepare receiver and actual
values before defaults and eligibility. Registration arguments run before eager
providers. Lambda/reference creation does not execute the body; unsupported
invocation or escape is explicitly unknown.

The kernel shares call activation, dispatch resolution, parameter binding and
continuation for ordinary and Canvas results. Only a continuing path delivers a
result; incomplete expansion and unknown behavior preserve continuation. The
small effect-free whitelist and structural proof do not exempt whole packages,
ordinary return types or unavailable bodies. Constructor delegation remains a
symbolic call; unsupported stored initialization stays a named boundary.

Ordinary calls and structural omission share the same eligibility decision.
Equality, hashing collection construction, and error-message conversion require
a scalar type proof (or a statically empty collection); otherwise they report a
localized implicit-callback boundary. Lists/arrays retain their callback-free
creation behavior. Bound references evaluate receivers, while lambda and
reference bodies remain deferred. This does not interpret arbitrary callbacks
or prove general exception safety.

DSL receivers bind the entry Canvas to invocation-local value references before
body evaluation. Captures retain that Canvas across nested builders/providers;
a provider's own receiver binds its construction layer without reconstructing it.
Stable Tile properties require a top-level immutable declaration with a supported
Tile initializer and either a default getter or Mosaic's generated delegated
getter. `by singleTile`, `by multiTile`, `by perKeyTile`, and `by chunkedMultiTile`
unwrap only Mosaic's own `provideDelegate`/`getValue` operators with compiler-created
property references. In pre-lowering IR, top-level getters read a delegate field
and local getters read a delegate variable. The local delegate API is nullable;
an absent or unrecognized delegate stays unknown. Supported local delegated Tile
values preserve fresh allocation identities and immutable aliases;
capability-bearing captures remain unknown. Arbitrary delegates, custom getters,
and member-dependent Tiles have conservative boundaries. Naming is runtime
metadata: this read-only plugin does not name ordinary `=` declarations.
Custom getters retain evaluated effects but yield unknown Tile provenance. Binary
references require a proven stable Tile export from the selected producer summary;
a top-level val alone is no proof.

## Test placement

`mosaic-analysis-core` tests own semantic combinations. Compiler tests check
source-to-contract fidelity, while Gradle TestKit tests check wiring, packaging,
and invalidation. Put a regression's decisive assertion at the cheapest layer
that exposes it, and extend a relevant compiled fixture before adding another
compiler or build invocation. Keep distinct failure guarantees covered.

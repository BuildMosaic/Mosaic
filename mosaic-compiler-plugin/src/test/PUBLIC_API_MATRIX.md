# First-release public API matrix

`S` means supported with exact Canvas identity and evaluation order; `U` means supported as a named conservative unknown when a runtime value cannot be established; `O` means outside analysis scope for the stated reason; `I` means not callable by a library user.

| Public API operation | Decision and semantics |
|---|---|
| `Mosaic.canvas` | S: return the currently bound Canvas value, including through an immutable alias. Do not use a nested builder/factory receiver as its owner. |
| `Mosaic.source(qualifier)` / `source(CanvasKey)` | S for known identity, required lookup on the receiver Mosaic's Canvas. U for dynamic qualifier/key. |
| `Mosaic.sourceOrNull(qualifier)` / `sourceOrNull(CanvasKey)` | S for known identity, optional lookup on the receiver Mosaic's Canvas. U for dynamic qualifier/key. |
| `Canvas.source(KClass, qualifier)` / `source(CanvasKey)` / reified `source<T>(qualifier)` | S for known identity, required lookup on the receiver Canvas. U for dynamic type/qualifier/key. |
| `Canvas.sourceOrNull(KClass, qualifier)` / `sourceOrNull(CanvasKey)` / reified `sourceOrNull<T>(qualifier)` | S for known identity, optional lookup on the receiver Canvas. U for dynamic type/qualifier/key. |
| `CanvasKey(KClass, qualifier)`, immutable local alias, immutable top-level declaration | S: a value with runtime `KClass` identity and exact nullable qualifier. Top-level cross-file references use the frozen declaration fact. Binary references use only a producer-exported fact; absent or incompatible facts are U. `copy`/destructuring/equality are O: arbitrary data-class value manipulation is beyond the stable-key subset. |
| `CanvasBuilder.provide(CanvasKey, ctor)` / reified `provide<T>(qualifier, ctor)` | S for known identity: register before eager construction, defer `ctor` until construction. U for dynamic key/qualifier. A foreign builder receiver is U. |
| `CanvasBuilder.instance(CanvasKey, value)` / reified `instance(value)` / `instance(qualifier, value)` | S: evaluate the value and key at registration time in source order; no deferred constructor effects. Same exact key, duplicate, qualifier, alias, layer, and unsupported-receiver semantics as `provide`. Resource ownership is O. |
| `CanvasFactory.source(CanvasKey)` / reified `source<T>(qualifier)` | S for known identity: local registration first, then parent fallback at dependency-construction time. U for dynamic key/qualifier or escaped factory. |
| `canvas(build)` / `canvas(parent, build)` / named parent | S: evaluate supplied parent once, before builder registration and dependency construction; no child binding can satisfy parent-expression work. Unknown parent is U unless local bindings prove the lookup. |
| `Canvas.withLayer(build)` | S: receiver evaluated once as parent, child has local-first/parent-fallback resolution. |
| Custom Canvas construction, inheritance, and delegation | I: Canvas is final with an internal constructor; consumers construct it through the DSL. |
| `Canvas.withMosaic(block)` | S: receiver first, one fresh standard Mosaic for one direct lambda invocation, preserving source order, Canvas provenance and immutable receiver aliases. U for indirect/escaping/helper/capture forms. |
| `MosaicImpl` construction/subclassing | I: internal runtime machinery. Use `Canvas.withMosaic`; no persistent Canvas execution entry point. |
| `Tile(block)` / `singleTile(block)` | S: evaluate creation arguments once; a fresh local Tile value and its immutable aliases retain one allocation identity, with the body deferred until composition. Relevant deferred capture provenance must be established. Known immutable `CanvasKey` facts and stable exported key references, including aliases, are snapshotted into the Tile body; unknown key provenance is named U. Canvas/Mosaic/Tile/callable captures without a faithful snapshot are named U when the body executes; harmless scalar and known KClass/qualifier captures remain S. Top-level immutable default-getter declarations remain stable exports. Custom/member/computed Tile properties retain their existing U provenance boundary. |
| `MultiTile(block)` / `multiTile(block)` / `perKeyTile(fetch)` / `chunkedMultiTile(batchSize, fetch)` | S for fresh local values with established deferred captures, including immutable aliases. Creation arguments execute once; body/fetch is deferred. Execute body for known nonempty keys, skip for known empty keys, retain both possibilities for unknown keys. Unsupported capability-bearing captures are named U only if the body can execute. Invalid batch size/general exceptions are O because the analyzer checks Canvas availability. |
| `Tile.name` / `MultiTile.name` | O: runtime labels; no Canvas availability effect. Ordinary `=` declarations receive no compiler-assisted name. |
| `Tile` / `MultiTile` delegation (`provideDelegate` / `getValue`) | S for Mosaic-owned factory patterns: top-level immutable delegated properties export stable Tile contracts; local values and aliases retain fresh identities and deferred bodies. Binary references require a selected stable export. Arbitrary delegates, member-dependent properties, and unsupported capability captures remain U. |
| `Mosaic.compose(Tile)` / `composeAsync(Tile)` | S: execute Tile body when composed; preserve synchronous versus asynchronous failure propagation. Evaluate tile argument first. |
| `Mosaic.compose(MultiTile, Collection)` / `composeAsync(MultiTile, Collection)` | S: evaluate both arguments; empty skips body, obvious nonempty executes body, unknown collection retains both paths. No arbitrary collection-content inference. |
| `Mosaic.compose(MultiTile, singleKey)` / `composeAsync(MultiTile, singleKey)` | S: evaluate both arguments; always nonempty. |
| `CanvasBuilder.installExecutionObserver` and execution observation callbacks | O: execution instrumentation, not Canvas availability; arbitrary integration factories/callbacks retain conservative unknown boundaries. |
| `Canvas.close` | O: resource lifecycle, not Canvas availability. `Canvas` constructor and `CanvasBuilder`/`CanvasFactory` constructors/build method are I (`internal`). |
| `SingleBinding` constructor/create | I: internal memoization, synchronization, cycle detection, and resource recording during Canvas construction; built Canvases store resolved values. |
| `CanvasKey.toString` and generated value methods | O: formatting/value operations, with no Canvas lookup/register/construction contract. General user overrides and collection callbacks remain existing conservative boundaries. |

## Fixture coverage

`PublicApiCoverageTest` selects many roots from one source family to cover
required and optional lookups through Mosaic, `Mosaic.canvas`, Canvas, aliases,
and dynamic receivers. It crosses reified, KClass, and CanvasKey identities with
qualifiers, aliases, parent expressions, layers, constructors, and Tile and
MultiTile execution forms. Binary key exports use a separately compiled
producer. The compiler scenario index records additional execution and unknown
boundary fixtures; analysis-core tests own evaluator semantics.

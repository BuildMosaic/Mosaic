# Mosaic Runtime 1.0.0

Released October 15, 2026.

Runtime 1.0 establishes the stable API for `mosaic-core`, `mosaic-test`, and
`mosaic-opentelemetry`. Use version 1.0.0 of each, or align them with `mosaic-bom`.
Optional Analysis is versioned separately.

## Upgrade from 0.7.0

This upgrade breaks Kotlin source and JVM binary compatibility. Update Runtime
dependencies together, migrate these calls, and **recompile consumers**:

| 0.7.0 API | Runtime 1.0 API |
| --- | --- |
| Canvas `single { ... }` | `provide { ... }` for Canvas-owned construction |
| Constructor `paint<T>()` | Required `source<T>()` lookup |
| Optional `sourceOr<T>()` | `sourceOrNull<T>()` |
| `Canvas.create()` or public `MosaicImpl` construction/subclassing | `canvas.withMosaic { handler(this) }` |
| `mosaicBuilder(testScope).build()` | `mosaicBuilder().withMosaic { ... }` |

Keep the complete handler or test inside `withMosaic`. It inherits the caller's
coroutine context and waits for cleanup on exit. Tests inside `runTest` use its
scheduler; no stored TestScope is needed.

`instance(...)` borrows existing values; `provide { ... }` constructs owned values.
Close an owning Canvas after its request work finishes. `withMosaic` does not close it.

Runtime requires Java 17+ and uses Kotlin language/API level 2.2. See
[compatibility](https://BuildMosaic.org/reference/compatibility/) for supported
Kotlin versions and the stable Runtime policy, or follow the
[Quick Start](https://BuildMosaic.org/start/quick-start/).

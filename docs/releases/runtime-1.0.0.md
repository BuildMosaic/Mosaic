# Mosaic Runtime 1.0.0

Release candidate; not yet published. No publication date has been assigned.

Runtime includes `mosaic-core`, `mosaic-test`, `mosaic-opentelemetry`, and
`mosaic-bom`. Use the same Runtime version for all four; the BOM aligns only these
modules. Optional Analysis has its own version and is installed separately.

## Upgrade from 0.7.0

Update all Runtime dependencies together after publication, migrate the following
calls, then **recompile your application and libraries**. This is a source and JVM
binary breaking upgrade from 0.x; 0.7.0 is not the 1.x compatibility baseline.

| 0.7.0 API | Runtime 1.0 API |
| --- | --- |
| Canvas `single { ... }` | `provide { ... }` for eager Canvas-owned construction |
| Constructor `paint<T>()` | Required `source<T>()` lookup |
| Optional `sourceOr<T>()` | `sourceOrNull<T>()` |
| `Canvas.create()` or public `MosaicImpl` construction | `canvas.withMosaic { handler(this) }` |
| `mosaicBuilder(testScope).build()` | `mosaicBuilder().withMosaic { ... }` |

`instance(...)` continues to borrow existing values. Canvas never closes borrowed
or inherited resources; aliases do not duplicate ownership. Finish request work
before closing an owning Canvas. `withMosaic` ends the execution, not the Canvas.

Keep the complete handler or test inside the scoped block. It inherits the calling
coroutine's Job, dispatcher, and test scheduler, cancels unfinished producers on
exit, and waits for cleanup. Put dispatcher selection around the block with
`withContext`. Do not retain its Mosaic or cancel a shared Deferred to stop waiting.

```kotlin
// Inside runTest; import org.buildmosaic.test.mosaicBuilder.
mosaicBuilder()
  .withMockTile(NameTile, "Jane")
  .withMosaic {
    assertEquals(GreetingTile, "Hello, Jane!")
  }
```

`NameTile` and `GreetingTile` are your application's Tile instances. Tests use a
fresh cache and configuration snapshot each time; Canvas test sources remain
caller-owned. See the [test guide](../../mosaic-test/README.md) and
[executable Quick Start](https://BuildMosaic.org/start/quick-start/#preview-runtime-100).

## Compatibility

Runtime targets JVM 17 and Kotlin language/API level 2.2, using Kotlin libraries
2.2.21. Choose an exact certified compiler for your release from the
[compatibility reference](https://BuildMosaic.org/reference/compatibility/), which
also defines the unchanged rolling 18-month Kotlin support policy. Compiler
certification is independent of Mosaic binary compatibility.

Stable Runtime APIs follow semantic versioning from 1.0. Minor releases may add
compatible capabilities; breaking changes normally belong in major releases.
Deprecations include migration guidance without a universal waiting period.
The Runtime ABI validator compares checked-in JVM signatures and flags all
snapshot differences for review. It does not prove behavior, full Kotlin source
compatibility, inline-body compatibility, or dependency compatibility.

Analysis is optional. When using it, select a certified Runtime/Analysis/compiler
combination and follow the [Analysis migration](../../docs/releases/analysis-1.0.0.md).
Historical [0.7.0 documentation](https://github.com/BuildMosaic/Mosaic/tree/0.7.0)
remains available.

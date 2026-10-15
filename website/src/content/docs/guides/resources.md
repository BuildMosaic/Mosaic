---
title: 'Own resources'
description: 'Give application and child Canvases explicit resource lifetimes.'
---

`Canvas` implements `AutoCloseable`. Layers that only bind values need no explicit closing. When a Canvas owns `AutoCloseable` bindings, call `close()`, use `use`, or arrange an equivalent application shutdown hook. Close an application Canvas with owned resources at shutdown; scope child Canvases explicitly when they own resources. `withMosaic` cancels and joins unfinished Tile work on every exit; it does not close its Canvas for you.

`close()` waits for cleanup to finish and can be called repeatedly or concurrently without closing resources again. It attempts locally owned resources in reverse successful creation order, including dependencies created early through `source`. Borrowed bindings and parent resources remain open.

If a close hook fails, remaining hooks still run. The caller performing cleanup receives the first failure, with later failures suppressed; further `close()` calls do not retry cleanup or repeat the failure. Finish scoped Mosaic work before closing its Canvas, and do not retain or use the Canvas afterward.

If construction fails or is cancelled, successfully created local `AutoCloseable` bindings are closed in reverse creation order, including dependencies resolved early through `source`. Parent resources are left open. Cleanup failures are suppressed on the original construction failure. A constructor is responsible for resources it allocates before failing to return a value.

For build-time checks within supported boundaries, see the optional [analysis plugin](https://github.com/BuildMosaic/Mosaic/blob/main/mosaic-gradle-plugin/README.md). It can report proven missing bindings and unverifiable paths, not guarantee every dynamic lookup.

## Borrow externally owned instances

Register a pre-existing service with `instance(existingClient)`, `instance<Client>("primary", existingClient)`, or `instance(clientKey, existingClient)`. Canvas never closes these borrowed values, including on construction rollback. They are available to `source` during eager construction and use the same lookup, qualifier, duplicate-key, override, and parent-fallback rules as `provide` bindings.

Use `provide { HttpClient() }` when Canvas constructs and owns the client. The external owner remains responsible for closing an `instance` binding.

## Scope a child that owns resources

Keep the lifetime visible around composition:

```kotlin
suspend fun render(applicationCanvas: Canvas): String =
  applicationCanvas.withLayer {
    provide<LocalClient> { LocalClient() }
  }.use { requestCanvas ->
    requestCanvas.withMosaic { compose(ResponseTile) }
  }
```

This excerpt assumes your `LocalClient` implements `AutoCloseable`, your `ResponseTile` returns `String`, and the corresponding Canvas/withMosaic imports are present. `use` closes the child when composition returns or throws. It leaves application-owned services open.

At application startup, construct the parent Canvas. Register its `close()` with your framework's shutdown lifecycle if it owns resources. A plain request ID binding does not need a close scope.

Do not make resource lifetime depend on tracing completion: telemetry finalization can happen later without delaying the application's result.

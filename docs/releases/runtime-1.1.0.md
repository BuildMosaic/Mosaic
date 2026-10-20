# Mosaic Runtime 1.1.0

Release prepared October 17, 2026.

Runtime 1.1.0 adds an external dependency resolver extension to Canvas. Integrations
can supply container-managed services to existing Tiles without registering each
service individually. This release provides the Core extension; Spring and
Micronaut adapters are not included.

## External dependency resolution

Implement `CanvasDependencyResolver.resolve(key: CanvasKey<T>): T?` and install it
once with `canvas { dependencyResolver(resolver) }`. Child layers inherit it.
Explicit bindings in the local Canvas and its parents take precedence over the
resolver, both during Canvas construction and Tile execution.

Return `null` only for an absent dependency. Ambiguity, bean creation failures,
and scope failures must propagate. Canvas forwards the exact type and nullable
string qualifier without conversion, and caches neither external values nor
misses. Integrations must support concurrent synchronous lookups and handle
framework scopes and context propagation.

Resolver lookups borrow their results; Canvas never closes those values or the
resolver. Existing registration ownership still applies: `provide` owns its
returned value. Use `instance` when binding an external value or alias so that
ownership stays with its container, including during construction rollback.
See the [resolver API contract](../../mosaic-core/src/main/kotlin/org/buildmosaic/core/injection/CanvasDependencyResolver.kt).

## Upgrade from Runtime 1.0.0

Update `mosaic-core`, `mosaic-test`, and `mosaic-opentelemetry` together to 1.1.0,
or align them with `mosaic-bom:1.1.0`. Existing source and JVM binaries remain
compatible; Canvas stays concrete and applications without a resolver retain
their existing behavior. Java 17+ remains required.

Analysis remains independently versioned at 1.0.1; this release publishes no
Analysis artifacts. Dynamic external availability remains `UNVERIFIED`, while
explicit bindings can still be verified. Published toolchain combinations are
listed in the [compatibility reference](https://BuildMosaic.org/reference/compatibility/).

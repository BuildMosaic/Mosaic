package org.buildmosaic.core.injection

/**
 * External fallback for keys absent from all explicit [Canvas] bindings.
 * Install once with [CanvasBuilder.dependencyResolver]; child layers inherit the same resolver.
 *
 * Return null only when the dependency is absent. Ambiguity, creation and scope failures must
 * propagate. Canvas passes the exact [CanvasKey] without qualifier conversion and does not cache
 * results or misses. Implementations must support concurrent synchronous calls; framework scopes
 * and any required context propagation remain the integration's responsibility.
 *
 * Lookup borrows the returned value and never registers it for Canvas cleanup. The resolver itself
 * is also borrowed. Existing binding ownership still applies: [CanvasBuilder.provide] owns its
 * returned value; use [CanvasBuilder.instance] to bind an external value or alias without transferring
 * ownership. Canvas does not infer the provenance of provider results.
 */
interface CanvasDependencyResolver {
  fun <T : Any> resolve(key: CanvasKey<T>): T?
}

package org.buildmosaic.core.injection

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.buildmosaic.core.MosaicRuntimeConfig
import org.buildmosaic.core.exception.MosaicMissingKeyException
import org.buildmosaic.core.observation.ExecutionObserver
import java.util.IdentityHashMap
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

private fun missingKeyError(key: CanvasKey<*>): Nothing = throw MosaicMissingKeyException(key)

/**
 * Builder for constructing a [Canvas] with dependency bindings.
 *
 * Use this builder to register dependencies that will be available for injection
 * in tiles and other canvas-aware components.
 */
class CanvasBuilder internal constructor(inheritedConfig: MosaicRuntimeConfig = MosaicRuntimeConfig.EMPTY) {
  internal val bindings = mutableMapOf<CanvasKey<*>, SingleBinding<*>>()
  private var observerState =
    if (inheritedConfig.executionObserver == null) ObserverState.ABSENT else ObserverState.CONFIGURED
  private var runtimeConfig = inheritedConfig

  // The supported installation extension lives with the integration SPI, outside the Canvas DSL members.
  internal fun configureExecutionObserver(factory: () -> ExecutionObserver) {
    check(observerState == ObserverState.ABSENT) { "Execution observer already installed or installing" }
    observerState = ObserverState.INSTALLING
    try {
      val observer = checkNotNull(factory()) { "Execution observer factory returned null" }
      runtimeConfig = runtimeConfig.copy(executionObserver = observer)
      observerState = ObserverState.CONFIGURED
    } finally {
      if (observerState == ObserverState.INSTALLING) observerState = ObserverState.ABSENT
    }
  }

  internal fun configuration(): MosaicRuntimeConfig {
    check(observerState != ObserverState.INSTALLING) { "Execution observer installation is incomplete" }
    return runtimeConfig
  }

  private enum class ObserverState { ABSENT, INSTALLING, CONFIGURED }

  /**
   * Registers a Canvas-owned singleton dependency. Constructed [AutoCloseable] values
   * are closed on Canvas close or construction rollback. Use [instance] to borrow a value.
   *
   * @param T The type of the dependency
   * @param key The [CanvasKey] associated with your dependency
   * @param ctor Constructor function that creates the dependency instance
   */
  fun <T : Any> single(
    key: CanvasKey<T>,
    ctor: suspend CanvasFactory.() -> T,
  ) = check(bindings.putIfAbsent(key, SingleBinding(ctor)) == null) { "Duplicate binding for $key" }

  /**
   * Registers a Canvas-owned singleton dependency. Constructed [AutoCloseable] values
   * are closed on Canvas close or construction rollback. Use [instance] to borrow a value.
   *
   * @param T The type of the dependency
   * @param qualifier Optional qualifier to distinguish between multiple instances of the same type
   * @param ctor Constructor function that creates the dependency instance
   */
  inline fun <reified T : Any> single(
    qualifier: String? = null,
    noinline ctor: suspend CanvasFactory.() -> T,
  ) = single(CanvasKey(T::class, qualifier), ctor)

  /**
   * Registers an externally owned value. Canvas never closes this value, including on
   * construction rollback. The external owner remains responsible for its lifetime.
   * Available eagerly to constructors through [CanvasFactory.paint], with normal local-first lookup.
   *
   * @param key The typed key under which the existing value is registered
   * @param value The already-existing value to borrow without transferring ownership
   */
  fun <T : Any> instance(
    key: CanvasKey<T>,
    value: T,
  ) = check(bindings.putIfAbsent(key, SingleBinding(value)) == null) { "Duplicate binding for $key" }

  /**
   * Registers an externally owned [value] under type [T], without a qualifier.
   * Canvas never closes it, including on construction rollback. Use an explicit type
   * argument to bind under an interface or superclass. Use [single] to construct an owned value.
   *
   * @param value The already-existing value to borrow without transferring ownership
   */
  inline fun <reified T : Any> instance(value: T) = instance(CanvasKey(T::class), value)

  /**
   * Registers an externally owned [value] under type [T] and [qualifier].
   * Canvas never closes it, including on construction rollback. A `null` qualifier means
   * the unqualified binding. Use an explicit type argument to bind an interface or superclass.
   * This mirrors `single<T>(qualifier) { ... }` while leaving ownership with the external owner.
   *
   * @param qualifier Qualifier distinguishing bindings of the same type, or `null`
   * @param value The already-existing value to borrow without transferring ownership
   */
  inline fun <reified T : Any> instance(
    qualifier: String?,
    value: T,
  ) = instance(CanvasKey(T::class, qualifier), value)
}

/**
 * Factory for creating [Canvas] instances from dependency bindings.
 *
 * This class handles the initialization of all registered dependencies and manages
 * their lifecycle, including cleanup of locally owned [AutoCloseable] instances when the canvas is closed.
 *
 * @param bindings Map of dependency keys to their construction state
 * @param parent Optional parent canvas for dependency resolution fallback
 */
class CanvasFactory internal constructor(
  private val bindings: Map<CanvasKey<*>, SingleBinding<*>>,
  private val parent: Canvas? = null,
  private val runtimeConfig: MosaicRuntimeConfig = MosaicRuntimeConfig.EMPTY,
) {
  private val closeables = mutableListOf<AutoCloseable>()
  private val dependencies = IdentityHashMap<SingleBinding<*>, MutableList<SingleBinding<*>>>()
  private val constructionKey = object : CoroutineContext.Key<ConstructingBinding> {}

  internal fun constructionContext(binding: SingleBinding<*>): CoroutineContext =
    ConstructingBinding(binding, constructionKey)

  internal fun created(instance: Any) {
    if (instance is AutoCloseable) synchronized(closeables) { closeables.add(instance) }
  }

  private fun createdResources(): List<AutoCloseable> = synchronized(closeables) { closeables.toList() }

  /** Tracks outstanding local resolutions, including requests from concurrent child constructors. */
  private suspend fun <T : Any> create(binding: SingleBinding<T>): T {
    val owner = currentCoroutineContext()[constructionKey]?.binding
    synchronized(dependencies) {
      binding.instanceOrNull()?.let { return it }
      if (owner != null) {
        check(!dependsOn(binding, owner)) { "Circular dependency detected during initialization" }
        // Keep duplicate requests until every caller has finished waiting.
        dependencies.getOrPut(owner) { mutableListOf() }.add(binding)
      }
    }
    try {
      return binding.create(this)
    } finally {
      if (owner != null) {
        synchronized(dependencies) {
          val requests = dependencies.getValue(owner)
          requests.remove(binding)
          if (requests.isEmpty()) dependencies.remove(owner)
        }
      }
    }
  }

  private fun dependsOn(
    binding: SingleBinding<*>,
    target: SingleBinding<*>,
    visited: MutableSet<SingleBinding<*>> = mutableSetOf(),
  ): Boolean {
    if (binding === target) return true
    if (!visited.add(binding)) return false
    return dependencies[binding].orEmpty().any { dependsOn(it, target, visited) }
  }

  /** Resolves all bindings, transferring local resource ownership only after successful construction. */
  @Suppress("TooGenericExceptionCaught")
  internal suspend fun build(): Canvas {
    try {
      currentCoroutineContext().ensureActive()
      val instances = bindings.mapValues { (_, binding) -> create(binding) }
      currentCoroutineContext().ensureActive()
      return Canvas(instances, createdResources(), parent, runtimeConfig)
    } catch (failure: Throwable) {
      createdResources().asReversed().forEach { resource ->
        runCatching { resource.close() }.onFailure { cleanupFailure ->
          if (cleanupFailure !== failure) failure.addSuppressed(cleanupFailure)
        }
      }
      throw failure
    }
  }

  /**
   * Creates a dependency instance during the canvas building phase.
   * Local bindings are resolved first. If this canvas does not contain [key], lookup falls
   * back through the parent canvas. Construction is eager, so parent-owned instances are
   * reused and child overrides do not rewire services already created by the parent.
   *
   * @param T The type of the dependency
   * @param key The canvas key identifying the dependency
   * @return The created dependency instance
   */
  suspend fun <T : Any> paint(key: CanvasKey<T>): T {
    @Suppress("UNCHECKED_CAST")
    val local = bindings[key] as SingleBinding<T>?
    if (local != null) return create(local)
    return parent?.sourceOr(key) ?: missingKeyError(key)
  }

  /**
   * Creates a dependency instance using reified type parameters.
   *
   * @param T The type of the dependency
   * @param qualifier Optional qualifier to distinguish between multiple instances
   * @return The created dependency instance
   */
  suspend inline fun <reified T : Any> paint(qualifier: String? = null): T = paint(CanvasKey(T::class, qualifier))
}

/** Child coroutines inherit the current constructor for each factory, including across nested Canvas builds. */
private class ConstructingBinding(
  val binding: SingleBinding<*>,
  key: CoroutineContext.Key<ConstructingBinding>,
) : AbstractCoroutineContextElement(key)

/**
 * Creates a new [Canvas] using the canvas DSL.
 *
 * This is the primary way to create a canvas with dependency bindings. The canvas
 * supports hierarchical dependency resolution and automatic lifecycle management.
 *
 * @param parent Optional parent canvas for fallback dependency resolution
 * @param build DSL block for configuring dependency bindings
 * @return A fully initialized [Canvas]
 *
 * ```kotlin
 * val canvas = canvas {
 *   single<UserService> { UserServiceImpl() }
 *   single<DatabaseConfig> { loadConfig() }
 * }
 * ```
 */
suspend fun canvas(
  parent: Canvas? = null,
  build: CanvasBuilder.() -> Unit,
): Canvas {
  val builder = CanvasBuilder(parent?.runtimeConfig ?: MosaicRuntimeConfig.EMPTY).apply(build)
  return CanvasFactory(builder.bindings, parent, builder.configuration()).build()
}

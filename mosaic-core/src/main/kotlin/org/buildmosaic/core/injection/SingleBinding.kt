package org.buildmosaic.core.injection

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Memoizes one binding while the eager dependency graph is being constructed. */
internal class SingleBinding<T : Any> private constructor(
  private val ctor: (suspend CanvasFactory.() -> T)?,
  initialInstance: T?,
) {
  constructor(ctor: suspend CanvasFactory.() -> T) : this(ctor, null)
  constructor(instance: T) : this(null, instance)

  private val initLock = Mutex()

  @Volatile private lateinit var instance: T

  init {
    if (initialInstance != null) instance = initialInstance
  }

  fun instanceOrNull(): T? = if (::instance.isInitialized) instance else null

  @Suppress("TooGenericExceptionCaught")
  suspend fun create(canvas: CanvasFactory): T {
    return initLock.withLock {
      if (::instance.isInitialized) return instance
      var constructorFailure: Throwable? = null
      try {
        withContext(canvas.constructionContext(this@SingleBinding)) {
          try {
            // Record ownership before a cancelled context can discard the returned value.
            checkNotNull(ctor)(canvas).also {
              canvas.created(it)
              instance = it
            }
          } catch (failure: Throwable) {
            constructorFailure = failure
            throw failure
          }
        }
      } catch (failure: Throwable) {
        // Coroutine stack recovery may copy the exception while leaving the construction context.
        throw constructorFailure ?: failure
      }
    }
  }
}

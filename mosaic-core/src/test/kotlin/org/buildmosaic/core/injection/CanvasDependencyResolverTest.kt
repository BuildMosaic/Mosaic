package org.buildmosaic.core.injection

import kotlinx.coroutines.test.runTest
import org.buildmosaic.core.exception.MosaicMissingKeyException
import org.buildmosaic.core.observation.CallerContext
import org.buildmosaic.core.observation.ExecutionObserver
import org.buildmosaic.core.observation.ExecutionStart
import org.buildmosaic.core.observation.StartedObservation
import org.buildmosaic.core.observation.installExecutionObserver
import org.buildmosaic.core.singleTile
import org.buildmosaic.core.source
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame

@Suppress("FunctionMaxLength")
class CanvasDependencyResolverTest {
  @Test
  fun `explicit local and ancestor bindings win in constructors and Tiles`() =
    runTest {
      val resolver = Resolver { error("Explicit bindings must win") }
      val ancestor =
        canvas {
          dependencyResolver(resolver)
          instance(Service("ancestor"))
        }
      val parent = ancestor.withLayer { instance(Service("parent")) }
      val tile = singleTile { source<Service>().name }
      parent.withLayer { provide<String> { source<Service>().name } }.use {
        assertEquals("parent", it.source<String>())
        assertEquals("parent", it.withMosaic { compose(tile) })
      }
      parent.withLayer {
        provide<String> { source<Service>().name }
        instance(Service("child"))
      }.use {
        assertEquals("child", it.source<String>())
        assertEquals("child", it.withMosaic { compose(tile) })
      }
      val failure = IllegalStateException("local constructor")
      assertSame(
        failure,
        assertFailsWith<IllegalStateException> {
          parent.withLayer { provide<Service> { throw failure } }
        },
      )
      assertEquals(emptyList(), resolver.requests)
      assertEquals("parent", parent.source<Service>().name)
      assertEquals("ancestor", ancestor.source<Service>().name)
    }

  @Test
  fun `inherited fallback does not cache values or misses during construction or execution`() =
    runTest {
      var available = false
      val resolver = Resolver { if (available) Service("external") else null }
      val parent = canvas { dependencyResolver(resolver) }.withLayer {}
      assertNull(parent.sourceOrNull<Service>())
      available = true
      parent.withLayer {
        provide<String> {
          assertNotSame(source<Service>(), source<Service>())
          "constructed"
        }
      }.use { child ->
        assertEquals("constructed", child.source<String>())
        val tile = singleTile { source<Service>() to source<Service>() }
        val first = child.withMosaic { compose(tile) }
        val second = child.withMosaic { compose(tile) }
        assertNotSame(first.first, first.second)
        assertNotSame(first.first, second.first)
      }
      assertEquals<List<CanvasKey<*>>>(List(7) { CanvasKey(Service::class) }, resolver.requests)
    }

  @Test
  fun `exact keys and absence stay distinct from resolver failures`() =
    runTest {
      val values = listOf(Service("default"), Service("empty"), Service("named"))
      val keys = listOf(null, "", "named").map { CanvasKey(Service::class, it) }
      val resolver = Resolver { key -> keys.indexOf(key).takeIf { it >= 0 }?.let(values::get) }
      canvas { dependencyResolver(resolver) }.use { root ->
        assertSame(values[0], root.source<Service>())
        assertSame(values[1], root.source(Service::class, ""))
        assertSame(values[2], root.source(keys[2]))
        val missing = CanvasKey(Service::class, "missing")
        assertNull(root.sourceOrNull(missing))
        assertEquals(missing, assertFailsWith<MosaicMissingKeyException> { root.source(missing) }.key)
        assertEquals(
          missing,
          assertFailsWith<MosaicMissingKeyException> {
            root.withLayer { provide<String> { source(missing).name } }
          }.key,
        )
      }
      for (failure in listOf(IllegalArgumentException("ambiguous"), IllegalStateException("creation"))) {
        val failing = Resolver { throw failure }
        val root = canvas { dependencyResolver(failing) }
        assertSame(failure, assertFailsWith<RuntimeException> { root.sourceOrNull<Service>() })
        assertSame(failure, assertFailsWith<RuntimeException> { root.source<Service>() })
        assertSame(
          failure,
          assertFailsWith<RuntimeException> {
            root.withLayer { provide<String> { source<Service>().name } }
          },
        )
      }
    }

  @Test
  fun `lookup and instance bindings borrow resources through close and rollback`() =
    runTest {
      for (rollback in listOf(false, true)) {
        val external = Resource()
        val owned = Resource()
        val resolver = Resolver { external }

        suspend fun construct(): Canvas =
          canvas {
            dependencyResolver(resolver)
            instance("alias", external)
            provide<Resource>("owned") {
              assertSame(external, source<Resource>("external"))
              owned
            }
            if (rollback) provide<String> { error("rollback") }
          }
        if (rollback) {
          assertFailsWith<IllegalStateException> { construct() }
        } else {
          construct().use {
            assertSame(external, it.source<Resource>())
            assertSame(external, it.source<Resource>("alias"))
            assertSame(owned, it.source<Resource>("owned"))
          }
        }
        assertEquals(1, owned.closes)
        assertEquals(0, external.closes)
        assertEquals(0, resolver.closes)
      }
    }

  @Test
  fun `resolver and observation configuration compose and reject resolver replacement`() =
    runTest {
      for (resolverFirst in listOf(false, true)) {
        var starts = 0
        val observer =
          object : ExecutionObserver {
            override fun captureCaller(): CallerContext? = null

            override fun onStart(start: ExecutionStart): StartedObservation? {
              starts++
              return null
            }
          }
        val resolver = Resolver { Service("external") }
        val root =
          canvas {
            if (resolverFirst) dependencyResolver(resolver)
            installExecutionObserver { observer }
            if (!resolverFirst) dependencyResolver(resolver)
            assertFailsWith<IllegalStateException> { dependencyResolver(resolver) }
          }
        assertFailsWith<IllegalStateException> { root.withLayer { dependencyResolver(resolver) } }
        root.withLayer {}.use { child ->
          assertEquals("external", child.withMosaic { compose(singleTile { source<Service>().name }) })
        }
        assertEquals(1, starts)
        assertEquals(1, resolver.requests.size)
      }
    }

  private class Service(val name: String)

  private class Resource : AutoCloseable {
    var closes = 0

    override fun close() {
      closes++
    }
  }

  private class Resolver(private val lookup: (CanvasKey<*>) -> Any?) : CanvasDependencyResolver, AutoCloseable {
    val requests = mutableListOf<CanvasKey<*>>()
    var closes = 0

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> resolve(key: CanvasKey<T>): T? {
      requests.add(key)
      return lookup(key) as T?
    }

    override fun close() {
      closes++
    }
  }
}

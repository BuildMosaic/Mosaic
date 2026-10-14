package org.buildmosaic.core.injection

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame

class InstanceBindingTest {
  private interface Service

  private class Resource : Service, AutoCloseable {
    var closes = 0

    override fun close() {
      closes++
    }
  }

  @Test fun borrowedValuesResolveDuringConstruction() =
    runTest {
      val ordinary = Any()
      val qualified = Any()
      val keyed = Any()
      val key = CanvasKey(Any::class, "keyed")
      val canvas =
        canvas {
          single<List<Any>> { listOf(paint<Any>(), paint<Any>("primary"), paint(key)) }
          instance(ordinary)
          instance("primary", qualified)
          instance(key, keyed)
        }
      assertSame(ordinary, canvas.source<Any>())
      assertSame(qualified, canvas.source(Any::class, "primary"))
      assertSame(keyed, canvas.source(key))
      assertEquals(listOf(ordinary, qualified, keyed), canvas.source<List<Any>>())
    }

  @Test fun explicitInterfaceBindingsUseTheDeclaredType() =
    runTest {
      val ordinary = Resource()
      val qualified = Resource()
      val canvas =
        canvas {
          instance<Service>(ordinary)
          instance<Service>("primary", qualified)
          single<List<Service>> { listOf(paint(), paint("primary")) }
        }
      assertSame(ordinary, canvas.source<Service>())
      assertSame(qualified, canvas.source(Service::class, "primary"))
      assertNull(canvas.sourceOr(Resource::class))
      assertNull(canvas.sourceOr(Resource::class, "primary"))
      assertEquals(listOf(ordinary, qualified), canvas.source<List<Service>>())
      canvas.close()
      assertEquals(listOf(0, 0), listOf(ordinary.closes, qualified.closes))
    }

  @Test fun stringBindingsResolveWithoutAmbiguity() =
    runTest {
      val orderKey = CanvasKey(String::class, "orderId")
      val canvas =
        canvas {
          instance("plain")
          instance("primary", "qualified")
          instance(orderKey, "order-1")
          single<List<String>> { listOf(paint(), paint("primary"), paint(orderKey)) }
        }
      assertEquals("plain", canvas.source<String>())
      assertEquals("qualified", canvas.source(String::class, "primary"))
      assertEquals("order-1", canvas.source(orderKey))
      assertEquals(listOf("plain", "qualified", "order-1"), canvas.source<List<String>>())
      assertEquals("unqualified", canvas { instance<String>(null, "unqualified") }.source<String>())
    }

  @Test fun onlyOwnedResourcesAreClosed() =
    runTest {
      val borrowed = Resource()
      val qualified = Resource()
      val keyed = Resource()
      val key = CanvasKey(Resource::class, "keyed")
      val owned = Resource()
      canvas {
        instance(borrowed)
        instance("primary", qualified)
        instance(key, keyed)
        single("owned") { owned }
      }.close()
      assertEquals(listOf(0, 0, 0), listOf(borrowed.closes, qualified.closes, keyed.closes))
      assertEquals(1, owned.closes)
    }

  @Test fun rollbackOnlyClosesConstructedOwnedResources() =
    runTest {
      val borrowed = Resource()
      val qualified = Resource()
      val keyed = Resource()
      val key = CanvasKey(Resource::class, "keyed")
      val owned = Resource()
      assertFailsWith<IllegalStateException> {
        canvas {
          instance(borrowed)
          instance("primary", qualified)
          instance(key, keyed)
          single("owned") { owned }
          single<String> { error("construction failed") }
        }
      }
      assertEquals(listOf(0, 0, 0), listOf(borrowed.closes, qualified.closes, keyed.closes))
      assertEquals(1, owned.closes)
    }

  @Test fun duplicateInstancesAndMixedBindingsAreRejected() =
    runTest {
      val key = CanvasKey(Any::class)
      assertFailsWith<IllegalStateException> {
        canvas {
          instance(Any())
          instance(Any())
        }
      }
      assertFailsWith<IllegalStateException> {
        canvas {
          single(key) { Any() }
          instance(key, Any())
        }
      }
      assertFailsWith<IllegalStateException> {
        canvas {
          instance(key, Any())
          single(key) { Any() }
        }
      }
      assertFailsWith<IllegalStateException> {
        canvas {
          instance("primary", Any())
          instance(CanvasKey(Any::class, "primary"), Any())
        }
      }
    }

  @Test fun childOverridesAndParentFallbackPreserveOwnership() =
    runTest {
      val inherited = Resource()
      val override = Resource()
      val parentOnly = Resource()
      val parent =
        canvas {
          instance(inherited)
          instance("parent", parentOnly)
        }
      val child =
        parent.withLayer {
          instance(override)
          single<List<Resource>> { listOf(paint(), paint("parent")) }
        }
      assertSame(override, child.source<Resource>())
      assertSame(inherited, parent.source<Resource>())
      assertSame(parentOnly, child.source(Resource::class, "parent"))
      assertEquals(listOf(override, parentOnly), child.source<List<Resource>>())
      child.close()
      parent.close()
      assertEquals(listOf(0, 0, 0), listOf(inherited.closes, override.closes, parentOnly.closes))
    }
}

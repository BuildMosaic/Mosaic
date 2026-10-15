package org.buildmosaic.core.injection

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.buildmosaic.core.exception.MosaicMissingKeyException
import org.buildmosaic.core.source
import org.buildmosaic.core.sourceOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@Suppress("LargeClass", "FunctionMaxLength")
class CanvasConstructionTest {
  // Test interfaces for dependency injection
  interface TestService {
    fun getValue(): String
  }

  interface TestRepository {
    fun getData(): String
  }

  // Simple implementations
  class TestServiceImpl(private val value: String) : TestService {
    override fun getValue(): String = value
  }

  class TestRepositoryImpl(private val data: String) : TestRepository {
    override fun getData(): String = data
  }

  class DependentService(private val repository: TestRepository) : TestService {
    override fun getValue(): String = repository.getData()
  }

  @Test
  fun `registration overloads route exact types and distinct qualifiers`() =
    runTest {
      val unqualified = TestServiceImpl("unqualified")
      val empty = TestServiceImpl("empty")
      val primary = TestServiceImpl("primary")
      val secondary = TestServiceImpl("secondary")
      val keyed = CanvasKey(TestService::class, "keyed")
      val value = TestServiceImpl("keyed")
      val testCanvas =
        canvas {
          provide<TestService>(null) { unqualified }
          provide<TestService>("") { empty }
          provide<TestService>("primary") { primary }
          provide(CanvasKey(TestService::class, "secondary")) { secondary }
          provide(keyed) { value }
        }
      assertSame(unqualified, testCanvas.source<TestService>())
      assertSame(unqualified, testCanvas.source(TestService::class, null))
      assertSame(empty, testCanvas.source(TestService::class, ""))
      assertSame(primary, testCanvas.source(TestService::class, "primary"))
      assertSame(secondary, testCanvas.source(TestService::class, "secondary"))
      assertSame(value, testCanvas.source(keyed))
      assertNull(testCanvas.sourceOrNull<TestServiceImpl>())
      assertNull(testCanvas.sourceOrNull(TestService::class, "missing"))
    }

  @Test
  fun `should source exact parent instances using reified and qualified keys`() =
    runTest {
      val parentService = TestServiceImpl("parent-service")
      val parentRepository = TestRepositoryImpl("parent-repository")
      val childCanvas =
        canvas(
          canvas {
            provide<TestService> { parentService }
            provide<TestRepository>("qualified") { parentRepository }
          },
        ) {
          provide<TestService>("consumer") {
            val service = source<TestService>()
            val repository = source(CanvasKey(TestRepository::class, "qualified"))
            TestServiceImpl("${service.getValue()}-${repository.getData()}")
          }
        }

      val consumer = childCanvas.source(TestService::class, "consumer")

      assertEquals("parent-service-parent-repository", consumer.getValue())
      assertSame(parentService, childCanvas.source<TestService>())
      assertSame(parentRepository, childCanvas.source(TestRepository::class, "qualified"))
    }

  @Test
  fun `should source through ancestors`() =
    runTest {
      val grandparentService = TestServiceImpl("grandparent-service")
      val nearestService = TestServiceImpl("nearest-service")
      val grandparentCanvas = canvas { provide<TestService> { grandparentService } }
      val parentCanvas =
        grandparentCanvas.withLayer {
          provide<TestService> { nearestService }
        }
      val childCanvas =
        canvas(parentCanvas) {
          provide<TestRepository> { TestRepositoryImpl(source<TestService>().getValue()) }
        }

      assertEquals("nearest-service", childCanvas.source<TestRepository>().getData())
    }

  @Test
  fun `should prefer local source binding even when registered after its consumer`() =
    runTest {
      val parentRepository = TestRepositoryImpl("parent-repository")
      val parentCanvas =
        canvas {
          provide<TestRepository> { parentRepository }
          provide<TestService> { DependentService(source<TestRepository>()) }
        }
      val parentService = parentCanvas.source<TestService>()
      val childService = TestServiceImpl("child-service")
      val childCanvas =
        canvas(parentCanvas) {
          provide<TestRepository>("consumer") {
            TestRepositoryImpl(source<TestService>().getValue())
          }
          provide<TestService> { childService }
        }

      assertSame(childService, childCanvas.source<TestService>())
      assertEquals("child-service", childCanvas.source(TestRepository::class, "consumer").getData())
      assertSame(parentService, parentCanvas.source<TestService>())
      assertEquals("parent-repository", parentService.getValue())
    }

  @Test
  fun `should preserve missing key details and local constructor failures`() =
    runTest {
      val requestedKey = CanvasKey(TestService::class, "missing")
      val parentCanvas = canvas { provide<TestService> { TestServiceImpl("parent") } }
      val missingException =
        assertFailsWith<MosaicMissingKeyException> {
          canvas(parentCanvas) {
            provide<TestRepository> {
              source(requestedKey)
              TestRepositoryImpl("unreachable")
            }
          }
        }
      assertEquals(requestedKey, missingException.key)

      var constructorContinued = false
      val failure =
        assertFailsWith<IllegalStateException> {
          canvas(parentCanvas) {
            provide<TestRepository> {
              source<TestService>()
              constructorContinued = true
              TestRepositoryImpl("unreachable")
            }
            provide<TestService> { error("local failure") }
          }
        }

      assertEquals("local failure", failure.message)
      assertEquals(false, constructorContinued)
    }

  // Hierarchical resolution tests
  @Test
  fun `should handle multi-level hierarchy`() =
    runTest {
      val grandparentCanvas =
        canvas {
          provide<TestService> { TestServiceImpl("grandparent-service") }
        }

      val parentCanvas =
        canvas(grandparentCanvas) {
          provide<TestRepository> { TestRepositoryImpl("parent-repo") }
        }

      val childCanvas =
        canvas(parentCanvas) {
          // No dependencies registered
        }

      val service = childCanvas.source<TestService>()
      val repository = childCanvas.source<TestRepository>()

      assertNotNull(service)
      assertNotNull(repository)
      assertEquals("grandparent-service", service.getValue())
      assertEquals("parent-repo", repository.getData())
    }

  @Test
  fun `should return null when dependency not found in hierarchy`() =
    runTest {
      val parentCanvas =
        canvas {
          provide<TestRepository> { TestRepositoryImpl("parent-repo") }
        }

      val childCanvas =
        canvas(parentCanvas) {
          // No TestService registered anywhere
        }

      val service = childCanvas.sourceOrNull<TestService>()
      val repository = childCanvas.sourceOrNull<TestRepository>()

      assertNull(service)
      assertNotNull(repository)
      assertEquals("parent-repo", repository.getData())
    }

  @Test
  fun `should close resources in reverse creation order`() =
    runTest {
      val events = mutableListOf<String>()
      val ancestor = canvas { provide { Resource { events.add("ancestor") } } }
      val parentResource = Resource { events.add("parent") }
      val parent = ancestor.withLayer { provide { parentResource } }
      parent.withLayer {
        provide<Resource>("first") {
          events.add("create first")
          Resource { events.add("close first") }
        }
        provide<Resource>("consumer") {
          assertSame(parentResource, source<Resource>())
          source<Resource>("dependency")
          events.add("create consumer")
          Resource { events.add("close consumer") }
        }
        provide<AutoCloseable>("first alias") { source<Resource>("first") }
        provide<AutoCloseable>("parent alias") { source<Resource>() }
        provide<AutoCloseable>("ancestor alias") { ancestor.source<Resource>() }
        provide<Resource>("dependency") {
          events.add("create dependency")
          Resource { events.add("close dependency") }
        }
      }.use {
        assertSame(it.source<Resource>("first"), it.source<AutoCloseable>("first alias"))
        assertSame(parentResource, it.source<AutoCloseable>("parent alias"))
        assertSame(ancestor.source<Resource>(), it.source<AutoCloseable>("ancestor alias"))
        assertEquals(listOf("create first", "create dependency", "create consumer"), events)
      }
      assertEquals(
        listOf(
          "create first",
          "create dependency",
          "create consumer",
          "close consumer",
          "close dependency",
          "close first",
        ),
        events,
      )
      parent.close()
      parent.close()
      assertEquals(1, events.count { it == "parent" })
      assertEquals("parent", events.last())
      ancestor.close()
      assertEquals("ancestor", events.last())
    }

  @Test
  fun `should roll back created resources and suppress cleanup failures`() =
    runTest {
      val closed = mutableListOf<String>()
      val parentResource = Resource { closed.add("parent") }
      val parent = canvas { provide { parentResource } }
      val failure = AssertionError("failed construction")
      val firstCleanup = IllegalStateException("first cleanup")
      val dependencyCleanup = IllegalArgumentException("dependency cleanup")
      var dependencyCount = 0
      val thrown =
        assertFailsWith<AssertionError> {
          parent.withLayer {
            provide<Resource>("first") {
              Resource {
                closed.add("first")
                throw firstCleanup
              }
            }
            provide<Resource>("consumer") {
              assertSame(parentResource, source<Resource>())
              assertSame(source<Resource>("dependency"), source<Resource>("dependency"))
              Resource {
                closed.add("consumer")
                throw failure
              }
            }
            provide<AutoCloseable>("first alias") { source<Resource>("first") }
            provide<AutoCloseable>("parent alias") { source<Resource>() }
            provide<String> { throw failure }
            provide<Resource>("dependency") {
              dependencyCount++
              Resource {
                closed.add("dependency")
                throw dependencyCleanup
              }
            }
            provide<Int> { error("must not be constructed") }
          }
        }

      assertSame(failure, thrown)
      assertEquals(1, dependencyCount)
      assertEquals(listOf(dependencyCleanup, firstCleanup), thrown.suppressed.toList())
      assertEquals(listOf("consumer", "dependency", "first"), closed)
      parent.close()
      assertEquals(listOf("consumer", "dependency", "first", "parent"), closed)
    }

  @Test
  fun `cancellation during a suspended constructor cleans local values and preserves cancellation`() =
    runTest {
      val closed = mutableListOf<String>()
      val entered = CompletableDeferred<Unit>()
      val cancellation = CancellationException("cancel construction")
      val cleanup = IllegalStateException("cleanup")
      var constructorFailure: CancellationException? = null
      var original: Throwable? = null
      val building =
        launch {
          try {
            canvas {
              provide<Resource>("first") { Resource { closed.add("first") } }
              provide<AutoCloseable>("first alias") { source<Resource>("first") }
              provide<String> {
                source<Resource>("dependency")
                entered.complete(Unit)
                try {
                  awaitCancellation()
                } catch (failure: CancellationException) {
                  constructorFailure = failure
                  throw failure
                }
              }
              provide<Resource>("dependency") {
                Resource {
                  closed.add("dependency")
                  throw cleanup
                }
              }
            }
          } catch (failure: CancellationException) {
            original = failure
            throw failure
          }
        }
      entered.await()
      building.cancel(cancellation)
      building.join()

      assertSame(constructorFailure, original)
      assertEquals(cancellation.message, original?.message)
      assertEquals(listOf(cleanup), original?.suppressed?.toList())
      assertEquals(listOf("dependency", "first"), closed)
    }

  @Test
  fun `cancellation cannot transfer ownership after a constructor returns`() =
    runTest {
      var closed = false
      val building =
        launch {
          canvas {
            provide {
              currentCoroutineContext()[Job]!!.cancel()
              Resource { closed = true }
            }
          }
          error("cancelled construction must not return a Canvas")
        }
      building.join()
      assertEquals(true, building.isCancelled)
      assertEquals(true, closed)
    }

  // Canvas DSL and suspend function tests
  @Test
  fun `should support suspend functions in dependency constructors`() =
    runTest {
      suspend fun createAsyncService(): TestService {
        delay(10) // Simulate async work
        return TestServiceImpl("async-service")
      }

      val testCanvas =
        canvas {
          provide<TestService> { createAsyncService() }
        }

      val service = testCanvas.source<TestService>()
      assertNotNull(service)
      assertEquals("async-service", service.getValue())
    }

  @Test
  fun `should support withLayer for temporary dependency overrides`() =
    runTest {
      val baseCanvas =
        canvas {
          provide<TestService> { TestServiceImpl("base-service") }
          provide<TestRepository> { TestRepositoryImpl("base-repo") }
        }

      val layeredCanvas =
        baseCanvas.withLayer {
          provide<TestService> { TestServiceImpl("layered-service") }
        }

      // Base canvas should remain unchanged
      val baseService = baseCanvas.source<TestService>()
      val baseRepo = baseCanvas.source<TestRepository>()
      assertEquals("base-service", baseService.getValue())
      assertEquals("base-repo", baseRepo.getData())

      // Layered canvas should have override
      val layeredService = layeredCanvas.source<TestService>()
      val layeredRepo = layeredCanvas.source<TestRepository>()
      assertEquals("layered-service", layeredService.getValue())
      assertEquals("base-repo", layeredRepo.getData()) // Inherited from base
    }

  @Test
  fun `should prevent duplicate bindings for both qualified and unqualified dependencies`() =
    runTest {
      assertFailsWith<IllegalStateException> {
        canvas {
          provide<TestService> { TestServiceImpl("first") }
          provide<TestService> { TestServiceImpl("duplicate") }
        }
      }

      assertFailsWith<IllegalStateException> {
        canvas {
          provide(CanvasKey(TestService::class)) { TestServiceImpl("first") }
          provide(CanvasKey(TestService::class)) { TestServiceImpl("duplicate") }
        }
      }

      assertFailsWith<IllegalStateException> {
        canvas {
          provide<TestService>(null) { TestServiceImpl("first") }
          provide<TestService>(null) { TestServiceImpl("duplicate") }
        }
      }

      assertFailsWith<IllegalStateException> {
        canvas {
          provide<TestService>("same-qualifier") { TestServiceImpl("first") }
          provide<TestService>("same-qualifier") { TestServiceImpl("duplicate") }
        }
      }

      assertFailsWith<IllegalStateException> {
        canvas {
          provide(CanvasKey(TestService::class, "same-qualifier")) { TestServiceImpl("first") }
          provide(CanvasKey(TestService::class, "same-qualifier")) { TestServiceImpl("duplicate") }
        }
      }
    }

  // Test interfaces for complex dependency graph
  interface DatabaseConfig {
    fun getUrl(): String
  }

  class DatabaseConfigImpl(private val url: String) : DatabaseConfig {
    override fun getUrl(): String = url
  }

  class DatabaseService(private val config: DatabaseConfig) : TestRepository {
    override fun getData(): String = "data-from-${config.getUrl()}"
  }

  class BusinessService(
    private val repository: TestRepository,
    private val config: DatabaseConfig,
  ) : TestService {
    override fun getValue(): String = "business-logic-${repository.getData()}-${config.getUrl()}"
  }

  @Test
  fun `should handle complex dependency graphs`() =
    runTest {
      val testCanvas =
        canvas {
          provide<DatabaseConfig> { DatabaseConfigImpl("localhost:5432") }
          provide<TestRepository> {
            val config = source<DatabaseConfig>()
            DatabaseService(config)
          }
          provide<TestService> {
            val repo = source<TestRepository>()
            val config = source<DatabaseConfig>()
            BusinessService(repo, config)
          }
        }

      val service = testCanvas.source<TestService>()
      assertNotNull(service)
      assertEquals("business-logic-data-from-localhost:5432-localhost:5432", service.getValue())
    }

  // Error handling and edge case tests
  @Test
  fun `should share one construction across concurrent source calls`() =
    runTest {
      var constructions = 0
      val expected = TestRepositoryImpl("shared")
      val testCanvas =
        canvas {
          provide<TestService> {
            val repositories =
              coroutineScope {
                listOf(async { source<TestRepository>() }, async { source<TestRepository>() }).awaitAll()
              }
            repositories.forEach { assertSame(expected, it) }
            DependentService(repositories.first())
          }
          provide<TestRepository> {
            constructions++
            delay(1)
            expected
          }
        }

      assertEquals(1, constructions)
      assertSame(expected, testCanvas.source<TestRepository>())
      assertEquals("shared", testCanvas.source<TestService>().getValue())
    }

  @Test
  fun `should reject circular dependencies between concurrent sibling constructors`() =
    runTest {
      val serviceStarted = CompletableDeferred<Unit>()
      val repositoryStarted = CompletableDeferred<Unit>()
      val failure =
        assertFailsWith<IllegalStateException> {
          withTimeout(1.seconds) {
            canvas {
              provide<String> {
                coroutineScope {
                  listOf(async { source<TestService>() }, async { source<TestRepository>() }).awaitAll()
                }
                "root"
              }
              provide<TestService> {
                serviceStarted.complete(Unit)
                repositoryStarted.await()
                DependentService(source<TestRepository>())
              }
              provide<TestRepository> {
                repositoryStarted.complete(Unit)
                serviceStarted.await()
                TestRepositoryImpl(source<TestService>().getValue())
              }
            }
          }
        }
      assertTrue(failure.message.orEmpty().contains("Circular dependency"), failure.toString())
    }

  @Test
  fun `should reject recursive dependencies through a child coroutine`() =
    runTest {
      val failure =
        assertFailsWith<IllegalStateException> {
          withTimeout(1.seconds) {
            canvas {
              provide<TestService> {
                val repo = source<TestRepository>()
                TestServiceImpl("service-${repo.getData()}")
              }
              provide<TestRepository> {
                val service = coroutineScope { async { source<TestService>() }.await() }
                TestRepositoryImpl("repo-${service.getValue()}")
              }
            }
          }
        }
      assertTrue(failure.message.orEmpty().contains("Circular dependency"), failure.toString())
    }

  @Test
  fun `scoped Mosaic source helpers resolve its Canvas`() =
    runTest {
      val testCanvas =
        canvas {
          provide<TestService>("mosaic-service") { TestServiceImpl("mosaic-test") }
          provide<TestRepository> { TestRepositoryImpl("default-repo") }
        }

      testCanvas.withMosaic {
        val mosaic = this
        // Test Mosaic extension functions
        val service = mosaic.source<TestService>("mosaic-service")
        val defaultRepo = mosaic.sourceOrNull<TestRepository>()
        val missing = mosaic.sourceOrNull<String>()
        val missingWithKey = mosaic.sourceOrNull(CanvasKey(TestService::class, "missing"))

        assertNotNull(service)
        assertEquals("mosaic-test", service.getValue())
        assertNotNull(defaultRepo)
        assertEquals("default-repo", defaultRepo.getData())
        assertNull(missing)
        assertNull(missingWithKey)
      }
    }

  private class Resource(private val onClose: () -> Unit) : AutoCloseable {
    override fun close() = onClose()
  }
}

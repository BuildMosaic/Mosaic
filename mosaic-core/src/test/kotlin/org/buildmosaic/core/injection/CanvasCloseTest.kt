package org.buildmosaic.core.injection

import kotlinx.coroutines.test.runTest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@Suppress("LargeClass", "FunctionMaxLength")
class CanvasCloseTest {
  @Test
  fun `repeated close attempts every owned resource once`() =
    runTest {
      val counts = List(3) { AtomicInteger() }
      val canvas =
        canvas {
          counts.forEachIndexed { index, count ->
            provide<Resource>("$index") { Resource { count.incrementAndGet() } }
          }
        }

      repeat(3) { canvas.close() }

      assertEquals(listOf(1, 1, 1), counts.map { it.get() })
    }

  @Test
  fun `distinct resources that compare equal are each closed`() =
    runTest {
      data class EqualResource(val value: Int) : AutoCloseable {
        var closes = 0

        override fun close() {
          closes++
        }
      }
      val first = EqualResource(1)
      val second = EqualResource(1)
      assertEquals(first, second)
      canvas {
        provide("first") { first }
        provide("second") { second }
        provide<AutoCloseable>("alias") { source<EqualResource>("first") }
      }.close()
      assertEquals(listOf(1, 1), listOf(first.closes, second.closes))
    }

  @Test
  fun `concurrent close cleans up once and only the cleanup caller receives failure`() =
    runTest {
      val callers = 16
      val ready = CountDownLatch(callers)
      val start = CountDownLatch(1)
      val calling = CountDownLatch(callers)
      val entered = CountDownLatch(1)
      val release = CountDownLatch(1)
      val counts = List(3) { AtomicInteger() }
      val failure = IllegalStateException("cleanup")
      val canvas =
        canvas {
          counts.forEachIndexed { index, count ->
            provide<Resource>("$index") {
              Resource {
                count.incrementAndGet()
                if (index == counts.lastIndex) {
                  entered.countDown()
                  assertTrue(release.await(5, TimeUnit.SECONDS))
                  throw failure
                }
              }
            }
          }
        }
      val executor = Executors.newFixedThreadPool(callers) { task -> Thread(task).apply { isDaemon = true } }
      try {
        val results =
          List(callers) {
            executor.submit<Throwable?> {
              ready.countDown()
              check(start.await(5, TimeUnit.SECONDS))
              calling.countDown()
              runCatching { canvas.close() }.exceptionOrNull()
            }
          }
        assertTrue(ready.await(5, TimeUnit.SECONDS))
        start.countDown()
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        assertTrue(calling.await(5, TimeUnit.SECONDS))
        release.countDown()

        val failures = results.mapNotNull { it.get(5, TimeUnit.SECONDS) }
        assertEquals(1, failures.size)
        assertSame(failure, failures.single())
        assertEquals(listOf(1, 1, 1), counts.map { it.get() })
        canvas.close()
        assertEquals(listOf(1, 1, 1), counts.map { it.get() })
      } finally {
        start.countDown()
        release.countDown()
        executor.shutdownNow()
      }
    }

  @Test
  fun `reentrant close returns and reverse cleanup continues once`() =
    runTest {
      val closed = mutableListOf<String>()
      lateinit var canvas: Canvas
      canvas =
        canvas {
          provide<Resource>("first") { Resource { closed.add("first") } }
          provide<Resource>("second") { Resource { closed.add("second") } }
          provide<Resource>("third") {
            Resource {
              closed.add("third")
              canvas.close()
            }
          }
        }
      val executor = Executors.newSingleThreadExecutor { task -> Thread(task).apply { isDaemon = true } }
      try {
        // A real thread and bounded wait protect against synchronous close deadlocks.
        val result = executor.submit<Throwable?> { runCatching { canvas.close() }.exceptionOrNull() }
        assertNull(result.get(5, TimeUnit.SECONDS))
        canvas.close()
        assertEquals(listOf("third", "second", "first"), closed)
      } finally {
        executor.shutdownNow()
      }
    }

  @Test
  fun `close attempts all resources in reverse order and suppresses later failures in order`() =
    runTest {
      val closed = mutableListOf<String>()
      val primary = AssertionError("third")
      val second = IllegalArgumentException("second")
      val first = IllegalStateException("first")
      val canvas =
        canvas {
          provide<Resource>("successful") { Resource { closed.add("successful") } }
          provide<Resource>("first") {
            Resource {
              closed.add("first")
              throw first
            }
          }
          provide<Resource>("second") {
            Resource {
              closed.add("second")
              throw second
            }
          }
          provide<Resource>("third") {
            Resource {
              closed.add("third")
              throw primary
            }
          }
        }

      assertSame(primary, assertFailsWith<AssertionError> { canvas.close() })
      assertEquals(listOf("third", "second", "first", "successful"), closed)
      assertEquals(listOf(second, first), primary.suppressed.toList())
      repeat(2) { canvas.close() }
      assertEquals(listOf("third", "second", "first", "successful"), closed)
      assertEquals(listOf(second, first), primary.suppressed.toList())
    }

  @Test
  fun `a repeated failure object is not suppressed onto itself and cleanup continues`() =
    runTest {
      val closed = mutableListOf<String>()
      val failure = IllegalStateException("shared failure")
      val canvas =
        canvas {
          provide<Resource>("successful") { Resource { closed.add("successful") } }
          listOf("first", "second").forEach { name ->
            provide<Resource>(name) {
              Resource {
                closed.add(name)
                throw failure
              }
            }
          }
        }

      assertSame(failure, assertFailsWith<IllegalStateException> { canvas.close() })
      assertEquals(listOf("second", "first", "successful"), closed)
      assertTrue(failure.suppressed.isEmpty())
    }

  private class Resource(private val onClose: () -> Unit) : AutoCloseable {
    override fun close() = onClose()
  }
}

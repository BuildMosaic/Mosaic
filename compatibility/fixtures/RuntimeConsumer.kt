package certification

import io.opentelemetry.api.OpenTelemetry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.runBlocking
import org.buildmosaic.core.*
import org.buildmosaic.core.injection.*
import org.buildmosaic.opentelemetry.tracing
import org.buildmosaic.test.mosaicBuilder

class Service(val value: String)
val Value by singleTile { source<Service>().value }
val Values by multiTile<String, String> { keys -> keys.associateWith { source<Service>().value + it } }
val PerKey by perKeyTile<String, String> { source<Service>().value + it }
val Chunked by chunkedMultiTile<String, String>(2) { keys -> keys.associateWith { source<Service>().value + it } }
val Combined by singleTile { composeAsync(Value).await() + compose(Values, "a") }

// Independent implementer: consumes Mosaic's default suspend methods and overloads.
class ForeignMosaic(override val canvas: Canvas) : Mosaic {
    override fun <V> composeAsync(tile: Tile<V>): Deferred<V> = error("Not used")
    override fun <K : Any, V> composeAsync(tile: MultiTile<K, V>, keys: Collection<K>): Map<K, Deferred<V>> =
        keys.associateWith { @Suppress("UNCHECKED_CAST") CompletableDeferred(("foreign:" + it) as V) }
}

inline fun <reified T : Any> read(canvas: Canvas, qualifier: String? = null): T = canvas.source(qualifier)

fun main() = runBlocking {
    check(System.getProperty("java.specification.version") == "17")
    val key = CanvasKey(Service::class)
    canvas {
        provide<Service> { Service("value:") }
        instance("named", Service("named:"))
        tracing { OpenTelemetry.noop() }
    }.use { base ->
        check(base.source(key).value == "value:")
        check(base.source(Service::class).value == "value:")
        check(read<Service>(base, "named").value == "named:")
        check(base.sourceOrNull<Int>() == null)
        base.withLayer { instance(42) }.use { child ->
            check(child.source<Int>() == 42)
            check(child.source<Service>().value == "value:")
            child.withMosaic {
                check(compose(Combined) == "value:value:a")
                check(compose(Value) == "value:")
                check(compose(Values, listOf("a", "b")) == mapOf("a" to "value:a", "b" to "value:b"))
                check(composeAsync(PerKey, "p").await() == "value:p")
                check(compose(Chunked, listOf("c", "d")) == mapOf("c" to "value:c", "d" to "value:d"))
            }
        }
        check(ForeignMosaic(base).compose(Values, "x") == "foreign:x")
        check(ForeignMosaic(base).compose(Values, listOf("x")) == mapOf("x" to "foreign:x"))
    }
    check(Value.name == "Value" && Values.name == "Values")
    mosaicBuilder().withCanvasSource(Service("test:"))
        .withMockTile(Value, "mock").withMosaic {
            assertEquals(Value, "mock")
            assertEquals(Value, "mock", "public overload")
            assertEquals(Values, listOf("a"), mapOf("a" to "test:a"))
        }
    consumer.verifyComposite()
    println("Runtime public API consumer passed on JVM 17")
}

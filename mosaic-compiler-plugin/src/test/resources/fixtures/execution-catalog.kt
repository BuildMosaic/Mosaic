package catalog
import kotlinx.coroutines.runBlocking
import org.buildmosaic.core.*
import org.buildmosaic.core.injection.*
val CapturedTile = singleTile {
    val outer = this
    canvas {
        provide<Metrics> { Metrics() }
        provide<String> { outer.source<Metrics>(); "ok" }
    }
    "ok"
}
val LabeledCaptureTile = singleTile tile@ {
    canvas {
        provide<Metrics> { Metrics() }
        provide<String> { this@tile.source<Metrics>(); "ok" }
    }
    "ok"
}
suspend fun capturedLabelEntry() = canvas {}.withMosaic { compose(LabeledCaptureTile) }
suspend fun capturedEntry() = canvas {}.withMosaic { compose(CapturedTile) }
suspend fun suppliedOuter(): Canvas = canvas { provide<Metrics> { Metrics() } }
suspend fun capturedSuppliedEntry() = suppliedOuter().withMosaic { compose(CapturedTile) }
suspend fun outerWithConstruction(): Canvas = canvas { provide<First> { First() }; provide<Metrics> { source<First>(); Metrics() } }
suspend fun capturedOnceEntry() { val base = outerWithConstruction(); val alias = base; alias.withMosaic { compose(CapturedTile) } }
suspend fun nestedFactoryMissing(): Canvas = canvas {
    provide<String> {
        val retained = this
        canvas {
            provide<Metrics> { Metrics() }; provide<Second> { Second() }
            provide<String> { source<Second>(); retained.source<Metrics>(); "ok" }
        }
        "ok"
    }
}
suspend fun nestedFactorySupplied(): Canvas = canvas {
    provide<Metrics> { Metrics() }
    provide<String> {
        val retained = this
        canvas {
            provide<Metrics> { Metrics() }; provide<Second> { Second() }
            provide<String> { source<Second>(); retained.source<Metrics>(); "ok" }
        }
        "ok"
    }
}
val NeedsMetrics = singleTile { source<Metrics>(); "required" }
val ExposedTile = singleTile { "backing" }
    get() { field; return NeedsMetrics }
val ComputedWorkTile = chunkedMultiTile<String, String>(empty.source<Int>()) { emptyMap() }
    get() { empty.source<Second>(); return field }
suspend fun computedEntry() = canvas {}.withMosaic { compose(ExposedTile) }
suspend fun computedWorkEntry() = canvas {}.withMosaic { compose(ComputedWorkTile, listOf("key")) }
suspend fun directNeedsEntry() = canvas {}.withMosaic { compose(NeedsMetrics) }
suspend fun directNeedsSupplied() = suppliedOuter().withMosaic { compose(NeedsMetrics) }
suspend fun stableTileAlias() { val alias = NeedsMetrics; suppliedOuter().withMosaic { compose(alias) } }
class CallbackValue {
    override fun equals(other: Any?): Boolean { runBlocking { canvas {}.source<Metrics>() }; return true }
    override fun hashCode(): Int { runBlocking { canvas {}.source<Metrics>() }; return 0 }
    override fun toString(): String { runBlocking { canvas {}.source<Metrics>() }; return "" }
}
fun structuralEquality() { CallbackValue() == CallbackValue() }
fun conditionalEquality(a: CallbackValue, b: CallbackValue) { if (a == b) defaults() }
suspend fun missingReceiver(): Canvas = canvas { provide<String> { source<Metrics>(); "" } }
suspend fun boundReference(enabled: Boolean) { if (enabled) { val unused = missingReceiver()::close } }
suspend fun directBoundReference() { val unused = missingReceiver()::close }
fun deferredCreation(enabled: Boolean) { if (enabled) { val lambda = { empty.source<Metrics>() }; val ref = ::work; val extension = Canvas::close } }
fun primitiveEquality(a: Int, b: Int) { if (a == b) defaults() }
fun stringEquality(a: String, b: String) { if (a == b) defaults() }
fun safeCollections() {
    setOf(1, 2); mutableSetOf("a", "b"); emptySet<CallbackValue>(); emptyMap<CallbackValue, Int>()
    listOf(CallbackValue()); arrayOf(CallbackValue()); mutableListOf(CallbackValue()); arrayListOf(CallbackValue())
    intArrayOf(1); emptyArray<CallbackValue>(); setOf<CallbackValue>(); mutableSetOf<CallbackValue>()
}
fun safeMaps(entries: Array<Pair<String, CallbackValue>>) { mapOf(*entries); mutableMapOf(*entries) }
fun safeSetSpread(elements: Array<String>) { setOf(*elements); mutableSetOf(*elements) }
fun setCallbacks() { setOf(CallbackValue(), CallbackValue()) }
fun mutableSetCallbacks() { mutableSetOf(CallbackValue()) }
fun mapCallbacks(entries: Array<Pair<CallbackValue, Int>>) { mapOf(*entries) }
fun mutableMapCallbacks(entries: Array<Pair<CallbackValue, Int>>) { mutableMapOf(*entries) }
fun opaqueSet(elements: Array<Any>) { setOf(*elements) }
fun conditionalSet(elements: Array<CallbackValue>) { if (true) setOf(*elements) }
fun conditionalMap(entries: Array<Pair<CallbackValue, Int>>) { if (true) mapOf(*entries) }
fun conditionalError(value: CallbackValue) { if (true) error(value) }
fun conditionalScalars(elements: Array<String>) { if (true) { setOf(*elements); error("not executed") } }
fun conditionalReferenceEscape() { if (true) ignore(::work) }
fun conditionalReferenceAlias() { val ref: Any = ::work; if (true) ignore(ref) }
fun Mosaic.noWork() {}
fun conditionalExtension(base: Mosaic) { if (true) base.noWork() }
fun errorCallback() { error(CallbackValue()) }
fun errorConstant() { error("not executed") }
fun mutableAlias(enabled: Boolean, base: Canvas) { if (enabled) { var alias = base } }
class FieldBox { @JvmField val value = 1 }
suspend fun fieldReceiver(): FieldBox { missingReceiver(); return FieldBox() }
suspend fun conditionalField(enabled: Boolean) { if (enabled) fieldReceiver().value }
suspend fun directField() { fieldReceiver().value }
class CallbackField {
    var callback: () -> Unit = {}
        set(value) { if (true) field = value }
}
class Metrics
class First
class Second
class Spread
class Receiver { fun consume(first: First, second: Second, vararg rest: Any) {} }
fun ignore(value: Any?) {}
suspend fun ignoredMissing() { ignore(canvas {}.source<Metrics>()) }
suspend fun ignoredSatisfied() { ignore(canvas { provide<Metrics> { Metrics() } }.source<Metrics>()) }
suspend fun ordered() { canvas {}.source<Receiver>().consume(second = canvas {}.source<Second>(), first = canvas {}.source<First>(), rest = *arrayOf(canvas {}.source<Spread>())) }
fun defaults(value: Int = 1) {}
class Defaults(value: Int = 1)
class CapabilityDefault(base: Canvas, value: Metrics = base.source<Metrics>())
suspend fun literalDefaults() { defaults(); Defaults() }
suspend fun explicitDefaults() { CapabilityDefault(canvas {}, Metrics()) }
var sink: Any? get() = null; set(value) {}
inline var inlineSink: Any? get() = null; set(value) {}
suspend fun setterMissing() { sink = canvas {}.source<Metrics>() }
suspend fun setterSatisfied() { sink = canvas { provide<Metrics> { Metrics() } }.source<Metrics>() }
fun inlineSetter() { inlineSink = 1 }
inline val ordinaryInline: Int get() = 1
inline val canvasInline: Canvas get() = error("not executed")
fun inlineOrdinary() { ordinaryInline }
fun inlineCanvas() { canvasInline }
val prefix: Canvas get() { unknownCanvas.source<Metrics>(); return unknownCanvas }
val unknownCanvas: Canvas get() = error("not executed")
val suppliedPrefix: Canvas get() { empty.source<Metrics>(); return empty }
val empty: Canvas get() = error("unavailable")
suspend fun prefixFunction(): Canvas { canvas {}.source<Metrics>(); return canvas {} }
fun getterPrefix() { suppliedPrefix }
suspend fun functionPrefix() { prefixFunction() }
suspend fun discardedFactory() { prefixFunction() }
suspend fun aborting(): Canvas { canvas { provide<String> { source<First>(); "" } }; return canvas { provide<String> { source<Second>(); "" } } }
suspend fun abortResult() { aborting() }
suspend fun emptyPrefix() { canvas {} }
suspend fun successful(): Canvas { defaults(); return canvas {} }
suspend fun successfulPrefix() { successful() }
fun callback(block: () -> Unit) { block() }
suspend fun unknownFactory(): Canvas { callback { empty.source<Metrics>() }; return canvas { provide<String> { source<Second>(); "" } } }
suspend fun unknownPrefix() { unknownFactory() }
open class Base { open suspend fun make(): Canvas = canvas {}; open val value: Canvas get() = empty }
suspend fun through(base: Base) { base.make() }
fun throughGetter(base: Base) { base.value }
suspend fun virtualMethod() { through(Base()) }
fun virtualGetter() { throughGetter(Base()) }
suspend fun finalFactory() { successful() }
suspend fun make(): Canvas { canvas {}.source<Metrics>(); return canvas {} }
suspend fun pass(base: Canvas) { base.withMosaic {}; base.withMosaic {} }
suspend fun aliases() { val base = make(); val alias = base; alias.withMosaic { val mosaic = this; val same = mosaic; same.sourceOrNull<String>(); pass(alias) } }
val SizedTile = chunkedMultiTile<String, String>(empty.source<Int>()) { emptyMap() }
suspend fun sizedTile() { canvas {}.withMosaic { compose(SizedTile, emptyList()) } }
class Tiles { val MemberTile = singleTile { source<Metrics>() } }
suspend fun memberTile() { canvas {}.withMosaic { compose(Tiles().MemberTile) } }
suspend fun unusedLambda() { val unused = { empty.source<Metrics>() } }
fun invokedLambda() { val work = { empty.source<Metrics>() }; work() }
fun conditionalCallableEscape() { val f = if (true) { { empty.source<Metrics>(); Unit } } else { {} }; ignoreCallback(f) }
fun returnedLambda(): () -> Unit = { empty.source<Metrics>(); Unit }
fun ignoreCallback(work: () -> Unit) {}
fun escapedReturnedLambda() { ignoreCallback(returnedLambda()) }
fun defaultWork(value: Metrics = empty.source<Metrics>()) {}
fun conditionalDefault() { if (true) defaultWork() }
fun work() { empty.source<Metrics>() }
fun invokedReference() { val ref = ::work; ref() }
fun escapedLambda() { val work = { empty.source<Metrics>(); Unit }; callback(work) }
fun unknownBuilder(): CanvasBuilder = error("not executed")
fun unknownFactoryReceiver(): CanvasFactory = error("not executed")
suspend fun foreignRegistration() { canvas { unknownBuilder().provide<Metrics> { Metrics() } }.source<Metrics>() }
suspend fun foreignConstruction() { canvas { provide<Metrics> { Metrics() }; provide<String> { unknownFactoryReceiver().source<Metrics>(); "" } } }
suspend fun registrationArgument() { val base = canvas {}; canvas { provide<String>(base.source<Metrics>().toString()) { "" } } }
suspend fun optionalArgument() { val base = canvas {}; base.withMosaic { sourceOrNull<String>(base.source<Metrics>().toString()) } }
object Boot { init { empty.source<Metrics>() }; val value = 1 }
fun objectAccess() { Boot.value }
val delegated by lazy { empty.source<Metrics>() }
fun delegatedAccess() { delegated }
open class Parent { init { empty.source<Metrics>() } }
class Child : Parent()
fun superInit() { Child() }
fun conditional() { if (System.nanoTime() > 0) empty.source<Metrics>() }
fun loop() { while (System.nanoTime() > 0) empty.source<Metrics>() }
fun tryFinally() { try { empty.source<Metrics>() } finally { empty.source<Second>() } }
fun safeElvis() { val maybe: Canvas? = null; maybe?.source<Metrics>() ?: empty.source<Second>() }
fun mutation() { var base = empty; base = empty; base.source<Metrics>() }
fun harmlessControl() { var value = 1; while (value < 3) value++; if (value == 3) defaults(value) }

package consumer

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.buildmosaic.core.injection.withMosaic
import kotlinx.coroutines.withContext
import org.buildmosaic.core.injection.canvas
import org.buildmosaic.core.observation.*
import org.buildmosaic.core.singleTile

private interface Token : CallerContext, ExecutionIdentity
private class FirstToken : Token
private class SecondToken : Token
private class Contexts(val values: List<CallerContext?>) : CallerContext
private class Identities(val values: List<ExecutionIdentity?>) : ExecutionIdentity

private class Component(private val token: () -> Token) : ExecutionObserver {
    val starts = mutableListOf<ExecutionStart>()
    val identities = mutableListOf<Token>()
    val dependencies = mutableListOf<ProducerReference>()
    val completions = mutableListOf<ExecutionCompletion>()
    val ambient = ThreadLocal<Token?>()

    override fun captureCaller(): CallerContext = token()

    override fun onStart(start: ExecutionStart): StartedObservation {
        starts.add(start)
        val identity = token().also { identities.add(it) }
        return StartedObservation(
            object : ExecutionObservation {
                override fun onDependency(producer: ProducerReference) { dependencies.add(producer) }
                override fun onComplete(completion: ExecutionCompletion) { completions.add(completion) }
            },
            identity,
            ExecutionContext {
                val previous = ambient.get()
                ambient.set(identity)
                AutoCloseable { ambient.set(previous) }
            },
        )
    }
}

private class Composite(private val components: List<ExecutionObserver>) : ExecutionObserver {
    override fun captureCaller(): CallerContext = Contexts(components.map { it.captureCaller() })

    override fun onStart(start: ExecutionStart): StartedObservation {
        val observations = components.mapIndexed { index, component ->
            checkNotNull(component.onStart(start.mapCallers { caller ->
                caller.copy(
                    execution = (caller.execution as? Identities)?.values?.get(index),
                    context = (caller.context as? Contexts)?.values?.get(index),
                )
            }))
        }
        return StartedObservation(
            object : ExecutionObservation {
                override fun onDependency(producer: ProducerReference) {
                    observations.forEachIndexed { index, observation ->
                        observation.callbacks.onDependency(object : ProducerReference {
                            override val resolution: ProducerResolution? get() = producer.resolution?.project(index)
                            override fun subscribe(listener: (ProducerResolution) -> Unit): AutoCloseable =
                                producer.subscribe { listener(it.project(index)) }
                        })
                    }
                }
                override fun onComplete(completion: ExecutionCompletion) {
                    observations.forEach { it.callbacks.onComplete(completion) }
                }
            },
            Identities(observations.map { it.identity }),
            ExecutionContext {
                val installations = mutableListOf<AutoCloseable>()
                try {
                    observations.forEach { it.context?.install()?.let(installations::add) }
                } catch (failure: Throwable) {
                    installations.asReversed().forEach { it.close() }
                    throw failure
                }
                AutoCloseable { installations.asReversed().forEach { it.close() } }
            },
        )
    }

    private fun ProducerResolution.project(index: Int): ProducerResolution = when (this) {
        is ProducerResolution.Published -> (identity as Identities).values[index]?.let {
            ProducerResolution.Published(it)
        } ?: ProducerResolution.Unavailable(UnavailableReason.NOT_OBSERVED)
        is ProducerResolution.Unavailable -> this
    }
}

fun verifyComposite() = runBlocking {
    val first = Component(::FirstToken)
    val second = Component(::SecondToken)
    val result = withContext(Dispatchers.Unconfined) {
      canvas { installExecutionObserver { Composite(listOf(first, second)) } }.withMosaic { compose(singleTile {
        check(first.ambient.get() === first.identities.first())
        check(second.ambient.get() === second.identities.first())
        compose(singleTile { 42 })
    })
      }
    }
    check(result == 42)
    listOf(first, second).forEach { component ->
        check(component.starts.size == 2)
        check(component.completions.size == 2)
        check(component.starts.last().contributors.initiating.execution === component.identities.first())
        check(component.starts.all { it.contributors.initiating.context!!::class == component.identities.first()::class })
        val producer = component.dependencies.single()
        check((producer.resolution as ProducerResolution.Published).identity === component.identities.last())
        var notified: ExecutionIdentity? = null
        producer.subscribe { notified = (it as ProducerResolution.Published).identity }.close()
        check(notified === component.identities.last())
        check(component.ambient.get() == null)
    }
}

package org.buildmosaic.performance

import kotlinx.coroutines.runBlocking
import org.buildmosaic.core.injection.CanvasKey
import org.buildmosaic.core.injection.canvas
import org.buildmosaic.core.injection.withMosaic
import org.buildmosaic.core.multiTile
import org.buildmosaic.core.singleTile
import org.buildmosaic.core.source

private val CustomerIdKey = CanvasKey(Int::class, "customerId")
private val CatalogIdKey = CanvasKey(Int::class, "catalogId")
private val ComputeSeedKey = CanvasKey(Long::class, "computeSeed")

/** Reusable graph definitions. Tile identities stay stable across all requests. */
private object MosaicGraph {
  private val customer = singleTile {
    source<SimulatedServices>().signal("customer", source(CustomerIdKey).toString())
  }
  private val lightPreferences = singleTile {
    source<SimulatedServices>().signal("preferences", source(CustomerIdKey).toString())
  }
  val light = singleTile {
    val c = composeAsync(customer)
    val p = composeAsync(lightPreferences)
    val customerResult = c.await()
    val preferencesResult = p.await()
    LightResponse(customerResult, preferencesResult, "${customerResult.value}:${preferencesResult.value}")
  }

  private val account = singleTile {
    source<SimulatedServices>().signal("account", compose(customer).value.toString())
  }
  private val preferences = singleTile {
    source<SimulatedServices>().signal("preferences", compose(customer).value.toString())
  }
  private val authorization = singleTile {
    source<SimulatedServices>().signal("authorization", compose(account).value.toString())
  }
  private val aggregateSections = sectionNames.map { name ->
    singleTile {
      val a = composeAsync(account)
      val p = composeAsync(preferences)
      val auth = composeAsync(authorization)
      val simulator = source<SimulatedServices>()
      val first = simulator.signal(sectionService(name, 1), firstKey(name, a.await(), p.await(), auth.await()))
      val c = compose(customer)
      val second = simulator.signal(sectionService(name, 2), nextKey(name, 2, first, c))
      val third = simulator.signal(sectionService(name, 3), nextKey(name, 3, second, c))
      val fourth = simulator.signal(sectionService(name, 4), nextKey(name, 4, third, c))
      Section(name, listOf(first, second, third, fourth))
    }
  }
  val aggregate = singleTile {
    val branches = aggregateSections.map(::composeAsync)
    val c = composeAsync(customer)
    val a = composeAsync(account)
    val p = composeAsync(preferences)
    val auth = composeAsync(authorization)
    AggregateResponse(listOf(c.await(), a.await(), p.await(), auth.await()), branches.map { it.await() })
  }

  private val products = multiTile<Int, Product> { ids -> source<SimulatedServices>().products(ids) }
  val batching = singleTile {
    val sections = productSections(source(CatalogIdKey))
    // All six consumers ask through the same MultiTile; its request cache deduplicates keys.
    val pending = sections.map { ids -> composeAsync(products, ids) }
    val values = pending.flatMap { it.entries }.associate { (id, deferred) -> id to deferred.await() }
    batchingResponse(sections, values)
  }

  private val coalescingSections = (0 until 6).map { section ->
    singleTile {
      val ids = coalescingSection(source(CatalogIdKey), section)
      val values = compose(products, ids)
      ProductSection("section-$section", ids.map { values.getValue(it) })
    }
  }
  val coalescing = singleTile {
    val pending = coalescingSections.map(::composeAsync)
    val sections = pending.map { it.await() }
    BatchingResponse(sections, sections.sumOf { it.products.sumOf(Product::value) })
  }

  private val computeBranches = (0 until 6).map { branch ->
    singleTile { source<SimulatedServices>().cpu(source(ComputeSeedKey), branch) }
  }
  val compute = singleTile { computeResponse(computeBranches.map(::composeAsync).map { it.await() }) }
}

class MosaicExecutor(services: SimulatedServices) : ScenarioExecutor {
  private val applicationCanvas = runBlocking {
    canvas { instance(services) }
  }
  // Force graph initialization during application setup, before the first request.
  private val graph = MosaicGraph

  override suspend fun light(input: LightInput): LightResponse =
    applicationCanvas.withLayer { instance(CustomerIdKey, input.customerId) }
      .withMosaic { compose(graph.light) }

  override suspend fun aggregate(input: AggregateInput): AggregateResponse =
    applicationCanvas.withLayer { instance(CustomerIdKey, input.customerId) }
      .withMosaic { compose(graph.aggregate) }

  override suspend fun batching(input: BatchingInput): BatchingResponse =
    applicationCanvas.withLayer { instance(CatalogIdKey, input.catalogId) }
      .withMosaic { compose(graph.batching) }

  override suspend fun coalescing(input: BatchingInput): BatchingResponse =
    applicationCanvas.withLayer { instance(CatalogIdKey, input.catalogId) }
      .withMosaic { compose(graph.coalescing) }

  override suspend fun compute(input: ComputeInput): ComputeResponse =
    applicationCanvas.withLayer { instance(ComputeSeedKey, input.seed) }
      .withMosaic { compose(graph.compute) }
}

fun main() {
  val config = AppConfig.load()
  serve(config, MosaicExecutor(SimulatedServices(config)))
}

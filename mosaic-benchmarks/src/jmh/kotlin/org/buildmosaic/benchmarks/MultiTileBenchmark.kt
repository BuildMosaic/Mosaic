package org.buildmosaic.benchmarks

import kotlinx.coroutines.runBlocking
import org.buildmosaic.core.Mosaic
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.OperationsPerInvocation
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State

open class MultiTileState {
  lateinit var keys: List<Int>
  lateinit var requests: Array<Mosaic>

  protected fun prepareKeys(count: Int) {
    keys = MultiTileFixtures.keys(count)
  }

  protected fun prepareRequests(count: Int, cachedPercent: Int) {
    // Distinct requests prevent misses from turning into hits in the next operation.
    requests = runBlocking {
      Array(BATCH_SIZE) { MultiTileFixtures.request(count, cachedPercent) }
    }
  }
}

@State(Scope.Thread)
open class ColdMultiTileState : MultiTileState() {
  @JvmField @Param("1", "16", "128", "512") var keyCount: Int = 0

  @Setup(Level.Trial)
  fun keys() = prepareKeys(keyCount)

  @Setup(Level.Invocation)
  fun requests() = prepareRequests(keyCount, 0)
}

@State(Scope.Thread)
open class HalfCachedMultiTileState : MultiTileState() {
  @JvmField @Param("16", "128", "512") var keyCount: Int = 0

  @Setup(Level.Trial)
  fun keys() = prepareKeys(keyCount)

  @Setup(Level.Invocation)
  fun requests() = prepareRequests(keyCount, 50)
}

@State(Scope.Thread)
open class FullyCachedMultiTileState : MultiTileState() {
  @JvmField @Param("1", "16", "128", "512") var keyCount: Int = 0

  @Setup(Level.Trial)
  fun keys() = prepareKeys(keyCount)

  // Repeated reads cannot change a fully populated cache; prewarm once per trial.
  @Setup(Level.Trial)
  fun requests() = prepareRequests(keyCount, 100)
}

@State(Scope.Thread)
open class MultiTileBenchmark {
  @Benchmark
  @OperationsPerInvocation(BATCH_SIZE)
  open fun cold(state: ColdMultiTileState): Int =
    suspendBatch { index -> state.requests[index].compose(MultiTileFixtures.tile, state.keys).values.sum() }

  @Benchmark
  @OperationsPerInvocation(BATCH_SIZE)
  open fun halfCached(state: HalfCachedMultiTileState): Int =
    suspendBatch { index -> state.requests[index].compose(MultiTileFixtures.tile, state.keys).values.sum() }

  @Benchmark
  @OperationsPerInvocation(BATCH_SIZE)
  open fun fullyCached(state: FullyCachedMultiTileState): Int =
    suspendBatch { index -> state.requests[index].compose(MultiTileFixtures.tile, state.keys).values.sum() }
}

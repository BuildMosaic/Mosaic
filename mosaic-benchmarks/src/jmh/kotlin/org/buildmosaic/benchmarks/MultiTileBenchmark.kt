package org.buildmosaic.benchmarks

import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.OperationsPerInvocation
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State

open class MultiTileState {
  lateinit var keys: List<Int>

  protected fun prepareKeys(count: Int) {
    keys = MultiTileFixtures.keys(count)
  }
}

@State(Scope.Thread)
open class ColdMultiTileState : MultiTileState() {
  @JvmField @Param("1", "16", "128", "512") var keyCount: Int = 0

  @Setup(Level.Trial)
  fun keys() = prepareKeys(keyCount)
}

@State(Scope.Thread)
open class HalfCachedMultiTileState : MultiTileState() {
  @JvmField @Param("16", "128", "512") var keyCount: Int = 0

  @Setup(Level.Trial)
  fun keys() = prepareKeys(keyCount)
}

@State(Scope.Thread)
open class FullyCachedMultiTileState : MultiTileState() {
  @JvmField @Param("1", "16", "128", "512") var keyCount: Int = 0

  @Setup(Level.Trial)
  fun keys() = prepareKeys(keyCount)
}

@State(Scope.Thread)
open class MultiTileBenchmark {
  @Benchmark
  @OperationsPerInvocation(BATCH_SIZE)
  open fun cold(state: ColdMultiTileState): Int =
    suspendBatch {
      MultiTileFixtures.withRequest(state.keyCount, 0) {
        compose(MultiTileFixtures.tile, state.keys).values.sum()
      }
    }

  @Benchmark
  @OperationsPerInvocation(BATCH_SIZE)
  open fun halfCached(state: HalfCachedMultiTileState): Int =
    suspendBatch {
      MultiTileFixtures.withRequest(state.keyCount, 50) {
        compose(MultiTileFixtures.tile, state.keys).values.sum()
      }
    }

  @Benchmark
  @OperationsPerInvocation(BATCH_SIZE)
  open fun fullyCached(state: FullyCachedMultiTileState): Int =
    suspendBatch {
      MultiTileFixtures.withRequest(state.keyCount, 100) {
        compose(MultiTileFixtures.tile, state.keys).values.sum()
      }
    }
}

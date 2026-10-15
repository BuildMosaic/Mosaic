package org.buildmosaic.benchmarks

import kotlinx.coroutines.runBlocking
import org.buildmosaic.core.Tile
import org.buildmosaic.core.injection.withMosaic
import org.buildmosaic.core.singleTile
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.OperationsPerInvocation
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.infra.Blackhole

@State(Scope.Thread)
open class CpuTileState {
  @JvmField @Param("32", "256", "2048") var cpuTokens: Long = 0
  lateinit var cpuTile: Tile<Int>

  @Setup
  fun prepare() {
    cpuTile = singleTile {
      Blackhole.consumeCPU(cpuTokens)
      7
    }
  }
}

@State(Scope.Thread)
open class SingleTileBenchmark {
  private val trivial = singleTile { 7 }
  @Benchmark
  @OperationsPerInvocation(BATCH_SIZE)
  open fun coldTrivial(): Int = suspendBatch { emptyCanvas.withMosaic { compose(trivial) } }

  @Benchmark
  @OperationsPerInvocation(BATCH_SIZE)
  open fun completedCacheHit(): Int = runBlocking {
    emptyCanvas.withMosaic {
      compose(trivial)
      var result = 0
      repeat(BATCH_SIZE) { result += compose(trivial) }
      result
    }
  }

  @Benchmark
  @OperationsPerInvocation(BATCH_SIZE)
  open fun completedCacheHitAsync(): Int = runBlocking {
    emptyCanvas.withMosaic {
      compose(trivial)
      var result = 0
      repeat(BATCH_SIZE) { result += composeAsync(trivial).await() }
      result
    }
  }

  @Benchmark
  @OperationsPerInvocation(BATCH_SIZE)
  open fun smallCpu(state: CpuTileState): Int = suspendBatch { emptyCanvas.withMosaic { compose(state.cpuTile) } }
}

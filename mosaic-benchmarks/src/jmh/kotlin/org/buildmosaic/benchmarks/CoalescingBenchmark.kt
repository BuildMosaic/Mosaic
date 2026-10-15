package org.buildmosaic.benchmarks

import org.buildmosaic.core.Tile
import org.buildmosaic.core.injection.withMosaic
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.OperationsPerInvocation
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State

@State(Scope.Thread)
open class CoalescingBenchmark {
  @JvmField @Param("2", "4", "8", "16") var fanOut: Int = 0
  @JvmField @Param("0", "1", "2", "5", "10") var depthSeparation: Int = 0
  private lateinit var root: Tile<Int>

  @Setup fun prepare() {
    root = CoalescingFixtures.graph(fanOut, depthSeparation)
  }

  @Benchmark
  @OperationsPerInvocation(BATCH_SIZE)
  open fun siblingConsumers(): Int = suspendBatch { emptyCanvas.withMosaic { compose(root) } }
}

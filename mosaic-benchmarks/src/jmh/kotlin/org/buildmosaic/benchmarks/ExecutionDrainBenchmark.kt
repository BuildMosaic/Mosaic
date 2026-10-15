package org.buildmosaic.benchmarks

import org.buildmosaic.core.Tile
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.OperationsPerInvocation
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State

/** Fixed four-branch graph cases including scoped request teardown. */
@State(Scope.Thread)
open class ExecutionDrainBenchmark {
  private lateinit var diamond: Tile<Int>
  private lateinit var coalescing: Tile<Int>

  @Setup fun prepare() {
    diamond = GraphFixtures.diamond(4)
    coalescing = CoalescingFixtures.graph(4, 0)
  }

  @Benchmark
  @OperationsPerInvocation(BATCH_SIZE)
  open fun sharedDiamond(): Int = suspendBatch { composeAndDrain(emptyCanvas, diamond) }

  @Benchmark
  @OperationsPerInvocation(BATCH_SIZE)
  open fun siblingConsumers(): Int = suspendBatch { composeAndDrain(emptyCanvas, coalescing) }
}

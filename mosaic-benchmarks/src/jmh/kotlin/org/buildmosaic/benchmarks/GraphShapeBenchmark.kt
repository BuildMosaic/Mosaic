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
open class GraphShapeBenchmark {
  @JvmField @Param("1", "4", "16", "64") var size: Int = 0

  private lateinit var widthRoot: Tile<Int>
  private lateinit var depthRoot: Tile<Int>
  private lateinit var diamondRoot: Tile<Int>

  @Setup
  fun prepare() {
    widthRoot = GraphFixtures.width(size)
    depthRoot = GraphFixtures.depth(size)
    diamondRoot = GraphFixtures.diamond(size)
  }

  @Benchmark
  @OperationsPerInvocation(BATCH_SIZE)
  open fun width(): Int = suspendBatch { emptyCanvas.withMosaic { compose(widthRoot) } }

  @Benchmark
  @OperationsPerInvocation(BATCH_SIZE)
  open fun depth(): Int = suspendBatch { emptyCanvas.withMosaic { compose(depthRoot) } }

  @Benchmark
  @OperationsPerInvocation(BATCH_SIZE)
  open fun sharedDiamond(): Int = suspendBatch { emptyCanvas.withMosaic { compose(diamondRoot) } }
}

package org.buildmosaic.benchmarks

import kotlinx.coroutines.runBlocking
import org.buildmosaic.core.injection.withMosaic
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.State

@State(Scope.Thread)
open class MosaicCreationBenchmark {
  @Benchmark
  open fun scopedExecution(): Unit = runBlocking { emptyCanvas.withMosaic {} }
}

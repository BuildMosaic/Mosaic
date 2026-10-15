# Performance benchmarking

Mosaic separates application CPU, HTTP latency, memory, and startup measurements
from isolated JMH operations and untimed batching diagnostics. The
[public performance page](../performance/README.md) presents the results; this
guide covers methodology, reproduction, and the complete published point tables.

## Application benchmarks

The separate `performance/` build compares equivalent optimized Kotlin and Mosaic
applications using the same Ktor server, models, serialization, and simulated
services. Direct Kotlin uses ordinary structured concurrency, explicit batching,
and request-scoped deduplication where appropriate. The [workload descriptions](../performance/README.md#workloads) explain
what each route does. Run application commands below from the repository root.

### Methodology

Each measured variant runs in a fresh JVM. Only one application runs at a time.
Pairs alternate order: direct then Mosaic, followed by Mosaic then direct.
Both variants receive the same JVM configuration and deterministic request input.
The authoritative JDK 21 configuration uses G1, `-Xms512m -Xmx512m`, and
`-XX:ActiveProcessorCount=12`, matching the twelve logical application CPUs.
One request body is prebuilt per run; inputs 1, 7, 42, and 99 rotate between
repetitions and remain identical within each pair.

Before measurement, a separate wrk2 run warms the same application JVM for
15 seconds using the same route, profile, offered rate, connections, and input.
Warmup must finish without HTTP/socket errors before the measured generator
starts calibration. Warmup CPU and requests are excluded from measurement.
`warmup_seconds` is recorded in configuration; use `--warmup-seconds` for a case.

wrk2 generates constant-rate POST traffic. The harness waits for every worker's
calibration-completion message before sampling application CPU and RSS from Linux
`/proc`. Measurement continues until wrk2 exits naturally. The default requests
30 seconds of measurement; the total generator duration includes its approximately
10-second calibration plus one second of margin. The actual post-calibration
window is recorded and must be between the requested duration and two seconds
longer. Startup and calibration CPU are excluded.

CPU/request uses the post-calibration completed-request count corresponding to
that measurement window, rather than the cumulative full-run count. A lightweight
Lua callback independently records dispatch counts and 100 ms bins in an interior
window starting 10.5 seconds after generator launch. Count fidelity, sub-second
pacing, socket errors, HTTP errors, and scheduling-latency anomalies are checked.
Invalid runs stop the suite and remain in the raw output; incomplete or invalid
pairs are excluded from comparisons. Each pair attempt is checkpointed durably.
Resume the same session with `--resume --output <existing-directory>` and the
original command/configuration. Completed valid pairs are retained; an invalid or
incomplete attempt reruns both variants in the original alternating order, in a
new attempt directory. No result is overwritten. Configuration, source, JDK, JVM,
generator, affinities, and host fingerprint must match before resuming. The first
complete valid attempt for each repetition supplies the summary; all attempts and
selected execution paths remain recorded in `pair-attempts.json`.

Primary **HTTP latency** measures actual request dispatch to response completion
(wrk2's uncorrected histogram). It is accepted only when the independent pacing
checks pass. **Scheduling latency** measures intended request schedule to response
completion (wrk2's corrected histogram) and is retained as a generator diagnostic.
It is not presented as application response latency.

Choose offered rates and connection counts appropriate to the workload and host.
Estimate concurrency from requests/second × response time in seconds, and leave
connection headroom. Passing pacing checks does not establish maximum application
capacity. CPU affinity is optional; by default processes inherit the caller's
affinity. Advanced options are documented by `--help`.

### Running application benchmarks

Requirements: Linux, Java 21, Python 3, and `wrk2` on PATH. The Linux utilities
`stdbuf` and, when requesting CPU affinity, `taskset` must also be available.
Run from a clean checkout. Application distributions are built by the harness.

```bash
./gradlew clean build -p performance

# Six pairs for one workload at a chosen offered rate.
python3 performance/benchmark/benchmark.py case \
  --route aggregate --profile service --rps 800 --repetitions 6

# Operational smoke validation, not a performance result.
python3 performance/benchmark/benchmark.py suite \
  --config performance/benchmark/config/smoke.json

python3 performance/benchmark/benchmark.py startup --samples 20
python3 -m unittest discover -s performance/benchmark/tests -v
```

The checked-in `performance/benchmark/config/authoritative.json` defines the
publication matrix: six pairs per point, 15s warmup, 30s measurement, four wrk2 threads, and
128 connections. Assign the application and generator affinities explicitly:

```bash
python3 performance/benchmark/benchmark.py --app-cpus 0-5,12-17 \
  --load-cpus 6-9,18-21 --output performance/results/authoritative suite \
  --config performance/benchmark/config/authoritative.json
# If interrupted/invalid: inspect raw evidence, then rerun the same command
# with --resume before the suite subcommand. Only incomplete/invalid pairs rerun.
```

For a larger matrix, copy the smoke JSON to an ignored file under
`performance/results/`, choose route/profile/rate lists, and set repetitions and
measurement duration. Use at least 30 seconds of measurement for performance
comparisons. Keep JVM settings, connection counts, and CPU work identical between
variants. Preserve whole physical cores when assigning separate affinities.

### Interpreting results

CPU cost is reported in milliseconds and microseconds per request. The primary
framework-cost measure is the paired Mosaic − direct overhead in **µs/request**.
HTTP and scheduling latency are in milliseconds, memory is in MiB, and startup
is in milliseconds. Peak RSS covers the measured window; `VmHWM` separately
records the process lifetime high-water mark.

Summaries show direct and Mosaic medians plus the median, minimum, maximum,
and interquartile range of paired differences in the metric's own units. The
interquartile range (IQR) describes the middle half of those differences.
A positive difference means Mosaic uses more CPU or memory, or has higher latency. For throughput, a positive
difference means more completed requests per second. Inspect individual pairs
and integrity warnings before interpreting a difference as repeatable.

Relative percentages can be misleading when the direct implementation itself uses
only a few hundredths of a millisecond of CPU. Public results therefore emphasize
absolute overhead per request.

wrk2's timing accuracy is approximately ±1 ms; small latency differences may be
below its resolution. Service delays can obscure response-time differences while
CPU/request still exposes orchestration cost. Compare results only when both
variants perform equivalent logical work, and report startup separately.

### Startup measurement

Startup measures process launch to the first successful `/health` response, using
20 alternating fresh-JVM pairs and a 5 ms readiness poll. It is measured separately
from warmed application execution.

### Metadata and raw output

Generated sessions contain the Git revision, JVM options, resolved generator
binary/version/hash, CPU affinity, kernel command line, clocksource, CPU topology,
scaling driver/governor and energy preference, microcode, idle driver, NixOS
version where available, request configuration, process samples, logs,
and JSON, CSV, and Markdown summaries. Results belong in the ignored
`performance/results/` directory.

Application distributions are fingerprinted by jar hashes, and metadata records
the actual Java executable/version, exact JVM options, affinities, and Git state.
Optional Linux/sysfs values are recorded where available without changing the
host. Generated raw sessions, process samples, execution commands, and pair-level
results remain ignored.

## JMH runtime benchmarks

Mosaic uses JMH for repeatable JVM timing and allocation measurements. The internal
`mosaic-benchmarks` module depends on `mosaic-core` and is neither published nor
included in the BOM. JMH handles warmup, measurement, forks, and JVM profiling;
the suite does not time operations with a clock in benchmark code.

The suite measures request Mosaic creation from an already-built Canvas, SingleTile
cold execution and completed `compose()` and `composeAsync()` cache hits, small CPU
tiles, graph width/depth/shared dependencies, MultiTile cold/half-cached/fully
cached requests, and Canvas lookup and layer creation.
Control methods help distinguish changes in the JVM or coroutine bridge from
changes in Mosaic. Fixtures are deterministic, and ordinary tests verify their
results, shared-leaf deduplication, and MultiTile cache states.

This is a micro/milli-benchmark suite for runtime costs. It does not measure an
application workload, databases, or network services. The separate
[`performance/`](../performance/README.md) build contains application-level
Mosaic versus handwritten Kotlin comparison workloads and published results.
Published operation timings come from JMH; application CPU and latency results
come from the separate comparison build. Neither suite defines regression thresholds.

### Developer profile

Use JDK 21 and run all benchmarks with:

```bash
./gradlew :mosaic-benchmarks:jmh
```

The defaults use average time in microseconds per operation, three one-second
warmup iterations, five one-second measurement iterations, and two forks. Results
are written to `mosaic-benchmarks/build/results/jmh/results.json`. The 0.7.3 Gradle
task does not expose arbitrary JMH command-line arguments. Build its executable
jar, then use JMH's ordinary CLI to filter or change a local exploratory run:

```bash
./gradlew :mosaic-benchmarks:jmhJar
java -jar mosaic-benchmarks/build/libs/mosaic-benchmarks-*-jmh.jar \
  '.*SingleTileBenchmark.*' -bm avgt -wi 3 -i 5 -w 1s -r 1s -f 2 \
  -tu us -rf json -rff mosaic-benchmarks/build/single-tile.json
```

### Authoritative timing profile

Keep the short defaults for developer iteration. Publication runs use one JMH
thread, average µs/op, 10 × 1s warmup, 10 × 1s measurement, and two forks:

```bash
./gradlew :mosaic-benchmarks:jmhJar
mkdir -p performance/results
taskset -c 0-5,12-17 java -jar mosaic-benchmarks/build/libs/mosaic-benchmarks-*-jmh.jar \
  -bm avgt -tu us -t 1 -wi 10 -i 10 -w 1s -r 1s -f 2 -foe true \
  -jvmArgs '-Xms512m -Xmx512m -XX:+UseG1GC -XX:ActiveProcessorCount=12' \
  -rf json -rff performance/results/jmh-timing.json
```

Use a new ignored output path. Record the exact jar/source revision, JDK, JVM
options, affinity, host fingerprint, and command alongside JSON. Inspect fork
and iteration variance as well as JMH's confidence interval. Do not profile the
canonical timing run; collect allocation evidence in a separate execution.

JMH's GC profiler reports allocation as `gc.alloc.rate.norm` in bytes per
operation:

```bash
java -jar mosaic-benchmarks/build/libs/mosaic-benchmarks-*-jmh.jar \
  '.*SingleTileBenchmark.completedCacheHitAsync' -bm avgt -wi 3 -i 5 -w 1s -r 1s -f 2 \
  -tu us -prof gc -rf json \
  -rff mosaic-benchmarks/build/cache-hit-async-gc.json
```

For publication allocation evidence, use the authoritative timing profile above
with `-prof gc` in a separate execution and a separate output file. The current
[operation and allocation results](../performance/README.md#isolated-jmh-operations)
identify the source revision and fixture boundaries.

The module's `check` task compiles benchmark sources without executing them.
Run `:mosaic-benchmarks:jmh` to verify JMH generation and execution. The full
suite is not part of normal PR validation.

### Fixture boundaries

Suspending benchmarks batch 32 operations inside one outer `runBlocking` and use
JMH `@OperationsPerInvocation(32)`. Each request executes through `withMosaic`,
including teardown. Execution inherits the caller dispatcher; the coalescing
diagnostic explicitly selects `Dispatchers.Default` or its serial dispatcher.

SingleTile completed-cache measurements prewarm one scoped cache, perform 32
reads, then leave the scope. Async reads await the already-completed Deferred.
Creation, prewarming, and teardown are amortized across those reads. MultiTile
measurements create and optionally prewarm a fresh scoped request for each
operation; `cold`, `halfCached`, and `fullyCached` include this work in timing and
allocation. A one-key request cannot be half cached, so that variant starts at
16 keys. No benchmark retains an unowned Mosaic between invocations.

These fixture boundaries require a new baseline. Earlier results below describe
their recorded revision and must not be compared directly to the scoped fixtures.

Compare runs only on the same hardware, JDK, JVM options, and benchmark settings.
Hosted CI variance makes small percentage movements unsuitable as regression
signals. The suite has no hard regression thresholds.

### Full execution drain

`ExecutionDrainBenchmark` runs the four-branch shared diamond and four sibling
coalescing consumers with the same fixtures. Each operation uses `Canvas.withMosaic`
to create a fresh Mosaic owned by the calling coroutine, inheriting its dispatcher.
After composing the root, block exit cancels unfinished work and waits for cleanup.
These measurements include request creation and cleanup after result publication;
the ordinary graph benchmarks now include the same scoped teardown boundary. Fixture tests
verify cancellation and attached-child cleanup, and fresh caches across requests.

These scoped timings and allocations are not directly comparable with earlier
unscoped complete-and-join drain fixtures, which used `Dispatchers.Default` and
allowed unfinished children to complete naturally.

```bash
./gradlew :mosaic-benchmarks:jmhJar
java -jar mosaic-benchmarks/build/libs/mosaic-benchmarks-*-jmh.jar \
  '.*ExecutionDrainBenchmark.*' -bm avgt -tu us -t 1 \
  -wi 5 -i 5 -w 1s -r 1s -f 2
```

This measures a complete request execution rather than just the time until a
root result becomes available. Use it to check total lifecycle cost when changing
scheduling or cleanup. A reliable 0.5.0 full-drain baseline is unavailable, so
these measurements do not support a release comparison with 0.5.0.

### 0.7.0 scoped and cached baselines

Six existing cells were measured on 2026-10-05 from clean source
[`15e0be605c3a8df3e1bb18bb2f499be46ac034fe`](https://github.com/BuildMosaic/Mosaic/commit/15e0be605c3a8df3e1bb18bb2f499be46ac034fe).
These are fresh baselines for the scoped drain and trial-prewarmed cached-read
fixtures described above. Their changed dispatcher, shutdown, setup, and cache
locality boundaries prevent regression comparisons with the old fixtures.
No other JMH cells or application workloads were remeasured.

The host was `Nick-Workstation`: Ryzen 9 9900X (12 cores / 24 threads), NixOS
26.05, Linux 7.2.7, Zulu JDK 21.0.11+10-LTS (Zulu21.50+19-CA). Both runs used
CPU affinity `0-5,12-17`, one JMH thread, average µs/op, 10 × 1s warmup,
10 × 1s measurement, and two forks. JVM options were
`-Xms512m -Xmx512m -XX:+UseG1GC -XX:ActiveProcessorCount=12`.
Builds and browser checks were idle during measurement. Timing had no profiler;
allocation was collected separately using `gc.alloc.rate.norm`.

| Existing cell | Mean µs/op | JMH 99.9% CI half-width µs | Separate normalized allocation B/op |
| --- | ---: | ---: | ---: |
| Scoped shared diamond, size 4 | 2.742992 | ±0.004963 | 6730.8 |
| Scoped sibling consumers, fan-out 4 / depth 0 | 4.035755 | ±0.010311 | 13010.8 |
| Fully cached MultiTile, 1 key | 0.042369 | ±0.000341 | 400.0 |
| Fully cached MultiTile, 16 keys | 0.373559 | ±0.005400 | 1866.3 |
| Fully cached MultiTile, 128 keys | 2.608475 | ±0.020329 | 12170.3 |
| Fully cached MultiTile, 512 keys | 10.911606 | ±0.067532 | 47498.3 |

The scoped cells include request creation, result composition, cancellation of
unfinished work, and cleanup. Cached cells exclude trial setup/prewarming but
include the coroutine bridge, result-map assembly, and sum. These are elapsed
operation and allocation measurements, not HTTP latency or application CPU/request.

Both runs completed all six cells with two forks and ten warmup/measurement
iterations per fork: 120 measured samples per run, without execution failures or
non-finite values. Timing sanity is shown below; ranges are observed values,
not confidence intervals. Allocation varied by less than 0.01 B/op within each
cell across measured iterations. No suspicious performance signal was observed;
the changed fixtures do not support attributing differences from old tables to
runtime regression or improvement.

| Cell | Timing fork means µs/op | Timing iteration min…max µs/op |
| --- | --- | --- |
| Scoped shared diamond, size 4 | 2.742597 / 2.743387 | 2.733685…2.757989 |
| Scoped sibling consumers, fan-out 4 / depth 0 | 4.040662 / 4.030848 | 4.017745…4.055487 |
| Fully cached MultiTile, 1 key | 0.042674 / 0.042063 | 0.041608…0.043076 |
| Fully cached MultiTile, 16 keys | 0.367971 / 0.379147 | 0.365107…0.384515 |
| Fully cached MultiTile, 128 keys | 2.588060 / 2.628891 | 2.577965…2.640406 |
| Fully cached MultiTile, 512 keys | 10.983012 / 10.840199 | 10.802816…11.019080 |

Exact commands, run from the measured source checkout:

```bash
./gradlew :mosaic-benchmarks:jmhJar
out=performance/results/release-0.7.0-15e0be6
mkdir -p "$out"
selection='.*(ExecutionDrainBenchmark\.(sharedDiamond|siblingConsumers)|MultiTileBenchmark\.fullyCached)$'
taskset -c 0-5,12-17 java -jar mosaic-benchmarks/build/libs/mosaic-benchmarks-0.7.0-jmh.jar \
  "$selection" -bm avgt -tu us -t 1 -wi 10 -i 10 -w 1s -r 1s -f 2 -foe true \
  -jvmArgs '-Xms512m -Xmx512m -XX:+UseG1GC -XX:ActiveProcessorCount=12' \
  -rf json -rff "$out/timing.json" > "$out/timing.log" 2>&1
taskset -c 0-5,12-17 java -jar mosaic-benchmarks/build/libs/mosaic-benchmarks-0.7.0-jmh.jar \
  "$selection" -bm avgt -tu us -t 1 -wi 10 -i 10 -w 1s -r 1s -f 2 -foe true \
  -jvmArgs '-Xms512m -Xmx512m -XX:+UseG1GC -XX:ActiveProcessorCount=12' \
  -prof gc -rf json -rff "$out/allocation.json" > "$out/allocation.log" 2>&1
```

The measured JMH JAR SHA-256 was
`7a0f6e304fc8f2778b27a8a5857295a41effb0c87e867b6bbcee482d686fea89`.
Raw JSON, logs, commands, host/JDK/source metadata, and per-fork/iteration sanity
remain in the ignored `performance/results/release-0.7.0-15e0be6/` directory.

### Release runtime comparison

The [0.6.0 runtime results](releases/0.6.0.md#runtime-cost) compare released tag
`0.5.0` with the 0.6.0 runtime at
[`488ebad`](https://github.com/BuildMosaic/Mosaic/commit/488ebad7bdb5eb23137246bdca5c44e69f271962).
They use the existing graph, coalescing, SingleTile, and MultiTile fixtures with
tracing disabled. These elapsed operation timings are separate from the
application CPU and HTTP latency dataset below.

Timing runs used one JMH thread, two forks, five one-second warmup iterations,
and five one-second measurement iterations. Separate `gc.alloc.rate.norm` runs
used one fork and the same iteration lengths. Both versions used JDK 21.0.11,
a Ryzen 9 9900X, CPU affinity `0-5,12-17`, and
`-Xms512m -Xmx512m -XX:+UseG1GC -XX:ActiveProcessorCount=12`.
Reversed-order timing and allocation runs also covered the shared diamond,
sibling coalescing, and depth-16/64 chains. Width-16/64 and coalescing-depth-5/10
timing sweeps used one fork, three one-second warmups, and five one-second
measurements. Allocation for those sweeps used the separate five-warmup profile.
MultiTile allocation in this release comparison includes invocation setup and
request/cache prewarming; independent repeats expose variation between forks.

Timing cells are means from the first comparison order. Allocation ranges show
means from independent JVM sessions, including reversed comparisons where run;
they are not confidence intervals. Normalized allocation includes the fixture
setup described above.

| Operation | 0.5.0 µs/op | 0.6.0 µs/op | 0.5.0 B/op | 0.6.0 B/op |
| --- | ---: | ---: | ---: | ---: |
| Shared diamond, size 4 | 5.553 | 5.703 | 3,465–3,489 | 4,697–4,728 |
| Sibling coalescing, fan-out 4 / depth 0 | 7.841 | 7.997 | 11,417–11,448 | 13,047–13,269 |
| Cold trivial SingleTile | 3.090 | 3.079 | 819 | 1,042 |
| Depth 16 | 9.065 | 9.474 | 10,571–10,603 | 12,898–13,218 |
| Depth 64 | 27.717 | 28.937 | 42,219–42,251 | 52,354 |
| Width 16 | 8.382 | 8.787 | 8,763 | 11,011 |
| Width 64 | 23.799 | 24.777 | 32,722 | 41,576 |
| Coalescing depth 5, fan-out 4 | 12.240 | 12.770 | 21,804 | 24,808 |
| Coalescing depth 10, fan-out 4 | 17.591 | 17.942 | 31,869 | 36,739 |
| Cold 16-key MultiTile | 4.865 | 5.023 | 8,024 | 9,024–9,056 |
| Cached 16-key MultiTile | 0.470 | 0.447 | 10,064 | 10,632–11,048 |

Reversing comparison order measured diamond at
5.581 → 5.672 µs/op and sibling coalescing at
7.816 → 7.972 µs/op. Depth 16/64 repeated at
9.082/28.366 → 9.477/28.923 µs/op.
Cold SingleTile and cold 16-key MultiTile timing intervals overlapped between
releases. MultiTile allocation varies across forks and includes request/cache
preparation; it should not be interpreted as isolated cached-call allocation.

The same 0.6.0 runtime produced the following full-drain results. Ranges span
forward and reversed comparison sessions; there is no reliable 0.5.0 drain
baseline.

| Full-drain operation | 0.6.0 µs/op | 0.6.0 B/op |
| --- | ---: | ---: |
| Shared diamond, size 4 | 5.948–5.956 | 4,957–4,967 |
| Sibling coalescing, fan-out 4 / depth 0 | 8.326–8.373 | 13,512–13,534 |

## Coalescing diagnostics

`coalescing` uses cyclic windows of 12 products with offsets 0, 4, 8, 12, 16,
and 20 modulo 24, shifted by `catalogId * 1000`. Every reference contributes to
its section and the response total. Six stable sibling Tiles read the catalog
and request the same application-lifetime Products MultiTile independently;
the root only launches and awaits sections. Tracing stays off in measurements.

```bash
./gradlew :comparison-tests:batchDiagnostic -p performance
./gradlew :mosaic-benchmarks:coalescingDiagnostic
./gradlew :mosaic-benchmarks:coalescingDiagnostic --args="--serial"
./gradlew :mosaic-benchmarks:jmhJar
java -jar mosaic-benchmarks/build/libs/mosaic-benchmarks-*-jmh.jar '.*CoalescingBenchmark.*' \
  -bm avgt -tu us -wi 3 -i 5 -w 1s -r 1s -f 2
```

The permanent JMH fixture varies sibling fan-out (2/4/8/16) and extra Tile
dependencies separating later consumers from the first (0/1/2/5/10). Adjacent
branches overlap one of three keys; the two-branch case is ABC + CDE.
Diagnostics additionally cover external suspension. They verify every distinct
key is fetched once and report invocation counts and keys per invocation;
batch counts and deep-path coalescing are observations, not API guarantees.

The diagnostic defaults to the same `Dispatchers.Default` as JMH. `--serial`
uses one FIFO worker for reproducible discovery order; record the dispatcher
with the evidence. Default-dispatcher batch partitions may vary with scheduling.

### Observed graph behavior

Twenty graph samples per dispatcher also verified key completeness and uniqueness.
ABC + CDE and fan-out 2 combined into one call in all Default-dispatcher samples;
fan-out 4 took 1–2 calls, fan-out 8 took 2–5, and fan-out 16 took 5–8.
A serial FIFO dispatcher combined all same-depth fan-out cases into one call.
Depth 5, depth 10, and externally suspended consumers remained two calls with
both dispatchers. These are scheduling observations, not API guarantees.

## Dataset provenance

The original application, startup, JMH timing/allocation, and diagnostic results
below were collected from clean revision
[`129b0c73864e82047e4f8e0b861aee31e4e9dc78`](https://github.com/BuildMosaic/Mosaic/commit/129b0c73864e82047e4f8e0b861aee31e4e9dc78),
using the coalescing runtime. Identify this dataset by revision rather than
assuming it describes the latest release. The separate 0.6.0 comparison above
does not update these application or operation tables.

| Setting | Authoritative configuration |
| --- | --- |
| CPU / OS | AMD Ryzen 9 9900X, 12 physical cores / 24 threads; NixOS 26.05; Linux 7.2.7 |
| Java | Zulu JDK 21.0.11+10-LTS |
| JVM | `-Xms512m -Xmx512m -XX:+UseG1GC -XX:ActiveProcessorCount=12` |
| Application / JMH affinity | `0-5,12-17` (six whole physical cores / twelve logical CPUs) |
| Generator affinity | `6-9,18-21`; remaining physical cores available to the OS |
| wrk2 | 4.0.0-e0109df; SHA256 `8ea9a2225686179c62bf66c681f2c8a1e990da3450d1bc66c3c2fd98d44c01f7` |
| HTTP | Six pairs/point; 15s warmup; 30s requested measurement; four threads; 128 connections |
| Simulated work | Deterministic inputs; 20,000 CPU-work iterations; tracing off |

Generated raw sessions, fingerprints, artifact hashes, execution commands, and
pair-level results remain in the ignored `performance/results/` directory.
Measurements compare equivalent applications under the same conditions within
each pair; they do not measure maximum server capacity.

## Detailed result tables

All values below are unchanged from the published dataset. Application points
have six complete valid pairs. The primary number is the paired absolute CPU
difference; variant medians, ranges, and IQR show its context and variation.

### Application CPU results

| Workload/profile | RPS | Direct µs/request | Mosaic µs/request | Paired overhead µs/request | Paired min…max | Paired IQR |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| light/zero | 800 | 36.5 | 52.9 | +14.6 | 10.5…24.3 | 4.3 |
| light/zero | 1600 | 30.7 | 43.7 | +14.0 | 10.5…17.8 | 3.6 |
| light/service | 800 | 57.3 | 65.9 | +15.0 | 6.5…30.0 | 11.0 |
| batching/zero | 800 | 54.5 | 77.0 | +20.5 | 6.5…35.3 | 9.0 |
| batching/zero | 1600 | 45.4 | 63.6 | +19.1 | 15.2…21.9 | 4.5 |
| batching/service | 800 | 61.4 | 76.4 | +14.4 | 8.1…28.9 | 5.0 |
| aggregate/zero | 800 | 51.1 | 111.0 | +60.0 | 52.3…67.3 | 9.3 |
| aggregate/zero | 1600 | 40.3 | 89.7 | +48.2 | 42.6…59.2 | 4.7 |
| aggregate/service | 800 | 89.4 | 201.4 | +114.9 | 104.6…120.0 | 8.9 |
| aggregate/service | 1200 | 74.4 | 178.5 | +103.9 | 99.2…115.9 | 2.7 |
| coalescing/zero | 1600 | 37.2 | 98.8 | +61.3 | 57.6…66.1 | 3.1 |
| coalescing/service | 800 | 58.4 | 136.4 | +78.0 | 72.2…88.3 | 6.3 |
| compute/zero | 800 | 387.3 | 394.5 | +8.7 | -4.1…17.0 | 6.5 |
| compute/zero | 1600 | 365.8 | 371.2 | +6.2 | -9.3…17.9 | 6.1 |

All 168 runs formed complete valid pairs, with zero integrity warnings, socket
errors, or HTTP errors. No authoritative point required a retry or rate change.
Completed rates were 99.80–99.99% of offered load. A separate 6,400 RPS
`light/zero` qualification pair passed the stricter 100 ms pacing checks.

Per-request CPU generally falls at higher offered rates as fixed process costs
are amortized. Inspect individual pairs before interpreting a difference as
repeatable. The CPU-heavy control's individual differences straddle zero.

### HTTP latency results

These are medians of six run-level uncorrected HTTP percentiles, not percentiles
of a pooled distribution. Scheduling-corrected latency remains a separate
generator diagnostic.

| Workload/profile | RPS | Direct p50 / p95 / p99 ms | Mosaic p50 / p95 / p99 ms |
| --- | ---: | ---: | ---: |
| light/zero | 800 | 0.049 / 0.069 / 0.082 | 0.060 / 0.084 / 0.095 |
| light/zero | 1600 | 0.043 / 0.068 / 0.074 | 0.050 / 0.072 / 0.086 |
| light/service | 800 | 3.070 / 3.105 / 3.120 | 3.070 / 3.110 / 3.150 |
| batching/zero | 800 | 0.073 / 0.101 / 0.117 | 0.079 / 0.114 / 0.131 |
| batching/zero | 1600 | 0.061 / 0.083 / 0.093 | 0.068 / 0.089 / 0.105 |
| batching/service | 800 | 3.080 / 3.118 / 3.130 | 3.090 / 3.119 / 3.140 |
| aggregate/zero | 800 | 0.068 / 0.092 / 0.103 | 0.070 / 0.095 / 0.107 |
| aggregate/zero | 1600 | 0.053 / 0.076 / 0.088 | 0.066 / 0.089 / 0.107 |
| aggregate/service | 800 | 21.120 / 21.159 / 21.210 | 21.120 / 21.159 / 21.185 |
| aggregate/service | 1200 | 21.100 / 21.135 / 21.150 | 21.120 / 21.151 / 21.170 |
| coalescing/zero | 1600 | 0.048 / 0.071 / 0.081 | 0.068 / 0.090 / 0.105 |
| coalescing/service | 800 | 3.080 / 3.116 / 3.125 | 3.080 / 3.098 / 3.110 |
| compute/zero | 800 | 0.111 / 0.140 / 0.174 | 0.113 / 0.143 / 0.175 |
| compute/zero | 1600 | 0.113 / 0.175 / 0.227 | 0.111 / 0.169 / 0.220 |

### Process memory results

Values are medians of measured-window average RSS. Paired differences need not
match the subtraction of variant medians. Peak RSS covers the measured window;
`VmHWM` records the reported process-lifetime high-water mark.

| Workload/profile | RPS | Direct avg RSS MiB | Mosaic avg RSS MiB | Paired RSS difference MiB |
| --- | ---: | ---: | ---: | ---: |
| light/zero | 800 | 402.5 | 431.4 | +27.9 |
| light/zero | 1600 | 471.3 | 478.8 | +7.7 |
| light/service | 800 | 410.9 | 427.2 | +16.3 |
| batching/zero | 800 | 476.5 | 486.6 | +14.9 |
| batching/zero | 1600 | 475.4 | 485.4 | +5.1 |
| batching/service | 800 | 468.5 | 471.9 | +5.3 |
| aggregate/zero | 800 | 472.8 | 488.4 | +13.9 |
| aggregate/zero | 1600 | 472.8 | 476.5 | +6.0 |
| aggregate/service | 800 | 471.8 | 477.6 | +8.1 |
| aggregate/service | 1200 | 478.7 | 478.7 | -1.7 |
| coalescing/zero | 1600 | 466.6 | 488.4 | +23.5 |
| coalescing/service | 800 | 469.2 | 476.7 | +5.6 |
| compute/zero | 800 | 448.0 | 447.3 | +4.3 |
| compute/zero | 1600 | 474.1 | 466.6 | -7.0 |

Two runs reported a sampled peak less than 2 MiB above the reported high-water
mark; both counters remain in the raw data as approximate observations.

### JMH operation results

Canonical timing used one thread, average µs/op, 10 × 1s warmup, 10 × 1s
measurement, and two forks, with no profiler.

| Operation / fixture | Mean µs/op | JMH 99.9% CI half-width µs |
| --- | ---: | ---: |
| Mosaic creation | 0.012134 | ±0.000010 |
| SingleTile cold (includes request creation) | 3.1157 | ±0.1094 |
| SingleTile completed compose | 0.005152 | ±0.000031 |
| SingleTile completed composeAsync | 0.001087 | ±0.000003 |
| MultiTile cold, 1 key | 3.3201 | ±0.0658 |
| MultiTile cold, 16 keys | 5.1665 | ±0.1331 |
| MultiTile fully cached, 1 key | 0.0744 | ±0.0023 |
| MultiTile fully cached, 16 keys | 0.4254 | ±0.0060 |
| Shared diamond, size 4 | 5.6155 | ±0.0112 |
| Sibling coalescing, fan-out 4 / depth 0 | 7.8977 | ±0.0214 |

### Allocation results

These separate GC-profiler executions used the same iteration/fork profile.
Normalized values are rounded bytes/op; see the fixture boundaries above,
especially for invocation setup in MultiTile allocation measurements.

| Operation / fixture | Normalized allocation B/op |
| --- | ---: |
| Mosaic creation | 208.0 |
| SingleTile cold (includes request creation) | 818.8 |
| SingleTile completed compose | 8.3 |
| SingleTile completed composeAsync | 0.0 |
| MultiTile cold, 1 key (includes invocation setup) | 2271.5 |
| MultiTile cold, 16 keys (includes invocation setup) | 8021.9 |
| MultiTile fully cached, 1 key (includes invocation setup) | 2799.5 |
| MultiTile fully cached, 16 keys (includes invocation setup) | 10035.9 |
| Shared diamond, size 4 | 3467.5 |
| Sibling coalescing, fan-out 4 / depth 0 | 11459.1 |

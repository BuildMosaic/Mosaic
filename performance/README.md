# Performance

These benchmarks compare Mosaic with equivalent optimized Kotlin. Both use the
same Ktor server, models, serialization, and simulated services, and perform the
same logical downstream work. Direct Kotlin explicitly batches and deduplicates
work where appropriate.

## Results

The application and JMH tables below describe the measured source revision linked
under [About these results](#about-these-results).
The [0.6.0 runtime comparison](../docs/releases/0.6.0.md#runtime-cost) is a separate
targeted check; these application tables have not been remeasured for 0.6.0.

Mosaic's additional CPU cost depends on the graph:

| Workload | Paired median Mosaic CPU overhead |
| --- | ---: |
| Light | +14–15 µs/request |
| Batching | +14–21 µs/request |
| Aggregate | +48–115 µs/request |
| Coalescing | +61–78 µs/request |
| Compute | +6–9 µs/request |

These ranges summarize rounded paired medians across the measured rates and
profiles. They describe framework overhead, not maximum throughput. The
CPU-heavy control's individual differences straddle zero; its small median is
not evidence of a repeatable speed advantage for either implementation.

### Application CPU

Both applications perform the same logical work, including downstream calls and
response assembly. The numbers below come from six warmed direct/Mosaic pairs per
point. Overhead is the median of the paired Mosaic − direct differences, which
can differ from subtracting the two variant medians.

| Workload/profile | RPS | Direct µs/request | Mosaic µs/request | Paired overhead µs/request |
| --- | ---: | ---: | ---: | ---: |
| light/zero | 1600 | 30.7 | 43.7 | +14.0 |
| batching/zero | 1600 | 45.4 | 63.6 | +19.1 |
| aggregate/zero | 1600 | 40.3 | 89.7 | +48.2 |
| aggregate/service | 800 | 89.4 | 201.4 | +114.9 |
| coalescing/zero | 1600 | 37.2 | 98.8 | +61.3 |
| coalescing/service | 800 | 58.4 | 136.4 | +78.0 |
| compute/zero | 1600 | 365.8 | 371.2 | +6.2 |

These are representative points from the full matrix. All 168 measured runs
passed the integrity checks. Costs vary between pairs: `batching/zero` at 800 RPS
spans 6.5–35.3 µs.
The [full table and interpretation](../docs/performance.md#application-cpu-results)
retain those ranges and variation.

### HTTP latency

Service delays dominate elapsed response time. In `aggregate/service` at 800 RPS,
direct Kotlin used 89.4 µs CPU/request and Mosaic 201.4 µs, while both had median
HTTP latency of about 21.12 ms.

| Workload/profile | RPS | Direct p50 / p95 / p99 ms | Mosaic p50 / p95 / p99 ms |
| --- | ---: | ---: | ---: |
| light/service | 800 | 3.070 / 3.105 / 3.120 | 3.070 / 3.110 / 3.150 |
| aggregate/service | 800 | 21.120 / 21.159 / 21.210 | 21.120 / 21.159 / 21.185 |
| coalescing/service | 800 | 3.080 / 3.116 / 3.125 | 3.080 / 3.098 / 3.110 |

These are medians of run-level HTTP percentiles, not a pooled distribution.
wrk2's approximately ±1 ms timing accuracy does not support claims about the
sub-millisecond differences between variants. CPU cost and response latency
answer different questions.

### Memory and startup

Across the measured points, paired median RSS differences ranged from −7.0 to
+27.9 MiB. For `aggregate/service` at 800 RPS, average RSS medians were 471.8 MiB
direct and 477.6 MiB Mosaic, with a paired difference of +8.1 MiB.

RSS includes the JVM, heap, server, and application; it is not live-object size
or allocation/request. Both variants use a fixed 512 MiB initial heap. Process
residency varies, and RSS counters are approximate.

Across 20 startup pairs, direct median startup was **332.1 ms** and Mosaic
**337.0 ms**. The median paired difference was **+4.9 ms**, ranging from −0.9 to
+15.5 ms. Startup measures launch to readiness, separately from warmed requests.

### Isolated JMH operations

JMH measures individual operations, separately from application CPU/request:

| Operation / fixture | Mean µs/op | JMH 99.9% CI half-width µs |
| --- | ---: | ---: |
| Mosaic creation | 0.012134 | ±0.000010 |
| SingleTile cold (includes request creation) | 3.1157 | ±0.1094 |
| MultiTile cold, 1 key | 3.3201 | ±0.0658 |
| MultiTile cold, 16 keys | 5.1665 | ±0.1331 |
| MultiTile fully cached, 16 keys | 0.4254 | ±0.0060 |
| Sibling coalescing, fan-out 4 / depth 0 | 7.8977 | ±0.0214 |

Cold SingleTile execution includes request creation; MultiTile timing excludes
request/cache preparation. These fixtures measure elapsed operation time on this
JVM, not HTTP latency.
[Fixture boundaries and the timing profile →](../docs/performance.md#jmh-runtime-benchmarks)

The scoped-drain and trial-prewarmed cached-read fixtures have separate
[0.7.0 baselines](../docs/performance.md#070-scoped-and-cached-baselines).
Their changed measurement boundaries prevent comparison with the historical
fixtures in these tables.

### Allocation

Separate JMH GC-profiler runs measured normalized allocation:

| Operation / fixture | Normalized allocation B/op |
| --- | ---: |
| Mosaic creation | 208.0 |
| SingleTile cold (includes request creation) | 818.8 |
| SingleTile completed composeAsync | 0.0 |
| MultiTile cold, 16 keys (includes invocation setup) | 8021.9 |
| Sibling coalescing, fan-out 4 / depth 0 | 11459.1 |

Values are rounded bytes/op. The cached SingleTile async case returns its existing
Deferred directly. MultiTile allocation in this dataset includes invocation setup and fresh
request/cache preparation, so it is not the allocation cost of an isolated cached
`compose` call. Allocation profiling is separate from the timing measurements.

### Batching and coalescing

The two product workloads answer different questions. `batching` is an
already-optimal control: the root requests products directly and gets one backend
batch. In `coalescing`, six sibling Tiles independently discover overlapping
12-key sections covering 24 products. Direct Kotlin unions the sections into one
call; Mosaic uses the shared MultiTile without caller coordination.

Untimed diagnostics collected 20 samples per profile. Mosaic produced:

| Workload/profile | One backend call | Two backend calls |
| --- | ---: | ---: |
| batching/zero | 20/20 | 0/20 |
| batching/service | 20/20 | 0/20 |
| coalescing/zero | 10/20 | 10/20 |
| coalescing/service | 9/20 | 11/20 |

Direct Kotlin made one 24-key call in every sample. Every distinct product was
fetched exactly once in both implementations, without deliberate batching delay.
Exact batch boundaries depend on scheduling; deeper or externally delayed
consumers can form later batches.

## Workloads

| Workload | Equivalent application behavior |
| --- | --- |
| `light` | A small customer graph combining customer and preference data. |
| `aggregate` | A larger graph with shared dependencies, parallel fan-out, and multiple stages. |
| `batching` | Already-optimal control: the root directly issues six requests for the same 24 products, obtaining one backend batch. |
| `coalescing` | Six sibling Tiles independently discover overlapping 12-key sections (72 references, 24 distinct products). Direct Kotlin unions the sections into one batch; Mosaic delegates batching to the shared MultiTile. |
| `compute` | Deterministic CPU work, using 20,000 iterations by default. |

The `zero` profile adds no simulated service delay. The `service` profile adds
3 ms to simulated service calls so orchestration runs alongside asynchronous I/O.
`compute` has no simulated service delay, so only `compute/zero` is measured.

## About these results

Measurements used a Ryzen 9 9900X, Zulu JDK 21.0.11, G1, and Linux 7.2.7, from
[revision `129b0c7`](https://github.com/BuildMosaic/Mosaic/commit/129b0c73864e82047e4f8e0b861aee31e4e9dc78).
Absolute timings depend on hardware, JVM, and workload. Compare datasets only
when their hardware, warmup, heap, processor count, JDK, and sampling settings
match; differences between environments cannot be attributed solely to Mosaic.

[Detailed methodology, configuration, and reproduction →](../docs/performance.md)

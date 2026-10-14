---
title: 'Performance'
description: "Measured orchestration cost, application behavior, and the limits of Mosaic's published benchmark evidence."
---

Mosaic adds orchestration work around your service calls. Its cost depends on the graph. The published benchmark compares Mosaic with equivalent optimized Kotlin: the same Ktor server, models, serialization, simulated services, and logical downstream work. Direct Kotlin explicitly deduplicates and batches when appropriate.

The repository's [application report](https://github.com/BuildMosaic/Mosaic/blob/main/performance/README.md) and [technical methodology](https://github.com/BuildMosaic/Mosaic/blob/main/docs/performance.md) own the full evidence. This page summarizes it for evaluation.

## Application CPU per request

| Workload                       | Additional Mosaic CPU/request |
| ------------------------------ | ----------------------------: |
| Light                          |                      14–15 µs |
| Batching                       |                      14–21 µs |
| Aggregate graph                |                     48–115 µs |
| Independent sibling coalescing |                      61–78 µs |
| CPU-heavy                      |                        6–9 µs |

Ranges summarize rounded paired medians across measured rates and profiles, not confidence intervals or maximum throughput. Each point has six warmed direct/Mosaic pairs. The primary result is the median of paired Mosaic minus direct differences; it can differ from subtracting variant medians. Individual CPU-heavy differences straddle zero, so that small median does not establish a repeatable speed advantage.

Representative service-backed aggregate work at 800 RPS used 89.4 µs CPU/request in direct Kotlin and 201.4 µs in Mosaic, with a paired difference of 114.9 µs.

## HTTP latency is a different measurement

Both implementations had median HTTP latency around **21.12 ms** for that aggregate/service point. Most elapsed time came from asynchronous service delays.

| Service workload / rate | Direct p50 / p95 / p99      | Mosaic p50 / p95 / p99      |
| ----------------------- | --------------------------- | --------------------------- |
| Light / 800 RPS         | 3.070 / 3.105 / 3.120 ms    | 3.070 / 3.110 / 3.150 ms    |
| Aggregate / 800 RPS     | 21.120 / 21.159 / 21.210 ms | 21.120 / 21.159 / 21.185 ms |
| Coalescing / 800 RPS    | 3.080 / 3.116 / 3.125 ms    | 3.080 / 3.098 / 3.110 ms    |

These are medians of run-level uncorrected HTTP percentiles, not pooled percentiles. wrk2's approximately ±1 ms timing accuracy cannot establish the tiny sub-millisecond latency differences between variants. CPU cost and HTTP latency answer different questions; these data do not show a latency improvement.

## Shared keyed work in the application

Six sibling Tiles independently discovered overlapping 12-key product sections: 72 references covering 24 distinct products. Mosaic used the same Products MultiTile, while direct Kotlin explicitly combined sections into one call.

Across 40 untimed Mosaic samples, all 24 distinct products were fetched once; 19 samples used one batch and 21 used two. Direct Kotlin used one batch in every sample. Ready work was not deliberately delayed to collect keys.

This shows the measured benefit of caller-independent keyed reuse, with scheduling-dependent batch shape. Deeper or externally delayed consumers can form later batches. Exact partitions are observations, not API guarantees.

## Synthetic runtime timings

JMH isolates runtime operations. A separate comparison of released 0.5.0 and 0.6.0, with tracing disabled, measured:

| Operation                         | 0.5.0 elapsed µs/op | 0.6.0 elapsed µs/op |
| --------------------------------- | ------------------: | ------------------: |
| Four-branch shared diamond        |                 5.6 |                 5.7 |
| Four sibling coalescing consumers |                 7.8 |                 8.0 |
| 64-Tile chain                     |           27.7–28.4 |                28.9 |

These are elapsed operation timings, not application CPU/request or HTTP latency. Across comparison orders, diamond differed by about 0.09–0.15 µs/op, coalescing by 0.16 µs/op, and the 64-Tile chain by 0.6–1.2 µs/op. Cold trivial Tiles and cold 16-key batches had overlapping timing intervals.

Read the [release comparison](https://github.com/BuildMosaic/Mosaic/blob/main/docs/performance.md#release-runtime-comparison) for width/depth sweeps, fixture boundaries, fork variation, and full execution-drain measurements. Result publication can precede attached-child completion, so those boundaries matter.

The scoped-drain and trial-prewarmed cached-read fixtures have separate [0.7.0 baselines](https://github.com/BuildMosaic/Mosaic/blob/0.7.0/docs/performance.md#070-scoped-and-cached-baselines). Only those six existing cells were remeasured. Their changed measurement boundaries prevent regression comparisons with the old fixtures; application results and historical 0.5/0.6 tables remain unchanged.

## Allocation and process memory

Allocation was profiled separately from timing. The 0.6.0 diamond and sibling coalescing fixtures allocated about 1.2 KB/op and 1.6–1.9 KB/op more than 0.5.0, respectively. MultiTile normalized allocation in that comparison includes invocation setup and fresh request/cache preparation; it is not an isolated cached-call allocation cost.

Application RSS is another measurement: across published application points, paired median residency differences ranged from −7.0 to +27.9 MiB. RSS includes the JVM, heap, server, and application, under a fixed 512 MiB initial heap. It is not live-object size or allocation/request, and the counters are approximate.

## Methodology and scope

Application results identify measured source revision [`129b0c7`](https://github.com/BuildMosaic/Mosaic/commit/129b0c73864e82047e4f8e0b861aee31e4e9dc78). They were **not remeasured for 0.6.0**; its JMH release comparison is a separate dataset. Do not combine the two into a new application performance claim.

The application dataset used a Ryzen 9 9900X, Zulu JDK 21.0.11, G1, and Linux 7.2.7. Tests controlled CPU affinity, heap, warmup, service work, and paired sampling. All 168 measured runs passed integrity checks. Results apply to those workloads and settings; they do not measure maximum server capacity or production network I/O.

Absolute timings depend on hardware, JVM, graph shape, and service behavior. Keep CPU, latency, allocation, and process memory separate when evaluating Mosaic. Reproduce a representative workload for your own application using the [benchmark methodology and commands](https://github.com/BuildMosaic/Mosaic/blob/main/docs/performance.md).

# On-demand Kotlin compatibility harness

Maintainers can test an exact Kotlin compiler against candidate Mosaic artifacts:

```bash
./gradlew certifyKotlin -Pcompat.kotlin=2.4.20
./gradlew certifyKotlin -Pcompat.kotlin=2.3.21
```

Python 3.9+, the repository's build JDK, and an installed JDK 17 are required.
Gradle toolchain discovery selects JDK 17 for consumer execution, JUnit, and direct
compiler processes. If necessary, add
`-Porg.gradle.java.installations.paths=/absolute/path/to/jdk17` to the command.
The harness uses the checked-in Gradle wrapper; it has no distribution selector.

`compat.kotlin` is mandatory. Only exact stable Kotlin versions in
`major.minor.patch` format are accepted, such as `2.4.20`, `2.3.21`, or `2.2.0`.
RCs, Betas, snapshots, other preview versions, dynamic selectors, version ranges,
and omitted versions fail promptly with a diagnostic. There is no fallback to
another compiler. Resolution or compiler ABI failures fail the requested harness
scope; they are evidence of the candidate's actual boundary.

To run one scope:

```bash
./gradlew certifyKotlinRuntime -Pcompat.kotlin=2.3.21
./gradlew certifyKotlinAnalysis -Pcompat.kotlin=2.4.20
```

To probe the shared-source introspector ABI builds:

```bash
./gradlew :mosaic-compiler-plugin:introspectorJars
./gradlew certifyKotlinAnalysis -Pcompat.kotlin=2.2.0 -Pcompat.introspectorApi=2.3.0 -Pcompat.probe=true
./gradlew certifyKotlinAnalysis -Pcompat.kotlin=2.3.21 -Pcompat.introspectorApi=2.3.20 -Pcompat.probe=true
```

The default compiler API and alternate targets come from the measured mapping.
Each profile compiles one extraction source tree with the repository's fixed
build compiler and bundles the same Analysis core. Alternate jars have local
`kotlin-<API>` classifiers and publish as classifier-free
`mosaic-compiler-plugin-kotlin-<API>` artifacts.
Their manifest records `Mosaic-Compiler-API`; reproducible packaging permits
SHA-256 comparisons across runs. The harness snapshots the selected alternate
jar, records both control and selected identities, and checks those bytes again
after fixture execution. The measured mapping in
[`compiler-profiles.properties`](../mosaic-analysis-core/src/main/resources/org/buildmosaic/analysis/compiler-profiles.properties)
is the single owner of production compiler admission, public plugin selection,
shared-source ABI build targets, and the release matrix. It does not document
published release guarantees.

`compat.probe=true` forwards `-Dmosaic.analysis.compatibilityProbe=true` to the
actual isolated compiler JVM. It bypasses only compiler-version admission;
Core revision validation, extraction, metadata checks, and fixture assertions
remain active. Reports explicitly mark probing. A passing probe cannot enable
production support: add a tentative measured mapping, rerun without the probe,
and verify the real public plugin installation before proposing that mapping.

`harnessResult=pass` means every required area of the requested scope passed.
`harnessComplete=true` means `scope=all` and all four harness areas passed.
A successful scoped run sets `harnessComplete=false` and records unexecuted
areas as `not-run`. Neither result establishes full Mosaic compatibility
certification: Gradle/KGP integration and the remaining compatibility-matrix
dimensions are outside this harness. No command publishes external artifacts or
changes support policy. Production Kotlin/KGP version guards remain authoritative.

## Artifacts and isolation

The harness first builds unchanged Mosaic artifacts with the repository's fixed
Kotlin compiler. It installs the existing Maven publications into a fresh private
file repository under a source-fingerprint candidate version. Runtime consumers
resolve the real POMs and transitive dependencies from that repository. Exclusive
Mosaic resolution prevents fallback to released `0.7.0` jars; neither the resolver
nor the consumer uses `mavenLocal`, project substitution, friend paths, or metadata
error suppression. The report records coordinates, resolved dependency versions,
source fingerprints, and SHA-256 hashes of resolved jars.

Only consumers and extraction fixtures use the requested compiler. Runtime is
never rebuilt with it. A dependency-only build in `compatibility/` resolves exact
`kotlin-compiler-embeddable` artifacts, independently of KGP. Each compilation
launches a fresh JVM with one compiler distribution. Compiler jars are absent
from the JUnit process. The control uses the repository's compiler version and
default introspector. Control and selected runs use the same Runtime, Analysis
core, and fixture sources.

## Test ownership

| Area | Owner and evidence |
| --- | --- |
| Runtime | `compatibility/fixtures/RuntimeConsumer.kt` executes Canvas construction/lookups/layers, Tile and MultiTile composition, inline/reified calls, delegated properties, suspend/default interface methods, overloads, third-party Mosaic implementation, testing, and tracing on JVM 17. Existing public API and execution catalog fixtures compile unchanged; the observer composition consumer executes unchanged. No extractor is installed. |
| Direct compiler | The existing `mosaic-compiler-plugin` test corpus runs twice, against control and selected compilers. The shared compile helper switches only execution transport. Assertions own source-to-contract fidelity, VERIFIED/MISSING/UNVERIFIED outcomes, source locations, binary exports, and unknown boundaries. Plugin load or ABI failures fail this area. |
| Pure metadata | Existing analysis-core `Summary*Test` suites own decoding, codec validation, required fields, metadata compatibility, and the closed wire corpus. They run without invoking or depending on the selected compiler. |
| Compiler-produced metadata | Every extracted summary is retained and compared against the control. Only the compiler-version header and derived payload hash are omitted from comparison. Payloads, format/semantics, source locations, effect order, binary identities, limitations, and unknown boundaries must agree. Missing, extra, changed, or absent summaries fail comparison. |

Compiler-produced summaries record the executing compiler in their producer
header. Internal shards carry that provenance too; assembly rejects stale or
mixed compiler versions, and empty summaries use the configured compiler version.
The harness checks every raw producer header against the compiler's `-version`
output before comparing semantics. Earlier investigation logs retain their fixed
2.4.20 headers and must not be treated as proof of correct provenance. A passing
wire corpus alone does not prove compiler integration. Metadata format compatibility
has no Gradle-version dimension.

## Reports and failures

Each invocation writes fresh evidence under
`build/reports/compatibility/<requested-version>/<scope>/<invocation>/`:

- `report.json` and `report.md`: requested/actual compiler, control version,
  artifact identities, suites, per-area pass/fail/not-run, semantic comparison,
  commands, environment, and failure diagnostics.
- `resolution.json`: exact resolved coordinates, paths, and jar hashes.
- Stage logs and JUnit XML: compilation, execution, and assertion failures.
- `control/summaries/` and `selected/summaries/`: reproducible fixture inputs,
  one command/log per isolated compiler invocation, and compiler-produced summaries.
- `repository/`: private candidate publications used by that invocation.

Required failures or unavailable prerequisites yield a nonzero exit code. Areas
skipped after failed preparation or extraction remain `not-run` with diagnostics
and still fail the required scope. Metadata
coverage still runs when the selected compiler is unavailable or its extractor
cannot load. Required areas that fail or cannot run make `harnessResult=fail` and
`harnessComplete=false`; partial results never imply complete harness coverage.
Reports describe observations, not full Mosaic compatibility certification, an
automatic support announcement, or a compatibility registry.

## Validation boundary

These tasks have no connection to root `build`, `check`, or `test`, examples,
or ordinary PR validation. `compatibility/` is a standalone resolution build,
not an included project. Scheduled maintenance runs targeted checks only for
previously unhandled Kotlin releases.
The ordinary direct compiler fixtures retain their fixed in-process compiler.

To check the harness's reporting and comparison rules:

```bash
python3 -m unittest discover -s compatibility -p 'test_*.py' -v
```

The release-only `:mosaic-gradle-plugin:releaseCompatibilityTest` reuses the real
Maven installation fixture. It selects exact compiler/KGP pairs and verifies the
public plugins DSL, introspector artifact and bytes, actual compiler provenance,
VERIFIED/MISSING/UNVERIFIED results, and Java 17 execution. Release certification
runs the entire measured matrix through `certifyReleaseCompatibility`;
maintenance selects only the newly discovered version. Neither task is part of
ordinary builds. A targeted pass is not full release certification.

## Automatic Kotlin maintenance

[`kotlin-compatibility.yml`](../.github/workflows/kotlin-compatibility.yml) runs
daily at **07:23 UTC** and accepts manual dispatch. GitHub loads scheduled workflow
definitions from default branch `main`; its jobs check out `develop` and target
all maintenance PRs there. Integrate the workflow into `main` through the normal
release-quality branch process before expecting scheduled runs.

`compatibility/maintain.py discover --plan` reads Maven Central's
`kotlin-compiler-embeddable/maven-metadata.xml`, accepts only exact stable versions,
and checks compiler and KGP POMs/jars before scheduling expensive work. It compares
all stable patches against the measured mapping, including backports. Existing
mapped families remain eligible without automatic removal. New families must be
within 18 calendar months of their `.0` compiler POM publication (`Last-Modified`);
missing publication evidence defers investigation conservatively. No pending
versions means no JDK setup or compatibility jobs.

The investigation job builds one Runtime candidate for the batch. For each
pending version, maintenance tests those unchanged Maven artifacts on Java 17. Analysis attempts reuse those exact Runtime bytes, trying
existing introspectors in order of nearby proven compilers. Each attempt runs the
controlled probe with the existing direct-compiler corpus, metadata checks and
semantic comparison. A passing profile gets a tentative exact mapping, then must
pass the same checks with production admission and the real compiler/KGP public
plugin installation. Only after every existing profile fails does maintenance
attempt another API target from the same introspector sources. Build targets and
publications derive from the mapping; no additional kernel or plugin is created.

A verified result produces `automation/kotlin-<version>` and a focused mapping
PR. Failures produce an issue with attempted APIs, actual diagnostics and Actions
evidence; source adaptation requires implementation and review. Tentative mappings
are restored on failure. Neither result changes the public registry. Reports and
installation logs are uploaded as `kotlin-compatibility` for 30 days;
retain relevant evidence elsewhere if review takes longer.

PRs and issues titled `Kotlin compatibility: <version>` (including closed ones)
prevent repeated investigation. To retry a failed version after implementing its
adaptation, rename the prior issue to remove that exact title, then manually
dispatch maintenance. No automatically generated PR merges itself.

Automation uses `GITHUB_TOKEN` with contents, pull-request and issue write
permissions only where it proposes changes. GitHub signs and verifies generated
commits through `createCommitOnBranch`; no signing secret or long-lived credential
is needed. Enable **Settings → Actions → General → Workflow permissions → Allow
GitHub Actions to create and approve pull requests**. If PR creation is blocked,
maintenance leaves its branch and opens an actionable issue for a manual PR.
Approve any workflow runs GitHub holds on token-created PRs before reviewing CI.

## Compatibility ranges

[`compatibility/registry.json`](../compatibility/registry.json) records published
compatibility guarantees: `schemaVersion: 1`, nullable `latestCertifiedKotlin`, and
`runtime`/`analysis` maps keyed by stable release version. Both maps start empty.
Each release requires `minKotlin`; `maxKotlin` is optional. Runtime can also set
`minAnalysis`/`maxAnalysis`, and Analysis can set `minRuntime`/`maxRuntime`.
Bounds are inclusive and compared numerically. Missing `maxKotlin` uses the global
frontier; a null frontier establishes no Kotlin support for that release. Missing
relationship bounds impose no constraint. Runtime works alone; when Analysis is
selected, both Kotlin intervals and both relationship intervals must match.
Unknown releases are unsupported. Ranges promise continuous stable-version support,
including backported patches inside their boundaries; test those patches too.

```bash
python3 compatibility/registry.py validate
python3 compatibility/registry.py check --runtime 1.0.0 --kotlin 2.4.20
python3 compatibility/registry.py check --runtime 1.0.0 --analysis 1.0.0 --kotlin 2.4.20
```

After publication and GitHub Release creation both succeed, the release workflow
proposes `automation/registry-<train>-<version>` against `develop`. It uses the
successful full certification reports from that immutable release commit, bound
to the actual released version. Stable releases only qualify. The updater rejects
probes, failed/incomplete checks, mismatched compiler provenance or source revision,
changed Runtime bytes, missing production installation, and any untested stable
compiler inside the proposed interval. A newly visible backport can therefore
block the registry proposal until it is certified. Development probes and mapping
PRs cannot populate the registry.

The updater adds only the released train's Kotlin interval, with no inferred
Runtime/Analysis relationship bounds or historical entries. Before advancing the
global frontier, it gives every older implicit interval an explicit ceiling at its
previous frontier. An older binary's guarantee cannot expand merely because a
newer release passed. A release certified below an existing frontier gets its own
explicit ceiling. `registry.py` validates the result; human review owns approval.
Release evidence and logs are uploaded as `release-compatibility` for 30 days.

Maintain boundaries through reviewed PRs linked to actual certification evidence.
Any incompatible stable patch lowers the affected release's `maxKotlin` to the
last supported version before the failure; fix the gap before extending it. Test
reports retain failures; the registry stores guarantees only.
The [18-month support policy](../website/src/content/docs/reference/compatibility.md)
remains separate. This tool checks recorded ranges, not evidence or environments.

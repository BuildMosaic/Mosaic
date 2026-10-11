# Kotlin compatibility maintenance

Official support covers Kotlin minor families for 18 months after their first
stable release, including stable patches. Existing mappings and published
verification records do not expire automatically; older combinations can remain
usable outside that support window.

The daily `kotlin-compatibility.yml` workflow checks stable Kotlin releases from
2.2.0 onward, including backports. This discovery range preserves coverage of
older versions without extending the official support guarantee. It runs only
for unmapped versions without an open compatibility PR. Failed versions are
retried on the next scan; dispatch with an exact `kotlin` version to retry sooner.
Scheduled runs use `main`; manual runs use the selected branch. The workflow
must reach `main` before scheduling starts.

For a new compiler, maintenance tentatively selects an existing introspector,
runs the existing compiler corpus and real Gradle installation fixture, and
proposes the passing mapping. If needed, it tries a new compiler API target using
the same extraction sources. Production admission is always enforced. Failures
print diagnostic output in Actions logs and go directly to Codex Cloud.
The workflow creates no administrative issues, report archives, or automatic
merges or releases. The Actions run records a completed scan; a passing mapping
PR records newly verified development compatibility. Neither expands the public
matrix until a published release is verified, so no separate last-checked field
is needed.

## One-time configuration

- Enable GitHub Actions pull-request creation in repository workflow permissions.
- Set repository variable `CODEX_COMPATIBILITY_ENVIRONMENT` to the Codex Cloud
  environment ID for this repository. This is intentionally left unconfigured
  until an environment is selected.
- Set secret `CODEX_AUTH_JSON` to the authenticated Codex CLI's file credentials
  for that environment. Keep credentials out of the repository; renew them if
  authentication expires. The workflow uses a pinned CLI and removes the runner's
  credential file after submitting the task.

If the Codex target or credentials are absent, the workflow fails with a clear
configuration message and leaves diagnostics in its logs. It does not claim to
have launched a task.

## Local verification

Use Python 3.9+, the repository build JDK, and JDK 17 for consumers:

```bash
./gradlew certifyKotlin -Pcompat.kotlin=2.2.0
./gradlew certifyKotlinRuntime -Pcompat.kotlin=2.4.21
python3 compatibility/certify.py --scope analysis --kotlin 2.2.0,2.3.20,2.4.21
```

Pass `--java-installations=/path/to/jdk17,/path/to/jdk21` to Python, or
`-Porg.gradle.java.installations.paths=...` to Gradle when toolchain discovery
needs help. Omitting Python's `--kotlin` checks every mapped compiler.
`maintain.py investigate --kotlin VERSION` tries mappings locally without
publishing anything; it leaves the passing proposal in the reports directory.

Artifacts are built once per certification. Runtime consumers compile and run on
JDK 17; metadata tests and baseline extraction run once. Each selected compiler
runs the existing extraction corpus, whose summaries must match the baseline
except for compiler provenance and the derived checksum. Installation uses the
public plugins DSL and checks VERIFIED, MISSING, and UNVERIFIED behavior. Logs,
JUnit results, and summaries are under `build/reports/compatibility/` and
`mosaic-gradle-plugin/build/reports/introspector-installation/`.
Required failures and skipped or absent tests fail certification.

These expensive checks are separate from ordinary builds. Administrative tests
are intentionally limited to preventing false compatibility claims and lost
semantic differences; the compiler corpus owns extraction correctness.

## Release records

`compiler-profiles.properties` owns current compiler admission and introspector
build targets. `compatibility/registry.json` records published releases only:
Runtime maps each release to exact tested Kotlin versions; Analysis records its
exact Kotlin versions and a map from each tested Runtime release to the exact
Kotlin versions verified for that pair. Missing versions or relationships are
unknown. The website renders these records directly.

The release workflow certifies the complete mapped compiler set, verifies that
no stable Kotlin release from 2.2.0 is missing, and exercises the latest recorded
release of the other train through the installation fixture. Analysis needs a
published Runtime before its first release. To certify additional counterparts,
use `-Pcompat.counterparts=VERSION,VERSION` (or the workflow's `counterparts` input).
Only tested relationships enter the registry; historical combinations are not
inferred from version ordering. Analysis releases also compile and execute the
published Runtime on the new Kotlin versions, allowing its recorded support to
expand without another Runtime release. Existing pairings retain their own exact
compiler lists.

Publication and GitHub Release creation must succeed before the workflow
proposes a registry PR. The success record identifies the train, release, source
commit, compiler versions, and tested counterparts. Existing pairings never grow
merely because another release passed. Review and merge the registry PR before
releasing a dependent train. If proposal creation fails after publication, copy
the compact JSON result from the certification step's Actions log into a local
file and retry only `maintain.py record` with that evidence; do not republish
artifacts. Unpublished candidates establish no public guarantees.

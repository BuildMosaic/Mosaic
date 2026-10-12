# Kotlin compatibility maintenance

Official support covers Kotlin minor families for 18 months after their first
stable release, including stable patches. Existing ABI rules and published
verification records do not expire automatically; older combinations can remain
usable outside that support window.

The daily `kotlin-compatibility.yml` workflow discovers stable Kotlin releases
from 2.2.0 onward, including backports. For every recorded Runtime release and
every Runtime/Analysis combination, it checks missing certification facts against
the unchanged published artifacts. It also discovers missing combinations after
either train releases. Existing issues suppress repeated checks of that exact
compiler and artifact combination; dispatch with an exact `kotlin` version to
retry it. Both scheduled and manual workflow runs require `main`.

Compiler admission and certification have separate owners. Bundled
`compiler-profiles.properties` defines inclusive lower and exclusive upper ABI
bounds for each introspector. Future stable patches inside an interval can work
without an Analysis release. A new compiler family or ABI boundary needs a
reviewed implementation change. The daily workflow neither changes admission
rules nor builds replacement Mosaic artifacts.

Verification compiles and executes published Runtime consumers, runs the existing
compiler corpus and metadata fixtures against published introspectors, and uses
the real Gradle installation fixture. It reads ABI bounds from the published JAR,
not the checkout. Baseline extraction uses that artifact's default compiler API.
Conservative VERIFIED, MISSING, and UNVERIFIED behavior remains part of installation
certification.

Passing facts are appended directly to `compatibility/registry.json` on `main`.
The writer merges with the latest registry and checks the expected branch head
before committing, preserving concurrent release updates. Failed combinations
never enter the matrix; passing combinations can still be recorded when another
combination fails. App-authenticated commits trigger the website deployment.
An idempotent publication retry dispatches the website explicitly.

Failures print diagnostics in Actions logs and open or update one GitHub issue
per compiler and artifact combination, with the revision and reproduction command.
A passing retry closes its issue. Implementation fixes require normal review.
No compatibility PR, approval, Mosaic release, report archive, or last-checked
field is needed to record new certification.

## One-time configuration

Keep repository Issues enabled. The built-in `GITHUB_TOKEN` handles issues and
website dispatches. Direct registry writes to protected `main` use a dedicated
GitHub App:

1. Create and install a repository GitHub App with **Contents: read and write**
   on Mosaic only. It needs no webhook or pull-request permissions.
2. Add the App to the `main` ruleset's bypass list with **Always allow** so it can
   append certification without a PR or required PR checks. Keep the existing
   review requirements for implementation changes.
3. Store its numeric App ID in repository variable `KOTLIN_COMPATIBILITY_APP_ID`
   and its generated PEM private key in secret `KOTLIN_COMPATIBILITY_APP_PRIVATE_KEY`.
   Both workflows mint a short-lived installation token only after certification.
4. Merge the workflows to `main`, then dispatch Kotlin compatibility maintenance
   to verify provisioning. An empty registry does no artifact certification; the
   independent release workflows seed it after successful publication.

The writer modifies only `compatibility/registry.json`. If provisioning or website
deployment fails, inspect the Actions run and retry after correcting the setup.
Do not weaken branch protection for ordinary repository users or workflows.

## Local verification

Use Python 3.9+, the repository build JDK, and JDK 17 for consumers:

```bash
./gradlew certifyKotlin -Pcompat.kotlin=2.2.0
./gradlew certifyKotlinRuntime -Pcompat.kotlin=2.4.21
python3 compatibility/certify.py --scope analysis --kotlin 2.2.0,2.3.20,2.4.21
python3 compatibility/certify.py --kotlin 2.4.21 --runtime 1.0.0 --analysis 1.0.0
```

Pass `--java-installations=/path/to/jdk17,/path/to/jdk21` to Python, or
`-Porg.gradle.java.installations.paths=...` to Gradle when toolchain discovery
needs help. Omitting Python's `--kotlin` checks every discovered stable compiler.
`--runtime` selects unchanged published artifacts rather than building candidates;
`--analysis` adds a published Analysis release and installation verification.
An optional `--repository=/path/to/maven` permits local Maven publications for
pre-release validation. These commands do not update GitHub or the public matrix.

Candidate verification builds artifacts once; published verification builds
fixture classes and their dependencies without republishing artifacts. Runtime
consumers compile and run on JDK 17; metadata tests and
baseline extraction run once per artifact combination. Each selected compiler
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

`compiler-profiles.properties` owns compiler ABI admission and introspector build
targets. `compatibility/registry.json` records published certification only:
Runtime maps each release to exact tested Kotlin versions; Analysis records its
exact Kotlin versions and a map from each tested Runtime release to the exact
Kotlin versions verified for that pair. Missing versions or relationships are
unknown. The website renders these records directly.

The release workflow certifies every discovered stable compiler from 2.2.0 and
exercises the latest recorded release of the other train. Analysis needs a
published Runtime before its first release. To certify additional counterparts,
use `-Pcompat.counterparts=VERSION,VERSION` (or the workflow's `counterparts` input).
Only tested relationships enter the registry; historical combinations are not
inferred from version ordering. Analysis releases also compile and execute the
published Runtime on the new Kotlin versions, allowing its recorded support to
expand without another Runtime release. Existing pairings retain their own exact
compiler lists.

Publication and GitHub Release creation must succeed before the workflow
records certification on `main`. The success record identifies the train, release,
source commit, compiler versions, and tested counterparts. Existing pairings never grow
merely because another release passed. Record certification before releasing a
dependent train. If recording fails after publication, copy the compact JSON result
from the certification step's Actions log into a local
file and retry only `maintain.py record` with that evidence; do not republish
artifacts. Unpublished candidates establish no public guarantees.

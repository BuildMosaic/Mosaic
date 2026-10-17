# Release and compatibility recovery

For maintainers. Consumer guarantees belong in the
[compatibility reference](https://BuildMosaic.org/reference/compatibility/).

## Release a train

Prepare releases on protected `main`. Keep the development default in
`gradle.properties`; the workflow overrides the selected train's version.

1. Add a dated `CHANGELOG.md` entry and `docs/releases/TRAIN-VERSION.md`.
2. Validate the commit and create its immutable signed `runtime-VERSION` or
   `analysis-VERSION` tag, reachable from protected `main`.
3. Dispatch **Release** from `main`, then approve the `release` environment once:

   ```bash
   gh workflow run release.yml --ref main -f train=runtime -f version=VERSION
   ```

The workflow processes outstanding Kotlin publications, validates the tag, checks
its source and examples, and installs candidate Maven artifacts. Its dynamic
matrix checks every stable Kotlin publication in the requested ranges with up to
12 workers. Each worker tests exact Runtime/Analysis intersections through
`compatibility/check.sh`; the final job compares normalized compiler summaries
and runs compiler switching, build-cache and configuration-cache regressions.
Mandatory failures block publication. Failed optional Analysis extensions retain
their existing Runtime bound. Only publication receives release credentials and
approval. The GitHub Release, protected registry write and website follow it.
Record Runtime before dependent Analysis. Prereleases establish no registry claims.

Inputs are inclusive bounds: Runtime `minKotlin` inherits the previous minimum;
`maxKotlin` defaults to the latest stable publication. Analysis minima inherit
previous Analysis bounds; maxima default to the latest Runtime and Kotlin.
The first Analysis starts at the oldest recorded Runtime and latest Runtime's
Kotlin minimum. Override with `minKotlin`, `maxKotlin`, and, for Analysis,
`minRuntime` and `maxRuntime`. Verification never narrows a requested range.

Maintenance and releases share the `mosaic-compatibility` queue. Release calls
the maintenance workflow inside that queue; its nested discovery uses a distinct
concurrency group to avoid waiting on its caller. Publications are processed in
timestamp order with the rolling 18-month Kotlin family policy. Older-family
patches are events too. `compatibility/kotlin-checkpoint.txt` is the last processed
upstream publication timestamp, initially Kotlin 2.4.21's publication. A failed
event preserves its ranges and checkpoint; earlier successful events may record.
Never advance the checkpoint manually to skip events.

Publication is not atomic. After failure, inspect Maven Central, Plugin Portal,
and GitHub; complete only missing steps. Never move tags, republish coordinates,
or blindly rerun the release. If publication succeeded but registry recording
failed, download the **release-result** artifact (also printed in the final job).
Confirm the published GitHub Release and tag match its train, version and revision.
From an up-to-date `main`, retry only recording:

```bash
python3 compatibility/maintain.py record --evidence /path/to/release-result.json \
  --train runtime --version VERSION --revision TAG_COMMIT
```

Set `GH_TOKEN` to the registry App token, `GH_WORKFLOW_TOKEN` to a token authorized
to dispatch the website, `GITHUB_REPOSITORY=BuildMosaic/Mosaic`,
`GITHUB_SERVER_URL=https://github.com`, and `GITHUB_RUN_ID` to the failed run ID.
Evidence must match the release and unchanged compatibility facts. Otherwise
recertify. An idempotent recording retry also requests website deployment.

## Retry compatibility failures

Failure issues link Actions diagnostics and a reproduction command. Retry unchanged
published artifacts, including historical compilers outside routine support:

```bash
gh workflow run kotlin-compatibility.yml --ref main -f kotlin=KOTLIN_VERSION
```

A passing retry closes the issue. Blank `kotlin` processes new publications.
Failures retry whole events without partial claims. Fixes require review and,
when needed, a new release.

With JDK 17 and the build JDK available, check exact released artifacts locally:

```bash
export MOSAIC_JAVA_INSTALLATIONS=/path/to/jdk17,/path/to/jdk21
./compatibility/check.sh RUNTIME_VERSION - KOTLIN_VERSION
./compatibility/check.sh RUNTIME_VERSION ANALYSIS_VERSION KOTLIN_VERSION
```

For local release candidates, first run the selected modules'
`publishAllPublicationsToInstallTestRepository` tasks with
`-Pmosaic.runtime.version=VERSION` or `-Pmosaic.analysis.version=VERSION` and
`-Pmosaic.installTestRepository=/absolute/repository`. Set
`MOSAIC_COMPAT_REPOSITORY` to that repository before checking. Other dependencies
resolve from Maven Central. Gradle reports are in `compatibility/build/`;
compiler outputs are in `build/reports/compatibility/`; installation diagnostics
are in `mosaic-gradle-plugin/build/reports/introspector-installation/`.

## Credentials and writer recovery

The protected `release` environment holds `MAVEN_CENTRAL_USERNAME`,
`MAVEN_CENTRAL_PASSWORD`, `SIGNING_PRIVATE_KEY`, and `SIGNING_PASSWORD`.
Analysis also uses `GRADLE_PUBLISH_KEY` and `GRADLE_PUBLISH_SECRET`.

The registry GitHub App needs Contents write and an **Always allow** bypass on
`main`. Its numeric ID is `KOTLIN_COMPATIBILITY_APP_ID`; its PEM key is
`KOTLIN_COMPATIBILITY_APP_PRIVATE_KEY` in both `release` and `compatibility-writer`.
Both environments restrict deployment branches to exactly `main`; maintenance
has no approval gate. Rotate with `gh secret set ... --env ENVIRONMENT < key.pem`,
without printing the key; verify both environments before removing an old copy.
The workflow token handles issues and website dispatch. Keep Issues enabled;
diagnose these boundaries without broadening permissions or weakening protection.
Never commit credentials or local certification results.

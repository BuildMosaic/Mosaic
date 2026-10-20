# Releases and Kotlin compatibility

For maintainers. Consumer guarantees belong in the
[compatibility reference](https://BuildMosaic.org/reference/compatibility/).

## Release Runtime or Analysis

1. Prepare the release commit on protected `main`, with a dated
   `CHANGELOG.md` entry and `docs/releases/TRAIN-VERSION.md`.
2. Create and push a signed annotated `runtime-VERSION` or `analysis-VERSION`
   tag. GitHub must verify its signature. Never move a release tag.
3. Dispatch **Release** from `main`:

   ```bash
   gh workflow run release.yml --ref main -f train=runtime -f version=VERSION
   gh workflow run release.yml --ref main -f train=analysis -f version=VERSION
   ```

Dispatch authorizes publication if verification passes. There is no second
reviewer approval. The workflow checks the signed tag and protected-main ancestry,
checks the product and examples, and runs exact compatibility combinations on up
to twelve workers. Each worker builds the selected train into a temporary local
Maven directory and runs a real Java 17 consumer. Publishing credentials are
available only to the publishing job. It rechecks the tag and immutable
coordinates, publishes the selected train, and creates the GitHub Release.
Only then does the protected App writer update compatibility on `main`; that
commit refreshes marked current-release recommendations and triggers the website.
Prereleases do not add compatibility records. Publication succeeds before recording;
a failed `record` job leaves documentation unchanged and `recording-status` reports
that recovery is needed. Never rerun publication to repair documentation.

`compatibility/registry.json` owns stable certified release versions and inclusive
compatibility ranges, not publication timestamps. `compatibility/documentation.py`
selects each train by semantic version and checks their Runtime/Kotlin intersection.
Only its marked current-release blocks are refreshed; migration examples stay pinned.
An incompatible latest pair, or a pinned setup Kotlin version outside its intersection,
fails refresh for review rather than silently selecting older versions or changing
certification.
After an intentional documentation or consumer-fixture edit, run
`python3 compatibility/documentation.py --write`, then the same command without
`--write` to check freshness. `compatibility/consumer-matrix.csv` feeds the published
consumer test and the marked compatibility table. Java/Kotlin support policy is
explicit documentation, not inferred from this fixture or the build toolchain.
The website's `--check-publications` gate compares stable GitHub Releases with the
registry and fails on missing records in either direction; it does not certify them.
Release links lead to GitHub's actual publication metadata. Changelog dates are
release-note dates and are not presented as publication dates.

Optional range inputs are `minKotlin`, `maxKotlin`, `minRuntime`, and `maxRuntime`.
Kotlin minima inherit the previous release; the maximum defaults to the latest
stable Kotlin publication. Analysis Runtime minima inherit the previous Analysis
release and the maximum defaults to the latest Runtime. For the first Analysis,
minima come from the oldest Runtime and the latest Runtime's Kotlin minimum.
Explicit bounds are never narrowed to make verification pass. Checks use the
Runtime/Analysis Kotlin intersections. A Runtime release may extend existing
Analysis Runtime ranges; failed optional extensions retain their previous bounds.
An empty intersection does not extend a range.

## Daily Kotlin maintenance

**Kotlin compatibility maintenance** discovers stable Kotlin publications by
publication timestamp, including new patches on older minor families. Routine
checks retain the rolling 18-month window from each family's first stable release.
The workflow selects affected published releases and runs the same consumer
checks. A passing batch atomically updates ranges and `kotlin-checkpoint.txt`.
A failed batch raises an issue and leaves both unchanged. Publications outside
the window are skipped, while their publication checkpoint is processed.
Releases and maintenance share the `mosaic-compatibility` Actions concurrency queue.

To retry an exact publication, including one outside routine support:

```bash
gh workflow run kotlin-compatibility.yml --ref main -f kotlin=KOTLIN_VERSION
```

A passing retry closes its compatibility issue. Never advance the checkpoint by
hand to skip a failed publication.

## Local checks and recovery

With JDK 17 available, check published artifacts directly:

```bash
./compatibility/check.sh RUNTIME ANALYSIS_OR_DASH KOTLIN
```

For an unpublished candidate, use Gradle's
`publishAllPublicationsToInstallTestRepository` tasks with
`-Pmosaic.installTestRepository=/absolute/path/to/maven` and the selected train's
`-Pmosaic.runtime.version=VERSION` or `-Pmosaic.analysis.version=VERSION` override.
Set `MOSAIC_COMPAT_REPOSITORY` to that directory when running the checker.
`JAVA_HOME_17_X64` and `JAVA_HOME_21_X64` can identify explicit JDK installations.
Consumer workspaces and reports remain under `build/compatibility/`; failed matrix
jobs upload them. Compatibility issues link logs and exact reproduction commands.

Publication is non-atomic. On failure, inspect Maven Central, the Plugin Portal,
and GitHub before retrying. Never move the tag or republish existing coordinates.
Complete only the missing publication steps after establishing what succeeded.
If registry recording failed after publication, rerun the failed recording job
when its input snapshot is still current. If compatibility records changed,
rerun the affected checker combinations and submit a compatibility-only PR with
the verified bounds. Keep the checkpoint unchanged during release recovery.
Website failures can be retried through **Website**. Recovery is manual; a normal release rerun refuses
already published coordinates.

## Credentials

The existing `release` environment is restricted to branch `main` and contains
`MAVEN_CENTRAL_USERNAME`, `MAVEN_CENTRAL_PASSWORD`, `SIGNING_PRIVATE_KEY`,
`SIGNING_PASSWORD`, `GRADLE_PUBLISH_KEY`, and `GRADLE_PUBLISH_SECRET`.
The `compatibility-writer` environment is also restricted to branch `main` and
contains `KOTLIN_COMPATIBILITY_APP_PRIVATE_KEY`. Repository variable
`KOTLIN_COMPATIBILITY_APP_ID` identifies the App with Contents write permission
and the protected-main bypass. Its token is used only for the registry commit.

Rotate PEM keys with `gh secret set ... --env ENVIRONMENT < /path/to/app.pem`,
without printing credentials. Keep publication credentials out of matrix jobs.

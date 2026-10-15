# Release and compatibility recovery

For maintainers. Consumer guarantees belong in the
[compatibility reference](https://BuildMosaic.org/reference/compatibility/);
normal contributor setup belongs in [CONTRIBUTING.md](CONTRIBUTING.md).

## Release a train

Prepare releases on protected `main`, bringing ready features from `develop`
through review. Keep the development default in `gradle.properties`; the workflow
overrides the selected train's version.

1. Add a dated `CHANGELOG.md` entry and public notes at
   `docs/releases/TRAIN-VERSION.md` on the release commit.
2. Validate the commit and create its immutable `runtime-VERSION` or
   `analysis-VERSION` tag. The tagged commit must be reachable from `main`.
3. Dispatch **Release** from `main`, selecting the train and version:

   ```bash
   gh workflow run release.yml --ref main -f train=runtime -f version=VERSION
   ```

4. Confirm publication, the GitHub Release, the registry commit, and the website
   deployment. Record Runtime before releasing dependent Analysis. Prereleases
   do not establish registry records.

Release certification covers all discovered stable compilers from Kotlin 2.2.0;
routine maintenance rechecks the rolling 18-month support window, including backports.
The optional `counterparts` input selects published releases of the other train;
blank selects the latest. Runtime selections include every intervening published
release. Certification extends only the bounds it verifies and preserves historical
ranges.

Publication is not atomic. If a release fails, inspect Maven Central, the Plugin
Portal, and GitHub before retrying. Never move a release tag or republish released
coordinates. Complete only missing publication steps after understanding partial
success; do not rerun the entire release blindly.

If publication succeeded but registry recording failed, recover the compact JSON
from the certification step's Actions log into a local file. After confirming the
matching GitHub Release is published and its immutable tag matches the evidence
revision, retry **only** recording from an up-to-date `main` checkout, with
`GH_TOKEN` set to the registry App installation token and
`GH_WORKFLOW_TOKEN` set to a token authorized to dispatch the website.

Also set `GITHUB_REPOSITORY=BuildMosaic/Mosaic`,
`GITHUB_SERVER_URL=https://github.com`, and `GITHUB_RUN_ID` to the failed workflow
run's numeric ID. The recovery commit uses this context to link to its evidence.

```bash
python3 compatibility/maintain.py record --evidence /path/to/result.json \
  --train runtime --version VERSION --revision TAG_COMMIT
```

The evidence must match the train, version, and source commit. Do not substitute
results from another checkout or edit the registry to manufacture success. An
idempotent recording retry also requests website deployment.

## Retry compatibility failures

A failure issue contains the exact reproduction command and Actions diagnostics.
After resolving the cause, retry the unchanged published artifacts with an exact
compiler version, including historical compilers outside routine support:

```bash
gh workflow run kotlin-compatibility.yml --ref main -f kotlin=KOTLIN_VERSION
```

A passing retry closes its issue. An exact retry beyond a recorded boundary also
checks intervening discovered compilers before extending the range. Implementation
fixes require normal review and, when needed, a new release.

A failed, unavailable, or issue-blocked compiler holds that release's Kotlin bounds.
Failed Analysis batches remain failed even if isolated diagnostic retries pass.
Open issues suppress automatic retries until an explicit dispatch; failures inside
a claimed range require a fix. Fetch the latest `main` before merging registry
changes to preserve concurrent bot updates.

For local checks without GitHub updates, use `compatibility/certify.py --help` or
`./gradlew tasks --group compatibility`. Supply both JDK 17 and the repository
build JDK through `--java-installations` (Gradle:
`-Porg.gradle.java.installations.paths`). Logs and evidence are under
`build/reports/compatibility/`; installation diagnostics are under
`mosaic-gradle-plugin/build/reports/introspector-installation/`.

## Credentials and writer recovery

The protected `release` environment holds `MAVEN_CENTRAL_USERNAME`,
`MAVEN_CENTRAL_PASSWORD`, `SIGNING_PRIVATE_KEY`, and `SIGNING_PASSWORD`.
Analysis also needs `GRADLE_PUBLISH_KEY` and `GRADLE_PUBLISH_SECRET`.

The registry writer is a repository-installed GitHub App with Contents write
permission and an **Always allow** bypass on the `main` ruleset. Its numeric ID is
repository variable `KOTLIN_COMPATIBILITY_APP_ID`. Its PEM private key is the
`KOTLIN_COMPATIBILITY_APP_PRIVATE_KEY` environment secret in **both** `release`
and `compatibility-writer`. Both environments must restrict deployment branches
to exactly `main` (branch, not tag); compatibility maintenance has no approval
gate. Preserve the release environment's other protections.

When rotating or restoring the key, import the PEM file directly using
`gh secret set ... --env ENVIRONMENT < /path/to/app.pem`, without printing it.
Verify the secret exists in both environments before removing a previous copy.
The built-in workflow token handles issues and website dispatch, independently
of the App's registry writes. Keep repository Issues enabled. Diagnose access
failures at these boundaries; do not broaden permissions or weaken protection
for ordinary users or workflows. Never commit credentials or local evidence.

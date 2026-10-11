# Release procedure

Runtime and Analysis release independently. Prepare maintenance on `main`; merge
ready features from `develop` before releasing them. Keep `mosaic.version` as the
development default. The workflow overrides the selected train's version.

1. Add a dated changelog entry and `docs/releases/TRAIN-VERSION.md`.
2. Validate the release commit, then create its immutable `runtime-VERSION` or
   `analysis-VERSION` tag.
3. Dispatch **Release** from `main` with the train and version.
4. After a stable publication, review and merge the generated compatibility registry PR.
   Prereleases are verified but do not establish public compatibility records.

```bash
gh workflow run release.yml --ref main -f train=runtime -f version=1.0.0
```

Configure the protected `release` environment with `MAVEN_CENTRAL_USERNAME`,
`MAVEN_CENTRAL_PASSWORD`, `SIGNING_PRIVATE_KEY`, and `SIGNING_PASSWORD`.
Analysis also requires `GRADLE_PUBLISH_KEY` and `GRADLE_PUBLISH_SECRET`.
Enable GitHub Actions pull-request creation in repository workflow permissions.

The workflow checks the repository and examples, certifies every stable Kotlin
version from 2.2.0, and tests the latest recorded counterpart release. The optional
`counterparts` input selects additional published versions to certify. Runtime
can release before any Analysis exists; Analysis requires a recorded Runtime.
See [compatibility maintenance](../kotlin-certification.md) for local commands.

`releaseRuntime` publishes Core, Test, OpenTelemetry, and the Runtime BOM.
`releaseAnalysis` publishes the Gradle plugin, plugin marker, and introspectors,
then submits the plugin to the Plugin Portal. Publication is not atomic; inspect
partial success before retrying and never republish released coordinates.

After publication and GitHub Release creation, the workflow proposes only the
compatibility facts it verified. Evidence remains in the `release-compatibility`
Actions artifact for 30 days. If the registry proposal fails, retry only that
proposal from retained evidence; publication has already happened. No workflow
creates or moves release tags, merges PRs, or invents historical support.

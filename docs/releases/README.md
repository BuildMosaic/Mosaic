# Release procedure

Runtime and Analysis release independently. Keep `mosaic.version` in the root
`gradle.properties` as the development default. Release tags are `runtime-<version>`
or `analysis-<version>`, for example `runtime-1.0.0`. The workflow derives the
tag from the selected train and version and overrides only `mosaic.runtime.version`
or `mosaic.analysis.version` for publication. Existing historical tags remain unchanged.

Integrate the release-prep feature PR into `develop`, then merge a validated
`develop` → `main` release PR. Include a dated changelog heading using the full tag,
such as `## [runtime-1.0.0] - YYYY-MM-DD`, and a companion
`docs/releases/runtime-1.0.0.md`. Create the immutable train tag at that validated
main commit. Release preparation itself does not tag, publish, or deploy.

Run the manual **Release** workflow from `main` with the train and version:

```bash
gh workflow run release.yml --ref main -f train=runtime -f version=1.0.0
```

Configure a protected GitHub Environment named `release` before running it.
Provide these environment secrets there: `MAVEN_CENTRAL_USERNAME`,
`MAVEN_CENTRAL_PASSWORD`, `SIGNING_PRIVATE_KEY`, and `SIGNING_PASSWORD`.
Analysis publication also needs `GRADLE_PUBLISH_KEY` and `GRADLE_PUBLISH_SECRET`;
Runtime publication does not use Plugin Portal credentials. Do not put credentials
in repository files.

The job validates and checks out the tag's exact commit, sets up JDKs 17 and 21,
and runs `./gradlew check` and `./gradlew check -p examples`. It then runs
`./gradlew certifyReleaseCompatibility -Pcompat.scope=runtime` or `analysis`.
Certification covers all supported Kotlin patches and must succeed before publication;
synthetic certification versions are separate from release versions.

`releaseRuntime` publishes `mosaic-core`, `mosaic-test`, `mosaic-opentelemetry`,
and the Runtime-only `mosaic-bom` to Maven Central. `releaseAnalysis` publishes
`mosaic-gradle-plugin`, all three introspector artifacts, and the generated plugin
marker to Maven Central, then publishes `org.buildmosaic.analysis` to the Plugin
Portal after both module-level Central tasks succeed. All Analysis artifacts share
one version. Neither task publishes the other train.

The workflow does not wait for Maven Central public visibility. Publication is not
atomic: if a later step fails after artifacts are published, inspect what succeeded
before retrying; do not republish released coordinates.

After Gradle publication succeeds, the workflow creates a published GitHub
Release from the checked-in release-notes companion and dated changelog entry.
The workflow never creates or moves a tag.

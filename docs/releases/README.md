# Release procedure

Keep `mosaic.version` in the root `gradle.properties` as the version source. The
runtime BOM contains `mosaic-core`, `mosaic-test`, and `mosaic-opentelemetry`;
analysis artifacts stay outside it. The changelog entry must have a date before
tagging. Release tags are immutable and must already point to the validated source
commit.

Integrate the release-prep feature PR into `develop`, then merge a validated
`develop` → `main` release PR. Create the immutable `0.7.0` tag at that validated
main commit. Release preparation itself does not tag, publish, or deploy.

Run the manual **Release** workflow from `main` with the existing version tag:

```bash
gh workflow run release.yml --ref main -f tag=0.7.0
```

Configure a protected GitHub Environment named `release` before running it.
Provide these environment secrets there: `MAVEN_CENTRAL_USERNAME`,
`MAVEN_CENTRAL_PASSWORD`, `SIGNING_PRIVATE_KEY`, `SIGNING_PASSWORD`,
`GRADLE_PUBLISH_KEY`, and `GRADLE_PUBLISH_SECRET`. Do not put credentials in
repository files.

The single release job validates the exact tag, runs `./gradlew check` and
`./gradlew check -p examples`, then runs `./gradlew release`. That Gradle task
signs and automatically releases six Mosaic modules through Vanniktech Maven
Publish: `mosaic-core`, `mosaic-test`, `mosaic-opentelemetry`, `mosaic-bom`,
`mosaic-compiler-plugin`, and `mosaic-gradle-plugin`. It then submits
`org.buildmosaic.analysis` through the Plugin Portal.
The generated plugin marker is an additional Maven coordinate. The workflow does
not wait for Maven Central public visibility. If a later step fails after Maven
publication succeeds, do not rerun the workflow against published coordinates.

After Gradle publication succeeds, the workflow creates a published GitHub
Release from the checked-in release-notes companion and dated changelog entry.
The workflow never creates or moves a tag.

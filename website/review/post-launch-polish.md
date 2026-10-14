# Post-launch polish review

Scope: the homepage composition graph and native Dokka branding. The page narrative, documentation, canonical mark, and native layouts are preserved.

## Graph

The desktop diagram keeps all seven dependencies. Summary, customer, line items, and the shared OrderTile form a symmetric group: summary has a direct center route; the other two callers approach from opposite sides. Every edge begins and ends at a distinct node border anchor. Connectors neither cross nor share segments. The mobile text representation is unchanged.

## Native API branding

`buildSrc/src/main/kotlin/dokka.convention.gradle.kts` owns the shared HTML configuration. Both the aggregate and library conventions apply it. A Gradle `Sync` task derives `build/dokka-brand/logo-icon.svg` from `assets/mosaic-mark.svg`; the provider carries its task dependency into Dokka's `customAssets`. No independently editable alias is committed.

This uses [Dokka's supported header asset override](https://kotlinlang.org/docs/dokka-html.html#customize-assets). The same custom stylesheet, accessibility/navigation script, inherited-member setting, and footer reach standalone modules, aggregate output, packages, and declarations. The authored logo pseudo-element is removed; Dokka's native header and favicon use the supplied asset.

Both current page-color tokens and Dokka's older background token resolve to canonical navy `#0d192a` in dark mode and white in light mode. The compact controls strip also uses navy. Selected sidebar rows use a navy-mixed Mosaic blue so small white labels meet contrast requirements. Source-set controls receive group semantics in the existing accessibility script; their native buttons and behavior remain intact.

## Evidence

Fresh Chromium captures at 1440 × 1000. Before captures use main revision `a78ea22`; after captures use this PR's production build. The Canvas screenshots show an actual nested declaration, not the aggregate index.

| Surface    | Before, dark                                             | After, dark                                            | Before, light                                             | After, light                                            |
| ---------- | -------------------------------------------------------- | ------------------------------------------------------ | --------------------------------------------------------- | ------------------------------------------------------- |
| Graph      | [Before](screenshots/post-launch/graph-before-dark.png)  | [After](screenshots/post-launch/graph-after-dark.png)  | [Before](screenshots/post-launch/graph-before-light.png)  | [After](screenshots/post-launch/graph-after-light.png)  |
| Full hero  | [Before](screenshots/post-launch/hero-before-dark.png)   | [After](screenshots/post-launch/hero-after-dark.png)   | [Before](screenshots/post-launch/hero-before-light.png)   | [After](screenshots/post-launch/hero-after-light.png)   |
| Canvas API | [Before](screenshots/post-launch/canvas-before-dark.png) | [After](screenshots/post-launch/canvas-after-dark.png) | [Before](screenshots/post-launch/canvas-before-light.png) | [After](screenshots/post-launch/canvas-after-light.png) |

These screenshots are manual design evidence. Automated graph checks verify dependency relationships, node labels, anchors, orthogonal edges, and the absence of crossings or shared segments. API assertions verify the effective logo's fetched bytes against the canonical mark, page/header colors, User docs, theme switching and persistence, native search, sidebar navigation, and source links. Every generated HTML page in the aggregate and three standalone runtime publications is checked for branding references; all four publications' assets must match canonical sources. Browser QA has no pixel baselines; see the [contributor workflow](../README.md#browser-checks).

## Validation

- `./gradlew clean build`: passed, including runtime, compiler, analysis, and Gradle TestKit tests.
- `./gradlew clean build -p examples`: passed.
- `./gradlew dokkaGenerate`: passed for HTML and Javadoc; repeat generation reused configuration cache, with generation tasks up to date.
- Frozen pnpm install; Astro build/check; format check: passed.
- Link validation: 6,479 internal links/assets across 175 HTML pages.
- Browser suite: 22 tests passed. The accessibility/reflow matrix covers eight routes at 1440, 1920, 1280, 820, 390, and 430px, in light and dark (96 combinations), including module and nested API pages.
- Canonical publication checks: 303 HTML pages across the aggregate, core, test, and OpenTelemetry outputs.
- Focused Impeccable review: rendered graph and API captures inspected across the viewport/theme matrix; one mechanical scan returned no findings. The pass stayed within these surfaces.
- `git diff --check`: passed.

Review toolchain: Node 22.23.3, pnpm 10.34.6, JDK 21, Dokka 2.2.0, Playwright 1.58.2. Manual evidence: Google Chrome 154 on Linux. Visual captures reset native sidebar session state and move the pointer away from controls. Automated checks do not depend on those captures or their rendering environment.

## Remaining issues

No known issue remains in the requested graph or branding changes. Dokka still emits the existing unresolved `[K]` KDoc link warning for `TestMosaicBuilder.withFailedTile`; the examples build also reports an existing unchecked test cast. Neither warning is introduced by this PR. Browser checks and screenshots cannot prove every accessibility requirement.

Impeccable reports an existing unused `buildPath: code-led` configuration value. That tooling setting was left outside this focused PR; its supported value can be corrected separately.

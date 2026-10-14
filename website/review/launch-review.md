# Launch review

This records the first production website review, not a benchmark or runtime release.

## Research and direction

Inspected current [Micronaut](https://micronaut.io/), [Ktor](https://ktor.io/), [Quarkus](https://quarkus.io/), and [htmx](https://htmx.org/) in desktop browsers, with mobile inspection where useful. The useful common patterns were restrained navigation, immediate documentation access, real technical material, legible code, and explicit separation of adoption copy from teaching. Mosaic uses its own response-first narrative and canonical geometry rather than copying those sites.

Canonical brand PR #79 was green and mergeable, then squash-merged unchanged before this sibling worktree was created from current main. Branding remains sourced from `.impeccable/BRAND.md` and the vector masters in `assets/`.

The homepage moves through response composition, independent work, shared execution, keyed reuse, static architecture, tracing and tests, measured cost, HTTP-framework examples, and starting a project. Public Sans, Barlow, and JetBrains Mono are self-hosted. Light uses white/ink/navy; dark uses canonical navy. Code imports the runnable examples, and release display reads `gradle.properties`.

## Independent critique and polish

Project-local official Impeccable 4.5.0 was installed without hooks. Installed source/reference/agent files were inspected; its downloaded engine is ignored. Existing Mosaic skills remain unchanged.

Independent critique A judged the design product-specific, scoring 27/36 (Good). Its material findings concerned a missing diagram consumer, a dense shared-work opening, mobile graph enlargement, and navigation density. Independent detector reviewer B reused the single CLI scan (zero findings), then ran the live browser detector on homepage, Quick Start, and shared work. Six prose-width signals were valid; five signals concerned hidden content or expected article/TOC structure.

One bounded polish batch corrected the dependency diagram, made all three sharing consumers visible on mobile, led shared-work teaching with a request example, capped prose at 68ch, collapsed later sidebar groups, made code blocks consistently 14px, and linked the generated graph at full size with a navy surround. Native API received a user-docs return link and small accessibility corrections through supported custom assets.

The fresh finish reviewer matched the fixed identity, narrative, typography, ground, first viewport, documentation, and all 32 supplied captures. It requested one icon-consistency fix. Authored SVG navigation arrows replaced Unicode icons; recaptures showed the fix resolved, with a scoped **ship** verdict. No template fork or replacement visual direction was introduced. Below 384px, headers use the canonical mark rather than shrinking a lockup below the brand's minimum size.

## Browser and accessibility evidence

Actual Chromium/Playwright production-browser inspection covered 1440, 1920, 1280, 820, 390, and 430px in both themes. Pages: homepage, Quick Start, shared-work concept, performance tables, large Kotlin blocks, and native API entry. Fonts were awaited and lazy images loaded before full-page captures. Screenshot crops here come from those browser captures.

All 15 committed browser tests passed, covering 72 route/width/theme combinations with axe WCAG A/AA checks and no page-level horizontal overflow. Separate full-default axe audits of captured pages reported no violations. Manual/browser interaction checks covered skip links, keyboard focus, actual code scrolling, copy controls, menu/Escape, sidebar, search, theme persistence, anchors, native API search, and the generated graph. Reduced motion disables optional transitions. Initial homepage layout shift stays within 0.1. Additional 320px reflow checks covered homepage and Quick Start in both themes.

Automated checks do not prove every accessibility requirement. Native Dokka keeps its own search and theme behavior; supported custom JS repairs upstream control/heading semantics. Asset preparation adds missing inherited-overload fragment aliases beside native declaration anchors, without rendering KDoc or replacing native layout.

## Validation

- `./gradlew clean build`: passed, including published-installation TestKit coverage.
- `./gradlew clean build -p examples`: passed.
- Benchmark harness unit tests: 21 passed; no new performance measurements claimed.
- Dokka HTML, Javadoc, and root multi-project generation: passed.
- Repeated Dokka generation: configuration cache reused; tasks up to date.
- Clean cached Dokka generation: 12 tasks restored from build cache.
- Runtime/compiler Maven Javadoc jars inspected: nonempty HTML and entry pages. The Gradle plugin's existing placeholder Javadoc artifact is unchanged.
- Both canonical Quick Start programs compiled against published 0.6.0 and printed the documented output.
- Frozen pnpm install, Astro content/types, production build, and formatting: passed.
- Internal links/assets: 5,724 checked across 175 HTML pages.
- New Website workflow: actionlint passed. Whole-repository lint also reports an existing SC2126 style suggestion in unchanged `pr-checks.yml`.
- `git diff --check`: passed.

## Launch state

GitHub Pages is configured to publish using Actions, with custom domain `buildmosaic.org`. The Pages environment permits protected branches; workflow guards restrict actual deployment to canonical repository main. Website branches/PRs do not deploy.

DNS still has parking A records, and no DNS-provider credentials were available. The exact replacement records and subsequent HTTPS step are in [website/README.md](../README.md#dns-action-required). GitHub rejected HTTPS enforcement because the certificate does not exist yet. Launch requires the reviewed PR to merge, DNS to point to Pages, and HTTPS enforcement after certificate issuance. No website PR merge, Maven Central publication, or release was performed.

Representative captures are in [screenshots/](screenshots/). Full local matrix captures remain under ignored `.impeccable/review/`. There is no hosted PR preview; local `pnpm dev` or complete `pnpm api && pnpm build && pnpm preview` is supported.

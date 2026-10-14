# BuildMosaic.org development

The custom Astro homepage introduces Mosaic. Starlight owns user documentation, static Pagefind search, sidebar/contents, code presentation, and theme selection. Dokka owns native Kotlin API HTML under `/api/`. GitHub Pages serves the static output, with no repository base path and no application server.

## Local workflow

Use Node **22.23.3** (`.node-version`) and **pnpm 10.34.6** (`package.json`). Enable Corepack with your Node installation or install that pnpm version. From `website/`:

```bash
pnpm install --frozen-lockfile
pnpm dev
```

Astro prints the local URL. Markdown edits need no JDK. Canonical brand assets and the existing architecture graphic are copied by `prepare:assets`; never edit the ignored copies. The homepage imports real Kotlin example declarations at build time and derives its version from `gradle.properties`.

Build a complete production site, including API, with JDK 21:

```bash
pnpm api
pnpm check
pnpm build
pnpm check:links
pnpm format:check
pnpm preview
```

`pnpm api` runs the repository Gradle wrapper's `dokkaGeneratePublicationHtml`, then copies its native output. API files and built site output stay out of Git. Production builds use Astro's supported `--force` option to refresh content when shared syntax styles change, avoiding stale hashed code-style references. An ordinary `pnpm build` supports content editing without API generation, but the complete-site link check requires `/api/` to be present.

## Browser checks

```bash
pnpm exec playwright install chromium
pnpm qa
```

CI installs Chromium and OS dependencies automatically. Playwright tests all six requested widths and both themes across the homepage, Quick Start, a long concept, large Kotlin blocks, tables, and native API. They exercise menu, sidebar, search, copy, theme persistence, anchors, and keyboard access; axe checks WCAG A/AA rules. `MOSAIC_CHROMIUM_PATH` can select a locally managed Chromium on platforms whose system packages provide the browser. Normal development does not require it.

Browser QA uses DOM, CSS, asset, layout, and geometry assertions. API branding checks compare the effective logo bytes with the canonical mark and verify exact theme colors. Graph checks verify dependency relationships, node labels, anchors, orthogonal edges, and the absence of crossings or shared segments. There are no pixel baselines to regenerate; the same suite runs on Nix and Ubuntu without a special rendering environment.

Inspect actual rendered pages and keyboard behavior as well; automated checks do not prove every accessibility requirement. Store local captures under ignored `.impeccable/review/`. Representative launch evidence in `review/` is for manual review, not executable test baselines.

## Content ownership

- `src/content/docs`: canonical tutorials, concepts, and user how-to guides.
- Root README: GitHub adoption narrative and concrete capability proof.
- Module READMEs: purpose, installation, orientation, and important constraints.
- Public KDoc: exact API documentation.
- `performance/README.md` and `docs/performance.md`: full benchmark evidence.
- Analysis/compiler module READMEs: detailed tooling semantics and boundaries.
- Root `PRODUCT.md`, `DESIGN.md`, and `.impeccable/surfaces`: persistent product and design context, maintained with the installed project-local Impeccable skill.

Add a feature by editing its canonical Markdown guide and, if adoption-relevant, a short homepage example. Keep benchmark and compatibility claims tied to their source. There is no Markdown synchronization system or speculative docs versioning.

## Dokka and deployment

Dokka 2.2.0 uses the supported v2 Gradle plugin. Root `dokka` dependencies aggregate core, test, and OpenTelemetry; module convention settings own source links and publication Javadoc. `api/mosaic.css` and `api/mosaic-api.js` provide small brand and accessibility corrections through Dokka's supported custom asset mechanism. The asset preparation step adds missing inherited-overload fragment aliases emitted by Dokka 2.2.0. It preserves the native declarations and layout, and the complete link check verifies the result. There is no template fork or KDoc renderer.

The Website workflow validates PRs targeting `main` or `develop`: it installs from the lockfile, validates content/types/format, generates API and Javadoc, builds, checks links, and runs Playwright/axe. It uploads and deploys a Pages artifact only from canonical main. Repository Kotlin PR validation remains independent. Pages uses Actions as its publication source; its environment allows protected branches. `public/CNAME` contains `buildmosaic.org`.

## DNS action required

At the domain's DNS provider, replace the apex parking A records `15.197.225.128` and `3.33.251.168` with GitHub Pages' four records:

| Type  | Name  | Value                   |
| ----- | ----- | ----------------------- |
| A     | `@`   | `185.199.108.153`       |
| A     | `@`   | `185.199.109.153`       |
| A     | `@`   | `185.199.110.153`       |
| A     | `@`   | `185.199.111.153`       |
| CNAME | `www` | `buildmosaic.github.io` |

Remove conflicting apex A/AAAA records. If publishing IPv6, use GitHub's optional AAAA records `2606:50c0:8000::153` through `2606:50c0:8003::153`. Do not use an apex CNAME or a wildcard. Keep the site's configured custom domain as the apex; GitHub can redirect the configured `www` record to it.

After DNS validates and GitHub issues the certificate, enable **Enforce HTTPS** in Pages settings (or the Pages API). It cannot be enabled while the custom-domain certificate is absent. DNS credentials were not available during implementation. See [GitHub's custom-domain instructions](https://docs.github.com/en/pages/configuring-a-custom-domain-for-your-github-pages-site/managing-a-custom-domain-for-your-github-pages-site).

The website PR is not deployed before review/merge. Once merged, the protected main workflow publishes automatically. No release or Maven artifact publication is part of this website workflow.

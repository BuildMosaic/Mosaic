# BuildMosaic.org development

Astro owns the homepage, Starlight owns user guides, and Dokka supplies native Kotlin API HTML under `/api/`. GitHub Pages serves the static output.

## Local workflow

Use Node from `.node-version` and pnpm from `package.json`. From `website/`:

```bash
pnpm install --frozen-lockfile
pnpm dev
```

Markdown editing needs no JDK. For a complete production build, use JDK 21:

```bash
pnpm api
pnpm check
pnpm build
pnpm check:links
pnpm format:check
```

`pnpm api` generates Dokka and copies its output. `prepare:assets` copies the canonical logo SVGs from `assets/` and the architecture graphic. Edit their sources; generated API, assets, and `dist` stay uncommitted. The homepage imports runnable Kotlin examples.

Latest stable Runtime and Analysis versions come from `compatibility/registry.json` through `src/data/releases.ts`. Use MDX expressions for release labels and Starlight’s `Code` component for versioned installation snippets. The Analysis example uses its certified Runtime maximum. Keep historical release links, example tags, and toolchain pins explicit. GitHub READMEs link to these installation pages or use a labeled version placeholder.

Teaching belongs in `src/content/docs/`; API contracts belong in KDoc. Keep maintainer procedures out of both. Root and module READMEs provide GitHub orientation rather than duplicating the teaching guides.

## Browser checks

```bash
pnpm exec playwright install chromium
pnpm qa
```

Playwright covers responsive layout, themes, navigation, search, copy, keyboard access, and axe checks. It also checks diagram geometry and native API branding; there are no screenshot baselines. `MOSAIC_CHROMIUM_PATH` can select a local Chromium. Inspect the rendered pages as well; automated checks cannot establish every accessibility requirement. Keep temporary captures in ignored `.impeccable/review/`.

Dokka customization uses supported assets in `api/`, without a template fork. `prepare-assets.mjs` supplies inherited-overload fragment aliases missing from Dokka's output; the complete-site link check validates them. The Website workflow validates PRs and deploys protected `main`. Publication or credential recovery belongs in the [maintainer runbook](../.github/MAINTAINING.md).

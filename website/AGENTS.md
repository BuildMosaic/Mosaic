# BuildMosaic.org

The website is an isolated Astro/Starlight project, using pnpm and Node 22.23.3. The homepage is native Astro/CSS; authored docs are Markdown/MDX; `/api/` is native Dokka HTML. Keep client JavaScript small and preserve Starlight's sidebar, search, theme selector, contents, and code-copy machinery.

## Ownership

- `src/content/docs/`: canonical user tutorials, concepts, and how-to guides.
- Root README: GitHub adoption and concrete feature proof, with site links.
- Module READMEs: purpose, install, first-use orientation, important constraints.
- KDoc: canonical API descriptions. Gradle owns Dokka generation and publication.
- `performance/README.md` and `docs/performance.md`: full benchmark evidence.
- `../.impeccable/BRAND.md`: canonical identity and logo usage; `../assets/`: logo SVG masters. `prepare:assets` copies the SVGs unchanged; do not edit the ignored copies in `public/brand`.

Use `../.agents/skills/public-docs/SKILL.md` for homepage, overview, adoption, and public proof; use `technical-docs` for tutorials, concepts, and guides. Preserve runtime versus analysis compatibility and benchmark dataset distinctions.

Use the project-local Impeccable skill for design work. Read root PRODUCT.md, DESIGN.md, and `.impeccable/surfaces/` before changing visual decisions. New-feature Markdown edits do not require a redesign. Keep canonical colors and supplied logo geometry. Do not turn the homepage into interchangeable feature cards.

## Commands

From `website/`, with Node from `.node-version` and pnpm from `package.json`:

```bash
pnpm install --frozen-lockfile
pnpm dev
pnpm check
pnpm api
pnpm build
pnpm check:links
pnpm format:check
pnpm qa
```

`pnpm api` requires JDK 21 and the repository Gradle wrapper. Normal Markdown editing does not need API generation. For a complete site build, generate API first. Generated assets, API HTML, `.astro`, QA output, and `dist` stay uncommitted.

## QA and deployment

Inspect the running site in a browser, not just source or generated screenshots. Batch first inspection and one bounded polish pass across 1440/1920 desktop, 1280 laptop, 820 tablet, and 390/430 mobile in light/dark. Review homepage, Quick Start, long concepts, tables, large code, API, menu/search/theme, keyboard/focus, copy/anchors, overflow, image scaling, reduced motion, and loading stability. Run Playwright/axe and internal link validation. Record actual findings and limits.

GitHub Actions validates site PRs and deploys only protected canonical main to GitHub Pages. The apex site is `https://BuildMosaic.org`, with no repository base path. Do not deploy branches, add analytics, or commit build output. Update DNS instructions in README when hosting requirements change.

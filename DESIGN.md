---
name: Mosaic
description: Composable backend orchestration for Kotlin
colors:
  m-navy: "#0d192a"
  m-blue: "#007def"
  m-orange: "#fd993e"
  m-ink: "#181818"
  m-white: "#ffffff"
  m-secondary: "#b6c7dc"
  m-fragment: "#142c48"
  m-slate: "#263244"
  light-panel: "color-mix(in srgb, var(--m-secondary) 16%, var(--m-white))"
  light-line: "color-mix(in srgb, var(--m-secondary) 70%, var(--m-white))"
  primary-hover: "color-mix(in srgb, var(--m-orange) 85%, var(--m-white))"
typography:
  display:
    fontFamily: "'Barlow', sans-serif"
    fontSize: "clamp(3.5rem, 6.8vw, 6rem)"
    fontWeight: 700
    lineHeight: 1.08
    letterSpacing: "-0.025em"
  headline:
    fontFamily: "'Barlow', sans-serif"
    fontSize: "clamp(2.2rem, 3.3vw, 3.2rem)"
    fontWeight: 600
    lineHeight: 1.08
    letterSpacing: "-0.025em"
  title:
    fontFamily: "'Barlow', sans-serif"
    fontSize: "1.6rem"
    fontWeight: 600
    lineHeight: 1.08
    letterSpacing: "-0.025em"
  body:
    fontFamily: "'Public Sans Variable', sans-serif"
    lineHeight: 1.7
  docs-body:
    fontFamily: "'Public Sans Variable', sans-serif"
    fontSize: "1.025rem"
  label:
    fontFamily: "'Public Sans Variable', sans-serif"
    fontSize: "0.95rem"
  button:
    fontFamily: "'Public Sans Variable', sans-serif"
    fontSize: "0.95rem"
    fontWeight: 600
    lineHeight: 1.7
  code:
    fontFamily: "'JetBrains Mono Variable', monospace"
    fontSize: "0.875rem"
    lineHeight: 1.75
  reuse-key:
    fontFamily: "'JetBrains Mono Variable', monospace"
    fontSize: "0.82rem"
  caption:
    fontFamily: "'Public Sans Variable', sans-serif"
    fontSize: "0.82rem"
    lineHeight: 1.7
rounded:
  control: "4px"
  code-frame: "0.25rem"
spacing:
  compact-gap: "0.6rem"
  action-gap: "0.8rem"
  inline-gap: "1rem"
  nav-compact-gap: "1.2rem"
  content-gap: "1.5rem"
  stack-gap: "2rem"
  narrow-column-gap: "2.2rem"
  medium-column-gap: "3rem"
  column-gap: "5rem"
  story-mobile: "3rem"
  story-tablet: "3.7rem"
  story-desktop: "5.5rem"
components:
  button-primary:
    backgroundColor: "{colors.m-orange}"
    textColor: "{colors.m-navy}"
    typography: "{typography.button}"
    rounded: "{rounded.control}"
    padding: "0.85rem 1.25rem"
  button-primary-hover:
    backgroundColor: "{colors.primary-hover}"
  button-secondary:
    typography: "{typography.button}"
    rounded: "{rounded.control}"
    padding: "0.85rem 1.25rem"
  navigation-link:
    typography: "{typography.label}"
  reuse-key:
    typography: "{typography.reuse-key}"
    padding: "0.3rem 0.6rem"
  shared-producer:
    backgroundColor: "{colors.m-orange}"
    textColor: "{colors.m-navy}"
    padding: "0.8rem 1.7rem"
  benchmark:
    width: "100%"
---

# Design System: Mosaic

## Overview

**Creative North Star: "Response composition"**

Response composition gives Mosaic its visual language: real Kotlin, dependency relationships, and restrained architectural surfaces. The site feels like a mature backend framework reference, with compact technical copy, substantial examples, and clear routes into teaching and API lookup.

Canonical blue and orange pieces sit within navy or white reading surfaces. Barlow supplies the display voice, Public Sans supports sustained reading, and JetBrains Mono keeps identifiers and source precise. Flat panels and thin rules supply structure; the visual system does not require animation to explain a relationship.

This is a source-derived record, not a replacement identity. `.impeccable/BRAND.md` owns general brand decisions; `PRODUCT.md` owns product context; `.impeccable/surfaces/` owns each surface's narrative and direction. `website/AGENTS.md` owns contributor workflow. The tokens above document repeated implemented values; they are not additional CSS variables. When changing a durable visual rule, update its owning stylesheet and this record together. Ordinary Markdown additions inherit the system without a redesign.

**Key Characteristics:**

- Canonical supplied vector identity and theme-aware lockups.
- Code and static dependency diagrams as visual material.
- Unequal explanatory columns that stack into readable mobile sequences.
- Shared typography and palette across a native homepage, Starlight teaching docs, and a lightly branded native Dokka reference.

## Colors

Blue connection lines and orange calls to action sit within restrained navy or white architectural surfaces. The primitive names match the shared CSS variables, omitting only the leading `--`.

### Primary

- **Mosaic Orange** (`m-orange`): primary CTA background, shared-producer emphasis, selection, and dark-theme text accent. Use navy text on orange.
- **Mosaic Blue** (`m-blue`): dependency edges, reuse-key border emphasis, and Starlight's primary accent. It also belongs to the immutable logo geometry.

### Neutral

- **Navy** (`m-navy`): dark page surface, architecture figure background, and text on orange.
- **Ink** (`m-ink`) and **White** (`m-white`): light reading text and surface, dark reading text, and the two supplied wordmark variants.
- **Secondary Copy** (`m-secondary`): muted dark-theme text and diagram notes.
- **Fragment Navy** (`m-fragment`): dark tonal panels and the light-theme heading/link accent.
- **Slate** (`m-slate`): dark dividers and muted light-theme reading text.
- **Light Panel** and **Light Line**: source-native `color-mix()` surfaces; retain the shared expressions rather than adding approximate hex copies.

`theme.css` owns the semantic assignments:

| CSS role        | Dark          | Light         |
| --------------- | ------------- | ------------- |
| `--page-bg`     | `m-navy`      | `m-white`     |
| `--page-text`   | `m-white`     | `m-ink`       |
| `--page-muted`  | `m-secondary` | `m-slate`     |
| `--page-panel`  | `m-fragment`  | `light-panel` |
| `--page-line`   | `m-slate`     | `light-line`  |
| `--page-accent` | `m-orange`    | `m-fragment`  |

The homepage and Starlight share `data-theme` and the `starlight-theme` storage key. Dokka retains its native theme switch and receives palette/focus overrides from `website/api/mosaic.css`.

**The Canonical Identity Rule.** Use the supplied vector mark and outlined wordmarks unchanged; semantic surfaces may adapt to the theme, but logo geometry and logo colors do not.

## Typography

**Display Font:** Barlow, sans-serif fallback; bundled weights 600 and 700.

**Body Font:** Public Sans Variable, sans-serif fallback.

**Label/Mono Font:** Public Sans for navigation and controls; JetBrains Mono Variable, monospace fallback for source and identifiers. Fonts are locally bundled, not fetched from a runtime font service.

**Character:** Compact display headings carry the positioning; comfortable reading text supports technical explanation. Monospace communicates actual program material rather than decorative labels. There is no universal geometric type ratio: the implemented roles and responsive overrides are purpose-specific.

### Hierarchy

- **Display:** frontmatter `display`, used for the homepage h1. At widths up to 1100px it becomes 5.1rem; up to 850px, 4rem with a 16ch maximum; up to 600px, 3.5rem, line-height 1.04, and a 12ch maximum.
- **Headline / Title:** frontmatter `headline` and `title` for homepage h2/h3. Mobile h2 becomes 2.3rem. The closing CTA h2 has its own `clamp(2.7rem, 4.5vw, 4.2rem)`; do not treat that exception as the default section heading.
- **Body:** homepage body inherits the browser root size and uses the recorded line-height. Hero descriptor is 1.35rem/1.4 at weight 500, hero copy 1.13rem, and context 0.96rem; mobile reduces them to 1.2rem, 1.02rem, and 0.9rem. Homepage section prose is usually limited to 44ch, growing to 65ch when stacked.
- **Documentation:** body uses `docs-body`; authored h1–h3 use Barlow with tracking -0.015em while Starlight owns the heading sizes. Direct prose/list/blockquote children have a 68ch maximum. The guide content container is 48rem.
- **Code:** highlighted source uses the recorded `code` role: 0.875rem (14px at the default root size), with line-height 1.75, at both desktop and mobile. `website/ec.config.mjs` explicitly owns the block font size and family. Inline homepage code is separately scoped to `:not(pre) > code` at 0.86em; reuse keys are 0.82rem. Expressive Code owns syntax colors through `github-dark`/`github-light` and the actual copy controls. Keep inline sizing separate from preformatted source so blocks retain their configured readable size.
- **Label / Caption:** navigation and buttons use their frontmatter roles; small notes use `caption`. Technical diagram nodes are 14px JetBrains Mono; diagram notes are 14px Public Sans. The benchmark table uses tabular numerals and monospace numeric results.

**The Three Voices Rule.** Use Barlow for authored display headings, Public Sans for reading and controls, and JetBrains Mono for source and identifiers. Keep the native Dokka type machinery intact.

## Layout

The native homepage pairs explanation with evidence, usually in unequal columns (0.82fr / 1.18fr) with the recorded desktop column gap. Its centered reading wrapper is `min(1240px, calc(100% - 112px))`; the header is wider at `min(1360px, calc(100% - 80px))`. Story sections use the recorded desktop/tablet/mobile spacing. Repeated inline, action, and stack gaps are listed in the frontmatter; they are observed values, not an invented comprehensive spacing scale.

| Homepage width condition | Implemented behavior                                                                                                                                                                                            |
| ------------------------ | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| At least 1600px          | Hero top padding grows from 3rem to 4rem.                                                                                                                                                                       |
| Up to 1100px             | Wrapper has 40px side gutters; paired columns become 0.8fr / 1.2fr with 3rem gaps.                                                                                                                              |
| Up to 850px              | Wrapper/header have 24px side gutters; hero and explanatory sections stack; code evidence can use a 650px maximum; architecture heading stacks. Inspection remains two columns. Desktop Performance link hides. |
| Up to 600px              | Wrapper/header have 20px side gutters; native menu replaces desktop navigation; inspection, closing CTA, and footer stack. The detailed SVG composition graph becomes a compact vertical dependency graph.      |

Other paired structures use their own ratios: architecture heading 1.1fr / 0.9fr with a 6rem desktop gap, inspection 1fr / 1fr with a 5rem gap. Preserve `min-width: 0` on code-bearing columns so overflow stays inside code rather than widening the page. Framework links become two-column rows with the description beneath the name on mobile. The final CTA changes from a flex row to a vertical sequence.

Starlight owns its navigation/sidebar/content layout. Local docs overrides set a 6rem nav height and hide the docs Performance link at 70rem. Shared branding normally uses the canonical 180 × 60px lockup. Below 24rem (the implemented maximum is 23.99rem), it switches to the supplied standalone mark at 30 × 35px in a 44 × 44px header target; it does not shrink the wordmark. Native Dokka owns `/api/` layout and responsive navigation.

For a new feature, choose the owning content surface first. Add teaching pages to Starlight; add API descriptions to KDoc; add a homepage proof section only when it belongs in the established narrative. Reuse an existing section/grid pattern rather than imposing homepage geometry on framework-managed docs.

## Elevation & Depth

Authored homepage surfaces have no box shadows. Tonal section backgrounds, one-pixel rules, navy architecture framing, and spacing establish structure. The mobile navigation is an absolutely positioned bordered surface (z-index 5); the focus-only skip link uses z-index 20. The single translucent blue triangular fragment beside the hero composition is a brand accent, not a reusable elevation effect. Framework-managed search dialogs and code controls retain their own native layering.

**The Flat Structure Rule.** Use tonal panels, thin borders, and spacing to show grouping. Authored homepage surfaces have no box shadows.

## Shapes

Rectangular diagram nodes, technical panels, ruled tables, and straight dependency connectors define the form. Controls use the small `control` radius. Expressive Code's configured `code-frame` radius is a scoped soft corner; the implementation is not universally square. Its inner radius is 0.25rem (4px at the default root size); the native frame plugin adds its one-pixel border to produce a 5px outer radius. Preserve this native inner/outer relationship rather than introducing a separate outer-radius override. Diagram connector strokes are 2px; authored link SVG arrows use a 24 × 24 viewBox, currentColor, and approximately 1.7–1.8px strokes at 16–19px display size. Use the shared Arrow component for ordinary internal/external link arrows.

The supplied mark's columns, triangles, central diamond, outlined wordmark, aspect ratio, and light/dark asset choice belong to `.impeccable/BRAND.md`. Normal website lockups keep their supplied 3:1 proportions. Do not reproduce the logo as CSS primitives.

## Components

### Buttons

Direct, legible actions with a small corner radius. Primary uses orange/navy; secondary uses the current text color, a one-pixel semantic divider border, and no filled background. Both have the recorded padding and a minimum height of 48px, inline flex alignment, and a 1rem internal gap. Primary hover uses the recorded mixed orange; secondary hover uses the semantic panel color. In `prefers-reduced-motion: no-preference`, background changes transition over 0.15s with ease-out; reduced-motion users receive no authored transition.

Shared visible focus is a 3px semantic accent outline with 4px offset. This becomes fragment navy in light mode. Expressive Code pre focus uses the same width with -3px inset; native Dokka uses orange with a 3px offset. Keep these surface-specific choices.

### Navigation

Quiet text navigation beside the canonical lockup. Homepage desktop links use the `label` role, 2rem gaps, and 44px minimum targets; hover adds semantic accent and underline. Theme/menu controls are at least 44 × 44px with a tonal hover. Mobile uses native details/summary with a 230px bordered dropdown, Escape dismissal and focus return, and outside-click dismissal. Keep accessible names and the focus-only skip link.

Starlight keeps native search, sidebar, contents, theme selector, and keyboard behavior. Local docs project links use 0.9rem type and 1.2rem gaps; active navigation retains the implemented theme-aware filled state. Do not build a parallel header/search system inside guide content.

### Code and technical containers

Evidence panels remain source-driven. Homepage Kotlin snippets are imported from runnable example files and displayed with Starlight's Code/Expressive Code machinery. `website/ec.config.mjs` owns highlight themes, font, frame radius, and keyboard-focusable pre blocks. Avoid replacing copy controls or duplicating example code in decorative markup. The architecture image sits on a navy rectangular frame, with 2rem padding (0.8rem on mobile) and a factual caption. No authored general-purpose card or input component is established by the homepage.

### Reuse keys and shared producers

Keys are technical identifiers, not action chips. Small monospace keys use semantic panel fill, one-pixel borders, recorded reuse-key padding, and blue border emphasis for the reused key. A shared producer uses orange/navy and the recorded padding; two blue branches converge into it. Keep the diagram static and label what is actually shared. The composition SVG has a title and description; the narrow version uses six outlined HTML nodes and decorative SVG arrows on a CSS grid. Three separate arrows enter the orange OrderTile, annotated “3 callers · 1 execution”; the diagram’s accessible description names the dependencies and request-scoped reuse.

### Evidence tables and framework rows

Performance evidence uses a semantic full-width table with collapsed borders, row separators, tabular numbers, muted small headers, and right-aligned accent-colored monospace values. Its Barlow caption is 1.9rem at weight 600. A caption and nearby note carry the context; retain the evidence's measurement distinctions. Framework links are ruled rows, with a larger name, muted description, and inline SVG arrow; hover uses the semantic accent.

## Do's and Don'ts

### Do:

- **Do** read PRODUCT.md and the relevant .impeccable/surfaces/ contract before changing a surface; this file records the implemented system.
- **Do** edit canonical identity in .impeccable/BRAND.md and logo SVGs in assets/, shared tokens in website/src/styles/theme.css, homepage layout in home.css, and documentation overrides in docs.css.
- **Do** extend the homepage with real source examples, factual diagrams, and the existing heading/body/link hierarchy; retain the response-composition narrative.
- **Do** put teaching content in website/src/content/docs/ and API descriptions in KDoc; retain Starlight search, sidebar, contents, theme controls, and code-copy behavior, and native Dokka generation.
- **Do** check both themes, keyboard focus, code overflow, and mobile stacking; use the website/AGENTS.md viewport matrix and website commands for the changed surface.
- **Do** preserve SVG title/description or a descriptive text alternative, native table semantics, visible focus, and the existing 44px navigation/control and 48px CTA target minimums.

### Don't:

- **Don't** recolor, stretch, redraw, or add effects to supplied logo geometry, or substitute the historical README accent variants for the website palette.
- **Don't** replace the homepage's code-and-composition narrative with an interchangeable feature-card catalog, generated abstract imagery, or unsupported product claims.
- **Don't** introduce a competing font scale, palette, shadow system, or client animation as a requirement for understanding static technical relationships.
- **Don't** edit copied public/brand assets, generated API HTML, dist output, or framework internals to implement authored visual changes; change their owning sources.
- **Don't** make a homepage-specific dimension into a global requirement for Starlight or Dokka; keep framework-owned layout and behavior in their native systems.

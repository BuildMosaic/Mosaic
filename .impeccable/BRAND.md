# Mosaic brand guide

The Mosaic M is built from mirrored blue columns and orange triangles and a
central diamond: separate pieces composing a whole. Preserve this geometry and
the outlined wordmark. All supplied SVGs are true vectors with no installed-font
or raster dependency.

## Reusable identity

- [mosaic-mark.svg](../assets/mosaic-mark.svg): standalone transparent mark.
- [mosaic-lockup-light.svg](../assets/mosaic-lockup-light.svg): transparent mark
  and dark wordmark for white or other light backgrounds.
- [mosaic-lockup-dark.svg](../assets/mosaic-lockup-dark.svg): transparent mark
  and white wordmark for navy or other dark backgrounds.

The lockups have tight bounds and no tagline. Use these rather than cropping the
padded 240 × 120 README artwork in `.github/images/`. The README retains its
light/dark SVG presentations and matching PNG fallbacks.

## Colors

| Role | Value |
| --- | --- |
| Navy background | `#0d192a` |
| Blue | `#007def` |
| Orange | `#fd993e` |
| Wordmark on light backgrounds | `#181818` |
| Wordmark on dark backgrounds | `#ffffff` |

These are the canonical identity colors. Website typography, supporting colors,
and component styles are documented in [DESIGN.md](../DESIGN.md).
The light README presentation intentionally retains blue `#1982fe` and orange
`#fdb05e` to match its raster fallback. Its dark presentation uses the canonical
accents.

## Copy

Primary positioning: **Think from the response up, not the database down.**

Short descriptor: **Composable backend orchestration for Kotlin**

General branded destination: **BuildMosaic.org**. Preserve that capitalization.
Use `github.com/BuildMosaic/Mosaic` only in specifically GitHub contexts, such as
repository links.

## Usage

Use the supplied vectors; preserve proportions and colors. Keep clear space of
at least one quarter of the mark's width around a standalone mark or lockup
(the tight SVG bounds do not include that padding). Prefer a mark at least
24 pixels tall and a horizontal lockup at least 180 pixels wide; use the mark
alone when lettering becomes too small. Avoid stretching, recoloring, adding
outlines, or redrawing the fragments. Rasterize from the SVG master at the
required dimensions, with antialiasing and sRGB tagging; retain a transparent
background for reusable identity assets.

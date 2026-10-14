import { defineConfig } from 'astro/config';
import starlight from '@astrojs/starlight';
import sitemap from '@astrojs/sitemap';

export default defineConfig({
  site: 'https://BuildMosaic.org',
  trailingSlash: 'always',
  devToolbar: { enabled: false },
  integrations: [
    sitemap(),
    starlight({
      title: 'Mosaic',
      favicon: '/brand/mosaic-mark.svg',
      components: {
        SiteTitle: './src/components/DocsSiteTitle.astro',
        SocialIcons: './src/components/DocsLinks.astro',
        Head: './src/components/DocsHead.astro',
      },
      customCss: ['./src/styles/theme.css', './src/styles/docs.css'],
      editLink: { baseUrl: 'https://github.com/BuildMosaic/Mosaic/edit/main/website/' },
      head: [
        { tag: 'meta', attrs: { property: 'og:image', content: 'https://BuildMosaic.org/social-preview.png' } },
        { tag: 'meta', attrs: { name: 'twitter:card', content: 'summary_large_image' } },
        { tag: 'meta', attrs: { name: 'twitter:image', content: 'https://BuildMosaic.org/social-preview.png' } },
      ],
      sidebar: [
        {
          label: 'Start here',
          items: [
            { label: 'What is Mosaic?', slug: 'start/overview' },
            { label: 'Installation', slug: 'start/installation' },
            { label: 'Quick Start', slug: 'start/quick-start' },
            { label: 'Questions and support', link: 'https://github.com/BuildMosaic/Mosaic/issues' },
            { label: 'Security policy', link: 'https://github.com/BuildMosaic/Mosaic/security/policy' },
          ],
        },
        {
          label: 'Compose responses',
          collapsed: true,
          items: [
            { label: 'Tiles and composition', slug: 'concepts/tiles' },
            { label: 'Concurrent work', slug: 'concepts/concurrency' },
            { label: 'Shared work and identity', slug: 'concepts/shared-work' },
            { label: 'Canvas and dependencies', slug: 'concepts/canvas' },
            { label: 'MultiTile and keyed work', slug: 'concepts/multitile' },
            { label: 'Choose a batching strategy', slug: 'concepts/batching' },
          ],
        },
        {
          label: 'Build and inspect',
          collapsed: true,
          items: [
            { label: 'Test compositions', slug: 'guides/testing' },
            { label: 'Trace execution', slug: 'guides/tracing' },
            { label: 'Analyze architecture', slug: 'guides/analysis' },
            { label: 'Use your HTTP framework', slug: 'guides/frameworks' },
            { label: 'Own resources', slug: 'guides/resources' },
          ],
        },
        {
          label: 'Evidence and reference',
          collapsed: true,
          items: [
            { label: 'Performance', slug: 'reference/performance' },
            { label: 'Analysis configuration', slug: 'reference/analysis-configuration' },
            { label: 'Compatibility', slug: 'reference/compatibility' },
            { label: 'Releases', slug: 'reference/releases' },
            { label: 'Kotlin API reference', link: '/api/' },
            { label: 'GitHub', link: 'https://github.com/BuildMosaic/Mosaic' },
          ],
        },
      ],
    }),
  ],
});

import { defineCollection } from 'astro:content';
import { z } from 'astro/zod';
import { docsLoader } from '@astrojs/starlight/loaders';
import { docsSchema } from '@astrojs/starlight/schema';
export const collections = {
  docs: defineCollection({
    loader: docsLoader(),
    schema: docsSchema({
      extend: z.object({
        banner: z.object({ content: z.string() }).default({
          content:
            'These guides preview Mosaic 1.0. For published 0.7.0 APIs, use the <a href="https://github.com/BuildMosaic/Mosaic/tree/0.7.0">released guides and examples</a>.',
        }),
      }),
    }),
  }),
};

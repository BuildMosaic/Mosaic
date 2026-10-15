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
            'These docs preview Runtime 1.0 and Analysis 1.0, neither published yet. Installation and the main Quick Start use 0.7.0; the Quick Start also includes a 1.0 preview. See the <a href="https://github.com/BuildMosaic/Mosaic/tree/0.7.0">released 0.7.0 guides and examples</a>.',
        }),
      }),
    }),
  }),
};

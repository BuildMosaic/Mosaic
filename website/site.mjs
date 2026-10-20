// Shared by Astro and the static Dokka assembly step.
export const siteOrigin = 'https://BuildMosaic.org';
export const apiEntries = {
  '/api/': {
    title: 'Mosaic Kotlin API reference',
    description:
      'Explore the Kotlin APIs for Tile composition, Canvas dependencies, isolated testing, and OpenTelemetry execution tracing.',
  },
  '/api/mosaic-core/': {
    title: 'Mosaic Core — Kotlin composition API',
    description:
      'Compose backend application logic with Tiles and MultiTiles, share work in a Mosaic, and bind services and inputs with Canvas.',
  },
  '/api/mosaic-test/': {
    title: 'Mosaic Test — Tile testing API',
    description:
      'Test Kotlin Tile compositions in isolation with dependency substitutions, controlled failures, and coroutine test scopes.',
  },
  '/api/mosaic-opentelemetry/': {
    title: 'Mosaic OpenTelemetry — Execution tracing API',
    description:
      'Connect Mosaic executions to OpenTelemetry traces with spans for Tile work and links for shared producers and batch callers.',
  },
};

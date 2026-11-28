// Only entry-page summaries live here. Declaration documentation remains in KDoc.
export const apiEntries = {
  'index.html': {
    title: 'Mosaic Kotlin API reference',
    description: 'Kotlin API reference for Mosaic backend orchestration, tile testing, and OpenTelemetry tracing.',
  },
  'mosaic-core/index.html': {
    title: 'mosaic-core: Kotlin orchestration API | Mosaic',
    description: 'Kotlin API reference for Mosaic core: compose backend work with Canvas, Mosaic, Tile, and MultiTile.',
  },
  'mosaic-test/index.html': {
    title: 'mosaic-test: Kotlin tile testing API | Mosaic',
    description: 'Kotlin API reference for testing Mosaic tiles and compositions with mosaic-test.',
  },
  'mosaic-opentelemetry/index.html': {
    title: 'mosaic-opentelemetry: Kotlin tracing API | Mosaic',
    description: 'Kotlin API reference for tracing Mosaic execution with the OpenTelemetry integration.',
  },
};

export function apiCanonicalUrl(relativePath) {
  return new URL(`/api/${relativePath.replace(/(^|\/)index\.html$/, '$1')}`, 'https://buildmosaic.org').href;
}

export const apiSitemapPages = Object.keys(apiEntries).map(apiCanonicalUrl);

export function addApiMetadata($, relativePath) {
  // navigation.html is a Dokka fragment, not a standalone document.
  if (relativePath.split('/').at(-1) === 'navigation.html') return;
  $('head link[rel="canonical"]').remove();
  $('<link>')
    .attr({ rel: 'canonical', href: apiCanonicalUrl(relativePath) })
    .appendTo('head');
  const entry = apiEntries[relativePath];
  if (!entry) return;
  $('head title, head meta[name="description"]').remove();
  $('<title>').text(entry.title).appendTo('head');
  $('<meta>').attr({ name: 'description', content: entry.description }).appendTo('head');
}

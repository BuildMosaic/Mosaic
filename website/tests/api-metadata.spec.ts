import { test, expect } from '@playwright/test';
import { readFile, readdir } from 'node:fs/promises';
import { join } from 'node:path';
import { load } from 'cheerio';
import { apiEntries, apiCanonicalUrl, addApiMetadata } from '../scripts/api-metadata.mjs';

test('published API documents have one normalized canonical and preserve KDoc', async () => {
  const pages = (await readdir('dist/api', { recursive: true })).filter(
    (file) => file.endsWith('.html') && !file.endsWith('navigation.html'),
  );
  expect(pages.length).toBeGreaterThan(4);
  for (const file of pages) {
    const $ = load(await readFile(join('dist/api', file), 'utf8'));
    const original = load(await readFile(join('../build/dokka/html', file), 'utf8'));
    const canonical = $('head link[rel="canonical"]');
    expect(canonical.length, file).toBe(1);
    const expected = `https://buildmosaic.org/api/${file.replace(/(^|\/)index\.html$/, '$1')}`;
    expect(canonical.attr('href'), file).toBe(expected);
    expect($('.main-content').text(), file).toBe(original('.main-content').text());
    expect($('meta[name="robots"][content*="noindex"]').length, file).toBe(0);
  }
});

test('API entry metadata and sitemap agree on discoverable directory URLs', async () => {
  const sitemap = load(await readFile('dist/sitemap-0.xml', 'utf8'), { xml: true });
  const locations = sitemap('loc')
    .map((_, node) => sitemap(node).text())
    .get();
  for (const [file, entry] of Object.entries(apiEntries)) {
    const $ = load(await readFile(join('dist/api', file), 'utf8'));
    expect($('head title').length).toBe(1);
    expect($('head title').text()).toBe(entry.title);
    expect($('meta[name="description"]').length).toBe(1);
    expect($('meta[name="description"]').attr('content')).toBe(entry.description);
    expect(locations.filter((url) => url === apiCanonicalUrl(file))).toHaveLength(1);
  }
});

test('metadata is repeatable and keeps declaration descriptions and native navigation intact', () => {
  const $ = load(
    '<html><head><title>Canvas</title><meta name="description" content="From KDoc"></head><body><a href="index.html#member">member</a><h1>Canvas</h1></body></html>',
  );
  const path = 'mosaic-core/org.buildmosaic.core.injection/-canvas/index.html';
  addApiMetadata($, path);
  addApiMetadata($, path);
  expect($('link[rel="canonical"]').length).toBe(1);
  expect($('link[rel="canonical"]').attr('href')).toBe(
    'https://buildmosaic.org/api/mosaic-core/org.buildmosaic.core.injection/-canvas/',
  );
  expect($('title').text()).toBe('Canvas');
  expect($('meta[name="description"]').attr('content')).toBe('From KDoc');
  expect($('a').attr('href')).toBe('index.html#member');
  expect(apiCanonicalUrl('mosaic-core/example.html')).toBe('https://buildmosaic.org/api/mosaic-core/example.html');
});

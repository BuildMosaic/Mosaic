import { readdir, readFile, stat } from 'node:fs/promises';
import { resolve, join, relative } from 'node:path';
import { load } from 'cheerio';
import { siteOrigin, apiEntries } from '../site.mjs';

const root = resolve('dist');
async function files(dir) {
  const entries = await readdir(dir, { withFileTypes: true });
  return (
    await Promise.all(
      entries.map((entry) => (entry.isDirectory() ? files(join(dir, entry.name)) : [join(dir, entry.name)])),
    )
  ).flat();
}
const html = (await files(root)).filter((file) => file.endsWith('.html'));
const documents = new Map(await Promise.all(html.map(async (file) => [file, load(await readFile(file, 'utf8'))])));
const errors = [];
let checked = 0;
for (const [file, $] of documents) {
  const from = new URL('/' + relative(root, file), siteOrigin);
  for (const node of $('a[href], img[src], script[src], link[href]').toArray()) {
    const raw = $(node).attr('href') ?? $(node).attr('src');
    if (node.tagName === 'link' && $(node).attr('rel') === 'canonical' && relative(root, file) === '404.html') continue;
    if (!raw || /^(mailto:|tel:|data:|javascript:)/i.test(raw)) continue;
    const url = new URL(raw, from);
    if (url.origin !== from.origin) continue;
    const pathname = decodeURIComponent(url.pathname);
    let target = resolve(root, '.' + pathname);
    if (!target.startsWith(root + '/') && target !== root) {
      errors.push(`${file}: path escapes output: ${raw}`);
      continue;
    }
    try {
      if ((await stat(target)).isDirectory()) target = join(target, 'index.html');
      await stat(target);
      if (url.hash && documents.has(target)) {
        const id = decodeURIComponent(url.hash.slice(1));
        // Dokka uses content switches with generated fragment names as well as DOM IDs.
        const doc = documents.get(target);
        const found = doc('[id], a[name], [data-name], [data-togglable]')
          .toArray()
          .some((el) =>
            ['id', 'name', 'data-name', 'data-togglable'].some(
              (key) => doc(el).attr(key) === id || doc(el).attr(key) === url.hash.slice(1),
            ),
          );
        if (!found) errors.push(`${relative(root, file)}: missing fragment ${raw}`);
      }
      checked++;
    } catch {
      errors.push(`${relative(root, file)}: missing ${raw}`);
    }
  }
}
if (!documents.has(join(root, 'api/index.html')))
  errors.push('API is missing: run pnpm api before a complete production build.');
// Check authored pages and a deliberately small set of API landing pages.
const sitemapFiles = (await files(root)).filter((file) => /sitemap-\d+\.xml$/.test(file));
const sitemapUrls = [];
for (const file of sitemapFiles) {
  const $ = load(await readFile(file, 'utf8'), { xmlMode: true });
  sitemapUrls.push(
    ...$('url > loc')
      .toArray()
      .map((node) => $(node).text()),
  );
}
if (new Set(sitemapUrls).size !== sitemapUrls.length) errors.push('Duplicate sitemap URLs');
if (sitemapUrls.some((url) => url.endsWith('/index.html'))) errors.push('Sitemap contains index.html aliases');
const titles = new Set();
const descriptions = new Set();
for (const [file, $] of documents) {
  const path = '/' + relative(root, file).replace(/index\.html$/, '');
  if (path === '/404.html' || (path.startsWith('/api/') && !apiEntries[path])) continue;
  const title = $('title').text().trim();
  const description = $('meta[name="description"]').attr('content')?.trim();
  const canonical = new URL(path, siteOrigin).href;
  if (!title || titles.has(title)) errors.push(`${path}: missing or duplicate title`);
  if (!description || descriptions.has(description)) errors.push(`${path}: missing or duplicate description`);
  titles.add(title);
  descriptions.add(description);
  if ($('link[rel="canonical"]').length !== 1 || $('link[rel="canonical"]').attr('href') !== canonical)
    errors.push(`${path}: expected one canonical ${canonical}`);
  if (!sitemapUrls.includes(canonical)) errors.push(`${path}: missing from sitemap`);
  if (apiEntries[path]) {
    if (title !== apiEntries[path].title || description !== apiEntries[path].description)
      errors.push(`${path}: API entry metadata differs from site configuration`);
  }
}
if (errors.length) {
  console.error(errors.join('\n'));
  process.exitCode = 1;
} else console.log(`Checked ${checked} internal links and assets across ${html.length} HTML pages (Cheerio).`);

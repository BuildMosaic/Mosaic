import { cp, mkdir, access, readdir, readFile, writeFile, rm } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { join } from 'node:path';
import { load } from 'cheerio';
import { siteOrigin, apiEntries } from '../site.mjs';
const root = fileURLToPath(new URL('../../', import.meta.url));
const publicDir = fileURLToPath(new URL('../public/', import.meta.url));
await mkdir(publicDir, { recursive: true });
await rm(`${publicDir}brand`, { recursive: true, force: true });
await mkdir(`${publicDir}brand`, { recursive: true });
for (const asset of ['mosaic-mark.svg', 'mosaic-lockup-light.svg', 'mosaic-lockup-dark.svg']) {
  await cp(`${root}assets/${asset}`, `${publicDir}brand/${asset}`);
}
await cp(`${root}.github/images/order-architecture.png`, `${publicDir}order-architecture.png`);
const apiSource = `${root}build/dokka/html`;
if (process.argv.includes('--api')) {
  await access(`${apiSource}/index.html`);
  await rm(`${publicDir}api`, { recursive: true, force: true });
  await cp(apiSource, `${publicDir}api`, { recursive: true, force: true });
  // Entry-page metadata belongs in static output, not visitor-time JavaScript.
  for (const [route, { title, description }] of Object.entries(apiEntries)) {
    const file = join(publicDir, route, 'index.html');
    const $ = load(await readFile(file, 'utf8'));
    $('title, meta[name="description"], link[rel="canonical"], meta[property^="og:"], meta[name^="twitter:"]').remove();
    const canonical = new URL(route, siteOrigin).href;
    const socialImage = new URL('/social-preview.png', siteOrigin).href;
    $('head').append($('<title>').text(title));
    for (const [name, content] of Object.entries({
      description,
      'og:title': title,
      'og:description': description,
      'og:url': canonical,
      'og:type': 'website',
      'og:image': socialImage,
      'twitter:card': 'summary_large_image',
      'twitter:title': title,
      'twitter:description': description,
      'twitter:image': socialImage,
    })) {
      $('head').append(
        $('<meta>')
          .attr(name.startsWith('og:') ? 'property' : 'name', name)
          .attr('content', content),
      );
    }
    $('head').append($('<link>').attr('rel', 'canonical').attr('href', canonical));
    await writeFile(file, $.html());
  }
  // Dokka 2.2 emits inherited-overload fragment links without corresponding IDs.
  // Add aliases beside the native declaration-group anchor; preserve its HTML/layout.
  async function prepareDokkaPages(dir) {
    for (const entry of await readdir(dir, { withFileTypes: true })) {
      const file = join(dir, entry.name);
      if (entry.isDirectory()) {
        await prepareDokkaPages(file);
        continue;
      }
      if (!entry.name.endsWith('.html')) continue;
      const original = await readFile(file, 'utf8');
      // A source-build fallback is not a published API version.
      let html = original.replace(/<div class="library-version" id="library-version">[\s\S]*?<\/div>/, '');
      const $ = load(html);
      const aliases = new Map();
      for (const link of entry.name === 'index.html' ? $('a[href^="index.html#"]').toArray() : []) {
        const fragment = $(link).attr('href').slice('index.html#'.length);
        if (
          $('[id]')
            .toArray()
            .some((el) => $(el).attr('id') === fragment)
        )
          continue;
        const group = $(link).closest('.table-row').prevAll('a[anchor-label]').first();
        if (!group.length) throw new Error(`Unresolved Dokka fragment ${file}#${fragment}`);
        aliases.set(fragment, group.attr('id'));
      }
      for (const [fragment, group] of aliases) {
        if (!/^[A-Za-z0-9%/-]+$/.test(fragment)) throw new Error('Unexpected Dokka fragment');
        html = html.replace(`<a data-name="${group}"`, `<a id="${fragment}"></a><a data-name="${group}"`);
      }
      if (html !== original) await writeFile(file, html);
    }
  }
  await prepareDokkaPages(`${publicDir}api`);
}

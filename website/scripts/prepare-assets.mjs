import { cp, mkdir, access, readdir, readFile, writeFile, rm } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { join } from 'node:path';
import { load } from 'cheerio';
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
  // Dokka 2.2 emits inherited-overload fragment links without corresponding IDs.
  // Add aliases beside the native declaration-group anchor; preserve its HTML/layout.
  async function repairInheritedLinks(dir) {
    for (const entry of await readdir(dir, { withFileTypes: true })) {
      const file = join(dir, entry.name);
      if (entry.isDirectory()) {
        await repairInheritedLinks(file);
        continue;
      }
      if (entry.name !== 'index.html') continue;
      let html = await readFile(file, 'utf8');
      const $ = load(html);
      const aliases = new Map();
      for (const link of $('a[href^="index.html#"]').toArray()) {
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
      if (aliases.size) await writeFile(file, html);
    }
  }
  await repairInheritedLinks(`${publicDir}api`);
}

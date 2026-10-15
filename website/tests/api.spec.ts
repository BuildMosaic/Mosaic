import { test, expect } from '@playwright/test';
import { readFile, readdir } from 'node:fs/promises';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { load } from 'cheerio';

const canvasRoute = '/api/mosaic-core/org.buildmosaic.core.injection/-canvas/';
const apiRoutes = [
  '/api/',
  '/api/mosaic-core/',
  '/api/mosaic-test/',
  '/api/mosaic-opentelemetry/',
  '/api/mosaic-core/org.buildmosaic.core.injection/',
  canvasRoute,
  `${canvasRoute}source.html`,
  '/api/mosaic-test/org.buildmosaic.test/-test-mosaic/',
  '/api/mosaic-opentelemetry/org.buildmosaic.opentelemetry/tracing.html',
];

for (const theme of ['light', 'dark'] as const) {
  test(`native Dokka ${theme}: branding on aggregate, modules, packages, and declarations`, async ({ page }) => {
    await page.setViewportSize({ width: 1440, height: 1000 });
    await page.emulateMedia({ colorScheme: theme, reducedMotion: 'reduce' });
    const canonicalMark = await (await page.request.get('/brand/mosaic-mark.svg')).text();
    for (const route of apiRoutes) {
      expect((await page.goto(route))?.status()).toBe(200);
      const logo = page.locator('.library-name--link');
      await expect(logo).toBeVisible();
      // Check the effective native logo and its bytes, not just an asset's filename.
      const background = await logo.evaluate((el) => getComputedStyle(el, '::before').backgroundImage);
      const logoUrl = background.match(/url\("?([^")]+)"?\)/)?.[1];
      expect(logoUrl).toBeTruthy();
      expect(await (await page.request.get(logoUrl!)).text()).toBe(canonicalMark);
      const initialBackground = await page.locator('body').evaluate((el) => getComputedStyle(el).backgroundColor);
      await expect(page.getByRole('link', { name: 'User docs', exact: true })).toHaveAttribute(
        'href',
        '/start/overview/',
      );
      await expect(page.locator('#searchBar button')).toBeVisible();
      await expect(page.locator('#toc-listbox')).toBeVisible();
      const toggle = page.locator('#theme-toggle-button');
      await toggle.click();
      await expect
        .poll(() => page.locator('body').evaluate((el) => getComputedStyle(el).backgroundColor))
        .not.toBe(initialBackground);
      const changedBackground = await page.locator('body').evaluate((el) => getComputedStyle(el).backgroundColor);
      await page.reload();
      await expect(page.locator('body')).toHaveCSS('background-color', changedBackground);
      await page.locator('#theme-toggle-button').click();
    }
    // Check Canvas navigation with a fresh sidebar state, independent of prior routes.
    await page.evaluate(() => sessionStorage.clear());
    await page.goto(canvasRoute);
    await expect(page.locator('#toc-listbox a').filter({ hasText: /^Canvas$/ })).toBeVisible();
    await page.setViewportSize({ width: 390, height: 844 });
    await expect(page.getByRole('link', { name: 'User docs', exact: true })).toBeVisible();
    await page.locator('#toc-toggle').click();
    await expect(page.locator('#toc-listbox')).toBeVisible();
    await page.getByRole('button', { name: 'Close table of contents' }).click();
    await expect(page.locator('#toc-listbox')).not.toBeVisible();
  });
}

test('native Dokka module navigation, search, sidebar, source links, and return to User docs', async ({ page }) => {
  await page.goto('/api/');
  await page.locator('.main-content a[href="mosaic-core/index.html"]').click();
  await expect(page).toHaveURL(/\/api\/mosaic-core\/index.html$/);
  await page.locator('#searchBar').click();
  await page.locator('input:visible').first().fill('Canvas');
  await page.getByRole('button', { name: /class Canvas : AutoCloseable/ }).click();
  await expect(page).toHaveURL(/\/-canvas\/index.html(?:\?|$)/);
  await expect(page.getByRole('heading', { level: 1 })).toHaveText('Canvas');
  await expect(page.getByRole('link', { name: 'source', exact: true }).first()).toHaveAttribute(
    'href',
    /https:\/\/github.com\/BuildMosaic\/Mosaic\/tree\/main\/mosaic-core\/src\/main\/kotlin\/.*Canvas.kt#L/,
  );
  await page
    .locator('#toc-listbox a')
    .filter({ hasText: /^CanvasBuilder$/ })
    .click();
  await expect(page.getByRole('heading', { level: 1 })).toHaveText('CanvasBuilder');
  // Exercise search again from a nested declaration, not only the module index.
  await page.locator('#searchBar').click();
  await page.locator('input:visible').first().fill('Canvas');
  await page.getByRole('button', { name: /class Canvas : AutoCloseable/ }).click();
  await expect(page.getByRole('heading', { level: 1 })).toHaveText('Canvas');
  await page.getByRole('link', { name: 'User docs', exact: true }).click();
  await expect(page).toHaveURL(/\/start\/overview\/$/);
});

test('Dokka standalone and aggregate publications share canonical branding on every HTML page', async () => {
  const root = fileURLToPath(new URL('../../', import.meta.url));
  const mark = await readFile(join(root, 'assets/mosaic-mark.svg'), 'utf8');
  const stylesheet = await readFile(join(root, 'website/api/mosaic.css'), 'utf8');
  const script = await readFile(join(root, 'website/api/mosaic-api.js'), 'utf8');
  for (const module of ['', 'mosaic-core', 'mosaic-test', 'mosaic-opentelemetry']) {
    const output = join(root, module, 'build/dokka/html');
    expect(await readFile(join(output, 'images/logo-icon.svg'), 'utf8')).toBe(mark);
    expect(await readFile(join(output, 'styles/mosaic.css'), 'utf8')).toBe(stylesheet);
    expect(await readFile(join(output, 'images/mosaic-api.js'), 'utf8')).toBe(script);
    const pages = (await readdir(output, { recursive: true })).filter(
      (file) => file.endsWith('.html') && !file.endsWith('navigation.html'),
    );
    expect(pages.length).toBeGreaterThan(1);
    for (const file of pages) {
      const path = join(output, file);
      const $ = load(await readFile(path, 'utf8'));
      for (const [selector, asset] of [
        ['link[href$="styles/mosaic.css"]', 'styles/mosaic.css'],
        ['script[src$="images/mosaic-api.js"]', 'images/mosaic-api.js'],
        ['link[rel="icon"]', 'images/logo-icon.svg'],
      ]) {
        const element = $(selector);
        expect(element.length, `${module}/${file}: ${asset}`).toBe(1);
        expect(resolve(dirname(path), element.attr('href') ?? element.attr('src')!)).toBe(join(output, asset));
      }
    }
  }
});

import { test, expect } from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';
declare global {
  interface Window {
    mosaicLayoutShift: number;
  }
}

const routes = [
  '/',
  '/start/quick-start/',
  '/concepts/shared-work/',
  '/reference/performance/',
  '/reference/analysis-configuration/',
  '/concepts/tiles/',
  '/api/',
  '/api/mosaic-core/',
  '/api/mosaic-core/org.buildmosaic.core.injection/-canvas/',
];
for (const width of [1440, 1920, 1280, 820, 390, 430]) {
  for (const theme of ['light', 'dark'] as const) {
    test(`${width}px ${theme}: responsive, accessible reading routes`, async ({ page }) => {
      await page.setViewportSize({ width, height: width < 600 ? 844 : 1000 });
      await page.emulateMedia({ colorScheme: theme, reducedMotion: 'reduce' });
      await page.addInitScript(() => {
        window.mosaicLayoutShift = 0;
        new PerformanceObserver((list) => {
          for (const entry of list.getEntries() as (PerformanceEntry & { hadRecentInput: boolean; value: number })[]) {
            if (!entry.hadRecentInput) window.mosaicLayoutShift += entry.value;
          }
        }).observe({ type: 'layout-shift', buffered: true });
      });
      for (const route of routes) {
        expect((await page.goto(route))?.status()).toBe(200);
        await page.evaluate(() => document.fonts.ready);
        await expect(page.locator('main, [role="main"]')).toBeVisible();
        expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
        if (route === '/reference/analysis-configuration/') {
          const registry = page.locator('.sl-markdown-content table').nth(2);
          if (await registry.evaluate((el) => el.scrollWidth > el.clientWidth)) {
            await registry.focus();
            await expect(registry).toBeFocused();
            const before = await registry.evaluate((el) => el.scrollLeft);
            await page.keyboard.press('ArrowRight');
            await expect.poll(() => registry.evaluate((el) => el.scrollLeft)).toBeGreaterThan(before);
          }
        }
        if (route === '/') {
          const graph = page.locator('.composition-figure > svg');
          const mobileGraph = page.locator('.mobile-composition');
          if (width <= 600) {
            await expect(graph).not.toBeVisible();
            await expect(mobileGraph).toBeVisible();
            await expect(mobileGraph.locator('[data-tile="OrderTile"] span')).toHaveText('3 callers · 1 execution');
          } else {
            await expect(graph).toBeVisible();
            await expect(mobileGraph).not.toBeVisible();
          }
          expect(await page.evaluate(() => window.mosaicLayoutShift)).toBeLessThanOrEqual(0.1);
          expect(
            await page
              .locator('.button.primary')
              .first()
              .evaluate((el) => getComputedStyle(el).transitionDuration),
          ).toBe('0s');
        }
        const results = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21aa']).analyze();
        expect(results.violations, `${route}: ${JSON.stringify(results.violations)}`).toEqual([]);
      }
    });
  }
}

// Exercise the compact graph independently of the longer reading-route/axe checks.
for (const width of [320, 390, 430, 600]) {
  for (const theme of ['light', 'dark'] as const) {
    test(`${width}px ${theme}: mobile composition preserves nodes and shared dependencies`, async ({ page }) => {
      await page.setViewportSize({ width, height: 844 });
      await page.emulateMedia({ colorScheme: theme });
      await page.goto('/');
      await page.evaluate(() => document.fonts.ready);
      const graph = page.locator('.mobile-composition');
      await expect(page.locator('.composition-figure > svg')).toBeHidden();
      await expect(graph).toBeVisible();
      await expect(graph).toHaveAttribute('role', 'img');

      // Every visible label/annotation must belong to a node, including text nodes.
      const orphanText = await graph.evaluate((el) => {
        const walker = document.createTreeWalker(el, NodeFilter.SHOW_TEXT);
        const orphans: string[] = [];
        while (walker.nextNode()) {
          const text = walker.currentNode;
          if (text.textContent?.trim() && !text.parentElement?.closest('.mobile-graph-node[data-tile]')) {
            orphans.push(text.textContent.trim());
          }
        }
        return orphans;
      });
      expect(orphanText).toEqual([]);
      const tiles = [
        'OrderPageTile',
        'OrderSummaryTile',
        'LogisticsTile',
        'CustomerTile',
        'LineItemsTile',
        'OrderTile',
      ];
      const nodes = graph.locator('.mobile-graph-node');
      await expect(nodes).toHaveCount(tiles.length);
      for (const tile of tiles) {
        const node = graph.locator(`.mobile-graph-node[data-tile="${tile}"]`);
        await expect(node).toHaveCount(1);
        await expect(node.locator('code')).toHaveText(tile);
        await expect(node).toBeVisible();
        await expect(graph).toHaveAccessibleName(new RegExp(tile));
      }
      const shared = graph.locator('[data-tile="OrderTile"]');
      await expect(shared.locator('span')).toHaveText('3 callers · 1 execution');
      await expect(graph).toHaveAccessibleName(/once per Mosaic/);
      for (const svg of await graph.locator('svg').all()) {
        await expect(svg).toHaveAttribute('aria-hidden', 'true');
      }

      const geometry = await graph.evaluate((el) => {
        const box = (node: Element) => {
          const { left, right, top, bottom } = node.getBoundingClientRect();
          return { left, right, top, bottom };
        };
        return {
          graph: box(el),
          container: box(el.parentElement!),
          viewport: document.documentElement.clientWidth,
          fits: el.scrollWidth <= el.clientWidth,
          nodes: Array.from(el.querySelectorAll<HTMLElement>('.mobile-graph-node')).map((node) => ({
            tile: node.dataset.tile,
            ...box(node),
            fits: node.scrollWidth <= node.clientWidth,
            labels: Array.from(node.children).map((label) => box(label)),
            fontSize: parseFloat(getComputedStyle(node.querySelector('code')!).fontSize),
            background: getComputedStyle(node).backgroundColor,
            border: getComputedStyle(node).borderTopStyle,
          })),
        };
      });
      expect(geometry.fits).toBe(true);
      expect(geometry.graph.left).toBeGreaterThanOrEqual(Math.max(0, geometry.container.left) - 1);
      expect(geometry.graph.right).toBeLessThanOrEqual(Math.min(geometry.viewport, geometry.container.right) + 1);
      const sharedStyle = geometry.nodes.find((node) => node.tile === 'OrderTile')!;
      for (const node of geometry.nodes) {
        expect(node.fits, node.tile).toBe(true);
        expect(node.left, node.tile).toBeGreaterThanOrEqual(geometry.graph.left - 1);
        expect(node.right, node.tile).toBeLessThanOrEqual(geometry.graph.right + 1);
        expect(node.fontSize, node.tile).toBeGreaterThanOrEqual(14);
        for (const label of node.labels) {
          expect(label.left).toBeGreaterThanOrEqual(node.left - 1);
          expect(label.right).toBeLessThanOrEqual(node.right + 1);
          expect(label.top).toBeGreaterThanOrEqual(node.top - 1);
          expect(label.bottom).toBeLessThanOrEqual(node.bottom + 1);
        }
        if (node.tile !== 'OrderTile') {
          expect(node.border).toBe('solid');
          expect(node.background).not.toBe(sharedStyle.background);
        }
      }
      for (const [i, node] of geometry.nodes.entries()) {
        for (const other of geometry.nodes.slice(i + 1)) {
          expect(
            node.right <= other.left ||
              other.right <= node.left ||
              node.bottom <= other.top ||
              other.bottom <= node.top,
            `${node.tile} overlaps ${other.tile}`,
          ).toBe(true);
        }
      }

      // Check the actual rendered connectors, not just a list of relationship attributes.
      const edges = await graph.locator('[data-source][data-target]').evaluateAll((paths) =>
        paths.map((element) => {
          const path = element as SVGPathElement;
          const graph = path.closest('.mobile-composition')!;
          const endpointOnNode = (distance: number, tile: string) => {
            const point = path.getPointAtLength(distance).matrixTransform(path.getScreenCTM()!);
            const node = graph.querySelector(`[data-tile="${tile}"]`)!.getBoundingClientRect();
            return (
              point.x >= node.left - 2 &&
              point.x <= node.right + 2 &&
              (Math.abs(point.y - node.top) <= 2 || Math.abs(point.y - node.bottom) <= 2)
            );
          };
          return {
            edge: `${path.dataset.source} → ${path.dataset.target}`,
            anchored:
              endpointOnNode(0, path.dataset.source!) && endpointOnNode(path.getTotalLength(), path.dataset.target!),
            visible: getComputedStyle(path).stroke !== 'none' && path.getBoundingClientRect().height > 0,
          };
        }),
      );
      expect(edges.map(({ edge }) => edge).sort()).toEqual(
        [
          'OrderPageTile → OrderSummaryTile',
          'OrderPageTile → LogisticsTile',
          'OrderSummaryTile → CustomerTile',
          'OrderSummaryTile → LineItemsTile',
          'OrderSummaryTile → OrderTile',
          'CustomerTile → OrderTile',
          'LineItemsTile → OrderTile',
        ].sort(),
      );
      for (const edge of edges) {
        expect(edge.anchored, edge.edge).toBe(true);
        expect(edge.visible, edge.edge).toBe(true);
      }
    });
  }
}

test('homepage navigation, theme persistence, skip link, code copy, and anchor', async ({ page, context }) => {
  await context.grantPermissions(['clipboard-read', 'clipboard-write']);
  await page.goto('/');
  await page.keyboard.press('Tab');
  await expect(page.getByRole('link', { name: 'Skip to content' })).toBeFocused();
  await page.keyboard.press('Enter');
  await expect(page).toHaveURL(/#main$/);
  const previous = await page.locator('html').getAttribute('data-theme');
  await page.locator('#theme-toggle').click();
  expect(await page.locator('html').getAttribute('data-theme')).not.toBe(previous);
  await page.reload();
  expect(await page.locator('html').getAttribute('data-theme')).not.toBe(previous);
  await page.locator('.hero-composition button').first().click();
  expect(await page.evaluate(() => navigator.clipboard.readText())).toContain('OrderPageTile');
  await page.getByRole('link', { name: 'Follow the composition' }).click();
  await expect(page).toHaveURL(/#composition$/);
  await page.setViewportSize({ width: 390, height: 844 });
  await page.locator('.mobile-nav summary').click();
  await expect(page.getByRole('navigation', { name: 'Mobile navigation' })).toBeVisible();
  await page.keyboard.press('Escape');
  await expect(page.locator('.mobile-nav')).not.toHaveAttribute('open');
  await expect(page.locator('.mobile-nav summary')).toBeFocused();
});

test('documentation search, sidebar, copy, theme, and keyboard code scrolling', async ({ page, context }) => {
  await context.grantPermissions(['clipboard-read', 'clipboard-write']);
  await page.goto('/start/quick-start/');
  await page.locator('starlight-theme-select select').first().selectOption('dark');
  await expect(page.locator('html')).toHaveAttribute('data-theme', 'dark');
  await page
    .getByRole('button', { name: /search/i })
    .first()
    .click();
  const input = page.locator('dialog input');
  await input.fill('MultiTile');
  await expect(page.locator('dialog .pagefind-ui__result-link').first()).toBeVisible();
  await page.keyboard.press('Escape');
  await expect(page.locator('dialog')).not.toBeVisible();
  await page.locator('.expressive-code button').first().click();
  expect(await page.evaluate(() => navigator.clipboard.readText())).toContain('plugins');
  await page.setViewportSize({ width: 390, height: 844 });
  await page.locator('.sl-menu-button').click();
  await expect(page.locator('#starlight__sidebar')).toBeVisible();
  await page.locator('#starlight__sidebar summary').filter({ hasText: 'Compose responses' }).click();
  await page.locator('#starlight__sidebar a[href="/concepts/shared-work/"]').click();
  await expect(page.getByRole('heading', { level: 1 })).toHaveText('Shared work and identity');
  const code = page.locator('.expressive-code pre').first();
  await code.focus();
  await expect(code).toBeFocused();
  const before = await code.evaluate((el) => el.scrollLeft);
  await page.keyboard.press('ArrowRight');
  await expect.poll(() => code.evaluate((el) => el.scrollLeft)).toBeGreaterThan(before);
  await expect(page.locator('html')).toHaveAttribute('data-theme', 'dark');
});

test('native Dokka navigation and search', async ({ page }) => {
  await page.goto('/api/');
  await page.locator('.main-content a[href="mosaic-core/index.html"]').click();
  await expect(page).toHaveURL(/\/api\/mosaic-core\/index.html$/);
  await page.locator('#searchBar').click();
  await page.locator('input:visible').first().fill('Canvas');
  await expect(page.getByRole('button', { name: /class Canvas : AutoCloseable/ })).toBeVisible();
  await page.keyboard.press('Escape');
  await page.locator('#theme-toggle-button').click();
});

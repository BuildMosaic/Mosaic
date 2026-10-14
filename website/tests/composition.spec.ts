import { test, expect } from '@playwright/test';

test('composition dependencies use distinct node anchors and never cross or overlap', async ({ page }) => {
  await page.goto('/');
  await expect(page.getByRole('img', { name: /^An order response, composed from shared work/ })).toBeVisible();
  await expect(page.locator('.graph-node text')).toHaveText([
    'OrderPageTile',
    'OrderSummaryTile',
    'LogisticsTile',
    'CustomerTile',
    'LineItemsTile',
    'OrderTile',
  ]);
  await expect(page.locator('.graph-shared text')).toHaveText('OrderTile');
  await expect(page.locator('.composition-figure figcaption')).toHaveText(
    'Three branches. One OrderTile execution per Mosaic.',
  );
  const edges = await page.locator('.graph-edges path').evaluateAll((paths) => {
    const bounds = new Map(
      Array.from(document.querySelectorAll<SVGGraphicsElement>('.composition-figure > svg [data-tile]')).map((node) => [
        node.dataset.tile,
        node.getBBox(),
      ]),
    );
    const onBorder = (point: number[], box: DOMRect) =>
      ((point[0] === box.x || point[0] === box.x + box.width) && point[1] >= box.y && point[1] <= box.y + box.height) ||
      ((point[1] === box.y || point[1] === box.y + box.height) && point[0] >= box.x && point[0] <= box.x + box.width);
    return paths.map((path) => {
      const values = path
        .getAttribute('d')!
        .match(/[0-9]+/g)!
        .map(Number);
      const points = Array.from({ length: values.length / 2 }, (_, i) => values.slice(i * 2, i * 2 + 2));
      const source = path.getAttribute('data-source')!;
      const target = path.getAttribute('data-target')!;
      return {
        source,
        target,
        points,
        anchored: onBorder(points[0], bounds.get(source)!) && onBorder(points.at(-1)!, bounds.get(target)!),
      };
    });
  });
  expect(edges.map(({ source, target }) => `${source} → ${target}`).sort()).toEqual(
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
  for (const edge of edges) expect(edge.anchored, `${edge.source} → ${edge.target}`).toBe(true);
  const segments = edges.map(({ points }) => points.slice(1).map((point, i) => [points[i], point]));
  // Bounding-box intersection is exact for these orthogonal connector segments.
  for (const [a, b] of segments.flat()) expect(a[0] === b[0] || a[1] === b[1]).toBe(true);
  const intersect = (a: number[][], b: number[][]) =>
    Math.max(Math.min(a[0][0], a[1][0]), Math.min(b[0][0], b[1][0])) <=
      Math.min(Math.max(a[0][0], a[1][0]), Math.max(b[0][0], b[1][0])) &&
    Math.max(Math.min(a[0][1], a[1][1]), Math.min(b[0][1], b[1][1])) <=
      Math.min(Math.max(a[0][1], a[1][1]), Math.max(b[0][1], b[1][1]));
  for (let i = 0; i < edges.length; i++) {
    for (let j = i + 1; j < edges.length; j++) {
      expect(
        segments[i].some((a) => segments[j].some((b) => intersect(a, b))),
        `Edges ${i} and ${j} cross, touch, or share a segment`,
      ).toBe(false);
    }
  }
});

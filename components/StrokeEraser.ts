import { PluginCommAPI, PluginFileAPI } from 'sn-plugin-lib';
import FloatingToolbarBridge from './FloatingToolbarBridge';

const LOG_TAG = '[StrokeEraser]';

const ERASER_RADIUS_PX = 40;

let armed = false;
let pathSub: { remove(): void } | null = null;
let cancelSub: { remove(): void } | null = null;

export async function arm(): Promise<void> {
  if (armed) {
    console.log(LOG_TAG, 'already armed; re-arming');
    disarm();
  }
  armed = true;
  console.log(LOG_TAG, 'arming...');

  pathSub = FloatingToolbarBridge.onStrokeEraserPath(async (event) => {
    if (!armed) return;
    await handlePath(event.points).catch(e => console.error(LOG_TAG, 'handlePath error', e));

    if (armed) {
      FloatingToolbarBridge.showStrokeEraserOverlay();
    }
  });

  cancelSub = FloatingToolbarBridge.onStrokeEraserCancel(() => {
    if (!armed) return;
    console.log(LOG_TAG, 'cancelled (timeout or user)');
    armed = false;
    disarm();
  });

  FloatingToolbarBridge.showStrokeEraserOverlay();
  console.log(LOG_TAG, 'overlay shown, waiting for eraser stroke...');
}

export function disarm(): void {
  if (pathSub) { try { pathSub.remove(); } catch (_) {} pathSub = null; }
  if (cancelSub) { try { cancelSub.remove(); } catch (_) {} cancelSub = null; }
}

export function isArmed(): boolean {
  return armed;
}

type Pt = { x: number; y: number };

async function handlePath(eraserPoints: Pt[]): Promise<void> {
  if (eraserPoints.length < 2) {
    console.warn(LOG_TAG, 'path too short, skip');
    return;
  }

  try {
    const [fpRes, pgRes]: any[] = await Promise.all([
      PluginCommAPI.getCurrentFilePath(),
      PluginCommAPI.getCurrentPageNum(),
    ]);

    if (!fpRes?.success || !pgRes?.success) {
      console.error(LOG_TAG, 'cannot get file/page', fpRes, pgRes);
      return;
    }

    const filePath: string = fpRes.result;
    const page: number = pgRes.result;

    const elRes: any = await PluginFileAPI.getElements(page, filePath);
    if (!elRes?.success || !elRes.result) {
      console.warn(LOG_TAG, 'getElements failed or empty', elRes);
      return;
    }

    const elements: any[] = elRes.result;

    let eMinX = Infinity, eMinY = Infinity, eMaxX = -Infinity, eMaxY = -Infinity;
    for (const p of eraserPoints) {
      if (p.x < eMinX) eMinX = p.x; if (p.y < eMinY) eMinY = p.y;
      if (p.x > eMaxX) eMaxX = p.x; if (p.y > eMaxY) eMaxY = p.y;
    }
    console.log(LOG_TAG, `${elements.length} elements on page. Eraser bounds: [${eMinX.toFixed(0)},${eMinY.toFixed(0)}→${eMaxX.toFixed(0)},${eMaxY.toFixed(0)}]`);

    const toDelete: number[] = [];

    for (const el of elements) {
      const bbox = await getElementBbox(el);
      if (!bbox) {
        console.log(LOG_TAG, `  el#${el.numInPage} type=${el.type} → no bbox, skip`);
        continue;
      }
      const cx = (bbox.left + bbox.right) / 2;
      const cy = (bbox.top + bbox.bottom) / 2;
      const dist = minDistanceToPath(eraserPoints, cx, cy);
      const hit = dist < ERASER_RADIUS_PX;
      if (hit) {
        console.log(LOG_TAG, `  el#${el.numInPage} HIT dist=${dist.toFixed(1)} center=(${cx.toFixed(0)},${cy.toFixed(0)})`);
        toDelete.push(el.numInPage);
      }
    }

    for (const el of elements) {
      try { await el.recycle?.(); } catch (_) {}
    }

    if (toDelete.length === 0) {
      console.log(LOG_TAG, 'no intersections found');
      return;
    }

    console.log(LOG_TAG, `erasing ${toDelete.length} elements: [${toDelete.join(',')}]`);

    let unionLeft = Infinity, unionTop = Infinity, unionRight = -Infinity, unionBottom = -Infinity;
    for (const el of elements) {
      if (!toDelete.includes(el.numInPage)) continue;
      const bbox = await getElementBbox(el);
      if (!bbox) continue;
      if (bbox.left < unionLeft) unionLeft = bbox.left;
      if (bbox.top < unionTop) unionTop = bbox.top;
      if (bbox.right > unionRight) unionRight = bbox.right;
      if (bbox.bottom > unionBottom) unionBottom = bbox.bottom;
    }

    const pad = 5;
    const rect = {
      left: Math.floor(unionLeft) - pad,
      top: Math.floor(unionTop) - pad,
      right: Math.ceil(unionRight) + pad,
      bottom: Math.ceil(unionBottom) + pad,
    };

    console.log(LOG_TAG, `lassoElements rect=[${rect.left},${rect.top}→${rect.right},${rect.bottom}]`);

    await PluginCommAPI.setLassoBoxState(1).catch(() => {});
    const lr: any = await PluginCommAPI.lassoElements(rect);
    if (!lr?.success || lr.result === false) {
      console.warn(LOG_TAG, 'lassoElements failed', lr);
      return;
    }
    await PluginCommAPI.setLassoBoxState(3);
    const dr: any = await PluginCommAPI.deleteLassoElements();
    console.log(LOG_TAG, `deleteLassoElements: ${dr?.success}`);
  } catch (e) {
    console.error(LOG_TAG, 'handlePath error:', e);
  }
}

async function getElementBbox(el: any): Promise<{ left: number; top: number; right: number; bottom: number } | null> {

  if (el.contoursSrc) {
    try {
      const count = await el.contoursSrc.size();
      if (count > 0) {
        const contour: Pt[] = await el.contoursSrc.get(0);
        if (contour && contour.length >= 2) {
          let minX = Infinity, minY = Infinity, maxX = -Infinity, maxY = -Infinity;
          for (const p of contour) {
            if (p.x < minX) minX = p.x; if (p.y < minY) minY = p.y;
            if (p.x > maxX) maxX = p.x; if (p.y > maxY) maxY = p.y;
          }
          if (maxX > minX && maxY > minY) {
            return { left: minX, top: minY, right: maxX, bottom: maxY };
          }
        }
      }
    } catch (_) {}
  }

  if (el.textBox?.textRect) {
    const r = el.textBox.textRect;
    return { left: r.left, top: r.top, right: r.right, bottom: r.bottom };
  }

  if (el.title && el.title.width > 0) {
    return {
      left: el.title.X,
      top: el.title.Y,
      right: el.title.X + el.title.width,
      bottom: el.title.Y + el.title.height,
    };
  }

  return null;
}

function minDistanceToPath(pts: Pt[], px: number, py: number): number {
  let min = Infinity;
  for (let i = 0; i < pts.length - 1; i++) {
    const d = pointToSegmentDist(px, py, pts[i].x, pts[i].y, pts[i + 1].x, pts[i + 1].y);
    if (d < min) min = d;
  }
  return min;
}

function pointToSegmentDist(px: number, py: number, ax: number, ay: number, bx: number, by: number): number {
  const dx = bx - ax, dy = by - ay;
  const lenSq = dx * dx + dy * dy;
  if (lenSq === 0) return Math.hypot(px - ax, py - ay);
  const t = Math.max(0, Math.min(1, ((px - ax) * dx + (py - ay) * dy) / lenSq));
  return Math.hypot(px - (ax + t * dx), py - (ay + t * dy));
}

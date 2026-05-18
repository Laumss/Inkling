import { PluginCommAPI, PluginFileAPI } from 'sn-plugin-lib';
import { FileLogger } from './FileLogger';
import FloatingToolbarBridge from './FloatingToolbarBridge';
import { LassoExtractor } from './LassoExtractor';

const PG_TAG = '[PenGuard]';

let snapshotNums: Set<number> | null = null;
let snapshotPath: string | null = null;
let snapshotPage: number | null = null;

export const PenGuard = {
  async begin(): Promise<void> {
    try {
      const [fpRes, pgRes]: any[] = await Promise.all([
        PluginCommAPI.getCurrentFilePath(),
        PluginCommAPI.getCurrentPageNum(),
      ]);
      if (!fpRes?.success || !fpRes.result) { PenGuard.reset(); return; }
      if (!pgRes?.success || typeof pgRes.result !== 'number') { PenGuard.reset(); return; }

      snapshotPath = fpRes.result;
      snapshotPage = pgRes.result;

      const numListRes: any = await PluginFileAPI.getElementNumList(snapshotPath, snapshotPage);
      if (!numListRes?.success || !Array.isArray(numListRes.result)) { PenGuard.reset(); return; }

      snapshotNums = new Set(numListRes.result as number[]);
      console.log(PG_TAG, `begin: path=${snapshotPath} page=${snapshotPage} elements=${snapshotNums.size}`);
    } catch (e) {
      console.error(PG_TAG, 'begin error:', e);
      PenGuard.reset();
    }
  },

  async end(): Promise<void> {
    if (snapshotNums === null || !snapshotPath || snapshotPage === null) return;

    const prevNums = snapshotNums;
    const path = snapshotPath;
    const page = snapshotPage;
    PenGuard.reset();

    try {
      const numListRes: any = await PluginFileAPI.getElementNumList(path, page);
      if (!numListRes?.success || !Array.isArray(numListRes.result)) return;

      const currentNums = numListRes.result as number[];
      const newNums = currentNums.filter(n => !prevNums.has(n));
      if (newNums.length === 0) return;

      const toDelete: number[] = [];
      for (const num of newNums) {
        try {
          const elRes: any = await PluginFileAPI.getElement(path, page, num);
          if (!elRes?.success) continue;
          const el = elRes.result;
          if (el.type === 0) {
            toDelete.push(num);
            try { await el.recycle?.(); } catch (_) {}
          }
        } catch (_) {}
      }

      if (toDelete.length === 0) return;
      console.log(PG_TAG, `end: deleting ${toDelete.length} leaked stroke(s):`, toDelete);
      const delRes: any = await PluginFileAPI.deleteElements(path, page, toDelete);
      if (delRes?.success) {
        await PluginCommAPI.reloadFile();
      }
    } catch (e) {
      console.error(PG_TAG, 'end error:', e);
    }
  },

  reset(): void {
    snapshotNums = null;
    snapshotPath = null;
    snapshotPage = null;
  },

  isActive(): boolean {
    return snapshotNums !== null;
  },
};

const PL_TAG = '[PenLasso]';
const BBOX_PADDING_PX = 100;

let armed = false;
let bboxSub: { remove(): void } | null = null;
let cancelSub: { remove(): void } | null = null;
let wasAlreadyLocked = false;

export const PenLasso = {
  async arm(): Promise<void> {
    if (armed) { PenLasso.disarm(); }
    armed = true;

    wasAlreadyLocked = FloatingToolbarBridge.isPenLockedSync();
    if (!wasAlreadyLocked) FloatingToolbarBridge.engagePenLock();

    try { await PenGuard.begin(); } catch (e) {
      console.warn(PL_TAG, 'PenGuard.begin failed:', e);
    }

    bboxSub = FloatingToolbarBridge.onPenLassoBbox(async (event) => {
      if (!armed) return;
      armed = false;
      PenLasso.disarm();
      await handleBbox(event).catch(e => console.error(PL_TAG, 'handleBbox error', e));
    });

    cancelSub = FloatingToolbarBridge.onPenLassoCancel(() => {
      if (!armed) return;
      armed = false;
      PenLasso.disarm();
      PenGuard.end().catch(() => {});
      if (!wasAlreadyLocked) {
        FloatingToolbarBridge.releasePenLock();
        FloatingToolbarBridge.disablePenBlock();
      }
      FloatingToolbarBridge.restoreToolbar();
    });

    FloatingToolbarBridge.showPenLassoOverlay();
  },

  disarm(): void {
    if (bboxSub) { try { bboxSub.remove(); } catch (_) {} bboxSub = null; }
    if (cancelSub) { try { cancelSub.remove(); } catch (_) {} cancelSub = null; }
  },

  isArmed(): boolean {
    return armed;
  },
};

async function handleBbox(bbox: { left: number; top: number; right: number; bottom: number }): Promise<void> {
  try {
    await PenGuard.end();
    if (!wasAlreadyLocked) {
      FloatingToolbarBridge.releasePenLock();
      FloatingToolbarBridge.disablePenBlock();
    }

    let pageW = 1920, pageH = 2560;
    try {
      const [fpRes, pgRes]: any[] = await Promise.all([
        PluginCommAPI.getCurrentFilePath(),
        PluginCommAPI.getCurrentPageNum(),
      ]);
      if (fpRes?.success && pgRes?.success) {
        const psRes: any = await PluginFileAPI.getPageSize(fpRes.result, pgRes.result);
        if (psRes?.success && psRes.result) { pageW = psRes.result.width; pageH = psRes.result.height; }
      }
    } catch (_) {}

    const rect = {
      left:   Math.max(0,     Math.floor(bbox.left)   - BBOX_PADDING_PX),
      top:    Math.max(0,     Math.floor(bbox.top)    - BBOX_PADDING_PX),
      right:  Math.min(pageW, Math.ceil(bbox.right)   + BBOX_PADDING_PX),
      bottom: Math.min(pageH, Math.ceil(bbox.bottom)  + BBOX_PADDING_PX),
    };

    if (rect.right - rect.left < 2 || rect.bottom - rect.top < 2) return;

    FileLogger.logEvent('PenLasso', `lassoElements rect=${JSON.stringify(rect)}`);
    const lr: any = await PluginCommAPI.lassoElements(rect);
    FileLogger.logEvent('PenLasso', `lassoElements result=${lr?.result} success=${lr?.success}`);

    if (lr?.success && lr.result !== false) {
      await (PluginCommAPI as any).setLassoBoxState?.(0);
      try {
        const counts = await (PluginCommAPI as any).getLassoElementTypeCounts?.();
        FileLogger.logEvent('PenLasso', `lasso created counts=${JSON.stringify(counts?.result ?? counts)}`);
      } catch (_) {}
      LassoExtractor.warmup();
    }
  } finally {
    FloatingToolbarBridge.restoreToolbar();
  }
}

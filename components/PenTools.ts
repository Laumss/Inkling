

import { PluginCommAPI, PluginFileAPI } from 'sn-plugin-lib';
import { FileLogger } from './FileLogger';
import FloatingToolbarBridge from './FloatingToolbarBridge';
import { LassoExtractor } from './LassoExtractor';

const PL_TAG = '[PenLasso]';
const BBOX_PADDING_PX = 100;

let armed = false;
let bboxSub: { remove(): void } | null = null;
let cancelSub: { remove(): void } | null = null;

export const PenLasso = {
  async arm(): Promise<void> {
    if (armed) { PenLasso.disarm(); }
    armed = true;

    bboxSub = FloatingToolbarBridge.onPenLassoBbox(async (event) => {
      if (!armed) return;
      armed = false;
      PenLasso.disarm();
      await handleBbox(event).catch(e => console.error(PL_TAG, 'handleBbox error', e));
    });

    cancelSub = FloatingToolbarBridge.onPenLassoCancel(() => {
      console.log(PL_TAG, 'onPenLassoCancel received, armed=', armed);
      if (!armed) return;
      armed = false;
      PenLasso.disarm();
      FloatingToolbarBridge.restoreToolbar();
    });

    FloatingToolbarBridge.showPenLassoOverlay();
  },

  disarm(): void {
    if (bboxSub) { try { bboxSub.remove(); } catch (_) {} bboxSub = null; }
    if (cancelSub) { try { cancelSub.remove(); } catch (_) {} cancelSub = null; }
  },
};

async function handleBbox(bbox: { left: number; top: number; right: number; bottom: number }): Promise<void> {
  try {
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

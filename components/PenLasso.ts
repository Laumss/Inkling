import { PluginCommAPI, PluginFileAPI } from 'sn-plugin-lib';
import { FileLogger } from './FileLogger';
import FloatingToolbarBridge from './FloatingToolbarBridge';
import * as PenGuard from './PenGuard';
import { LassoExtractor } from './LassoExtractor';

const LOG_TAG = '[PenLasso]';

const BBOX_PADDING_PX = 100;

let armed = false;
let bboxSub: { remove(): void } | null = null;
let cancelSub: { remove(): void } | null = null;

let wasAlreadyLocked = false;

export async function arm(): Promise<void> {
  if (armed) {
    console.log(LOG_TAG, 'already armed; re-arming');
    disarm();
  }
  armed = true;
  console.log(LOG_TAG, 'arming...');

  wasAlreadyLocked = FloatingToolbarBridge.isPenLockedSync();
  if (!wasAlreadyLocked) {
    FloatingToolbarBridge.engagePenLock();
  }

  try {
    await PenGuard.begin();
  } catch (e) {
    console.warn(LOG_TAG, 'PenGuard.begin failed:', e);
  }

  bboxSub = FloatingToolbarBridge.onPenLassoBbox(async (event) => {
    if (!armed) return;
    armed = false;
    disarm();
    await handleBbox(event).catch(e => console.error(LOG_TAG, 'handleBbox error', e));
  });

  cancelSub = FloatingToolbarBridge.onPenLassoCancel(() => {
    if (!armed) return;
    console.log(LOG_TAG, 'cancelled by native (timeout or error)');
    armed = false;
    disarm();
    PenGuard.end().catch(e => console.warn(LOG_TAG, 'PenGuard.end after cancel:', e));
    if (!wasAlreadyLocked) {
      FloatingToolbarBridge.releasePenLock();
      FloatingToolbarBridge.disablePenBlock();
    }
    FloatingToolbarBridge.restoreToolbar();
  });

  FloatingToolbarBridge.showPenLassoOverlay();
  console.log(LOG_TAG, 'overlay shown, waiting for pen stroke...');
}

export function disarm(): void {
  if (bboxSub) { try { bboxSub.remove(); } catch (_) {} bboxSub = null; }
  if (cancelSub) { try { cancelSub.remove(); } catch (_) {} cancelSub = null; }
}

export function isArmed(): boolean {
  return armed;
}

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
        if (psRes?.success && psRes.result) {
          pageW = psRes.result.width;
          pageH = psRes.result.height;
        }
      }
    } catch (_) {}

    const rect = {
      left:   Math.max(0,     Math.floor(bbox.left)   - BBOX_PADDING_PX),
      top:    Math.max(0,     Math.floor(bbox.top)    - BBOX_PADDING_PX),
      right:  Math.min(pageW, Math.ceil(bbox.right)   + BBOX_PADDING_PX),
      bottom: Math.min(pageH, Math.ceil(bbox.bottom)  + BBOX_PADDING_PX),
    };

    if (rect.right - rect.left < 2 || rect.bottom - rect.top < 2) {
      console.warn(LOG_TAG, 'bbox too small after padding, abort', rect);
      return;
    }

    FileLogger.logEvent('PenLasso', `lassoElements rect=${JSON.stringify(rect)}`);
    const lr: any = await PluginCommAPI.lassoElements(rect);
    FileLogger.logEvent('PenLasso', `lassoElements result=${lr?.result} success=${lr?.success}`);

    if (!lr?.success || lr.result === false) {
      console.warn(LOG_TAG, 'lassoElements failed', lr);
    } else {
      await (PluginCommAPI as any).setLassoBoxState?.(0);
      try {
        const counts = await (PluginCommAPI as any).getLassoElementTypeCounts?.();
        FileLogger.logEvent('PenLasso', `lasso created counts=${JSON.stringify(counts?.result ?? counts)}`);
        console.log(LOG_TAG, 'lasso created', rect, 'counts=', counts?.result ?? counts);
      } catch (_) {
        console.log(LOG_TAG, 'lasso created', rect);
      }
      LassoExtractor.warmup();
    }
  } finally {
    FloatingToolbarBridge.restoreToolbar();
  }
}

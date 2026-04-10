/**
 * StagingService — Insertion queue + history.
 *
 * Queue:   /sdcard/SCREENSHOT/.plugin_staging/queue/{timestamp}.png  (LIFO)
 * History: /sdcard/SCREENSHOT/.plugin_history/{timestamp}.png
 */

import RNFS from 'react-native-fs';

const STAGING_DIR = '/sdcard/SCREENSHOT/.plugin_staging';
const QUEUE_DIR   = `${STAGING_DIR}/queue`;
const HISTORY_DIR = '/sdcard/SCREENSHOT/.plugin_history';
const MAX_HISTORY = 20;

export interface HistoryEntry {
  path: string;
  timestamp: number;
}

export const StagingService = {
  async ensureDir(): Promise<void> {
    for (const d of [STAGING_DIR, QUEUE_DIR, HISTORY_DIR]) {
      if (!(await RNFS.exists(d))) await RNFS.mkdir(d);
    }
  },

  /** Stage for insertion (queued LIFO) and save to history. */
  async stage(sourceUri: string, opts?: { deleteSrcAfter?: boolean }): Promise<boolean> {
    try {
      await StagingService.ensureDir();
      const src = sourceUri.replace('file://', '');
      const ts = Date.now();
      await RNFS.copyFile(src, `${QUEUE_DIR}/${ts}.png`);
      await RNFS.copyFile(src, `${HISTORY_DIR}/${ts}.png`);
      await StagingService.pruneHistory();
      if (opts?.deleteSrcAfter) {
        try { await RNFS.unlink(src); } catch (_) {}
      }
      return true;
    } catch (_) { return false; }
  },

  /** Save to history only, not queued for insertion. */
  async saveToHistoryOnly(sourceUri: string): Promise<boolean> {
    try {
      await StagingService.ensureDir();
      const src = sourceUri.replace('file://', '');
      await RNFS.copyFile(src, `${HISTORY_DIR}/${Date.now()}.png`);
      await StagingService.pruneHistory();
      return true;
    } catch (_) { return false; }
  },

  /** Get the most recent queued image path (LIFO), or null. */
  async getNextQueued(): Promise<string | null> {
    try {
      await StagingService.ensureDir();
      const files = await RNFS.readDir(QUEUE_DIR);
      const pngs = files
        .filter(f => f.name.endsWith('.png'))
        .sort((a, b) => {
          const ta = parseInt(a.name.replace('.png', ''), 10);
          const tb = parseInt(b.name.replace('.png', ''), 10);
          return tb - ta; // newest first
        });
      return pngs.length > 0 ? pngs[0].path : null;
    } catch (_) { return null; }
  },

  /** Remove the most recent queued image after insertion. */
  async dequeue(): Promise<void> {
    const path = await StagingService.getNextQueued();
    if (path) {
      try { await RNFS.unlink(path); } catch (_) {}
    }
  },

  async pruneHistory(): Promise<void> {
    try {
      const files = await RNFS.readDir(HISTORY_DIR);
      const pngs = files
        .filter(f => f.name.endsWith('.png'))
        .sort((a, b) => {
          const ta = parseInt(a.name.replace('.png', ''), 10);
          const tb = parseInt(b.name.replace('.png', ''), 10);
          return ta - tb;
        });
      if (pngs.length > MAX_HISTORY) {
        const toDelete = pngs.slice(0, pngs.length - MAX_HISTORY);
        for (const f of toDelete) await RNFS.unlink(f.path);
      }
    } catch (_) {}
  },

  /** Get history entries, newest first. */
  async getHistory(): Promise<HistoryEntry[]> {
    try {
      await StagingService.ensureDir();
      const files = await RNFS.readDir(HISTORY_DIR);
      return files
        .filter(f => f.name.endsWith('.png'))
        .map(f => ({
          path: f.path,
          timestamp: parseInt(f.name.replace('.png', ''), 10) || 0,
        }))
        .sort((a, b) => b.timestamp - a.timestamp);
    } catch (_) { return []; }
  },

  async clearHistory(): Promise<void> {
    try {
      const files = await RNFS.readDir(HISTORY_DIR);
      for (const f of files) {
        if (f.name.endsWith('.png')) await RNFS.unlink(f.path);
      }
    } catch (_) {}
  },
};

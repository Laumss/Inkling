import { PluginCommAPI, PluginFileAPI } from 'sn-plugin-lib';

const LOG_TAG = '[PenGuard]';

let snapshotNums: Set<number> | null = null;
let snapshotPath: string | null = null;
let snapshotPage: number | null = null;

export async function begin(): Promise<void> {
  try {
    const [fpRes, pgRes]: any[] = await Promise.all([
      PluginCommAPI.getCurrentFilePath(),
      PluginCommAPI.getCurrentPageNum(),
    ]);
    if (!fpRes?.success || !fpRes.result) {
      console.warn(LOG_TAG, 'begin: getCurrentFilePath failed');
      reset();
      return;
    }
    if (!pgRes?.success || typeof pgRes.result !== 'number') {
      console.warn(LOG_TAG, 'begin: getCurrentPageNum failed');
      reset();
      return;
    }

    snapshotPath = fpRes.result;
    snapshotPage = pgRes.result;

    const numListRes: any = await PluginFileAPI.getElementNumList(snapshotPath, snapshotPage);
    if (!numListRes?.success || !Array.isArray(numListRes.result)) {
      console.warn(LOG_TAG, 'begin: getElementNumList failed');
      reset();
      return;
    }

    snapshotNums = new Set(numListRes.result as number[]);
    console.log(LOG_TAG, `begin: path=${snapshotPath} page=${snapshotPage} elements=${snapshotNums.size}`);
  } catch (e) {
    console.error(LOG_TAG, 'begin error:', e);
    reset();
  }
}

export async function end(): Promise<void> {
  if (snapshotNums === null || !snapshotPath || snapshotPage === null) {
    console.log(LOG_TAG, 'end: no snapshot, skipping');
    return;
  }

  const prevNums = snapshotNums;
  const path = snapshotPath;
  const page = snapshotPage;
  reset();

  try {
    const numListRes: any = await PluginFileAPI.getElementNumList(path, page);
    if (!numListRes?.success || !Array.isArray(numListRes.result)) {
      console.warn(LOG_TAG, 'end: getElementNumList failed');
      return;
    }

    const currentNums = numListRes.result as number[];
    const newNums = currentNums.filter(n => !prevNums.has(n));

    if (newNums.length === 0) {
      console.log(LOG_TAG, 'end: no new elements, nothing to clean');
      return;
    }

    console.log(LOG_TAG, `end: ${newNums.length} new element(s) found, inspecting...`);

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
      } catch (e) {
        console.warn(LOG_TAG, `end: getElement(${num}) error:`, e);
      }
    }

    if (toDelete.length === 0) {
      console.log(LOG_TAG, 'end: no new strokes to delete');
      return;
    }

    console.log(LOG_TAG, `end: deleting ${toDelete.length} leaked stroke(s):`, toDelete);
    const delRes: any = await PluginFileAPI.deleteElements(path, page, toDelete);
    if (delRes?.success) {
      await PluginCommAPI.reloadFile();
      console.log(LOG_TAG, 'end: cleanup complete, file reloaded');
    } else {
      console.warn(LOG_TAG, 'end: deleteElements failed', delRes);
    }
  } catch (e) {
    console.error(LOG_TAG, 'end error:', e);
  }
}

export function reset(): void {
  snapshotNums = null;
  snapshotPath = null;
  snapshotPage = null;
}

export function isActive(): boolean {
  return snapshotNums !== null;
}

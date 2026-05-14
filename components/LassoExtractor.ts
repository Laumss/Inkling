import { PluginCommAPI, PluginFileAPI, PluginNoteAPI } from 'sn-plugin-lib';
import RNFS from 'react-native-fs';
import { FileLogger } from './FileLogger';

export interface LinkedFile {
  path: string;
  linkType: number;
  label: string;
}

export interface ExtractedContent {
  text: string;
  imagePaths: string[];
  linkedFiles: LinkedFile[];

  lastTextBoxRect?: { left: number; top: number; right: number; bottom: number };
  stats: {
    strokes: number;
    textBoxes: number;
    pictures: number;
    others: number;
    recognizedStrokes: number;
  };
}

export type ExtractStage = 'recognizing' | 'done';
export type ExtractProgress = (stage: ExtractStage) => void;

type WarmEntry = {
  signature: string;
  promise: Promise<ExtractedContent>;
  startedAt: number;
};
let _warmEntry: WarmEntry | null = null;
const WARM_TTL_MS = 30_000;

async function buildWarmSignature(): Promise<string | null> {
  try {
    const [fpRes, pgRes, rectRes]: any[] = await Promise.all([
      PluginCommAPI.getCurrentFilePath(),
      PluginCommAPI.getCurrentPageNum(),
      PluginCommAPI.getLassoRect(),
    ]);
    if (!fpRes?.success || !pgRes?.success || !rectRes?.success || !rectRes.result) return null;
    const r = rectRes.result;
    return `${fpRes.result}|${pgRes.result}|${r.left},${r.top},${r.right},${r.bottom}`;
  } catch (_) {
    return null;
  }
}

export class LassoExtractor {

  static async extract(onProgress?: ExtractProgress): Promise<ExtractedContent> {
    const result: ExtractedContent = {
      text: '',
      imagePaths: [],
      linkedFiles: [],
      stats: { strokes: 0, textBoxes: 0, pictures: 0, others: 0, recognizedStrokes: 0 },
    };

    const res = await PluginCommAPI.getLassoElements() as any;
    if (!res?.success || !Array.isArray(res.result)) {
      FileLogger.logEvent('LassoExtract', `getLassoElements failed: ${res?.error?.message ?? 'unknown'}`);
      return result;
    }

    const elements = res.result;
    FileLogger.logEvent('LassoExtract', `found ${elements.length} elements`);

    type SortableItem = {
      sortY: number;
      text?: string;
      imagePath?: string;
      rect?: { left: number; top: number; right: number; bottom: number };
    };
    const sortable: SortableItem[] = [];
    const unrecognizedStrokes: { el: any; sortY: number }[] = [];

    for (const el of elements) {
      try {
        switch (el.type) {
          case 500:
          case 501:
          case 502: {
            result.stats.textBoxes++;
            const content = el.textBox?.textContentFull ?? '';
            const rect = el.textBox?.textRect as
              | { left: number; top: number; right: number; bottom: number }
              | undefined;
            if (content.trim()) {
              sortable.push({ sortY: rect?.top ?? 0, text: content.trim(), rect });
            }
            break;
          }

          case 0: {
            result.stats.strokes++;
            let sortY = 0;
            try {
              const cSize = await el.contoursSrc?.size?.();
              if (cSize && cSize > 0) {
                const firstContour = await el.contoursSrc.get(0);
                if (Array.isArray(firstContour) && firstContour.length > 0) {
                  sortY = firstContour[0].y ?? 0;
                }
              }
            } catch (_) {}
            unrecognizedStrokes.push({ el, sortY });
            break;
          }

          case 200: {
            result.stats.pictures++;

            const imgPath = el.picture?.picturePath ?? el.picture?.path;
            if (imgPath) {
              sortable.push({ sortY: 0, imagePath: imgPath });
              result.imagePaths.push(imgPath);
            }
            break;
          }

          default:
            result.stats.others++;
            break;
        }
      } catch (e) {
        console.warn('[LassoExtractor]: error processing element', el.type, e);
      }
    }

    if (unrecognizedStrokes.length > 0) {
      try { onProgress?.('recognizing'); } catch (_) {}
      try {
        const fpRes: any = await PluginCommAPI.getCurrentFilePath();
        const pgRes: any = await PluginCommAPI.getCurrentPageNum();
        if (fpRes?.success && fpRes.result && pgRes?.success && pgRes.result !== undefined) {
          const psRes: any = await PluginFileAPI.getPageSize(fpRes.result, pgRes.result);
          if (psRes?.success && psRes.result) {
            const els = unrecognizedStrokes.map(u => u.el);
            const ocrRes: any = await PluginCommAPI.recognizeElements(els, psRes.result);
            if (ocrRes?.success && typeof ocrRes.result === 'string' && ocrRes.result.trim()) {
              const minSortY = Math.min(...unrecognizedStrokes.map(u => u.sortY));
              sortable.push({ sortY: minSortY, text: ocrRes.result.trim() });
              result.stats.recognizedStrokes += unrecognizedStrokes.length;
              FileLogger.logEvent('LassoExtract',
                `recognizeElements ok: ${unrecognizedStrokes.length} strokes → ${ocrRes.result.length}chars`);
            } else {
              FileLogger.logEvent('LassoExtract',
                `recognizeElements empty/failed: ${ocrRes?.error?.message ?? 'no text'}`);
            }
          } else {
            FileLogger.logEvent('LassoExtract',
              `getPageSize failed for OCR: ${psRes?.error?.message ?? 'unknown'}`);
          }
        }
      } catch (e) {
        console.warn('[LassoExtractor]: recognizeElements error:', e);
        FileLogger.logEvent('LassoExtract', `recognizeElements threw: ${String(e)}`);
      }
    }
    try { onProgress?.('done'); } catch (_) {}

    sortable.sort((a, b) => a.sortY - b.sortY);

    const textParts: string[] = [];
    for (const item of sortable) {
      if (item.text) textParts.push(item.text);
    }
    result.text = textParts.join('\n');

    for (const item of sortable) {
      if (item.rect && typeof item.rect.bottom === 'number') {
        if (!result.lastTextBoxRect || item.rect.bottom > result.lastTextBoxRect.bottom) {
          result.lastTextBoxRect = item.rect;
        }
      }
    }

    try {
      const linkRes = await PluginNoteAPI.getLassoLinks() as any;
      if (linkRes?.success && Array.isArray(linkRes.result)) {
        const currentFilePath = (await PluginCommAPI.getCurrentFilePath())?.result ?? '';
        for (const link of linkRes.result) {
          const lt = link.linkType as number;
          const dest = (link.destPath ?? '') as string;
          if (lt === 4 && dest) {

            result.linkedFiles.push({ path: dest, linkType: lt, label: dest });
          } else if (lt === 0 && currentFilePath) {

            if (!result.linkedFiles.some(f => f.path === currentFilePath)) {
              const name = currentFilePath.split('/').pop()?.replace(/\.[^.]+$/, '') ?? 'note';
              result.linkedFiles.push({ path: currentFilePath, linkType: lt, label: name });
            }
          } else if ([1, 2, 3, 6].includes(lt) && dest) {

            const exists = await RNFS.exists(dest);
            if (exists) {
              const name = dest.split('/').pop()?.replace(/\.[^.]+$/, '') ?? 'file';
              result.linkedFiles.push({ path: dest, linkType: lt, label: name });
            } else {
              FileLogger.logEvent('LassoExtract', `linked file not found: ${dest}`);
            }
          }
        }
        if (result.linkedFiles.length > 0) {
          FileLogger.logEvent('LassoExtract',
            `found ${result.linkedFiles.length} linked file(s): ${result.linkedFiles.map(f => f.label).join(', ')}`);
        }
      }
    } catch (e) {
      FileLogger.logEvent('LassoExtract', `getLassoLinks failed (non-fatal): ${String(e)}`);
    }

    for (const el of elements) {

      try { el.recycle?.(); } catch (_) {}
    }

    FileLogger.logEvent('LassoExtract',
      `extracted: text=${result.text.length}chars images=${result.imagePaths.length} stats=${JSON.stringify(result.stats)}`);
    return result;
  }

  static warmup(): void {
    buildWarmSignature().then(signature => {
      if (!signature) return;

      if (_warmEntry && _warmEntry.signature === signature
          && Date.now() - _warmEntry.startedAt < WARM_TTL_MS) {
        return;
      }
      const startedAt = Date.now();
      const promise = LassoExtractor.extract().catch(e => {
        console.warn('[LassoExtractor]: warmup extract failed:', e);

        if (_warmEntry?.signature === signature) _warmEntry = null;
        throw e;
      });
      _warmEntry = { signature, promise, startedAt };
      FileLogger.logEvent('LassoExtract', `warmup started sig=${signature.slice(-32)}`);
    }).catch(() => {});
  }

  static async consumeWarmed(onProgress?: ExtractProgress): Promise<ExtractedContent | null> {
    const entry = _warmEntry;
    if (!entry) return null;
    if (Date.now() - entry.startedAt > WARM_TTL_MS) {
      _warmEntry = null;
      return null;
    }
    const signature = await buildWarmSignature();
    if (!signature || signature !== entry.signature) {

      _warmEntry = null;
      return null;
    }
    _warmEntry = null;

    let resolved = false;
    entry.promise.finally(() => { resolved = true; });
    if (!resolved) {
      try { onProgress?.('recognizing'); } catch (_) {}
    }
    try {
      const result = await entry.promise;
      try { onProgress?.('done'); } catch (_) {}
      FileLogger.logEvent('LassoExtract', `warmup consumed (cached)`);
      return result;
    } catch (_) {
      try { onProgress?.('done'); } catch (_) {}
      return null;
    }
  }

  static invalidateWarm(): void {
    if (_warmEntry) {
      _warmEntry = null;
      FileLogger.logEvent('LassoExtract', `warmup invalidated`);
    }
  }
}

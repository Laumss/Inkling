import { PluginCommAPI, PluginFileAPI, PluginNoteAPI, PluginManager, NativeUIUtils, FileUtils } from 'sn-plugin-lib';
import { NativeModules, EmitterSubscription, Dimensions } from 'react-native';
import { loadClips, saveClips, loadSnapshots, saveSnapshots } from './ToolPresets';
import { t } from './i18n';
import { toggleMode, handleAiSend, getLastMode, stopAiMode } from './BackgroundService';
import { getRNFS } from './rnfs';
import FloatingToolbarBridge from './FloatingToolbarBridge';
import FloatingBubbleBridge from './BubbleBridges';
import { layerIdOf, moveLiveElements } from './ElementOps';

const { FloatingToolbar } = NativeModules;

let _filePath: string | null = null;
let _pageNum: number | null = null;

let _imageQueue: string[] = [];
let _docLinkQueue: string[] = [];
let _clipBusy = false;

const STICKER_DIR = '/sdcard/MyStyle/Sticker';

async function ensureStickerDir(): Promise<void> {
  const RNFS = getRNFS();
  if (!RNFS) return;
  try {
    const parentExists = await RNFS.exists('/sdcard/MyStyle');
    if (!parentExists) {
      await RNFS.mkdir('/sdcard/MyStyle');
      console.log('[CLIP-DBG] created /sdcard/MyStyle');
    }
    const exists = await RNFS.exists(STICKER_DIR);
    if (!exists) {
      await RNFS.mkdir(STICKER_DIR);
      console.log('[CLIP-DBG] created', STICKER_DIR);
    }
  } catch (e) {
    console.warn('[CLIP-DBG] ensureStickerDir FAILED:', e);
  }
}

export const OPEN_SEND_SCREEN = '__open_send_screen__';

let _noteType: number | null = null;

async function ctx(): Promise<boolean> {
  try {
    const fp = await PluginCommAPI.getCurrentFilePath();
    if (fp.success && fp.result) _filePath = fp.result;
    const pg = await PluginCommAPI.getCurrentPageNum();
    if (pg.success && pg.result !== undefined) _pageNum = pg.result;
    if (_filePath) {
      const nt = await PluginFileAPI.getNoteType(_filePath);
      _noteType = nt.success ? (nt.result as number) : null;
    }
    return !!_filePath && _pageNum !== null;
  } catch { return false; }
}

function isRecognitionNote(): boolean {
  return _noteType === 1;
}

async function _insertDocLinkDirect(docPath: string): Promise<void> {
  const rawName = docPath.split('/').pop() ?? 'link';
  const dotIdx = rawName.lastIndexOf('.');
  const linkName = dotIdx > 0 ? rawName.substring(0, dotIdx) : rawName;

  const screen = Dimensions.get('window');
  let pageW = screen.width > screen.height ? 1872 : 1404;
  let pageH = screen.width > screen.height ? 1404 : 1872;
  let notePath = '';
  let pageNum = 0;
  try {
    const fpRes: any = await PluginCommAPI.getCurrentFilePath();
    const pgRes: any = await PluginCommAPI.getCurrentPageNum();
    if (fpRes?.success && fpRes.result) notePath = fpRes.result;
    if (pgRes?.success && pgRes.result !== undefined) pageNum = pgRes.result;
    if (notePath && pageNum !== undefined) {
      const layersRes: any = await PluginFileAPI.getLayers(notePath, pageNum);
      const currentLayer = Array.isArray(layersRes?.result)
        ? layersRes.result.find((layer: any) => layer.isCurrentLayer)
        : null;
      const currentLayerId = currentLayer?.layerId ?? currentLayer?.layerNum;
      if (currentLayerId !== undefined && currentLayerId !== 0) {
        console.warn('[ToolActions] insertDocLink blocked on non-main layer:', currentLayerId);
        NativeUIUtils.showErrorTipDialog(t('doc_link_main_only'));
        return;
      }

      const psRes: any = await PluginFileAPI.getPageSize(notePath, pageNum);
      if (psRes?.success && psRes.result) {
        pageW = psRes.result.width;
        pageH = psRes.result.height;
      }
    }
  } catch (_) {}

  try { await (PluginCommAPI as any).setLassoBoxState?.(2); } catch (_) {}

  const linkW = Math.min(linkName.length * 30 + 40, pageW * 0.6);
  const left = Math.round((pageW - linkW) / 2);
  let top = Math.round(pageH * 0.15);
  const fontSize = 49;
  const lineH = fontSize + 10;

  try {
    if (notePath) {
      const elRes: any = await PluginFileAPI.getElements(pageNum, notePath);
      if (elRes?.success && Array.isArray(elRes.result)) {
        const occupied: { top: number; bottom: number }[] = [];
        for (const el of elRes.result) {
          try {
            const elType = el.type;

            if (typeof elType === 'number' && elType >= 500 && elType <= 502) {
              const rect = el.textBox?.textRect;
              if (rect && typeof rect.top === 'number' && typeof rect.bottom === 'number') {
                occupied.push({ top: rect.top, bottom: rect.bottom });
              }
            }

            if (typeof elType === 'number' && elType === 600) {
              const lk = el.link;
              if (lk && typeof lk.Y === 'number' && typeof lk.height === 'number') {
                occupied.push({ top: lk.Y, bottom: lk.Y + lk.height });
              }
            }
          } finally {
            try { el.recycle?.(); } catch (_) {}
          }
        }
        occupied.sort((a, b) => a.top - b.top);

        const GAP = 10;
        for (let iter = 0; iter < 50; iter++) {
          let collision = false;
          for (const range of occupied) {
            if (top < range.bottom && (top + lineH) > range.top) {
              console.log('[ToolActions] docLink collision at top=', top,
                'with existing [', range.top, ',', range.bottom, '] → skip to', range.bottom + GAP);
              top = range.bottom + GAP;
              collision = true;
              break;
            }
          }
          if (!collision) break;
        }

        if (top + lineH > pageH - 50) {
          console.warn('[ToolActions] docLink: no room on page, using original position');
          top = Math.round(pageH * 0.15);
        }
      }
    }
  } catch (e) {
    console.warn('[ToolActions] docLink collision scan failed (non-fatal):', e);
  }

  const ext = docPath.split('.').pop()?.toLowerCase() || '';
  const isNoteFile = ext === 'note';
  const isDocFile = ['epub', 'pdf', 'cbz', 'doc', 'docx', 'djvu', 'mobi', 'fb2'].includes(ext);
  const destPath = docPath.replace('/sdcard/', '/storage/emulated/0/');

  const textLink = {
    destPath,
    destPage: -1,
    style: 0,
    linkType: isNoteFile ? 1 : isDocFile ? 2 : 0,
    rect: { left, top, right: left + Math.round(linkW), bottom: top + lineH },
    fontSize,
    fullText: linkName,
    showText: linkName,
    isItalic: 0,
  };
  const insertRes: any = await (PluginNoteAPI as any).insertTextLink(textLink);
  console.log('[ToolActions] insertTextLink result:', insertRes);
  if (!insertRes?.success || insertRes.result !== 0) {
    console.warn('[ToolActions] insertTextLink failed:', insertRes);
    if (insertRes?.error?.code === 810) {
      NativeUIUtils.showErrorTipDialog(t('doc_link_main_only'));
    }
    return;
  }

  try {
    await new Promise(r => setTimeout(r, 500));

    const pad = 5;
    const lassoRect = {
      left: textLink.rect.left - pad,
      top: textLink.rect.top - pad,
      right: textLink.rect.right + pad,
      bottom: textLink.rect.bottom + pad,
    };
    const lr: any = await PluginCommAPI.lassoElements(lassoRect);
    if (lr?.success && lr.result !== false) {
      await (PluginCommAPI as any).setLassoBoxState?.(0);
    }
  } catch (e) {
    console.warn('[ToolActions] lassoElements after insertTextLink failed:', e);
  }
}

export async function executeAction(action: string): Promise<string> {
  console.log('[ToolActions]:', action);

  if (action === 'insert_text') {
    const lastMode = getLastMode();
    const mode = await toggleMode(lastMode);
    return mode === 'nospacing' ? 'Text receive: no-gap ON' : mode === 'paragraph' ? 'Text receive: paragraph ON' : 'Text receive OFF';
  }

  if (action === 'text_recv_nospacing') {
    const mode = await toggleMode('nospacing');
    return mode === 'nospacing' ? 'Text receive: no-gap ON' : 'Text receive OFF';
  }
  if (action === 'text_recv_paragraph') {
    const mode = await toggleMode('paragraph');
    return mode === 'paragraph' ? 'Text receive: paragraph ON' : 'Text receive OFF';
  }

  if (action === 'voice_transcribe') {
    const { startAiReceiveMode, stopAiMode, isAiActive } = require('./BackgroundService');
    if (isAiActive()) {
      stopAiMode();
      return 'AI receive: OFF';
    }
    await startAiReceiveMode();
    return 'AI receive: ON';
  }

  if (action === 'lasso_ai') {
    handleAiSend().catch(e => console.error('[ToolActions]: lasso_ai error:', e));
    return 'Sending to AI...';
  }

  if (action === 'lasso_smart_send') {
    if (await hasLassoSelection()) {
      const { LassoExtractor } = require('./LassoExtractor');
      const { markLassoDataPrePopulated } = require('../App');
      const extracted = await LassoExtractor.extract();
      if (!extracted.text && extracted.imagePaths.length === 0 && extracted.linkedFiles.length === 0) {
        NativeUIUtils.showErrorTipDialog(t('lasso_send_ocr_failed'));
        return 'Send aborted: nothing to send';
      }
      FloatingToolbarBridge.setLassoData(
        extracted.text,
        JSON.stringify(extracted.imagePaths),
        JSON.stringify(extracted.linkedFiles),
      );
      markLassoDataPrePopulated();
      FloatingToolbarBridge.showSendPanelFromBubble();
      return 'Send panel opened';
    }
    const { handleScreenshotSend } = require('./BackgroundService');
    await handleScreenshotSend();
    return 'Screenshot send: delegated to native';
  }

  if (action === 'lasso_send') {
    return OPEN_SEND_SCREEN;
  }

  if (action === 'screenshot_ai') {
    const { handleScreenshotAi } = require('./BackgroundService');
    await handleScreenshotAi();
    return 'Screenshot AI: delegated to native';
  }

  if (action === 'invert_ink') {
    try {
      await NativeModules.AIRelayModule?.openRelayMain?.('AIRelay');
      return 'Opened AIRelay';
    } catch (e: any) {
      console.warn('[ToolActions]: open AIRelay failed:', e);
      return `Open AIRelay failed: ${e?.message ?? e}`;
    }
  }

  if (action === 'insert_link') {
    try {
      const fpRes: any = await PluginCommAPI.getCurrentFilePath();
      const pgRes: any = await PluginCommAPI.getCurrentPageNum();
      if (fpRes?.success && fpRes.result) _filePath = fpRes.result;
      if (pgRes?.success && pgRes.result !== undefined) _pageNum = pgRes.result;
    } catch (e) {
      console.warn('[ToolActions] insert_link context refresh failed:', e);
    }

    if (_filePath && _pageNum !== null) {
      try {
        const layersRes: any = await PluginFileAPI.getLayers(_filePath, _pageNum);
        const currentLayer = Array.isArray(layersRes?.result)
          ? layersRes.result.find((layer: any) => layer.isCurrentLayer)
          : null;
        const currentLayerId = currentLayer?.layerId ?? currentLayer?.layerNum;
        if (currentLayerId !== undefined && currentLayerId !== 0) {
          console.warn('[ToolActions] insert_link blocked on non-main layer:', currentLayerId);
          NativeUIUtils.showErrorTipDialog(t('doc_link_main_only'));
          return 'Document links require the main layer';
        }
      } catch (e) {
        console.warn('[ToolActions] insert_link layer check failed:', e);
      }
    }

    if (_docLinkQueue.length === 0) {
      _docLinkQueue = FloatingToolbarBridge.drainDocLinkQueue();
    }
    if (_docLinkQueue.length > 0) {
      const docPath = _docLinkQueue.shift()!;
      console.log('[QUEUE-DBG/TS] docLink queue pop:', docPath, 'remaining:', _docLinkQueue.length);
      try { await _insertDocLinkDirect(docPath); } catch (_) {}
      return 'Doc link inserted from queue';
    }
    FloatingToolbarBridge.showDocLinkPanel(_filePath);
    return 'Doc link panel opened';
  }

  if (action === 'toggle_spacing') {
    const { getActiveMode } = require('./BackgroundService');
    const cur = getActiveMode();
    const next = cur === 'nospacing' ? 'paragraph' : 'nospacing';
    const mode = await toggleMode(next);
    return mode ? `Switched to ${mode}` : 'Mode toggled off';
  }

  if (action === 'insert_image') {
    if (_imageQueue.length === 0) {
      _imageQueue = FloatingToolbarBridge.drainImageQueue();
    }
    if (_imageQueue.length > 0) {
      const imgPath = _imageQueue.shift()!;
      console.log('[QUEUE-DBG/TS] image queue pop:', imgPath, 'remaining:', _imageQueue.length);
      try {

        try { await (PluginCommAPI as any).setLassoBoxState?.(2); } catch (_) {}
        await (PluginNoteAPI as any).insertImage(imgPath);
      } catch (_) {}
      return 'Image inserted from queue';
    }
    FloatingToolbarBridge.showImagePanel();
    return 'Image panel opened';
  }

  if (action === 'insert_doc_screenshot') {
    FloatingToolbarBridge.handleDocScreenshot();
    return 'Doc screenshot: delegated to native';
  }

  if (!action.startsWith('clip_save_')) {
    if (!await ctx()) return 'No file context';
  }

  try {
    if (action === 'layer_prev') {
      if (isRecognitionNote()) { NativeUIUtils.showErrorTipDialog(t('layer_err_recognition')); return ''; }
      if (await hasLassoSelection()) return moveLassoElementsLayer('up');
      await PluginNoteAPI.saveCurrentNote();
      return await layerPrev();
    }
    if (action === 'layer_next') {
      if (isRecognitionNote()) { NativeUIUtils.showErrorTipDialog(t('layer_err_recognition')); return ''; }
      if (await hasLassoSelection()) return moveLassoElementsLayer('down');
      await PluginNoteAPI.saveCurrentNote();
      return await layerNext();
    }

    if (action.startsWith('clip_paste_')) {
      const slot = action.charAt(action.length - 1);
      return await clipSmartAction(slot);
    }
    if (action.startsWith('clip_save_')) {
      const slot = action.charAt(action.length - 1);
      return await clipSave(slot);
    }
    if (action.startsWith('clip_clear_')) {
      const slot = action.charAt(action.length - 1);
      return await clipClear(slot);
    }

    return `Unknown: ${action}`;
  } catch (e) {
    console.error('[ToolActions]:', e);
    return `Error: ${String(e)}`;
  }
}

const LAYER_MOVABLE_TYPES = new Set([0, 200, 500, 700]);

async function hasLassoSelection(): Promise<boolean> {
  try {
    const r = await PluginCommAPI.getLassoRect();
    console.log('[ToolActions] hasLassoSelection:', r.success, r.result);
    return r.success && r.result != null;
  } catch { return false; }
}

async function moveLassoElementsLayer(direction: 'up' | 'down'): Promise<string> {
  if (!_filePath || _pageNum === null) return 'No context';

  try {
    const cntRes = await (PluginCommAPI as any).getLassoElementTypeCounts?.();
    const c = cntRes?.result;
    const hasTitle = (c?.titleNum ?? 0) > 0;
    const hasLink = ((c?.textLinkNum ?? 0) + (c?.trailLinkNum ?? 0) + (c?.todoLinkNum ?? 0)) > 0;
    const hasTextBox = ((c?.normalTextBoxNum ?? 0) + (c?.digestTextBoxNum ?? 0) + (c?.digestTextBoxEditableNum ?? 0)) > 0;
    const hasImage = (c?.bitmapNum ?? 0) > 0;
    if (hasTitle || hasLink || hasTextBox || hasImage) {
      NativeUIUtils.showErrorTipDialog(t('layer_err_unmovable'));
      return 'Unmovable element in selection';
    }
  } catch (_) {}

  const lr = await PluginFileAPI.getLayers(_filePath, _pageNum) as any;
  if (!lr?.success || !lr.result) return 'Get layers failed';
  let userLayerIds = (lr.result as any[])
    .map((l: any) => (l.layerId !== undefined ? l.layerId : l.layerNum) as number)
    .filter((id: number) => id >= 0)
    .sort((a: number, b: number) => a - b);
  if (userLayerIds.length === 0) return 'No layers';

  const elemRes = await PluginCommAPI.getLassoElements() as any;
  if (!elemRes?.success || !elemRes.result?.length) return 'No lasso elements';

  const lassoElements = elemRes.result as any[];
  const movable = lassoElements.filter((e: any) => LAYER_MOVABLE_TYPES.has(e.type));
  if (movable.length === 0) {
    NativeUIUtils.showErrorTipDialog(t('layer_err_unmovable'));
    return 'No movable elements in selection';
  }

  if (direction === 'up') {
    const maxLayer = Math.max(...movable.map((e: any) => e.layerNum ?? 0));
    const maxIdx = userLayerIds.indexOf(maxLayer);
    if (maxIdx === userLayerIds.length - 1) {
      NativeUIUtils.showErrorTipDialog(t('layer_err_need_new'));
      return 'Need more layers to move up';
    }
  }

  const targetLayerMap = new Map<number, number>();
  for (const el of movable) {
    const curLayer = el.layerNum ?? 0;
    const curIdx = userLayerIds.indexOf(curLayer);
    const targetIdx = direction === 'up' ? curIdx + 1 : curIdx - 1;
    if (targetIdx < 0 || targetIdx >= userLayerIds.length) continue;
    targetLayerMap.set(el.numInPage, userLayerIds[targetIdx]);
  }
  if (targetLayerMap.size === 0) {
    const msg = direction === 'up' ? t('layer_err_at_top') : t('layer_err_at_bottom');
    NativeUIUtils.showErrorTipDialog(msg);
    return direction === 'up' ? 'Already at top layer' : 'Already at bottom layer';
  }

  const fullRes = await PluginFileAPI.getElements(_pageNum, _filePath) as any;
  if (!fullRes?.success || !fullRes.result) return 'Get page elements failed';
  const allEls = fullRes.result as any[];

  try {
    const toMove = allEls.filter((e: any) => targetLayerMap.has(e.numInPage));
    if (toMove.length === 0) return 'No matching page elements';

    const insertByTarget = new Map<number, any[]>();
    const deleteBySource = new Map<number, number[]>();
    for (const el of toMove) {
      const src = el.layerNum ?? 0;
      const dst = targetLayerMap.get(el.numInPage)!;
      el.layerNum = dst;
      const ins = insertByTarget.get(dst);
      if (ins) ins.push(el); else insertByTarget.set(dst, [el]);
      const del = deleteBySource.get(src);
      if (del) del.push(el.numInPage); else deleteBySource.set(src, [el.numInPage]);
    }

    console.log('[ToolActions] moveLayer live insert/delete', toMove.length, 'elements');
    const moved = await moveLiveElements(_pageNum, insertByTarget, deleteBySource);
    if (!moved.ok) {
      NativeUIUtils.showErrorTipDialog(moved.error ?? 'Move layer failed');
      return `Move failed: ${moved.error ?? 'unknown'}`;
    }
    return `Moved ${toMove.length} element(s) ${direction}`;
  } finally {
    for (const el of allEls) { try { el?.recycle?.(); } catch (_) {} }
    for (const el of lassoElements) { try { el?.recycle?.(); } catch (_) {} }
  }
}

function normalizeLayerName(layer: any, id: number): void {
  if (typeof layer.name !== 'string' || !layer.name.trim()) {
    layer.name = id === 0 ? 'Main Layer' : `Layer ${id}`;
  }
  if (typeof layer.isVisible !== 'boolean') layer.isVisible = true;
  layer.layerId = id;
}

async function switchCurrentLayer(targetId: number, layers: any[]): Promise<boolean> {
  // LayerSchema rejects layerId=-1 (background). Mutate in place so names match the file.
  const toSend = layers.filter((l: any) => {
    const id = layerIdOf(l);
    return id >= 0 && id <= 3;
  });
  for (const l of toSend) {
    const id = layerIdOf(l);
    normalizeLayerName(l, id);
    l.isCurrentLayer = id === targetId;
  }
  console.log('[Layer] modifyLayers →', toSend.map((l: any) => ({
    id: l.layerId, name: l.name, cur: l.isCurrentLayer, vis: l.isVisible,
  })));
  const r: any = await PluginFileAPI.modifyLayers(_filePath!, _pageNum!, toSend);
  console.log('[Layer] modifyLayers ok=', r?.success, 'err=', r?.error, 'result=', r?.result);
  if (!r?.success) {
    if (r?.error?.message) NativeUIUtils.showErrorTipDialog(r.error.message);
    return false;
  }
  // Host loadNoteData already refreshes the open page. reloadFile races that write
  // and can restore the previous current layer.
  return true;
}

async function layerPrev(): Promise<string> {
  if (!_filePath || _pageNum === null) return 'No context';
  const lr = await PluginFileAPI.getLayers(_filePath, _pageNum);
  if (!lr.success || !lr.result) return 'Get layers failed';

  const allLayers = (lr.result as any[]).slice().sort((a: any, b: any) => layerIdOf(a) - layerIdOf(b));
  const current = allLayers.find((l: any) => l.isCurrentLayer) || allLayers[allLayers.length - 1];
  const currentId = layerIdOf(current);

  const above = allLayers.filter((l: any) => layerIdOf(l) > currentId);
  if (above.length > 0) {
    const targetId = layerIdOf(above[0]);
    const ok = await switchCurrentLayer(targetId, allLayers);
    return ok ? `Layer ${targetId}` : 'Switch failed';
  }

  const userLayers = allLayers.filter((l: any) => layerIdOf(l) >= 0);
  const maxUserId = userLayers.length > 0 ? Math.max(...userLayers.map((l: any) => layerIdOf(l))) : -1;
  if (maxUserId >= 3) { NativeUIUtils.showErrorTipDialog(t('layer_err_max')); return 'Max 4 user layers'; }

  const newId = maxUserId + 1;
  const ir: any = await PluginFileAPI.insertLayer(_filePath, _pageNum, {
    layerId: newId,
    name: `Layer ${newId + 1}`,
    isVisible: true,
    isCurrentLayer: true,
  });
  console.log('[Layer] insertLayer ok=', ir?.success, 'err=', ir?.error);

  if (ir?.success) return `New layer ${newId}`;
  NativeUIUtils.showErrorTipDialog(ir?.error?.message ?? t('layer_err_max'));
  return 'Create layer failed';
}

async function layerNext(): Promise<string> {
  if (!_filePath || _pageNum === null) return 'No context';
  const lr = await PluginFileAPI.getLayers(_filePath, _pageNum);
  if (!lr.success || !lr.result) return 'Get layers failed';

  const allLayers = (lr.result as any[]).slice().sort((a: any, b: any) => layerIdOf(a) - layerIdOf(b));
  const current = allLayers.find((l: any) => l.isCurrentLayer) || allLayers[allLayers.length - 1];
  const currentId = layerIdOf(current);

  const below = allLayers
    .filter((l: any) => layerIdOf(l) >= 0 && layerIdOf(l) < currentId)
    .sort((a: any, b: any) => layerIdOf(b) - layerIdOf(a));
  if (below.length === 0) { NativeUIUtils.showErrorTipDialog(t('layer_err_at_main')); return 'Already at bottom layer'; }

  const targetId = layerIdOf(below[0]);
  const ok = await switchCurrentLayer(targetId, allLayers);
  return ok ? `Layer ${targetId}` : 'Switch failed';
}

async function clipSmartAction(slot: string): Promise<string> {
  if (_clipBusy) return 'Clip busy';
  _clipBusy = true;
  try {
    let hasLasso = false;
    try {
      const lassoRes = await PluginCommAPI.getLassoRect();
      hasLasso = lassoRes.success && lassoRes.result != null;
    } catch {}

    if (hasLasso) {
      return await clipSave(slot);
    } else {

      try { await (PluginCommAPI as any).setLassoBoxState?.(2); } catch (_) {}
      return await clipPasteSticker(slot);
    }
  } finally {
    _clipBusy = false;
  }
}

async function clipSave(slot: string): Promise<string> {
  await ensureStickerDir();

  try {
    const cntRes = await (PluginCommAPI as any).getLassoElementTypeCounts?.();
    const c = cntRes?.result;
    const hasTitle = (c?.titleNum ?? 0) > 0;
    const hasLink = ((c?.textLinkNum ?? 0) + (c?.trailLinkNum ?? 0) + (c?.todoLinkNum ?? 0)) > 0;
    const hasTextBox = ((c?.normalTextBoxNum ?? 0) + (c?.digestTextBoxNum ?? 0) + (c?.digestTextBoxEditableNum ?? 0)) > 0;
    const hasImage = (c?.bitmapNum ?? 0) > 0;
    if (hasTitle) { NativeUIUtils.showErrorTipDialog(t('clip_err_title')); return 'Unsupported: title'; }
    if (hasLink)  { NativeUIUtils.showErrorTipDialog(t('clip_err_link'));  return 'Unsupported: link'; }
    if (hasTextBox) { NativeUIUtils.showErrorTipDialog(t('clip_err_textbox')); return 'Unsupported: textbox'; }
    if (hasImage) { NativeUIUtils.showErrorTipDialog(t('clip_err_image')); return 'Unsupported: image'; }
  } catch (_) {}

  const existingClips = await loadClips();
  const oldPath = existingClips[slot];
  const name = `quickbar_clip_${slot}_${Date.now()}.sticker`;
  const path = `${STICKER_DIR}/${name}`;

  console.log('[ToolActions]: clipSave slot=', slot, 'path=', path);

  if (oldPath && await getRNFS()?.exists(oldPath).catch(() => false)) {
    const confirmed = await NativeUIUtils.showRattaDialog(
      t('clip_overwrite'), t('btn_cancel'), t('btn_confirm'), false
    ).catch(() => true);
    if (!confirmed) return `Clip ${slot} overwrite cancelled`;
  }

  const commitSave = async (): Promise<string> => {
    if (oldPath) {
      try { await FileUtils.deleteFile(oldPath); } catch (_) {}
      try { await FileUtils.deleteFile(oldPath + '.meta.json'); } catch (_) {}
    }
    await PluginCommAPI.setLassoBoxState(2);
    const clips = await loadClips();
    clips[slot] = path;
    await saveClips(clips);
    return `Saved to clip ${slot}`;
  };

  console.log('[CLIP-DBG] ===== clipSave v2 (no saveStickerByLasso) =====');
  const elemRes = await PluginCommAPI.getLassoElements() as any;
  if (!elemRes?.success || !elemRes.result || !Array.isArray(elemRes.result) || elemRes.result.length === 0) {
    console.warn('[CLIP-DBG] getLassoElements failed or empty:', elemRes?.error);
    if (await lassoContainsPicture()) {
      NativeUIUtils.showErrorTipDialog(t('clip_err_image'));
      return 'Unsupported: image';
    }
    NativeUIUtils.showErrorTipDialog(t('clip_err_read_failed'));
    return `Save clip ${slot} failed (no elements)`;
  }

  const deviceType = await PluginManager.getDeviceType();
  let fileType: number | null = null;
  try {
    const fpRes: any = await PluginCommAPI.getCurrentFilePath();
    if (fpRes?.success && fpRes.result) {
      const ftRes: any = await (PluginFileAPI as any).getFileMachineType(fpRes.result);
      if (ftRes?.success && typeof ftRes.result === 'number') fileType = ftRes.result;
    }
  } catch (_) {}

  const machineType = pickStickerMachineType(elemRes.result, fileType, deviceType);
  console.log('[CLIP-DBG] deviceType=', deviceType, 'fileType=', fileType, 'machineType=', machineType);
  console.log('[CLIP-DBG] convertElement2Sticker elements=', elemRes.result.length, 'machineType=', machineType);
  const convertRes: any = await NativeModules.NativePluginAPI.convertElement2Sticker({
    machineType,
    elements: elemRes.result,
    stickerPath: path,
  });
  console.log('[CLIP-DBG] convertElement2Sticker result:', JSON.stringify(convertRes));

  if (!convertRes?.success || convertRes.result === false) {
    console.warn('[CLIP-DBG] convertElement2Sticker FAILED');
    NativeUIUtils.showErrorTipDialog(t('clip_save_failed'));
    return `Save clip ${slot} failed`;
  }

  return await commitSave();
}

async function lassoContainsPicture(): Promise<boolean> {
  let elements: any[] = [];
  try {
    const rectRes: any = await PluginCommAPI.getLassoRect();
    const rect = rectRes?.success ? rectRes.result : null;
    if (!rect || rect.right <= rect.left || rect.bottom <= rect.top) return false;

    const fpRes: any = await PluginCommAPI.getCurrentFilePath();
    const pgRes: any = await PluginCommAPI.getCurrentPageNum();
    if (!fpRes?.success || !fpRes.result || !pgRes?.success || pgRes.result == null) return false;

    const psRes: any = await PluginFileAPI.getPageSize(fpRes.result, pgRes.result);
    const pageW = psRes?.result?.width ?? 0;
    const pageH = psRes?.result?.height ?? 0;
    if (!pageW || !pageH) return false;

    const elemRes: any = await PluginFileAPI.getElements(pgRes.result, fpRes.result);
    elements = elemRes?.success && Array.isArray(elemRes.result) ? elemRes.result : [];
    for (const el of elements) {
      if (el?.type !== 200 || !el.picture?.rect) continue; // 200 = Element.TYPE_PICTURE
      if (!(el.maxX > 0) || !(el.maxY > 0)) continue;
      const sx = pageW / el.maxX;
      const sy = pageH / el.maxY;
      const p = el.picture.rect;
      const hit = p.left * sx < rect.right && p.right * sx > rect.left &&
                  p.top * sy < rect.bottom && p.bottom * sy > rect.top;
      if (hit) {
        console.log('[CLIP-DBG] lassoContainsPicture: hit picRect=', JSON.stringify(p),
          'scale=', sx.toFixed(3), sy.toFixed(3), 'lasso=', JSON.stringify(rect));
        return true;
      }
    }
  } catch (e) {
    console.warn('[CLIP-DBG] lassoContainsPicture error:', e);
  } finally {
    for (const el of elements) { try { el?.recycle?.(); } catch (_) {} }
  }
  return false;
}

function pickStickerMachineType(elements: any[], fileType: number | null, deviceType: number): number {
  const A5X2 = 5;
  let big = 0, small = 0;
  for (const el of elements) {
    const mx = el?.maxX;
    if (typeof mx !== 'number' || mx <= 0) continue;
    if (mx >= 16000 && mx !== 21098) big++; else small++;
  }
  console.log('[CLIP-DBG] pickStickerMachineType big=', big, 'small=', small,
    'maxXs=', JSON.stringify(elements.map((el: any) => el?.maxX)));
  if (big === 0 && small === 0) {
    return fileType != null ? fileType : deviceType;
  }
  if (big > 0 && small > 0) {
    console.warn('[CLIP-DBG] mixed maxX families in one lasso; sizes of minority family will drift');
  }
  if (big >= small) return A5X2;
  if (fileType != null && fileType !== A5X2) return fileType;
  if (deviceType !== A5X2) return deviceType;
  return 4; // A6X2
}

async function clipPasteSticker(slot: string): Promise<string> {
  const clips = await loadClips();
  const stored = clips[slot];
  if (!stored) {
    NativeUIUtils.showErrorTipDialog(t('clip_empty_hint'));
    return 'Clip empty';
  }

  let paths: string[];
  if (stored.startsWith('/')) {

    paths = [stored];
  } else {

    paths = [`${STICKER_DIR}/${stored}`];
  }

  for (const path of paths) {

    if (path.endsWith('.textclip.json')) {
      try {
        const RNFS = getRNFS();
        if (!RNFS) return `Paste clip ${slot} failed (no fs)`;
        const content = await RNFS.readFile(path, 'utf8');
        const payload = JSON.parse(content);
        const items: any[] = Array.isArray(payload?.items) ? payload.items : [];
        if (items.length === 0) {
          return `Paste clip ${slot} failed (empty textclip)`;
        }
        let okAll = true;
        let unionRect = { left: Infinity, top: Infinity, right: -Infinity, bottom: -Infinity };
        for (const tb of items) {
          console.log('[ToolActions] insertText input textRect:', JSON.stringify(tb.textRect));
          const r: any = await (PluginNoteAPI as any).insertText?.(tb);
          console.log('[ToolActions] insertText result:', JSON.stringify(r));
          if (!r?.success) {
            okAll = false;
            console.warn('[ToolActions]: insertText failed:', r);
          } else if (tb.textRect) {
            unionRect.left = Math.min(unionRect.left, tb.textRect.left);
            unionRect.top = Math.min(unionRect.top, tb.textRect.top);
            unionRect.right = Math.max(unionRect.right, tb.textRect.right);
            unionRect.bottom = Math.max(unionRect.bottom, tb.textRect.bottom);
          }
        }

        if (unionRect.left < Infinity) {
          try {
            await new Promise(r => setTimeout(r, 500));

            await PluginCommAPI.lassoElements({ left: 0, top: 0, right: 1, bottom: 1 });
            await new Promise(r => setTimeout(r, 200));
            const pad = 5;
            const lassoTarget = {
              left: unionRect.left - pad, top: unionRect.top - pad,
              right: unionRect.right + pad, bottom: unionRect.bottom + pad,
            };
            console.log('[ToolActions] lassoElements target rect:', JSON.stringify(lassoTarget));
            const lr: any = await PluginCommAPI.lassoElements(lassoTarget);
            console.log('[ToolActions] lassoElements result:', JSON.stringify(lr));
            await (PluginCommAPI as any).setLassoBoxState?.(0);
          } catch (e) { console.warn('[ToolActions] lassoElements after textclip paste:', e); }
        }

        return okAll ? `Pasted clip ${slot}` : `Paste clip ${slot} partial`;
      } catch (e) {
        console.warn('[ToolActions]: textclip paste error:', e);
        return `Paste clip ${slot} failed`;
      }
    }

    console.log('[CLIP-DBG] clipPaste path=', path);
    try {
      const pasteSzRes: any = await PluginCommAPI.getStickerSize(path);
      console.log('[CLIP-DBG] paste getStickerSize before insert=', JSON.stringify(pasteSzRes));
    } catch (e) {
      console.warn('[CLIP-DBG] paste getStickerSize before insert failed:', e);
    }
    try {
      const r = await PluginCommAPI.insertSticker(path);
      console.log('[CLIP-DBG] insertSticker result:', JSON.stringify(r));
      if (r.success) {
        return `Pasted clip ${slot}`;
      }
    } catch (e) { console.warn('[CLIP-DBG] insertSticker error:', e); }

    try {
      const r2 = await PluginCommAPI.insertSticker(path + '.sticker');
      console.log('[CLIP-DBG] insertSticker(.sticker) result:', JSON.stringify(r2));
      if (r2.success) {
        return `Pasted clip ${slot}`;
      }
    } catch (e) { console.warn('[CLIP-DBG] insertSticker(.sticker) error:', e); }
  }

  return `Paste clip ${slot} failed`;
}

const ELEMENT_TYPE_PICTURE = 200;

async function repairPictureElementPaths(
  elements: any[], notePath: string, page: number,
): Promise<string[]> {
  const RNFS = getRNFS();
  const broken: any[] = [];
  for (const el of elements) {
    if (el?.type !== ELEMENT_TYPE_PICTURE) continue;
    const p = el.picture?.picturePath;
    let exists = false;
    if (p && RNFS) { try { exists = await RNFS.exists(p); } catch (_) {} }
    if (!exists) broken.push(el);
  }
  if (broken.length === 0) return [];

  const tmpFiles: string[] = [];
  const ts = Date.now();
  const previewPath = `${STICKER_DIR}/tmp_layer0_${ts}.png`;

  let ok = false;
  try {
    const r0: any = await PluginNoteAPI.generateLayerPreviewImage(notePath, page, 0, previewPath);
    ok = r0?.success === true && r0.result !== false &&
      (RNFS ? await RNFS.exists(previewPath) : true);
  } catch (e) { console.warn('[SNAP] layer preview(0) error:', e); }
  if (!ok) {
    try {
      const r1: any = await PluginNoteAPI.generateLayerPreviewImage(notePath, page, -1, previewPath);
      ok = r1?.success === true && r1.result !== false &&
        (RNFS ? await RNFS.exists(previewPath) : true);
    } catch (e) { console.warn('[SNAP] layer preview(-1) error:', e); }
  }
  if (!ok) {
    console.warn('[SNAP] layer preview unavailable, pictures cannot be repaired');
    return tmpFiles;
  }
  tmpFiles.push(previewPath);

  let pageW = 0;
  let pageH = 0;
  try {
    const psRes: any = await PluginFileAPI.getPageSize(notePath, page);
    if (psRes?.success && psRes.result) {
      pageW = psRes.result.width;
      pageH = psRes.result.height;
    }
  } catch (_) {}

  for (let i = 0; i < broken.length; i++) {
    const el = broken[i];
    const rect = el.picture?.rect;
    if (!rect) continue;
    const outPath = `${STICKER_DIR}/tmp_pic_${ts}_${i}.png`;
    try {
      await NativeModules.InklingImageUtil.cropRegion(
        previewPath, pageW, pageH,
        rect.left, rect.top, rect.right, rect.bottom, outPath,
      );
      el.picture.picturePath = outPath;
      tmpFiles.push(outPath);
      console.log('[SNAP] picture repaired:', JSON.stringify(rect), '→', outPath);
    } catch (e) {
      console.warn('[SNAP] picture crop failed:', e);
    }
  }
  return tmpFiles;
}

export async function snapshotCreate(): Promise<string> {
  await ensureStickerDir();

  const fpRes: any = await PluginCommAPI.getCurrentFilePath();
  const pgRes: any = await PluginCommAPI.getCurrentPageNum();
  if (!fpRes?.success || !fpRes.result || !pgRes?.success || pgRes.result == null) {
    NativeUIUtils.showErrorTipDialog(t('snapshot_create_failed'));
    return 'Snapshot: no note context';
  }
  const notePath: string = fpRes.result;
  const page: number = pgRes.result;

  const elemRes: any = await PluginFileAPI.getElements(page, notePath);
  const elements: any[] = elemRes?.success && Array.isArray(elemRes.result) ? elemRes.result : [];
  if (elements.length === 0) {
    NativeUIUtils.showErrorTipDialog(t('snapshot_empty_page'));
    return 'Snapshot: page empty';
  }

  let tmpFiles: string[] = [];
  try {
    const deviceType = await PluginManager.getDeviceType();
    let fileType: number | null = null;
    try {
      const ftRes: any = await (PluginFileAPI as any).getFileMachineType(notePath);
      if (ftRes?.success && typeof ftRes.result === 'number') fileType = ftRes.result;
    } catch (_) {}
    const machineType = pickStickerMachineType(elements, fileType, deviceType);

    const ts = Date.now();
    const stickerPath = `${STICKER_DIR}/quickbar_snapshot_${ts}.sticker`;

    tmpFiles = await repairPictureElementPaths(elements, notePath, page);
    let convertElements = elements;
    if (elements.some(el => el?.type === ELEMENT_TYPE_PICTURE)) {
      const RNFS = getRNFS();
      const usable: any[] = [];
      for (const el of elements) {
        if (el?.type === ELEMENT_TYPE_PICTURE) {
          const p = el.picture?.picturePath;
          let exists = false;
          if (p && RNFS) { try { exists = await RNFS.exists(p); } catch (_) {} }
          if (!exists) {
            console.warn('[SNAP] dropping picture with unreadable path:', p);
            continue;
          }
        }
        usable.push(el);
      }
      convertElements = usable;
    }
    if (convertElements.length === 0) {
      NativeUIUtils.showErrorTipDialog(t('snapshot_create_failed'));
      return 'Snapshot: no convertible elements';
    }

    console.log('[SNAP] create page=', page, 'elements=', convertElements.length,
      '(raw=', elements.length, ') machineType=', machineType, 'path=', stickerPath);
    const convertRes: any = await NativeModules.NativePluginAPI.convertElement2Sticker({
      machineType,
      elements: convertElements,
      stickerPath,
    });
    if (!convertRes?.success || convertRes.result === false) {
      console.warn('[SNAP] convertElement2Sticker FAILED:', JSON.stringify(convertRes));
      NativeUIUtils.showErrorTipDialog(t('snapshot_create_failed'));
      return 'Snapshot: convert failed';
    }

    let thumbPath = `${STICKER_DIR}/quickbar_snapshot_${ts}.png`;
    try {
      const pngRes: any = await PluginFileAPI.generateNotePng({
        notePath, page, times: 1, pngPath: thumbPath, type: 1,
      });
      if (!pngRes?.success || pngRes.result === false) {
        console.warn('[SNAP] generateNotePng failed:', JSON.stringify(pngRes));
        thumbPath = '';
      }
    } catch (e) {
      console.warn('[SNAP] generateNotePng error:', e);
      thumbPath = '';
    }

    const records = await loadSnapshots();
    records.unshift({
      id: String(ts),
      sticker: stickerPath,
      thumb: thumbPath,
      note: notePath,
      page,
      ts,
    });
    await saveSnapshots(records);
    return 'Snapshot created';
  } finally {
    for (const f of tmpFiles) { try { await FileUtils.deleteFile(f); } catch (_) {} }
    for (const el of elements) { try { el?.recycle?.(); } catch (_) {} }
  }
}

export async function snapshotRestore(stickerPath: string): Promise<string> {
  const RNFS = getRNFS();
  if (RNFS && !(await RNFS.exists(stickerPath).catch(() => false))) {
    NativeUIUtils.showErrorTipDialog(t('snapshot_missing'));
    return 'Snapshot file missing';
  }
  try { await (PluginCommAPI as any).setLassoBoxState?.(2); } catch (_) {}
  try {
    const r: any = await PluginCommAPI.insertSticker(stickerPath);
    console.log('[SNAP] insertSticker result:', JSON.stringify(r));
    if (r?.success) return 'Snapshot restored';
  } catch (e) { console.warn('[SNAP] insertSticker error:', e); }
  NativeUIUtils.showErrorTipDialog(t('snapshot_restore_failed'));
  return 'Snapshot restore failed';
}

export async function snapshotDelete(id: string): Promise<string> {
  const records = await loadSnapshots();
  const target = records.find(r => r.id === id);
  if (!target) return 'Snapshot not found';
  try { await FileUtils.deleteFile(target.sticker); } catch (_) {}
  if (target.thumb) { try { await FileUtils.deleteFile(target.thumb); } catch (_) {} }
  await saveSnapshots(records.filter(r => r.id !== id));
  return 'Snapshot deleted';
}

async function clipClear(slot: string): Promise<string> {
  const clips = await loadClips();
  const oldPath = clips[slot];
  if (oldPath) {
    try { await FileUtils.deleteFile(oldPath); } catch (_) {}
    try { await FileUtils.deleteFile(oldPath + '.meta.json'); } catch (_) {}
  }
  clips[slot] = null;
  await saveClips(clips);
  return `Clip ${slot} cleared`;
}

export interface PaletteLassoInfo {
  elementNums: number[];
  strokeCount: number;
  geometryCount: number;
  avgThickness: number;
  hasMarkerStroke: boolean;
  dominantPenType: number | null;
  dominantPenColor: number | null;
  elements?: any[];
}

const PEN_TYPE_MARKER = 11;

export async function getPaletteLassoInfo(retainElements?: boolean): Promise<PaletteLassoInfo | null> {
  const t0 = Date.now();
  console.log('[PLT/stats] START');
  const elemRes = await PluginCommAPI.getLassoElements() as any;
  if (!elemRes?.success || !Array.isArray(elemRes.result) || elemRes.result.length === 0) {
    console.log(`[PLT/stats] getLassoElements empty ok=${elemRes?.success} n=${elemRes?.result?.length ?? 0} +${Date.now() - t0}ms`);
    return null;
  }
  const els: any[] = elemRes.result;
  console.log(`[PLT/stats] ${els.length} raw elements +${Date.now() - t0}ms`);
  let shouldRecycle = true;
  try {
    const strokes = els.filter((el: any) => el?.type === 0);
    const geoms   = els.filter((el: any) => el?.type === 700);
    const skipped = els.length - strokes.length - geoms.length;
    const elementNums = [...strokes, ...geoms]
      .map((el: any) => el?.numInPage)
      .filter((n: any): n is number => typeof n === 'number');
    console.log(`[PLT/stats] strokes=${strokes.length} geoms=${geoms.length} skip=${skipped} validNums=${elementNums.length}`);
    if (elementNums.length === 0) return null;

    const thicknessVals: number[] = [];
    for (const el of strokes) if (typeof el?.thickness === 'number') thicknessVals.push(el.thickness);
    for (const el of geoms)   if (typeof el?.geometry?.penWidth === 'number') thicknessVals.push(el.geometry.penWidth);
    const avgThickness = thicknessVals.length
      ? Math.round(thicknessVals.reduce((a, b) => a + b, 0) / thicknessVals.length)
      : 100;

    let resolvedDefaultPenType: number | null = null;
    if (strokes.some((el: any) => el?.stroke?.penType === 1)) {
      try {
        const pi = await PluginCommAPI.getPenInfo();
        if (pi?.success && pi.result != null) resolvedDefaultPenType = pi.result.type ?? null;
        console.log(`[PLT/stats] resolvedDefaultPenType=${resolvedDefaultPenType}`);
      } catch (e) {
        console.warn('[PLT/stats] getPenInfo failed:', e);
      }
    }

    const penTypeCount = new Map<number, number>();
    const penColorCount = new Map<number, number>();
    for (const el of strokes) {
      let pt = el?.stroke?.penType;
      if (pt === 1 && resolvedDefaultPenType != null) pt = resolvedDefaultPenType;
      if (typeof pt === 'number') penTypeCount.set(pt, (penTypeCount.get(pt) ?? 0) + 1);
      const pc = el?.stroke?.penColor;
      if (typeof pc === 'number') penColorCount.set(pc, (penColorCount.get(pc) ?? 0) + 1);
    }
    for (const el of geoms) {
      const pc = el?.geometry?.penColor;
      if (typeof pc === 'number') penColorCount.set(pc, (penColorCount.get(pc) ?? 0) + 1);
    }
    const dominant = (m: Map<number, number>): number | null => {
      let best: number | null = null, bestN = 0;
      for (const [k, v] of m) if (v > bestN) { best = k; bestN = v; }
      return best;
    };

    const info: PaletteLassoInfo = {
      elementNums,
      strokeCount: strokes.length,
      geometryCount: geoms.length,
      avgThickness,
      hasMarkerStroke: penTypeCount.has(PEN_TYPE_MARKER),
      dominantPenType: dominant(penTypeCount),
      dominantPenColor: dominant(penColorCount),
    };
    if (retainElements) {
      info.elements = els;
      shouldRecycle = false;
    }
    console.log(`[PLT/stats] result: s=${info.strokeCount} g=${info.geometryCount} avgT=${info.avgThickness} domPT=${info.dominantPenType} domPC=${info.dominantPenColor} marker=${info.hasMarkerStroke} retain=${!!retainElements} +${Date.now() - t0}ms`);
    return info;
  } finally {
    if (shouldRecycle) {
      for (const el of els) { try { el?.recycle?.(); } catch (_) {} }
      console.log(`[PLT/stats] recycled ${els.length} +${Date.now() - t0}ms`);
    }
  }
}

let modeExitSub: EmitterSubscription | null = null;

export function attachModeListeners(): void {
  modeExitSub?.remove();
  modeExitSub = FloatingToolbarBridge.onToolModeExit(({ toolAction }) => {
    switch (toolAction) {
      case 'insert_text':
      case 'text_recv_nospacing':
      case 'text_recv_paragraph':
        FloatingBubbleBridge.hide();
        break;
      case 'voice_transcribe':
        stopAiMode();
        break;
    }
  });
}

export function detachModeListeners(): void {
  modeExitSub?.remove();
  modeExitSub = null;
}

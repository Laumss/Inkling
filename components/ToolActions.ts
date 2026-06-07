import { PluginCommAPI, PluginFileAPI, PluginNoteAPI, PluginManager, NativeUIUtils } from 'sn-plugin-lib';
import { NativeModules, EmitterSubscription, Dimensions } from 'react-native';
import RNFS from 'react-native-fs';
import { loadClips, saveClips } from './ToolPresets';
import { t } from './i18n';
import { toggleMode, handleAiSend, getLastMode, stopAiMode, ensureLocalSendReady } from './BackgroundService';
import FloatingToolbarBridge from './FloatingToolbarBridge';
import FloatingBubbleBridge from './BubbleBridges';

const { FloatingToolbar } = NativeModules;

let _filePath: string | null = null;
let _pageNum: number | null = null;

let _imageQueue: string[] = [];
let _docLinkQueue: string[] = [];
let _clipBusy = false;

const STICKER_DIR = '/sdcard/MyStyle/Sticker';

async function ensureStickerDir(): Promise<void> {
  try {
    const exists = await RNFS.exists(STICKER_DIR);
    if (!exists) await RNFS.mkdir(STICKER_DIR);
  } catch (e) {
    console.warn('[ToolActions]: ensureStickerDir failed:', e);
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
  // 插入文档链接前，先固化上一次的套索选中态
  try { await (PluginCommAPI as any).setLassoBoxState?.(2); } catch (_) {}

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
      const psRes: any = await PluginFileAPI.getPageSize(notePath, pageNum);
      if (psRes?.success && psRes.result) {
        pageW = psRes.result.width;
        pageH = psRes.result.height;
      }
    }
  } catch (_) {}

  const linkW = Math.min(linkName.length * 30 + 40, pageW * 0.6);
  const left = Math.round((pageW - linkW) / 2);
  let top = Math.round(pageH * 0.15);
  const fontSize = 49;
  const lineH = fontSize + 10;

  // 检测页面上已有的文本框/链接，避免重叠插入
  try {
    if (notePath) {
      const elRes: any = await PluginFileAPI.getElements(pageNum, notePath);
      if (elRes?.success && Array.isArray(elRes.result)) {
        const occupied: { top: number; bottom: number }[] = [];
        for (const el of elRes.result) {
          try {
            const elType = el.type;
            // type 500-502: 文本框 / 文本摘要
            if (typeof elType === 'number' && elType >= 500 && elType <= 502) {
              const rect = el.textBox?.textRect;
              if (rect && typeof rect.top === 'number' && typeof rect.bottom === 'number') {
                occupied.push({ top: rect.top, bottom: rect.bottom });
              }
            }
            // type 600: 链接元素
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
        // 逐个检查碰撞并下移
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
        // 确保不超出页面底部
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
  const isDocFile = ['epub', 'pdf', 'cbz', 'doc', 'docx', 'djvu', 'mobi', 'fb2'].includes(ext);
  const destPath = docPath.replace('/sdcard/', '/storage/emulated/0/');

  const textLink = {
    destPath,
    destPage: -1,
    style: 0,
    linkType: isDocFile ? 2 : 0,
    rect: { left, top, right: left + Math.round(linkW), bottom: top + lineH },
    fontSize,
    fullText: linkName,
    showText: linkName,
    isItalic: 0,
  };
  await (PluginNoteAPI as any).insertTextLink(textLink);

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
    console.log('[ToolActions]: screenshot_ai — placeholder');
    return 'Screenshot AI: coming soon';
  }

  if (action === 'invert_ink') {
    try {
      await FloatingToolbar?.launchActivity(
        'com.dictation.server.relay',
        'com.dictation.server.MainActivity',
      );
      return 'Launched dictation relay';
    } catch (e: any) {
      console.warn('[ToolActions]: launchActivity failed:', e);
      return `Launch failed: ${e?.message ?? e}`;
    }
  }

  if (action === 'insert_link') {
    if (_docLinkQueue.length === 0) {
      _docLinkQueue = FloatingToolbarBridge.drainDocLinkQueue();
    }
    if (_docLinkQueue.length > 0) {
      const docPath = _docLinkQueue.shift()!;
      console.log('[QUEUE-DBG/TS] docLink queue pop:', docPath, 'remaining:', _docLinkQueue.length);
      try { await _insertDocLinkDirect(docPath); } catch (_) {}
      return 'Doc link inserted from queue';
    }
    ensureLocalSendReady().catch(() => {});
    FloatingToolbarBridge.showDocLinkPanel();
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
        // 插入新图前，先固化上一张：清掉上一张插入后残留的套索选中态（等价于手动点空白处）。
        // 插入后保留套索态以便调整；最后一张由用户手动点空白固化。
        // 第一张时无套索，调用失败无害。禁止用 saveCurrentNote 固化（见 CLAUDE.md）。
        try { await (PluginCommAPI as any).setLassoBoxState?.(2); } catch (_) {}
        await (PluginNoteAPI as any).insertImage(imgPath);
      } catch (_) {}
      return 'Image inserted from queue';
    }
    ensureLocalSendReady().catch(() => {});
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

  // Get full element data (including stroke binary data) for the elements to move
  const fullRes = await PluginFileAPI.getElements(_pageNum, _filePath) as any;
  if (!fullRes?.success || !fullRes.result) return 'Get page elements failed';

  const toMove = (fullRes.result as any[])
    .filter((e: any) => targetLayerMap.has(e.numInPage));
  const toInsert = toMove.map((e: any) => ({
    ...e,
    layerNum: targetLayerMap.get(e.numInPage),
  }));
  const numsToDelete = toMove.map((e: any) => e.numInPage as number);

  console.log('[ToolActions] moveLayer: deleting', numsToDelete.length,
    'elements, inserting to target layers');

  // modifyElements doesn't change layerNum in native — use delete + insert instead
  await PluginNoteAPI.saveCurrentNote();

  // Step 1: Delete elements from source layer
  const delRes = await PluginFileAPI.deleteElements(_filePath, _pageNum, numsToDelete) as any;
  console.log('[ToolActions] deleteElements result:', delRes?.success, delRes?.error);
  if (!delRes?.success) {
    await PluginCommAPI.reloadFile();
    return `Delete failed: ${delRes?.error?.message ?? 'unknown'}`;
  }

  // Step 2: Insert elements into target layer
  const insRes = await PluginFileAPI.insertElements(_filePath, _pageNum, toInsert) as any;
  console.log('[ToolActions] insertElements result:', insRes?.success, insRes?.error);
  if (!insRes?.success) {
    await PluginCommAPI.reloadFile();
    return `Insert failed: ${insRes?.error?.message ?? 'unknown'}`;
  }

  await PluginCommAPI.reloadFile();
  return `Moved ${toMove.length} element(s) ${direction}`;
}

async function layerPrev(): Promise<string> {
  if (!_filePath || _pageNum === null) return 'No context';
  const lr = await PluginFileAPI.getLayers(_filePath, _pageNum);
  if (!lr.success || !lr.result) return 'Get layers failed';

  const allLayers = lr.result.map((l: any) => ({
    ...l,
    id: l.layerId !== undefined ? l.layerId : l.layerNum
  })).sort((a: any, b: any) => a.id - b.id);

  const current = allLayers.find((l: any) => l.isCurrentLayer) || allLayers[allLayers.length - 1];
  const currentId = current.id;

  const above = allLayers.filter((l: any) => l.id > currentId);
  if (above.length > 0) {
    const target = above[0];
    const updated = allLayers
      .filter((l: any) => l.id >= 0)
      .map((l: any) => ({
        layerId: l.id,
        name: l.name,
        isVisible: l.isVisible,
        isCurrentLayer: l.id === target.id
      }));
    const r = await PluginFileAPI.modifyLayers(_filePath, _pageNum, updated);
    if (r.success) { await PluginCommAPI.reloadFile(); return `Layer ${target.id}`; }
    return 'Switch failed';
  }

  const userLayers = allLayers.filter((l: any) => l.id >= 0);
  const maxUserId = userLayers.length > 0 ? Math.max(...userLayers.map((l: any) => l.id)) : -1;
  if (maxUserId >= 3) { NativeUIUtils.showErrorTipDialog(t('layer_err_max')); return 'Max 4 user layers'; }

  const newId = maxUserId + 1;
  const ir = await PluginFileAPI.insertLayer(_filePath, _pageNum, {
    layerId: newId,
    name: `Layer ${newId + 1}`,
    isVisible: true,
    isCurrentLayer: true,
  });

  if (ir.success) {
    await PluginCommAPI.reloadFile();
    return `New layer ${newId}`;
  }
  return 'Create layer failed';
}

async function layerNext(): Promise<string> {
  if (!_filePath || _pageNum === null) return 'No context';
  const lr = await PluginFileAPI.getLayers(_filePath, _pageNum);
  if (!lr.success || !lr.result) return 'Get layers failed';

  const allLayers = lr.result.map((l: any) => ({
    ...l,
    id: l.layerId !== undefined ? l.layerId : l.layerNum
  })).sort((a: any, b: any) => a.id - b.id);

  const current = allLayers.find((l: any) => l.isCurrentLayer) || allLayers[allLayers.length - 1];
  const currentId = current.id;

  const below = allLayers.filter((l: any) => l.id >= 0 && l.id < currentId).sort((a: any, b: any) => b.id - a.id);
  if (below.length === 0) { NativeUIUtils.showErrorTipDialog(t('layer_err_at_main')); return 'Already at bottom layer'; }

  const target = below[0];
  const updated = allLayers
    .filter((l: any) => l.id >= 0)
    .map((l: any) => ({
      layerId: l.id,
      name: l.name,
      isVisible: l.isVisible,
      isCurrentLayer: l.id === target.id
    }));
  const r = await PluginFileAPI.modifyLayers(_filePath, _pageNum, updated);
  if (r.success) { await PluginCommAPI.reloadFile(); return `Layer ${target.id}`; }
  return 'Switch failed';
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
      // 只有在确定要粘贴时，才在粘贴前固化清除套索状态
      try { await (PluginCommAPI as any).setLassoBoxState?.(2); } catch (_) {}
      return await clipPasteSticker(slot);
    }
  } finally {
    _clipBusy = false;
  }
}

async function clipSave(slot: string): Promise<string> {
  await ensureStickerDir();

  // 如果剪贴板已有内容，弹出确认覆盖对话框
  const existingClips = await loadClips();
  if (existingClips[slot]) {
    try {
      const confirmed = await NativeUIUtils.showRattaDialog(
        t('clip_overwrite'), t('btn_cancel'), t('btn_confirm'), true
      );
      if (!confirmed) return 'Clip save cancelled';
    } catch (_) {}
  }

  try {
    const cntRes = await (PluginCommAPI as any).getLassoElementTypeCounts?.();
    const c = cntRes?.result;

    if ((c?.titleNum ?? 0) > 0) {
      NativeUIUtils.showErrorTipDialog(t('clip_err_title'));
      return 'Title clip not supported';
    }

    const linkCount = (c?.textLinkNum ?? 0) + (c?.trailLinkNum ?? 0) + (c?.todoLinkNum ?? 0);
    if (linkCount > 0) {
      NativeUIUtils.showErrorTipDialog(t('clip_err_link'));
      return 'Link clip not supported';
    }

    const tbCount =
      (c?.normalTextBoxNum ?? 0) +
      (c?.digestTextBoxNum ?? 0) +
      (c?.digestTextBoxEditableNum ?? 0);
    if (tbCount > 0) {
      NativeUIUtils.showErrorTipDialog(t('clip_err_textbox'));
      return 'TextBox clip not supported';
    }

    if ((c?.bitmapNum ?? 0) > 0) {
      NativeUIUtils.showErrorTipDialog(t('clip_err_image'));
      return 'Image clip not supported';
    }
  } catch (e) {
    console.warn('[ToolActions]: getLassoElementTypeCounts failed, fall through to sticker:', e);
  }

  const name = `quickbar_clip_${slot}_${Date.now()}.sticker`;
  const path = `${STICKER_DIR}/${name}`;

  console.log('[ToolActions]: clipSave slot=', slot, 'path=', path);

  const lassoRectRes = await PluginCommAPI.getLassoRect();
  console.log('[ToolActions]: lasso rect before save =', JSON.stringify(lassoRectRes?.result));
  const saveRes = await PluginCommAPI.saveStickerByLasso(path);
  if (saveRes.success) {
    const stickerSizeRes = await PluginCommAPI.getStickerSize(path);
    console.log('[ToolActions]: saveStickerByLasso ok, stickerSize =', JSON.stringify(stickerSizeRes?.result));
    await PluginCommAPI.setLassoBoxState(2);
    const clips = await loadClips();
    clips[slot] = path;
    await saveClips(clips);
    return `Saved to clip ${slot}`;
  }

  console.warn('[ToolActions]: saveStickerByLasso failed, fallback to convertElement2Sticker:', saveRes);

  const elemRes = await PluginCommAPI.getLassoElements() as any;
  if (!elemRes?.success || !elemRes.result || !Array.isArray(elemRes.result) || elemRes.result.length === 0) {
    console.warn('[ToolActions]: getLassoElements failed or empty:', elemRes);
    return `Save clip ${slot} failed (no elements)`;
  }

  const deviceType = await PluginManager.getDeviceType();

  let resolvedPenType: number | null = null;
  let resolvedPenWidth: number | null = null;
  try {
    const strokeEls = (elemRes.result as any[]).filter((el: any) => el?.stroke != null);
    const allDefault = strokeEls.length > 0 && strokeEls.every((el: any) => el.stroke.penType === 1);
    if (allDefault) {
      const penInfoRes = await PluginCommAPI.getPenInfo();
      if (penInfoRes.success && penInfoRes.result != null) {
        resolvedPenType = penInfoRes.result.type;
        resolvedPenWidth = penInfoRes.result.width ?? null;
        console.log('[ToolActions]: clipSave penInfo type=', resolvedPenType,
          'width=', resolvedPenWidth,
          '— SDK defaulted all penTypes to 1, injecting real type+width');
      }
    } else {
      console.log('[ToolActions]: clipSave penTypes from SDK=',
        strokeEls.map((el: any) => el.stroke.penType));
    }
  } catch (e) {
    console.warn('[ToolActions]: getPenInfo failed:', e);
  }

  const elementsForConvert = resolvedPenType != null
    ? (elemRes.result as any[]).map((el: any) => {
        if (el?.stroke != null) {
          const strokePatch: any = { penType: resolvedPenType! };
          if (resolvedPenWidth != null) strokePatch.penWidth = resolvedPenWidth;
          return { ...el, stroke: { ...el.stroke, ...strokePatch } };
        }
        return el;
      })
    : elemRes.result;

  let convertRes = await PluginCommAPI.convertElement2Sticker({
    machineType: deviceType,
    elements: elementsForConvert,
    stickerPath: path,
  });

  if (!convertRes.success) {
    console.warn('[ToolActions]: convertElement2Sticker failed, retrying with penType=1:', convertRes);
    const safeElements = (elemRes.result as any[]).map((el: any) =>
      el?.stroke != null ? { ...el, stroke: { ...el.stroke, penType: 1 } } : el);
    convertRes = await PluginCommAPI.convertElement2Sticker({
      machineType: deviceType,
      elements: safeElements,
      stickerPath: path,
    });
  }

  if (!convertRes.success) {
    console.warn('[ToolActions]: convertElement2Sticker failed:', convertRes);
    return `Save clip ${slot} failed`;
  }

  await PluginCommAPI.setLassoBoxState(2);

  const clips = await loadClips();
  clips[slot] = path;
  await saveClips(clips);
  return `Saved to clip ${slot}`;
}

async function clipPasteSticker(slot: string): Promise<string> {
  const clips = await loadClips();
  const stored = clips[slot];
  if (!stored) return 'Clip empty';

  let paths: string[];
  if (stored.startsWith('/')) {

    paths = [stored];
  } else {

    paths = [`${STICKER_DIR}/${stored}`];
  }

  for (const path of paths) {

    if (path.endsWith('.textclip.json')) {
      try {
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

    try {
      const r = await PluginCommAPI.insertSticker(path);
      if (r.success) return `Pasted clip ${slot}`;
    } catch {}

    try {
      const r2 = await PluginCommAPI.insertSticker(path + '.sticker');
      if (r2.success) return `Pasted clip ${slot}`;
    } catch {}
  }

  return `Paste clip ${slot} failed`;
}

async function clipClear(slot: string): Promise<string> {
  const clips = await loadClips();
  clips[slot] = null;
  await saveClips(clips);
  return `Clip ${slot} cleared`;
}

export async function queryLayerInfo(): Promise<{ current: number; total: number } | null> {
  try {
    const fp = await PluginCommAPI.getCurrentFilePath();
    if (!fp.success || !fp.result) return null;
    const pg = await PluginCommAPI.getCurrentPageNum();
    if (!pg.success || pg.result === undefined) return null;

    const lr = await PluginFileAPI.getLayers(fp.result, pg.result);
    if (!lr.success || !lr.result) return null;

    const layers = lr.result.map((l: any) => ({
      id: l.layerId !== undefined ? l.layerId : l.layerNum,
      isCurrent: !!l.isCurrentLayer,
    })).sort((a: any, b: any) => a.id - b.id);

    const userLayers = layers.filter((l: any) => l.id >= 0);
    const currentIdx = userLayers.findIndex((l: any) => l.isCurrent);
    return {
      current: currentIdx >= 0 ? currentIdx + 1 : 1,
      total: userLayers.length,
    };
  } catch {
    return null;
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


import { PluginCommAPI, PluginFileAPI, PluginNoteAPI, PluginManager } from 'sn-plugin-lib';
import { NativeModules, EmitterSubscription } from 'react-native';
import RNFS from 'react-native-fs';
import { loadClips, saveClips } from './ToolPresets';
import { toggleMode, handleAiSend, getLastMode, stopAiMode, ensureLocalSendReady } from './BackgroundService';
import FloatingToolbarBridge from './FloatingToolbarBridge';
import FloatingBubbleBridge from './FloatingBubbleBridge';

const { FloatingToolbar } = NativeModules;

let _filePath: string | null = null;
let _pageNum: number | null = null;

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

  if (action === 'send_ai') {
    const { startAiReceiveMode } = require('./BackgroundService');
    await startAiReceiveMode();
    return 'AI receive: ON';
  }

  if (action === 'lasso_ai') {
    handleAiSend().catch(e => console.error('[ToolActions]: lasso_ai error:', e));
    return 'Sending to AI...';
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
    ensureLocalSendReady().catch(() => {});
    FloatingToolbarBridge.showNativeDocPanel();
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
    ensureLocalSendReady().catch(() => {});
    FloatingToolbarBridge.showNativeImagePanel();
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
      if (isRecognitionNote()) return '';
      if (await hasLassoSelection()) return moveLassoElementsLayer('up');
      await PluginNoteAPI.saveCurrentNote();
      return await layerPrev();
    }
    if (action === 'layer_next') {
      if (isRecognitionNote()) return '';
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
  if (movable.length === 0) return 'No movable elements (title/link must stay on main layer)';

  if (direction === 'up') {
    const maxLayer = Math.max(...movable.map((e: any) => e.layerNum ?? 0));
    const maxIdx = userLayerIds.indexOf(maxLayer);
    if (maxIdx === userLayerIds.length - 1) {
      const maxUserId = Math.max(...userLayerIds);
      if (maxUserId >= 3) return 'Max 4 user layers';
      const newId = maxUserId + 1;
      const ir = await PluginFileAPI.insertLayer(_filePath, _pageNum, {
        layerId: newId, name: `Layer ${newId + 1}`, isVisible: true, isCurrentLayer: false,
      } as any) as any;
      if (!ir?.success) return 'Create layer failed';
      userLayerIds.push(newId);
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
  if (targetLayerMap.size === 0) return direction === 'up' ? 'Already at top layer' : 'Already at bottom layer';

  const fullRes = await PluginFileAPI.getElements(_pageNum, _filePath) as any;
  if (!fullRes?.success || !fullRes.result) return 'Get page elements failed';

  const toModify = (fullRes.result as any[]).filter((e: any) => targetLayerMap.has(e.numInPage));
  for (const el of toModify) {
    el.layerNum = targetLayerMap.get(el.numInPage);
  }

  await PluginNoteAPI.saveCurrentNote();
  const modRes = await PluginFileAPI.modifyElements(_filePath, _pageNum, toModify) as any;
  console.log('[ToolActions] modifyElements result:', modRes?.success, modRes?.error);
  if (!modRes?.success) return `Modify failed: ${modRes?.error?.message ?? 'unknown'}`;
  await PluginCommAPI.reloadFile();
  return `Moved ${toModify.length} element(s) ${direction}`;
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
  if (maxUserId >= 3) return 'Max 4 user layers';

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

  const below = allLayers.filter((l: any) => l.id < currentId).sort((a: any, b: any) => b.id - a.id);
  if (below.length === 0) return 'Already at bottom layer';

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
  try {
    const lassoRes = await PluginCommAPI.getLassoRect();
    const hasLasso = lassoRes.success && lassoRes.result != null;
    if (hasLasso) {
      return await clipSave(slot);
    }
  } catch {}
  return await clipPasteSticker(slot);
}

async function clipSave(slot: string): Promise<string> {
  await ensureStickerDir();

  try {
    const cntRes = await (PluginCommAPI as any).getLassoElementTypeCounts?.();
    const c = cntRes?.result;
    const tbCount =
      (c?.normalTextBoxNum ?? 0) +
      (c?.digestTextBoxNum ?? 0) +
      (c?.digestTextBoxEditableNum ?? 0);
    if (tbCount > 0) {
      return await clipSaveTextBox(slot);
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

async function clipSaveTextBox(slot: string): Promise<string> {
  const name = `quickbar_clip_${slot}_${Date.now()}.textclip.json`;
  const path = `${STICKER_DIR}/${name}`;

  console.log('[ToolActions]: clipSaveTextBox slot=', slot, 'path=', path);

  const res: any = await (PluginNoteAPI as any).getLassoText?.();
  if (!res?.success || !Array.isArray(res.result) || res.result.length === 0) {
    console.warn('[ToolActions]: getLassoText failed or empty:', res);
    return `Save clip ${slot} failed (no textbox)`;
  }

  const payload = {
    version: 1 as const,
    kind: 'textbox' as const,
    items: (res.result as any[]).map(tb => ({
      textContentFull: tb.textContentFull ?? '',
      textRect: tb.textRect,
      fontSize: tb.fontSize,
      fontPath: tb.fontPath,
      textAlign: tb.textAlign,
      textBold: tb.textBold,
      textItalics: tb.textItalics,
      textFrameWidthType: tb.textFrameWidthType,
      textFrameStyle: tb.textFrameStyle,
      textEditable: tb.textEditable,
    })),
  };

  try {
    await RNFS.writeFile(path, JSON.stringify(payload), 'utf8');
  } catch (e) {
    console.warn('[ToolActions]: writeFile textclip failed:', e);
    return `Save clip ${slot} failed (write)`;
  }

  try { await PluginCommAPI.setLassoBoxState(2); } catch (_) {}

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
        for (const tb of items) {
          const r: any = await (PluginNoteAPI as any).insertText?.(tb);
          if (!r?.success) {
            okAll = false;
            console.warn('[ToolActions]: insertText failed:', r);
          }
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
      case 'send_ai':
        stopAiMode();
        break;
    }
  });
}

export function detachModeListeners(): void {
  modeExitSub?.remove();
  modeExitSub = null;
}

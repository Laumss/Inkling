

import React, { useState, useEffect, useCallback, useRef } from 'react';
import {
  View, StyleSheet, StatusBar,
  DeviceEventEmitter, AppState, AppStateStatus,
  NativeModules,
} from 'react-native';
import { PluginManager, PluginNoteAPI, PluginFileAPI, PluginCommAPI, NativeUIUtils } from 'sn-plugin-lib';

import FloatingToolbarBridge from './components/FloatingToolbarBridge';
import FloatingBubbleBridge, { PaletteBubbleBridge } from './components/BubbleBridges';
import {
  loadClips, ClipData,
  loadBubbleActions,
} from './components/ToolPresets';
import {
  ensureInit, getActiveMode, flushPendingTexts, reviveIfNeeded,
  setInsertTop, refreshBubbleActions, stopMode, stopAiMode,
} from './components/BackgroundService';
import { executeAction, attachModeListeners, detachModeListeners, getPaletteLassoInfo } from './components/ToolActions';
import { InsertMode } from './components/TextInserter';
import { FileLogger } from './components/FileLogger';
import { LassoExtractor } from './components/LassoExtractor';
import { checkPendingButton, peekPendingButton, setAppMounted } from './pendingButton';
import { t } from './components/i18n';
import { appendPageWithLink } from './components/AppendPageService';


type AppScreen = 'nativeHelper' | 'penLock';

function App(): React.JSX.Element {

  const initialPending = peekPendingButton();
  const initialPendingScreen = FloatingToolbarBridge.getPendingScreenSync();
  console.log('[App] init: pendingBtn=', initialPending, 'pendingScreen=', JSON.stringify(initialPendingScreen));
  const [screen, setScreen]               = useState<AppScreen>(
    (initialPendingScreen === 'penLock') ? 'penLock' :
    (initialPendingScreen === 'nativeSendHelper' || initialPendingScreen === 'nativeInsertHelper' || initialPendingScreen?.startsWith('action:')) ? 'nativeHelper' :
    'nativeHelper'
  );
  const [clips, setClips]                 = useState<ClipData>({ '1': null, '2': null, '3': null, '4': null });
  const [insertMode, setInsertMode]       = useState<InsertMode | null>(null);
  const [_resumeTick, setResumeTick]      = useState(0);
  const [bubbleActionIds, setBubbleActionIds] = useState<string[]>([]);

  const actionInProgressRef = useRef(false);
  const nativeSendActiveRef = useRef(false);

  const hasPermissionRef = useRef(true);
  const screenRef     = useRef(screen);      screenRef.current     = screen;

  const openMainPanel = useCallback(() => {
    if (getActiveMode()) stopMode();
    stopAiMode();
    flushPendingTexts();
    reviveIfNeeded();
    FloatingToolbarBridge.ackPendingScreen();
    FloatingToolbarBridge.openPanel('config');
  }, []);

  useEffect(() => {
    console.log('[App]: ── mount ──');
    setAppMounted(true);

    attachModeListeners();
    ensureInit();

    const runLassoExtraction = () => {
      LassoExtractor.extract().then(extracted => {
        FloatingToolbarBridge.setLassoData(
          extracted.text,
          JSON.stringify(extracted.imagePaths),
          JSON.stringify(extracted.linkedFiles),
        );
        setTimeout(() => PluginManager.closePluginView(), 200);
      }).catch(() => {
        FloatingToolbarBridge.setLassoData('', '[]', '[]');
        setTimeout(() => PluginManager.closePluginView(), 200);
      });
    };

    const runActionFlow = (action: string) => {
      actionInProgressRef.current = true;
      executeAction(action).then(() => {
        setTimeout(() => {
          actionInProgressRef.current = false;
          if (hasPermissionRef.current) {
            FloatingToolbarBridge.showCurrent();
          }
          PluginManager.closePluginView();
        }, 300);
      }).catch(() => {
        actionInProgressRef.current = false;
        setTimeout(() => PluginManager.closePluginView(), 300);
      });
    };

    const fromToolbar    = FloatingToolbarBridge.checkPendingOpenMainSync();
    const toolbarShowing = FloatingToolbarBridge.isShowingSync();
    const pendingScreen  = FloatingToolbarBridge.getPendingScreenSync();
    console.log('[LASSO-DBG/App] mount: fromToolbar=', fromToolbar, 'toolbarShowing=', toolbarShowing, 'pendingScreen=', JSON.stringify(pendingScreen), 'initialScreen=', screen);

    if (fromToolbar) {

      if (getActiveMode()) stopMode();
      flushPendingTexts();
        FloatingToolbarBridge.ackPendingScreen();
      openMainPanel();
      setTimeout(() => FloatingToolbarBridge.ackOpenMain(), 200);
    } else if (pendingScreen) {

      if (pendingScreen !== 'penLock') FloatingToolbarBridge.hide();
      flushPendingTexts();
      reviveIfNeeded();
      if (pendingScreen === 'nativeSendHelper') {
        nativeSendActiveRef.current = true;
        setScreen('nativeHelper');
        setTimeout(() => FloatingToolbarBridge.ackPendingScreen(), 200);
        runLassoExtraction();
      } else if (pendingScreen === 'nativeInsertHelper') {

            setScreen('nativeHelper');
        setTimeout(() => FloatingToolbarBridge.ackPendingScreen(), 200);
      } else if (pendingScreen.startsWith('action:')) {

        const action = pendingScreen.slice(7);
        FloatingToolbarBridge.ackPendingScreen();
        setScreen('nativeHelper');
        runActionFlow(action);
      } else if (pendingScreen === 'penLock') {
        FloatingToolbarBridge.ackPendingScreen();
        FloatingToolbarBridge.engagePenLock();
        setScreen('penLock');
      } else {
        FloatingToolbarBridge.ackPendingScreen();
        setScreen('nativeHelper');
      }
    } else if (toolbarShowing) {

      const earlyPending = checkPendingButton();
      if (earlyPending === 999) {

        openMainPanel();
      } else if (earlyPending === 300) {

        setTimeout(() => { try { PluginManager.closePluginView(); } catch (_) {} }, 0);
      } else if (earlyPending === 100) {

        setTimeout(() => { try { PluginManager.closePluginView(); } catch (_) {} }, 0);
      } else {

        setTimeout(() => { try { PluginManager.closePluginView(); } catch (_) {} }, 0);
      }
    } else {
      // showType=0 的按钮（如 LocalSend）也会触发宿主 showPluginView 把 App 挂起来，
      // 此时不能落进「打开配置面板」的兜底，否则点 LocalSend 会弹出配置界面
      const earlyPending = checkPendingButton();
      if (earlyPending === 200) {
        setTimeout(() => { try { PluginManager.closePluginView(); } catch (_) {} }, 0);
      } else {
        openMainPanel();
      }
    }

    const existingMode = getActiveMode();
    if (existingMode) setInsertMode(existingMode);

    Promise.all([loadClips(), loadBubbleActions()]).then(([clipData, bubbleIds]) => {
      setClips(clipData);
      setBubbleActionIds(bubbleIds);
      const filled = ([1,2,3,4,5,6] as const).map(n => !!clipData[String(n) as keyof typeof clipData]);
      FloatingToolbarBridge.updateTitleClips(filled);
    });

    const toolTapSub = FloatingToolbarBridge.onToolTap(async ({ toolAction }) => {
      const result = await executeAction(toolAction);
      console.log('[App]: tool result:', result);

      if (toolAction === 'voice_transcribe' && result === 'AI receive: ON') {
        if (hasPermissionRef.current) {
          FloatingToolbarBridge.showCurrent();
        }
        setTimeout(() => PluginManager.closePluginView(), 300);
        return;
      }
      if (typeof result === 'string' &&
          (result.startsWith('Saved to clip') || (result.startsWith('Clip') && result.endsWith('cleared')))) {
        const newClips = await loadClips();
        setClips(newClips);
      }
    });

    const toolModeExitSub = FloatingToolbarBridge.onToolModeExit(async ({ toolAction }) => {
      console.log('[App]: onToolModeExit:', toolAction);
      if (toolAction === 'voice_transcribe') return;
      await executeAction(toolAction);
    });

    const clipsChangedSub = DeviceEventEmitter.addListener('clipsChanged', async () => {
      const newClips = await loadClips();
      setClips(newClips);
      const filled = ([1,2,3,4,5,6] as const).map(n => !!newClips[String(n) as keyof typeof newClips]);
      FloatingToolbarBridge.updateTitleClips(filled);
    });

    const longPressSub = FloatingToolbarBridge.onToolLongPress(async ({ toolId }) => {
      if (toolId.startsWith('clip_')) {
        const slot   = toolId.split('_')[1];
        const result = await executeAction(`clip_clear_${slot}`);
        console.log('[App]: long press clear:', result);
        const newClips = await loadClips();
        setClips(newClips);
      } else if (toolId === 'invert_ink') {
        try {
          console.log('[PLT/longPress] checking lasso type counts…');
          const cntRes = await (PluginCommAPI as any).getLassoElementTypeCounts?.();
          const c = cntRes?.result;
          console.log('[PLT/longPress] typeCounts:', JSON.stringify(c));
          const nonInk =
            (c?.titleNum ?? 0) +
            (c?.textLinkNum ?? 0) + (c?.trailLinkNum ?? 0) + (c?.todoLinkNum ?? 0) +
            (c?.normalTextBoxNum ?? 0) + (c?.digestTextBoxNum ?? 0) + (c?.digestTextBoxEditableNum ?? 0) +
            (c?.bitmapNum ?? 0);
          if (nonInk > 0) {
            console.warn(`[PLT/longPress] rejected: nonInk=${nonInk}`);
            NativeUIUtils.showErrorTipDialog(t('palette_ink_only'));
            return;
          }
          const info = await getPaletteLassoInfo();
          console.log(`[PLT/longPress] info ready, nums=${info?.elementNums?.length ?? 0}`);
          FloatingToolbarBridge.showPalettePanel(JSON.stringify(info ?? {}));
        } catch (e) {
          console.error('[PLT/longPress] CRASH:', e);
        }
      }
    });

    const openMainSub = FloatingToolbarBridge.onToolbarOpenMain(() => {
      console.log('[App]: onToolbarOpenMain received');
      flushPendingTexts();
      reviveIfNeeded();
      const pendingMain = FloatingToolbarBridge.checkPendingOpenMainSync();
      const pendingScr  = FloatingToolbarBridge.getPendingScreenSync();
      if (pendingMain) {

        openMainPanel();
        FloatingToolbarBridge.ackOpenMain();
      } else if (pendingScr) {
        if (pendingScr === 'nativeSendHelper') {
          FloatingToolbarBridge.hide();
          nativeSendActiveRef.current = true;
          setScreen('nativeHelper');
          FloatingToolbarBridge.ackPendingScreen();
          runLassoExtraction();
        } else if (pendingScr === 'nativeInsertHelper') {
          FloatingToolbarBridge.hide();
                setScreen('nativeHelper');
          FloatingToolbarBridge.ackPendingScreen();
        } else if (pendingScr.startsWith('action:')) {
          const action = pendingScr.slice(7);
          FloatingToolbarBridge.ackPendingScreen();
          FloatingToolbarBridge.hide();
          setScreen('nativeHelper');
          runActionFlow(action);
        } else if (pendingScr === 'penLock') {

          FloatingToolbarBridge.ackPendingScreen();
          FloatingToolbarBridge.engagePenLock();
          setScreen('penLock');
        } else {
          FloatingToolbarBridge.ackPendingScreen();
          setScreen('nativeHelper');
        }
      } else {

        if (!actionInProgressRef.current && !nativeSendActiveRef.current
            && !FloatingToolbarBridge.isShowingSync()) {
          openMainPanel();
        }
      }
      setResumeTick(n => n + 1);
      setTimeout(() => setResumeTick(n => n + 1), 150);
      setTimeout(() => setResumeTick(n => n + 1), 400);
    });

    const tapSub = FloatingToolbarBridge.onTap(() => {

    });

    const clipChangeSub = DeviceEventEmitter.addListener('clipboardChanged', async () => {
      const newClips = await loadClips();
      setClips(newClips);
    });

    const modeSub = DeviceEventEmitter.addListener(
      'insertModeChanged',
      ({ mode }: { mode: InsertMode | null }) => {
        console.log('[App]: insertModeChanged →', mode);
        setInsertMode(mode);

        if (!mode) {

          FloatingBubbleBridge.hide();
          if (hasPermissionRef.current && FloatingToolbarBridge.isShowingSync()) {
            FloatingToolbarBridge.showCurrent();
          }
        }
      },
    );

    const appStateSub = AppState.addEventListener('change', (state: AppStateStatus) => {
      if (state === 'active') {
        reviveIfNeeded();
        const pending = FloatingToolbarBridge.checkPendingOpenMainSync();
        if (pending && screenRef.current !== 'penLock') {
          FloatingToolbarBridge.hide();
          openMainPanel();
          FloatingToolbarBridge.ackOpenMain();
        }
        setResumeTick(n => n + 1);
      }
    });

    const handleButton = (buttonId: number) => {
      if (buttonId === 999) {

        openMainPanel();
        return;
      }
      if (buttonId === 300) {

        return;
      }
      if (buttonId === 100) {
        const pending    = FloatingToolbarBridge.checkPendingOpenMainSync();
        const pendingScr = FloatingToolbarBridge.getPendingScreenSync();
        const isShowing  = FloatingToolbarBridge.isShowingSync();
        if (pending) {

          openMainPanel();
          setTimeout(() => FloatingToolbarBridge.ackOpenMain(), 200);
          return;
        }
        if (pendingScr) {
          if (pendingScr !== 'penLock') FloatingToolbarBridge.hide();
          if (pendingScr === 'nativeSendHelper') {
            nativeSendActiveRef.current = true;
            setScreen('nativeHelper');
            setTimeout(() => FloatingToolbarBridge.ackPendingScreen(), 200);
            runLassoExtraction();
          } else if (pendingScr === 'nativeInsertHelper') {
                    setScreen('nativeHelper');
            setTimeout(() => FloatingToolbarBridge.ackPendingScreen(), 200);
          } else if (pendingScr.startsWith('action:')) {
            const action = pendingScr.slice(7);
            FloatingToolbarBridge.ackPendingScreen();
            setScreen('nativeHelper');
            runActionFlow(action);
          } else if (pendingScr === 'penLock') {
            FloatingToolbarBridge.ackPendingScreen();
            FloatingToolbarBridge.engagePenLock();
            setScreen('penLock');
          } else {
            FloatingToolbarBridge.ackPendingScreen();
            setScreen('nativeHelper');
          }
          return;
        }
        if (isShowing) {
          PluginManager.closePluginView();
          return;
        }

        openMainPanel();
      }
    };

    const pending = checkPendingButton();
    if (pending !== null) handleButton(pending);

    const btnSub = DeviceEventEmitter.addListener('quickToolbarButton', ({ id }) => {
      checkPendingButton();
      handleButton(id);
    });

    let lastInsertTime = 0;
    const INSERT_DEDUP_MS = 1000;
    const nativeInsertSub = DeviceEventEmitter.addListener('nativeInsertImage', async (evt: {
      path: string;
      fromQueue?: boolean;
      fromInsertNext?: boolean;
      cacheBaseName?: string;
      replaceNotePath?: string;
      replacePageNum?: number;
      replaceNumInPage?: number;
    }) => {
      const { path, fromQueue, fromInsertNext, cacheBaseName, replaceNotePath, replacePageNum, replaceNumInPage } = evt;
      const now = Date.now();
      const elapsed = now - lastInsertTime;
      console.log('[INSERT-DBG/App] nativeInsertImage event, path=', path, 'elapsed=', elapsed, 'fromInsertNext=', fromInsertNext);
      if (!fromQueue && elapsed < INSERT_DEDUP_MS) {
        console.log('[INSERT-DBG/App] skipped (dedup, elapsed=' + elapsed + 'ms)');
        return;
      }
      lastInsertTime = now;

      if (PluginNoteAPI) {
        console.log('[INSERT-DBG/App] calling PluginNoteAPI.insertImage');
        try {
          try { await (PluginCommAPI as any).setLassoBoxState?.(2); } catch (_le) { /* 首张无套索，忽略 */ }
          const result = PluginNoteAPI.insertImage(path);
          console.log('[INSERT-DBG/App] insertImage returned:', result);
          if (result && typeof result.then === 'function') {
            result.then(async (r: any) => {
              console.log('[INSERT-DBG/App] insertImage promise resolved:', r);
              if (r && r.success && fromInsertNext) {
                console.log('[INSERT-DBG/App] insertNext succeeded → deleting file');
                FloatingToolbarBridge.deleteQueueFile(path).then(d =>
                  console.log('[INSERT-DBG/App] queue file delete result:', d)
                );
              } else if (!r || !r.success) {
                console.warn('[INSERT-DBG/App] insert FAILED, keeping file:', r?.error);
              }
            }).catch((e: unknown) => console.error('[INSERT-DBG/App] insertImage promise rejected:', e));
          }
        } catch (e) {
          console.error('[INSERT-DBG/App] insertImage threw:', e);
        }
      } else {
        console.warn('[INSERT-DBG/App] PluginNoteAPI unavailable');
      }
    });

    const nativeDocLinkSub = DeviceEventEmitter.addListener('nativeInsertDocLink', async (evt: {
      path: string;
      linkName: string;
    }) => {
      const { path, linkName: rawLinkName } = evt;

      const dotIdx = rawLinkName.lastIndexOf('.');
      const linkName = dotIdx > 0 ? rawLinkName.substring(0, dotIdx) : rawLinkName;
      console.log('[App] nativeInsertDocLink: path=', path, 'linkName=', linkName);
      if (PluginNoteAPI) {
        try {
          // 插入文档链接前，先固化上一次的套索选中态
          try { await (PluginCommAPI as any).setLassoBoxState?.(2); } catch (_le) {}

          const fpRes = await PluginCommAPI.getCurrentFilePath();
          const pgRes = await PluginCommAPI.getCurrentPageNum();
          let pageW = 1404, pageH = 1872;
          let notePath = '';
          let pageNum = 0;
          if (fpRes?.success && fpRes.result) notePath = fpRes.result;
          if (pgRes?.success && pgRes.result !== undefined) pageNum = pgRes.result;
          if (notePath && pageNum !== undefined) {
            const psRes: any = await PluginFileAPI.getPageSize(notePath, pageNum);
            if (psRes?.success && psRes.result) {
              pageW = psRes.result.width;
              pageH = psRes.result.height;
            }
          }

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
                const GAP = 10;
                for (let iter = 0; iter < 50; iter++) {
                  let collision = false;
                  for (const range of occupied) {
                    if (top < range.bottom && (top + lineH) > range.top) {
                      console.log('[App] docLink collision at top=', top,
                        'with existing [', range.top, ',', range.bottom, '] → skip to', range.bottom + GAP);
                      top = range.bottom + GAP;
                      collision = true;
                      break;
                    }
                  }
                  if (!collision) break;
                }
                if (top + lineH > pageH - 50) {
                  console.warn('[App] docLink: no room on page, using original position');
                  top = Math.round(pageH * 0.15);
                }
              }
            }
          } catch (ce) {
            console.warn('[App] docLink collision scan failed (non-fatal):', ce);
          }

          const ext = path.split('.').pop()?.toLowerCase() || '';
          const isDocFile = ['epub', 'pdf', 'cbz', 'doc', 'docx', 'djvu', 'mobi', 'fb2'].includes(ext);
          const linkType = isDocFile ? 2 : 0;

          const destPath = path.replace('/sdcard/', '/storage/emulated/0/');

          const textLink = {
            destPath,
            destPage: -1,
            style: 0,
            linkType,
            rect: { left, top, right: left + Math.round(linkW), bottom: top + lineH },
            fontSize,
            fullText: linkName,
            showText: linkName,
            isItalic: 0,
          };
          const r: any = await (PluginNoteAPI as any).insertTextLink(textLink);
          console.log('[App] insertTextLink result:', r);

          try {
            await PluginCommAPI.reloadFile();
            await new Promise(resolve => setTimeout(resolve, 300));

            const pad = 5;
            const lassoRect = {
              left: textLink.rect.left - pad,
              top: textLink.rect.top - pad,
              right: textLink.rect.right + pad,
              bottom: textLink.rect.bottom + pad,
            };
            const lr: any = await PluginCommAPI.lassoElements(lassoRect);
            console.log('[App] lassoElements after insertTextLink:', lr);
            if (lr?.success && lr.result !== false) {
              await (PluginCommAPI as any).setLassoBoxState?.(0);
            }
          } catch (le) {
            console.warn('[App] lassoElements after insertTextLink failed:', le);
          }
        } catch (e) {
          console.error('[App] insertTextLink error:', e);
        }
      }
    });

    const nativeCloseSub = DeviceEventEmitter.addListener('nativeClosePluginView', () => {
      PluginManager.closePluginView();
    });

    let paletteInFlight = false;

    async function doPalette(
      elementNums: number[],
      penColor: number | null,
      thickness: number | null,
      penType: number | null,
      tag: string,
      prefetchedEls?: any[],
      hasMarker?: boolean,
    ): Promise<void> {
      if (elementNums.length === 0) { console.log(`[PLT/${tag}] skip: empty elementNums`); return; }
      const wantColorOrThickness = penColor !== null || thickness !== null;
      const wantPenType = penType !== null;
      if (!wantColorOrThickness && !wantPenType) { console.log(`[PLT/${tag}] skip: nothing to change`); return; }

      if (paletteInFlight) {
        console.warn(`[PLT/${tag}] BLOCKED: another doPalette is running`);
        return;
      }
      paletteInFlight = true;
      let prefetchConsumed = false;
      const t0 = Date.now();
      console.log(`[PLT/${tag}] START nums=${elementNums.length} color=${penColor} thick=${thickness} pt=${penType} prefetch=${!!prefetchedEls} marker=${!!hasMarker}`);

      try {
        // Marker strokes (penType 11) carry extra internal data (markPenDirection …) that
        // is NOT fully materialized in a live lasso/getElements read. Modifying them in that
        // state makes the host rebuild the page empty → strokes vanish. The committed build
        // avoided this by solidifying first. So when the selection contains markers, restore
        // that proven path: saveCurrentNote + reloadFile commits them to disk before modify.
        // Non-marker selections keep the fast path (no save).
        if (hasMarker || wantPenType && penType === 11) {
          if (prefetchedEls) {
            for (const el of prefetchedEls) { try { el?.recycle?.(); } catch (_) {} }
            prefetchConsumed = true;
          }
          console.log(`[PLT/${tag}] marker present → solidify`);
          await PluginNoteAPI.saveCurrentNote();
          console.log(`[PLT/${tag}] saveCurrentNote +${Date.now() - t0}ms`);
          await PluginCommAPI.reloadFile();
          console.log(`[PLT/${tag}] reloadFile +${Date.now() - t0}ms`);
        }

        const fpRes: any = await PluginCommAPI.getCurrentFilePath();
        const pgRes: any = await PluginCommAPI.getCurrentPageNum();
        if (!fpRes?.success || !pgRes?.success) {
          console.warn(`[PLT/${tag}] ABORT: filePath.ok=${fpRes?.success} pageNum.ok=${pgRes?.success}`);
          return;
        }
        const filePath = fpRes.result;
        const pageNum = pgRes.result;
        console.log(`[PLT/${tag}] file="${filePath}" page=${pageNum} +${Date.now() - t0}ms`);

        // NOTE: do NOT recycle prefetchedEls / clearElementCache here (non-marker path). The
        // prefetched lasso elements share uuids with the page strokes; recycling them or
        // clearing the cache before the getElements+modifyElements below poisons the host's
        // point-data resolution → the host rebuilds the page with 0 trails → DATA LOSS.
        // They are recycled in the outer finally, after all modify passes complete.

        const numSet = new Set(elementNums);

        // ── Pass 1: color + thickness (must NOT touch penType) ──
        if (wantColorOrThickness) {
          console.log(`[PLT/${tag}] P1 getElements…`);
          const elRes: any = await PluginFileAPI.getElements(pageNum, filePath);
          if (!elRes?.success || !Array.isArray(elRes.result)) {
            console.warn(`[PLT/${tag}] P1 getElements FAIL ok=${elRes?.success}`);
            return;
          }
          const allEls: any[] = elRes.result;
          console.log(`[PLT/${tag}] P1 got ${allEls.length} els +${Date.now() - t0}ms`);
          try {
            const targets = allEls.filter(
              (el: any) => el?.numInPage != null && numSet.has(el.numInPage) && (el.type === 0 || el.type === 700)
            );
            if (targets.length === 0) { console.log(`[PLT/${tag}] P1 no match`); return; }
            console.log(`[PLT/${tag}] P1 matched ${targets.length} of ${allEls.length}`);

            for (const el of targets) {
              if (penColor !== null) {
                if (el.type === 0 && el.stroke) el.stroke.penColor = penColor;
                if (el.type === 700 && el.geometry) el.geometry.penColor = penColor;
              }
              if (thickness !== null) {
                if (el.type === 0) el.thickness = Math.max(10, thickness);
                if (el.type === 700 && el.geometry) el.geometry.penWidth = Math.max(10, thickness);
              }
            }

            console.log(`[PLT/${tag}] P1 modifyElements n=${targets.length}…`);
            const modRes: any = await NativeModules.NativePluginAPI.modifyElements(filePath, pageNum, targets);
            const modCount = Array.isArray(modRes?.result) ? modRes.result.length : -1;
            console.log(`[PLT/${tag}] P1 ok=${modRes?.success} code=${modRes?.code} modified=${modCount}/${targets.length} +${Date.now() - t0}ms`);
            if (modCount >= 0 && modCount < targets.length) {
              const got = new Set(modRes.result);
              const skipped = targets.filter((el: any) => !got.has(el.numInPage)).map((el: any) => el.numInPage);
              console.warn(`[PLT/${tag}] P1 host SKIPPED ${skipped.length} strokes (likely markers): nums=${JSON.stringify(skipped)}`);
            }
            if (wantPenType) {
              await PluginCommAPI.reloadFile();
              console.log(`[PLT/${tag}] P1 reloadFile (for P2) +${Date.now() - t0}ms`);
            }
          } finally {
            for (const el of allEls) { try { el?.recycle?.(); } catch (_) {} }
            try { (PluginCommAPI as any).clearElementCache?.(); } catch (_) {}
          }
        }

        // ── Pass 2: penType (separate call — mixing with color/thickness breaks marker strokes) ──
        if (wantPenType) {
          const apiPenType = penType!;
          console.log(`[PLT/${tag}] P2 getElements for pt=${apiPenType}…`);
          const elRes2: any = await PluginFileAPI.getElements(pageNum, filePath);
          if (!elRes2?.success || !Array.isArray(elRes2.result)) {
            console.warn(`[PLT/${tag}] P2 getElements FAIL ok=${elRes2?.success}`);
          } else {
            const allEls2: any[] = elRes2.result;
            console.log(`[PLT/${tag}] P2 got ${allEls2.length} els +${Date.now() - t0}ms`);
            try {
              const strokes2 = allEls2.filter(
                (el: any) => el?.numInPage != null && numSet.has(el.numInPage) && el.type === 0
              );
              if (strokes2.length === 0) {
                console.log(`[PLT/${tag}] P2 no stroke targets`);
              } else {
                console.log(`[PLT/${tag}] P2 matched ${strokes2.length} strokes`);
                for (const el of strokes2) { if (el.stroke) el.stroke.penType = apiPenType; }
                const modRes2: any = await NativeModules.NativePluginAPI.modifyElements(filePath, pageNum, strokes2);
                const modCount2 = Array.isArray(modRes2?.result) ? modRes2.result.length : -1;
                console.log(`[PLT/${tag}] P2 ok=${modRes2?.success} code=${modRes2?.code} modified=${modCount2}/${strokes2.length} +${Date.now() - t0}ms`);
                if (modCount2 >= 0 && modCount2 < strokes2.length) {
                  const got2 = new Set(modRes2.result);
                  const skipped2 = strokes2.filter((el: any) => !got2.has(el.numInPage)).map((el: any) => el.numInPage);
                  console.warn(`[PLT/${tag}] P2 host SKIPPED ${skipped2.length} strokes (likely markers): nums=${JSON.stringify(skipped2)}`);
                }
              }
            } finally {
              for (const el of allEls2) { try { el?.recycle?.(); } catch (_) {} }
              try { (PluginCommAPI as any).clearElementCache?.(); } catch (_) {}
            }
          }
        }

        await PluginCommAPI.reloadFile();
        console.log(`[PLT/${tag}] DONE ${Date.now() - t0}ms`);
      } catch (e) {
        console.error(`[PLT/${tag}] CRASH:`, e);
        throw e;
      } finally {
        if (prefetchedEls && !prefetchConsumed) {
          for (const el of prefetchedEls) { try { el?.recycle?.(); } catch (_) {} }
        }
        paletteInFlight = false;
      }
    }

    const paletteApplySub = DeviceEventEmitter.addListener('paletteApply', async (evt: any) => {
      console.log('[PLT/apply] event:', JSON.stringify(evt));
      try {
        const penColor = evt.penColor != null ? evt.penColor : null;
        const thickness = evt.thickness != null ? evt.thickness : null;
        const penType = evt.penType != null ? evt.penType : null;
        let elementNums: number[] = [];
        try {
          elementNums = evt.elementNums ? JSON.parse(evt.elementNums) : [];
        } catch (pe) {
          console.error('[PLT/apply] elementNums parse FAIL:', pe, 'raw=', evt.elementNums);
          return;
        }
        const hasMarker = evt.hasMarkerStroke === true;
        console.log(`[PLT/apply] nums=${elementNums.length} c=${penColor} t=${thickness} pt=${penType} marker=${hasMarker}`);
        await doPalette(elementNums, penColor, thickness, penType, 'apply', undefined, hasMarker);
      } catch (e) {
        console.error('[PLT/apply] CRASH:', e);
      }
    });

    const COLOR_MAP: Record<string, number> = { black: 0x00, darkGray: 0x9D, lightGray: 0xC9, ghost: 0xFE };
    let cachedPaletteNums: number[] = [];

    const paletteBubbleSlotSub = PaletteBubbleBridge.onSlotTap(async ({ color, thickness, penType }) => {
      console.log(`[PLT/bubble] slotTap color=${color} thick=${thickness} pt=${penType}`);
      try {
        const cntRes = await (PluginCommAPI as any).getLassoElementTypeCounts?.();
        const c = cntRes?.result;
        console.log('[PLT/bubble] typeCounts:', JSON.stringify(c));
        const nonInk =
          (c?.titleNum ?? 0) +
          (c?.textLinkNum ?? 0) + (c?.trailLinkNum ?? 0) + (c?.todoLinkNum ?? 0) +
          (c?.normalTextBoxNum ?? 0) + (c?.digestTextBoxNum ?? 0) + (c?.digestTextBoxEditableNum ?? 0) +
          (c?.bitmapNum ?? 0);
        if (nonInk > 0) {
          console.warn(`[PLT/bubble] rejected: nonInk=${nonInk}`);
          NativeUIUtils.showErrorTipDialog(t('palette_ink_only'));
          return;
        }
        const info = await getPaletteLassoInfo(true);
        const fromCache = !info?.elementNums;
        const elementNums: number[] = info?.elementNums ?? cachedPaletteNums;
        if (elementNums.length === 0) { console.log('[PLT/bubble] no selection (info=null, cache empty)'); return; }
        console.log(`[PLT/bubble] nums=${elementNums.length} fromCache=${fromCache} prevCache=${cachedPaletteNums.length}`);
        cachedPaletteNums = elementNums;
        const penColor = COLOR_MAP[color] ?? null;
        await doPalette(elementNums, penColor, thickness, penType, 'bubble', info?.elements, info?.hasMarkerStroke === true);
      } catch (e) {
        console.error('[PLT/bubble] CRASH:', e);
      }
    });

    const appendPageSub = DeviceEventEmitter.addListener('onAppendPageAction', (evt: any) => {
      if (!evt?.imagePath) return;
      const crop = evt.cropX >= 0 ? {
        offsetX: evt.cropX, offsetY: evt.cropY,
        width: evt.cropW, height: evt.cropH,
        imageWidth: evt.imgW, imageHeight: evt.imgH,
      } : undefined;
      (async () => {
        try { await appendPageWithLink(evt.imagePath, crop); } catch (e) { console.warn('[App]: appendPage error:', e); }
      })();
    });

    const titleClipSub = FloatingToolbarBridge.onTitleClipTap(async ({ slot }) => {
      const result = await executeAction(`clip_paste_${slot}`);
      if (typeof result === 'string' &&
          (result.startsWith('Saved to clip') || (result.startsWith('Clip') && result.endsWith('cleared')))) {
        const newClips = await loadClips();
        setClips(newClips);
        const filled = ([1,2,3,4,5,6] as const).map(n => !!newClips[String(n) as keyof typeof newClips]);
        FloatingToolbarBridge.updateTitleClips(filled);
      }
    });

    const titleClipLongSub = (FloatingToolbarBridge as any).onTitleClipLongPress
      ? (FloatingToolbarBridge as any).onTitleClipLongPress(async ({ slot }: { slot: string }) => {
          await executeAction(`clip_clear_${slot}`);
          const newClips = await loadClips();
          setClips(newClips);
          const filled = ([1,2,3,4,5,6] as const).map(n => !!newClips[String(n) as keyof typeof newClips]);
          FloatingToolbarBridge.updateTitleClips(filled);
        })
      : { remove() {} };

    const titleLayerSub = FloatingToolbarBridge.onTitleLayerAction(async ({ direction }) => {
      await executeAction(direction === 'prev' ? 'layer_prev' : 'layer_next');
    });

    const nativePanelOpenSub = FloatingToolbarBridge.onNativePanelOpen(async () => {
    });

    const nativePanelCloseSub = FloatingToolbarBridge.onNativePanelClose(async ({ panel }) => {
      if (panel === 'send') nativeSendActiveRef.current = false;
    });

    const penLockRequestSub = FloatingToolbarBridge.onPenLockRequest(() => {
      FloatingToolbarBridge.engagePenLock();
      setScreen('penLock');
    });

    const penLockReleaseSub = FloatingToolbarBridge.onPenLockRelease(() => {

      FloatingToolbarBridge.releasePenLock();
      FloatingToolbarBridge.disablePenBlock();
      setScreen('nativeHelper');
      setTimeout(() => {
        PluginManager.closePluginView();
      }, 200);
    });

    return () => {
      setAppMounted(false);
      toolTapSub.remove();
      toolModeExitSub.remove();
      longPressSub.remove();
      clipsChangedSub.remove();
      openMainSub.remove();
      tapSub.remove();
      clipChangeSub.remove();
      modeSub.remove();
      btnSub.remove();
      appStateSub.remove();
      nativeInsertSub.remove();
      nativeDocLinkSub.remove();
      nativeCloseSub.remove();
      paletteApplySub.remove();
      paletteBubbleSlotSub.remove();
      appendPageSub.remove();
      nativePanelOpenSub.remove();
      nativePanelCloseSub.remove();
      penLockRequestSub.remove();
      penLockReleaseSub.remove();
      titleClipSub.remove();
      titleClipLongSub.remove();
      titleLayerSub.remove();
      detachModeListeners();
    };
  }, []);


  console.log('[App] render: screen=', screen);
  return (
    <View style={st.container}>
      <StatusBar barStyle="dark-content" />
      <View key={`ct-${_resumeTick}`} style={st.centerWrapper}>

        {screen === 'nativeHelper' && (
          <View style={{ flex: 1, backgroundColor: 'transparent' }} />
        )}

        {screen === 'penLock' && (
          <View style={{ flex: 1, backgroundColor: 'transparent' }} />
        )}

      </View>

    </View>
  );
}


const st = StyleSheet.create({
  container:     { flex: 1, backgroundColor: 'transparent' },
  centerWrapper: { flex: 1, justifyContent: 'center', alignItems: 'center' },

});

export default App;

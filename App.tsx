

import React, { useState, useEffect, useCallback, useRef } from 'react';
import {
  View, StyleSheet, StatusBar,
  DeviceEventEmitter, AppState, AppStateStatus,
} from 'react-native';
import { PluginManager, PluginNoteAPI, PluginFileAPI, PluginCommAPI, NativeUIUtils, FileUtils } from 'sn-plugin-lib';

import FloatingToolbarBridge from './components/FloatingToolbarBridge';
import FloatingBubbleBridge, { PaletteBubbleBridge } from './components/BubbleBridges';
import { loadClips } from './components/ToolPresets';
import {
  ensureInit, getActiveMode, flushPendingTexts, reviveIfNeeded,
  stopMode, stopAiMode,
  startLocalSend, stopLocalSend, isLocalSendRunning,
} from './components/BackgroundService';
import { executeAction, attachModeListeners, detachModeListeners, getPaletteLassoInfo, snapshotCreate, snapshotRestore, snapshotDelete } from './components/ToolActions';
import { modifyLiveElements, ensureMarkerDirection, isMarkerStroke, MARKER_PT } from './components/ElementOps';
import { FileLogger } from './components/FileLogger';
import { LassoExtractor } from './components/LassoExtractor';
import { checkPendingButton, peekPendingButton, setAppMounted, getLastHostButtonEventAt } from './pendingButton';
import { t } from './components/i18n';
import { appendPageWithLink } from './components/AppendPageService';
import LocalSendBridge from './components/LocalSendBridge';

type AppScreen = 'nativeHelper';

let _lassoExtractGen = 0;
let _lassoExtractStartedAt = 0;
let _lassoDataPrePopulated = false;
export function markLassoDataPrePopulated() { _lassoDataPrePopulated = true; }

function App(): React.JSX.Element {

  const initialPending = peekPendingButton();
  const initialPendingScreen = FloatingToolbarBridge.getPendingScreenSync();
  console.log('[App] init: pendingBtn=', initialPending, 'pendingScreen=', JSON.stringify(initialPendingScreen));
  const [screen, setScreen]               = useState<AppScreen>('nativeHelper');
  const [_resumeTick, setResumeTick]      = useState(0);

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

  const runLocalSendToggle = useCallback(async () => {
    try {
      if (isLocalSendRunning()) {
        await stopLocalSend();
        NativeUIUtils.showErrorTipDialog(t('localsend_stopped'));
      } else {
        const wifi = await LocalSendBridge.isWifiConnected();
        if (!wifi) {
          NativeUIUtils.showErrorTipDialog(t('no_wifi'));
          return;
        }
        const ok = await startLocalSend();
        if (ok) NativeUIUtils.showErrorTipDialog(t('localsend_started'));
      }
    } catch (e) {
      console.error('[App]: LocalSend fallback toggle error:', e);
    } finally {
      setTimeout(() => {
        try { PluginManager.closePluginView(); } catch (_) {}
      }, 100);
    }
  }, []);

  useEffect(() => {
    console.log('[App]: ── mount ──');
    setAppMounted(true);

    attachModeListeners?.();
    ensureInit();

    const runLassoExtraction = () => {
      if (_lassoDataPrePopulated) {
        _lassoDataPrePopulated = false;
        console.log('[LASSO-DBG/App] runLassoExtraction skipped (pre-populated)');
        setTimeout(() => PluginManager.closePluginView(), 200);
        return;
      }
      const now = Date.now();
      if (now - _lassoExtractStartedAt < 800) {
        console.log('[LASSO-DBG/App] runLassoExtraction skipped (debounce)');
        return;
      }
      _lassoExtractStartedAt = now;
      const gen = ++_lassoExtractGen;
      LassoExtractor.extract().then(extracted => {
        if (gen !== _lassoExtractGen) {
          console.log('[LASSO-DBG/App] stale extraction discarded, gen=' + gen);
          return;
        }
        FloatingToolbarBridge.setLassoData(
          extracted.text,
          JSON.stringify(extracted.imagePaths),
          JSON.stringify(extracted.linkedFiles),
        );
        setTimeout(() => PluginManager.closePluginView(), 200);
      }).catch(() => {
        if (gen !== _lassoExtractGen) return;
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

      FloatingToolbarBridge.hide();
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
      } else {
        FloatingToolbarBridge.ackPendingScreen();
        setScreen('nativeHelper');
      }
    } else if (toolbarShowing) {
      const earlyPending = checkPendingButton();
      if (earlyPending === 999) {
        openMainPanel();
      } else {
        setTimeout(() => { try { PluginManager.closePluginView(); } catch (_) {} }, 0);
      }
    } else {
      const earlyPending = checkPendingButton();
      if (earlyPending === 100 || earlyPending === 200 || earlyPending === 300) {
        // index.js closes the plugin view after the toggle flow finishes.
      } else if (earlyPending === 999) {
        openMainPanel();
      } else {
        console.log('[App]: source-less PluginView mount → inspect host entry');
        FloatingToolbarBridge.inspectAndFlushHostEntry().then((entry) => {
          if (entry === 'config') {
            console.log('[App]: host entry is config (showType=1) → opening config panel');
            openMainPanel();
            return;
          }
          if (entry === 'localsend') {
            const fallbackAt = Date.now();
            setTimeout(() => {
              if (getLastHostButtonEventAt() >= fallbackAt) return;
              console.log('[App]: cached LocalSend event never reached JS → fallback toggle service');
              runLocalSendToggle();
            }, 1200);
            return;
          }
          if (entry === 'flushed') {
            const flushAt = Date.now();
            setTimeout(() => {
              const replayed = checkPendingButton();
              if (replayed === 999) {
                openMainPanel();
                return;
              }
              if (getLastHostButtonEventAt() >= flushAt) return;
              FloatingToolbarBridge.reportHostButtonChannel(false);
              if (earlyPending === 200) {
                console.log('[App]: LocalSend event never reached JS → fallback toggle service');
                runLocalSendToggle();
                return;
              }
              console.log('[App]: flushed event never reached JS → fallback toggle toolbar');
              FloatingToolbarBridge.toggleFromPluginButton();
            }, 1200);
            return;
          }
          if (earlyPending === 200) {
            console.log('[App]: LocalSend host entry unavailable → fallback toggle service');
            runLocalSendToggle();
            return;
          }
          FloatingToolbarBridge.toggleFromPluginButton();
        });
      }
    }

    loadClips().then((clipData) => {
      const filled = ([1,2,3,4,5,6] as const).map(n => !!clipData[String(n) as keyof typeof clipData]);
      FloatingToolbarBridge.updateTitleClips(filled);
    });

    const toolTapSub = FloatingToolbarBridge.onToolTap(async ({ toolAction }) => {
      console.log('[BUBBLE-DBG/JS] onToolTap received action=', toolAction);
      if (toolAction === 'invert_ink') {
        try {
          console.log('[PLT/tap] checking lasso type counts…');
          const cntRes = await (PluginCommAPI as any).getLassoElementTypeCounts?.();
          const c = cntRes?.result;
          console.log('[PLT/tap] typeCounts:', JSON.stringify(c));
          const nonInk =
            (c?.titleNum ?? 0) +
            (c?.textLinkNum ?? 0) + (c?.trailLinkNum ?? 0) + (c?.todoLinkNum ?? 0) +
            (c?.normalTextBoxNum ?? 0) + (c?.digestTextBoxNum ?? 0) + (c?.digestTextBoxEditableNum ?? 0) +
            (c?.bitmapNum ?? 0);
          if (nonInk > 0) {
            console.warn(`[PLT/tap] rejected: nonInk=${nonInk}`);
            NativeUIUtils.showErrorTipDialog(t('palette_ink_only'));
            return;
          }
          const info = await getPaletteLassoInfo(true);
          console.log(`[PLT/tap] info ready, nums=${info?.elementNums?.length ?? 0}`);
          if (prefetchedPaletteEls) {
            for (const el of prefetchedPaletteEls) { try { el?.recycle?.(); } catch (_) {} }
          }
          prefetchedPaletteEls = info?.elements ?? null;
          FloatingToolbarBridge.showPalettePanel(JSON.stringify(info ?? {}));
        } catch (e) {
          console.error('[PLT/tap] CRASH:', e);
        }
        return;
      }
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
        const filled = ([1,2,3,4,5,6] as const).map(n => !!newClips[String(n) as keyof typeof newClips]);
        FloatingToolbarBridge.updateTitleClips(filled);
      }
    });

    const toolModeExitSub = FloatingToolbarBridge.onToolModeExit(async ({ toolAction }) => {
      console.log('[App]: onToolModeExit:', toolAction);
      if (toolAction === 'voice_transcribe') return;
      await executeAction(toolAction);
    });

    const clipsChangedSub = DeviceEventEmitter.addListener('clipsChanged', async () => {
      const newClips = await loadClips();
      const filled = ([1,2,3,4,5,6] as const).map(n => !!newClips[String(n) as keyof typeof newClips]);
      FloatingToolbarBridge.updateTitleClips(filled);
    });

    const longPressSub = FloatingToolbarBridge.onToolLongPress(async ({ toolId }) => {
      if (toolId.startsWith('clip_')) {
        const slot   = toolId.split('_')[1];
        const result = await executeAction(`clip_clear_${slot}`);
        console.log('[App]: long press clear:', result);
        const newClips = await loadClips();
        const filled = ([1,2,3,4,5,6] as const).map(n => !!newClips[String(n) as keyof typeof newClips]);
        FloatingToolbarBridge.updateTitleClips(filled);
      } else if (toolId === 'invert_ink') {
        PaletteBubbleBridge.show();
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

    const modeSub = DeviceEventEmitter.addListener(
      'insertModeChanged',
      ({ mode }: { mode: string | null }) => {
        console.log('[App]: insertModeChanged →', mode);

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
        if (pending) {
          FloatingToolbarBridge.hide();
          openMainPanel();
          FloatingToolbarBridge.ackOpenMain();
        }
        setResumeTick(n => n + 1);
      }
    });

    const openConfigForButton = (buttonId: number) => {
      if (buttonId === 999) openMainPanel();
    };

    const pending = checkPendingButton();
    if (pending !== null) openConfigForButton(pending);

    const btnSub = DeviceEventEmitter.addListener('quickToolbarButton', ({ id }) => {
      checkPendingButton();
      openConfigForButton(id);
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
          try { await (PluginCommAPI as any).setLassoBoxState?.(2); } catch (_le) {}
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
              if (r && r.success) {
                const toDelete = FloatingToolbarBridge.drainReceivedDeletes();
                for (const p of toDelete) {
                  FileUtils.deleteFile(p).catch(() => {});
                }
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
          const fpRes = await PluginCommAPI.getCurrentFilePath();
          const pgRes = await PluginCommAPI.getCurrentPageNum();
          let pageW = 1404, pageH = 1872;
          let notePath = '';
          let pageNum = 0;
          if (fpRes?.success && fpRes.result) notePath = fpRes.result;
          if (pgRes?.success && pgRes.result !== undefined) pageNum = pgRes.result;
          if (notePath && pageNum !== undefined) {
            const layersRes: any = await PluginFileAPI.getLayers(notePath, pageNum);
            const currentLayer = Array.isArray(layersRes?.result)
              ? layersRes.result.find((layer: any) => layer.isCurrentLayer)
              : null;
            const currentLayerId = currentLayer?.layerId ?? currentLayer?.layerNum;
            if (currentLayerId !== undefined && currentLayerId !== 0) {
              console.warn('[App] nativeInsertDocLink blocked on non-main layer:', currentLayerId);
              NativeUIUtils.showErrorTipDialog(t('doc_link_main_only'));
              return;
            }

            const psRes: any = await PluginFileAPI.getPageSize(notePath, pageNum);
            if (psRes?.success && psRes.result) {
              pageW = psRes.result.width;
              pageH = psRes.result.height;
            }
          }

          try { await (PluginCommAPI as any).setLassoBoxState?.(2); } catch (_le) {}

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
          const isNoteFile = ext === 'note';
          const isDocFile = ['epub', 'pdf', 'cbz', 'doc', 'docx', 'djvu', 'mobi', 'fb2'].includes(ext);
          const linkType = isNoteFile ? 1 : isDocFile ? 2 : 0;

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
          if (!r?.success || r.result !== 0) {
            console.warn('[App] insertTextLink failed:', JSON.stringify(r));
            if (r?.error?.code === 810) {
              NativeUIUtils.showErrorTipDialog(t('doc_link_main_only'));
            }
            return;
          }

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
            console.log('[App] lassoElements rect:', JSON.stringify(lassoRect),
              'textLink.rect:', JSON.stringify(textLink.rect),
              'pageW:', pageW, 'pageH:', pageH);
            const lr: any = await PluginCommAPI.lassoElements(lassoRect);
            console.log('[App] lassoElements result:', JSON.stringify(lr));
            if (lr?.success && lr.result !== false) {
              console.log('[App] lassoElements succeeded, calling setLassoBoxState(0)');
              await (PluginCommAPI as any).setLassoBoxState?.(0);
            } else {
              console.warn('[App] lassoElements did not select: success=', lr?.success, 'result=', lr?.result);
            }
          } catch (le) {
            console.warn('[App] lassoElements after insertTextLink failed:', le);
          }
        } catch (e) {
          console.error('[App] insertTextLink error:', e);
        }
      }
    });

    let paletteInFlight = false;
    let prefetchedPaletteEls: any[] | null = null;

    // getElements after a reloadFile can race the host's async page reload
    // (LoadPageAsyncTask) and return an EMPTY page. Retry a few times until it's back.
    // Returns { ok, els } — caller owns recycling els.
    async function getElementsStable(pageNum: number, filePath: string, tag: string): Promise<{ ok: boolean; els: any[] }> {
      for (let attempt = 0; attempt < 5; attempt++) {
        const res: any = await PluginFileAPI.getElements(pageNum, filePath);
        if (!res?.success || !Array.isArray(res.result)) {
          console.warn(`[PLT/${tag}] getElements FAIL ok=${res?.success}`);
          return { ok: false, els: [] };
        }
        if (res.result.length > 0) return { ok: true, els: res.result };
        console.log(`[PLT/${tag}] getElements empty (attempt ${attempt + 1}) — page still reloading, retrying…`);
        for (const el of res.result) { try { el?.recycle?.(); } catch (_) {} }
        await new Promise(r => setTimeout(r, 150));
      }
      console.warn(`[PLT/${tag}] getElements still empty after retries`);
      return { ok: true, els: [] };
    }

    function applyPaletteFields(
      targets: any[],
      penColor: number | null,
      thickness: number | null,
      penType: number | null,
    ): void {
      for (const el of targets) {
        if (penColor !== null) {
          if (el.type === 0 && el.stroke) el.stroke.penColor = penColor;
          if (el.type === 700 && el.geometry) el.geometry.penColor = penColor;
        }
        if (thickness !== null) {
          if (el.type === 0) el.thickness = Math.max(10, thickness);
          if (el.type === 700 && el.geometry) el.geometry.penWidth = Math.max(10, thickness);
        }
        if (penType !== null && el.type === 0 && el.stroke) {
          el.stroke.penType = penType;
        }
      }
    }

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
      let ownedEls: any[] | null = null;
      let prefetchRecycled = false;
      const t0 = Date.now();
      const convertingToMarker = wantPenType && penType === MARKER_PT;
      const prefetchedHasMarker = !!prefetchedEls?.some(isMarkerStroke);
      const hasMarkerSelection = !!hasMarker || prefetchedHasMarker || convertingToMarker;
      console.log(`[PLT/${tag}] START nums=${elementNums.length} color=${penColor} thick=${thickness} pt=${penType} prefetch=${!!prefetchedEls} marker=${hasMarkerSelection}`);

      try {
        const fpRes: any = await PluginCommAPI.getCurrentFilePath();
        const pgRes: any = await PluginCommAPI.getCurrentPageNum();
        if (!fpRes?.success || !pgRes?.success) {
          console.warn(`[PLT/${tag}] ABORT: filePath.ok=${fpRes?.success} pageNum.ok=${pgRes?.success}`);
          return;
        }
        const filePath = fpRes.result;
        const pageNum = pgRes.result;

        let targets: any[] = [];
        if (hasMarkerSelection) {
          // Marker fill data is only complete after the host flushes unsaved strokes.
          if (prefetchedEls) {
            for (const el of prefetchedEls) { try { el?.recycle?.(); } catch (_) {} }
            prefetchRecycled = true;
          }
          await PluginNoteAPI.saveCurrentNote();
          console.log(`[PLT/${tag}] saveCurrentNote +${Date.now() - t0}ms`);
          try { (PluginCommAPI as any).clearElementCache?.(); } catch (_) {}
          const fetched = await getElementsStable(pageNum, filePath, tag);
          if (!fetched.ok) return;
          ownedEls = fetched.els;
          const numSet = new Set(elementNums);
          targets = fetched.els.filter(
            (el: any) => el?.numInPage != null && numSet.has(el.numInPage) && (el.type === 0 || el.type === 700)
          );
        } else if (prefetchedEls) {
          targets = prefetchedEls.filter(
            (el: any) => el?.numInPage != null && (el.type === 0 || el.type === 700)
          );
        } else {
          const fetched = await getElementsStable(pageNum, filePath, tag);
          if (!fetched.ok) return;
          ownedEls = fetched.els;
          const numSet = new Set(elementNums);
          targets = fetched.els.filter(
            (el: any) => el?.numInPage != null && numSet.has(el.numInPage) && (el.type === 0 || el.type === 700)
          );
        }

        if (targets.length === 0) {
          console.log(`[PLT/${tag}] no targets`);
          return;
        }
        console.log(`[PLT/${tag}] targets=${targets.length} page=${pageNum} +${Date.now() - t0}ms`);

        applyPaletteFields(targets, penColor, thickness, penType);
        if (convertingToMarker) {
          for (const el of targets) {
            if (el.type === 0) await ensureMarkerDirection(el);
          }
        }

        const modRes: any = await modifyLiveElements(targets, pageNum);
        const modCount = Array.isArray(modRes?.result) ? modRes.result.length : -1;
        console.log(`[PLT/${tag}] live ok=${modRes?.success} modified=${modCount}/${targets.length} err=${modRes?.error?.message ?? ''} +${Date.now() - t0}ms`);
        if (!modRes?.success) {
          console.warn(`[PLT/${tag}] modifyPageElements failed`, modRes?.error);
        } else if (modCount >= 0 && modCount < targets.length) {
          const got = new Set(Array.isArray(modRes.result) ? modRes.result : []);
          const skipped = targets.filter((el: any) => !got.has(el.numInPage)).map((el: any) => el.numInPage);
          console.warn(`[PLT/${tag}] host SKIPPED ${skipped.length} strokes: nums=${JSON.stringify(skipped)}`);
        }
        console.log(`[PLT/${tag}] DONE ${Date.now() - t0}ms`);
      } catch (e) {
        console.error(`[PLT/${tag}] CRASH:`, e);
        throw e;
      } finally {
        if (ownedEls) {
          for (const el of ownedEls) { try { el?.recycle?.(); } catch (_) {} }
        } else if (prefetchedEls && !prefetchRecycled) {
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
        const prefetched = prefetchedPaletteEls;
        prefetchedPaletteEls = null;
        console.log(`[PLT/apply] nums=${elementNums.length} c=${penColor} t=${thickness} pt=${penType} marker=${hasMarker} prefetch=${!!prefetched}`);
        await doPalette(elementNums, penColor, thickness, penType, 'apply', prefetched ?? undefined, hasMarker);
      } catch (e) {
        console.error('[PLT/apply] CRASH:', e);
      }
    });

    const paletteSnapshotSub = DeviceEventEmitter.addListener('paletteSnapshot', async (evt: any) => {
      console.log('[SNAP] event:', JSON.stringify(evt));
      try {
        if (evt?.op === 'create') {
          console.log('[SNAP] create:', await snapshotCreate());
        } else if (evt?.op === 'restore' && evt.sticker) {
          console.log('[SNAP] restore:', await snapshotRestore(evt.sticker));
        } else if (evt?.op === 'delete' && evt.id) {
          console.log('[SNAP] delete:', await snapshotDelete(evt.id));
        }
      } catch (e) {
        console.error('[SNAP] error:', e);
      } finally {
        FloatingToolbarBridge.paletteSnapshotsChanged();
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
        if (!info?.elementNums && ((c?.trailNum ?? 0) + (c?.geometryNum ?? 0) + (c?.straightLineNum ?? 0) + (c?.curveLineNum ?? 0) + (c?.circleNum ?? 0) + (c?.ellipseNum ?? 0) + (c?.polygonNum ?? 0)) > 0) {
          console.warn('[PLT/bubble] ABORT: lasso has ink counts but getLassoElements returned no elements');
          return;
        }
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
        const filled = ([1,2,3,4,5,6] as const).map(n => !!newClips[String(n) as keyof typeof newClips]);
        FloatingToolbarBridge.updateTitleClips(filled);
      }
    });

    const titleClipLongSub = (FloatingToolbarBridge as any).onTitleClipLongPress
      ? (FloatingToolbarBridge as any).onTitleClipLongPress(async ({ slot }: { slot: string }) => {
          await executeAction(`clip_clear_${slot}`);
          const newClips = await loadClips();
          const filled = ([1,2,3,4,5,6] as const).map(n => !!newClips[String(n) as keyof typeof newClips]);
          FloatingToolbarBridge.updateTitleClips(filled);
        })
      : { remove() {} };

    const titleLayerSub = FloatingToolbarBridge.onTitleLayerAction(async ({ direction }) => {
      await executeAction(direction === 'prev' ? 'layer_prev' : 'layer_next');
    });

    const nativePanelCloseSub = FloatingToolbarBridge.onNativePanelClose(async ({ panel }) => {
      if (panel === 'send') nativeSendActiveRef.current = false;
    });

    return () => {
      setAppMounted(false);
      toolTapSub.remove();
      toolModeExitSub.remove();
      longPressSub.remove();
      clipsChangedSub.remove();
      openMainSub.remove();
      modeSub.remove();
      btnSub.remove();
      appStateSub.remove();
      nativeInsertSub.remove();
      nativeDocLinkSub.remove();
      paletteApplySub.remove();
      paletteSnapshotSub.remove();
      paletteBubbleSlotSub.remove();
      appendPageSub.remove();
      nativePanelCloseSub.remove();
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

      </View>

    </View>
  );
}

const st = StyleSheet.create({
  container:     { flex: 1, backgroundColor: 'transparent' },
  centerWrapper: { flex: 1, justifyContent: 'center', alignItems: 'center' },

});

export default App;

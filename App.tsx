import React, { useState, useEffect, useCallback, useRef } from 'react';
import {
  View, StyleSheet, Pressable, Text, StatusBar, Dimensions,
  ScrollView, DeviceEventEmitter, AppState, AppStateStatus, NativeModules,
} from 'react-native';
import { PluginManager, PluginNoteAPI, PluginFileAPI, PluginCommAPI } from 'sn-plugin-lib';

import FloatingToolbarBridge, { ToolItem } from './components/FloatingToolbarBridge';
import FloatingBubbleBridge from './components/FloatingBubbleBridge';
import {
  AVAILABLE_TOOLS, getAvailableTools,
  loadConfig, saveConfig, isValidToolCount,
  loadClips, injectClipStatus, ClipData,
  getLayoutForCount, getToolCategory, ToolCategory,
  getAvailableBubbleActions, loadBubbleActions, saveBubbleActions, BubbleAction,
} from './components/ToolPresets';
import {
  ensureInit, getActiveMode, flushPendingTexts, reviveIfNeeded,
  setInsertTop, refreshBubbleActions, stopMode, stopAiMode,
} from './components/BackgroundService';
import { executeAction, attachModeListeners, detachModeListeners } from './components/ToolActions';
import { InsertMode } from './components/TextInserter';
import { FileLogger } from './components/FileLogger';
import { LassoExtractor } from './components/LassoExtractor';
import { checkPendingButton, peekPendingButton, setAppMounted } from './pendingButton';
import { t } from './components/i18n';

import { CropOverlay, CropResult } from './components/CropOverlay';
import { StitchEditor } from './components/StitchEditor';
import { StitchSession, StitchSessionData, StagingService, ScreenshotService } from './components/ScreenshotPipeline';
import { PenGuard } from './components/PenTools';
const { ScreenshotModule } = NativeModules;

const screenWidth  = Dimensions.get('window').width;
const screenHeight = Dimensions.get('window').height;
const WINDOW_WIDTH  = screenWidth  * 0.65;
const WINDOW_HEIGHT = screenHeight * 0.72;

type AppScreen = 'main' | 'add_tool' | 'permission' | 'nativeHelper' | 'cropping' | 'stitching' | 'penLock';
type CatFilter = 'all' | 'layer' | 'insert' | 'text' | 'lasso';

const CAT_FILTERS: { key: CatFilter; labelKey: string }[] = [
  { key: 'all',    labelKey: 'cat_all' },
  { key: 'layer',  labelKey: 'cat_layer' },
  { key: 'insert', labelKey: 'cat_insert' },
  { key: 'text',   labelKey: 'cat_text' },
  { key: 'lasso',  labelKey: 'cat_lasso' },
];

const LAYOUT_OPTIONS = [
  { key: '3x2', cols: 3, rows: 2, count: 4,  label: '3×2' },
  { key: '4x2', cols: 4, rows: 2, count: 6,  label: '4×2' },
  { key: '3x3', cols: 3, rows: 3, count: 7,  label: '3×3' },
  { key: '4x3', cols: 4, rows: 3, count: 10, label: '4×3' },
];

function MiniToolbarPreview({ tools, side }: { tools: ToolItem[]; side: 'left' | 'right' }) {
  const layout = getLayoutForCount(tools.length);
  const { cols, rows } = layout;
  const isL = side === 'left';
  const cellSize = 22;
  const gap = 2;
  const pad = 2;
  const maxShow = cols * rows;
  const handleW = 4;
  const gridW = cols * cellSize + (cols - 1) * gap + pad * 2;
  const gridH = rows * cellSize + (rows - 1) * gap + pad * 2;

  const gridView = (
    <View style={{ width: gridW, height: gridH, backgroundColor: '#F0F0F0', borderRadius: 2, padding: pad, gap, flexDirection: 'row', flexWrap: 'wrap' }}>
      {[...Array(maxShow)].map((_, i) => (
        <View key={i} style={{ width: cellSize, height: cellSize, backgroundColor: tools[i] ? '#888' : '#CCCCCC', borderRadius: 2, margin: gap / 2 }} />
      ))}
    </View>
  );
  const handleView = (
    <View style={{ width: handleW, height: gridH, backgroundColor: '#AAAAAA', borderRadius: 1 }} />
  );
  return (
    <View style={{ flexDirection: 'row', alignItems: 'center' }}>
      {!isL && gridView}{handleView}{isL && gridView}
    </View>
  );
}

function App(): React.JSX.Element {

  const initialPending = peekPendingButton();
  const initialPendingScreen = FloatingToolbarBridge.getPendingScreenSync();
  const hasScreenshotPending = ScreenshotModule?.hasPendingPath?.() === true;
  console.log('[LASSO-DBG/App] App component init: pendingBtn=', initialPending, 'pendingScreen=', JSON.stringify(initialPendingScreen), 'hasScreenshotPending=', hasScreenshotPending);
  const [screen, setScreen]               = useState<AppScreen>(
    (initialPending === 300 || hasScreenshotPending) ? 'nativeHelper' :
    (initialPendingScreen === 'penLock') ? 'penLock' :
    'main'
  );
  const [tools, setTools]                 = useState<ToolItem[]>(AVAILABLE_TOOLS.slice(0, 7));
  const [clips, setClips]                 = useState<ClipData>({ '1': null, '2': null, '3': null, '4': null });
  const [statusMsg, setStatusMsg]         = useState('');
  const [hasPermission, setHasPermission] = useState(false);
  const [insertMode, setInsertMode]       = useState<InsertMode | null>(null);
  const [_resumeTick, setResumeTick]      = useState(0);
  const dockSide                          = 'left' as const;
  const [catFilter, setCatFilter]         = useState<CatFilter>('all');
  const [bubbleActionIds, setBubbleActionIds] = useState<string[]>([]);
  const allBubbleActions                      = getAvailableBubbleActions();
  const [toolbarOrientation, setToolbarOrientation] = useState<'horizontal' | 'vertical'>(
    () => (FloatingToolbarBridge as any).getOrientationSync?.() || 'horizontal'
  );

  const [screenshotUri, setScreenshotUri]       = useState<string | null>(null);
  const [originalSize, setOriginalSize]         = useState({ width: 0, height: 0 });
  const [hasStitchSession, setHasStitchSession] = useState(false);
  const [stitchSession, setStitchSession]       = useState<StitchSessionData | null>(null);
  const [isCompositing, setIsCompositing]       = useState(false);
  const screenshotBusyRef = useRef(false);
  const actionInProgressRef = useRef(false);
  const nativeSendActiveRef = useRef(false);

  const toolsRef      = useRef(tools);       toolsRef.current      = tools;
  const clipsRef      = useRef(clips);       clipsRef.current      = clips;
  const insertModeRef = useRef(insertMode);  insertModeRef.current = insertMode;
  const hasPermissionRef = useRef(hasPermission); hasPermissionRef.current = hasPermission;
  const screenRef     = useRef(screen);      screenRef.current     = screen;

  const showStatus = (msg: string) => {
    setStatusMsg(msg);
    setTimeout(() => setStatusMsg(''), 2500);
  };

  const refreshToolbar = useCallback(async (c?: ClipData, ts?: ToolItem[], mode?: InsertMode | null) => {
    const data = c   || clipsRef.current;
    const tl   = ts  || toolsRef.current;
    const m    = mode !== undefined ? mode : insertModeRef.current;
    FloatingToolbarBridge.updateTools(injectClipStatus(tl, data, m));
  }, []);

  const resetTransientScreens = useCallback(() => {
    setScreenshotUri(null);
    setOriginalSize({ width: 0, height: 0 });
    setStitchSession(null);
  }, []);

  const openMainPanel = useCallback(() => {
    flushPendingTexts();
    reviveIfNeeded();
    resetTransientScreens();
    FloatingToolbarBridge.ackPendingScreen();
    setScreen('main');
    setResumeTick(n => n + 1);
  }, [resetTransientScreens]);

  const layout      = getLayoutForCount(tools.length);
  const layoutLabel = `${layout.cols}×${layout.rows}`;

  const loadScreenshot = useCallback(async () => {
    console.log('[CROP-DBG/App] loadScreenshot START, busy=', screenshotBusyRef.current);
    if (screenshotBusyRef.current) return;
    screenshotBusyRef.current = true;
    setScreen('cropping');
    setScreenshotUri(null);
    setOriginalSize({ width: 0, height: 0 });
    try {
      const nativePath: string | null = await ScreenshotModule.getPendingPath();
      console.log('[CROP-DBG/App] getPendingPath =', nativePath);
      const dims = await ScreenshotService.getDeviceDimensions();
      console.log('[CROP-DBG/App] device dims =', dims);

      let uri: string;
      if (nativePath) {
        uri = `file://${nativePath}`;
      } else {
        console.log('[CROP-DBG/App] no native path, calling ScreenshotService.capture()');
        uri = await ScreenshotService.capture();
        console.log('[CROP-DBG/App] capture result =', uri);
      }

      const activeSession = await StitchSession.load();
      if (activeSession && activeSession.images.length >= 1) {
        const rawPath = nativePath || uri.replace('file://', '');
        const updatedSession = await StitchSession.addImage(rawPath, dims.width, dims.height);
        if (updatedSession && updatedSession.images.length >= 2) {
          console.log('[CROP-DBG/App] entering stitch mode');
          setStitchSession(updatedSession);
          setScreen('stitching');
          screenshotBusyRef.current = false;
          return;
        }
      }

      console.log('[CROP-DBG/App] setting screenshotUri =', uri, 'dims =', dims);
      setScreenshotUri(uri);
      setOriginalSize(dims);
      const active = await StitchSession.hasActiveSession();
      setHasStitchSession(active);
    } catch (e) {
      console.error('[CROP-DBG/App] loadScreenshot error:', e);
    } finally {
      screenshotBusyRef.current = false;
      console.log('[CROP-DBG/App] loadScreenshot END');
    }
  }, []);

  const handleCropConfirm = useCallback(async (crop: CropResult, stayOpen: boolean) => {
    if (!screenshotUri || screenshotBusyRef.current) return;
    screenshotBusyRef.current = true;
    try {
      const croppedUri = await ScreenshotService.crop(screenshotUri, crop);
      await StagingService.stageToQueueOnly(croppedUri, { deleteSrcAfter: true });
      if (!stayOpen) PluginManager.closePluginView();
    } catch {} finally {
      screenshotBusyRef.current = false;
    }
  }, [screenshotUri]);

  const handleLongScreenshot = useCallback(async () => {
    if (!screenshotUri || screenshotBusyRef.current) return;
    screenshotBusyRef.current = true;
    try {
      const rawPath = screenshotUri.replace('file://', '');
      const existingSession = await StitchSession.load();
      if (existingSession) {
        await StitchSession.addImage(rawPath, originalSize.width, originalSize.height);
      } else {
        await StitchSession.startSession(rawPath, originalSize.width, originalSize.height);
      }
      PluginManager.closePluginView();
    } catch {} finally {
      screenshotBusyRef.current = false;
    }
  }, [screenshotUri, originalSize]);

  const handleCropAddToHistory = useCallback(async (crop: CropResult, stayOpen: boolean) => {
    if (!screenshotUri || screenshotBusyRef.current) return;
    screenshotBusyRef.current = true;
    try {
      const croppedUri = await ScreenshotService.crop(screenshotUri, crop);
      await StagingService.saveToHistoryOnly(croppedUri);
      if (!stayOpen) PluginManager.closePluginView();
    } catch {} finally {
      screenshotBusyRef.current = false;
    }
  }, [screenshotUri]);

  const handleStitchConfirm = useCallback(async (session: StitchSessionData) => {
    if (screenshotBusyRef.current) return;
    screenshotBusyRef.current = true;
    setIsCompositing(true);
    try {
      const nativeParams = JSON.stringify({
        direction: session.params.direction,
        overlap: session.params.overlap,
        topLayerIndex: session.params.topLayerIndex,
        images: session.images.map(img => ({
          path: img.path, width: img.width, height: img.height, crop: img.crop,
        })),
      });

      const compositePath: string = await ScreenshotModule.compositeImages(nativeParams);

      const imgs = session.images;
      const effW = imgs.map(img => Math.round(img.width * (1 - img.crop.cropLeft - img.crop.cropRight)));
      const effH = imgs.map(img => Math.round(img.height * (1 - img.crop.cropTop - img.crop.cropBottom)));
      let compW: number, compH: number;
      if (session.params.direction === 'vertical') {
        compW = Math.max(effW[0], effW[1]);
        compH = effH[0] + effH[1] - session.params.overlap;
      } else {
        compW = effW[0] + effW[1] - session.params.overlap;
        compH = Math.max(effH[0], effH[1]);
      }

      await StitchSession.clearSession();
      setHasStitchSession(false);
      setStitchSession(null);
      setScreenshotUri(`file://${compositePath}`);
      setOriginalSize({ width: compW, height: compH });
      setIsCompositing(false);
      setScreen('cropping');
    } catch (e) {
      console.log('Stitch composite error:', e);
      setIsCompositing(false);
      PluginManager.closePluginView();
    } finally {
      screenshotBusyRef.current = false;
    }
  }, []);

  const handleStitchCancel = useCallback(async () => {
    await StitchSession.keepFirstOnly();
    setStitchSession(null);
    PluginManager.closePluginView();
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
            FloatingToolbarBridge.show(
              injectClipStatus(toolsRef.current, clipsRef.current, getActiveMode())
            );
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
      resetTransientScreens();
      FloatingToolbarBridge.ackPendingScreen();
      setScreen('main');
      setResumeTick(n => n + 1);
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
        resetTransientScreens();
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
        setScreen(pendingScreen as AppScreen);
        setResumeTick(n => n + 1);
        setTimeout(() => FloatingToolbarBridge.ackPendingScreen(), 200);
      }
    } else if (toolbarShowing) {
      const earlyPending = checkPendingButton();
      if (earlyPending === 999) {
        openMainPanel();
      } else if (earlyPending === 300) {
        FloatingToolbarBridge.hide();
        FloatingBubbleBridge.hide();
        setTimeout(() => loadScreenshot(), 100);
      } else if (earlyPending === 100) {
        setTimeout(() => { try { PluginManager.closePluginView(); } catch (_) {} }, 0);
      } else {
        setTimeout(() => { try { PluginManager.closePluginView(); } catch (_) {} }, 0);
      }
    } else if (hasScreenshotPending) {
      loadScreenshot();
    } else {
      openMainPanel();
    }

    const existingMode = getActiveMode();
    if (existingMode) setInsertMode(existingMode);

    Promise.all([loadConfig(), loadClips(), loadBubbleActions()]).then(([configData, clipData, bubbleIds]) => {
      setTools(configData.tools);
      setClips(clipData);
      setBubbleActionIds(bubbleIds);
      refreshToolbar(clipData, configData.tools);
      const filled = ([1,2,3,4] as const).map(n => !!clipData[String(n) as keyof typeof clipData]);
      FloatingToolbarBridge.updateTitleClips(filled);
    });

    FloatingToolbarBridge.checkPermission().then(ok => setHasPermission(ok));

    const toolTapSub = FloatingToolbarBridge.onToolTap(async ({ toolAction }) => {
      const result = await executeAction(toolAction);
      console.log('[App]: tool result:', result);

      if (toolAction === 'send_ai' && result === 'AI receive: ON') {
        if (hasPermissionRef.current) {
          FloatingToolbarBridge.show(injectClipStatus(toolsRef.current, clipsRef.current, getActiveMode()));
        }
        setTimeout(() => PluginManager.closePluginView(), 300);
        return;
      }
      if (typeof result === 'string' &&
          (result.startsWith('Saved to clip') || (result.startsWith('Clip') && result.endsWith('cleared')))) {
        const newClips = await loadClips();
        setClips(newClips);
        refreshToolbar(newClips);
      }
    });

    const toolModeExitSub = FloatingToolbarBridge.onToolModeExit(async ({ toolAction }) => {
      console.log('[App]: onToolModeExit:', toolAction);
      if (toolAction === 'send_ai') return;
      await executeAction(toolAction);
    });

    const clipsChangedSub = DeviceEventEmitter.addListener('clipsChanged', async () => {
      const newClips = await loadClips();
      setClips(newClips);
      refreshToolbar(newClips);
    });

    const longPressSub = FloatingToolbarBridge.onToolLongPress(async ({ toolId }) => {
      if (toolId.startsWith('clip_')) {
        const slot   = toolId.split('_')[1];
        const result = await executeAction(`clip_clear_${slot}`);
        console.log('[App]: long press clear:', result);
        const newClips = await loadClips();
        setClips(newClips);
        refreshToolbar(newClips);
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
          resetTransientScreens();
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
          FloatingToolbarBridge.hide();
          setScreen(pendingScr as AppScreen);
          FloatingToolbarBridge.ackPendingScreen();
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

    const permDeniedSub = FloatingToolbarBridge.onPermissionDenied(() => {
      setHasPermission(false);
      setScreen('permission');
    });

    const clipChangeSub = DeviceEventEmitter.addListener('clipboardChanged', async () => {
      const newClips = await loadClips();
      setClips(newClips);
      refreshToolbar(newClips);
    });

    const modeSub = DeviceEventEmitter.addListener(
      'insertModeChanged',
      ({ mode }: { mode: InsertMode | null }) => {
        console.log('[App]: insertModeChanged →', mode);
        setInsertMode(mode);

        refreshToolbar(undefined, undefined, mode);
        if (!mode) {

          FloatingBubbleBridge.hide();
          if (hasPermissionRef.current && FloatingToolbarBridge.isShowingSync()) {
            FloatingToolbarBridge.show(injectClipStatus(toolsRef.current, clipsRef.current, null));
          }
        }
      },
    );

    const localeSub = DeviceEventEmitter.addListener('localeChanged', (_: unknown) => {
      loadConfig().then(d => setTools(d.tools));
    });

    const nativeBubbleDragSub = FloatingBubbleBridge.onDragEnd(({ pageY }) => {
      setInsertTop(pageY);
    });

    const appStateSub = AppState.addEventListener('change', (state: AppStateStatus) => {
      if (state === 'active') {
        reviveIfNeeded();
        const pending = FloatingToolbarBridge.checkPendingOpenMainSync();
        if (pending && screenRef.current !== 'penLock') {
          FloatingToolbarBridge.hide();
          setScreen('main');
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

        FloatingToolbarBridge.hide();
        FloatingBubbleBridge.hide();
        loadScreenshot();
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
            resetTransientScreens();
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
            setScreen(pendingScr as AppScreen);
            setResumeTick(n => n + 1);
            setTimeout(() => FloatingToolbarBridge.ackPendingScreen(), 200);
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
      cacheBaseName?: string;
      replaceNotePath?: string;
      replacePageNum?: number;
      replaceNumInPage?: number;
    }) => {
      const { path, fromQueue, cacheBaseName, replaceNotePath, replacePageNum, replaceNumInPage } = evt;
      const now = Date.now();
      const elapsed = now - lastInsertTime;
      console.log('[INSERT-DBG/App] nativeInsertImage event, path=', path, 'elapsed=', elapsed, 'fromQueue=', fromQueue);
      if (!fromQueue && elapsed < INSERT_DEDUP_MS) {
        console.log('[INSERT-DBG/App] skipped (dedup, elapsed=' + elapsed + 'ms)');
        return;
      }
      lastInsertTime = now;

      if (PluginNoteAPI) {
        console.log('[INSERT-DBG/App] calling PluginNoteAPI.insertImage');
        try {
          const result = PluginNoteAPI.insertImage(path);
          console.log('[INSERT-DBG/App] insertImage returned:', result);
          if (result && typeof result.then === 'function') {
            result.then(async (r: any) => {
              console.log('[INSERT-DBG/App] insertImage promise resolved:', r);
              const isQueuePath = typeof path === 'string' && path.includes('/.plugin_staging/queue/');
              if (r && r.success && isQueuePath) {
                console.log('[INSERT-DBG/App] insert succeeded → deleting queue file');
                FloatingToolbarBridge.deleteQueueFile(path).then(d =>
                  console.log('[INSERT-DBG/App] queue file delete result:', d)
                );
              } else if (!r || !r.success) {
                console.warn('[INSERT-DBG/App] insert FAILED, keeping queue file:', r?.error);
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
          if (fpRes?.success && fpRes.result && pgRes?.success && pgRes.result !== undefined) {
            const psRes: any = await PluginFileAPI.getPageSize(fpRes.result, pgRes.result);
            if (psRes?.success && psRes.result) {
              pageW = psRes.result.width;
              pageH = psRes.result.height;
            }
          }

          const linkW = Math.min(linkName.length * 30 + 40, pageW * 0.6);
          const left = Math.round((pageW - linkW) / 2);
          const top = Math.round(pageH * 0.15);
          const fontSize = 49;
          const lineH = fontSize + 10;

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
        } catch (e) {
          console.error('[App] insertTextLink error:', e);
        }
      }
    });

    const nativeCloseSub = DeviceEventEmitter.addListener('nativeClosePluginView', () => {
      PluginManager.closePluginView();
    });

    const titleClipSub = FloatingToolbarBridge.onTitleClipTap(async ({ slot }) => {
      const result = await executeAction(`clip_paste_${slot}`);
      if (typeof result === 'string' &&
          (result.startsWith('Saved to clip') || (result.startsWith('Clip') && result.endsWith('cleared')))) {
        const newClips = await loadClips();
        setClips(newClips);
        refreshToolbar(newClips);
        const filled = ([1,2,3,4] as const).map(n => !!newClips[String(n) as keyof typeof newClips]);
        FloatingToolbarBridge.updateTitleClips(filled);
      }
    });

    const titleClipLongSub = (FloatingToolbarBridge as any).onTitleClipLongPress
      ? (FloatingToolbarBridge as any).onTitleClipLongPress(async ({ slot }: { slot: string }) => {
          await executeAction(`clip_clear_${slot}`);
          const newClips = await loadClips();
          setClips(newClips);
          refreshToolbar(newClips);
          const filled = ([1,2,3,4] as const).map(n => !!newClips[String(n) as keyof typeof newClips]);
          FloatingToolbarBridge.updateTitleClips(filled);
        })
      : { remove() {} };

    const titleLayerSub = FloatingToolbarBridge.onTitleLayerAction(async ({ direction }) => {
      await executeAction(direction === 'prev' ? 'layer_prev' : 'layer_next');
    });

    const nativePanelOpenSub = FloatingToolbarBridge.onNativePanelOpen(async () => {
      try { await PenGuard.begin(); } catch (e) { console.warn('[App] PenGuard.begin failed:', e); }
    });

    const nativePanelCloseSub = FloatingToolbarBridge.onNativePanelClose(async ({ panel }) => {
      if (panel === 'send') nativeSendActiveRef.current = false;
      try { await PenGuard.end(); } catch (e) { console.warn('[App] PenGuard.end failed:', e); }
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
      permDeniedSub.remove();
      clipChangeSub.remove();
      modeSub.remove();
      localeSub.remove();
      btnSub.remove();
      nativeBubbleDragSub.remove();
      appStateSub.remove();
      nativeInsertSub.remove();
      nativeDocLinkSub.remove();
      nativeCloseSub.remove();
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

  const isFirstToolsLoad = useRef(true);
  useEffect(() => {
    if (isFirstToolsLoad.current) { isFirstToolsLoad.current = false; return; }
    if (isValidToolCount(tools.length)) saveConfig({ tools });
  }, [tools]);

  const showToolbarAndClose = useCallback(async () => {
    if (!hasPermission) { setScreen('permission'); return; }
    FloatingToolbarBridge.show(injectClipStatus(toolsRef.current, clipsRef.current, insertModeRef.current));

    const clips = clipsRef.current;
    const filled = ([1,2,3,4] as const).map(n => !!clips[String(n) as keyof typeof clips]);
    FloatingToolbarBridge.updateTitleClips(filled);
    setTimeout(() => PluginManager.closePluginView(), 150);
  }, [hasPermission]);

  const removeTool  = useCallback((id: string) => setTools(prev => prev.filter(t => t.id !== id)), []);
  const moveToolUp  = useCallback((idx: number) => {
    if (idx <= 0) return;
    setTools(prev => { const a = [...prev]; [a[idx - 1], a[idx]] = [a[idx], a[idx - 1]]; return a; });
  }, []);
  const moveToolDown = useCallback((idx: number) => {
    setTools(prev => {
      if (idx >= prev.length - 1) return prev;
      const a = [...prev]; [a[idx], a[idx + 1]] = [a[idx + 1], a[idx]]; return a;
    });
  }, []);
  const addTool  = useCallback((tool: ToolItem) => setTools(prev => prev.some(t => t.id === tool.id) ? prev : [...prev, tool]), []);

  const collapseAll = useCallback(() => {
    showToolbarAndClose();
  }, [hasPermission, tools, clips, insertMode]);

  const destroyAll = useCallback(() => {
    FloatingToolbarBridge.destroyAll();
    PluginManager.closePluginView();
  }, []);

  const maxVisible    = layout.cols * layout.rows - 1;
  const overflowCount = Math.max(0, tools.length - maxVisible);

  console.log('[LASSO-DBG/App] render: screen=', screen, 'cropUri=', screenshotUri, 'cropDims=', originalSize.width + 'x' + originalSize.height);
  return (
    <View style={st.container}>
      <StatusBar barStyle="dark-content" />
      <View key={`ct-${_resumeTick}`} style={st.centerWrapper}>

        {screen === 'permission' && (
          <View style={[st.window, { width: WINDOW_WIDTH, height: WINDOW_HEIGHT * 0.55 }]}>
            <View style={st.sectionHeader}>
              <View style={st.headerRow}>
                <View style={st.permIcon}><Text style={st.permIconText}>!</Text></View>
                <View>
                  <Text style={st.headerLabel}>{t('perm_required')}</Text>
                  <Text style={st.headerSub}>PERMISSION REQUIRED</Text>
                </View>
              </View>
            </View>
            <View style={st.permBody}>
              <Text style={st.permText}>{t('perm_text')}</Text>
              <View style={st.permPkg}><Text style={st.permPkgText}>com.ratta.supernote.pluginhost</Text></View>
              <View style={st.permBtns}>
                <Pressable onPress={() => { FloatingToolbarBridge.requestPermission(); showStatus(t('perm_check_settings')); }} style={st.btnFill}>
                  <Text style={st.btnFillT}>{t('perm_open_settings')}</Text>
                </Pressable>
                <Pressable onPress={async () => {
                  const ok = await FloatingToolbarBridge.checkPermission();
                  setHasPermission(ok);
                  if (ok) { setScreen('main'); showStatus(t('perm_granted')); }
                  else showStatus(t('perm_not_granted'));
                }} style={st.btnLine}>
                  <Text style={st.btnLineT}>{t('perm_recheck')}</Text>
                </Pressable>
                <Pressable onPress={() => setScreen('main')} style={st.btnGhost}>
                  <Text style={st.btnGhostT}>{t('back')}</Text>
                </Pressable>
              </View>
            </View>
            {statusMsg ? <View style={st.toast}><Text style={st.toastT}>{statusMsg}</Text></View> : null}
          </View>
        )}

        {screen === 'main' && (
          <View style={[st.window, { width: WINDOW_WIDTH, height: WINDOW_HEIGHT }]}>
            <View style={st.titleBar}>
              <Text style={st.titleTextCenter}>{t('app_title')}</Text>
            </View>
            {statusMsg ? <View style={st.toast}><Text style={st.toastT}>{statusMsg}</Text></View> : null}

            <View style={st.prevSection}>
              <View style={st.prevHeader}>
                <View style={st.headerRow}>
                  <Text style={st.headerLabel}>{t('preview')}</Text>
                  <Text style={st.headerSub}>PREVIEW</Text>
                </View>
              </View>
              <View style={st.prevBody}>
                <View style={st.devFrame}>
                  {[...Array(8)].map((_, i) => (
                    <View key={i} style={{ height: 3, borderRadius: 1, marginBottom: 5, backgroundColor: i % 4 === 0 ? '#CCCCCC' : '#DDDDDD', width: i === 7 ? '25%' : i % 3 === 2 ? '65%' : '90%', marginHorizontal: 10 }} />
                  ))}
                  <View style={[st.miniWrap, dockSide === 'left' ? { left: 0 } : { right: 0 }]}>
                    <MiniToolbarPreview tools={tools} side={dockSide} />
                  </View>
                </View>
                <View style={st.layoutCol}>
                  <View style={st.layoutBadge}>
                    <Text style={st.layoutBadgeT}>{layoutLabel}</Text>
                    <Text style={st.layoutBadgeS}>{t('buttons_count', { n: tools.length })}</Text>
                  </View>
                  <View style={st.layoutMiniRow}>
                    {LAYOUT_OPTIONS.map(lo => {
                      const cur = lo.count === maxVisible;
                      return (
                        <View key={lo.key} style={[st.layoutMini, cur && st.layoutMiniA]}>
                          <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: 1, width: lo.cols * 7 + (lo.cols - 1) }}>
                            {[...Array(lo.count)].map((_, j) => (
                              <View key={j} style={{ width: 6, height: 6, borderRadius: 1, backgroundColor: cur ? (j === 0 ? '#333' : '#AAA') : '#CCC' }} />
                            ))}
                          </View>
                          <Text style={[st.layoutMiniT, cur && st.layoutMiniTA]}>{lo.label}</Text>
                        </View>
                      );
                    })}
                  </View>
                  {!isValidToolCount(tools.length) && (
                    <View style={st.warnBadge}><Text style={st.warnBadgeT}>{t('tools_count_hint', { n: tools.length })}</Text></View>
                  )}
                </View>
              </View>
            </View>

            <View style={st.toolSection}>
              <View style={st.toolHeader}>
                <View style={st.headerRow}>
                  <Text style={st.headerLabel}>{t('tool_list')}</Text>
                  <Text style={st.headerSub}>TOOLS · {tools.length}</Text>
                </View>
                <Pressable onPress={() => { setCatFilter('all'); setScreen('add_tool'); }} style={st.addBtn}>
                  <Text style={st.addBtnT}>{t('add_tool')}</Text>
                </Pressable>
              </View>
              <ScrollView style={st.toolScroll}>
                {tools.length === 0 && (
                  <View style={st.empty}>
                    <Text style={st.emptyT}>{t('empty_tools')}</Text>
                    <Text style={st.emptyH}>{t('empty_tools_hint')}</Text>
                  </View>
                )}
                {injectClipStatus(tools, clips, insertMode).map((tool, idx) => {
                  const muted = idx >= maxVisible;
                  return (
                    <View key={tool.id + idx} style={st.toolRow}>
                      <View style={[st.idxBadge, muted && st.idxBadgeM]}>
                        <Text style={[st.idxBadgeT, muted && st.idxBadgeTM]}>{idx + 1}</Text>
                      </View>
                      <View style={[st.toolIcon, muted && st.toolIconM]}>
                        <Text style={[st.toolIconT, muted && st.toolIconTM]}>{tool.icon}</Text>
                      </View>
                      <View style={st.toolInfo}>
                        <Text style={[st.toolName, muted && st.toolNameM]}>{tool.name}</Text>
                        <Text style={st.toolAct}>{tool.action}</Text>
                      </View>
                      <View style={st.moveBtns}>
                        <Pressable onPress={() => moveToolUp(idx)} style={st.moveBtn} hitSlop={8}><Text style={st.moveBtnT}>▲</Text></Pressable>
                        <Pressable onPress={() => moveToolDown(idx)} style={st.moveBtn} hitSlop={8}><Text style={st.moveBtnT}>▼</Text></Pressable>
                      </View>
                    </View>
                  );
                })}
              </ScrollView>
              {overflowCount > 0 && (
                <View style={st.overflowBar}>
                  <Text style={st.overflowT}>{t('overflow_warn', { max: maxVisible, layout: layoutLabel, extra: overflowCount })}</Text>
                </View>
              )}
            </View>

            <View style={st.bubbleSection}>
              <View style={st.bubbleHeader}>
                <Text style={st.headerLabel}>{t('bubble_actions_title')}</Text>
                <Text style={st.headerSub}>{t('bubble_actions_hint')}</Text>
              </View>
              <View style={st.bubbleRow}>
                {allBubbleActions.map(ba => {
                  const enabled = bubbleActionIds.includes(ba.id);
                  return (
                    <Pressable
                      key={ba.id}
                      onPress={async () => {
                        const next = enabled
                          ? bubbleActionIds.filter(x => x !== ba.id)
                          : [...bubbleActionIds, ba.id];
                        setBubbleActionIds(next);
                        await saveBubbleActions(next);
                        refreshBubbleActions();
                      }}
                      style={[st.bubbleChip, enabled && st.bubbleChipActive]}
                    >
                      <Text style={[st.bubbleChipIcon, enabled && st.bubbleChipIconActive]}>{ba.icon}</Text>
                      <Text style={[st.bubbleChipLabel, enabled && st.bubbleChipLabelActive]}>{ba.label}</Text>
                    </Pressable>
                  );
                })}
              </View>
            </View>

            <View style={st.bottomBar}>
              <Pressable onPress={() => {
                const next = toolbarOrientation === 'vertical' ? 'horizontal' : 'vertical';
                (FloatingToolbarBridge as any).setOrientation?.(next);
                setToolbarOrientation(next);
              }} style={st.btnGhost}>
                <Text style={st.btnGhostT}>{toolbarOrientation === 'vertical' ? t('orient_v') : t('orient_h')}</Text>
              </Pressable>
              <Pressable onPress={collapseAll} style={st.btnLine}><Text style={st.btnLineT}>{t('collapse')}</Text></Pressable>
              <Pressable onPress={destroyAll} style={st.btnFill}><Text style={st.btnFillT}>{t('save')}</Text></Pressable>
            </View>
          </View>
        )}

        {screen === 'add_tool' && (
          <View style={[st.window, { width: WINDOW_WIDTH, height: WINDOW_HEIGHT }]}>
            <View style={st.titleBar}>
              <Text style={st.titleTextCenter}>{t('add_tool_title')}</Text>
            </View>
            <View style={st.catRow}>
              {CAT_FILTERS.map(cf => (
                <Pressable key={cf.key} onPress={() => setCatFilter(cf.key)} style={[st.catPill, catFilter === cf.key && st.catPillA]}>
                  <Text style={[st.catPillT, catFilter === cf.key && st.catPillTA]}>{t(cf.labelKey as any)}</Text>
                </Pressable>
              ))}
            </View>
            <ScrollView style={st.toolScroll}>
              {injectClipStatus(getAvailableTools(), clips, insertMode)
                .filter(tool => catFilter === 'all' || getToolCategory(tool.id) === catFilter)
                .map(tool => {
                  const added = tools.some(t => t.id === tool.id);
                  return (
                    <Pressable key={tool.id} onPress={() => !added && addTool(tool)} style={st.toolRow}>
                      <View style={[st.toolIcon, added && st.toolIconM]}>
                        <Text style={[st.toolIconT, added && st.toolIconTM]}>{tool.icon}</Text>
                      </View>
                      <View style={st.toolInfo}>
                        <Text style={[st.toolName, added && st.toolNameM]}>{tool.name}</Text>
                        <Text style={st.toolAct}>{tool.action}</Text>
                      </View>
                      {added
                        ? <Pressable onPress={() => removeTool(tool.id)} style={st.delBtn} hitSlop={8}><Text style={st.delBtnT}>✕</Text></Pressable>
                        : <View style={st.addIconBtn}><Text style={st.addIconBtnT}>+</Text></View>}
                    </Pressable>
                  );
                })}
            </ScrollView>
            <View style={st.bottomBar}>
              <Text style={st.footerCount}>{t('selected_count', { n: tools.length })} · {layoutLabel}</Text>
              <Pressable
                onPress={() => { if (tools.length >= 4) setScreen('main'); }}
                style={[st.btnFill, tools.length < 4 && st.btnDis]}
                disabled={tools.length < 4}
              >
                <Text style={st.btnFillT}>{t('done')}</Text>
              </Pressable>
            </View>
          </View>
        )}

        {screen === 'nativeHelper' && (
          <View style={{ flex: 1, backgroundColor: 'transparent' }} />
        )}

        {screen === 'penLock' && (
          <View style={{ flex: 1, backgroundColor: 'transparent' }} />
        )}

      </View>

      {screen === 'cropping' && screenshotUri && originalSize.width > 0 && (
        <View style={StyleSheet.absoluteFill}>
          <CropOverlay
            key={screenshotUri}
            imageUri={screenshotUri}
            originalWidth={originalSize.width}
            originalHeight={originalSize.height}
            onConfirm={handleCropConfirm}
            onLongScreenshot={handleLongScreenshot}
            onAddToHistory={handleCropAddToHistory}
            onClose={() => PluginManager.closePluginView()}
            hasStitchSession={hasStitchSession}
          />
        </View>
      )}

      {screen === 'stitching' && stitchSession && (
        <View style={StyleSheet.absoluteFill}>
          <StitchEditor
            session={stitchSession}
            onConfirm={handleStitchConfirm}
            onCancel={handleStitchCancel}
            disabled={isCompositing}
          />
        </View>
      )}
    </View>
  );
}

const st = StyleSheet.create({
  container:     { flex: 1, backgroundColor: 'transparent' },
  centerWrapper: { flex: 1, justifyContent: 'center', alignItems: 'center' },

  window: { backgroundColor: '#FFFFFF', borderWidth: 1.5, borderColor: '#444444', borderRadius: 8, overflow: 'hidden' },

  titleBar:  { flexDirection: 'row', alignItems: 'center', justifyContent: 'center', paddingHorizontal: 14, paddingVertical: 10, borderBottomWidth: 1, borderBottomColor: '#D8D8D8' },
  titleText: { fontSize: 17, fontWeight: '700', color: '#111111' },
  titleTextCenter: { fontSize: 17, fontWeight: '700', color: '#111111', textAlign: 'center' },
  chipBtn:   { paddingVertical: 4, paddingHorizontal: 10, borderWidth: 1, borderColor: '#000000', borderRadius: 3, backgroundColor: '#FFFFFF' },
  chipBtnT:  { fontSize: 12, fontWeight: '700', color: '#000000' },
  backBtn:   { width: 28, height: 28, borderWidth: 1, borderColor: '#CCCCCC', borderRadius: 3, justifyContent: 'center', alignItems: 'center' },
  backBtnT:  { fontSize: 18, fontWeight: '500', color: '#333333', marginTop: -2 },

  toast:  { paddingHorizontal: 14, paddingVertical: 6, backgroundColor: '#111111' },
  toastT: { fontSize: 11, fontWeight: '500', color: '#FFFFFF' },

  sectionHeader: { paddingHorizontal: 14, paddingVertical: 10, borderBottomWidth: 1, borderBottomColor: '#E8E8E8' },
  headerRow:     { flexDirection: 'row', alignItems: 'center', gap: 6 },
  headerLabel:   { fontSize: 12, fontWeight: '700', color: '#222222' },
  headerSub:     { fontSize: 9,  fontWeight: '400', color: '#AAAAAA' },

  prevSection: { borderBottomWidth: 1, borderBottomColor: '#E0E0E0', backgroundColor: '#FAFAF8' },
  prevHeader:  { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', paddingHorizontal: 14, paddingTop: 8, paddingBottom: 6 },
  prevBody:    { flexDirection: 'row', alignItems: 'center', justifyContent: 'center', paddingHorizontal: 10, paddingBottom: 10, gap: 12 },

  devFrame:  { width: 120, height: 155, backgroundColor: '#E8E8E8', borderRadius: 6, borderWidth: 1, borderColor: '#888888', position: 'relative', overflow: 'hidden', paddingTop: 10 },
  miniWrap:  { position: 'absolute', top: '40%' },

  layoutCol:     { alignItems: 'center', gap: 6 },
  layoutBadge:   { paddingVertical: 4, paddingHorizontal: 10, borderWidth: 1.5, borderColor: '#444444', borderRadius: 4, alignItems: 'center' },
  layoutBadgeT:  { fontSize: 16, fontWeight: '700', color: '#111111' },
  layoutBadgeS:  { fontSize: 8, color: '#888888', marginTop: 1 },
  layoutMiniRow: { flexDirection: 'row', gap: 4 },
  layoutMini:    { alignItems: 'center', paddingVertical: 3, paddingHorizontal: 5, borderRadius: 3, borderWidth: 1, borderColor: '#DDDDDD', backgroundColor: '#FAFAFA' },
  layoutMiniA:   { borderColor: '#000000', backgroundColor: '#F5F3ED' },
  layoutMiniT:   { fontSize: 8, color: '#AAAAAA', marginTop: 2 },
  layoutMiniTA:  { color: '#000000', fontWeight: '700' },
  warnBadge:     { paddingVertical: 2, paddingHorizontal: 6, backgroundColor: '#FFF5F5', borderRadius: 3, borderWidth: 1, borderColor: '#EECCCC' },
  warnBadgeT:    { fontSize: 8, color: '#CC4444' },

  toolSection: { flex: 1 },
  toolHeader:  { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', paddingHorizontal: 14, paddingVertical: 8, borderBottomWidth: 1, borderBottomColor: '#EEEEEE', backgroundColor: '#FAFAFA' },
  addBtn:      { paddingVertical: 4, paddingHorizontal: 12, borderWidth: 1.5, borderColor: '#000000', borderRadius: 3, backgroundColor: '#FFFFFF' },
  addBtnT:     { fontSize: 11, fontWeight: '600', color: '#000000' },
  toolScroll:  { flex: 1 },

  toolRow:    { flexDirection: 'row', alignItems: 'center', paddingHorizontal: 10, paddingVertical: 8, borderBottomWidth: 1, borderBottomColor: '#F0F0F0', gap: 8 },
  toolRowDim: { opacity: 0.4 },

  idxBadge:   { width: 18, height: 18, borderRadius: 9, backgroundColor: '#000000', justifyContent: 'center', alignItems: 'center' },
  idxBadgeM:  { backgroundColor: '#DDDDDD' },
  idxBadgeT:  { fontSize: 9, fontWeight: '700', color: '#FFFFFF' },
  idxBadgeTM: { color: '#999999' },

  toolIcon:   { width: 36, height: 36, borderWidth: 1.5, borderColor: '#888888', borderRadius: 4, justifyContent: 'center', alignItems: 'center', backgroundColor: '#FFFFFF' },
  toolIconM:  { borderColor: '#DDDDDD', backgroundColor: '#F8F8F8' },
  toolIconT:  { fontSize: 13, fontWeight: '700', color: '#333333' },
  toolIconTM: { color: '#BBBBBB' },

  toolInfo:  { flex: 1 },
  toolName:  { fontSize: 13, fontWeight: '600', color: '#111111' },
  toolNameM: { color: '#AAAAAA' },
  toolAct:   { fontSize: 9, color: '#AAAAAA', marginTop: 1 },

  moveBtns: { flexDirection: 'column', gap: 2 },
  moveBtn:  { width: 24, height: 18, borderWidth: 1, borderColor: '#DDDDDD', borderRadius: 2, justifyContent: 'center', alignItems: 'center', backgroundColor: '#FAFAFA' },
  moveBtnT: { fontSize: 8, color: '#888888' },
  delBtn:   { width: 24, height: 24, borderWidth: 1, borderColor: '#E0C0C0', borderRadius: 3, justifyContent: 'center', alignItems: 'center', backgroundColor: '#FFF8F8' },
  delBtnT:  { fontSize: 10, color: '#CC6666' },

  empty:  { paddingVertical: 40, alignItems: 'center' },
  emptyT: { fontSize: 14, fontWeight: '600', color: '#999999' },
  emptyH: { fontSize: 11, color: '#BBBBBB', marginTop: 4 },

  overflowBar: { paddingHorizontal: 14, paddingVertical: 6, backgroundColor: '#FFFBE6', borderTopWidth: 1, borderTopColor: '#F0E8C0' },
  overflowT:   { fontSize: 9, color: '#A08800' },

  bubbleSection:        { borderTopWidth: 1, borderTopColor: '#E8E8E8', paddingHorizontal: 14, paddingVertical: 8, backgroundColor: '#FAFAF8' },
  bubbleHeader:         { marginBottom: 6 },
  bubbleRow:            { flexDirection: 'row', flexWrap: 'wrap', gap: 6 },
  bubbleChip:           { flexDirection: 'row', alignItems: 'center', gap: 4, paddingVertical: 5, paddingHorizontal: 10, borderWidth: 1.5, borderColor: '#DDDDDD', borderRadius: 4, backgroundColor: '#FFFFFF' },
  bubbleChipActive:     { borderColor: '#000000', backgroundColor: '#F5F3ED' },
  bubbleChipIcon:       { fontSize: 11, fontWeight: '700' as const, color: '#BBBBBB' },
  bubbleChipIconActive: { color: '#000000' },
  bubbleChipLabel:      { fontSize: 10, color: '#AAAAAA' },
  bubbleChipLabelActive:{ color: '#000000', fontWeight: '600' as const },

  catRow:    { flexDirection: 'row', flexWrap: 'wrap', gap: 4, paddingHorizontal: 14, paddingVertical: 8, borderBottomWidth: 1, borderBottomColor: '#F0F0F0' },
  catPill:   { paddingVertical: 3, paddingHorizontal: 10, borderWidth: 1, borderColor: '#DDDDDD', borderRadius: 12, backgroundColor: '#FFFFFF' },
  catPillA:  { backgroundColor: '#000000', borderColor: '#000000' },
  catPillT:  { fontSize: 10, color: '#888888' },
  catPillTA: { color: '#FFFFFF' },

  addedBadge:  { paddingVertical: 2, paddingHorizontal: 8, borderWidth: 1, borderColor: '#E0E0E0', borderRadius: 3 },
  addedBadgeT: { fontSize: 9, color: '#BBBBBB' },
  addIconBtn:  { width: 26, height: 26, borderWidth: 1.5, borderColor: '#000000', borderRadius: 3, justifyContent: 'center', alignItems: 'center' },
  addIconBtnT: { fontSize: 14, fontWeight: '700', color: '#000000' },
  footerCount: { fontSize: 10, color: '#888888' },

  bottomBar: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', borderTopWidth: 1, borderTopColor: '#D8D8D8', paddingHorizontal: 14, paddingVertical: 10 },

  btnFill:   { paddingVertical: 8, paddingHorizontal: 18, backgroundColor: '#000000', borderRadius: 3 },
  btnFillT:  { fontSize: 13, fontWeight: '600', color: '#FFFFFF' },
  btnLine:   { paddingVertical: 8, paddingHorizontal: 18, borderWidth: 1.5, borderColor: '#000000', backgroundColor: '#FFFFFF', borderRadius: 3 },
  btnLineT:  { fontSize: 13, fontWeight: '500', color: '#000000' },
  btnGhost:  { paddingVertical: 8, paddingHorizontal: 14, borderWidth: 1, borderColor: '#CCCCCC', borderRadius: 3, backgroundColor: '#FFFFFF' },
  btnGhostT: { fontSize: 13, color: '#888888' },
  btnDis:    { opacity: 0.35 },

  permIcon:     { width: 30, height: 30, borderWidth: 2, borderColor: '#000000', borderRadius: 15, justifyContent: 'center', alignItems: 'center' },
  permIconText: { fontSize: 15, fontWeight: '700', color: '#000000' },
  permBody:     { flex: 1, paddingHorizontal: 18, paddingVertical: 16 },
  permText:     { fontSize: 13, color: '#444444', lineHeight: 22, marginBottom: 12 },
  permPkg:      { paddingVertical: 5, paddingHorizontal: 10, backgroundColor: '#F8F8F8', borderRadius: 3, borderWidth: 1, borderColor: '#EEEEEE', marginBottom: 18 },
  permPkgText:  { fontSize: 9, color: '#999999' },
  permBtns:     { flexDirection: 'row', gap: 8, flexWrap: 'wrap' },
});

export default App;

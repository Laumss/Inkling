import React, { useState, useEffect, useCallback, useRef } from 'react';
import {
  View, StyleSheet, Text, Pressable, StatusBar,
  DeviceEventEmitter, NativeModules, AppState,
} from 'react-native';

import { CropOverlay, CropResult } from './components/CropOverlay';
import { HistoryView } from './components/HistoryView';
import { ScreenshotService } from './components/ScreenshotService';
import { StagingService } from './components/StagingService';
import { StitchSession, StitchSessionData } from './components/StitchSession';
import { StitchEditor } from './components/StitchEditor';
import { PluginManager, PluginNoteAPI, PluginCommAPI } from 'sn-plugin-lib';
import { checkPendingButton } from './index';

const { ScreenshotModule } = NativeModules;

type AppMode = 'cropping' | 'saving' | 'error' | 'history' | 'stitching';

export default function App(): React.JSX.Element {
  const [mode, setMode] = useState<AppMode>('cropping');
  const [screenshotUri, setScreenshotUri] = useState<string | null>(null);
  const [originalSize, setOriginalSize] = useState({ width: 0, height: 0 });
  const [currentDocPath, setCurrentDocPath] = useState('');
  const [hasStitchSession, setHasStitchSession] = useState(false);
  const [stitchSession, setStitchSession] = useState<StitchSessionData | null>(null);
  const [isCompositing, setIsCompositing] = useState(false);
  const busyRef = useRef(false);

  // Check for active stitch session
  const checkStitchSession = useCallback(async () => {
    const active = await StitchSession.hasActiveSession();
    setHasStitchSession(active);
    return active;
  }, []);

  const loadScreenshot = useCallback(async () => {
    if (busyRef.current) return;
    busyRef.current = true;
    setMode('cropping');
    setScreenshotUri(null);
    setOriginalSize({ width: 0, height: 0 });
    try {
      const nativePath: string | null = await ScreenshotModule.getPendingPath();
      const dims = await ScreenshotService.getDeviceDimensions();

      let uri: string;
      if (nativePath) {
        uri = `file://${nativePath}`;
      } else {
        uri = await ScreenshotService.capture();
      }

      // Check if there's an active stitch session with at least 1 image
      const activeSession = await StitchSession.load();
      if (activeSession && activeSession.images.length >= 1) {
        // Add the new screenshot to the session
        const rawPath = nativePath || uri.replace('file://', '');
        const updatedSession = await StitchSession.addImage(rawPath, dims.width, dims.height);
        if (updatedSession && updatedSession.images.length >= 2) {
          setStitchSession(updatedSession);
          setMode('stitching');
          busyRef.current = false;
          return;
        }
      }

      setScreenshotUri(uri);
      setOriginalSize(dims);
      await checkStitchSession();
    } finally {
      busyRef.current = false;
    }
  }, [checkStitchSession]);

  // NOTE button: queue has image → insert directly, else → history
  const handleNoteButton = useCallback(async () => {
    if (busyRef.current) return;
    busyRef.current = true;
    setScreenshotUri(null);
    setOriginalSize({ width: 0, height: 0 });
    try {
      const next = await StagingService.getNextQueued();
      if (next) {
        if (PluginNoteAPI) PluginNoteAPI.insertImage(next);
        await StagingService.dequeue();
        PluginManager.closePluginView();
      } else {
        setMode('history');
      }
    } finally {
      busyRef.current = false;
    }
  }, []);

  useEffect(() => {
    const btnSub = DeviceEventEmitter.addListener('screenshotCropButton', ({ id }) => {
      if (id === 100) loadScreenshot();
      if (id === 200) handleNoteButton();
    });

    const appStateSub = AppState.addEventListener('change', async (s) => {
      if (s === 'active' && await ScreenshotModule.getPendingPath()) loadScreenshot();
    });

    const pending = checkPendingButton();
    if (pending === 200) {
      handleNoteButton();
    } else {
      (async () => {
        await loadScreenshot();
        try {
          const fp = await (PluginCommAPI as any).getCurrentFilePath();
          if (fp?.success && fp.result) setCurrentDocPath(fp.result);
        } catch (_) {}
      })();
    }

    return () => { btnSub.remove(); appStateSub.remove(); };
  }, [loadScreenshot, handleNoteButton]);

  // -----------------------------------------------------------------------
  // Crop mode handlers
  // -----------------------------------------------------------------------

  /** Confirm crop → queue only (not visible in history), close or stay */
  const handleConfirm = useCallback(async (crop: CropResult, stayOpen: boolean) => {
    if (!screenshotUri || busyRef.current) return;
    busyRef.current = true;
    if (!stayOpen) setMode('saving');
    try {
      const croppedUri = await ScreenshotService.crop(screenshotUri, crop);
      await StagingService.stageToQueueOnly(croppedUri, { deleteSrcAfter: true });
      if (!stayOpen) PluginManager.closePluginView();
      // else: stay open, toast shown by CropOverlay
    } catch {
      if (!stayOpen) setMode('error');
    } finally {
      busyRef.current = false;
    }
  }, [screenshotUri]);

  /** Long Screenshot → save to stitch session + close plugin */
  const handleLongScreenshot = useCallback(async () => {
    if (!screenshotUri || busyRef.current) return;
    busyRef.current = true;
    setMode('saving');
    try {
      const rawPath = screenshotUri.replace('file://', '');
      const existingSession = await StitchSession.load();

      if (existingSession) {
        // Add to existing session
        await StitchSession.addImage(rawPath, originalSize.width, originalSize.height);
      } else {
        // Start new session
        await StitchSession.startSession(rawPath, originalSize.width, originalSize.height);
      }

      PluginManager.closePluginView();
    } catch {
      setMode('error');
    } finally {
      busyRef.current = false;
    }
  }, [screenshotUri, originalSize]);

  /** Add to History → crop region + save to history, close or stay */
  const handleAddToHistory = useCallback(async (crop: CropResult, stayOpen: boolean) => {
    if (!screenshotUri || busyRef.current) return;
    busyRef.current = true;
    if (!stayOpen) setMode('saving');
    try {
      const croppedUri = await ScreenshotService.crop(screenshotUri, crop);
      await StagingService.saveToHistoryOnly(croppedUri);
      if (!stayOpen) PluginManager.closePluginView();
    } catch {
      if (!stayOpen) setMode('error');
    } finally {
      busyRef.current = false;
    }
  }, [screenshotUri]);

  // -----------------------------------------------------------------------
  // Stitch editor handlers
  // -----------------------------------------------------------------------

  /** Stitch confirm → native composite → crop mode on result (NO exit during processing) */
  const handleStitchConfirm = useCallback(async (session: StitchSessionData) => {
    if (busyRef.current) return;
    busyRef.current = true;
    setIsCompositing(true);
    try {
      const nativeParams = JSON.stringify({
        direction: session.params.direction,
        overlap: session.params.overlap,
        topLayerIndex: session.params.topLayerIndex,
        images: session.images.map(img => ({
          path: img.path,
          width: img.width,
          height: img.height,
          crop: img.crop,
        })),
      });

      const compositePath: string = await ScreenshotModule.compositeImages(nativeParams);

      // Compute expected dimensions from session params (matches native canvas exactly)
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

      // Clear stitch session
      await StitchSession.clearSession();
      setHasStitchSession(false);
      setStitchSession(null);

      // Set data and switch mode atomically
      setScreenshotUri(`file://${compositePath}`);
      setOriginalSize({ width: compW, height: compH });
      setIsCompositing(false);
      setMode('cropping');
    } catch (e) {
      console.log('Stitch composite error:', e);
      setIsCompositing(false);
      setMode('error');
    } finally {
      busyRef.current = false;
    }
  }, []);

  /** Stitch cancel → clear session + reload normal crop */
  const handleStitchCancel = useCallback(async () => {
    await StitchSession.clearSession();
    setHasStitchSession(false);
    setStitchSession(null);
    loadScreenshot();
  }, [loadScreenshot]);

  /** History insert */
  const handleHistoryInsert = useCallback(async (path: string) => {
    if (PluginNoteAPI) PluginNoteAPI.insertImage(path);
    PluginManager.closePluginView();
  }, []);

  // -----------------------------------------------------------------------
  // Render
  // -----------------------------------------------------------------------

  const showCrop = (mode === 'cropping' || mode === 'saving' || mode === 'error')
    && screenshotUri && originalSize.width > 0;

  return (
    <View style={s.root}>
      <StatusBar hidden />

      {mode === 'history' && (
        <HistoryView
          onInsert={handleHistoryInsert}
          onClose={() => PluginManager.closePluginView()}
        />
      )}

      {mode === 'stitching' && stitchSession && (
        <StitchEditor
          session={stitchSession}
          onConfirm={handleStitchConfirm}
          onCancel={handleStitchCancel}
          disabled={isCompositing}
        />
      )}

      {showCrop && (
        <CropOverlay
          key={screenshotUri}
          imageUri={screenshotUri!}
          originalWidth={originalSize.width}
          originalHeight={originalSize.height}
          onConfirm={handleConfirm}
          onLongScreenshot={handleLongScreenshot}
          onAddToHistory={handleAddToHistory}
          onClose={() => PluginManager.closePluginView()}
          disabled={mode !== 'cropping'}
          hasStitchSession={hasStitchSession}
        />
      )}

      {mode === 'error' && (
        <View style={s.dialogOverlay}>
          <View style={s.dialog}>
            <Text style={s.dialogTitle}>Prompt</Text>
            <View style={s.dialogBody}>
              <Text style={s.dialogIcon}>&#9432;</Text>
              <Text style={s.dialogMsg}>Operation failed. Please try again.</Text>
            </View>
            <View style={s.dialogActions}>
              <Pressable onPress={() => PluginManager.closePluginView()} style={s.dialogBtnOutline}>
                <Text style={s.dialogBtnOutlineText}>Cancel</Text>
              </Pressable>
              <Pressable onPress={loadScreenshot} style={s.dialogBtnFilled}>
                <Text style={s.dialogBtnFilledText}>Retry</Text>
              </Pressable>
            </View>
          </View>
        </View>
      )}
    </View>
  );
}

const s = StyleSheet.create({
  root: { flex: 1, backgroundColor: 'transparent' },
  dialogOverlay: {
    ...StyleSheet.absoluteFillObject,
    justifyContent: 'center',
    alignItems: 'center',
    backgroundColor: 'rgba(0,0,0,0.15)',
  },
  dialog: {
    backgroundColor: '#fff',
    borderWidth: 1,
    borderColor: '#000',
    paddingTop: 12,
    paddingHorizontal: 24,
    paddingBottom: 20,
    minWidth: 440,
    maxWidth: 500,
  },
  dialogTitle: {
    fontSize: 19,
    fontWeight: 'normal',
    color: '#000',
    marginBottom: 10,
    marginLeft: 4,
    marginTop: 4,
  },
  dialogBody: {
    flexDirection: 'row',
    alignItems: 'center',
    marginBottom: 20,
    marginLeft: 2,
  },
  dialogIcon: {
    fontSize: 34,
    color: '#000',
    marginRight: 10,
  },
  dialogMsg: {
    fontSize: 20,
    color: '#000',
    flex: 1,
    lineHeight: 28,
  },
  dialogActions: {
    flexDirection: 'row',
    justifyContent: 'flex-end',
    gap: 12,
    marginBottom: 14,
    marginRight: 2,
  },
  dialogBtnOutline: {
    paddingVertical: 7,
    paddingHorizontal: 26,
    borderWidth: 1,
    borderColor: '#000',
    backgroundColor: '#fff',
  },
  dialogBtnOutlineText: {
    fontSize: 18,
    fontWeight: 'normal',
    color: '#000',
  },
  dialogBtnFilled: {
    paddingVertical: 7,
    paddingHorizontal: 26,
    borderWidth: 1,
    borderColor: '#000',
    backgroundColor: '#000',
  },
  dialogBtnFilledText: {
    fontSize: 18,
    fontWeight: 'normal',
    color: '#fff',
  },
});

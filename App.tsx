import React, { useState, useEffect, useCallback, useRef } from 'react';
import {
  View, StyleSheet, Text, Pressable, StatusBar,
  DeviceEventEmitter, NativeModules, AppState,
} from 'react-native';

import { CropOverlay, CropResult } from './components/CropOverlay';
import { HistoryView } from './components/HistoryView';
import { ScreenshotService } from './components/ScreenshotService';
import { StagingService } from './components/StagingService';
import { PluginManager, PluginNoteAPI, PluginCommAPI } from 'sn-plugin-lib';
import { checkPendingButton } from './index';

const { ScreenshotModule } = NativeModules;

type AppMode = 'cropping' | 'saving' | 'error' | 'history';

export default function App(): React.JSX.Element {
  const [mode, setMode] = useState<AppMode>('cropping');
  const [screenshotUri, setScreenshotUri] = useState<string | null>(null);
  const [originalSize, setOriginalSize] = useState({ width: 0, height: 0 });
  const [currentDocPath, setCurrentDocPath] = useState('');
  const busyRef = useRef(false);

  const loadScreenshot = useCallback(async () => {
    if (busyRef.current) return;
    busyRef.current = true;
    setMode('cropping');
    setScreenshotUri(null);
    setOriginalSize({ width: 0, height: 0 });
    try {
      const nativePath: string | null = await ScreenshotModule.getPendingPath();
      const dims = await ScreenshotService.getDeviceDimensions();
      if (nativePath) {
        setScreenshotUri(`file://${nativePath}`);
      } else {
        setScreenshotUri(await ScreenshotService.capture());
      }
      setOriginalSize(dims);
    } finally {
      busyRef.current = false;
    }
  }, []);

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

  // Confirm crop → queue + history + close
  const handleConfirm = useCallback(async (crop: CropResult) => {
    if (!screenshotUri || busyRef.current) return;
    busyRef.current = true;
    setMode('saving');
    try {
      const croppedUri = await ScreenshotService.crop(screenshotUri, crop);
      await StagingService.stage(croppedUri, { deleteSrcAfter: true });
      PluginManager.closePluginView();
    } catch {
      setMode('error');
    } finally {
      busyRef.current = false;
    }
  }, [screenshotUri]);

  // Full Screen → queue + history + close (no crop)
  const handleFullScreen = useCallback(async () => {
    if (!screenshotUri || busyRef.current) return;
    busyRef.current = true;
    setMode('saving');
    try {
      await StagingService.stage(screenshotUri);
      PluginManager.closePluginView();
    } catch {
      setMode('error');
    } finally {
      busyRef.current = false;
    }
  }, [screenshotUri]);

  // Add to History → history only + close
  const handleAddToHistory = useCallback(async () => {
    if (!screenshotUri || busyRef.current) return;
    busyRef.current = true;
    setMode('saving');
    try {
      await StagingService.saveToHistoryOnly(screenshotUri);
      PluginManager.closePluginView();
    } catch {
      setMode('error');
    } finally {
      busyRef.current = false;
    }
  }, [screenshotUri]);

  // History insert: direct insert from history path
  const handleHistoryInsert = useCallback(async (path: string) => {
    if (PluginNoteAPI) PluginNoteAPI.insertImage(path);
    PluginManager.closePluginView();
  }, []);

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

      {showCrop && (
        <CropOverlay
          imageUri={screenshotUri!}
          originalWidth={originalSize.width}
          originalHeight={originalSize.height}
          onConfirm={handleConfirm}
          onFullScreen={handleFullScreen}
          onAddToHistory={handleAddToHistory}
          onClose={() => PluginManager.closePluginView()}
          disabled={mode !== 'cropping'}
        />
      )}

      {mode === 'error' && (
        <View style={s.dialogOverlay}>
          <View style={s.dialog}>
            <Text style={s.dialogTitle}>Prompt</Text>
            <View style={s.dialogBody}>
              <Text style={s.dialogIcon}>&#9432;</Text>
              <Text style={s.dialogMsg}>Screenshot save failed. Please try again.</Text>
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

import { AppRegistry, Image, DeviceEventEmitter } from 'react-native';
import App from './App';
import { name as appName } from './app.json';
import { PluginManager } from 'sn-plugin-lib';
import { ensureInit } from './components/BackgroundService';
import { setPendingButton, isAppMounted } from './pendingButton';
import { warmupCache, getCachedConfig, getCachedClips, injectClipStatus, loadClips } from './components/ToolPresets';
import FloatingToolbarBridge from './components/FloatingToolbarBridge';
import { executeAction } from './components/ToolActions';
import { setLocale } from './components/i18n';
import { PenLasso } from './components/PenTools';
import * as StrokeEraser from './components/StrokeEraser';

AppRegistry.registerComponent(appName, () => App);

PluginManager.init();
ensureInit();
warmupCache();

PluginManager.registerLangListener({
  onMsg(msg) {
    const lang = msg.lang || '';
    const locale = lang.toLowerCase().startsWith('zh') ? 'zh' : 'en';
    setLocale(locale);
  },
});

FloatingToolbarBridge.onTitlePenLassoAction(() => {
  console.log('[index]: onTitlePenLassoAction → arm pen lasso');
  PenLasso.arm().catch(e => console.error('[index]: PenLasso.arm error:', e));
});

FloatingToolbarBridge.onPenLockRequest(() => {
  console.log('[index]: onPenLockRequest → arm stroke eraser after pen lock');
  if (!isAppMounted()) {
    FloatingToolbarBridge.setPendingScreen('penLock');
    FloatingToolbarBridge.openPenLockView();
  }

  setTimeout(() => {
    StrokeEraser.arm().catch(e => console.error('[index]: StrokeEraser.arm error:', e));
  }, 300);
});

FloatingToolbarBridge.onPenLockRelease(() => {
  StrokeEraser.disarm();
  FloatingToolbarBridge.dismissStrokeEraserOverlay();
});

FloatingToolbarBridge.onToolTap(async ({ toolAction }) => {
  console.log('[index]: onToolTap action=', toolAction);

  if (toolAction === 'lasso_send') {
    FloatingToolbarBridge.openPanel('nativeSendHelper');
    return;
  }

  const result = await executeAction(toolAction);
  console.log('[index]: executeAction result=', result);

  if (typeof result === 'string' &&
      (result.startsWith('Saved to clip') || (result.startsWith('Clip') && result.endsWith('cleared')))) {
    const newClips = await loadClips();
    const config = getCachedConfig();
    if (config) {
      FloatingToolbarBridge.updateTools(injectClipStatus(config.tools, newClips, null));
    }
  }
});

FloatingToolbarBridge.onToolLongPress(async ({ toolId }) => {
  if (toolId.startsWith('clip_')) {
    const slot = toolId.split('_')[1];
    await executeAction(`clip_clear_${slot}`);
    const newClips = await loadClips();
    const config = getCachedConfig();
    if (config) {
      FloatingToolbarBridge.updateTools(injectClipStatus(config.tools, newClips, null));
    }
  }
});

let lastCaptureTime = 0;
const CAPTURE_DEBOUNCE = 3000;

PluginManager.registerButtonListener({
  onButtonPress(event) {
    console.log('[index]: button id=', event.id);

    if (event.id === 300) {
      const now = Date.now();
      if (now - lastCaptureTime < CAPTURE_DEBOUNCE) return;
      lastCaptureTime = now;
      const { NativeModules } = require('react-native');
      const { ScreenshotModule } = NativeModules;
      if (ScreenshotModule) {
        ScreenshotModule.captureAndReopen(3000).catch(() => {});
      }
      setPendingButton(event.id);
      DeviceEventEmitter.emit('quickToolbarButton', { id: event.id });
      return;
    }

    if (event.id === 100) {

      if (FloatingToolbarBridge.isShowingSync()) {
        console.log('[index]: toolbar visible → destroyAll');
        FloatingToolbarBridge.destroyAll();
        return;
      }

      setPendingButton(event.id);
      const config = getCachedConfig();
      const clips = getCachedClips();
      console.log('[index]: id=100 show path, config=', !!config, 'clips=', !!clips, 'FloatingToolbar=', !!require('react-native').NativeModules.FloatingToolbar);
      if (config && clips) {
        FloatingToolbarBridge.show(injectClipStatus(config.tools, clips, null));
      } else {
        const { getAvailableTools } = require('./components/ToolPresets');
        const defaultClips = { '1': null, '2': null, '3': null, '4': null, '5': null, '6': null };
        console.log('[index]: using default tools, count=', getAvailableTools().slice(0, 8).length);
        FloatingToolbarBridge.show(injectClipStatus(getAvailableTools().slice(0, 8), defaultClips, null));
        warmupCache().then(() => {
          const c = getCachedConfig();
          const cl = getCachedClips();
          if (!c || !cl) return;
          FloatingToolbarBridge.updateTools(injectClipStatus(c.tools, cl, null));
        });
      }
      return;
    }
    setPendingButton(event.id);
    DeviceEventEmitter.emit('quickToolbarButton', { id: event.id });
  },
});

PluginManager.registerButton(1, ['NOTE'], {
  id: 100,
  name: 'Inkling',
  icon: Image.resolveAssetSource(require('./assets/toolbar_icon.png')).uri,
  showType: 0,
});

PluginManager.registerButton(1, ['DOC'], {
  id: 300,
  name: JSON.stringify({ en: 'Screenshot Crop', zh_CN: '截图裁切' }),
  icon: Image.resolveAssetSource(require('./assets/toolbar_icon.png')).uri,
  showType: 1,
});

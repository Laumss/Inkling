

import { AppRegistry, Image, DeviceEventEmitter } from 'react-native';
import App from './App';
import { name as appName } from './app.json';
import { PluginManager, NativeUIUtils } from 'sn-plugin-lib';
import { ensureInit, stopAllModes, startLocalSend, stopLocalSend, isLocalSendRunning } from './components/BackgroundService';
import { setPendingButton, isAppMounted } from './pendingButton';
import { warmupCache, getCachedConfig, getCachedClips, injectClipStatus, loadClips } from './components/ToolPresets';
import FloatingToolbarBridge from './components/FloatingToolbarBridge';
import LocalSendBridge from './components/LocalSendBridge';
import { executeAction } from './components/ToolActions';
import { setLocale, t } from './components/i18n';
import { PenLasso } from './components/PenTools';

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

const localSendButtonIcon = Image.resolveAssetSource(require('./assets/toolbar_icon.png')).uri;

function registerLocalSendButton() {
  const running = isLocalSendRunning();
  const name = running ? t('localsend_btn_on') : t('localsend_btn_off');
  PluginManager.registerButton(1, ['NOTE'], {
    id: 200,
    name: JSON.stringify({ en: name, zh_CN: name }),
    icon: localSendButtonIcon,
    showType: 0,
  });
}

DeviceEventEmitter.addListener('localSendStateChanged', ({ running }) => {
  console.log('[index]: localSendStateChanged running=', running);
  registerLocalSendButton();
});

DeviceEventEmitter.addListener('startLocalSendFromNative', async () => {
  console.log('[index]: startLocalSendFromNative');
  await startLocalSend();
});

DeviceEventEmitter.addListener('showTip', ({ key }) => {
  NativeUIUtils.showErrorTipDialog(t(key));
});

DeviceEventEmitter.addListener('showClipboardSyncConfirm', async ({ senderAlias }) => {
  try {
    const msg = t('sync_clipboard_ask').replace('%s', senderAlias);
    const confirmed = await NativeUIUtils.showRattaDialog(msg, t('btn_cancel'), t('btn_confirm'), false);
    LocalSendBridge.respondClipboardSync(confirmed);
  } catch (e) {
    console.error('[index]: showClipboardSyncConfirm error:', e);
    LocalSendBridge.respondClipboardSync(true);
  }
});

DeviceEventEmitter.addListener('showConfirmStartLocalSend', async () => {
  try {
    const confirmed = await NativeUIUtils.showRattaDialog(
      t('localsend_ask_enable'), t('btn_cancel'), t('btn_confirm'), false
    );
    if (confirmed) {
      await startLocalSend();
      FloatingToolbarBridge.openSendPanelClipboardSync();
    }
  } catch (e) {
    console.error('[index]: showConfirmStartLocalSend error:', e);
  }
});

DeviceEventEmitter.addListener('clipboardChanged', async () => {
  console.log('[index]: clipboardChanged → refreshing toolbar');
  const newClips = await loadClips();
  const config = getCachedConfig();
  if (config && FloatingToolbarBridge.isShowingSync()) {
    FloatingToolbarBridge.updateTools(injectClipStatus(config.tools, newClips, null));
  }
});

FloatingToolbarBridge.onTitlePenLassoAction(() => {
  console.log('[index]: onTitlePenLassoAction → arm pen lasso');
  PenLasso.arm().catch(e => console.error('[index]: PenLasso.arm error:', e));
});

FloatingToolbarBridge.onDestroyAll(() => {
  console.log('[index]: onDestroyAll → stopAllModes');
  stopAllModes();
});

FloatingToolbarBridge.onPenLockRequest(() => {
  console.log('[index]: onPenLockRequest');
  if (!isAppMounted()) {
    FloatingToolbarBridge.setPendingScreen('penLock');
    FloatingToolbarBridge.openPenLockView();
  }
});

FloatingToolbarBridge.onPenLockRelease(() => {
  console.log('[index]: onPenLockRelease');
});

FloatingToolbarBridge.onToolTap(async ({ toolAction }) => {
  console.log('[index]: onToolTap action=', toolAction);

  if (toolAction === 'lasso_send') {
    FloatingToolbarBridge.openPanel('nativeSendHelper');
    return;
  }

  if (isAppMounted()) return;

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
      FloatingToolbarBridge.handleDocScreenshotCrop();
      return;
    }

    if (event.id === 200) {
      (async () => {
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
          console.error('[index]: LocalSend toggle error:', e);
        }
      })();
      return;
    }

    if (event.id === 100) {

      if (FloatingToolbarBridge.isShowingSync()) {
        console.log('[index]: toolbar visible → destroyAll');

        stopAllModes();
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

registerLocalSendButton();

PluginManager.registerButton(1, ['DOC'], {
  id: 300,
  name: JSON.stringify({ en: 'Screenshot Crop', zh_CN: '截图裁切' }),
  icon: Image.resolveAssetSource(require('./assets/toolbar_icon.png')).uri,
  showType: 0,
});


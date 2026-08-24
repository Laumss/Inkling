

import { AppRegistry, Image, DeviceEventEmitter } from 'react-native';
import App from './App';
import { name as appName } from './app.json';
import { PluginManager, NativeUIUtils, FileUtils } from 'sn-plugin-lib';
import { ensureInit, stopAllModes, startLocalSend, stopLocalSend, isLocalSendRunning } from './components/BackgroundService';
import { PluginCommAPI } from 'sn-plugin-lib';
import { setPendingButton, isAppMounted, markHostButtonEvent } from './pendingButton';
import { loadClips } from './components/ToolPresets';
import FloatingToolbarBridge, { ENABLE_DEBUG } from './components/FloatingToolbarBridge';
import LocalSendBridge from './components/LocalSendBridge';
import { executeAction } from './components/ToolActions';
import { setLocale, getLocale, t } from './components/i18n';
import { ensureInitialPermissions } from './components/PermissionCoordinator';

if (!ENABLE_DEBUG) {
  console.log = () => {};
  console.warn = () => {};
  console.info = () => {};
  console.debug = () => {};
}

AppRegistry.registerComponent(appName, () => App);

PluginManager.init();
setLocale(getLocale());
ensureInit();
if (ENABLE_DEBUG) {
  const names = Object.keys(require('react-native').NativeModules || {});
  console.log('[index] FloatingToolbar available=', FloatingToolbarBridge.isAvailable, 'nativeModules=', names.join(','));
}

DeviceEventEmitter.addListener('plugin_button_event', (msg) => {
  markHostButtonEvent();
  FloatingToolbarBridge.reportHostButtonChannel(true);
  FloatingToolbarBridge.reportHostButtonRaw('probe ' + JSON.stringify(msg));
  console.log('[index] raw plugin_button_event:', JSON.stringify(msg));
});
async function refreshTitleClips() {
  try {
    const clips = await loadClips();
    const filled = [1, 2, 3, 4, 5, 6].map(n => !!clips[String(n)]);
    FloatingToolbarBridge.updateTitleClips(filled);
  } catch (e) {
    console.warn('[index]: refreshTitleClips failed:', e);
  }
}

PluginManager.registerLangListener({
  onMsg(msg) {
    const lang = msg.lang || '';
    const locale = lang.toLowerCase().startsWith('zh') ? 'zh' : 'en';
    setLocale(locale);
  },
});

const localSendButtonIcon = Image.resolveAssetSource(require('./assets/toolbar_icon.png')).uri;

// Button name is fixed; don't encode toggle state in it. The host persists
// registrations in its DB sorted by recentUsedTime, so a mutable name leaves
// stale labels after restart. Toggle state is shown via post-tap tip instead.
function registerLocalSendButton() {
  PluginManager.registerButton(1, ['NOTE'], {
    id: 200,
    name: JSON.stringify({ en: 'LocalSend', zh_CN: 'LocalSend' }),
    icon: localSendButtonIcon,
    showType: 0,
  });
}

DeviceEventEmitter.addListener('nativeDeleteFile', async (evt) => {
  if (evt && evt.path) {
    try {
      await FileUtils.deleteFile(evt.path);
    } catch (e) {
      console.warn('[index]: nativeDeleteFile failed:', e);
    }
  }
});

DeviceEventEmitter.addListener('startLocalSendFromNative', async (evt) => {
  console.log('[index]: startLocalSendFromNative');
  const ok = await startLocalSend();
  if (ok && evt && evt.openClipboardSync) {
    FloatingToolbarBridge.openSendPanelClipboardSync();
  }
});

DeviceEventEmitter.addListener('clipsChanged', async () => {
  console.log('[index]: clipsChanged (from native sync) → refresh title clips');
  await refreshTitleClips();
});

FloatingToolbarBridge.onDestroyAll(() => {
  console.log('[index]: onDestroyAll → stopAllModes');
  stopAllModes();
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
    await refreshTitleClips();
  }
});

FloatingToolbarBridge.onToolLongPress(async ({ toolId }) => {
  if (toolId.startsWith('clip_')) {
    const slot = toolId.split('_')[1];
    await executeAction(`clip_clear_${slot}`);
    await refreshTitleClips();
  }
});

PluginManager.registerButtonListener({
  async onButtonPress(event) {
    FloatingToolbarBridge.reportHostButtonRaw('listener id=' + (event && event.id));
    console.log('[index]: button id=', event.id);

    if (event.id === 100 || event.id === 200 || event.id === 300) {
      setPendingButton(event.id);
      const ready = await ensureInitialPermissions();
      if (!ready) {
        setPendingButton(null);
        try { PluginManager.closePluginView(); } catch (_) {}
        return;
      }
    }

    if (event.id === 300) {
      FloatingToolbarBridge.toggleScreenshotBubble();
      setTimeout(() => {
        try { PluginManager.closePluginView(); } catch (_) {}
      }, 100);
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
        } finally {
          setTimeout(() => {
            try { PluginManager.closePluginView(); } catch (_) {}
          }, 100);
        }
      })();
      return;
    }

    if (event.id === 100) {
      console.log('[index]: id=100 → toggle native toolbar available=', FloatingToolbarBridge.isAvailable, 'showing=', FloatingToolbarBridge.isShowingSync());
      stopAllModes();
      FloatingToolbarBridge.toggleFromPluginButton();
      refreshTitleClips();
      return;
    }
  },
});

PluginManager.registerButton(1, ['NOTE'], {
  id: 100,
  name: 'Inkling',
  icon: Image.resolveAssetSource(require('./assets/toolbar_icon.png')).uri,
  showType: 0,
});

registerLocalSendButton();

// On startup, reconcile with native server: if the previous JS context's server
// is still running (defensive; normally stopped in onCatalystInstanceDestroy),
// adopt it to sync the _localSendStarted flag.
LocalSendBridge.getServerStatus().then(st => {
  if (st && st.running) {
    console.log('[index]: adopting already-running LocalSend server');
    startLocalSend();
  }
}).catch(() => {});

PluginManager.registerButton(1, ['DOC'], {
  id: 300,
  name: JSON.stringify({ en: 'Screenshot Crop', zh_CN: '截图裁切' }),
  icon: Image.resolveAssetSource(require('./assets/toolbar_icon.png')).uri,
  showType: 0,
});

if (ENABLE_DEBUG) {
  PluginManager.registerConfigButton();
  PluginManager.registerConfigButtonListener({
    onClick() {
      console.log('[index]: config button clicked → opening main panel');
      setPendingButton(999);
      DeviceEventEmitter.emit('quickToolbarButton', { id: 999 });
    },
  });
}

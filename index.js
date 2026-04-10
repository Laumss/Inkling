/**
 * Screenshot Crop Plugin — Entry Point
 *
 * DOC button (id:100, showType:1): close → screencap → reopen
 * NOTE button (id:200, showType:0): insert staged image or open history
 *
 * @format
 */

import { AppRegistry, Image, NativeModules, DeviceEventEmitter } from 'react-native';
import App from './App';
import { name as appName } from './app.json';
import { PluginManager } from 'sn-plugin-lib';

const { ScreenshotModule } = NativeModules;

let pendingButtonId = null;
let lastCaptureTime = 0;
const CAPTURE_DEBOUNCE = 3000;

AppRegistry.registerComponent(appName, () => App);

PluginManager.init();

PluginManager.registerButtonListener({
  onButtonPress(event) {
    if (event.id === 100) {
      const now = Date.now();
      if (now - lastCaptureTime < CAPTURE_DEBOUNCE) return;
      lastCaptureTime = now;
      ScreenshotModule.captureAndReopen(3000).catch(() => {});
    }

    pendingButtonId = event.id;
    DeviceEventEmitter.emit('screenshotCropButton', { id: event.id });
  },
});

export const checkPendingButton = () => {
  const val = pendingButtonId;
  pendingButtonId = null;
  return val;
};

// DOC context: screenshot crop
PluginManager.registerButton(1, ['DOC'], {
  id: 100,
  name: 'Screenshot Crop',
  icon: Image.resolveAssetSource(require('./assets/screenshot_crop.png')).uri,
  showType: 1,
});

// NOTE context: insert staged or open history
PluginManager.registerButton(1, ['NOTE'], {
  id: 200,
  name: 'Insert Screenshot',
  icon: Image.resolveAssetSource(require('./assets/screenshot_crop.png')).uri,
  showType: 1,
});

import { Image, NativeModules, Dimensions } from 'react-native';
import RNFS from 'react-native-fs';
import ImageEditor from '@react-native-community/image-editor';
import { PluginManager } from 'sn-plugin-lib';

const { ScreenshotModule } = NativeModules;

export interface CropRegion {
  offsetX: number;
  offsetY: number;
  width: number;
  height: number;
}

export interface ImageSize {
  width: number;
  height: number;
}

export interface DeviceDimensions {
  width: number;
  height: number;
}

const SCREENSHOT_DIRS = [
  '/sdcard/SCREENSHOT',
  '/sdcard/EXPORT',
  '/sdcard/INBOX'
];

export const ScreenshotService = {

  async getDeviceDimensions(): Promise<DeviceDimensions> {
    const screen = Dimensions.get('window');
    const landscape = screen.width > screen.height;

    try {
      const dt = await PluginManager.getDeviceType();

      if (dt === 5) {
        return landscape ? { width: 2560, height: 1920 } : { width: 1920, height: 2560 };
      }
    } catch (_) {}

    return landscape ? { width: 1872, height: 1404 } : { width: 1404, height: 1872 };
  },

  async capture(): Promise<string> {

    try {
      if (ScreenshotModule) {
        const outPath = await ScreenshotModule.takeScreenshot();
        return `file://${outPath}`;
      }
    } catch (_) {}

    type Entry = { path: string; mtime: number };
    const all: Entry[] = [];

    for (const dir of SCREENSHOT_DIRS) {
      try {
        if (!(await RNFS.exists(dir))) continue;
        const files = await RNFS.readDir(dir);
        for (const f of files) {
          if (f.isDirectory()) continue;
          if (!/\.(png|jpg|jpeg|bmp)$/i.test(f.name)) continue;
          all.push({
            path: f.path,
            mtime: f.mtime ? new Date(f.mtime).getTime() : 0,
          });
        }
      } catch (_) { continue; }
    }

    all.sort((a, b) => b.mtime - a.mtime);

    if (all.length > 0) {
      return `file://${all[0].path}`;
    }

    throw new Error(
      'Could not capture document screenshot.\n\n' +
      'Please press Power + Volume Down to take a screenshot manually,\n' +
      'then reopen the plugin.'
    );
  },

  async pickLatest(): Promise<string> {
    type Entry = { path: string; mtime: number };
    const all: Entry[] = [];

    for (const dir of SCREENSHOT_DIRS) {
      try {
        if (!(await RNFS.exists(dir))) continue;
        const files = await RNFS.readDir(dir);
        for (const f of files) {
          if (f.isDirectory()) continue;
          if (!/\.(png|jpg|jpeg|bmp)$/i.test(f.name)) continue;
          all.push({
            path: f.path,
            mtime: f.mtime ? new Date(f.mtime).getTime() : 0,
          });
        }
      } catch (_) { continue; }
    }

    all.sort((a, b) => b.mtime - a.mtime);

    if (all.length === 0) {
      throw new Error(
        'No screenshot files found. Please take a screenshot manually (Power + Volume Down) first.'
      );
    }

    return `file://${all[0].path}`;
  },

  getImageSize(uri: string): Promise<ImageSize> {
    return new Promise((resolve, reject) => {
      Image.getSize(
        uri,
        (width, height) => resolve({ width, height }),
        (err) => reject(err || new Error('Failed to read image dimensions')),
      );
    });
  },

  async crop(sourceUri: string, region: CropRegion): Promise<string> {
    const result = await ImageEditor.cropImage(sourceUri, {
      offset: { x: region.offsetX, y: region.offsetY },
      size: { width: region.width, height: region.height },
    });
    return result.uri;
  },
};

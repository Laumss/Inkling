/**
 * ScreenshotService — Reusable screenshot capture & processing service.
 *
 * Provides a clean API for:
 *   1. Capturing the current document page via Supernote SDK
 *   2. Getting the real image dimensions
 *   3. Cropping a region from the captured image
 *
 * Primary strategy: native screencap command (captures e-ink display)
 * Fallback: find the most recent screenshot file on disk
 */

import { Image, NativeModules, Dimensions } from 'react-native';
import RNFS from 'react-native-fs';
import ImageEditor from '@react-native-community/image-editor';
import { PluginManager } from 'sn-plugin-lib';

const { ScreenshotModule } = NativeModules;

// ---------------------------------------------------------------------------
// Types
// ---------------------------------------------------------------------------

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

// ---------------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------------

const SCREENSHOT_DIRS = [
  '/sdcard/SCREENSHOT',
  '/sdcard/EXPORT',
  '/sdcard/INBOX'
];

// ---------------------------------------------------------------------------
// ScreenshotService
// ---------------------------------------------------------------------------

export const ScreenshotService = {
  /**
   * Detect the real device dimensions based on Supernote device type.
   * Returns { width, height } in pixels.
   */
  async getDeviceDimensions(): Promise<DeviceDimensions> {
    const screen = Dimensions.get('window');
    const landscape = screen.width > screen.height;

    try {
      const dt = await PluginManager.getDeviceType();
      // deviceType 5 = Manta (A5X2 / larger screen)
      if (dt === 5) {
        return landscape ? { width: 2560, height: 1920 } : { width: 1920, height: 2560 };
      }
    } catch (_) {}
    // Default: A5X / A6X2 standard resolution
    return landscape ? { width: 1872, height: 1404 } : { width: 1404, height: 1872 };
  },

  /**
   * Capture the current document page. Strategies:
   *
   * 1. Native screencap command (system-level screenshot, captures e-ink display)
   * 2. Most recent screenshot file on disk (fallback)
   */
  async capture(): Promise<string> {
    // Strategy 1: Native screencap via ScreenshotModule
    try {
      if (ScreenshotModule) {
        const outPath = await ScreenshotModule.takeScreenshot();
        return `file://${outPath}`;
      }
    } catch (_) {}

    // Strategy 2: Most recent screenshot file (any age)
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

  /**
   * Pick the most recent existing screenshot file (without capturing).
   */
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

  /**
   * Get the real pixel dimensions of an image at the given URI.
   */
  getImageSize(uri: string): Promise<ImageSize> {
    return new Promise((resolve, reject) => {
      Image.getSize(
        uri,
        (width, height) => resolve({ width, height }),
        (err) => reject(err || new Error('Failed to read image dimensions')),
      );
    });
  },

  /**
   * Crop a region from the source image and return the URI of the cropped result.
   */
  async crop(sourceUri: string, region: CropRegion): Promise<string> {
    const result = await ImageEditor.cropImage(sourceUri, {
      offset: { x: region.offsetX, y: region.offsetY },
      size: { width: region.width, height: region.height },
    });
    return result.uri;
  },
};

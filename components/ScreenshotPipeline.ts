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
          all.push({ path: f.path, mtime: f.mtime ? new Date(f.mtime).getTime() : 0 });
        }
      } catch (_) { continue; }
    }
    all.sort((a, b) => b.mtime - a.mtime);
    if (all.length > 0) return `file://${all[0].path}`;
    throw new Error('Could not capture document screenshot.\n\nPlease press Power + Volume Down to take a screenshot manually,\nthen reopen the plugin.');
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
          all.push({ path: f.path, mtime: f.mtime ? new Date(f.mtime).getTime() : 0 });
        }
      } catch (_) { continue; }
    }
    all.sort((a, b) => b.mtime - a.mtime);
    if (all.length === 0) throw new Error('No screenshot files found. Please take a screenshot manually (Power + Volume Down) first.');
    return `file://${all[0].path}`;
  },

  getImageSize(uri: string): Promise<ImageSize> {
    return new Promise((resolve, reject) => {
      Image.getSize(uri, (width, height) => resolve({ width, height }), (err) => reject(err || new Error('Failed to read image dimensions')));
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

const STAGING_DIR = '/sdcard/SCREENSHOT/.plugin_staging';
const QUEUE_DIR   = `${STAGING_DIR}/queue`;
const HISTORY_DIR = '/sdcard/SCREENSHOT/.plugin_history';
const MAX_HISTORY = 20;

export interface HistoryEntry {
  path: string;
  timestamp: number;
}

export const StagingService = {
  async ensureDir(): Promise<void> {
    for (const d of [STAGING_DIR, QUEUE_DIR, HISTORY_DIR]) {
      if (!(await RNFS.exists(d))) await RNFS.mkdir(d);
    }
  },

  async stageToQueueOnly(sourceUri: string, opts?: { deleteSrcAfter?: boolean }): Promise<boolean> {
    try {
      await StagingService.ensureDir();
      const src = sourceUri.replace('file://', '');
      const ts = Date.now();
      await RNFS.copyFile(src, `${QUEUE_DIR}/${ts}.png`);
      if (opts?.deleteSrcAfter) { try { await RNFS.unlink(src); } catch (_) {} }
      return true;
    } catch (_) { return false; }
  },

  async stage(sourceUri: string, opts?: { deleteSrcAfter?: boolean }): Promise<boolean> {
    try {
      await StagingService.ensureDir();
      const src = sourceUri.replace('file://', '');
      const ts = Date.now();
      await RNFS.copyFile(src, `${QUEUE_DIR}/${ts}.png`);
      await RNFS.copyFile(src, `${HISTORY_DIR}/${ts}.png`);
      await StagingService.pruneHistory();
      if (opts?.deleteSrcAfter) { try { await RNFS.unlink(src); } catch (_) {} }
      return true;
    } catch (_) { return false; }
  },

  async saveToHistoryOnly(sourceUri: string): Promise<boolean> {
    try {
      await StagingService.ensureDir();
      const src = sourceUri.replace('file://', '');
      await RNFS.copyFile(src, `${HISTORY_DIR}/${Date.now()}.png`);
      await StagingService.pruneHistory();
      return true;
    } catch (_) { return false; }
  },

  async getNextQueued(): Promise<string | null> {
    try {
      await StagingService.ensureDir();
      const files = await RNFS.readDir(QUEUE_DIR);
      const pngs = files
        .filter(f => f.name.endsWith('.png'))
        .sort((a, b) => parseInt(a.name) - parseInt(b.name));
      return pngs.length > 0 ? pngs[0].path : null;
    } catch (_) { return null; }
  },

  async dequeue(): Promise<void> {
    const path = await StagingService.getNextQueued();
    if (path) { try { await RNFS.unlink(path); } catch (_) {} }
  },

  async pruneHistory(): Promise<void> {
    try {
      const files = await RNFS.readDir(HISTORY_DIR);
      const pngs = files.filter(f => f.name.endsWith('.png')).sort((a, b) => parseInt(a.name) - parseInt(b.name));
      if (pngs.length > MAX_HISTORY) {
        for (const f of pngs.slice(0, pngs.length - MAX_HISTORY)) await RNFS.unlink(f.path);
      }
    } catch (_) {}
  },

  async getHistory(): Promise<HistoryEntry[]> {
    try {
      await StagingService.ensureDir();
      const files = await RNFS.readDir(HISTORY_DIR);
      return files
        .filter(f => f.name.endsWith('.png'))
        .map(f => ({ path: f.path, timestamp: parseInt(f.name.replace('.png', ''), 10) || 0 }))
        .sort((a, b) => b.timestamp - a.timestamp);
    } catch (_) { return []; }
  },

  async clearHistory(): Promise<void> {
    try {
      const files = await RNFS.readDir(HISTORY_DIR);
      for (const f of files) { if (f.name.endsWith('.png')) await RNFS.unlink(f.path); }
    } catch (_) {}
  },
};

const SESSION_FILE = `${STAGING_DIR}/stitch_session.json`;
const STITCH_IMAGES_DIR = `${STAGING_DIR}/stitch_images`;

export interface ImageCrop {
  cropTop: number;
  cropBottom: number;
  cropLeft: number;
  cropRight: number;
}

export interface StitchImage {
  path: string;
  width: number;
  height: number;
  crop: ImageCrop;
}

export interface StitchParams {
  direction: 'vertical' | 'horizontal';
  overlap: number;
  topLayerIndex: number;
}

export interface StitchSessionData {
  images: StitchImage[];
  params: StitchParams;
  createdAt: number;
}

const DEFAULT_PARAMS: StitchParams = { direction: 'vertical', overlap: 100, topLayerIndex: 1 };
const DEFAULT_CROP: ImageCrop = { cropTop: 0, cropBottom: 0, cropLeft: 0, cropRight: 0 };

export const StitchSession = {
  async ensureDir(): Promise<void> {
    for (const d of [STAGING_DIR, STITCH_IMAGES_DIR]) {
      if (!(await RNFS.exists(d))) await RNFS.mkdir(d);
    }
  },

  async hasActiveSession(): Promise<boolean> {
    try { return await RNFS.exists(SESSION_FILE); } catch (_) { return false; }
  },

  async load(): Promise<StitchSessionData | null> {
    try {
      if (!(await RNFS.exists(SESSION_FILE))) return null;
      const json = await RNFS.readFile(SESSION_FILE, 'utf8');
      const data = JSON.parse(json) as StitchSessionData;
      for (const img of data.images) { if (!(await RNFS.exists(img.path))) return null; }
      return data;
    } catch (_) { return null; }
  },

  async startSession(imagePath: string, width: number, height: number): Promise<StitchSessionData> {
    await StitchSession.ensureDir();
    const ts = Date.now();
    const destPath = `${STITCH_IMAGES_DIR}/${ts}_0.png`;
    await RNFS.copyFile(imagePath.replace('file://', ''), destPath);
    const session: StitchSessionData = {
      images: [{ path: destPath, width, height, crop: { ...DEFAULT_CROP } }],
      params: { ...DEFAULT_PARAMS },
      createdAt: ts,
    };
    await RNFS.writeFile(SESSION_FILE, JSON.stringify(session), 'utf8');
    return session;
  },

  async addImage(imagePath: string, width: number, height: number): Promise<StitchSessionData | null> {
    const session = await StitchSession.load();
    if (!session) return null;
    await StitchSession.ensureDir();
    if (session.images.length >= 2) {
      try { await RNFS.unlink(session.images[1].path); } catch (_) {}
      session.images.splice(1);
    }
    const ts = Date.now();
    const destPath = `${STITCH_IMAGES_DIR}/${ts}_1.png`;
    await RNFS.copyFile(imagePath.replace('file://', ''), destPath);
    session.images.push({ path: destPath, width, height, crop: { ...DEFAULT_CROP } });
    session.params = { ...DEFAULT_PARAMS };
    await RNFS.writeFile(SESSION_FILE, JSON.stringify(session), 'utf8');
    return session;
  },

  async keepFirstOnly(): Promise<void> {
    const session = await StitchSession.load();
    if (!session || session.images.length < 2) return;
    try { await RNFS.unlink(session.images[1].path); } catch (_) {}
    session.images.splice(1);
    session.params = { ...DEFAULT_PARAMS };
    await RNFS.writeFile(SESSION_FILE, JSON.stringify(session), 'utf8');
  },

  async updateParams(params: Partial<StitchParams>): Promise<void> {
    const session = await StitchSession.load();
    if (!session) return;
    session.params = { ...session.params, ...params };
    await RNFS.writeFile(SESSION_FILE, JSON.stringify(session), 'utf8');
  },

  async updateImageCrop(index: number, crop: Partial<ImageCrop>): Promise<void> {
    const session = await StitchSession.load();
    if (!session || index >= session.images.length) return;
    session.images[index].crop = { ...session.images[index].crop, ...crop };
    await RNFS.writeFile(SESSION_FILE, JSON.stringify(session), 'utf8');
  },

  async save(session: StitchSessionData): Promise<void> {
    await StitchSession.ensureDir();
    await RNFS.writeFile(SESSION_FILE, JSON.stringify(session), 'utf8');
  },

  async clearSession(): Promise<void> {
    try {
      if (await RNFS.exists(SESSION_FILE)) await RNFS.unlink(SESSION_FILE);
      if (await RNFS.exists(STITCH_IMAGES_DIR)) {
        const files = await RNFS.readDir(STITCH_IMAGES_DIR);
        for (const f of files) { try { await RNFS.unlink(f.path); } catch (_) {} }
      }
    } catch (_) {}
  },
};

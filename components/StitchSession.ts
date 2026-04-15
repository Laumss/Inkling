/**
 * StitchSession — Persistent long-screenshot session.
 *
 * Stores image paths and stitch parameters across plugin restarts.
 * Session file: /sdcard/SCREENSHOT/.plugin_staging/stitch_session.json
 */

import RNFS from 'react-native-fs';

const STAGING_DIR = '/sdcard/SCREENSHOT/.plugin_staging';
const SESSION_FILE = `${STAGING_DIR}/stitch_session.json`;
const STITCH_IMAGES_DIR = `${STAGING_DIR}/stitch_images`;

export interface ImageCrop {
  cropTop: number;    // 0-1 fraction trimmed from top
  cropBottom: number; // 0-1 fraction trimmed from bottom
  cropLeft: number;   // 0-1 fraction trimmed from left
  cropRight: number;  // 0-1 fraction trimmed from right
}

export interface StitchImage {
  path: string;       // absolute file path
  width: number;      // original pixel width
  height: number;     // original pixel height
  crop: ImageCrop;    // per-image crop
}

export interface StitchParams {
  direction: 'vertical' | 'horizontal';
  overlap: number;        // pixels of overlap between images (in original image coords)
  topLayerIndex: number;  // 0 or 1: which image is on top in overlap zone
}

export interface StitchSessionData {
  images: StitchImage[];
  params: StitchParams;
  createdAt: number;
}

const DEFAULT_PARAMS: StitchParams = {
  direction: 'vertical',
  overlap: 100,
  topLayerIndex: 1, // newer image on top by default
};

const DEFAULT_CROP: ImageCrop = {
  cropTop: 0,
  cropBottom: 0,
  cropLeft: 0,
  cropRight: 0,
};

export const StitchSession = {
  async ensureDir(): Promise<void> {
    for (const d of [STAGING_DIR, STITCH_IMAGES_DIR]) {
      if (!(await RNFS.exists(d))) await RNFS.mkdir(d);
    }
  },

  /** Check if there's an active stitch session. */
  async hasActiveSession(): Promise<boolean> {
    try {
      return await RNFS.exists(SESSION_FILE);
    } catch (_) {
      return false;
    }
  },

  /** Load the current session, or null if none. */
  async load(): Promise<StitchSessionData | null> {
    try {
      if (!(await RNFS.exists(SESSION_FILE))) return null;
      const json = await RNFS.readFile(SESSION_FILE, 'utf8');
      const data = JSON.parse(json) as StitchSessionData;
      // Validate images still exist
      for (const img of data.images) {
        if (!(await RNFS.exists(img.path))) return null;
      }
      return data;
    } catch (_) {
      return null;
    }
  },

  /** Start a new session with the first image. */
  async startSession(imagePath: string, width: number, height: number): Promise<StitchSessionData> {
    await StitchSession.ensureDir();

    // Copy image to stitch_images dir for persistence
    const ts = Date.now();
    const destPath = `${STITCH_IMAGES_DIR}/${ts}_0.png`;
    const src = imagePath.replace('file://', '');
    await RNFS.copyFile(src, destPath);

    const session: StitchSessionData = {
      images: [{
        path: destPath,
        width,
        height,
        crop: { ...DEFAULT_CROP },
      }],
      params: { ...DEFAULT_PARAMS },
      createdAt: ts,
    };

    await RNFS.writeFile(SESSION_FILE, JSON.stringify(session), 'utf8');
    return session;
  },

  /** Add a second image to the session and return the updated session. */
  async addImage(imagePath: string, width: number, height: number): Promise<StitchSessionData | null> {
    const session = await StitchSession.load();
    if (!session) return null;

    await StitchSession.ensureDir();

    const ts = Date.now();
    const destPath = `${STITCH_IMAGES_DIR}/${ts}_${session.images.length}.png`;
    const src = imagePath.replace('file://', '');
    await RNFS.copyFile(src, destPath);

    session.images.push({
      path: destPath,
      width,
      height,
      crop: { ...DEFAULT_CROP },
    });

    await RNFS.writeFile(SESSION_FILE, JSON.stringify(session), 'utf8');
    return session;
  },

  /** Update stitch parameters. */
  async updateParams(params: Partial<StitchParams>): Promise<void> {
    const session = await StitchSession.load();
    if (!session) return;
    session.params = { ...session.params, ...params };
    await RNFS.writeFile(SESSION_FILE, JSON.stringify(session), 'utf8');
  },

  /** Update crop for a specific image. */
  async updateImageCrop(index: number, crop: Partial<ImageCrop>): Promise<void> {
    const session = await StitchSession.load();
    if (!session || index >= session.images.length) return;
    session.images[index].crop = { ...session.images[index].crop, ...crop };
    await RNFS.writeFile(SESSION_FILE, JSON.stringify(session), 'utf8');
  },

  /** Save entire session data. */
  async save(session: StitchSessionData): Promise<void> {
    await StitchSession.ensureDir();
    await RNFS.writeFile(SESSION_FILE, JSON.stringify(session), 'utf8');
  },

  /** Clear the session and clean up images. */
  async clearSession(): Promise<void> {
    try {
      if (await RNFS.exists(SESSION_FILE)) {
        await RNFS.unlink(SESSION_FILE);
      }
      // Clean up stitch images
      if (await RNFS.exists(STITCH_IMAGES_DIR)) {
        const files = await RNFS.readDir(STITCH_IMAGES_DIR);
        for (const f of files) {
          try { await RNFS.unlink(f.path); } catch (_) {}
        }
      }
    } catch (_) {}
  },
};

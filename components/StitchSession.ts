import RNFS from 'react-native-fs';

const STAGING_DIR = '/sdcard/SCREENSHOT/.plugin_staging';
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

const DEFAULT_PARAMS: StitchParams = {
  direction: 'vertical',
  overlap: 100,
  topLayerIndex: 1,
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

  async hasActiveSession(): Promise<boolean> {
    try {
      return await RNFS.exists(SESSION_FILE);
    } catch (_) {
      return false;
    }
  },

  async load(): Promise<StitchSessionData | null> {
    try {
      if (!(await RNFS.exists(SESSION_FILE))) return null;
      const json = await RNFS.readFile(SESSION_FILE, 'utf8');
      const data = JSON.parse(json) as StitchSessionData;

      for (const img of data.images) {
        if (!(await RNFS.exists(img.path))) return null;
      }
      return data;
    } catch (_) {
      return null;
    }
  },

  async startSession(imagePath: string, width: number, height: number): Promise<StitchSessionData> {
    await StitchSession.ensureDir();

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

  async addImage(imagePath: string, width: number, height: number): Promise<StitchSessionData | null> {
    const session = await StitchSession.load();
    if (!session) return null;

    await StitchSession.ensureDir();

    if (session.images.length >= 2) {
      const oldPath = session.images[1].path;
      try { await RNFS.unlink(oldPath); } catch (_) {}
      session.images.splice(1);
    }

    const ts = Date.now();
    const destPath = `${STITCH_IMAGES_DIR}/${ts}_1.png`;
    const src = imagePath.replace('file://', '');
    await RNFS.copyFile(src, destPath);

    session.images.push({
      path: destPath,
      width,
      height,
      crop: { ...DEFAULT_CROP },
    });

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
      if (await RNFS.exists(SESSION_FILE)) {
        await RNFS.unlink(SESSION_FILE);
      }

      if (await RNFS.exists(STITCH_IMAGES_DIR)) {
        const files = await RNFS.readDir(STITCH_IMAGES_DIR);
        for (const f of files) {
          try { await RNFS.unlink(f.path); } catch (_) {}
        }
      }
    } catch (_) {}
  },
};

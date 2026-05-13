import RNFS from 'react-native-fs';

const LOG_DIR = '/sdcard/INBOX';
const LOG_FILE = `${LOG_DIR}/localsend-plugin.log`;
const MAX_LOG_SIZE = 2 * 1024 * 1024;

function ts(): string {
  const d = new Date();
  const pad = (n: number, len = 2) => String(n).padStart(len, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}.${pad(d.getMilliseconds(), 3)}`;
}

async function rotateIfNeeded() {
  try {
    const stat = await RNFS.stat(LOG_FILE);
    if (Number(stat.size) > MAX_LOG_SIZE) {
      const bakPath = `${LOG_FILE}.bak`;
      if (await RNFS.exists(bakPath)) await RNFS.unlink(bakPath);
      await RNFS.moveFile(LOG_FILE, bakPath);
    }
  } catch (_) {

  }
}

async function appendLine(line: string) {
  try {
    await rotateIfNeeded();
    await RNFS.appendFile(LOG_FILE, line + '\n', 'utf8');
  } catch (e) {
    console.log('[FileLogger] write error:', e);
  }
}

export const FileLogger = {
  log(tag: string, message: string, data?: Record<string, unknown>) {
    const entry = data
      ? `[${ts()}][${tag}] ${message} ${JSON.stringify(data)}`
      : `[${ts()}][${tag}] ${message}`;
    console.log(entry);
    appendLine(entry);
  },

  logCrop(info: {
    cropBox: { x: number; y: number; w: number; h: number };
    displaySize: { x: number; y: number; width: number; height: number };
    originalSize: { width: number; height: number };
    relative: { relX: number; relY: number; relW: number; relH: number };
    cropData: { offset: { x: number; y: number }; size: { width: number; height: number } };
    resultUri?: string;
    error?: string;
  }) {
    appendLine(`[${ts()}][Crop] === Crop & Insert ===`);
    appendLine(`  cropBox:      ${JSON.stringify(info.cropBox)}`);
    appendLine(`  displaySize:  ${JSON.stringify(info.displaySize)}`);
    appendLine(`  originalSize: ${JSON.stringify(info.originalSize)}`);
    appendLine(`  relative:     ${JSON.stringify(info.relative)}`);
    appendLine(`  cropData:     ${JSON.stringify(info.cropData)}`);
    if (info.resultUri) appendLine(`  resultUri:    ${info.resultUri}`);
    if (info.error) appendLine(`  ERROR:        ${info.error}`);
  },

  logImageInsert(path: string, cropped: boolean) {
    appendLine(`[${ts()}][Insert] image=${path} cropped=${cropped}`);
  },

  logTextReceived(source: string, text: string) {
    const preview = text.length > 100 ? text.slice(0, 100) + '...' : text;
    appendLine(`[${ts()}][TextRecv] source=${source} len=${text.length} preview=${preview}`);
  },

  logTextInserted(page: number, rect: { left: number; top: number; right: number; bottom: number }, textLen: number) {
    appendLine(`[${ts()}][TextInsert] page=${page} rect=${JSON.stringify(rect)} textLen=${textLen}`);
  },

  logEvent(tag: string, message: string) {
    appendLine(`[${ts()}][${tag}] ${message}`);
  },
};

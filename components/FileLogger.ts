import { getRNFS } from './rnfs';

const LOG_DIR = '/sdcard/INBOX';
const LOG_FILE = `${LOG_DIR}/localsend-plugin.log`;
const MAX_LOG_SIZE = 2 * 1024 * 1024;

function ts(): string {
  const d = new Date();
  const pad = (n: number, len = 2) => String(n).padStart(len, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}.${pad(d.getMilliseconds(), 3)}`;
}

async function rotateIfNeeded() {
  const RNFS = getRNFS();
  if (!RNFS) return;
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
  const RNFS = getRNFS();
  if (!RNFS) return;
  try {
    await rotateIfNeeded();
    await RNFS.appendFile(LOG_FILE, line + '\n', 'utf8');
  } catch (e) {
    console.log('[FileLogger] write error:', e);
  }
}

export const FileLogger = {
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

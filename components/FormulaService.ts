import { NativeModules } from 'react-native';
import { PluginCommAPI, PluginFileAPI, PluginNoteAPI, PointUtils } from 'sn-plugin-lib';
import { getRNFS } from './rnfs';
import { FileLogger } from './FileLogger';

const { AIRelayModule, FloatingToolbar, TextLayoutEngine } = NativeModules;

const STAGE_DIR = '/sdcard/EXPORT/lasso_ai';
const FORMULA_PX_PER_EM = 72;
const FORMULA_CTX_STORE_KEY = 97;
const FORMULA_CAPTURE_MARGIN = 120;

export const FORMULA_PROMPT =
  '请识别图片中的手写内容（数学公式和文字）。严格按以下格式输出：\n' +
  '\n' +
  '### 识别\n' +
  '将手写内容逐字转写为印刷体：数学公式用 $$...$$ 包裹的标准 LaTeX 表示，' +
  '公式前后的普通文字保持原文。不要更正、不要补全，忠实转写。\n' +
  '\n' +
  '---\n' +
  '\n' +
  '### 解答\n' +
  '如果识别内容是计算题、方程、求解/化简/证明等数学问题，给出简洁的解题过程与最终答案' +
  '（公式同样用 $$...$$ 包裹）。如果只是普通笔记而非问题，简要给出相关说明或补充。\n' +
  '\n' +
  '数学排版硬性要求：所有数学内容（哪怕只是一个变量或短式）一律用 $$...$$ 包裹并' +
  '**独立成行**，公式行前后各留一个空行；绝对不要使用单个 $ 的行内公式，' +
  '不要把 $$...$$ 嵌在句子中间。\n' +
  '\n' +
  '除以上两个小节外不要输出任何其他内容，不要使用代码块。';

interface FormulaStroke {
  w: number;
  pts: number[];
}

interface RenderedFormula {
  width: number;
  height: number;
  em: number;
  png: string;
  strokes?: FormulaStroke[];
}

export interface FormulaBlock {
  type: 'math' | 'text';
  content: string;
  image?: RenderedFormula;
}

interface ReplaceContext {
  filePath: string;
  pageNum: number;
  elementNums: number[];
  rect: { left: number; top: number; right: number; bottom: number };
  createdAt: number;
}

let _ctx: ReplaceContext | null = null;
const CTX_TTL_MS = 30 * 60_000;

function saveCtx(): void {
  try { FloatingToolbar?.savePreset(FORMULA_CTX_STORE_KEY, JSON.stringify(_ctx ?? null)); } catch (_) {}
}

async function loadCtx(): Promise<ReplaceContext | null> {
  if (_ctx) return _ctx;
  try {
    const raw: string | null = await FloatingToolbar?.loadPreset(FORMULA_CTX_STORE_KEY);
    if (raw) {
      const parsed = JSON.parse(raw);
      if (parsed && Array.isArray(parsed.elementNums) && parsed.rect) {
        _ctx = parsed as ReplaceContext;
      }
    }
  } catch (_) {}
  return _ctx;
}

export async function sendFormulaQuery(): Promise<'ok' | 'no-lasso' | 'no-relay' | 'error'> {
  try {
    try {
      const ping: string = await AIRelayModule.pingRelay();
      if (ping !== '1') {
        FileLogger.logEvent('FormulaSend', `relay unreachable: ping=${ping}`);
        return 'no-relay';
      }
    } catch (e) {
      FileLogger.logEvent('FormulaSend', `ping failed: ${String(e)}`);
      return 'no-relay';
    }

    const [fpRes, pgRes]: any[] = await Promise.all([
      PluginCommAPI.getCurrentFilePath(),
      PluginCommAPI.getCurrentPageNum(),
    ]);
    if (!fpRes?.success || !pgRes?.success) return 'error';
    const filePath = fpRes.result as string;
    const pageNum = pgRes.result as number;

    const elementNums: number[] = [];
    try {
      const elsRes: any = await PluginCommAPI.getLassoElements();
      if (elsRes?.success && Array.isArray(elsRes.result)) {
        for (const el of elsRes.result) {
          const num = el.numInPage ?? el.trailNumInPage;
          if (typeof num === 'number' && num >= 1) elementNums.push(num);
          try { el.recycle?.(); } catch (_) {}
        }
      }
    } catch (e) {
      FileLogger.logEvent('FormulaSend', `getLassoElements failed: ${String(e)}`);
    }
    if (elementNums.length === 0) {
      FileLogger.logEvent('FormulaSend', 'no lasso elements');
      return 'no-lasso';
    }

    const lr: any = await (PluginCommAPI as any).getLassoRect();
    if (!lr?.success || !lr.result || typeof lr.result.left !== 'number') {
      FileLogger.logEvent('FormulaSend', `getLassoRect failed: ${lr?.error?.message ?? 'unknown'}`);
      return 'error';
    }
    const rect = {
      left: lr.result.left, top: lr.result.top,
      right: lr.result.right, bottom: lr.result.bottom,
    };

    _ctx = { filePath, pageNum, elementNums, rect, createdAt: Date.now() };
    saveCtx();

    try { await (PluginCommAPI as any).setLassoBoxState?.(2); } catch (_) {}
    await new Promise<void>(res => setTimeout(res, 500));

    const capPath = `${STAGE_DIR}/formula_query.png`;
    const captured: string | null = await AIRelayModule.captureScreenRegion(
      rect.left, rect.top, rect.right, rect.bottom, FORMULA_CAPTURE_MARGIN, capPath);
    if (!captured) {
      FileLogger.logEvent('FormulaSend', 'captureScreenRegion failed');
      return 'error';
    }

    try { AIRelayModule.sendAlive?.(); } catch (_) {}
    AIRelayModule.sendImageQueryKind(captured, '', FORMULA_PROMPT, 'formula');
    FileLogger.logEvent('FormulaSend',
      `sent img=${captured} elements=${elementNums.length} rect=${JSON.stringify(rect)}`);
    return 'ok';
  } catch (e) {
    console.error('[FormulaService]: sendFormulaQuery error:', e);
    FileLogger.logEvent('FormulaSend', `error: ${String(e)}`);
    return 'error';
  }
}

function parseBlocks(blocksJson: string): FormulaBlock[] {
  try {
    const arr = JSON.parse(blocksJson);
    if (!Array.isArray(arr)) return [];
    return arr
      .filter((b: any) => b && typeof b.content === 'string' && b.content.trim())
      .map((b: any) => {
        const img = b.image;
        const strokes: FormulaStroke[] | undefined = Array.isArray(img?.strokes)
          ? img.strokes.filter((s: any) =>
              s && typeof s.w === 'number' && Array.isArray(s.pts) && s.pts.length >= 4)
          : undefined;
        const image: RenderedFormula | undefined =
          img && typeof img.png === 'string' && img.png.length > 0
            && typeof img.width === 'number' && img.width > 0
            && typeof img.height === 'number' && img.height > 0
            ? {
                width: img.width, height: img.height,
                em: typeof img.em === 'number' && img.em > 0 ? img.em : img.height / 144,
                png: img.png,
                strokes: strokes && strokes.length > 0 ? strokes : undefined,
              } : undefined;
        return {
          type: b.type === 'math' ? 'math' as const : 'text' as const,
          content: String(b.content).trim(),
          image,
        };
      });
  } catch (_) {
    return [];
  }
}

const sleep = (ms: number) => new Promise<void>(r => setTimeout(r, ms));

const MAX_STROKE_ELEMENTS = 240;
const STROKE_PRESSURE = 1000; // 0–4096
const PEN_TYPE_FINELINER = 10;
const FORMULA_THICKNESS_FACTOR = 130; // raw = FILL_PEN_PX * sy * factor
const FORMULA_THICKNESS_MIN = 300;
const FORMULA_THICKNESS_MAX = 900;

async function insertFormulaStrokes(
  img: RenderedFormula,
  target: { left: number; top: number; right: number; bottom: number },
  filePath: string,
  pageNum: number,
): Promise<boolean> {
  const strokes = img.strokes;
  if (!strokes || strokes.length === 0) return false;
  if (strokes.length > MAX_STROKE_ELEMENTS) {
    FileLogger.logEvent('FormulaInsert', `too many strokes (${strokes.length}) — fallback to image`);
    return false;
  }
  try {
    const psRes: any = await (PluginFileAPI as any).getPageSize(filePath, pageNum);
    const pageSize = psRes?.result;
    if (!psRes?.success || !pageSize?.width || !pageSize?.height) {
      FileLogger.logEvent('FormulaInsert', `getPageSize failed: ${psRes?.error?.message ?? 'unknown'}`);
      return false;
    }

    const sx = (target.right - target.left) / img.width;
    const sy = (target.bottom - target.top) / img.height;

    const elements: any[] = [];
    for (const s of strokes) {
      const createRes: any = await (PluginCommAPI as any).createElement(0);
      if (!createRes?.success || !createRes.result) {
        FileLogger.logEvent('FormulaInsert',
          `createElement failed: ${createRes?.error?.message ?? 'unknown'}`);
        return false;
      }
      const el: any = createRes.result;

      const emrPoints: any[] = [];
      const pressures: number[] = [];
      for (let i = 0; i + 1 < s.pts.length; i += 2) {
        const px = target.left + s.pts[i] * sx;
        const py = target.top + s.pts[i + 1] * sy;
        emrPoints.push(PointUtils.androidPoint2Emr(
          { x: Math.round(px), y: Math.round(py) }, pageSize));
        pressures.push(STROKE_PRESSURE);
      }
      if (emrPoints.length < 2) continue;

      el.thickness = Math.max(FORMULA_THICKNESS_MIN,
        Math.min(FORMULA_THICKNESS_MAX, Math.round(s.w * sy * FORMULA_THICKNESS_FACTOR)));
      if (!el.stroke) {
        FileLogger.logEvent('FormulaInsert', 'created element has no stroke field');
        return false;
      }
      el.stroke.penColor = 0;
      el.stroke.penType = PEN_TYPE_FINELINER;
      const okPts = await el.stroke.points.setRange(0, emrPoints.length - 1, emrPoints);
      const okPrs = await el.stroke.pressures.setRange(0, pressures.length - 1, pressures);
      if (!okPts || !okPrs) {
        FileLogger.logEvent('FormulaInsert', `setRange failed pts=${okPts} prs=${okPrs}`);
        return false;
      }
      elements.push(el);
    }
    if (elements.length === 0) return false;

    const ins: any = await PluginFileAPI.insertElements(filePath, pageNum, elements);
    if (!ins?.success) {
      FileLogger.logEvent('FormulaInsert',
        `insertElements failed: ${ins?.error?.message ?? 'unknown'}`);
      return false;
    }
    FileLogger.logEvent('FormulaInsert',
      `ink placed: ${elements.length} strokes rect=${JSON.stringify(target)}`);
    return true;
  } catch (e) {
    FileLogger.logEvent('FormulaInsert', `stroke insert threw: ${String(e)}`);
    throw e;
  }
}

async function insertFormulaImage(img: RenderedFormula): Promise<boolean> {
  const pngPath = `${STAGE_DIR}/formula_insert.png`;
  try {
    const RNFS = getRNFS();
    if (!RNFS) {
      FileLogger.logEvent('FormulaInsert', 'RNFS unavailable');
      return false;
    }
    if (!(await RNFS.exists(STAGE_DIR))) await RNFS.mkdir(STAGE_DIR);
    await RNFS.writeFile(pngPath, img.png, 'base64');

    const ins: any = await PluginNoteAPI.insertImage(pngPath);
    if (!ins?.success) {
      FileLogger.logEvent('FormulaInsert', `insertImage failed: ${ins?.error?.message ?? 'unknown'}`);
      return false;
    }
    await sleep(400);
    try { await (PluginCommAPI as any).setLassoBoxState?.(2); } catch (_) {}
    FileLogger.logEvent('FormulaInsert', 'image inserted (fallback, host default placement)');
    return true;
  } catch (e) {
    FileLogger.logEvent('FormulaInsert', `image insert threw: ${String(e)}`);
    return false;
  }
}

const NOTE_LINE_H = 42;
const BOX_TOP_INSET = 14;
const NOTE_RENDER_PAD = 28;
const WIDTH_ADJUSTMENT = 30;
const TOP_MARGIN_BASE = 150;
const BASE_PAGE_HEIGHT = 1872;
const FONT_SIZE = 36;
const FORMULA_GAP_PAD = 8;
const INLINE_MAX_H = 58;
const INLINE_PAD = 10;
const TEXT_X_INSET = 15;

function cleanNoteLines(raw: string): string[] {
  let text = raw.replace(/^#{1,6}\s+/gm, '');
  text = text.replace(/^(?:>\s?)+/gm, '');
  text = text.replace(/^(\d+)\./gm, '$1\u200B.');
  text = text.replace(/\*/g, '');
  text = text.replace(/`/g, '');
  const out: string[] = [];
  for (const line of text.split('\n')) {
    const t = line.trim();
    if (t.length === 0) continue;
    if (/^-{2,}$/.test(t)) continue;
    if (/^#{1,3}\s*(Human|Assistant)\s*:?/i.test(t)) continue;
    if (t.startsWith('|') && t.endsWith('|')) {
      const inner = t.slice(1, -1);
      if (/^[-\s|]+$/.test(inner)) continue;
      out.push(inner.split('|').map(c => c.trim()).filter(c => c.length > 0).join('  '));
      continue;
    }
    out.push(line);
  }
  return out;
}

interface EmbeddedMathSlot {
  block: FormulaBlock;
  w: number;
  h: number;
  inline: boolean;
  lines: number;
  charOffset: number;
  phEnd: number;
  line?: number;
  x?: number;
  _lineArr?: number;
  _col?: number;
  _colEnd?: number;
}

function buildMergedText(
  blocks: FormulaBlock[],
  inlinePlan: boolean[],
  spaceWidth: number,
  innerWidth: number,
): { merged: string; slots: EmbeddedMathSlot[] } | null {
  const lines: string[] = [];
  const slots: EmbeddedMathSlot[] = [];
  let cur = '';
  let curSlots: EmbeddedMathSlot[] = [];
  let prev: 'none' | 'text' | 'inlineMath' | 'blockMath' = 'none';
  let mathIdx = -1;

  const flush = () => {
    if (cur.length === 0 && curSlots.length === 0) return;
    for (const s of curSlots) s._lineArr = lines.length;
    lines.push(cur);
    cur = '';
    curSlots = [];
  };

  for (const b of blocks) {
    if (b.type === 'math' && b.image) {
      mathIdx++;
      const img = b.image;
      let h = Math.max(32, Math.round(img.em * FORMULA_PX_PER_EM));
      let w = Math.round(h * img.width / img.height);
      if (w > innerWidth) {
        h = Math.max(32, Math.round(h * innerWidth / w));
        w = innerWidth;
      }
      if (inlinePlan[mathIdx]) {
        const n = Math.max(2, Math.ceil((w + 2 * INLINE_PAD) / spaceWidth));
        const slot: EmbeddedMathSlot = {
          block: b, w, h, inline: true, lines: 0,
          charOffset: 0, phEnd: 0, _col: cur.length, _colEnd: cur.length + n,
        };
        cur += ' '.repeat(n);
        curSlots.push(slot);
        slots.push(slot);
        prev = 'inlineMath';
      } else {
        flush();
        const k = Math.max(1, Math.ceil((h + FORMULA_GAP_PAD) / NOTE_LINE_H));
        const slot: EmbeddedMathSlot = {
          block: b, w, h, inline: false, lines: k,
          charOffset: 0, phEnd: 0, _lineArr: lines.length, _col: 0,
        };
        for (let i = 0; i < k; i++) lines.push('');
        slots.push(slot);
        prev = 'blockMath';
      }
    } else if (b.type === 'text') {
      const tl = cleanNoteLines(b.content);
      tl.forEach((ln, i) => {
        if (i === 0 && prev === 'inlineMath') {
          cur += ln;
        } else {
          flush();
          cur = ln;
        }
      });
      if (tl.length > 0) prev = 'text';
    }
  }
  flush();
  if (slots.length === 0 && lines.every(l => l.trim().length === 0)) return null;

  const lineOffsets: number[] = [];
  { let off = 0; for (const l of lines) { lineOffsets.push(off); off += l.length + 1; } }
  for (const s of slots) {
    const base = lineOffsets[s._lineArr!];
    s.charOffset = base + (s._col ?? 0);
    s.phEnd = s.inline ? base + (s._colEnd ?? 0) : s.charOffset;
  }
  return { merged: lines.join('\n'), slots };
}

async function insertEmbeddedBlocks(
  blocks: FormulaBlock[],
  area: { left: number; top: number; width: number },
  filePath: string,
  pageNum: number,
): Promise<number | null> {
  if (!blocks.every(b => b.type !== 'math' || (b.image?.strokes?.length ?? 0) > 0)) {
    FileLogger.logEvent('FormulaEmbed', 'math without strokes — fallback to interleaved');
    return null;
  }

  const psRes: any = await (PluginFileAPI as any).getPageSize(filePath, pageNum);
  const pageSize = psRes?.result;
  if (!psRes?.success || !pageSize?.width || !pageSize?.height) return null;

  const innerWidth = Math.max(64, area.width - 16);
  const measureWidth = Math.max(24, area.width - WIDTH_ADJUSTMENT);

  const pre: any = await TextLayoutEngine.measureLineStarts({ text: ' ', boxWidth: area.width });
  const spaceWidth: number = pre?.spaceWidth > 0 ? pre.spaceWidth : 18;

  const mathBlocks = blocks.filter(b => b.type === 'math');
  const inlinePlan: boolean[] = mathBlocks.map(b => {
    const img = b.image!;
    const h = Math.max(32, Math.round(img.em * FORMULA_PX_PER_EM));
    return h <= INLINE_MAX_H;
  });

  let merged = '';
  let slots: EmbeddedMathSlot[] = [];
  let measure: any = null;
  for (let iter = 0; iter <= mathBlocks.length; iter++) {
    const built = buildMergedText(blocks, inlinePlan, spaceWidth, innerWidth);
    if (!built) return null;
    merged = built.merged;
    slots = built.slots;
    const inlineSlots = slots.filter(s => s.inline);
    measure = await TextLayoutEngine.measureLineStarts({
      text: merged, boxWidth: area.width,
      offsets: inlineSlots.flatMap(s => [s.charOffset, Math.max(s.charOffset, s.phEnd - 1)]),
    });
    const infos: any[] = Array.isArray(measure?.offsetInfo) ? measure.offsetInfo : [];
    let demoted = false;
    inlineSlots.forEach((s, i) => {
      const a = infos[i * 2];
      const b = infos[i * 2 + 1];
      const sameLine = a && b && a.line === b.line;
      const fitsWidth = a && (a.x + 2 * INLINE_PAD + s.w) <= measureWidth;
      if (!sameLine || !fitsWidth) {
        const mi = mathBlocks.indexOf(s.block);
        if (mi >= 0 && inlinePlan[mi]) {
          inlinePlan[mi] = false;
          demoted = true;
          FileLogger.logEvent('FormulaEmbed',
            `inline demoted #${mi} (sameLine=${!!sameLine} fits=${!!fitsWidth})`);
        }
      } else {
        s.line = a.line;
        s.x = a.x;
      }
    });
    if (!demoted) break;
  }

  const lineStarts: number[] = Array.isArray(measure?.lineStarts) ? measure.lineStarts : [];
  const lineCount: number = measure?.lineCount ?? 0;
  if (lineCount <= 0 || lineStarts.length === 0) return null;

  const boxH = (measure.nativeHeight ?? lineCount * NOTE_LINE_H) + NOTE_RENDER_PAD;
  const topMargin = Math.round(TOP_MARGIN_BASE * (pageSize.height / BASE_PAGE_HEIGHT));
  const top = Math.max(topMargin, Math.round(area.top));
  if (top + boxH > pageSize.height - topMargin) {
    FileLogger.logEvent('FormulaEmbed',
      `page overflow (top=${top} boxH=${boxH} pageH=${pageSize.height}) — fallback`);
    return null;
  }

  const renderedLineOf = (offset: number): number => {
    let idx = 0;
    for (let i = 0; i < lineStarts.length; i++) {
      if (lineStarts[i] <= offset) idx = i; else break;
    }
    return idx;
  };

  const rect = {
    left: Math.round(area.left), top,
    right: Math.round(area.left + area.width), bottom: top + boxH,
  };
  const ins: any = await PluginNoteAPI.insertText({
    textContentFull: merged,
    textRect: rect,
    fontSize: FONT_SIZE,
    textAlign: 0, textBold: 0, textItalics: 0,
    textFrameWidthType: 0, textFrameStyle: 0, textEditable: 1,
  });
  if (!ins?.success) {
    FileLogger.logEvent('FormulaEmbed', `insertText failed: ${ins?.error?.message ?? 'unknown'}`);
    return null;
  }
  FileLogger.logEvent('FormulaEmbed',
    `box placed rect=${JSON.stringify(rect)} lines=${lineCount} slots=${slots.length}`);

  for (const s of slots) {
    let target: { left: number; top: number; right: number; bottom: number };
    if (s.inline && s.line != null && s.x != null) {
      const x0 = Math.round(area.left + TEXT_X_INSET + s.x + INLINE_PAD);
      const lineCenter = top + BOX_TOP_INSET + s.line * NOTE_LINE_H + NOTE_LINE_H / 2;
      const y0 = Math.round(lineCenter - s.h / 2);
      target = { left: x0, top: y0, right: x0 + s.w, bottom: y0 + s.h };
    } else {
      const startLine = renderedLineOf(s.charOffset);
      const gapTop = top + BOX_TOP_INSET + startLine * NOTE_LINE_H;
      const gapH = Math.max(1, s.lines) * NOTE_LINE_H;
      const y0 = Math.round(gapTop + Math.max(0, (gapH - s.h) / 2));
      target = {
        left: Math.round(area.left + 8),
        top: y0,
        right: Math.round(area.left + 8 + s.w),
        bottom: y0 + s.h,
      };
    }
    const placed = await insertFormulaStrokes(s.block.image!, target, filePath, pageNum);
    if (!placed) {
      FileLogger.logEvent('FormulaEmbed',
        `stroke slot failed (inline=${s.inline}) rect=${JSON.stringify(target)} — gap left blank`);
    }
  }
  return rect.bottom;
}

export interface ProcessBlocksOptions {
  replace: boolean;
  insertText: (text: string, anchorTop: number | null, anchorLeft: number | null) => void;
  waitTextIdle: () => Promise<number | null>;
  onStatus?: (msg: 'rendering' | 'inserting' | 'done' | 'failed') => void;
  onEmbedded?: (bottom: number, left: number) => void;
}

export async function processRelayBlocks(
  blocksJson: string,
  opts: ProcessBlocksOptions,
): Promise<boolean> {
  const blocks = parseBlocks(blocksJson);
  if (blocks.length === 0) return false;

  if (blocks.some(b => b.type === 'math' && !b.image)) {
    FileLogger.logEvent('FormulaInsert', 'math block without image — abort');
    opts.onStatus?.('failed');
    return false;
  }

  const loaded = await loadCtx();
  const ctx = loaded && Date.now() - loaded.createdAt < CTX_TTL_MS ? loaded : null;

  const [fpRes, pgRes]: any[] = await Promise.all([
    PluginCommAPI.getCurrentFilePath(),
    PluginCommAPI.getCurrentPageNum(),
  ]);
  if (!fpRes?.success || !pgRes?.success) return false;
  const filePath = fpRes.result as string;
  const pageNum = pgRes.result as number;

  const canReplace = opts.replace && ctx != null
    && ctx.elementNums.length > 0
    && ctx.filePath === filePath && ctx.pageNum === pageNum;
  if (opts.replace && !canReplace) {
    FileLogger.logEvent('FormulaInsert', 'replace ctx missing/mismatch — fallback to insert');
  }

  const FALLBACK_W = 700;
  const refRect = ctx?.rect ?? null;
  const areaLeft = refRect ? refRect.left : 120;
  const areaWidth = refRect
    ? Math.max(200, refRect.right - refRect.left)
    : FALLBACK_W;
  let cursorY = canReplace
    ? (refRect ? refRect.top : 200)
    : (refRect ? refRect.bottom + 40 : 200);
  if (!refRect) {
    const base = await opts.waitTextIdle();
    if (base != null) cursorY = Math.max(cursorY, base);
  }

  opts.onStatus?.('inserting');

  if (canReplace && ctx) {
    try {
      const del: any = await PluginFileAPI.deleteElements(filePath, pageNum, ctx.elementNums);
      FileLogger.logEvent('FormulaInsert',
        `replace: deleted ${ctx.elementNums.length} elements ok=${del?.success}`);
    } catch (e) {
      FileLogger.logEvent('FormulaInsert', `replace delete failed: ${String(e)}`);
    }
  }

  try {
    const embeddedBottom = await insertEmbeddedBlocks(
      blocks,
      { left: areaLeft, top: cursorY, width: areaWidth },
      filePath, pageNum,
    );
    if (embeddedBottom != null) {
      if (canReplace && _ctx) { _ctx = { ..._ctx, elementNums: [] }; saveCtx(); }
      opts.onEmbedded?.(embeddedBottom, areaLeft);
      opts.onStatus?.('done');
      return true;
    }
  } catch (e) {
    FileLogger.logEvent('FormulaEmbed', `abort: host comm failure — ${String(e)}`);
    opts.onStatus?.('failed');
    return false;
  }

  const GAP = 24;
  let ok = true;

  for (const b of blocks) {
    if (b.type === 'math' && b.image) {
      const img = b.image;
      let h = Math.max(32, Math.round(img.em * FORMULA_PX_PER_EM));
      let w = Math.round(h * img.width / img.height);
      if (w > areaWidth) {
        h = Math.max(32, Math.round(h * areaWidth / w));
        w = areaWidth;
      }
      const target = {
        left: Math.round(areaLeft),
        top: Math.round(cursorY),
        right: Math.round(areaLeft + w),
        bottom: Math.round(cursorY + h),
      };
      let placed = false;
      try {
        placed = await insertFormulaStrokes(img, target, filePath, pageNum);
      } catch (e) {
        FileLogger.logEvent('FormulaInsert', `abort: host comm failure — ${String(e)}`);
        ok = false;
        break;
      }
      if (!placed) placed = await insertFormulaImage(img);
      if (!placed) { ok = false; break; }
      cursorY += h + GAP;
    } else if (b.type === 'text') {
      try {
        opts.insertText(
          b.content,
          Math.round(cursorY),
          Math.round(areaLeft),
        );
      } catch (e) {
        FileLogger.logEvent('FormulaInsert', `insertText failed: ${String(e)}`);
        continue;
      }
      const settled = await opts.waitTextIdle();
      if (settled != null) cursorY = Math.max(cursorY, settled);
    }
  }

  if (canReplace && _ctx) { _ctx = { ..._ctx, elementNums: [] }; saveCtx(); }

  opts.onStatus?.(ok ? 'done' : 'failed');
  return ok;
}

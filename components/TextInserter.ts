import { PluginFileAPI, PluginCommAPI, PluginNoteAPI, PluginManager } from 'sn-plugin-lib';
import { NativeModules, NativeEventEmitter } from 'react-native';
import { FileLogger } from './FileLogger';
import { splitTextForHeight } from './TextboxMetrics';
import { normalizeInsertionLeft } from './InsertionGeometry';

const { FloatingToolbar } = NativeModules;

const { TextLayoutEngine } = NativeModules;

export type InsertMode = 'nospacing' | 'paragraph';

export type TextSource = 'localsend' | 'broadcast' | 'unknown';

interface QueueItem {
  text: string;
  source: TextSource;
}

const TOP_MARGIN_BASE     = 150;

const BASE_PAGE_HEIGHT    = 1872;

let _presetNotePath: string | null = null;
let _presetPageSize: { width: number; height: number } | null = null;

export function presetPageInfo(notePath: string, size: { width: number; height: number }): void {
  _presetNotePath = notePath;
  _presetPageSize = size;
  console.log('[TextInserter]: presetPageInfo cached notePath=', notePath, 'size=', JSON.stringify(size));
}

const FONT_SIZE         = 36;
// Must mirror TextLayoutEngine.kt: NOTE renders textboxes taller than
// StaticLayout.height (42px device line height + frame inset); without this
// the last line's bottom quarter clips. Applied on top of split fittingHeight.
const NOTE_RENDER_PAD   = 28;
// Mirrors TextLayoutEngine.DEVICE_LINE_HEIGHT / emojiExtraHeight: the NOTE
// app renders emoji wider than StaticLayout measures → device wraps extra
// lines. Reserve one device line per 4 emoji in split measurement too.
const DEVICE_LINE_HEIGHT = 42;
// Mirrors TextLayoutEngine's long-box allowance for NOTE's system font, whose
// wrap width differs from PluginHost StaticLayout despite the width adjustment.
const SYSTEM_FONT_WRAP_SAFETY_RATIO = 0.15;
const SYSTEM_FONT_WRAP_SAFETY_MIN_LINES = 8;
const EMOJI_PER_EXTRA_LINE = 4;
const EMOJI_REGEX = /[\u{1F000}-\u{1FAFF}\u{2600}-\u{27BF}\u{FE0F}\u{2190}-\u{21FF}\u{2B00}-\u{2BFF}]/gu;

function emojiExtraHeight(text: string): number {
  const count = (text.match(EMOJI_REGEX) ?? []).length;
  if (count === 0) return 0;
  return Math.ceil(count / EMOJI_PER_EXTRA_LINE) * DEVICE_LINE_HEIGHT;
}

function systemFontWrapExtraHeight(lineCount: number): number {
  if (lineCount < SYSTEM_FONT_WRAP_SAFETY_MIN_LINES) return 0;
  return Math.ceil(lineCount * SYSTEM_FONT_WRAP_SAFETY_RATIO) * DEVICE_LINE_HEIGHT;
}
// Must mirror TextLayoutEngine.WIDTH_ADJUSTMENT: the NOTE app wraps text
// ~30px narrower than the frame rect, so split measurement must use the
// same reduced width or the device wraps more lines than we budgeted for.
const WIDTH_ADJUSTMENT  = 30;
const INTERVAL_MS       = 200;

const PEN_SAFE_GAP_MS   = 2000;

// The note app re-loads its page layers right after returning to the
// foreground; inserting a text box during that window crashes its native
// redraw. Wait this long after a foreground return before inserting.
const NOTE_FOREGROUND_SETTLE_MS = 1500;

const SDK_TIMEOUT_MS    = 8000;

interface ModeConfig {

  boxGap: number;

  threshold: number;

  lineHeightRatio: number;

  newlineGapLines: number;
}

const MODE_CONFIG: Record<InsertMode, ModeConfig> = {
  nospacing: {
    boxGap: 0,
    threshold: 0.87,
    lineHeightRatio: 1.4,
    newlineGapLines: 0,
  },
  paragraph: {
    boxGap: 40,
    threshold: 0.80,
    lineHeightRatio: 1.6,
    newlineGapLines: 0.3,
  },
};

interface OccupiedRange {
  top: number;
  bottom: number;
}

function withTimeout<T>(promise: Promise<T>, ms: number, label: string): Promise<T> {
  return new Promise<T>((resolve, reject) => {
    const timer = setTimeout(() => {
      reject(new Error(`[Timeout] ${label} exceeded ${ms}ms`));
    }, ms);
    promise.then(
      (v) => { clearTimeout(timer); resolve(v); },
      (e) => { clearTimeout(timer); reject(e); },
    );
  });
}

function sdkCall<T = any>(p: Promise<any>, label: string, ms: number = SDK_TIMEOUT_MS): Promise<T> {
  return withTimeout(p as Promise<T>, ms, label);
}

export class TextInserter {
  private textQueue: QueueItem[] = [];
  private pageSize: { width: number; height: number } | null = null;
  private notePath = '';
  private currentPage = 0;
  private targetPage  = 0;
  private pageNextTop = new Map<number, number>();
  private pageNextLeft = new Map<number, number>();

  private _bubbleAnchorTop: number = -1;

  private timerRef: ReturnType<typeof setTimeout> | null = null;
  private activeMode: InsertMode | null = null;

  private inserting = false;

  private lastScheduleAt = 0;

  private lastPenUpAt = 0;

  private penUpSub: { remove(): void } | null = null;

  private _paused = false;
  private _scheduleNextDeferred: InsertMode | null = null;
  private _deferredTimerRef: any = null;
  private _nativeTickSub: { remove(): void } | null = null;

  private _occupiedRanges: OccupiedRange[] = [];
  private _occupiedPage = -1;

  // Inserts are held until this timestamp. Set when the note app returns to
  // the foreground: inserting while it is still re-loading its page layers
  // can crash the note's native redraw (SIGSEGV in redrawTrails).
  private _holdInsertsUntil = 0;

  private _expectedTotalPages = -1;

  private onAck?: (text: string, success: boolean, error: string | null) => void;
  private onPositionChanged?: (page: number, nextTop: number, source: TextSource, isPageChange: boolean) => void;

  private onNoteChanged?: (reason: 'note_switched' | 'pages_changed', detail: string) => void;

  constructor(
    onAck?: (text: string, success: boolean, error: string | null) => void,
    onPositionChanged?: (page: number, nextTop: number, source: TextSource, isPageChange: boolean) => void,
    onNoteChanged?: (reason: 'note_switched' | 'pages_changed', detail: string) => void,
  ) {
    this.onAck = onAck;
    this.onPositionChanged = onPositionChanged;
    this.onNoteChanged = onNoteChanged;
  }

  private _topMargin(): number {
    if (!this.pageSize) return TOP_MARGIN_BASE;
    return Math.round(TOP_MARGIN_BASE * (this.pageSize.height / BASE_PAGE_HEIGHT));
  }

  enqueue(text: string, source: TextSource = 'unknown') {
    // The note renders plain text only: drop markdown heading markers
    // ("# "…"###### " at line starts) instead of inserting them literally.
    text = text.replace(/^#{1,6}\s+/gm, '');
    // Same for blockquote markers: strip leading "> " (incl. nested ">> ").
    text = text.replace(/^(?:>\s?)+/gm, '');
    this.textQueue.push({ text, source });

    if (this.activeMode && !this._paused) this._registerPenUp();

    if (this.activeMode && !this.inserting && !this._paused) {
      if (this.timerRef === null) {

        console.log('[TextInserter]: waking up from enqueue');
        this.timerRef = 1 as any;
        this._scheduleNext(this.activeMode);
      } else {

        const stale = Date.now() - this.lastScheduleAt > INTERVAL_MS * 3;
        if (stale) {
          console.log('[TextInserter]: timer stale, kick-starting from enqueue');
          this._scheduleNext(this.activeMode);
        }
      }
    }
  }

  clearQueue() {
    this.textQueue = [];
  }

  getQueueLength(): number {
    return this.textQueue.length;
  }

  getQueueSnapshot(): string[] {
    return this.textQueue.map(q => q.text);
  }

  drainQueue(): string[] {
    const out = this.textQueue.map(q => q.text);
    this.textQueue = [];
    return out;
  }

  isBusy(): boolean {
    return this.inserting;
  }

  isRunning(): boolean {
    return this.activeMode !== null;
  }

  hasLiveTimers(): boolean {
    return this.timerRef !== null;
  }

  getMode(): InsertMode | null {
    return this.activeMode;
  }

  pause(): void {
    if (this._paused) return;
    this._paused = true;
    this._unregisterPenUp();
    console.log('[TextInserter]: ⏸ paused (queue len=', this.textQueue.length, ')');
    FileLogger.logEvent('InsertPaused', `queueLen=${this.textQueue.length}`);
  }

  resume(): void {
    if (!this._paused) return;
    this._paused = false;
    console.log('[TextInserter]: ▶ resumed (queue len=', this.textQueue.length, ')');
    FileLogger.logEvent('InsertResumed', `queueLen=${this.textQueue.length}`);

    if (this.activeMode && this.textQueue.length > 0) this._registerPenUp();
    if (this.activeMode && !this.inserting) {
      if (this.timerRef !== null) clearTimeout(this.timerRef as any);
      this.timerRef = 1 as any;
      this._scheduleNext(this.activeMode);
    }
  }

  isPaused(): boolean {
    return this._paused;
  }

  isWaitingPage(): boolean {
    return this.activeMode !== null && this.currentPage < this.targetPage;
  }

  getNotePath(): string {
    return this.notePath;
  }

  setStartTopIfAdvance(top: number) {
    const tm = this._topMargin();
    const current = this.pageNextTop.get(this.targetPage) ?? tm;
    if (top > current) {
      this.pageNextTop.set(this.targetPage, top);
      FileLogger.logEvent('SetStartTop',
        `page=${this.targetPage} advanced from ${current} to ${top}`);
      console.log('[TextInserter]: setStartTopIfAdvance page=', this.targetPage,
        'advanced', current, '→', top);
    }
  }

  forceSetNextTop(top: number) {
    const tm = this._topMargin();
    const clamped = Math.max(tm, top);
    this.pageNextTop.set(this.targetPage, clamped);

    this._bubbleAnchorTop = clamped;
    console.warn('[TextInserter/DIAG]: forceSetNextTop page=', this.targetPage, 'top=', clamped,
      'bubbleAnchor=', this._bubbleAnchorTop,
      'allEntries=', JSON.stringify(Array.from(this.pageNextTop.entries())));
    this.onPositionChanged?.(this.targetPage, clamped, 'unknown', false);
  }

  /** Delay any insertText for [ms] from now (e.g. note app just resumed). */
  holdInserts(ms: number) {
    const until = Date.now() + ms;
    if (until > this._holdInsertsUntil) {
      this._holdInsertsUntil = until;
      console.log('[TextInserter]: holding inserts for', ms, 'ms');
    }
  }

  private _normalizeNextLeft(left: number) {
    if (!this.pageSize) {
      const normalized = Math.max(0, Math.round(left));
      return { left: normalized, safeMaxLeft: normalized, right: normalized, minTextboxWidth: 0 };
    }
    return normalizeInsertionLeft(this.pageSize.width, left, FONT_SIZE);
  }

  forceSetNextLeft(left: number): number | undefined {
    if (left < 0) {
      this.pageNextLeft.delete(this.targetPage);
      return undefined;
    }
    const geometry = this._normalizeNextLeft(left);
    this.pageNextLeft.set(this.targetPage, geometry.left);
    console.log('[TextInserter]: forceSetNextLeft page=', this.targetPage,
      'requested=', left, 'normalized=', geometry.left, 'safeMax=', geometry.safeMaxLeft,
      'minWidth=', geometry.minTextboxWidth);
    return geometry.left;
  }

  setNextInsertionAnchor(top: number, left: number, source: string = 'programmatic'):
      { top: number; left: number; safeMaxLeft: number } {
    const clampedTop = Math.max(this._topMargin(), Math.round(top));
    const geometry = this._normalizeNextLeft(left);
    this.pageNextTop.set(this.targetPage, clampedTop);
    this.pageNextLeft.set(this.targetPage, geometry.left);
    this._bubbleAnchorTop = clampedTop;
    console.warn('[TextInserter/DIAG]: setNextInsertionAnchor source=', source,
      'page=', this.targetPage, 'requested=', JSON.stringify({ top, left }),
      'normalized=', JSON.stringify({ top: clampedTop, left: geometry.left }),
      'safeMax=', geometry.safeMaxLeft, 'minWidth=', geometry.minTextboxWidth);
    FileLogger.logEvent('SetInsertAnchor',
      `source=${source} page=${this.targetPage} requested=(${left},${top}) normalized=(${geometry.left},${clampedTop}) safeMax=${geometry.safeMaxLeft}`);
    this.onPositionChanged?.(this.targetPage, clampedTop, 'unknown', false);
    return { top: clampedTop, left: geometry.left, safeMaxLeft: geometry.safeMaxLeft };
  }

  getTargetPage(): number { return this.targetPage; }

  relocateTo(page: number): void {
    this.targetPage = page;
    this.currentPage = page;
    console.log('[TextInserter]: relocateTo page=', page);
  }

  getNextTop(): number {
    return this.pageNextTop.get(this.targetPage) ?? this._topMargin();
  }

  getNextLeft(): number | undefined {
    return this.pageNextLeft.get(this.targetPage);
  }

  getPageSize(): { width: number; height: number } | null {
    return this.pageSize;
  }

  switchMode(newMode: InsertMode): boolean {
    if (!this.activeMode || !this.pageSize) return false;

    const oldMode = this.activeMode;
    if (oldMode === newMode) return true;

    this._applyModeSwitchGapInternal(oldMode, newMode);

    this.activeMode = newMode;

    if (this._paused) {
      this._paused = false;
      console.log('[TextInserter]: auto-resume on switchMode');
    }

    if (this.timerRef !== null) {
      clearTimeout(this.timerRef as any);
      this.timerRef = 1 as any;
      this._scheduleNext(newMode);
    }

    console.log('[TextInserter]: switchMode', oldMode, '→', newMode);
    FileLogger.logEvent('SwitchMode',
      `${oldMode} → ${newMode} nextTop=${this.pageNextTop.get(this.targetPage)}`);
    return true;
  }

  applyModeSwitchGap(fromMode: InsertMode, toMode: InsertMode): void {
    if (!this.pageSize) return;
    if (fromMode === toMode) return;
    this._applyModeSwitchGapInternal(fromMode, toMode);
  }

  private _applyModeSwitchGapInternal(fromMode: InsertMode, toMode: InsertMode): void {
    const oldCfg = MODE_CONFIG[fromMode];
    const newCfg = MODE_CONFIG[toMode];
    const tm = this._topMargin();

    if (newCfg.boxGap > oldCfg.boxGap) {
      const currentTop = this.pageNextTop.get(this.targetPage) ?? tm;
      const gapDiff = newCfg.boxGap - oldCfg.boxGap;
      const oneLineHeight = Math.ceil(FONT_SIZE * newCfg.lineHeightRatio);
      const extraGap = gapDiff + oneLineHeight;
      this.pageNextTop.set(this.targetPage, currentTop + extraGap);
      console.log('[TextInserter]: modeSwitchGap +', extraGap,
        '(gapDiff=', gapDiff, 'lineH=', oneLineHeight, ') → nextTop=', currentTop + extraGap);
    }
  }

  async start(mode: InsertMode, preservePosition = false): Promise<boolean> {
    this.stop(true);
    if (!preservePosition) {
      this.pageNextTop.clear();
      this.pageNextLeft.clear();
      this._bubbleAnchorTop = -1;
    }
    this.activeMode = mode;
    this.lastPenUpAt = 0;
    this.inserting = false;
    this._paused = false;

    try {
      const fpRes = await sdkCall(PluginCommAPI.getCurrentFilePath(), 'getCurrentFilePath');
      console.log('[TextInserter]: getCurrentFilePath →', JSON.stringify(fpRes));
      if (fpRes?.success && fpRes.result) this.notePath = fpRes.result;

      const pgRes = await sdkCall(PluginCommAPI.getCurrentPageNum(), 'getCurrentPageNum');
      console.log('[TextInserter]: getCurrentPageNum →', JSON.stringify(pgRes));
      if (pgRes?.success && typeof pgRes.result === 'number') {
        this.currentPage = pgRes.result;
        this.targetPage  = pgRes.result;
      }

      if (!this.notePath) {
        console.warn('[TextInserter]: notePath empty, retrying getCurrentFilePath...');
        await new Promise<void>(r => setTimeout(r, 600));
        const fp2 = await sdkCall(PluginCommAPI.getCurrentFilePath(), 'getCurrentFilePath(retry)');
        console.log('[TextInserter]: getCurrentFilePath(retry) →', JSON.stringify(fp2));
        if (fp2?.success && fp2.result) this.notePath = fp2.result;
      }

      let psRes: any = null;
      if (_presetNotePath && _presetPageSize && _presetNotePath === this.notePath) {
        psRes = { success: true, result: { ..._presetPageSize } };
        console.log('[TextInserter]: pageSize from preset cache →', JSON.stringify(psRes.result));
      } else {
        psRes = await sdkCall(PluginFileAPI.getPageSize(this.notePath, this.currentPage), 'getPageSize');
        console.log('[TextInserter]: getPageSize(notePath=', this.notePath, ', page=', this.currentPage, ') →', JSON.stringify(psRes));
      }

      if (!psRes?.success || !psRes.result?.width) {
        console.warn('[TextInserter]: getPageSize failed, retrying in 800ms...');
        await new Promise<void>(r => setTimeout(r, 800));
        psRes = await sdkCall(PluginFileAPI.getPageSize(this.notePath, this.currentPage), 'getPageSize(retry)');
        console.log('[TextInserter]: getPageSize(retry) →', JSON.stringify(psRes));
      }
      if (psRes?.success && psRes.result?.width) {
        this.pageSize = { width: psRes.result.width, height: psRes.result.height };
        console.log('[TextInserter]: pageSize=', this.pageSize,
          'topMargin=', this._topMargin());
      } else {
        console.error('[TextInserter]: failed to get pageSize after retry');
        this.activeMode = null;
        return false;
      }
    } catch (e) {
      console.error('[TextInserter]: failed to get page info', e);
      this.activeMode = null;
      return false;
    }

    await this._refreshOccupiedRanges();

    try {
      const tpRes = await sdkCall(PluginFileAPI.getNoteTotalPageNum(this.notePath), 'getNoteTotalPageNum');
      if (tpRes?.success && typeof tpRes.result === 'number') {
        this._expectedTotalPages = tpRes.result;
        console.log('[TextInserter]: initial totalPages=', this._expectedTotalPages);
      }
    } catch (e) {
      console.warn('[TextInserter]: getNoteTotalPageNum failed (non-fatal):', e);
    }

    this.timerRef = 1 as any;
    console.log('[TextInserter]: started, mode=', mode, 'page=', this.currentPage, 'notePath=', this.notePath);
    this._scheduleNext(mode);
    return true;
  }

  stop(clearActive = false) {
    if (this.timerRef) {
      clearTimeout(this.timerRef as any);
      this.timerRef = null;
    }
    this._stopNativeTick();
    if (clearActive) {
      this.activeMode = null;
      this._paused = false;
      this._unregisterPenUp();
    }
  }

  destroy() {
    this.stop(true);
  }

  private _registerPenUp() {
    this._unregisterPenUp();
    try {
      this.penUpSub = PluginManager.registerEventListener('event_pen_up', 1, {
        onMsg: (_elements: any) => {
          this.lastPenUpAt = Date.now();
          if (Array.isArray(_elements)) {
            for (const el of _elements) {
              try { el.recycle?.(); } catch (_) {}
            }
          }
        },
      });
      console.log('[TextInserter]: pen-up listener registered');
    } catch (e) {
      console.warn('[TextInserter]: pen-up listener registration failed:', e);
    }
  }

  private _unregisterPenUp() {
    if (this.penUpSub) {
      try { this.penUpSub.remove(); } catch (_) {}
      this.penUpSub = null;
    }
  }

  private _isPenIdle(): boolean {
    if (this.lastPenUpAt === 0) return true;
    return (Date.now() - this.lastPenUpAt) >= PEN_SAFE_GAP_MS;
  }

  private async _refreshOccupiedRanges(): Promise<void> {

    if (this._occupiedPage === this.targetPage) return;

    this._occupiedRanges = [];
    this._occupiedPage = this.targetPage;

    try {
      const res = await sdkCall(PluginFileAPI.getElements(this.targetPage, this.notePath), 'getElements(scan)');
      if (res?.success && Array.isArray(res.result)) {
        for (const el of res.result) {
          try {
            const elType = el.type;

            if (typeof elType === 'number' && elType >= 500 && elType <= 502) {
              const rect = el.textBox?.textRect;
              if (rect && typeof rect.top === 'number' && typeof rect.bottom === 'number') {
                this._occupiedRanges.push({ top: rect.top, bottom: rect.bottom });
              }
            }
          } finally {

            try { el.recycle?.(); } catch (_) {}
          }
        }

        this._occupiedRanges.sort((a, b) => a.top - b.top);
        console.log('[TextInserter]: scanned page', this.targetPage,
          'found', this._occupiedRanges.length, 'existing textboxes');
        FileLogger.logEvent('ScanTextBoxes',
          `page=${this.targetPage} count=${this._occupiedRanges.length}`);
      }
    } catch (e) {
      console.warn('[TextInserter]: _refreshOccupiedRanges failed:', e);
    }
  }

  private _addToOccupied(top: number, bottom: number): void {
    if (this._occupiedPage === this.targetPage) {
      this._occupiedRanges.push({ top, bottom });
      this._occupiedRanges.sort((a, b) => a.top - b.top);
    }
  }

  private async _readBackActualBottom(): Promise<number | null> {
    try {
      const res = await sdkCall((PluginFileAPI.getLastElement as any)(this.targetPage, this.notePath), 'getLastElement');
      if (res?.success && res.result) {
        const el = res.result;

        const elType = el.type;
        const isTextBox = typeof elType === 'number' && elType >= 500 && elType <= 502;
        const actualBottom = el.textBox?.textRect?.bottom;
        try { el.recycle?.(); } catch (_) {}
        if (isTextBox && typeof actualBottom === 'number' && actualBottom > 0) {
          return actualBottom;
        }
      }
    } catch (e) {
      console.warn('[TextInserter]: readback failed:', e);
    }
    return null;
  }

  private async _insertParagraph(mode: InsertMode): Promise<'continue' | 'pause' | 'done'> {
    if (!MODE_CONFIG[mode] || !this.pageSize) return 'done';

    if (this._paused) {
      return 'continue';
    }

    if (Date.now() < this._holdInsertsUntil) {
      return 'continue';
    }

    // Authoritative anti-race gate: ask the native side directly how long the
    // note app has been foreground. The event-based holdInserts() above can
    // lose the race (native flag flips before the JS event lands), which let
    // an insertText hit the note 26ms into its page reload and crash it.
    try {
      const elapsed = FloatingToolbar?.getNoteForegroundElapsedMs?.();
      if (typeof elapsed === 'number' && elapsed < NOTE_FOREGROUND_SETTLE_MS) {
        return 'continue'; // covers elapsed === -1 (not in note app) too
      }
    } catch (_) {}

    if (!this._isPenIdle()) {
      return 'continue';
    }

    const contextOk = await this._checkNoteContext();
    if (!contextOk) return 'done';

    let newText = this.textQueue.shift();
    const isFromQueue = !!newText;

    if (!newText) {
      return 'continue';
    }

    const itemSource = newText.source;
    let text = newText.text;
    console.log('[TextInserter]: dequeued text len=', text.length, 'source=', itemSource, 'preview=', text.slice(0, 30));

    const rawLines = text.split('\n');
    const rawLineCount = rawLines.length;
    const emptyLineCount = rawLines.filter(l => l.trim().length === 0).length;
    // Strip <t>...</t> thinking blocks (may span multiple lines)
    text = text.replace(/<t>[\s\S]*?<\/t>\s*/g, '');
    // Strip unclosed <t> at end of text (last thinking block cut by split)
    text = text.replace(/<t>[^]*$/g, '');
    // Strip orphaned </t> at/near start (relay chunks at 600 chars; a <t>
    // block spanning the boundary leaves the second chunk starting with
    // "...thinking</t>actual reply" — no opening <t> for our regexes above).
    text = text.replace(/^[^]*?<\/t>\s*/g, '');
    text = text.replace(/^(\d+)\./gm, '$1\u200B.');
    text = text.replace(/\*/g, '');
    text = text.replace(/`/g, '');
    // Prefill placeholder UUIDs \u2014 defense in depth (relay strips them on
    // closeHead, but streamed/older texts still carry them; tail group may
    // be truncated mid-stream, hence {1,12}).
    text = text.replace(/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{1,12}/gi, '');
    // Role turn markers:
    //   H:/Human: \u2014 the user's handwritten text (already on the page as ink);
    //             delete the ENTIRE line (marker + content) to avoid duplicate.
    //   A:/Assistant: \u2014 AI reply; strip the marker only, keep the content.
    // BLANK is an invisible sentinel that survives the empty-line filter
    // below and turns back into a real empty line (turn separator) at the
    // end; leading sentinels (prefill "## H:" opener at text start) are
    // dropped so the note doesn't begin with a blank line.
    const BLANK = '\u2063';
    // H lines: entire line \u2192 blank sentinel (content discarded)
    text = text.replace(/^(?:#{1,3}\s*)?(?:H|Human)\s*[:\uFF1A].*$/gim, BLANK);
    // A lines: marker stripped, content kept on its own line
    text = text.replace(/^(?:#{1,3}\s*)?(?:A|Assistant)\s*[:\uFF1A][ \t]*(.*)$/gim,
      (_, rest) => (rest && rest.trim().length > 0 ? `${BLANK}\n${rest}` : BLANK));
    text = text.split('\n').map(line => {
      const t = line.trim();
      if (t.startsWith('|') && t.endsWith('|')) {
        const inner = t.slice(1, -1);
        if (/^[-\s|]+$/.test(inner)) return null;
        return inner.split('|').map(c => c.trim()).filter(c => c.length > 0).join('  ');
      }
      return line;
    }).filter(line => {
      if (line === null) return false;
      const t = line.trim();
      if (t.length === 0) return false;
      if (/^-{2,}$/.test(t)) return false;
      return true;
    }).join('\n');
    // Sentinel → real blank line: collapse runs, drop leading/trailing ones
    // (a prefill "## H:" opener must not indent the note with an empty line),
    // then the bare sentinel char vanishes leaving "\n\n" = one blank line.
    text = text
      .replace(new RegExp(`(?:${BLANK}\\n?)+`, 'g'), `${BLANK}\n`)
      .replace(new RegExp(`^(?:${BLANK}\\n)+`), '')
      .replace(new RegExp(`(?:\\n?${BLANK}\\n?)+$`), '')
      .replace(new RegExp(BLANK, 'g'), '');
    const cleanedLineCount = text.split('\n').length;
    FileLogger.logEvent('TextPreprocess',
      `rawLines=${rawLineCount} emptyLines=${emptyLineCount} cleanedLines=${cleanedLineCount} textLen=${text.length}`);

    if (text.trim().length === 0) {
      console.log('[TextInserter]: text empty after preprocessing, skipping');
      FileLogger.logEvent('TextSkipEmpty', `source=${itemSource}`);
      this.onAck?.('', false, 'empty after preprocessing');
      return 'continue';
    }

    if (!this.activeMode || !this.pageSize) {
      if (isFromQueue) this.textQueue.unshift({ text, source: itemSource });
      return 'done';
    }

    const ps = this.pageSize;
    const tm = this._topMargin();

    try {
      const pgCheck = await sdkCall(PluginCommAPI.getCurrentPageNum(), 'getCurrentPageNum(preLayout)');
      if (pgCheck?.success && typeof pgCheck.result === 'number') {
        this.currentPage = pgCheck.result;
        if (this.currentPage < this.targetPage) {

          console.log('[TextInserter]: waiting for user to navigate to target page',
            this.currentPage, '→', this.targetPage);
          if (isFromQueue) {
            this.textQueue.unshift({ text, source: itemSource });
          }
          this.onPositionChanged?.(this.targetPage, this.getNextTop(), itemSource, false);
          return 'pause';
        } else if (this.currentPage > this.targetPage) {
          console.log('[TextInserter]: user on forward page, relocating',
            this.targetPage, '→', this.currentPage);
          FileLogger.logEvent('PageAutoRelocate',
            `target=${this.targetPage} → current=${this.currentPage}`);
          this.relocateTo(this.currentPage);
          const relocTop = this._bubbleAnchorTop > 0 ? this._bubbleAnchorTop : this._topMargin();
          this.pageNextTop.set(this.currentPage, relocTop);
          console.log('[TextInserter]: relocate set top=', relocTop, 'bubbleAnchor=', this._bubbleAnchorTop);
          this.onPositionChanged?.(this.currentPage, relocTop, itemSource, true);
        }
      }
    } catch (e) {
      console.warn('[TextInserter]: pre-layout page check failed:', e);
    }

    this._occupiedPage = -1;
    await this._refreshOccupiedRanges();

    const nextTopVal = this.pageNextTop.get(this.targetPage) ?? tm;
    const nextLeftVal = this.pageNextLeft.get(this.targetPage);
    console.warn('[TextInserter/DIAG]: pre-layout targetPage=', this.targetPage,
      'nextTopVal=', nextTopVal, 'nextLeftVal=', nextLeftVal,
      'pageNextTop=', JSON.stringify(Array.from(this.pageNextTop.entries())));

    const layout = await sdkCall(TextLayoutEngine.calculateLayout({
      text,
      mode,
      pageWidth: ps.width,
      pageHeight: ps.height,
      nextTop: nextTopVal,
      occupiedRanges: this._occupiedRanges,
      ...(nextLeftVal !== undefined ? { overrideLeft: nextLeftVal } : {}),
    }), 'TextLayoutEngine.calculateLayout');

    const { left, right, boxHeight: boxH, lines } = layout;
    let top: number = layout.top;
    const bottom = layout.bottom;
    const maxH: number = layout.maxH;
    const remainH = maxH - top;
    FileLogger.logEvent('BoxCalc',
      `mode=${mode} lines=${lines} boxH=${boxH} top=${top} maxH=${maxH} remainH=${remainH} gap=${layout.boxGap} fits=${boxH <= remainH} topMargin=${tm}`);

    if (layout.newPage) {

      const splitAvailH: number = layout.remainingHeight ?? 0;
      const boxWidth = right - left;
      let fittingInserted = false;

      if (splitAvailH > FONT_SIZE * 2) {

        let splitFitting: string | null = null;
        let splitOverflow: string | null = null;
        let splitHeight: number = splitAvailH;
        let splitLineCount = 0;

        try {
          // Measure at the note's effective wrap width, and reserve the
          // render pad + emoji allowance so fitting lines still fit the page
          // after the device's wider emoji wrapping.
          const measureWidth = Math.max(24, boxWidth - WIDTH_ADJUSTMENT);
          const availableLineBudget = Math.floor(
            Math.max(0, splitAvailH - NOTE_RENDER_PAD) / DEVICE_LINE_HEIGHT,
          );
          const measureAvailH = Math.max(FONT_SIZE,
            splitAvailH - NOTE_RENDER_PAD - emojiExtraHeight(text)
              - systemFontWrapExtraHeight(availableLineBudget));
          const splitResult = await splitTextForHeight(text, measureWidth, FONT_SIZE, measureAvailH);
          if (splitResult && splitResult.didSplit && splitResult.fittingText.trim().length > 0) {
            splitFitting = splitResult.fittingText;
            splitOverflow = splitResult.overflowText;
            splitHeight = splitResult.fittingHeight;
            splitLineCount = splitResult.fittingLineCount;
            console.log('[TextInserter]: native split: fitting=', splitFitting.length, 'overflow=', splitOverflow.length);
          }
        } catch (e) {
          console.warn('[TextInserter]: splitTextForHeight error:', e);
        }

        if (!splitFitting && layout.lines > 0 && layout.charsPerLine > 0) {
          const lineH = boxH / layout.lines;
          const fittingLines = Math.max(1, Math.floor(splitAvailH / lineH) - 1);
          if (fittingLines < layout.lines) {
            const approxSplitChar = fittingLines * layout.charsPerLine;

            let splitAt = Math.min(approxSplitChar, text.length);
            const searchStart = Math.max(0, splitAt - Math.floor(splitAt * 0.15));

            const lastNL = text.lastIndexOf('\n', splitAt);
            if (lastNL >= searchStart) { splitAt = lastNL + 1; }
            else {

              const sentenceEnds = ['。', '！', '？', '.', '!', '?'];
              for (let i = splitAt - 1; i >= searchStart; i--) {
                if (sentenceEnds.includes(text[i])) { splitAt = i + 1; break; }
              }
            }
            const fit = text.substring(0, splitAt).trimEnd();
            const over = text.substring(splitAt).trimStart();
            if (fit.length > 0 && over.length > 0) {
              splitFitting = fit;
              splitOverflow = over;
              splitHeight = Math.ceil(fittingLines * lineH);
              splitLineCount = fittingLines;
              console.log('[TextInserter]: JS fallback split: fitting=', fit.length,
                'overflow=', over.length, 'fittingLines=', fittingLines);
            }
          }
        }

        if (splitFitting && splitFitting.trim().length > 0) {
          const fitTop = top;
          // splitHeight includes StaticLayout's bottom padding; add the
          // note-render + emoji allowance (same basis as the single-box path).
          const fitBottom = Math.min(ps.height,
            fitTop + splitHeight + NOTE_RENDER_PAD + emojiExtraHeight(splitFitting)
              + systemFontWrapExtraHeight(splitLineCount));
          try {
            const fitRes = await sdkCall(
              PluginNoteAPI.insertText({
                textContentFull: splitFitting,
                textRect: { left, top: fitTop, right, bottom: fitBottom },
                fontSize: FONT_SIZE,
                textAlign: 0,
                textBold: 0,
                textItalics: 0,
                textFrameWidthType: 0,
                textFrameStyle: 0,
                textEditable: 1,
              }),
              'insertText(split-fit)',
            );

            if (fitRes?.success) {
              FileLogger.logEvent('SplitInsert',
                `page=${this.targetPage} fittingLen=${splitFitting.length} overflowLen=${splitOverflow?.length ?? 0} fitH=${splitHeight}`);
              this.onAck?.(splitFitting, true, null);
              this._addToOccupied(fitTop, fitBottom);
              fittingInserted = true;

              if (splitOverflow && splitOverflow.trim().length > 0) {
                this.textQueue.unshift({ text: splitOverflow, source: itemSource });
              }
            }
          } catch (e) {
            console.warn('[TextInserter]: split-fit insertText failed:', e);
          }
        }
      }

      if (!fittingInserted) {
        this.textQueue.unshift({ text, source: itemSource });
      }

      if (fittingInserted && this.textQueue.length === 0) {
        const actualBottom = await this._readBackActualBottom();
        const maxReasonableBottom = ps.height;
        const effectiveBottom = (actualBottom !== null && actualBottom > top && actualBottom <= maxReasonableBottom)
          ? actualBottom : (top + (layout.remainingHeight ?? boxH));
        const newNextTop = effectiveBottom + layout.boxGap;
        this.pageNextTop.set(this.targetPage, newNextTop);
        this.onPositionChanged?.(this.targetPage, newNextTop, itemSource, false);
        return 'continue';
      }

      // Page full — do NOT auto-create a new page (that triggers a note save
      // which breaks undo/redo). Instead pause and let the user decide.
      console.log('[TextInserter]: page full, pausing (remaining queue=', this.textQueue.length, ')');
      FileLogger.logEvent('PageFull', `page=${this.targetPage} queueLen=${this.textQueue.length}`);
      return 'pause';
    }

    if (!this.activeMode || !this.pageSize) {
      if (isFromQueue) this.textQueue.unshift({ text, source: itemSource });
      return 'done';
    }

    const renderBottom = Math.min(ps.height, bottom);
    try {
      const res = await sdkCall(
        PluginNoteAPI.insertText({
          textContentFull: text,
          textRect: { left, top, right, bottom: renderBottom },
          fontSize: FONT_SIZE,
          textAlign: 0,
          textBold: 0,
          textItalics: 0,
          textFrameWidthType: 0,
          textFrameStyle: 0,
          textEditable: 1,
        }),
        'insertText',
      );

      if (res?.success) {
        FileLogger.logTextInserted(this.targetPage, { left, top, right, bottom }, text.length);

        FileLogger.logEvent('PageAnnotation',
          `text inserted to page=${this.targetPage} currentPage=${this.currentPage} textLen=${text.length} preview=${text.slice(0, 30).replace(/\n/g, '↵')}`);

        const actualBottom = await this._readBackActualBottom();

        const maxReasonableBottom = bottom * 1.5;
        const effectiveBottom = (actualBottom !== null
          && actualBottom > top
          && actualBottom <= maxReasonableBottom)
          ? Math.max(actualBottom, bottom)
          : bottom;
        const newNextTop = effectiveBottom + layout.boxGap;
        const currentNextTop = this.pageNextTop.get(this.targetPage) ?? 0;

        if (newNextTop >= currentNextTop) {
          this.pageNextTop.set(this.targetPage, newNextTop);
          console.warn('[TextInserter/DIAG]: postInsert SET nextTop=', newNextTop, 'was=', currentNextTop);
        } else {
          console.warn('[TextInserter/DIAG]: postInsert SKIP nextTop=', newNextTop, '<', currentNextTop, '(kept higher)');
        }

        this._addToOccupied(top, effectiveBottom);

        FileLogger.logEvent('InsertPos',
          `top=${top} estimatedBottom=${bottom} actualBottom=${actualBottom} effectiveBottom=${effectiveBottom} nextTop=${effectiveBottom + layout.boxGap} source=${itemSource} preview=${text.slice(0, 40).replace(/\n/g, '↵')}`);

        this.onAck?.(text, true, null);
        this.onPositionChanged?.(this.targetPage, effectiveBottom + layout.boxGap, itemSource, false);
        return 'continue';
      } else {
        const errMsg = res?.error?.message || 'insertText failed';
        console.error('[TextInserter]: insertText failed (skipping):', errMsg);
        FileLogger.logEvent('TextInsertFail', `page=${this.targetPage} err=${errMsg} textLen=${text.length}`);
        this.onAck?.(text, false, errMsg);
        return 'continue';
      }
    } catch (e) {
      const errMsg = String(e);
      console.error('[TextInserter]: insertText exception:', errMsg);
      FileLogger.logEvent('TextInsertException', `page=${this.targetPage} err=${errMsg}`);
      this.onAck?.(text, false, errMsg);
      return 'continue';
    }
  }

  private _deferNextTick(mode: InsertMode, delay: number) {
    this._scheduleNextDeferred = mode;
    clearTimeout(this._deferredTimerRef);
    this._deferredTimerRef = setTimeout(() => {
      if (this._scheduleNextDeferred === mode && !this.inserting) {
        this._stopNativeTick();
        this._scheduleNextDeferred = null;
        this._scheduleNext(mode);
      }
    }, delay);
    this._startNativeTick(mode, delay);
  }

  private _startNativeTick(mode: InsertMode, intervalMs: number) {
    this._stopNativeTick();
    try {
      FloatingToolbar?.startInsertTimer(Math.max(intervalMs, 500));
      const emitter = new NativeEventEmitter(FloatingToolbar);
      this._nativeTickSub = emitter.addListener('onInsertTimerTick', () => {
        if (this._scheduleNextDeferred === mode && !this.inserting) {
          this._stopNativeTick();
          clearTimeout(this._deferredTimerRef);
          this._scheduleNextDeferred = null;
          this._scheduleNext(mode);
        }
      });
    } catch (_) {}
  }

  private _stopNativeTick() {
    this._nativeTickSub?.remove();
    this._nativeTickSub = null;
    try { FloatingToolbar?.stopInsertTimer(); } catch (_) {}
  }

  private _scheduleNext(mode: InsertMode) {
    if (this._paused || this.timerRef === null || this.textQueue.length === 0) {
      this.timerRef = null;

      this._unregisterPenUp();
      console.log(`[TextInserter]: idle (paused=${this._paused}, queue=${this.textQueue.length})`);
      return;
    }

    if (this.inserting) return;

    if (!this._isPenIdle()) {

      this._deferNextTick(mode, PEN_SAFE_GAP_MS);
      return;
    }

    this.inserting = true;
    this.lastScheduleAt = Date.now();

    this._insertParagraph(mode).then(result => {
      this.inserting = false;

      if (result === 'continue' && this.timerRef !== null) {
        if (this.textQueue.length > 0) {

          this.timerRef = 1 as any;
          this._deferNextTick(mode, 50);
        } else {
          this._deferNextTick(mode, INTERVAL_MS);
        }
      } else if (result === 'pause') {

        console.log('[TextInserter]: page changed, continuing after delay');
        this.timerRef = 1 as any;
        this._deferNextTick(mode, 800);
      } else {
        this.timerRef = null;
        this.activeMode = null;
        console.log('[TextInserter]: stopped');
      }
    }).catch(e => {
      this.inserting = false;
      console.error('[TextInserter]: _scheduleNext unexpected error:', e);
      FileLogger.logEvent('ScheduleError', String(e));
      if (this.timerRef !== null) {
        this.timerRef = 1 as any;
        this._deferNextTick(mode, INTERVAL_MS * 5);
      }
    });
  }

  private async _checkNoteContext(): Promise<boolean> {
    try {

      const fgElapsed = FloatingToolbar?.getNoteForegroundElapsedMs?.();
      const notInNoteApp = typeof fgElapsed === 'number' && fgElapsed < 0;

      const fpRes = await sdkCall(PluginCommAPI.getCurrentFilePath(), 'getCurrentFilePath(check)');
      if (!notInNoteApp &&
          fpRes?.success && fpRes.result && this.notePath && fpRes.result !== this.notePath) {
        const oldPath = this.notePath;
        const newPath = fpRes.result;
        console.error('[TextInserter]: NOTE SWITCHED! old=', oldPath, 'new=', newPath);
        FileLogger.logEvent('NoteSwitched', `old=${oldPath} new=${newPath}`);
        this.stop(true);
        this.onNoteChanged?.('note_switched', `${oldPath} → ${newPath}`);
        return false;
      }

      if (this._expectedTotalPages > 0 && this.notePath) {
        const tpRes = await sdkCall(PluginFileAPI.getNoteTotalPageNum(this.notePath), 'getNoteTotalPageNum(check)');
        if (tpRes?.success && typeof tpRes.result === 'number') {
          const actual = tpRes.result;
          if (actual !== this._expectedTotalPages) {
            const diff = actual - this._expectedTotalPages;
            console.log('[TextInserter]: totalPages updated: expected=',
              this._expectedTotalPages, 'actual=', actual, 'diff=', diff);
            FileLogger.logEvent('PagesUpdated',
              `expected=${this._expectedTotalPages} actual=${actual} diff=${diff} targetPage=${this.targetPage}`);
            this._expectedTotalPages = actual;
          }
        }
      }
    } catch (e) {
      console.warn('[TextInserter]: _checkNoteContext error:', e);
    }
    return true;
  }
}

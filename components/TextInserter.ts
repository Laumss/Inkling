import { PluginFileAPI, PluginCommAPI, PluginNoteAPI, PluginManager } from 'sn-plugin-lib';
import { FileLogger } from './FileLogger';

export type InsertMode = 'nospacing' | 'paragraph';

export type TextSource = 'localsend' | 'broadcast' | 'unknown';

interface QueueItem {
  text: string;
  source: TextSource;
}

const PAGE_MARGIN_RIGHT = 0.04;
const PAGE_MARGIN_LEFT  = 0.07;

const TOP_MARGIN_BASE     = 150;

const BASE_PAGE_HEIGHT    = 1872;

const CHAR_WIDTH_FACTOR_CJK = 1.0;

const CHAR_WIDTH_FACTOR_LATIN = 0.62;
const FONT_SIZE         = 36;
const INTERVAL_MS       = 200;

const PEN_SAFE_GAP_MS   = 2000;

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

  private timerRef: ReturnType<typeof setTimeout> | null = null;
  private activeMode: InsertMode | null = null;

  private inserting = false;

  private lastScheduleAt = 0;

  private lastPenUpAt = 0;

  private penUpSub: { remove(): void } | null = null;

  private _paused = false;
  private _scheduleNextDeferred: InsertMode | null = null;
  private _deferredTimerRef: any = null;

  private _occupiedRanges: OccupiedRange[] = [];
  private _occupiedPage = -1;

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
    console.log('[TextInserter]: forceSetNextTop page=', this.targetPage, 'top=', clamped);
    this.onPositionChanged?.(this.targetPage, clamped, 'unknown', false);
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
    }
    this.activeMode = mode;
    this.lastPenUpAt = 0;
    this.inserting = false;
    this._paused = false;

    try {
      const fpRes = await sdkCall(PluginCommAPI.getCurrentFilePath(), 'getCurrentFilePath');
      if (fpRes?.success && fpRes.result) this.notePath = fpRes.result;

      const pgRes = await sdkCall(PluginCommAPI.getCurrentPageNum(), 'getCurrentPageNum');
      if (pgRes?.success && typeof pgRes.result === 'number') {
        this.currentPage = pgRes.result;
        this.targetPage  = pgRes.result;
      }

      if (!this.notePath) {
        console.warn('[TextInserter]: notePath empty, retrying getCurrentFilePath...');
        await new Promise<void>(r => setTimeout(r, 600));
        const fp2 = await sdkCall(PluginCommAPI.getCurrentFilePath(), 'getCurrentFilePath(retry)');
        if (fp2?.success && fp2.result) this.notePath = fp2.result;
      }

      let psRes = await sdkCall(PluginFileAPI.getPageSize(this.notePath, this.currentPage), 'getPageSize');

      if (!psRes?.success || !psRes.result?.width) {
        console.warn('[TextInserter]: getPageSize failed, retrying in 800ms...');
        await new Promise<void>(r => setTimeout(r, 800));
        psRes = await sdkCall(PluginFileAPI.getPageSize(this.notePath, this.currentPage), 'getPageSize(retry)');
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

  private _skipOccupiedArea(candidateTop: number, boxH: number, gap: number): number {
    let top = candidateTop;

    let iterations = 0;
    while (iterations < 50) {
      iterations++;
      let collision = false;
      for (const range of this._occupiedRanges) {

        if (top < range.bottom && (top + boxH) > range.top) {

          const newTop = range.bottom + gap;
          console.log('[TextInserter]: collision at top=', top,
            'with existing [', range.top, ',', range.bottom, '] → skip to', newTop);
          FileLogger.logEvent('CollisionSkip',
            `top=${top} boxH=${boxH} existing=[${range.top},${range.bottom}] newTop=${newTop}`);
          top = newTop;
          collision = true;
          break;
        }
      }
      if (!collision) break;
    }
    return top;
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
    const cfg = MODE_CONFIG[mode];
    if (!cfg || !this.pageSize) return 'done';

    if (this._paused) {
      return 'continue';
    }

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
    text = text.replace(/^(\d+)\./gm, '$1\u200B.');
    text = text.replace(/\*/g, '');
    text = text.split('\n').filter(line => line.trim().length > 0).join('\n');
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
    const left     = Math.floor(ps.width * PAGE_MARGIN_LEFT);
    const right    = ps.width - Math.floor(ps.width * PAGE_MARGIN_RIGHT);
    const maxH     = Math.floor(ps.height * cfg.threshold);
    const boxWidth = right - left;

    const cjkCount = (text.match(/[\u4e00-\u9fff\u3000-\u303f\uff00-\uffef]/g) || []).length;
    const cjkRatio = text.length > 0 ? cjkCount / text.length : 0;
    const charWidthFactor = CHAR_WIDTH_FACTOR_CJK * cjkRatio + CHAR_WIDTH_FACTOR_LATIN * (1 - cjkRatio);
    const charsPerLine = Math.max(1, Math.floor(boxWidth / (FONT_SIZE * charWidthFactor)));

    const segments = text.split('\n');
    const lines = segments.reduce((acc, seg, idx) => {
      if (seg.trim().length === 0) return acc;
      const segLines = Math.max(1, Math.ceil(seg.length / charsPerLine));
      const newlineGap = cfg.newlineGapLines > 0 &&
        (idx < segments.length - 1 && segments[idx + 1].trim().length > 0)
        ? cfg.newlineGapLines : 0;
      return acc + segLines + newlineGap;
    }, 0);
    const boxH = Math.ceil(lines * FONT_SIZE * cfg.lineHeightRatio);

    if (this._occupiedPage !== this.targetPage) {
      await this._refreshOccupiedRanges();
    }

    let top = this.pageNextTop.get(this.targetPage) ?? tm;

    top = this._skipOccupiedArea(top, boxH, Math.max(cfg.boxGap, 10));

    const bottom = Math.min(top + boxH, ps.height - tm);
    const remainH = maxH - top;
    FileLogger.logEvent('BoxCalc',
      `mode=${mode} segments=${segments.length} lines=${lines} boxH=${boxH} top=${top} maxH=${maxH} remainH=${remainH} gap=${cfg.boxGap} fits=${boxH <= remainH} topMargin=${tm}`);

    if (top >= maxH || (top > tm + 80 && top + boxH > ps.height - tm)) {
      if (isFromQueue) {
        this.textQueue.unshift({ text, source: itemSource });
      }

      const candidateTemplates: string[] = [];
      try {
        const templates = await sdkCall(PluginCommAPI.getNoteSystemTemplates(), 'getNoteSystemTemplates');

        if (Array.isArray(templates) && templates.length > 0) {
          for (const t of templates) {
            if (t?.name) candidateTemplates.push(t.name);
          }
        }
        console.log('[TextInserter]: parsed templates count=', candidateTemplates.length);
      } catch (_) {}

      if (!candidateTemplates.includes('style_white')) {
        candidateTemplates.push('style_white');
      }

      const newPageIndex = this.targetPage + 1;
      console.log('[TextInserter]: page full, creating page', newPageIndex, 'candidates=', candidateTemplates);

      let pageCreated = false;
      for (const templateName of candidateTemplates) {
        try {
          const npRes = await sdkCall(
            PluginFileAPI.insertNotePage({
              notePath: this.notePath,
              page: newPageIndex,
              template: templateName,
            } as any),
            'insertNotePage',
          );

          if (npRes?.success) {
            console.log('[TextInserter]: page created with template=', templateName);
            this.targetPage = newPageIndex;
            this.pageNextTop.set(newPageIndex, tm);

            this._occupiedRanges = [];
            this._occupiedPage = newPageIndex;

            if (this._expectedTotalPages > 0) this._expectedTotalPages++;
            pageCreated = true;

            try {
              await sdkCall(PluginCommAPI.reloadFile(), 'reloadFile');
              console.log('[TextInserter]: reloadFile after insertNotePage OK');
            } catch (e) {
              console.warn('[TextInserter]: reloadFile failed (non-fatal):', e);
            }

            this.onPositionChanged?.(newPageIndex, tm, itemSource, true);

            break;
          } else {
            console.warn('[TextInserter]: insertNotePage failed with template=', templateName, ':', npRes?.error?.message);
          }
        } catch (e) {
          console.warn('[TextInserter]: insertNotePage exception with template=', templateName, ':', e);
        }
      }

      if (!pageCreated) {
        console.error('[TextInserter]: all template candidates failed, pausing (not stopping)');
        FileLogger.logEvent('InsertPageAllFailed', `page=${newPageIndex} candidates=${candidateTemplates.join(',')}`);

        this.targetPage = newPageIndex;
      }
      return 'pause';
    }

    if (!this.activeMode || !this.pageSize) {
      if (isFromQueue) this.textQueue.unshift({ text, source: itemSource });
      return 'done';
    }

    try {
      const pgCheck = await sdkCall(PluginCommAPI.getCurrentPageNum(), 'getCurrentPageNum(preInsert)');
      if (pgCheck?.success && typeof pgCheck.result === 'number') {
        this.currentPage = pgCheck.result;
        if (this.currentPage !== this.targetPage) {
          console.warn('[TextInserter]: page mismatch! current=', this.currentPage,
            'target=', this.targetPage, ', pausing to prevent wrong-page insertion');
          FileLogger.logEvent('PageMismatchPause',
            `current=${this.currentPage} target=${this.targetPage} textLen=${text.length}`);

          if (isFromQueue) this.textQueue.unshift({ text, source: itemSource });
          return 'pause';
        }
      }
    } catch (e) {
      console.warn('[TextInserter]: pre-insert page check failed (proceeding cautiously):', e);
    }

    const renderBottom = Math.min(ps.height, bottom + (lines >= 6 ? 7 : 0));
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
          ? actualBottom
          : bottom;
        this.pageNextTop.set(this.targetPage, effectiveBottom + cfg.boxGap);

        this._addToOccupied(top, effectiveBottom);

        FileLogger.logEvent('InsertPos',
          `top=${top} estimatedBottom=${bottom} actualBottom=${actualBottom} effectiveBottom=${effectiveBottom} nextTop=${effectiveBottom + cfg.boxGap} source=${itemSource} preview=${text.slice(0, 40).replace(/\n/g, '↵')}`);

        this.onAck?.(text, true, null);
        this.onPositionChanged?.(this.targetPage, effectiveBottom + cfg.boxGap, itemSource, false);
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
        this._scheduleNextDeferred = null;
        this._scheduleNext(mode);
      }
    }, delay);
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
        this.timerRef = null;
        this.activeMode = null;
        console.log('[TextInserter]: stopped after page change — restart text insertion to continue');
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

      const fpRes = await sdkCall(PluginCommAPI.getCurrentFilePath(), 'getCurrentFilePath(check)');
      if (fpRes?.success && fpRes.result && this.notePath && fpRes.result !== this.notePath) {
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
            console.warn('[TextInserter]: totalPages changed externally: expected=',
              this._expectedTotalPages, 'actual=', actual, 'diff=', diff);
            FileLogger.logEvent('PagesChanged',
              `expected=${this._expectedTotalPages} actual=${actual} diff=${diff} targetPage=${this.targetPage}`);
            this._expectedTotalPages = actual;
            this.stop(true);
            this.onNoteChanged?.('pages_changed',
              `totalPages ${actual - diff} → ${actual}, targetPage was ${this.targetPage}`);
            return false;
          }
        }
      }
    } catch (e) {
      console.warn('[TextInserter]: _checkNoteContext error:', e);
    }
    return true;
  }
}

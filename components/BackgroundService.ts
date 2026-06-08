

import { NativeModules, NativeEventEmitter, DeviceEventEmitter } from 'react-native';
import { PluginCommAPI, NativeUIUtils } from 'sn-plugin-lib';
import { TextInserter, InsertMode, TextSource } from './TextInserter';
import { FileLogger } from './FileLogger';
import LocalSendBridge, { TextReceivedInfo } from './LocalSendBridge';
import { LassoExtractor } from './LassoExtractor';
import FloatingBubbleBridge, { AiBubbleBridge } from './BubbleBridges';

import { loadBubbleActions, resolveBubbleActions, loadAiBubbleActions, resolveAiBubbleActions } from './ToolPresets';
import { t } from './i18n';
import FloatingToolbarBridge from './FloatingToolbarBridge';

const { BroadcastBridge } = NativeModules;

const AI_REPLY_GAP_PX = 80;
const LAST_MODE_STORE_KEY = 96;

const { FloatingToolbar } = NativeModules;

async function saveLastMode(mode: InsertMode): Promise<void> {
  try { await FloatingToolbar?.savePreset(LAST_MODE_STORE_KEY, mode); } catch (_) {}
}

async function loadLastMode(): Promise<InsertMode> {
  try {
    const v = await FloatingToolbar?.loadPreset(LAST_MODE_STORE_KEY);
    if (v === 'nospacing' || v === 'paragraph') return v;
  } catch (_) {}
  return 'nospacing';
}

let _textInserter: TextInserter | null = null;
let _broadcastTextSub: { remove(): void } | null = null;
let _lsTextSub: { remove(): void } | null = null;
let _initialized = false;
let _activeMode: InsertMode | null = null;
let _lastMode: InsertMode = 'nospacing';
let _aiWaiting = false;
let _aiTimeoutRef: ReturnType<typeof setTimeout> | null = null;
let _nativeBubblePermission: boolean | null = null;

let _lastAiSendAt = 0;

let _aiSendInFlight = false;

let _lastAiSendText = '';
const AI_SAME_TEXT_DEBOUNCE_MS = 8000;

let _bubbleActionSub: { remove(): void } | null = null;

let _nativePanelCloseSub: { remove(): void } | null = null;

let _cachedBubbleActionIds: string[] = [];

let _cachedAiBubbleActionIds: string[] = [];

let _aiActive = false;

let _aiPositionLocked = false;

let _switchVersion = 0;

let _localSendStarted = false;

function clearAiTimeout(): void {
  if (_aiTimeoutRef !== null) {
    clearTimeout(_aiTimeoutRef);
    _aiTimeoutRef = null;
  }
}

function emitInsertMode(mode: InsertMode | null): void {
  DeviceEventEmitter.emit('insertModeChanged', { mode });
}

function _pushActiveModes(): void {
  const modes: string[] = [];
  if (_activeMode) modes.push('insert_text');
  if (_aiActive) {
    modes.push('voice_transcribe');
  }
  FloatingToolbarBridge.setActiveModes(modes);
}

function reviveBridge(): void {
  if (!BroadcastBridge) return;

  if (_broadcastTextSub) {
    _broadcastTextSub.remove();
    _broadcastTextSub = null;
  }

  BroadcastBridge.startListening();

  const bbEmitter = new NativeEventEmitter(BroadcastBridge);
  _broadcastTextSub = bbEmitter.addListener('onTextFromRelay', (text: string) => {
    console.log('[BackgroundService]: onTextFromRelay len=', text.length);
    FileLogger.logTextReceived('Broadcast', text);

    try { BroadcastBridge.sendAck(text, true, null); } catch (_) {}

    clearAiTimeout();

    if (_aiWaiting && _textInserter) {
      PluginCommAPI.getCurrentPageNum().then((pgRes: any) => {
        if (pgRes?.success && typeof pgRes.result === 'number') {
          const currentPage = pgRes.result;
          if (currentPage !== _textInserter!.getTargetPage()) {
            console.log('[BackgroundService]: AI reply page update', _textInserter!.getTargetPage(), '→', currentPage);
            _textInserter!.relocateTo(currentPage);
          }
        }
        _textInserter?.enqueue(text, 'broadcast');
      }).catch(() => {
        _textInserter?.enqueue(text, 'broadcast');
      });
    } else {
      _textInserter?.enqueue(text, 'broadcast');
    }
  });

  console.log('[BackgroundService]: bridge revived');
}

export async function checkNativeBubblePermission(): Promise<boolean> {
  if (!FloatingBubbleBridge.isAvailable) return false;
  if (_nativeBubblePermission !== null) return _nativeBubblePermission;
  _nativeBubblePermission = await FloatingBubbleBridge.checkPermission();
  return _nativeBubblePermission;
}

export function invalidatePermissionCache(): void {
  _nativeBubblePermission = null;
}

export function requestNativeBubblePermission(): void {
  FloatingBubbleBridge.requestPermission();
}

function showAiBubble(statusText: string): void {
  if (!AiBubbleBridge.isAvailable) return;
  const ps = _textInserter?.getPageSize();
  if (ps) AiBubbleBridge.setPageHeight(ps.height);
  AiBubbleBridge.setActionButtons(resolveAiBubbleActions(_cachedAiBubbleActionIds));
  AiBubbleBridge.show(statusText);
}

function _getBubbleStatusText(): string {
  if (!_activeMode) return '';
  const base = _activeMode === 'nospacing'
    ? t('bubble_recv_nospacing')
    : t('bubble_recv_paragraph');

  if (_textInserter?.isWaitingPage()) {
    return base + ' ⊕';
  }

  const qLen = _textInserter?.getQueueLength() ?? 0;
  if (qLen > 0) return base + ` (${qLen})`;
  return base;
}

function syncBubbleActionsToNative(): void {
  if (!FloatingBubbleBridge.isAvailable) return;
  const actions = resolveBubbleActions(_cachedBubbleActionIds).filter(
    a => a.id !== 'lasso_ai' && a.id !== 'screenshot_ai'
  );
  FloatingBubbleBridge.setActionButtons(actions);
}

export async function refreshBubbleActions(): Promise<void> {
  _cachedBubbleActionIds = await loadBubbleActions();
  syncBubbleActionsToNative();
}

export async function refreshAiBubbleActions(): Promise<void> {
  _cachedAiBubbleActionIds = await loadAiBubbleActions();
  if (_aiActive && AiBubbleBridge.isAvailable) {
    AiBubbleBridge.setActionButtons(resolveAiBubbleActions(_cachedAiBubbleActionIds));
  }
}

export function ensureInit(): void {
  if (_initialized) return;
  _initialized = true;

  console.log('[BackgroundService]: ── initializing ──');

  loadLastMode().then(m => { _lastMode = m; });

  _textInserter = new TextInserter(
    (text, success, error) => {

      if (BroadcastBridge) {
        try { BroadcastBridge.sendAck(text, success, error); } catch (_) {}
      }
      if (_aiWaiting) {
        _aiWaiting = false;

        if (AiBubbleBridge.isAvailable) {
          AiBubbleBridge.show('Inserted');
          setTimeout(() => {
            if (!_aiWaiting) AiBubbleBridge.show(t('bubble_ai_ready'));
          }, 2000);
        }
      }

      if (_activeMode && FloatingBubbleBridge.isAvailable) {
        FloatingBubbleBridge.updateText(_getBubbleStatusText());
      }
    },
    (page, nextTop, source, isPageChange) => {

      try {
        if (BroadcastBridge?.sendInsertPosition) {
          BroadcastBridge.sendInsertPosition(page, nextTop);
        }
      } catch (e) {
        console.warn('[BackgroundService]: sendInsertPosition failed:', e);
      }

      if (FloatingBubbleBridge.isAvailable) {
        if (isPageChange) {
          const ps = _textInserter?.getPageSize();
          if (ps) { FloatingBubbleBridge.setPageHeight(ps.height); FloatingBubbleBridge.setPageWidth(ps.width); }
          const left = _textInserter?.getNextLeft();
          if (left !== undefined) {
            _aiPositionLocked = true;
            FloatingBubbleBridge.showAt(_getBubbleStatusText(), left, nextTop);
            console.log('[BackgroundService]: pageChange showAt left=', left, 'top=', nextTop);
          } else {
            _aiPositionLocked = false;
            FloatingBubbleBridge.show(_getBubbleStatusText());
            console.log('[BackgroundService]: pageChange show (bubble decides position)');
          }
        } else {
          FloatingBubbleBridge.updateText(_getBubbleStatusText());
        }
      }

    },

    (reason, detail) => {
      console.warn('[BackgroundService]: note context changed:', reason, detail);
      FileLogger.logEvent('NoteContextChanged', `${reason}: ${detail}`);
      const prevMode = _activeMode;
      _activeMode = null;
      clearAiTimeout();
      _pushActiveModes();
      emitInsertMode(null);

      if (_aiWaiting) {
        _aiWaiting = false;
        if (_aiActive && AiBubbleBridge.isAvailable) {
          AiBubbleBridge.updateText(t('bubble_ai_ready'));
        }
      }

      if (reason === 'note_switched' && prevMode && _textInserter) {
        console.log('[BackgroundService]: note switched, restarting inserter mode=', prevMode);
        _textInserter.clearQueue();
        _textInserter.start(prevMode).then(ok => {
          if (ok) {
            _activeMode = prevMode;
            _pushActiveModes();
            emitInsertMode(prevMode);
            if (FloatingBubbleBridge.isAvailable) {
              FloatingBubbleBridge.show(_getBubbleStatusText());
            }
          }
        });
      } else if (FloatingBubbleBridge.isAvailable) {
        const msg = reason === 'note_switched'
          ? t('note_switched_stop')
          : t('pages_changed_stop');
        FloatingBubbleBridge.show(msg);
        setTimeout(() => FloatingBubbleBridge.hide(), 3500);
      }
    },
  );

  reviveBridge();

  if (!_lsTextSub) {
    _lsTextSub = LocalSendBridge.onTextReceived((info: TextReceivedInfo) => {
      console.log('[BackgroundService]: onTextReceived(LocalSend) len=', info.text.length);
      FileLogger.logTextReceived('LocalSend', info.text);
      if (info._pendingId) LocalSendBridge.ackPendingText(info._pendingId);
      _textInserter?.enqueue(info.text, 'localsend');
    });
  }

  try { BroadcastBridge?.sendAlive?.(); } catch (_) {}

  DeviceEventEmitter.addListener('onLocalSendStopped', () => {
    console.log('[BackgroundService]: WiFi lost – LocalSend server stopped');
    _localSendStarted = false;
    DeviceEventEmitter.emit('localSendStateChanged', { running: false });
  });


  if (FloatingBubbleBridge.isAvailable) {
    const applyBubblePosition = (data: {
      pageY: number; pageX: number;
      pageBottomY: number; bubbleHeight: number;
      screenY: number; screenBottomY: number;
    }) => {

      const bottomY = (data.pageBottomY > data.pageY) ? data.pageBottomY : data.pageY;

      const insertTop = bottomY + 4;

      const insertLeft = Math.max(0, data.pageX);
      console.log('[BackgroundService]: applyBubblePosition raw=', JSON.stringify(data),
        'insertTop=', insertTop, 'insertLeft=', insertLeft);
      _textInserter?.forceSetNextTop(insertTop);
      _textInserter?.forceSetNextLeft(insertLeft);
    };

    FloatingBubbleBridge.onDragEnd((d) => { _aiPositionLocked = false; applyBubblePosition(d); });
    FloatingBubbleBridge.onLayout((d) => { if (!_aiPositionLocked) applyBubblePosition(d); });

    if (!_bubbleActionSub) {
      _bubbleActionSub = FloatingBubbleBridge.onBubbleAction(({ actionId }) => {
        console.log('[BackgroundService]: text bubble action:', actionId);
        if (actionId === 'lasso_send') {
          FloatingBubbleBridge.hide();
          FloatingToolbarBridge.showSendPanelFromBubble();
        } else if (actionId === 'screenshot_send') {
          handleScreenshotSend().catch(e => console.error('[BackgroundService]: bubble screenshot_send error:', e));
        } else if (actionId === 'toggle_spacing') {
          const cur = _activeMode;
          const next: InsertMode = cur === 'nospacing' ? 'paragraph' : 'nospacing';
          toggleMode(next);
        } else if (actionId === 'lasso_ai') {

          handleAiSend().catch(e => console.error('[BackgroundService]: bubble lasso_ai error:', e));
        } else if (actionId === 'screenshot_ai') {

          handleScreenshotAi().catch(e => console.error('[BackgroundService]: bubble screenshot_ai error:', e));
        } else {
          console.warn('[BackgroundService]: unknown text bubble actionId:', actionId);
        }
      });
    }

    if (AiBubbleBridge.isAvailable) {
      AiBubbleBridge.onAction(({ actionId }) => {
        console.log('[BackgroundService]: AI bubble action:', actionId);
        if (actionId === 'lasso_ai') {
          handleAiSend().catch(e => console.error('[BackgroundService]: AI lasso_ai error:', e));
        } else if (actionId === 'screenshot_ai') {
          handleScreenshotAi().catch(e => console.error('[BackgroundService]: AI screenshot_ai error:', e));
        } else if (actionId === 'pen_lasso_ai') {
          if (AiBubbleBridge.isAvailable) AiBubbleBridge.hide();
          if (FloatingBubbleBridge.isAvailable) FloatingBubbleBridge.hide();

          const bboxSub = FloatingToolbarBridge.onPenLassoBbox(() => {
            bboxSub.remove();
            cancelSub.remove();
            setTimeout(() => {
              restoreBubbleAfterLasso();
              handleAiSend().catch(e => console.error('[BackgroundService]: pen_lasso_ai handleAiSend error:', e));
            }, 300);
          });
          const cancelSub = FloatingToolbarBridge.onPenLassoCancel(() => {
            bboxSub.remove();
            cancelSub.remove();
            setTimeout(() => restoreBubbleAfterLasso(), 300);
          });

          const { PenLasso } = require('./PenTools');
          PenLasso.arm().catch((e: any) => {
            console.error('[BackgroundService]: PenLasso.arm error:', e);
            bboxSub.remove();
            cancelSub.remove();
            restoreBubbleAfterLasso();
          });
        } else if (actionId === 'copilot') {
          FloatingToolbar?.launchActivity(
            'com.dictation.server.relay',
            'com.dictation.server.MainActivity',
          ).catch((e: any) => console.warn('[BackgroundService]: copilot launchActivity failed:', e));
        } else if (actionId === 'cancel_ai') {
          stopAiMode();
        }
      });
    }

    if (!_nativePanelCloseSub) {
      _nativePanelCloseSub = FloatingToolbarBridge.onNativePanelClose(({ panel, cameFromBubble, screenshotBbox }) => {
        console.log('[BackgroundService]: onNativePanelClose panel=', panel, 'fromBubble=', cameFromBubble);

        if (screenshotBbox && _textInserter) {
          _textInserter.forceSetNextTop(screenshotBbox.bottom + AI_REPLY_GAP_PX);
          _textInserter.forceSetNextLeft(screenshotBbox.left);
          _aiPositionLocked = true;
          console.log('[BackgroundService]: screenshot insert pos set to left=', screenshotBbox.left, 'top=', screenshotBbox.bottom + AI_REPLY_GAP_PX);
        }
        if (cameFromBubble) {

          setTimeout(() => restoreBubbleAfterLasso(), 300);
        }
      });
    }
  }

  loadBubbleActions().then(ids => { _cachedBubbleActionIds = ids; });
  loadAiBubbleActions().then(ids => { _cachedAiBubbleActionIds = ids; });

  FileLogger.logEvent('BackgroundInit', 'ready');
}

export function getTextInserter(): TextInserter | null {
  return _textInserter;
}

export function getActiveMode(): InsertMode | null {
  return _activeMode;
}

export function getLastMode(): InsertMode {
  return _lastMode;
}

export function isAiWaiting(): boolean {
  return _aiWaiting;
}

export function isAiActive(): boolean {
  return _aiActive;
}

export async function toggleMode(target: InsertMode): Promise<InsertMode | null> {
  ensureInit();
  if (!_textInserter) return null;

  const cur = _activeMode;

  const isActuallyRunning = _textInserter.isRunning() || _textInserter.isPaused();
  if (cur === null || !isActuallyRunning) {
    await switchToMode(target);
  } else if (cur === target) {
    stopMode();
  } else {
    await switchToMode(target);
  }

  FileLogger.logEvent('ToggleMode', `target=${target} result=${_activeMode}`);
  return _activeMode;
}

export async function startMode(mode: InsertMode): Promise<boolean> {
  _lastMode = mode;
  saveLastMode(mode);
  return switchToMode(mode);
}

export function stopMode(): void {
  if (_textInserter) {
    const cur = _textInserter.getMode();
    if (cur) { _lastMode = cur; saveLastMode(cur); }
    _textInserter.clearQueue();
    _textInserter.stop(true);
  }
  _activeMode = null;
  if (FloatingBubbleBridge.isAvailable) {
    FloatingBubbleBridge.hide();
  }
  _pushActiveModes();
  emitInsertMode(null);
}

export async function startAiReceiveMode(): Promise<void> {
  ensureInit();
  if (_aiActive) {

    if (AiBubbleBridge.isAvailable) showAiBubble(t('bubble_ai_ready'));
    return;
  }
  _aiActive = true;
  _pushActiveModes();
  if (AiBubbleBridge.isAvailable) showAiBubble(t('bubble_ai_ready'));
}

export function stopAiMode(): void {
  _aiActive = false;
  _aiWaiting = false;
  _aiPositionLocked = false;
  clearAiTimeout();
  AiBubbleBridge.hide();
  _pushActiveModes();
}

export function stopAllModes(): void {
  stopMode();
  stopAiMode();
}

export async function switchToMode(mode: InsertMode): Promise<boolean> {
  ensureInit();
  if (!_textInserter) return false;

  const myVersion = ++_switchVersion;
  const previousMode = _lastMode;
  _lastMode = mode;
  saveLastMode(mode);

  if (_textInserter.isPaused()) {
    _textInserter.resume();
  }

  if (_textInserter.isRunning()) {
    const ok = _textInserter.switchMode(mode);
    if (ok) {
      _activeMode = mode;
      _pushActiveModes();
      emitInsertMode(mode);

      if (FloatingBubbleBridge.isAvailable) {
        FloatingBubbleBridge.updateText(_getBubbleStatusText());
      }
      return true;
    }
  }

  _textInserter.clearQueue();
  const ok = await _textInserter.start(mode, true);
  if (myVersion !== _switchVersion) return false;
  if (ok) {
    _textInserter.applyModeSwitchGap(previousMode, mode);
    _activeMode = mode;
    if (FloatingBubbleBridge.isAvailable) {
      const ps = _textInserter.getPageSize();
      if (ps) { FloatingBubbleBridge.setPageHeight(ps.height); FloatingBubbleBridge.setPageWidth(ps.width); }
    }
  } else {
    let retryOk = false;
    const retryDelays = [800, 2000];
    for (const delay of retryDelays) {
      await new Promise<void>(r => setTimeout(r, delay));
      if (myVersion !== _switchVersion) return false;
      if (_textInserter.isRunning()) { retryOk = true; break; }
      console.log('[BackgroundService]: retrying start, delay=', delay);
      FileLogger.logEvent('StartRetry', `mode=${mode} delay=${delay}`);
      retryOk = await _textInserter.start(mode, true);
      if (myVersion !== _switchVersion) return false;
      if (retryOk) break;
    }
    if (myVersion !== _switchVersion) return false;
    if (retryOk) {
      _textInserter.applyModeSwitchGap(previousMode, mode);
      _activeMode = mode;
      if (FloatingBubbleBridge.isAvailable) {
        const ps = _textInserter.getPageSize();
        if (ps) { FloatingBubbleBridge.setPageHeight(ps.height); FloatingBubbleBridge.setPageWidth(ps.width); }
      }
    } else {
      _activeMode = null;
    }
  }
  _pushActiveModes();
  emitInsertMode(_activeMode);

  if (_activeMode && FloatingBubbleBridge.isAvailable) {
    FloatingBubbleBridge.show(_getBubbleStatusText());
    syncBubbleActionsToNative();
  }

  return ok;
}

export async function handleAiSend(): Promise<void> {
  ensureInit();

  if (_aiSendInFlight) {
    console.log('[BackgroundService]: handleAiSend skipped (in-flight: extracting/OCR/sending)');
    FileLogger.logEvent('AiSendDebounce', 'inFlight');
    return;
  }
  if (_aiWaiting) {
    console.log('[BackgroundService]: handleAiSend skipped (already waiting for AI reply)');
    FileLogger.logEvent('AiSendDebounce', 'aiWaiting');
    return;
  }
  const now = Date.now();
  if (now - _lastAiSendAt < 3000) {
    console.log('[BackgroundService]: handleAiSend debounced (< 3s since last send)');
    FileLogger.logEvent('AiSendDebounce', `gap=${now - _lastAiSendAt}ms`);
    return;
  }
  _aiSendInFlight = true;

  reviveBridge();
  console.log('[BackgroundService]: ── handleAiSend ──');

  const onProgress = (stage: 'recognizing' | 'done') => {
    if (!AiBubbleBridge.isAvailable) return;
    try {
      if (stage === 'recognizing') AiBubbleBridge.updateText(t('bubble_ai_recognizing'));
      else AiBubbleBridge.updateText(t('bubble_ai_sending'));
    } catch (_) {}
  };

  try {

    let extracted = await LassoExtractor.consumeWarmed(onProgress);
    if (!extracted) {
      extracted = await LassoExtractor.extract(onProgress);
    }
    if (!extracted.text) {
      FileLogger.logEvent('AiSendAbort', 'no text');

      if (AiBubbleBridge.isAvailable) {
        AiBubbleBridge.updateText(t('bubble_ai_no_lasso'));
        setTimeout(() => {
          if (!_aiWaiting) AiBubbleBridge.updateText(t('bubble_ai_ready'));
        }, 2500);
      }
      return;
    }
    if (!BroadcastBridge) {
      console.error('[BackgroundService]: BroadcastBridge not available');
      return;
    }

    const sendTimeNow = Date.now();
    if (extracted.text === _lastAiSendText && sendTimeNow - _lastAiSendAt < AI_SAME_TEXT_DEBOUNCE_MS) {
      console.log('[BackgroundService]: handleAiSend dropped — same text within debounce window');
      FileLogger.logEvent('AiSendDebounce', `sameText gap=${sendTimeNow - _lastAiSendAt}ms len=${extracted.text.length}`);
      if (AiBubbleBridge.isAvailable) {
        AiBubbleBridge.updateText(t('bubble_ai_waiting'));
      }
      return;
    }

    reviveBridge();

    try {
      await Promise.race([
        (PluginCommAPI.setLassoBoxState as any)(2),
        new Promise((_, reject) => setTimeout(() => reject(new Error('setLassoBoxState timeout')), 3000)),
      ]);
      console.log('[BackgroundService]: lasso selection cleared');
    } catch (e) {
      console.warn('[BackgroundService]: setLassoBoxState failed (non-fatal):', e);
    }

    const anchorRect = extracted.lassoRect ?? extracted.lastTextBoxRect;

    if (_textInserter) {
      if (anchorRect) {
        _textInserter.setLassoAnchor(anchorRect.bottom + AI_REPLY_GAP_PX, anchorRect.left);
      }

      if (_textInserter.isPaused()) {
        _textInserter.resume();
      } else if (!_textInserter.isRunning()) {

        _textInserter.clearQueue();
        const ok = await _textInserter.start('nospacing');
        if (ok) {
          _activeMode = 'nospacing';
          _pushActiveModes();
          emitInsertMode('nospacing');

          if (FloatingBubbleBridge.isAvailable) {
            _aiPositionLocked = false;

            const ps = _textInserter.getPageSize();
            if (ps) { FloatingBubbleBridge.setPageHeight(ps.height); FloatingBubbleBridge.setPageWidth(ps.width); }

            if (anchorRect) {
              console.log('[BackgroundService]: showAt lasso anchor left=', anchorRect.left, 'bottom=', anchorRect.bottom);
              FloatingBubbleBridge.showAt(
                _getBubbleStatusText(),
                anchorRect.left,
                anchorRect.bottom,
              );
            } else {
              FloatingBubbleBridge.show(_getBubbleStatusText());
            }
            syncBubbleActionsToNative();
          }
        }
      } else {
        if (anchorRect) {
          _textInserter.forceSetNextTop(anchorRect.bottom + AI_REPLY_GAP_PX);
          _textInserter.forceSetNextLeft(anchorRect.left);
          console.log('[BackgroundService]: relocate running inserter to lasso anchor left=', anchorRect.left, 'top=', anchorRect.bottom + AI_REPLY_GAP_PX);

          if (FloatingBubbleBridge.isAvailable) {
            _aiPositionLocked = false;
            FloatingBubbleBridge.showAt(
              _getBubbleStatusText(),
              anchorRect.left,
              anchorRect.bottom,
            );
          }
        }
      }
    }

    try { BroadcastBridge.sendAlive?.(); } catch (_) {}
    BroadcastBridge.sendQuery(extracted.text);
    _aiWaiting = true;
    _lastAiSendAt = Date.now();
    _lastAiSendText = extracted.text;
    if (AiBubbleBridge.isAvailable) AiBubbleBridge.updateText(t('bubble_ai_waiting'));
    FileLogger.logEvent('SendToAI', `${extracted.text.length} chars`);

    clearAiTimeout();
    _aiTimeoutRef = setTimeout(() => {
      _aiTimeoutRef = null;
      if (_aiWaiting) {
        console.warn('[BackgroundService]: AI reply timeout');
        FileLogger.logEvent('AiTimeout', 'no reply in 90s');
        if (AiBubbleBridge.isAvailable) {
          AiBubbleBridge.updateText('AI 无响应，请检查中转站');
          setTimeout(() => {
            if (!_aiWaiting) return;
            AiBubbleBridge.updateText(t('bubble_ai_ready'));
            _aiWaiting = false;
          }, 5000);
        } else {
          _aiWaiting = false;
        }
      }
    }, 90_000);

  } catch (e) {
    console.error('[BackgroundService]: handleAiSend error:', e);
    FileLogger.logEvent('AiSendError', String(e));
  } finally {

    _aiSendInFlight = false;
  }
}

export async function extractLassoForSend() {
  ensureInit();
  return LassoExtractor.extract();
}

export async function restartServer(dest: string): Promise<void> {
  try { await LocalSendBridge.stopServer(); } catch (_) {}
  _localSendStarted = false;
  try {
    await LocalSendBridge.startServer({ alias: 'Supernote', port: 53317, dest, pin: '' });
    _localSendStarted = true;
  } catch (e) {
    console.log('[BackgroundService]: restartServer error:', e);
  }
}

export async function startLocalSend(): Promise<boolean> {
  if (_localSendStarted) return true;
  try {
    const status = await LocalSendBridge.getServerStatus();
    if (status?.running) {
      _localSendStarted = true;
      DeviceEventEmitter.emit('localSendStateChanged', { running: true });
      return true;
    }
    await LocalSendBridge.startServer({
      alias: 'Supernote', port: 53317,
      dest: '/sdcard/INBOX', pin: '',
    });
    _localSendStarted = true;
    console.log('[BackgroundService]: LocalSend server started');
    DeviceEventEmitter.emit('localSendStateChanged', { running: true });
    LocalSendBridge.scanForPeers().catch(() => {});
    return true;
  } catch (e) {
    console.warn('[BackgroundService]: startLocalSend error:', e);
    return false;
  }
}

export async function stopLocalSend(): Promise<void> {
  try { await LocalSendBridge.stopServer(); } catch (_) {}
  _localSendStarted = false;
  console.log('[BackgroundService]: LocalSend server stopped');
  DeviceEventEmitter.emit('localSendStateChanged', { running: false });
}

export function isLocalSendRunning(): boolean {
  return _localSendStarted;
}

export function pauseInsertion(): void {
  _textInserter?.pause();
}

export function resumeInsertion(): void {
  _textInserter?.resume();
}

export function isInsertionPaused(): boolean {
  return _textInserter?.isPaused() ?? false;
}

export function setInsertTop(pageTop: number): void {
  _textInserter?.forceSetNextTop(pageTop);
}

export function getInsertPosition(): { page: number; top: number } | null {
  if (!_textInserter) return null;
  return { page: _textInserter.getTargetPage(), top: _textInserter.getNextTop() };
}

export function getPageSize(): { width: number; height: number } | null {
  return _textInserter?.getPageSize() ?? null;
}

export async function flushPendingTexts(): Promise<number> {
  if (!BroadcastBridge?.flushPendingTexts || !_textInserter) return 0;
  try {
    const texts: string[] = await BroadcastBridge.flushPendingTexts();
    if (texts && texts.length > 0) {
      for (const tx of texts) _textInserter.enqueue(tx, 'broadcast');
      FileLogger.logEvent('FlushPending', `count=${texts.length}`);
    }
    return texts?.length ?? 0;
  } catch (e) {
    console.warn('[BackgroundService]: flushPendingTexts error:', e);
    return 0;
  }
}

export async function reviveIfNeeded(): Promise<void> {

  reviveBridge();
  if (!_textInserter || !_activeMode) return;
  if (!_textInserter.hasLiveTimers()) {
    const mode = _activeMode;
    _textInserter.clearQueue();
    const ok = await _textInserter.start(mode, true);
    if (ok) {
      _pushActiveModes();
      emitInsertMode(_activeMode);
    } else {

      console.warn('[BackgroundService]: reviveIfNeeded start() failed, keeping mode=', mode);
      FileLogger.logEvent('ReviveFailed', `mode=${mode}`);
    }
  }
}

export async function handleScreenshotAi(): Promise<void> {
  console.log('[BackgroundService] handleScreenshotAi START (native path)');

  FloatingToolbarBridge.showLassoScreenshotPanelFromBubble();
  FileLogger.logEvent('ScreenshotAi', 'native panel invoked');
}

export async function handleScreenshotSend(): Promise<void> {
  console.log('[BackgroundService] handleScreenshotSend START (native path)');
  FloatingToolbarBridge.showLassoScreenshotPanelForSendFromBubble();
  FileLogger.logEvent('ScreenshotSend', 'native panel invoked');
}

export function restoreBubbleAfterLasso(): void {
  if (_activeMode && FloatingBubbleBridge.isAvailable) {
    FloatingBubbleBridge.show(_getBubbleStatusText());
  }
  if (_aiActive && AiBubbleBridge.isAvailable) {
    const txt = _aiWaiting ? t('bubble_ai_waiting') : t('bubble_ai_ready');
    showAiBubble(txt);
  }
}

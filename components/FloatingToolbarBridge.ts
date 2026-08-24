

import { NativeModules, NativeEventEmitter } from 'react-native';

function getFT(): any {
  return NativeModules.FloatingToolbar;
}

export const ENABLE_DEBUG: boolean =
  getFT()?.getConstants?.()?.ENABLE_DEBUG ?? getFT()?.ENABLE_DEBUG ?? true;

export interface ToolTapEvent {
  toolId: string;
  toolAction: string;
  toolName: string;
}

let _emitter: NativeEventEmitter | null = null;

function getEmitter(): NativeEventEmitter | null {
  const mod = getFT();
  if (!mod) return null;
  if (!_emitter) {
    _emitter = new NativeEventEmitter(mod);
  }
  return _emitter;
}

const FloatingToolbarBridge = {

  get isAvailable(): boolean {
    return !!getFT();
  },

  showCurrent(): void {
    try {
      getFT()?.showCurrent();
    } catch (e) {
      console.warn('[FloatingToolbarBridge]: showCurrent failed:', e);
    }
  },

  toggleFromPluginButton(): void {
    try {
      getFT()?.toggleFromPluginButton();
    } catch (e) {
      console.warn('[FloatingToolbarBridge]: toggleFromPluginButton failed:', e);
    }
  },

  async inspectAndFlushHostEntry(): Promise<'none' | 'config' | 'localsend' | 'flushed'> {
    try {
      const result = await getFT()?.inspectAndFlushHostEntry();
      return result === 'config' || result === 'localsend' || result === 'flushed' ? result : 'none';
    } catch (e) {
      console.warn('[FloatingToolbarBridge]: inspectAndFlushHostEntry failed:', e);
      return 'none';
    }
  },

  reportHostButtonChannel(working: boolean): void {
    try {
      getFT()?.reportHostButtonChannel(working);
    } catch (e) {
      console.warn('[FloatingToolbarBridge]: reportHostButtonChannel failed:', e);
    }
  },

  reportHostButtonRaw(payload: string): void {
    try { getFT()?.reportHostButtonRaw?.(payload); } catch (_) {}
  },

  hide(): void {
    try {
      getFT()?.hide();
    } catch (e) {
      console.warn('[FloatingToolbarBridge]: hide failed:', e);
    }
  },

  restoreToolbar(): void {
    try {
      getFT()?.restoreToolbar();
    } catch (e) {
      console.warn('[FloatingToolbarBridge]: restoreToolbar failed:', e);
    }
  },

  paletteSnapshotsChanged(): void {
    try {
      getFT()?.paletteSnapshotsChanged();
    } catch (e) {
      console.warn('[FloatingToolbarBridge]: paletteSnapshotsChanged failed:', e);
    }
  },

  isShowingSync(): boolean {
    try {
      return getFT()?.isShowingSync() ?? false;
    } catch { return false; }
  },

  checkPendingOpenMainSync(): boolean {
    try {
      return getFT()?.checkPendingOpenMainSync() ?? false;
    } catch { return false; }
  },

  ackOpenMain(): void {
    try { getFT()?.ackOpenMain(); } catch (_) {}
  },

  getPendingScreenSync(): string {
    try { return getFT()?.getPendingScreenSync() ?? ''; } catch { return ''; }
  },

  ackPendingScreen(): void {
    try { getFT()?.ackPendingScreen(); } catch (_) {}
  },

  async deleteQueueFile(path: string): Promise<boolean> {
    try { return await getFT()?.deleteQueueFile(path) ?? false; } catch { return false; }
  },

  setLocale(loc: 'zh' | 'en'): void {
    try { getFT()?.setLocale(loc); } catch (_) {}
  },

  openPanel(screen: string): void {
    try { getFT()?.openPanel(screen); } catch (_) {}
  },

  openPluginSettingsAfterFileWritePermissionDenied(): void {
    try {
      getFT()?.openPluginSettingsAfterFileWritePermissionDenied?.();
    } catch (e) {
      console.warn('[FloatingToolbarBridge]: openPluginSettingsAfterFileWritePermissionDenied failed:', e);
    }
  },

  async requestFileReadPermission(): Promise<boolean> {
    try {
      return await getFT()?.requestFileReadPermission?.() ?? false;
    } catch (e) {
      console.warn('[FloatingToolbarBridge]: requestFileReadPermission failed:', e);
      return false;
    }
  },

  async requestFileWritePermission(): Promise<boolean> {
    try {
      return await getFT()?.requestFileWritePermission?.() ?? false;
    } catch (e) {
      console.warn('[FloatingToolbarBridge]: requestFileWritePermission failed:', e);
      return false;
    }
  },

  async requestFileDeletePermission(): Promise<boolean> {
    try {
      return await getFT()?.requestFileDeletePermission?.() ?? false;
    } catch (e) {
      console.warn('[FloatingToolbarBridge]: requestFileDeletePermission failed:', e);
      return false;
    }
  },

  async requestInternetPermission(): Promise<boolean> {
    try {
      return await getFT()?.requestInternetPermission?.() ?? false;
    } catch (e) {
      console.warn('[FloatingToolbarBridge]: requestInternetPermission failed:', e);
      return false;
    }
  },

  setLassoData(text: string, imagePathsJson: string, linkedFilesJson?: string): void {
    try {
      getFT()?.setLassoData(text, imagePathsJson, linkedFilesJson ?? '[]');
    } catch (e) {
      console.warn('[FloatingToolbarBridge]: setLassoData failed:', e);
    }
  },

  drainImageQueue(): string[] {
    try {
      const json = getFT()?.drainImageQueue();
      return json ? JSON.parse(json) : [];
    } catch (e) {
      return [];
    }
  },

  drainReceivedDeletes(): string[] {
    try {
      const json = getFT()?.drainReceivedDeletes();
      return json ? JSON.parse(json) : [];
    } catch (e) {
      return [];
    }
  },

  drainDocLinkQueue(): string[] {
    try {
      const json = getFT()?.drainDocLinkQueue();
      return json ? JSON.parse(json) : [];
    } catch (e) {
      return [];
    }
  },

  showImagePanel(): void {
    try {
      getFT()?.showImagePanel();
    } catch (e) {
      console.warn('[FloatingToolbarBridge]: showImagePanel failed:', e);
    }
  },

  showDocLinkPanel(currentFilePath?: string | null): void {
    try {
      getFT()?.showDocLinkPanel(currentFilePath ?? null);
    } catch (e) {
      console.warn('[FloatingToolbarBridge]: showDocLinkPanel failed:', e);
    }
  },

  showPalettePanel(infoJson: string): void {
    try {
      getFT()?.showPalettePanel(infoJson);
    } catch (e) {
      console.warn('[FloatingToolbarBridge]: showPalettePanel failed:', e);
    }
  },

  handleDocScreenshot(): void {
    try {
      getFT()?.handleDocScreenshot?.();
    } catch (e) {
      console.warn('[FloatingToolbarBridge]: handleDocScreenshot failed:', e);
    }
  },

  toggleScreenshotBubble(): void {
    try {
      getFT()?.toggleScreenshotBubble?.();
    } catch (e) {
      console.warn('[FloatingToolbarBridge]: toggleScreenshotBubble failed:', e);
    }
  },

  showSendPanelFromBubble(): void {
    try {
      getFT()?.showSendPanelFromBubble();
    } catch (e) {
      console.warn('[FloatingToolbarBridge]: showSendPanelFromBubble failed:', e);
    }
  },

  openSendPanelClipboardSync(): void {
    try {
      getFT()?.openSendPanelClipboardSync();
    } catch (e) {
      console.warn('[FloatingToolbarBridge]: openSendPanelClipboardSync failed:', e);
    }
  },

  showLassoScreenshotPanelFromBubble(): void {
    try {
      getFT()?.showLassoScreenshotPanelFromBubble();
    } catch (e) {
      console.warn('[FloatingToolbarBridge]: showLassoScreenshotPanelFromBubble failed:', e);
    }
  },

  showLassoScreenshotPanelForSendFromBubble(): void {
    try {
      getFT()?.showLassoScreenshotPanelForSendFromBubble();
    } catch (e) {
      console.warn('[FloatingToolbarBridge]: showLassoScreenshotPanelForSendFromBubble failed:', e);
    }
  },

  onToolModeExit(
    cb: (e: { toolId: string; toolAction: string }) => void
  ): { remove(): void } {
    const emitter = getEmitter();
    if (!emitter) return { remove() {} };
    return emitter.addListener('onToolModeExit', cb);
  },

  setActiveModes(modeIds: string[]): void {
    try { getFT()?.setActiveModes(JSON.stringify(modeIds)); } catch (_) {}
  },

  onToolTap(callback: (event: ToolTapEvent) => void): { remove(): void } {
    const emitter = getEmitter();
    if (!emitter) return { remove() {} };
    return emitter.addListener('onToolTap', callback);
  },

  onToolLongPress(callback: (event: { toolId: string; toolName: string }) => void): { remove(): void } {
    const emitter = getEmitter();
    if (!emitter) return { remove() {} };
    return emitter.addListener('onToolLongPress', callback);
  },

  onToolbarOpenMain(callback: () => void): { remove(): void } {
    const emitter = getEmitter();
    if (!emitter) return { remove() {} };
    return emitter.addListener('onToolbarOpenMain', () => callback());
  },

  onNativePanelClose(
    callback: (data: {
      panel: string;
      cameFromBubble: boolean;
      screenshotBbox?: { left: number; top: number; right: number; bottom: number };
    }) => void
  ): { remove(): void } {
    const emitter = getEmitter();
    if (!emitter) return { remove() {} };
    return emitter.addListener('onNativePanelClose', callback);
  },

  /** Note app entered/left the foreground (from the native foreground monitor). */
  onNoteForegroundChanged(callback: (data: { inNote: boolean }) => void): { remove(): void } {
    const emitter = getEmitter();
    if (!emitter) return { remove() {} };
    return emitter.addListener('onNoteForegroundChanged', callback);
  },

  onDestroyAll(callback: () => void): { remove(): void } {
    const emitter = getEmitter();
    if (!emitter) return { remove() {} };
    return emitter.addListener('onToolbarDestroyAll', () => callback());
  },

  updateTitleClips(filled: boolean[]): void {
    try { getFT()?.updateTitleClips(JSON.stringify(filled)); } catch (_) {}
  },

  onTitleClipTap(callback: (event: { slot: string }) => void): { remove(): void } {
    const emitter = getEmitter();
    if (!emitter) return { remove() {} };
    return emitter.addListener('onTitleClipTap', callback);
  },

  onTitleClipLongPress(callback: (event: { slot: string }) => void): { remove(): void } {
    const emitter = getEmitter();
    if (!emitter) return { remove() {} };
    return emitter.addListener('onTitleClipLongPress', callback);
  },

  onTitleLayerAction(callback: (event: { direction: 'prev' | 'next' }) => void): { remove(): void } {
    const emitter = getEmitter();
    if (!emitter) return { remove() {} };
    return emitter.addListener('onTitleLayerAction', callback);
  },

  onAppendPageAction(callback: () => void): { remove(): void } {
    const emitter = getEmitter();
    if (!emitter) return { remove() {} };
    return emitter.addListener('onAppendPageAction', callback);
  },

  showPenLassoOverlay(): void {
    try { getFT()?.showPenLassoOverlay(); } catch (e) {
      console.warn('[FloatingToolbarBridge]: showPenLassoOverlay failed:', e);
    }
  },

  onPenLassoBbox(callback: (event: { left: number; top: number; right: number; bottom: number }) => void): { remove(): void } {
    const emitter = getEmitter();
    if (!emitter) return { remove() {} };
    return emitter.addListener('onPenLassoBbox', callback);
  },

  onPenLassoCancel(callback: () => void): { remove(): void } {
    const emitter = getEmitter();
    if (!emitter) return { remove() {} };
    return emitter.addListener('onPenLassoCancel', callback);
  },

};

export default FloatingToolbarBridge;

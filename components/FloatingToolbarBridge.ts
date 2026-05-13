import { NativeModules, NativeEventEmitter } from 'react-native';

const { FloatingToolbar } = NativeModules;

export interface ToolItem {
  id: string;
  name: string;
  icon: string;
  action: string;

  latches?: boolean;
}

const LATCHING_ACTIONS = new Set<string>([
  'insert_text',
  'text_recv_nospacing',
  'text_recv_paragraph',
  'send_ai',
]);

export function withLatchFlag(tool: Omit<ToolItem, 'latches'>): ToolItem {
  return { ...tool, latches: LATCHING_ACTIONS.has(tool.action) };
}

export interface ToolTapEvent {
  toolId: string;
  toolAction: string;
  toolName: string;
}

let _emitter: NativeEventEmitter | null = null;

function getEmitter(): NativeEventEmitter | null {
  if (!FloatingToolbar) return null;
  if (!_emitter) {
    _emitter = new NativeEventEmitter(FloatingToolbar);
  }
  return _emitter;
}

const FloatingToolbarBridge = {

  isAvailable: !!FloatingToolbar,

  show(tools: ToolItem[]): void {
    try {
      FloatingToolbar?.show(JSON.stringify(tools.map(withLatchFlag)));
    } catch (e) {
      console.warn('[FloatingToolbarBridge]: show failed:', e);
    }
  },

  hide(): void {
    try {
      FloatingToolbar?.hide();
    } catch (e) {
      console.warn('[FloatingToolbarBridge]: hide failed:', e);
    }
  },

  updateTools(tools: ToolItem[]): void {
    try {
      FloatingToolbar?.updateTools(JSON.stringify(tools.map(withLatchFlag)));
    } catch (e) {
      console.warn('[FloatingToolbarBridge]: updateTools failed:', e);
    }
  },

  collapse(): void {
    try {
      FloatingToolbar?.collapse();
    } catch (e) {
      console.warn('[FloatingToolbarBridge]: collapse failed:', e);
    }
  },

  expand(): void {
    try {
      FloatingToolbar?.expand();
    } catch (e) {
      console.warn('[FloatingToolbarBridge]: expand failed:', e);
    }
  },

  restoreToolbar(): void {
    try {
      FloatingToolbar?.restoreToolbar();
    } catch (e) {
      console.warn('[FloatingToolbarBridge]: restoreToolbar failed:', e);
    }
  },

  setSide(side: 'left' | 'right'): void {
    try {
      FloatingToolbar?.setSide(side);
    } catch (e) {
      console.warn('[FloatingToolbarBridge]: setSide failed:', e);
    }
  },

  async isShowing(): Promise<boolean> {
    try {
      return await FloatingToolbar?.isShowing() ?? false;
    } catch { return false; }
  },

  isShowingSync(): boolean {
    try {
      return FloatingToolbar?.isShowingSync() ?? false;
    } catch { return false; }
  },

  async checkPendingOpenMain(): Promise<boolean> {
    try {
      return await FloatingToolbar?.checkPendingOpenMain() ?? false;
    } catch { return false; }
  },

  checkPendingOpenMainSync(): boolean {
    try {
      return FloatingToolbar?.checkPendingOpenMainSync() ?? false;
    } catch { return false; }
  },

  ackOpenMain(): void {
    try { FloatingToolbar?.ackOpenMain(); } catch (_) {}
  },

  setPendingScreen(name: string): void {
    try { FloatingToolbar?.setPendingScreen(name); } catch (_) {}
  },

  getPendingScreenSync(): string {
    try { return FloatingToolbar?.getPendingScreenSync() ?? ''; } catch { return ''; }
  },

  ackPendingScreen(): void {
    try { FloatingToolbar?.ackPendingScreen(); } catch (_) {}
  },

  async deleteQueueFile(path: string): Promise<boolean> {
    try { return await FloatingToolbar?.deleteQueueFile(path) ?? false; } catch { return false; }
  },

  openPluginView(): void {
    try { FloatingToolbar?.openPluginView(); } catch (_) {}
  },

  forceClosePluginView(): void {
    try { FloatingToolbar?.forceClosePluginView(); } catch (_) {}
  },

  dumpNativePluginManagerMethods(): void {
    try { FloatingToolbar?.dumpNativePluginManagerMethods(); } catch (_) {}
  },

  dumpPluginAppFields(): void {
    try { FloatingToolbar?.dumpPluginAppFields(); } catch (_) {}
  },

  showCaptureToast(message?: string): void {
    try { FloatingToolbar?.showCaptureToast(message ?? ''); } catch (_) {}
  },

  hideCaptureToast(): void {
    try { FloatingToolbar?.hideCaptureToast(); } catch (_) {}
  },

  openPanel(screen: string): void {
    try { FloatingToolbar?.openPanel(screen); } catch (_) {}
  },

  async checkPermission(): Promise<boolean> {
    try {
      return await FloatingToolbar?.checkOverlayPermission() ?? false;
    } catch { return false; }
  },

  async getStickerDir(): Promise<string | null> {
    try {
      return await FloatingToolbar?.getStickerDir() ?? null;
    } catch { return null; }
  },

  async ensureStickerDir(): Promise<string | null> {
    try {
      return await FloatingToolbar?.ensureStickerDir() ?? null;
    } catch { return null; }
  },

  requestPermission(): void {
    try {
      FloatingToolbar?.requestOverlayPermission();
    } catch (e) {
      console.warn('[FloatingToolbarBridge]: requestPermission failed:', e);
    }
  },

  setLassoData(text: string, imagePathsJson: string, linkedFilesJson?: string): void {
    try {
      FloatingToolbar?.setLassoData(text, imagePathsJson, linkedFilesJson ?? '[]');
    } catch (e) {
      console.warn('[FloatingToolbarBridge]: setLassoData failed:', e);
    }
  },

  showNativeImagePanel(): void {
    try {
      FloatingToolbar?.showNativeImagePanel();
    } catch (e) {
      console.warn('[FloatingToolbarBridge]: showNativeImagePanel failed:', e);
    }
  },

  showNativeDocPanel(): void {
    try {
      FloatingToolbar?.showNativeDocPanel();
    } catch (e) {
      console.warn('[FloatingToolbarBridge]: showNativeDocPanel failed:', e);
    }
  },

  handleDocScreenshot(): void {
    try {
      (FloatingToolbar as any)?.handleDocScreenshot?.();
    } catch (e) {
      console.warn('[FloatingToolbarBridge]: handleDocScreenshot failed:', e);
    }
  },

  showNativeSendPanelFromBubble(): void {
    try {
      FloatingToolbar?.showNativeSendPanelFromBubble();
    } catch (e) {
      console.warn('[FloatingToolbarBridge]: showNativeSendPanelFromBubble failed:', e);
    }
  },

  showNativeLassoScreenshotPanelFromBubble(): void {
    try {
      FloatingToolbar?.showNativeLassoScreenshotPanelFromBubble();
    } catch (e) {
      console.warn('[FloatingToolbarBridge]: showNativeLassoScreenshotPanelFromBubble failed:', e);
    }
  },

  showNativeLassoScreenshotPanelForSendFromBubble(): void {
    try {
      FloatingToolbar?.showNativeLassoScreenshotPanelForSendFromBubble();
    } catch (e) {
      console.warn('[FloatingToolbarBridge]: showNativeLassoScreenshotPanelForSendFromBubble failed:', e);
    }
  },

  onToolModeExit(
    cb: (e: { toolId: string; toolAction: string }) => void
  ): { remove(): void } {
    const emitter = getEmitter();
    if (!emitter) return { remove() {} };
    return emitter.addListener('onToolModeExit', cb);
  },

  async queryActiveMode(): Promise<string | null> {
    try { return await FloatingToolbar?.queryActiveMode() ?? null; } catch { return null; }
  },

  async exitActiveMode(): Promise<void> {

    try { await FloatingToolbar?.exitActiveMode(); } catch (_) {}
  },

  async exitMode(toolId: string): Promise<void> {

    try { await FloatingToolbar?.exitMode?.(toolId); } catch (_) {}
  },

  setActiveModes(modeIds: string[]): void {
    try { FloatingToolbar?.setActiveModes(JSON.stringify(modeIds)); } catch (_) {}
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

  onDragEnd(callback: (pos: { x: number; y: number }) => void): { remove(): void } {
    const emitter = getEmitter();
    if (!emitter) return { remove() {} };
    return emitter.addListener('onToolbarDragEnd', callback);
  },

  onToolbarOpenMain(callback: () => void): { remove(): void } {
    const emitter = getEmitter();
    if (!emitter) return { remove() {} };
    return emitter.addListener('onToolbarOpenMain', () => callback());
  },

  onTap(callback: () => void): { remove(): void } {
    const emitter = getEmitter();
    if (!emitter) return { remove() {} };
    return emitter.addListener('onToolbarTap', () => callback());
  },

  onPermissionDenied(callback: () => void): { remove(): void } {
    const emitter = getEmitter();
    if (!emitter) return { remove() {} };
    return emitter.addListener('onToolbarPermissionDenied', () => callback());
  },

  onCollapseChange(callback: (data: { collapsed: boolean; side: string }) => void): { remove(): void } {
    const emitter = getEmitter();
    if (!emitter) return { remove() {} };
    return emitter.addListener('onToolbarCollapseChange', callback);
  },

  onNativePanelOpen(callback: (data: { panel: string }) => void): { remove(): void } {
    const emitter = getEmitter();
    if (!emitter) return { remove() {} };
    return emitter.addListener('onNativePanelOpen', callback);
  },

  onNativePanelClose(
    callback: (data: { panel: string; cameFromBubble: boolean }) => void
  ): { remove(): void } {
    const emitter = getEmitter();
    if (!emitter) return { remove() {} };
    return emitter.addListener('onNativePanelClose', callback);
  },

  setOrientation(value: 'horizontal' | 'vertical'): void {
    try { (FloatingToolbar as any)?.setOrientation(value); } catch (_) {}
  },

  getOrientationSync(): 'horizontal' | 'vertical' {
    try {
      const v = (FloatingToolbar as any)?.getOrientationSync?.();
      return v === 'vertical' ? 'vertical' : 'horizontal';
    } catch { return 'horizontal'; }
  },

  hideNativePanels(): void {
    try { (FloatingToolbar as any)?.hideAllNativePanelsFromJs?.(); } catch (_) {}
  },

  closeAllForSettings(): void {
    try { (FloatingToolbar as any)?.closeAllForSettings?.(); } catch (_) {}
  },

  destroyAll(): void {
    try { FloatingToolbar?.destroyAllFromJs(); } catch (_) {}
  },

  isPenLockedSync(): boolean {
    try { return (FloatingToolbar as any)?.isPenLockedSync?.() ?? false; } catch { return false; }
  },

  setPenLocked(locked: boolean): void {
    try { FloatingToolbar?.setPenLocked(locked); } catch (_) {}
  },

  openPenLockView(): void {
    try { FloatingToolbar?.openPenLockView(); } catch (_) {}
  },

  disablePenBlock(): void {
    try { (FloatingToolbar as any)?.disablePenBlock?.(); } catch (_) {}
  },

  engagePenLock(): void {
    try { (FloatingToolbar as any)?.engagePenLock?.(); } catch (_) {}
  },

  releasePenLock(): void {
    try { (FloatingToolbar as any)?.releasePenLock?.(); } catch (_) {}
  },

  updateTitleClips(filled: boolean[]): void {
    try { FloatingToolbar?.updateTitleClips(JSON.stringify(filled)); } catch (_) {}
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

  onPenLockRequest(callback: () => void): { remove(): void } {
    const emitter = getEmitter();
    if (!emitter) return { remove() {} };
    return emitter.addListener('onPenLockRequest', callback);
  },

  onPenLockRelease(callback: () => void): { remove(): void } {
    const emitter = getEmitter();
    if (!emitter) return { remove() {} };
    return emitter.addListener('onPenLockRelease', callback);
  },

  onTitlePenLassoAction(callback: () => void): { remove(): void } {
    const emitter = getEmitter();
    if (!emitter) return { remove() {} };
    return emitter.addListener('onTitlePenLassoAction', callback);
  },

  showPenLassoOverlay(): void {
    try { FloatingToolbar?.showPenLassoOverlay(); } catch (e) {
      console.warn('[FloatingToolbarBridge]: showPenLassoOverlay failed:', e);
    }
  },

  dismissPenLassoOverlay(): void {
    try { FloatingToolbar?.dismissPenLassoOverlay(); } catch (e) {
      console.warn('[FloatingToolbarBridge]: dismissPenLassoOverlay failed:', e);
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

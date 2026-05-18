import { NativeModules, NativeEventEmitter } from 'react-native';

const { FloatingBubble } = NativeModules;

let _bubbleEmitter: NativeEventEmitter | null = null;
function getBubbleEmitter(): NativeEventEmitter | null {
  if (!FloatingBubble) return null;
  if (!_bubbleEmitter) _bubbleEmitter = new NativeEventEmitter(FloatingBubble);
  return _bubbleEmitter;
}

const FloatingBubbleBridge = {
  isAvailable: !!FloatingBubble,

  show(statusText: string, mode?: string): void {
    try { FloatingBubble?.show(statusText, mode ?? ''); } catch (e) {
      console.warn('[FloatingBubbleBridge]: show failed:', e);
    }
  },

  hide(): void {
    try { FloatingBubble?.hide(); } catch (e) {
      console.warn('[FloatingBubbleBridge]: hide failed:', e);
    }
  },

  updateText(text: string): void {
    try { FloatingBubble?.updateText(text); } catch (e) {
      console.warn('[FloatingBubbleBridge]: updateText failed:', e);
    }
  },

  setPageHeight(height: number): void {
    try { FloatingBubble?.setPageHeight(height); } catch (_) {}
  },

  setScreenHeight(height: number): void {
    try { FloatingBubble?.setScreenHeight(height); } catch (_) {}
  },

  setPositionY(pageY: number): void {
    try { FloatingBubble?.setPositionY(pageY); } catch (e) {
      console.warn('[FloatingBubbleBridge]: setPositionY failed:', e);
    }
  },

  async isShowing(): Promise<boolean> {
    try { return await FloatingBubble?.isShowing() ?? false; } catch { return false; }
  },

  async checkPermission(): Promise<boolean> {
    try { return await FloatingBubble?.checkOverlayPermission() ?? false; } catch { return false; }
  },

  requestPermission(): void {
    try { FloatingBubble?.requestOverlayPermission(); } catch (e) {
      console.warn('[FloatingBubbleBridge]: requestPermission failed:', e);
    }
  },

  setActionButtons(buttons: { id: string; icon: string; label: string }[]): void {
    try { FloatingBubble?.setActionButtons(JSON.stringify(buttons)); } catch (e) {
      console.warn('[FloatingBubbleBridge]: setActionButtons failed:', e);
    }
  },

  onTap(callback: () => void): { remove(): void } {
    const em = getBubbleEmitter();
    if (!em) return { remove() {} };
    return em.addListener('onBubbleTap', () => callback());
  },

  onDragEnd(callback: (data: { screenY: number; pageY: number }) => void): { remove(): void } {
    const em = getBubbleEmitter();
    if (!em) return { remove() {} };
    return em.addListener('onBubbleDragEnd', (event) => {
      callback({ screenY: event.screenY, pageY: event.pageY });
    });
  },

  onPermissionDenied(callback: () => void): { remove(): void } {
    const em = getBubbleEmitter();
    if (!em) return { remove() {} };
    return em.addListener('onBubblePermissionDenied', () => callback());
  },

  onBubbleAction(callback: (data: { actionId: string }) => void): { remove(): void } {
    const em = getBubbleEmitter();
    if (!em) return { remove() {} };
    return em.addListener('onBubbleAction', callback);
  },
};

const { AiBubble } = NativeModules;

let _aiEmitter: NativeEventEmitter | null = null;
function getAiEmitter(): NativeEventEmitter | null {
  if (!AiBubble) return null;
  if (!_aiEmitter) _aiEmitter = new NativeEventEmitter(AiBubble);
  return _aiEmitter;
}

const AiBubbleBridge = {
  isAvailable: !!AiBubble,

  show(statusText: string): void {
    try { AiBubble?.show(statusText); } catch (e) {
      console.warn('[AiBubbleBridge]: show failed:', e);
    }
  },

  hide(): void {
    try { AiBubble?.hide(); } catch (e) {
      console.warn('[AiBubbleBridge]: hide failed:', e);
    }
  },

  updateText(text: string): void {
    try { AiBubble?.updateText(text); } catch (e) {
      console.warn('[AiBubbleBridge]: updateText failed:', e);
    }
  },

  setPageHeight(height: number): void {
    try { AiBubble?.setPageHeight(height); } catch (_) {}
  },

  setScreenHeight(height: number): void {
    try { AiBubble?.setScreenHeight(height); } catch (_) {}
  },

  setActionButtons(buttons: { id: string; icon: string; label: string }[]): void {
    try { AiBubble?.setActionButtons(JSON.stringify(buttons)); } catch (e) {
      console.warn('[AiBubbleBridge]: setActionButtons failed:', e);
    }
  },

  async isShowing(): Promise<boolean> {
    try { return await AiBubble?.isShowing() ?? false; } catch (_) { return false; }
  },

  isShowingSync(): boolean {
    try { return AiBubble?.isShowingSync() ?? false; } catch (_) { return false; }
  },

  async checkPermission(): Promise<boolean> {
    try { return await AiBubble?.checkOverlayPermission() ?? false; } catch (_) { return false; }
  },

  requestPermission(): void {
    try { AiBubble?.requestOverlayPermission(); } catch (_) {}
  },

  onTap(cb: () => void): { remove(): void } {
    return getAiEmitter()?.addListener('onAiBubbleTap', cb) ?? { remove() {} };
  },

  onLongPress(cb: () => void): { remove(): void } {
    return getAiEmitter()?.addListener('onAiBubbleLongPress', cb) ?? { remove() {} };
  },

  onDragEnd(cb: (e: { screenY: number; pageY: number }) => void): { remove(): void } {
    return getAiEmitter()?.addListener('onAiBubbleDragEnd', cb) ?? { remove() {} };
  },

  onAction(cb: (e: { actionId: string }) => void): { remove(): void } {
    return getAiEmitter()?.addListener('onAiBubbleAction', cb) ?? { remove() {} };
  },

  onPermissionDenied(cb: () => void): { remove(): void } {
    return getAiEmitter()?.addListener('onAiBubblePermissionDenied', cb) ?? { remove() {} };
  },
};

export default FloatingBubbleBridge;
export { AiBubbleBridge };

import { NativeModules, NativeEventEmitter } from 'react-native';

const { AiBubble } = NativeModules;

let _emitter: NativeEventEmitter | null = null;
function emitter(): NativeEventEmitter | null {
  if (!AiBubble) return null;
  if (!_emitter) _emitter = new NativeEventEmitter(AiBubble);
  return _emitter;
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
    return emitter()?.addListener('onAiBubbleTap', cb) ?? { remove() {} };
  },

  onLongPress(cb: () => void): { remove(): void } {
    return emitter()?.addListener('onAiBubbleLongPress', cb) ?? { remove() {} };
  },

  onDragEnd(cb: (e: { screenY: number; pageY: number }) => void): { remove(): void } {
    return emitter()?.addListener('onAiBubbleDragEnd', cb) ?? { remove() {} };
  },

  onAction(cb: (e: { actionId: string }) => void): { remove(): void } {
    return emitter()?.addListener('onAiBubbleAction', cb) ?? { remove() {} };
  },

  onPermissionDenied(cb: () => void): { remove(): void } {
    return emitter()?.addListener('onAiBubblePermissionDenied', cb) ?? { remove() {} };
  },
};

export default AiBubbleBridge;

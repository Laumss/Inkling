import { NativeModules, NativeEventEmitter, Platform } from 'react-native';

const { FloatingBubble } = NativeModules;

const EVENT_TAP = 'onBubbleTap';
const EVENT_DRAG_END = 'onBubbleDragEnd';

let _emitter: NativeEventEmitter | null = null;

function getEmitter(): NativeEventEmitter | null {
  if (!FloatingBubble) return null;
  if (!_emitter) {
    _emitter = new NativeEventEmitter(FloatingBubble);
  }
  return _emitter;
}

const FloatingBubbleBridge = {

  isAvailable: !!FloatingBubble,

  show(statusText: string, mode?: string): void {
    try {
      FloatingBubble?.show(statusText, mode ?? '');
    } catch (e) {
      console.warn('[FloatingBubbleBridge]: show failed:', e);
    }
  },

  hide(): void {
    try {
      FloatingBubble?.hide();
    } catch (e) {
      console.warn('[FloatingBubbleBridge]: hide failed:', e);
    }
  },

  updateText(text: string): void {
    try {
      FloatingBubble?.updateText(text);
    } catch (e) {
      console.warn('[FloatingBubbleBridge]: updateText failed:', e);
    }
  },

  setPageHeight(height: number): void {
    try {
      FloatingBubble?.setPageHeight(height);
    } catch (e) {}
  },

  setScreenHeight(height: number): void {
    try {
      FloatingBubble?.setScreenHeight(height);
    } catch (e) {}
  },

  setPositionY(pageY: number): void {
    try {
      FloatingBubble?.setPositionY(pageY);
    } catch (e) {
      console.warn('[FloatingBubbleBridge]: setPositionY failed:', e);
    }
  },

  async isShowing(): Promise<boolean> {
    try {
      return await FloatingBubble?.isShowing() ?? false;
    } catch {
      return false;
    }
  },

  async checkPermission(): Promise<boolean> {
    try {
      return await FloatingBubble?.checkOverlayPermission() ?? false;
    } catch {
      return false;
    }
  },

  requestPermission(): void {
    try {
      FloatingBubble?.requestOverlayPermission();
    } catch (e) {
      console.warn('[FloatingBubbleBridge]: requestPermission failed:', e);
    }
  },

  setActionButtons(buttons: { id: string; icon: string; label: string }[]): void {
    try {
      FloatingBubble?.setActionButtons(JSON.stringify(buttons));
    } catch (e) {
      console.warn('[FloatingBubbleBridge]: setActionButtons failed:', e);
    }
  },

  onTap(callback: () => void): { remove(): void } {
    const emitter = getEmitter();
    if (!emitter) return { remove() {} };
    const sub = emitter.addListener(EVENT_TAP, () => callback());
    return sub;
  },

  onDragEnd(callback: (data: { screenY: number; pageY: number }) => void): { remove(): void } {
    const emitter = getEmitter();
    if (!emitter) return { remove() {} };
    const sub = emitter.addListener(EVENT_DRAG_END, (event) => {
      callback({ screenY: event.screenY, pageY: event.pageY });
    });
    return sub;
  },

  onPermissionDenied(callback: () => void): { remove(): void } {
    const emitter = getEmitter();
    if (!emitter) return { remove() {} };
    const sub = emitter.addListener('onBubblePermissionDenied', () => callback());
    return sub;
  },

  onBubbleAction(callback: (data: { actionId: string }) => void): { remove(): void } {
    const emitter = getEmitter();
    if (!emitter) return { remove() {} };
    return emitter.addListener('onBubbleAction', callback);
  },
};

export default FloatingBubbleBridge;

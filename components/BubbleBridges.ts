

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
    console.log('[BUBBLE-DBG/JS] bridge.show statusText=', statusText, 'mode=', mode, 'nativeAvailable=', !!FloatingBubble);
    try { FloatingBubble?.show(statusText, mode ?? ''); } catch (e) {
      console.warn('[FloatingBubbleBridge]: show failed:', e);
    }
  },

  showAt(statusText: string, pageX: number, pageY: number, mode?: string): void {
    console.log('[BUBBLE-DBG/JS] bridge.showAt statusText=', statusText, 'page=', pageX, pageY, 'mode=', mode, 'nativeAvailable=', !!FloatingBubble);
    try { FloatingBubble?.showAt(statusText, Math.round(pageX), Math.round(pageY), mode ?? ''); } catch (e) {
      console.warn('[FloatingBubbleBridge]: showAt failed:', e);
    }
  },

  hide(): void {
    try { FloatingBubble?.hide(); } catch (e) {
      console.warn('[FloatingBubbleBridge]: hide failed:', e);
    }
  },

  /** Toggle the "text card waiting to be inserted" texture on the receiver bubble. */
  setPending(pending: boolean): void {
    try { FloatingBubble?.setPending(pending); } catch (_) {}
  },

  setPageHeight(height: number): void {
    try { FloatingBubble?.setPageHeight(height); } catch (_) {}
  },

  setPageWidth(width: number): void {
    try { FloatingBubble?.setPageWidth(width); } catch (_) {}
  },

  onTap(callback: (data: {
    screenY: number; pageY: number;
    screenX: number; pageX: number;
    screenBottomY: number; pageBottomY: number;
    bubbleHeight: number;
  }) => void): { remove(): void } {
    const em = getBubbleEmitter();
    if (!em) return { remove() {} };
    return em.addListener('onBubbleTap', (event) => {
      const bh = event?.bubbleHeight ?? 0;
      const pageY = event?.pageY ?? 0;
      const pbY = (typeof event?.pageBottomY === 'number' && event.pageBottomY > pageY)
        ? event.pageBottomY
        : pageY + bh;
      callback({
        screenY: event?.screenY ?? 0,
        pageY,
        screenX: event?.screenX ?? 0,
        pageX: event?.pageX ?? 0,
        screenBottomY: event?.screenBottomY ?? event?.screenY ?? 0,
        pageBottomY: pbY,
        bubbleHeight: bh,
      });
    });
  },

  onDragEnd(callback: (data: {
    screenY: number; pageY: number;
    screenX: number; pageX: number;
    screenBottomY: number; pageBottomY: number;
    bubbleHeight: number;
  }) => void): { remove(): void } {
    const em = getBubbleEmitter();
    if (!em) return { remove() {} };
    return em.addListener('onBubbleDragEnd', (event) => {
      console.log('[BubbleBridge]: onBubbleDragEnd raw keys=', Object.keys(event), 'pageBottomY=', event.pageBottomY, 'bubbleHeight=', event.bubbleHeight);
      const screenH = event.screenBottomY ?? event.screenY;
      const bh = event.bubbleHeight ?? 0;

      const pbY = (typeof event.pageBottomY === 'number' && event.pageBottomY > event.pageY)
        ? event.pageBottomY
        : event.pageY + bh;
      callback({
        screenY: event.screenY,
        pageY: event.pageY,
        screenX: event.screenX ?? 0,
        pageX: event.pageX ?? 0,
        screenBottomY: screenH,
        pageBottomY: pbY,
        bubbleHeight: bh,
      });
    });
  },

  onLayout(callback: (data: {
    screenY: number; pageY: number;
    screenX: number; pageX: number;
    screenBottomY: number; pageBottomY: number;
    bubbleHeight: number;
  }) => void): { remove(): void } {
    const em = getBubbleEmitter();
    if (!em) return { remove() {} };
    return em.addListener('onBubbleLayout', (event) => {
      const bh = event.bubbleHeight ?? 0;
      const pbY = (typeof event.pageBottomY === 'number' && event.pageBottomY > event.pageY)
        ? event.pageBottomY
        : event.pageY + bh;
      callback({
        screenY: event.screenY,
        pageY: event.pageY,
        screenX: event.screenX ?? 0,
        pageX: event.pageX ?? 0,
        screenBottomY: event.screenBottomY ?? event.screenY,
        pageBottomY: pbY,
        bubbleHeight: bh,
      });
    });
  },

};

const { AiBubble } = NativeModules;

let _aiEmitter: NativeEventEmitter | null = null;
function getAiEmitter(): NativeEventEmitter | null {
  if (!AiBubble) return null;
  if (!_aiEmitter) _aiEmitter = new NativeEventEmitter(AiBubble);
  return _aiEmitter;
}

type AiBubbleMode = 'ai' | 'voice';

const AiBubbleBridge = {
  isAvailable: !!AiBubble,

  show(statusText: string, mode: AiBubbleMode = 'ai'): void {
    try { AiBubble?.show(statusText, mode); } catch (e) {
      console.warn('[AiBubbleBridge]: show failed:', e);
    }
  },

  /** Open the AI entry as the collected single-button form (does not start AIRelay). */
  showCollapsed(statusText: string): void {
    try { AiBubble?.showCollapsed(statusText); } catch (e) {
      console.warn('[AiBubbleBridge]: showCollapsed failed:', e);
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

  setActionButtons(buttons: { id: string; icon: string; label: string }[]): void {
    try { AiBubble?.setActionButtons(JSON.stringify(buttons)); } catch (e) {
      console.warn('[AiBubbleBridge]: setActionButtons failed:', e);
    }
  },

  /** Relay inbox previews → capsule bars under the status pill. */
  setInboxItems(
    items: { id: string; title: string; preview: string; source: string; time: number; pinned?: boolean }[],
  ): void {
    try { AiBubble?.setInboxItems(JSON.stringify(items)); } catch (e) {
      console.warn('[AiBubbleBridge]: setInboxItems failed:', e);
    }
  },

  onLongPress(cb: () => void): { remove(): void } {
    return getAiEmitter()?.addListener('onAiBubbleLongPress', cb) ?? { remove() {} };
  },

  onAction(cb: (e: { actionId: string }) => void): { remove(): void } {
    return getAiEmitter()?.addListener('onAiBubbleAction', cb) ?? { remove() {} };
  },

  /** Tap on the collected single-button form: start AIRelay, then expand. */
  onExpand(cb: () => void): { remove(): void } {
    return getAiEmitter()?.addListener('onAiBubbleExpand', cb) ?? { remove() {} };
  },

  /** Tap on a capsule bar: open that message's translucent detail page. */
  onCapsuleTap(cb: (e: { id: string }) => void): { remove(): void } {
    return getAiEmitter()?.addListener('onAiCapsuleTap', cb) ?? { remove() {} };
  },

  /** Long-press on a capsule bar: insert that message directly. */
  onCapsuleLongPress(cb: (e: { id: string }) => void): { remove(): void } {
    return getAiEmitter()?.addListener('onAiCapsuleLongPress', cb) ?? { remove() {} };
  },
};

const { PaletteBubble } = NativeModules;

let _paletteEmitter: NativeEventEmitter | null = null;
function getPaletteEmitter(): NativeEventEmitter | null {
  if (!PaletteBubble) return null;
  if (!_paletteEmitter) _paletteEmitter = new NativeEventEmitter(PaletteBubble);
  return _paletteEmitter;
}

const PaletteBubbleBridge = {
  isAvailable: !!PaletteBubble,

  show(): void {
    try { PaletteBubble?.show(); } catch (e) {
      console.warn('[PaletteBubbleBridge]: show failed:', e);
    }
  },

  onSlotTap(cb: (e: { slotIndex: number; color: string; thickness: number; penType: number }) => void): { remove(): void } {
    return getPaletteEmitter()?.addListener('onPaletteBubbleSlotTap', cb) ?? { remove() {} };
  },
};

export default FloatingBubbleBridge;
export { AiBubbleBridge, PaletteBubbleBridge };

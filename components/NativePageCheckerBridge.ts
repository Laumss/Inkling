import { NativeModules, NativeEventEmitter, EmitterSubscription } from 'react-native';

const { NativePageChecker } = NativeModules;

const EVENT_TICK = 'onPageCheckTick';

let _emitter: NativeEventEmitter | null = null;

function getEmitter(): NativeEventEmitter | null {
  if (!NativePageChecker) return null;
  if (!_emitter) {
    _emitter = new NativeEventEmitter(NativePageChecker);
  }
  return _emitter;
}

const NativePageCheckerBridge = {

  isAvailable: !!NativePageChecker,

  startPolling(intervalMs: number): void {
    try {
      NativePageChecker?.startPolling(intervalMs);
    } catch (e) {
      console.warn('[NativePageCheckerBridge]: startPolling failed:', e);
    }
  },

  stopPolling(): void {
    try {
      NativePageChecker?.stopPolling();
    } catch (e) {
      console.warn('[NativePageCheckerBridge]: stopPolling failed:', e);
    }
  },

  onTick(callback: () => void): { remove(): void } {
    const emitter = getEmitter();
    if (!emitter) {
      console.warn('[NativePageCheckerBridge]: NativePageChecker not available, tick events disabled');
      return { remove() {} };
    }
    const sub: EmitterSubscription = emitter.addListener(EVENT_TICK, () => {
      try {
        callback();
      } catch (e) {
        console.warn('[NativePageCheckerBridge]: tick callback error:', e);
      }
    });
    return sub;
  },
};

export default NativePageCheckerBridge;

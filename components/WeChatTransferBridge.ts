import { NativeModules, NativeEventEmitter } from 'react-native';

const { WeChatTransferModule } = NativeModules;

export interface WeChatStatus {
  registered: boolean;
  bound: boolean;
  polling: boolean;
  ilinkImSdkId: string;
  receiveDir: string;
  deviceName: string;
}

export interface QrCodeInfo {
  ticket: string;
  url: string;
  qrCodeUrl: string;
  ilinkImSdkId: string;
}

export interface WeChatFileInfo {
  name: string;
  path: string;
  size: number;
  id: string;
  isImage: boolean;
}

export interface WeChatLinkInfo {
  name: string;
  url: string;
  id: string;
}

class WeChatTransferBridge {
  private emitter: NativeEventEmitter;

  constructor() {
    this.emitter = new NativeEventEmitter(WeChatTransferModule);
  }

  async register(): Promise<string> {
    return await WeChatTransferModule.register();
  }

  async getQrCodeUrl(): Promise<QrCodeInfo> {
    return await WeChatTransferModule.getQrCodeUrl();
  }

  async downloadQrImage(qrUrl: string): Promise<string> {
    return await WeChatTransferModule.downloadQrImage(qrUrl);
  }

  async checkBind(): Promise<boolean> {
    return await WeChatTransferModule.checkBind();
  }

  async startPolling(): Promise<string> {
    return await WeChatTransferModule.startPolling();
  }

  async stopPolling(): Promise<string> {
    return await WeChatTransferModule.stopPolling();
  }

  async unbind(): Promise<string> {
    return await WeChatTransferModule.unbind();
  }

  async getStatus(): Promise<WeChatStatus> {
    return await WeChatTransferModule.getStatus();
  }

  onPollingStarted(cb: () => void) {
    return this.emitter.addListener('onWeChatPollingStarted', cb);
  }

  onPollingStopped(cb: () => void) {
    return this.emitter.addListener('onWeChatPollingStopped', cb);
  }

  onFileReceived(cb: (info: WeChatFileInfo) => void) {
    return this.emitter.addListener('onWeChatFileReceived', cb);
  }

  onLinkReceived(cb: (info: WeChatLinkInfo) => void) {
    return this.emitter.addListener('onWeChatLinkReceived', cb);
  }

  onDownloadStarted(cb: (info: { name: string; id: string; fileSize: string }) => void) {
    return this.emitter.addListener('onWeChatDownloadStarted', cb);
  }

  onDownloadError(cb: (info: { name: string; error: string }) => void) {
    return this.emitter.addListener('onWeChatDownloadError', cb);
  }

  onUnbound(cb: () => void) {
    return this.emitter.addListener('onWeChatUnbound', cb);
  }
}

export default new WeChatTransferBridge();

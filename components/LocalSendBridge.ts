import { NativeModules, NativeEventEmitter, Platform } from 'react-native';

const { LocalSendModule } = NativeModules;

export interface ServerConfig {
  alias: string;
  port?: number;
  dest?: string;
  pin?: string;
}

export interface ServerStatus {
  running: boolean;
  ip: string;
  port: number;
  alias: string;
  receiveDir: string;
  activeSessions: number;
}

export interface TextReceivedInfo {
  _pendingId?: string;
  text: string;
  fileName: string;
}

class LocalSendBridge {
  private emitter: NativeEventEmitter;

  constructor() {
    this.emitter = new NativeEventEmitter(LocalSendModule);
  }

  async startServer(config: ServerConfig): Promise<string> {
    return await LocalSendModule.startServer(config);
  }

  async stopServer(): Promise<string> {
    return await LocalSendModule.stopServer();
  }

  async isWifiConnected(): Promise<boolean> {
    return await LocalSendModule.isWifiConnected();
  }

  async getServerStatus(): Promise<ServerStatus> {
    return await LocalSendModule.getServerStatus();
  }

  onTextReceived(callback: (info: TextReceivedInfo) => void) {
    return this.emitter.addListener('onTextReceived', callback);
  }

  async flushPendingTexts(): Promise<TextReceivedInfo[]> {
    return await LocalSendModule.flushPendingTexts();
  }

  ackPendingText(id: string): void {
    LocalSendModule.ackPendingText(id);
  }

  async scanForPeers(): Promise<string> {
    return await LocalSendModule.scanForPeers();
  }
}

export default new LocalSendBridge();

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

export interface ReceivedFile {
  name: string;
  path: string;
  size: number;
  modified: number;
  isImage: boolean;
}

export interface PeerInfo {
  alias: string;
  ip: string;
  port: number;
  deviceType: string;
  fingerprint: string;
}

export interface TransferInfo {
  sender: string;
  fileCount: number;
  sessionId: string;
  fileNames: string[];
}

export interface ProgressInfo {
  fileName: string;
  received: number;
  total: number;
  percent: number;
}

export interface FileReceivedInfo {
  fileName: string;
  path: string;
  size: number;
  isImage: boolean;
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

  async getServerStatus(): Promise<ServerStatus> {
    return await LocalSendModule.getServerStatus();
  }

  async getReceivedFiles(): Promise<ReceivedFile[]> {
    return await LocalSendModule.getReceivedFiles();
  }

  onServerStarted(callback: (info: { ip: string; port: number; alias: string }) => void) {
    return this.emitter.addListener('onServerStarted', callback);
  }

  onServerStopped(callback: () => void) {
    return this.emitter.addListener('onServerStopped', callback);
  }

  onServerError(callback: (info: { error: string }) => void) {
    return this.emitter.addListener('onServerError', callback);
  }

  onPeerFound(callback: (peer: PeerInfo) => void) {
    return this.emitter.addListener('onPeerFound', callback);
  }

  onTransferStarted(callback: (info: TransferInfo) => void) {
    return this.emitter.addListener('onTransferStarted', callback);
  }

  onTransferProgress(callback: (info: ProgressInfo) => void) {
    return this.emitter.addListener('onTransferProgress', callback);
  }

  onFileReceived(callback: (info: FileReceivedInfo) => void) {
    return this.emitter.addListener('onFileReceived', callback);
  }

  onTransferComplete(callback: (info: { sessionId: string }) => void) {
    return this.emitter.addListener('onTransferComplete', callback);
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

  async getDiscoveredPeers(): Promise<PeerInfo[]> {
    return await LocalSendModule.getDiscoveredPeers();
  }

  async scanForPeers(): Promise<string> {
    return await LocalSendModule.scanForPeers();
  }

  async sendText(ip: string, port: number, text: string): Promise<string> {
    return await LocalSendModule.sendText(ip, port, text);
  }

  async sendFile(ip: string, port: number, filePath: string): Promise<string> {
    return await LocalSendModule.sendFile(ip, port, filePath);
  }

  onSendStarted(callback: (info: { sessionId: string; fileCount: number; targetIp: string }) => void) {
    return this.emitter.addListener('onSendStarted', callback);
  }

  onSendProgress(callback: (info: { fileId: string; fileName: string; percent: number }) => void) {
    return this.emitter.addListener('onSendProgress', callback);
  }

  onSendComplete(callback: (info: { sessionId: string }) => void) {
    return this.emitter.addListener('onSendComplete', callback);
  }

  onSendError(callback: (info: { error: string }) => void) {
    return this.emitter.addListener('onSendError', callback);
  }
}

export default new LocalSendBridge();

import { ConnectionState, RemoteCommand, RemoteResponse, TvState } from '../types/protocol';
import { PairingService, PairedTvInfo } from './pairing';

type StateListener = (state: ConnectionState) => void;
type TvStateListener = (tvState: TvState) => void;

class NetworkServiceImpl {
  private ws: WebSocket | null = null;
  private connectionState: ConnectionState = 'disconnected';
  private tvInfo: PairedTvInfo | null = null;
  private stateListeners: Set<StateListener> = new Set();
  private tvStateListeners: Set<TvStateListener> = new Set();

  private reconnectTimer: number | null = null;
  private heartbeatTimer: number | null = null;
  private reconnectDelay = 1000;
  private pendingRequests: Map<string, { resolve: (res: RemoteResponse) => void; reject: (err: any) => void; timer: number }> = new Map();
  private currentTvState: TvState = {
    tv_name: 'Mango TV',
    volume: 50,
    is_muted: false,
    media_state: 'unknown'
  };

  constructor() {
    this.tvInfo = PairingService.getSavedTv();
  }

  public subscribeState(listener: StateListener): () => void {
    this.stateListeners.add(listener);
    listener(this.connectionState);
    return () => this.stateListeners.delete(listener);
  }

  public subscribeTvState(listener: TvStateListener): () => void {
    this.tvStateListeners.add(listener);
    listener(this.currentTvState);
    return () => this.tvStateListeners.delete(listener);
  }

  public getConnectionState(): ConnectionState {
    return this.connectionState;
  }

  public getTvState(): TvState {
    return this.currentTvState;
  }

  public getPairedTv(): PairedTvInfo | null {
    return this.tvInfo;
  }

  public setPairedTv(info: PairedTvInfo) {
    this.tvInfo = info;
    this.disconnect();
    this.connect();
  }

  private setConnectionState(newState: ConnectionState) {
    if (this.connectionState !== newState) {
      this.connectionState = newState;
      this.stateListeners.forEach((fn) => fn(newState));
    }
  }

  public connect() {
    if (!this.tvInfo) {
      this.tvInfo = PairingService.getSavedTv();
    }

    if (!this.tvInfo || !this.tvInfo.authToken) {
      this.setConnectionState('pairing_required');
      return;
    }

    if (this.ws && (this.ws.readyState === WebSocket.OPEN || this.ws.readyState === WebSocket.CONNECTING)) {
      return;
    }

    this.setConnectionState('connecting');
    const wsUrl = `ws://${this.tvInfo.ip}:${this.tvInfo.port}/ws`;

    try {
      this.ws = new WebSocket(wsUrl);

      this.ws.onopen = () => {
        this.reconnectDelay = 1000;
        this.startHeartbeat();
        // Send initial auth/ping
        this.sendCommand('ping')
          .then(() => {
            this.setConnectionState('connected');
          })
          .catch(() => {
            // If ping fails due to bad auth, trigger pairing
            this.setConnectionState('pairing_required');
          });
      };

      this.ws.onmessage = (event) => {
        try {
          const data = JSON.parse(event.data);
          if (data.type === 'event' && data.event === 'tv_state') {
            this.currentTvState = { ...this.currentTvState, ...data.data };
            this.tvStateListeners.forEach((fn) => fn(this.currentTvState));
          } else if (data.id && this.pendingRequests.has(data.id)) {
            const req = this.pendingRequests.get(data.id)!;
            window.clearTimeout(req.timer);
            this.pendingRequests.delete(data.id);
            req.resolve(data);
          }
        } catch (_) {}
      };

      this.ws.onclose = () => {
        this.cleanup();
        this.setConnectionState('disconnected');
        this.scheduleReconnect();
      };

      this.ws.onerror = () => {
        this.cleanup();
        this.setConnectionState('disconnected');
        this.scheduleReconnect();
      };
    } catch (e) {
      this.setConnectionState('disconnected');
      this.scheduleReconnect();
    }
  }

  public disconnect() {
    if (this.reconnectTimer) {
      window.clearTimeout(this.reconnectTimer);
      this.reconnectTimer = null;
    }
    this.cleanup();
    this.setConnectionState('disconnected');
  }

  private cleanup() {
    this.stopHeartbeat();
    if (this.ws) {
      this.ws.onopen = null;
      this.ws.onmessage = null;
      this.ws.onclose = null;
      this.ws.onerror = null;
      try {
        this.ws.close();
      } catch (_) {}
      this.ws = null;
    }
    // Reject all pending requests
    this.pendingRequests.forEach((req) => {
      window.clearTimeout(req.timer);
      req.reject(new Error('Connection closed'));
    });
    this.pendingRequests.clear();
  }

  private scheduleReconnect() {
    if (this.reconnectTimer) return;
    this.reconnectTimer = window.setTimeout(() => {
      this.reconnectTimer = null;
      if (this.connectionState !== 'connected') {
        this.reconnectDelay = Math.min(this.reconnectDelay * 1.5, 8000);
        this.connect();
      }
    }, this.reconnectDelay);
  }

  private startHeartbeat() {
    this.stopHeartbeat();
    this.heartbeatTimer = window.setInterval(() => {
      if (this.ws && this.ws.readyState === WebSocket.OPEN) {
        this.sendCommand('ping').catch(() => {});
      }
    }, 5000);
  }

  private stopHeartbeat() {
    if (this.heartbeatTimer) {
      window.clearInterval(this.heartbeatTimer);
      this.heartbeatTimer = null;
    }
  }

  public sendCommand(action: string, payload: Record<string, any> = {}): Promise<RemoteResponse> {
    return new Promise((resolve, reject) => {
      if (!this.ws || this.ws.readyState !== WebSocket.OPEN) {
        reject(new Error('TV Remote is disconnected'));
        return;
      }

      const id = 'cmd_' + Math.random().toString(36).substring(2, 9) + '_' + Date.now();
      const command: RemoteCommand = {
        id,
        type: 'command',
        action,
        payload,
        auth_token: this.tvInfo?.authToken
      };

      const timer = window.setTimeout(() => {
        this.pendingRequests.delete(id);
        reject(new Error('Command timeout'));
      }, 4000);

      this.pendingRequests.set(id, { resolve, reject, timer });

      try {
        this.ws.send(JSON.stringify(command));
      } catch (err) {
        window.clearTimeout(timer);
        this.pendingRequests.delete(id);
        reject(err);
      }
    });
  }

  // Convenience methods
  public navigate(direction: 'up' | 'down' | 'left' | 'right' | 'select' | 'back' | 'home' | 'menu') {
    return this.sendCommand('navigate', { direction });
  }

  public media(mediaAction: 'play_pause' | 'play' | 'pause' | 'fast_forward' | 'rewind' | 'next' | 'previous') {
    return this.sendCommand('media', { media_action: mediaAction });
  }

  public volume(volumeAction: 'up' | 'down' | 'mute') {
    return this.sendCommand('volume', { volume_action: volumeAction });
  }

  public launchApp(packageName: string) {
    return this.sendCommand('launch_app', { package: packageName });
  }

  public textInput(text: string) {
    return this.sendCommand('text_input', { text });
  }
}

export const Network = new NetworkServiceImpl();

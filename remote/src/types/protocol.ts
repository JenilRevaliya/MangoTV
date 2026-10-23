export type ConnectionState = 'disconnected' | 'connecting' | 'connected' | 'pairing_required';
export type RemoteMode = 'buttons' | 'swipe';

export interface RemoteCommand {
  id: string;
  type: 'command' | 'pair' | 'ping';
  action: string;
  payload?: Record<string, any>;
  auth_token?: string;
}

export interface RemoteResponse {
  id: string;
  status: 'ok' | 'error';
  result?: any;
  message?: string;
}

export interface TvState {
  tv_name: string;
  device_model?: string;
  volume: number;
  is_muted: boolean;
  active_app?: string;
  active_package?: string;
  media_state?: 'playing' | 'paused' | 'stopped' | 'unknown';
}

export interface FavoriteApp {
  id: string;
  name: string;
  package: string;
  iconType: 'netflix' | 'youtube' | 'prime' | 'mangoplay' | 'custom';
}

// Pairing and Local Storage Credentials Manager
export interface PairedTvInfo {
  ip: string;
  port: number;
  name: string;
  authToken: string;
  model?: string;
  lastConnected: number;
}

const STORAGE_KEY = 'mango_remote_paired_tv';

export class PairingService {
  public static getSavedTv(): PairedTvInfo | null {
    try {
      const saved = localStorage.getItem(STORAGE_KEY);
      if (saved) return JSON.parse(saved);
    } catch (_) {}
    return null;
  }

  public static savePairedTv(info: PairedTvInfo): void {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(info));
  }

  public static clearPairedTv(): void {
    localStorage.removeItem(STORAGE_KEY);
  }

  public static getDefaultHost(): string {
    // If opened directly from http://<tv-ip>:8090, use window.location.hostname
    if (window.location.hostname && window.location.hostname !== 'localhost' && window.location.hostname !== '127.0.0.1') {
      return window.location.hostname;
    }
    const saved = this.getSavedTv();
    return saved ? saved.ip : '192.168.1.3';
  }

  public static async pairWithPin(ip: string, port: number, pin: string, clientName: string = 'Mango Remote'): Promise<PairedTvInfo> {
    const url = `http://${ip}:${port}/api/pair`;
    const response = await fetch(url, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json'
      },
      body: JSON.stringify({
        pin: pin.trim(),
        client_name: clientName,
        client_id: 'pwa_' + Math.random().toString(36).substring(2, 10)
      })
    });

    if (!response.ok) {
      const errJson = await response.json().catch(() => ({}));
      throw new Error(errJson.message || 'Pairing failed. Check PIN and ensure TV is on.');
    }

    const data = await response.json();
    if (data.status !== 'ok' || !data.auth_token) {
      throw new Error(data.message || 'Invalid PIN or pairing expired.');
    }

    const info: PairedTvInfo = {
      ip,
      port,
      name: data.tv_name || 'Mango TV',
      model: data.device_model,
      authToken: data.auth_token,
      lastConnected: Date.now()
    };

    this.savePairedTv(info);
    return info;
  }
}

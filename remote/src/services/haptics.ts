// Semantic Haptics & Tactile Audio Service
class HapticsServiceImpl {
  private hapticsEnabled: boolean = true;
  private audioEnabled: boolean = true;
  private audioCtx: AudioContext | null = null;

  constructor() {
    this.hapticsEnabled = localStorage.getItem('mango_haptics_enabled') !== 'false';
    this.audioEnabled = localStorage.getItem('mango_audio_feedback') !== 'false';
  }

  public setHapticsEnabled(enabled: boolean) {
    this.hapticsEnabled = enabled;
    localStorage.setItem('mango_haptics_enabled', String(enabled));
  }

  public isHapticsEnabled(): boolean {
    return this.hapticsEnabled;
  }

  public setAudioEnabled(enabled: boolean) {
    this.audioEnabled = enabled;
    localStorage.setItem('mango_audio_feedback', String(enabled));
  }

  public isAudioEnabled(): boolean {
    return this.audioEnabled;
  }

  public buttonPressed() {
    this.vibrate(12);
    this.playTactileClick(180, 0.015);
  }

  public buttonReleased() {
    // Subtle release if needed
  }

  public selectionChanged() {
    this.vibrate(8);
    this.playTactileClick(240, 0.01);
  }

  public gestureRecognized() {
    this.vibrate(18);
    this.playTactileClick(220, 0.02);
  }

  public longPressStarted() {
    this.vibrate([20, 40, 25]);
    this.playTactileClick(140, 0.035);
  }

  public confirm() {
    this.vibrate([15, 30, 20]);
    this.playTactileClick(320, 0.03);
  }

  public error() {
    this.vibrate([40, 60, 40]);
    this.playTactileClick(110, 0.04);
  }

  private vibrate(pattern: number | number[]) {
    if (!this.hapticsEnabled) return;
    try {
      if ('vibrate' in navigator && typeof navigator.vibrate === 'function') {
        navigator.vibrate(pattern);
      }
    } catch (_) {}
  }

  private playTactileClick(frequency: number, duration: number) {
    if (!this.audioEnabled) return;
    try {
      const AudioCtxClass = window.AudioContext || (window as any).webkitAudioContext;
      if (!AudioCtxClass) return;

      if (!this.audioCtx) {
        this.audioCtx = new AudioCtxClass();
      }

      if (this.audioCtx.state === 'suspended') {
        this.audioCtx.resume().catch(() => {});
      }

      const osc = this.audioCtx.createOscillator();
      const gain = this.audioCtx.createGain();

      osc.type = 'triangle';
      osc.frequency.setValueAtTime(frequency, this.audioCtx.currentTime);
      osc.frequency.exponentialRampToValueAtTime(frequency * 0.4, this.audioCtx.currentTime + duration);

      gain.gain.setValueAtTime(0.06, this.audioCtx.currentTime);
      gain.gain.exponentialRampToValueAtTime(0.0001, this.audioCtx.currentTime + duration);

      osc.connect(gain);
      gain.connect(this.audioCtx.destination);

      osc.start();
      osc.stop(this.audioCtx.currentTime + duration);
    } catch (_) {}
  }
}

export const Haptics = new HapticsServiceImpl();

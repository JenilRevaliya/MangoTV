import React, { useState } from 'react';
import { Haptics } from '../services/haptics';
import { PairingService } from '../services/pairing';
import { Network } from '../services/network';
import { IconClose } from './Icons';

interface SettingsModalProps {
  isOpen: boolean;
  onClose: () => void;
  onOpenInstallGuide: () => void;
  onForgetTv: () => void;
}

export const SettingsModal: React.FC<SettingsModalProps> = ({
  isOpen,
  onClose,
  onOpenInstallGuide,
  onForgetTv
}) => {
  const [hapticsEnabled, setHapticsEnabled] = useState(Haptics.isHapticsEnabled());
  const [audioEnabled, setAudioEnabled] = useState(Haptics.isAudioEnabled());

  if (!isOpen) return null;

  const paired = PairingService.getSavedTv();

  const handleToggleHaptics = () => {
    const next = !hapticsEnabled;
    setHapticsEnabled(next);
    Haptics.setHapticsEnabled(next);
    if (next) Haptics.confirm();
  };

  const handleToggleAudio = () => {
    const next = !audioEnabled;
    setAudioEnabled(next);
    Haptics.setAudioEnabled(next);
    if (next) Haptics.confirm();
  };

  const handleForget = () => {
    Haptics.error();
    PairingService.clearPairedTv();
    Network.disconnect();
    onForgetTv();
    onClose();
  };

  return (
    <div className="modal-overlay" onClick={onClose}>
      <div className="modal-sheet" onClick={(e) => e.stopPropagation()}>
        <div className="modal-header">
          <span className="modal-title">Settings</span>
          <button className="modal-close-btn" onClick={onClose} type="button" aria-label="Close">
            <IconClose size={16} />
          </button>
        </div>

        {/* Tactile Feedback Settings */}
        <div style={{ display: 'flex', flexDirection: 'column', gap: '14px' }}>
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
            <div>
              <div style={{ fontSize: '15px', fontWeight: 500 }}>Vibration Haptics</div>
              <div style={{ fontSize: '12px', color: 'var(--text-tertiary)' }}>Tactile vibration on touch</div>
            </div>
            <input
              type="checkbox"
              checked={hapticsEnabled}
              onChange={handleToggleHaptics}
              style={{ width: '20px', height: '20px', accentColor: 'var(--accent-mango)' }}
            />
          </div>

          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
            <div>
              <div style={{ fontSize: '15px', fontWeight: 500 }}>Audio Clicks</div>
              <div style={{ fontSize: '12px', color: 'var(--text-tertiary)' }}>Subtle mechanical click sound</div>
            </div>
            <input
              type="checkbox"
              checked={audioEnabled}
              onChange={handleToggleAudio}
              style={{ width: '20px', height: '20px', accentColor: 'var(--accent-mango)' }}
            />
          </div>
        </div>

        {/* Connected TV Info */}
        <div style={{ background: 'var(--bg-surface)', padding: '14px', borderRadius: '14px', border: '1px solid var(--border-subtle)' }}>
          <div style={{ fontSize: '12px', color: 'var(--text-tertiary)', textTransform: 'uppercase', letterSpacing: '0.5px' }}>
            Current TV
          </div>
          <div style={{ fontSize: '16px', fontWeight: 600, marginTop: '4px' }}>
            {paired ? paired.name : 'Not Paired'}
          </div>
          {paired && (
            <div style={{ fontSize: '13px', color: 'var(--text-secondary)', marginTop: '2px' }}>
              {paired.ip}:{paired.port} {paired.model ? `(${paired.model})` : ''}
            </div>
          )}
        </div>

        {/* Installation guide button */}
        <button
          className="primary-btn"
          type="button"
          style={{ background: 'var(--bg-surface-elevated)', color: 'var(--text-primary)' }}
          onClick={() => {
            Haptics.buttonPressed();
            onClose();
            onOpenInstallGuide();
          }}
        >
          Add to Home Screen Guide
        </button>

        {paired && (
          <button
            className="primary-btn"
            type="button"
            style={{ background: 'rgba(255, 59, 48, 0.15)', color: '#FF3B30' }}
            onClick={handleForget}
          >
            Disconnect & Forget TV
          </button>
        )}
      </div>
    </div>
  );
};

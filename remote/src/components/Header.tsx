import React from 'react';
import { ConnectionState, TvState } from '../types/protocol';
import { IconMic, IconKeyboard, IconGear } from './Icons';
import { Haptics } from '../services/haptics';

interface HeaderProps {
  connectionState: ConnectionState;
  tvState: TvState;
  onOpenPairing: () => void;
  onOpenKeyboard: () => void;
  onOpenVoice: () => void;
  onOpenSettings: () => void;
}

export const Header: React.FC<HeaderProps> = ({
  connectionState,
  tvState,
  onOpenPairing,
  onOpenKeyboard,
  onOpenVoice,
  onOpenSettings
}) => {
  const getStatusLabel = () => {
    switch (connectionState) {
      case 'connected':
        return 'Connected';
      case 'connecting':
        return 'Connecting...';
      case 'pairing_required':
      case 'disconnected':
      default:
        return 'Connect';
    }
  };

  const getStatusDotClass = () => {
    switch (connectionState) {
      case 'connected':
        return 'status-dot connected';
      case 'connecting':
        return 'status-dot connecting';
      case 'pairing_required':
      case 'disconnected':
      default:
        return 'status-dot disconnected';
    }
  };

  return (
    <header className="remote-header">
      <div className="brand-section">
        <svg width="22" height="22" viewBox="0 0 40 40" style={{ flexShrink: 0 }}>
          <rect width="40" height="40" rx="10" fill="#FF9500" />
          <path d="M20 7 C23 3, 29 2, 31 3 C31 8, 27 11, 20 11 Z" fill="#2C2C2E" />
          <circle cx="20" cy="23" r="10" fill="#FFFFFF" />
          <circle cx="20" cy="23" r="5" fill="#FF9500" />
        </svg>
        <span className="brand-title">{tvState.tv_name || 'Mango TV'}</span>
        <button
          className="status-badge"
          onClick={() => {
            Haptics.selectionChanged();
            if (connectionState !== 'connected') {
              onOpenPairing();
            }
          }}
          type="button"
          aria-label="Connection Status"
        >
          <span className={getStatusDotClass()} />
          <span style={{ color: connectionState === 'connected' ? 'var(--text-primary)' : 'var(--text-secondary)' }}>
            {getStatusLabel()}
          </span>
        </button>
      </div>

      <div className="header-actions">
        <button
          className="header-icon-btn"
          onClick={() => {
            Haptics.buttonPressed();
            onOpenVoice();
          }}
          type="button"
          aria-label="Voice Search"
        >
          <IconMic size={18} />
        </button>
        <button
          className="header-icon-btn"
          onClick={() => {
            Haptics.buttonPressed();
            onOpenKeyboard();
          }}
          type="button"
          aria-label="Text Input"
        >
          <IconKeyboard size={18} />
        </button>
        <button
          className="header-icon-btn"
          onClick={() => {
            Haptics.buttonPressed();
            onOpenSettings();
          }}
          type="button"
          aria-label="Settings"
        >
          <IconGear size={18} />
        </button>
      </div>
    </header>
  );
};

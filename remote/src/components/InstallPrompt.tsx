import React, { useState, useEffect } from 'react';
import { Haptics } from '../services/haptics';
import { IconClose } from './Icons';

interface InstallPromptProps {
  isOpen: boolean;
  onClose: () => void;
}

export const InstallPrompt: React.FC<InstallPromptProps> = ({ isOpen, onClose }) => {
  const [deferredPrompt, setDeferredPrompt] = useState<any>(null);
  const [isIos, setIsIos] = useState(false);
  const [isStandalone, setIsStandalone] = useState(false);

  useEffect(() => {
    // Detect iOS
    const ua = window.navigator.userAgent.toLowerCase();
    const isAppleDevice = /iphone|ipad|ipod/.test(ua);
    setIsIos(isAppleDevice);

    // Detect standalone PWA mode
    const standalone = window.matchMedia('(display-mode: standalone)').matches || (window.navigator as any).standalone;
    setIsStandalone(Boolean(standalone));

    const handleBeforeInstallPrompt = (e: Event) => {
      e.preventDefault();
      setDeferredPrompt(e);
    };

    window.addEventListener('beforeinstallprompt', handleBeforeInstallPrompt);
    return () => window.removeEventListener('beforeinstallprompt', handleBeforeInstallPrompt);
  }, []);

  if (!isOpen || isStandalone) return null;

  const handleNativeInstall = async () => {
    if (deferredPrompt) {
      Haptics.buttonPressed();
      deferredPrompt.prompt();
      const choiceResult = await deferredPrompt.userChoice;
      if (choiceResult.outcome === 'accepted') {
        Haptics.confirm();
        onClose();
      }
      setDeferredPrompt(null);
    }
  };

  return (
    <div className="modal-overlay" onClick={onClose}>
      <div className="modal-sheet" onClick={(e) => e.stopPropagation()}>
        <div className="modal-header">
          <span className="modal-title">Install Mango Remote</span>
          <button className="modal-close-btn" onClick={onClose} type="button" aria-label="Close">
            <IconClose size={16} />
          </button>
        </div>

        <p style={{ fontSize: '14px', color: 'var(--text-secondary)', lineHeight: 1.5 }}>
          Install Mango Remote to your phone’s Home Screen for instant launch and a full-screen native remote feel.
        </p>

        {isIos ? (
          <div style={{ background: 'var(--bg-surface)', padding: '16px', borderRadius: '14px', display: 'flex', flexDirection: 'column', gap: '10px' }}>
            <div style={{ fontSize: '14px', fontWeight: 600, color: 'var(--text-primary)' }}>iPhone Installation:</div>
            <div style={{ fontSize: '13px', color: 'var(--text-secondary)' }}>
              1. Tap the <strong>Share</strong> button in Safari's bottom toolbar.
            </div>
            <div style={{ fontSize: '13px', color: 'var(--text-secondary)' }}>
              2. Scroll down and tap <strong>Add to Home Screen</strong>.
            </div>
            <div style={{ fontSize: '13px', color: 'var(--text-secondary)' }}>
              3. Tap <strong>Add</strong> in the top-right corner.
            </div>
          </div>
        ) : (
          <div style={{ background: 'var(--bg-surface)', padding: '16px', borderRadius: '14px', display: 'flex', flexDirection: 'column', gap: '10px' }}>
            <div style={{ fontSize: '14px', fontWeight: 600, color: 'var(--text-primary)' }}>Android Installation:</div>
            {deferredPrompt ? (
              <button className="primary-btn" onClick={handleNativeInstall} type="button">
                Install Mango Remote
              </button>
            ) : (
              <div style={{ fontSize: '13px', color: 'var(--text-secondary)', lineHeight: 1.4 }}>
                Tap the three-dot browser menu in Chrome and select <strong>Install app</strong> or <strong>Add to Home screen</strong>.
              </div>
            )}
          </div>
        )}

        <button
          className="primary-btn"
          type="button"
          style={{ background: 'var(--bg-surface-elevated)', color: 'var(--text-primary)' }}
          onClick={onClose}
        >
          Continue in Browser
        </button>
      </div>
    </div>
  );
};

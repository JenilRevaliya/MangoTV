import React, { useState, useRef, useEffect } from 'react';
import { Network } from '../services/network';
import { Haptics } from '../services/haptics';
import { IconClose } from './Icons';

interface KeyboardSheetProps {
  isOpen: boolean;
  onClose: () => void;
}

export const KeyboardSheet: React.FC<KeyboardSheetProps> = ({ isOpen, onClose }) => {
  const [text, setText] = useState('');
  const inputRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    if (isOpen) {
      setTimeout(() => inputRef.current?.focus(), 150);
    } else {
      setText('');
    }
  }, [isOpen]);

  if (!isOpen) return null;

  const handleSend = (e: React.FormEvent) => {
    e.preventDefault();
    if (!text.trim()) return;
    Haptics.buttonPressed();
    Network.textInput(text).catch(() => {});
    Network.navigate('select').catch(() => {});
    setText('');
    onClose();
  };

  const handleStreamChar = (newVal: string) => {
    setText(newVal);
  };

  const handleBackspace = () => {
    Haptics.buttonPressed();
    Network.sendCommand('key', { key_code: 67 }).catch(() => {}); // KEYCODE_DEL
  };

  return (
    <div className="modal-overlay" onClick={onClose}>
      <div className="modal-sheet" onClick={(e) => e.stopPropagation()}>
        <div className="modal-header">
          <span className="modal-title">Keyboard</span>
          <button className="modal-close-btn" onClick={onClose} type="button" aria-label="Close">
            <IconClose size={16} />
          </button>
        </div>

        <form onSubmit={handleSend} style={{ display: 'flex', flexDirection: 'column', gap: '12px' }}>
          <input
            ref={inputRef}
            className="input-text"
            type="text"
            placeholder="Type text for TV..."
            value={text}
            onChange={(e) => handleStreamChar(e.target.value)}
            autoCapitalize="none"
            autoCorrect="off"
            spellCheck="false"
          />

          <div style={{ display: 'flex', gap: '8px' }}>
            <button
              className="primary-btn"
              type="button"
              style={{ flex: 1, background: 'var(--bg-surface-elevated)', color: 'var(--text-primary)' }}
              onClick={handleBackspace}
            >
              Delete
            </button>
            <button className="primary-btn" type="submit" style={{ flex: 1.5 }}>
              Send to TV
            </button>
          </div>
        </form>
      </div>
    </div>
  );
};

import React, { useState, useRef, useEffect } from 'react';
import { PairingService } from '../services/pairing';
import { Network } from '../services/network';
import { Haptics } from '../services/haptics';
import { IconClose } from './Icons';

interface PairingModalProps {
  isOpen: boolean;
  onClose: () => void;
  onPairedSuccess: () => void;
}

export const PairingModal: React.FC<PairingModalProps> = ({ isOpen, onClose, onPairedSuccess }) => {
  const [ip, setIp] = useState(PairingService.getDefaultHost());
  const [pinDigits, setPinDigits] = useState(['', '', '', '']);
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);

  const digitInputsRef = useRef<(HTMLInputElement | null)[]>([]);

  useEffect(() => {
    if (isOpen) {
      setErrorMessage(null);
      setPinDigits(['', '', '', '']);
      setTimeout(() => {
        digitInputsRef.current[0]?.focus();
      }, 150);
    }
  }, [isOpen]);

  if (!isOpen) return null;

  const handleDigitChange = (index: number, val: string) => {
    if (!/^\d*$/.test(val)) return;
    const char = val.slice(-1);
    const updated = [...pinDigits];
    updated[index] = char;
    setPinDigits(updated);
    setErrorMessage(null);

    if (char && index < 3) {
      digitInputsRef.current[index + 1]?.focus();
    }
  };

  const handleKeyDown = (index: number, e: React.KeyboardEvent<HTMLInputElement>) => {
    if (e.key === 'Backspace' && !pinDigits[index] && index > 0) {
      digitInputsRef.current[index - 1]?.focus();
    }
  };

  const fullPin = pinDigits.join('');

  const handlePair = async (e?: React.FormEvent) => {
    if (e) e.preventDefault();
    if (fullPin.length !== 4) {
      setErrorMessage('Enter all 4 digits shown on your TV');
      return;
    }

    setIsSubmitting(true);
    setErrorMessage(null);

    try {
      Haptics.buttonPressed();
      const paired = await PairingService.pairWithPin(ip.trim(), 8090, fullPin);
      Haptics.confirm();
      Network.setPairedTv(paired);
      onPairedSuccess();
      onClose();
    } catch (err: any) {
      Haptics.error();
      setErrorMessage(err.message || 'Pairing failed');
    } finally {
      setIsSubmitting(false);
    }
  };

  return (
    <div className="modal-overlay" onClick={onClose}>
      <div className="modal-sheet" onClick={(e) => e.stopPropagation()}>
        <div className="modal-header">
          <span className="modal-title">Connect to TV</span>
          <button className="modal-close-btn" onClick={onClose} type="button" aria-label="Close">
            <IconClose size={16} />
          </button>
        </div>

        <p style={{ fontSize: '14px', color: 'var(--text-secondary)', lineHeight: 1.4 }}>
          Open Mango Remote Pairing on your TV screen to see the 4-digit PIN.
        </p>

        <div>
          <label style={{ fontSize: '12px', color: 'var(--text-tertiary)', textTransform: 'uppercase', letterSpacing: '0.5px' }}>
            TV IP Address
          </label>
          <input
            className="input-text"
            style={{ marginTop: '4px' }}
            type="text"
            value={ip}
            onChange={(e) => setIp(e.target.value)}
            placeholder="192.168.1.3"
            autoCapitalize="none"
            autoCorrect="off"
          />
        </div>

        <div>
          <label style={{ fontSize: '12px', color: 'var(--text-tertiary)', textTransform: 'uppercase', letterSpacing: '0.5px' }}>
            Pairing PIN
          </label>
          <div className="pin-input-container">
            {[0, 1, 2, 3].map((idx) => (
              <input
                key={idx}
                ref={(el) => (digitInputsRef.current[idx] = el)}
                className={`pin-digit-box ${pinDigits[idx] ? 'active' : ''}`}
                type="tel"
                maxLength={1}
                value={pinDigits[idx]}
                onChange={(e) => handleDigitChange(idx, e.target.value)}
                onKeyDown={(e) => handleKeyDown(idx, e)}
                style={{ textAlign: 'center', outline: 'none' }}
              />
            ))}
          </div>
        </div>

        {errorMessage && (
          <div style={{ color: 'var(--status-disconnected)', fontSize: '13px', textAlign: 'center' }}>
            {errorMessage}
          </div>
        )}

        <button
          className="primary-btn"
          onClick={() => handlePair()}
          disabled={isSubmitting || fullPin.length !== 4}
          type="button"
          style={{ opacity: fullPin.length === 4 && !isSubmitting ? 1 : 0.5 }}
        >
          {isSubmitting ? 'Connecting...' : 'Pair & Connect'}
        </button>
      </div>
    </div>
  );
};

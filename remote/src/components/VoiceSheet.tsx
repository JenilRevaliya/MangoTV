import React, { useState, useEffect, useRef } from 'react';
import { SpeechService, VoiceCommandResult } from '../services/speech';
import { Haptics } from '../services/haptics';
import { IconClose } from './Icons';

interface VoiceSheetProps {
  isOpen: boolean;
  onClose: () => void;
}

export const VoiceSheet: React.FC<VoiceSheetProps> = ({ isOpen, onClose }) => {
  const [isListening, setIsListening] = useState(false);
  const [transcript, setTranscript] = useState('');
  const [feedback, setFeedback] = useState<string | null>(null);
  const [errorMsg, setErrorMsg] = useState<string | null>(null);

  const recognitionRef = useRef<any>(null);

  useEffect(() => {
    if (!isOpen) {
      stopListening();
      setTranscript('');
      setFeedback(null);
      setErrorMsg(null);
      return;
    }

    startListening();

    return () => {
      stopListening();
    };
  }, [isOpen]);

  const startListening = () => {
    setErrorMsg(null);
    setFeedback(null);
    setTranscript('');

    const SpeechClass = (window as any).SpeechRecognition || (window as any).webkitSpeechRecognition;
    if (!SpeechClass) {
      setErrorMsg('Speech recognition is not supported on this browser context.');
      return;
    }

    try {
      const recognition = new SpeechClass();
      recognition.continuous = false;
      recognition.interimResults = true;
      recognition.lang = 'en-US';

      recognition.onstart = () => {
        setIsListening(true);
        Haptics.buttonPressed();
      };

      recognition.onresult = (event: any) => {
        let interim = '';
        for (let i = event.resultIndex; i < event.results.length; ++i) {
          if (event.results[i].isFinal) {
            const finalTranscript = event.results[i][0].transcript;
            setTranscript(finalTranscript);
            executeCommand(finalTranscript);
            return;
          } else {
            interim += event.results[i][0].transcript;
          }
        }
        setTranscript(interim);
      };

      recognition.onerror = (event: any) => {
        setIsListening(false);
        setErrorMsg('Voice input error: ' + (event.error || 'Check microphone access'));
      };

      recognition.onend = () => {
        setIsListening(false);
      };

      recognitionRef.current = recognition;
      recognition.start();
    } catch (e: any) {
      setErrorMsg('Microphone error: ' + (e.message || 'Microphone access denied'));
    }
  };

  const stopListening = () => {
    if (recognitionRef.current) {
      try {
        recognitionRef.current.abort();
      } catch (_) {}
      recognitionRef.current = null;
    }
    setIsListening(false);
  };

  const executeCommand = (text: string) => {
    Haptics.confirm();
    const res: VoiceCommandResult = SpeechService.parseAndExecute(text);
    setFeedback(res.actionTaken);
    setTimeout(() => {
      onClose();
    }, 1200);
  };

  if (!isOpen) return null;

  return (
    <div className="modal-overlay" onClick={onClose}>
      <div className="modal-sheet" onClick={(e) => e.stopPropagation()}>
        <div className="modal-header">
          <span className="modal-title">Voice Control</span>
          <button className="modal-close-btn" onClick={onClose} type="button" aria-label="Close">
            <IconClose size={16} />
          </button>
        </div>

        <div style={{ textAlign: 'center', padding: '16px 0' }}>
          {isListening && (
            <div className="voice-wave-container">
              <div className="voice-wave-bar" />
              <div className="voice-wave-bar" />
              <div className="voice-wave-bar" />
              <div className="voice-wave-bar" />
              <div className="voice-wave-bar" />
            </div>
          )}

          <div
            style={{
              fontSize: '18px',
              fontWeight: 500,
              color: transcript ? 'var(--text-primary)' : 'var(--text-tertiary)',
              minHeight: '28px',
              margin: '12px 0'
            }}
          >
            {transcript || (isListening ? 'Listening...' : 'Tap below to speak')}
          </div>

          {feedback && (
            <div style={{ color: 'var(--status-connected)', fontSize: '14px', fontWeight: 600 }}>
              {feedback}
            </div>
          )}

          {errorMsg && (
            <div style={{ color: 'var(--status-disconnected)', fontSize: '13px', marginTop: '6px' }}>
              {errorMsg}
            </div>
          )}
        </div>

        <button
          className="primary-btn"
          type="button"
          onClick={isListening ? stopListening : startListening}
        >
          {isListening ? 'Cancel' : 'Start Speaking'}
        </button>
      </div>
    </div>
  );
};

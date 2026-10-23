import React from 'react';
import { RemoteMode } from '../types/protocol';
import { Haptics } from '../services/haptics';

interface ModeSelectorProps {
  currentMode: RemoteMode;
  onSelectMode: (mode: RemoteMode) => void;
}

export const ModeSelector: React.FC<ModeSelectorProps> = ({ currentMode, onSelectMode }) => {
  return (
    <div className="mode-selector">
      <div
        className={`mode-tab ${currentMode === 'buttons' ? 'active' : ''}`}
        onClick={() => {
          if (currentMode !== 'buttons') {
            Haptics.selectionChanged();
            onSelectMode('buttons');
          }
        }}
      >
        Buttons
      </div>
      <div
        className={`mode-tab ${currentMode === 'swipe' ? 'active' : ''}`}
        onClick={() => {
          if (currentMode !== 'swipe') {
            Haptics.selectionChanged();
            onSelectMode('swipe');
          }
        }}
      >
        Swipe
      </div>
    </div>
  );
};

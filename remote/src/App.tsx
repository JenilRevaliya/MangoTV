import React, { useState, useEffect } from 'react';
import { ConnectionState, RemoteMode, TvState } from './types/protocol';
import { Network } from './services/network';
import { PairingService } from './services/pairing';
import { Header } from './components/Header';
import { ModeSelector } from './components/ModeSelector';
import { ButtonsRemote } from './components/ButtonsRemote';
import { SwipeRemote } from './components/SwipeRemote';
import { AppDock } from './components/AppDock';
import { PairingModal } from './components/PairingModal';
import { KeyboardSheet } from './components/KeyboardSheet';
import { VoiceSheet } from './components/VoiceSheet';
import { SettingsModal } from './components/SettingsModal';
import { InstallPrompt } from './components/InstallPrompt';

const MODE_STORAGE_KEY = 'mango_remote_preferred_mode';
const INSTALL_DISMISSED_KEY = 'mango_remote_install_dismissed';

export const App: React.FC = () => {
  const [connectionState, setConnectionState] = useState<ConnectionState>(Network.getConnectionState());
  const [tvState, setTvState] = useState<TvState>(Network.getTvState());
  const [mode, setMode] = useState<RemoteMode>(() => {
    return (localStorage.getItem(MODE_STORAGE_KEY) as RemoteMode) || 'buttons';
  });

  // Modals state
  const [pairingOpen, setPairingOpen] = useState(false);
  const [keyboardOpen, setKeyboardOpen] = useState(false);
  const [voiceOpen, setVoiceOpen] = useState(false);
  const [settingsOpen, setSettingsOpen] = useState(false);
  const [installOpen, setInstallOpen] = useState(false);

  useEffect(() => {
    // Subscribe to connection state and TV updates
    const unsubState = Network.subscribeState((newState) => {
      setConnectionState(newState);
      if (newState === 'pairing_required') {
        setPairingOpen(true);
      }
    });

    const unsubTvState = Network.subscribeTvState((newTvState) => {
      setTvState(newTvState);
    });

    // Auto-connect on startup
    const saved = PairingService.getSavedTv();
    if (saved) {
      Network.connect();
    } else {
      // Prompt pairing if no saved TV
      setPairingOpen(true);
    }

    // Check first-run install prompt
    const isStandalone = window.matchMedia('(display-mode: standalone)').matches || (window.navigator as any).standalone;
    const dismissed = localStorage.getItem(INSTALL_DISMISSED_KEY) === 'true';
    if (!isStandalone && !dismissed) {
      const timer = setTimeout(() => {
        setInstallOpen(true);
      }, 1000);
      return () => clearTimeout(timer);
    }

    return () => {
      unsubState();
      unsubTvState();
    };
  }, []);

  const handleSelectMode = (newMode: RemoteMode) => {
    setMode(newMode);
    localStorage.setItem(MODE_STORAGE_KEY, newMode);
  };

  const handleDismissInstall = () => {
    setInstallOpen(false);
    localStorage.setItem(INSTALL_DISMISSED_KEY, 'true');
  };

  return (
    <div className="remote-app">
      {/* Top Status Header */}
      <Header
        connectionState={connectionState}
        tvState={tvState}
        onOpenPairing={() => setPairingOpen(true)}
        onOpenKeyboard={() => setKeyboardOpen(true)}
        onOpenVoice={() => setVoiceOpen(true)}
        onOpenSettings={() => setSettingsOpen(true)}
      />

      {/* Mode Selector Pill */}
      <ModeSelector currentMode={mode} onSelectMode={handleSelectMode} />

      {/* Main Control Surface */}
      <main className="main-control-area">
        {mode === 'buttons' ? (
          <ButtonsRemote tvState={tvState} />
        ) : (
          <SwipeRemote tvState={tvState} />
        )}
      </main>

      {/* Four-button App Dock */}
      <AppDock />

      {/* Modals & Bottom Sheets */}
      <PairingModal
        isOpen={pairingOpen}
        onClose={() => setPairingOpen(false)}
        onPairedSuccess={() => {
          setPairingOpen(false);
          Network.connect();
        }}
      />

      <KeyboardSheet
        isOpen={keyboardOpen}
        onClose={() => setKeyboardOpen(false)}
      />

      <VoiceSheet
        isOpen={voiceOpen}
        onClose={() => setVoiceOpen(false)}
      />

      <SettingsModal
        isOpen={settingsOpen}
        onClose={() => setSettingsOpen(false)}
        onOpenInstallGuide={() => setInstallOpen(true)}
        onForgetTv={() => {
          setConnectionState('disconnected');
          setPairingOpen(true);
        }}
      />

      <InstallPrompt
        isOpen={installOpen}
        onClose={handleDismissInstall}
      />
    </div>
  );
};

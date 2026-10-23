import React, { useRef } from 'react';
import { Network } from '../services/network';
import { Haptics } from '../services/haptics';
import {
  IconChevronUp,
  IconChevronDown,
  IconChevronLeft,
  IconChevronRight,
  IconBack,
  IconHome,
  IconMenu,
  IconRewind,
  IconPlay,
  IconPause,
  IconFastForward,
  IconVolumeDown,
  IconVolumeMute,
  IconVolumeUp
} from './Icons';
import { TvState } from '../types/protocol';

interface ButtonsRemoteProps {
  tvState: TvState;
}

export const ButtonsRemote: React.FC<ButtonsRemoteProps> = ({ tvState }) => {
  const repeatTimerRef = useRef<number | null>(null);
  const repeatIntervalRef = useRef<number | null>(null);

  const startRepeating = (action: () => void) => {
    action();
    stopRepeating();
    repeatTimerRef.current = window.setTimeout(() => {
      repeatIntervalRef.current = window.setInterval(() => {
        action();
      }, 110);
    }, 320);
  };

  const stopRepeating = () => {
    if (repeatTimerRef.current !== null) {
      window.clearTimeout(repeatTimerRef.current);
      repeatTimerRef.current = null;
    }
    if (repeatIntervalRef.current !== null) {
      window.clearInterval(repeatIntervalRef.current);
      repeatIntervalRef.current = null;
    }
  };

  const handleNav = (direction: 'up' | 'down' | 'left' | 'right') => {
    Haptics.buttonPressed();
    Network.navigate(direction).catch(() => {});
  };

  const handleSelect = () => {
    Haptics.buttonPressed();
    Network.navigate('select').catch(() => {});
  };

  return (
    <div className="buttons-mode-container">
      {/* Directional Circular Pad */}
      <div className="dpad-cluster">
        {/* Up */}
        <button
          className="dpad-btn up"
          onMouseDown={() => startRepeating(() => handleNav('up'))}
          onMouseUp={stopRepeating}
          onMouseLeave={stopRepeating}
          onTouchStart={(e) => {
            e.preventDefault();
            startRepeating(() => handleNav('up'));
          }}
          onTouchEnd={(e) => {
            e.preventDefault();
            stopRepeating();
          }}
          type="button"
          aria-label="Up"
        >
          <IconChevronUp size={28} />
        </button>

        {/* Down */}
        <button
          className="dpad-btn down"
          onMouseDown={() => startRepeating(() => handleNav('down'))}
          onMouseUp={stopRepeating}
          onMouseLeave={stopRepeating}
          onTouchStart={(e) => {
            e.preventDefault();
            startRepeating(() => handleNav('down'));
          }}
          onTouchEnd={(e) => {
            e.preventDefault();
            stopRepeating();
          }}
          type="button"
          aria-label="Down"
        >
          <IconChevronDown size={28} />
        </button>

        {/* Left */}
        <button
          className="dpad-btn left"
          onMouseDown={() => startRepeating(() => handleNav('left'))}
          onMouseUp={stopRepeating}
          onMouseLeave={stopRepeating}
          onTouchStart={(e) => {
            e.preventDefault();
            startRepeating(() => handleNav('left'));
          }}
          onTouchEnd={(e) => {
            e.preventDefault();
            stopRepeating();
          }}
          type="button"
          aria-label="Left"
        >
          <IconChevronLeft size={28} />
        </button>

        {/* Right */}
        <button
          className="dpad-btn right"
          onMouseDown={() => startRepeating(() => handleNav('right'))}
          onMouseUp={stopRepeating}
          onMouseLeave={stopRepeating}
          onTouchStart={(e) => {
            e.preventDefault();
            startRepeating(() => handleNav('right'));
          }}
          onTouchEnd={(e) => {
            e.preventDefault();
            stopRepeating();
          }}
          type="button"
          aria-label="Right"
        >
          <IconChevronRight size={28} />
        </button>

        {/* Center OK / Select */}
        <button
          className="dpad-center-ok"
          onClick={handleSelect}
          type="button"
          aria-label="Select"
        >
          OK
        </button>
      </div>

      {/* Navigation Utilities: Back, Home, Menu */}
      <div className="nav-utils-row">
        <button
          className="nav-pill-btn"
          onClick={() => {
            Haptics.buttonPressed();
            Network.navigate('back').catch(() => {});
          }}
          type="button"
          aria-label="Back"
        >
          <IconBack size={20} />
        </button>
        <button
          className="nav-pill-btn"
          onClick={() => {
            Haptics.buttonPressed();
            Network.navigate('home').catch(() => {});
          }}
          type="button"
          aria-label="Home"
        >
          <IconHome size={20} />
        </button>
        <button
          className="nav-pill-btn"
          onClick={() => {
            Haptics.buttonPressed();
            Network.navigate('menu').catch(() => {});
          }}
          type="button"
          aria-label="Menu"
        >
          <IconMenu size={20} />
        </button>
      </div>

      {/* Control Bars: Media Bar & Volume Bar */}
      <div className="control-bars-row">
        {/* Media Controls Bar */}
        <div className="media-bar">
          <button
            className="media-btn"
            onClick={() => {
              Haptics.buttonPressed();
              Network.media('rewind').catch(() => {});
            }}
            type="button"
            aria-label="Rewind"
          >
            <IconRewind size={18} />
          </button>
          <button
            className="media-btn play-pause"
            onClick={() => {
              Haptics.buttonPressed();
              Network.media('play_pause').catch(() => {});
            }}
            type="button"
            aria-label="Play or Pause"
          >
            {tvState.media_state === 'playing' ? <IconPause size={18} /> : <IconPlay size={18} />}
          </button>
          <button
            className="media-btn"
            onClick={() => {
              Haptics.buttonPressed();
              Network.media('fast_forward').catch(() => {});
            }}
            type="button"
            aria-label="Fast Forward"
          >
            <IconFastForward size={18} />
          </button>
        </div>

        {/* Volume Bar */}
        <div className="volume-bar">
          <button
            className="volume-btn"
            onClick={() => {
              Haptics.buttonPressed();
              Network.volume('down').catch(() => {});
            }}
            type="button"
            aria-label="Volume Down"
          >
            <IconVolumeDown size={18} />
          </button>
          <button
            className="volume-btn"
            onClick={() => {
              Haptics.buttonPressed();
              Network.volume('mute').catch(() => {});
            }}
            type="button"
            aria-label="Mute"
          >
            <IconVolumeMute size={18} />
          </button>
          <button
            className="volume-btn"
            onClick={() => {
              Haptics.buttonPressed();
              Network.volume('up').catch(() => {});
            }}
            type="button"
            aria-label="Volume Up"
          >
            <IconVolumeUp size={18} />
          </button>
        </div>
      </div>
    </div>
  );
};

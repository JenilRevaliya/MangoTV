import React, { useRef, useState } from 'react';
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

interface SwipeRemoteProps {
  tvState: TvState;
}

export const SwipeRemote: React.FC<SwipeRemoteProps> = ({ tvState }) => {
  const [touchActive, setTouchActive] = useState(false);
  const [directionActive, setDirectionActive] = useState<string | null>(null);

  const touchStartRef = useRef<{ x: number; y: number; time: number } | null>(null);
  const longPressTimerRef = useRef<number | null>(null);
  const hasSwipedRef = useRef(false);

  const SWIPE_THRESHOLD = 26; // px

  const handleTouchStart = (e: React.TouchEvent | React.MouseEvent) => {
    const clientX = 'touches' in e ? e.touches[0].clientX : (e as React.MouseEvent).clientX;
    const clientY = 'touches' in e ? e.touches[0].clientY : (e as React.MouseEvent).clientY;

    touchStartRef.current = { x: clientX, y: clientY, time: Date.now() };
    hasSwipedRef.current = false;
    setTouchActive(true);

    longPressTimerRef.current = window.setTimeout(() => {
      if (!hasSwipedRef.current) {
        Haptics.longPressStarted();
        Network.navigate('menu').catch(() => {});
        hasSwipedRef.current = true;
      }
    }, 550);
  };

  const handleTouchMove = (e: React.TouchEvent | React.MouseEvent) => {
    if (!touchStartRef.current || hasSwipedRef.current) return;

    const clientX = 'touches' in e ? e.touches[0].clientX : (e as React.MouseEvent).clientX;
    const clientY = 'touches' in e ? e.touches[0].clientY : (e as React.MouseEvent).clientY;

    const dx = clientX - touchStartRef.current.x;
    const dy = clientY - touchStartRef.current.y;
    const absX = Math.abs(dx);
    const absY = Math.abs(dy);

    if (Math.max(absX, absY) > 10 && longPressTimerRef.current) {
      window.clearTimeout(longPressTimerRef.current);
      longPressTimerRef.current = null;
    }

    if (absX >= SWIPE_THRESHOLD || absY >= SWIPE_THRESHOLD) {
      hasSwipedRef.current = true;
      if (longPressTimerRef.current) {
        window.clearTimeout(longPressTimerRef.current);
        longPressTimerRef.current = null;
      }

      if (absX > absY) {
        if (dx > 0) {
          triggerSwipe('right');
        } else {
          triggerSwipe('left');
        }
      } else {
        if (dy > 0) {
          triggerSwipe('down');
        } else {
          triggerSwipe('up');
        }
      }
    }
  };

  const triggerSwipe = (direction: 'up' | 'down' | 'left' | 'right') => {
    Haptics.gestureRecognized();
    setDirectionActive(direction);
    Network.navigate(direction).catch(() => {});
    window.setTimeout(() => setDirectionActive(null), 200);
  };

  const handleTouchEnd = () => {
    setTouchActive(false);

    if (longPressTimerRef.current) {
      window.clearTimeout(longPressTimerRef.current);
      longPressTimerRef.current = null;
    }

    if (touchStartRef.current && !hasSwipedRef.current) {
      const elapsed = Date.now() - touchStartRef.current.time;
      if (elapsed < 350) {
        // Clean tap = OK
        Haptics.buttonPressed();
        Network.navigate('select').catch(() => {});
      }
    }

    touchStartRef.current = null;
    hasSwipedRef.current = false;
  };

  return (
    <div className="swipe-mode-container">
      {/* Large Capacitive Touch Surface */}
      <div
        className={`touchpad-surface ${touchActive ? 'touch-active' : ''}`}
        onTouchStart={handleTouchStart}
        onTouchMove={handleTouchMove}
        onTouchEnd={handleTouchEnd}
        onTouchCancel={handleTouchEnd}
        onMouseDown={handleTouchStart}
        onMouseMove={handleTouchMove}
        onMouseUp={handleTouchEnd}
        onMouseLeave={handleTouchEnd}
      >
        <div className="touchpad-guide-up" style={{ opacity: directionActive === 'up' ? 1 : 0.25 }}>
          <IconChevronUp size={22} color={directionActive === 'up' ? 'var(--accent-mango)' : 'currentColor'} />
        </div>
        <div className="touchpad-guide-down" style={{ opacity: directionActive === 'down' ? 1 : 0.25 }}>
          <IconChevronDown size={22} color={directionActive === 'down' ? 'var(--accent-mango)' : 'currentColor'} />
        </div>
        <div className="touchpad-guide-left" style={{ opacity: directionActive === 'left' ? 1 : 0.25 }}>
          <IconChevronLeft size={22} color={directionActive === 'left' ? 'var(--accent-mango)' : 'currentColor'} />
        </div>
        <div className="touchpad-guide-right" style={{ opacity: directionActive === 'right' ? 1 : 0.25 }}>
          <IconChevronRight size={22} color={directionActive === 'right' ? 'var(--accent-mango)' : 'currentColor'} />
        </div>

        <div className="touchpad-center-hint">
          Swipe to Navigate<br />Tap to Select
        </div>
      </div>

      {/* Navigation Utilities */}
      <div className="nav-utils-row" style={{ maxWidth: '100%' }}>
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

      {/* Media & Volume Bars */}
      <div className="control-bars-row">
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

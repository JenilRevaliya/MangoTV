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

  // Single active pointer and gesture lifecycle
  const activePointerIdRef = useRef<number | null>(null);
  const pointerStartRef = useRef<{ x: number; y: number; time: number } | null>(null);
  const gestureHandledRef = useRef<boolean>(false);
  const longPressTimerRef = useRef<number | null>(null);

  const SWIPE_THRESHOLD = 28; // px threshold for deliberate swipe classification
  const TAP_MAX_DISTANCE = 12; // px threshold to distinguish tap from swipe
  const TAP_MAX_DURATION = 320; // ms maximum for tap detection

  const clearLongPress = () => {
    if (longPressTimerRef.current !== null) {
      window.clearTimeout(longPressTimerRef.current);
      longPressTimerRef.current = null;
    }
  };

  const handlePointerDown = (e: React.PointerEvent<HTMLDivElement>) => {
    // Only track primary pointer; ignore secondary multi-touch touches
    if (activePointerIdRef.current !== null) {
      return;
    }

    activePointerIdRef.current = e.pointerId;
    pointerStartRef.current = { x: e.clientX, y: e.clientY, time: Date.now() };
    gestureHandledRef.current = false;
    setTouchActive(true);

    try {
      e.currentTarget.setPointerCapture(e.pointerId);
    } catch (_) {}

    clearLongPress();
    longPressTimerRef.current = window.setTimeout(() => {
      if (!gestureHandledRef.current && activePointerIdRef.current === e.pointerId) {
        gestureHandledRef.current = true;
        Haptics.longPressStarted();
        const cmdId = 'longpress_menu_' + Date.now();
        Network.navigate('menu', cmdId).catch(() => {});
      }
    }, 550);
  };

  const handlePointerMove = (e: React.PointerEvent<HTMLDivElement>) => {
    if (e.pointerId !== activePointerIdRef.current) return;
    if (gestureHandledRef.current || !pointerStartRef.current) return;

    const dx = e.clientX - pointerStartRef.current.x;
    const dy = e.clientY - pointerStartRef.current.y;
    const absX = Math.abs(dx);
    const absY = Math.abs(dy);

    if (Math.max(absX, absY) > 8) {
      clearLongPress();
    }

    if (absX >= SWIPE_THRESHOLD || absY >= SWIPE_THRESHOLD) {
      // Mark handled immediately before dispatch to guarantee 1:1 delivery
      gestureHandledRef.current = true;
      clearLongPress();

      const direction = absX > absY ? (dx > 0 ? 'right' : 'left') : (dy > 0 ? 'down' : 'up');
      triggerSwipe(direction);
    }
  };

  const triggerSwipe = (direction: 'up' | 'down' | 'left' | 'right') => {
    Haptics.gestureRecognized();
    setDirectionActive(direction);
    const cmdId = 'swipe_' + direction + '_' + Date.now() + '_' + Math.random().toString(36).substring(2, 6);
    Network.navigate(direction, cmdId).catch(() => {});
    window.setTimeout(() => setDirectionActive(null), 180);
  };

  const handlePointerUp = (e: React.PointerEvent<HTMLDivElement>) => {
    if (e.pointerId !== activePointerIdRef.current) return;
    clearLongPress();

    if (!gestureHandledRef.current && pointerStartRef.current) {
      const elapsed = Date.now() - pointerStartRef.current.time;
      const dist = Math.hypot(e.clientX - pointerStartRef.current.x, e.clientY - pointerStartRef.current.y);

      if (elapsed <= TAP_MAX_DURATION && dist <= TAP_MAX_DISTANCE) {
        gestureHandledRef.current = true;
        Haptics.buttonPressed();
        const cmdId = 'tap_select_' + Date.now() + '_' + Math.random().toString(36).substring(2, 6);
        Network.navigate('select', cmdId).catch(() => {});
      }
    }

    resetPointer(e);
  };

  const handlePointerCancel = (e: React.PointerEvent<HTMLDivElement>) => {
    if (e.pointerId !== activePointerIdRef.current) return;
    clearLongPress();
    resetPointer(e);
  };

  const resetPointer = (e: React.PointerEvent<HTMLDivElement>) => {
    try {
      if (e.currentTarget.hasPointerCapture(e.pointerId)) {
        e.currentTarget.releasePointerCapture(e.pointerId);
      }
    } catch (_) {}

    activePointerIdRef.current = null;
    pointerStartRef.current = null;
    gestureHandledRef.current = false;
    setTouchActive(false);
  };

  return (
    <div className="swipe-mode-container">
      {/* Unified Pointer Touch Surface (Zero Dual Event Dispatch) */}
      <div
        className={`touchpad-surface ${touchActive ? 'touch-active' : ''}`}
        onPointerDown={handlePointerDown}
        onPointerMove={handlePointerMove}
        onPointerUp={handlePointerUp}
        onPointerCancel={handlePointerCancel}
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

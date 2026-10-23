import React, { useState } from 'react';
import { Network } from '../services/network';
import { Haptics } from '../services/haptics';
import { FavoriteApp } from '../types/protocol';
import { IconNetflix, IconYoutube, IconPrime, IconMangoPlay } from './Icons';

const DEFAULT_APPS: FavoriteApp[] = [
  {
    id: 'youtube',
    name: 'YouTube',
    package: 'com.amazon.firetv.youtube',
    iconType: 'youtube'
  },
  {
    id: 'netflix',
    name: 'Netflix',
    package: 'com.netflix.ninja',
    iconType: 'netflix'
  },
  {
    id: 'prime',
    name: 'Prime Video',
    package: 'com.amazon.firebat',
    iconType: 'prime'
  },
  {
    id: 'mangoplay',
    name: 'Mango Play',
    package: 'com.gathin.tvhome',
    iconType: 'mangoplay'
  }
];

const STORAGE_FAVORITES_KEY = 'mango_remote_favorite_apps';

export const AppDock: React.FC = () => {
  const [apps] = useState<FavoriteApp[]>(() => {
    try {
      const saved = localStorage.getItem(STORAGE_FAVORITES_KEY);
      if (saved) return JSON.parse(saved);
    } catch (_) {}
    return DEFAULT_APPS;
  });

  const [launchingAppId, setLaunchingAppId] = useState<string | null>(null);

  const handleLaunch = (app: FavoriteApp) => {
    Haptics.buttonPressed();
    setLaunchingAppId(app.id);
    Network.launchApp(app.package)
      .catch(() => {})
      .finally(() => {
        setTimeout(() => setLaunchingAppId(null), 600);
      });
  };

  const renderIcon = (type: FavoriteApp['iconType']) => {
    switch (type) {
      case 'netflix':
        return <IconNetflix size={22} />;
      case 'youtube':
        return <IconYoutube size={22} />;
      case 'prime':
        return <IconPrime size={22} />;
      case 'mangoplay':
      default:
        return <IconMangoPlay size={22} />;
    }
  };

  return (
    <div className="app-dock-grid">
      {apps.map((app) => (
        <button
          key={app.id}
          className="app-tile"
          onClick={() => handleLaunch(app)}
          type="button"
          style={{
            borderColor: launchingAppId === app.id ? 'var(--accent-mango)' : 'var(--border-subtle)'
          }}
          aria-label={`Open ${app.name}`}
        >
          <div className="app-tile-icon">{renderIcon(app.iconType)}</div>
          <span className="app-tile-label">{app.name}</span>
        </button>
      ))}
    </div>
  );
};

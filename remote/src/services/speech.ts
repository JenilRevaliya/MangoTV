import { Network } from './network';

export interface VoiceCommandResult {
  transcript: string;
  actionTaken: string;
  isCustomSearch: boolean;
}

export class SpeechService {
  public static isSupported(): boolean {
    return 'SpeechRecognition' in window || 'webkitSpeechRecognition' in window;
  }

  public static parseAndExecute(transcript: string): VoiceCommandResult {
    const clean = transcript.toLowerCase().trim();

    if (clean.includes('youtube')) {
      Network.launchApp('com.amazon.firetv.youtube');
      return { transcript, actionTaken: 'Opening YouTube', isCustomSearch: false };
    }
    if (clean.includes('netflix')) {
      Network.launchApp('com.netflix.ninja');
      return { transcript, actionTaken: 'Opening Netflix', isCustomSearch: false };
    }
    if (clean.includes('prime') || clean.includes('amazon video')) {
      Network.launchApp('com.amazon.amazonvideo.livingroom');
      return { transcript, actionTaken: 'Opening Prime Video', isCustomSearch: false };
    }
    if (clean.includes('apple tv')) {
      Network.launchApp('com.apple.atve.amazon.appletv');
      return { transcript, actionTaken: 'Opening Apple TV', isCustomSearch: false };
    }

    if (clean === 'pause' || clean === 'play' || clean === 'play pause') {
      Network.media('play_pause');
      return { transcript, actionTaken: 'Toggling Play/Pause', isCustomSearch: false };
    }
    if (clean === 'volume up' || clean === 'louder') {
      Network.volume('up');
      return { transcript, actionTaken: 'Volume Up', isCustomSearch: false };
    }
    if (clean === 'volume down' || clean === 'lower volume' || clean === 'quieter') {
      Network.volume('down');
      return { transcript, actionTaken: 'Volume Down', isCustomSearch: false };
    }
    if (clean === 'mute' || clean === 'unmute') {
      Network.volume('mute');
      return { transcript, actionTaken: 'Toggling Mute', isCustomSearch: false };
    }
    if (clean === 'home' || clean === 'go home') {
      Network.navigate('home');
      return { transcript, actionTaken: 'Navigating Home', isCustomSearch: false };
    }
    if (clean === 'back') {
      Network.navigate('back');
      return { transcript, actionTaken: 'Back', isCustomSearch: false };
    }

    // Default: send text as TV search query or input
    Network.textInput(transcript);
    return { transcript, actionTaken: `Searching "${transcript}"`, isCustomSearch: true };
  }
}

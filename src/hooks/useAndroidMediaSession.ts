import { useEffect, useRef } from 'react';
import type { RefObject } from 'react';
import type { SongResult } from '../types';
import { isAndroidMusicRuntime } from '../services/androidMusicRuntime';
import { getSongAlbumLabel, getSongArtistLabel, getSongCoverUrl } from '../services/onlineMusic/songMetadata';
import { isMediaSessionSourceReady } from '../utils/mediaSessionSync';

// Publish the actual displayed deck to Android's foreground media service, without a second player.
type Options = {
    audioRef: RefObject<HTMLAudioElement | null>;
    getDisplayAudioElement?: () => HTMLAudioElement | null;
    audioSrc: string | null;
    currentSong: SongResult | null;
    cachedCoverUrl: string | null;
    unknownArtistLabel: string;
    isNowPlayingStageActive: boolean;
    mediaSessionPlayRef: RefObject<() => Promise<void>>;
    mediaSessionPauseRef: RefObject<() => void>;
    mediaSessionPrevRef: RefObject<() => void>;
    mediaSessionNextRef: RefObject<() => Promise<void> | void>;
    isNowPlayingControlDisabledRef: RefObject<boolean>;
};
type MediaAction = { action: string; position?: number };
type AndroidWindow = Window & {
    __foliaAndroidMediaAction?: (event: MediaAction) => void;
    Capacitor?: { nativePromise: (plugin: string, method: string, value: Record<string, unknown>) => Promise<unknown> };
};
const update = (value: Record<string, unknown>) => {
    void (window as AndroidWindow).Capacitor?.nativePromise('FoliaPlayback', 'update', value)
        .catch(error => console.warn('[AndroidMediaSession] Failed to publish playback', error));
};

async function prepareArtwork(url: string, signal: AbortSignal): Promise<string> {
    if (!url) return '';
    const response = await fetch(url, { signal });
    if (!response.ok) return '';
    const blob = await response.blob();
    if (blob.size > 8 * 1024 * 1024 || !blob.type.startsWith('image/')) return '';
    const bitmap = await createImageBitmap(blob);
    try {
        const size = Math.min(512, bitmap.width, bitmap.height);
        const canvas = document.createElement('canvas');
        canvas.width = size; canvas.height = size;
        canvas.getContext('2d')?.drawImage(bitmap, 0, 0, size, size);
        return canvas.toDataURL('image/jpeg', 0.85);
    } finally { bitmap.close(); }
}

export function useAndroidMediaSession(options: Options): void {
    const latest = useRef(options);
    latest.current = options;
    useEffect(() => {
        if (!isAndroidMusicRuntime()) return;
        const target = window as AndroidWindow;
        const handler = (event: MediaAction) => {
            const state = latest.current;
            if (state.isNowPlayingControlDisabledRef.current) return;
            switch (event.action) {
                case 'play': void state.mediaSessionPlayRef.current().catch(error => console.warn('[AndroidMediaSession] Play failed', error)); break;
                case 'pause': state.mediaSessionPauseRef.current(); break;
                case 'previoustrack': state.mediaSessionPrevRef.current(); break;
                case 'nexttrack': void state.mediaSessionNextRef.current(); break;
                case 'seekto': {
                    const audio = state.getDisplayAudioElement?.() ?? state.audioRef.current;
                    if (audio && Number.isFinite(audio.duration) && Number.isFinite(event.position)) {
                        audio.currentTime = Math.min(audio.duration, Math.max(0, event.position!));
                    }
                    break;
                }
            }
        };
        target.__foliaAndroidMediaAction = handler;
        return () => {
            if (target.__foliaAndroidMediaAction === handler) delete target.__foliaAndroidMediaAction;
            update({ clear: true });
        };
    }, []);

    const { audioRef, getDisplayAudioElement, audioSrc, currentSong, cachedCoverUrl, unknownArtistLabel, isNowPlayingStageActive } = options;
    useEffect(() => {
        if (!isAndroidMusicRuntime()) return;
        if (!currentSong || isNowPlayingStageActive) { update({ clear: true }); return; }
        const audio = getDisplayAudioElement?.() ?? audioRef.current;
        if (!audio || !audioSrc) return;
        let disposed = false;
        let artwork = '';
        let lastPositionUpdate = 0;
        const publish = () => {
            if (disposed || !isMediaSessionSourceReady(audio, audioSrc, document.baseURI)) return;
            update({
                title: currentSong.name,
                artist: getSongArtistLabel(currentSong) || unknownArtistLabel,
                album: getSongAlbumLabel(currentSong),
                artwork,
                duration: audio.duration,
                position: audio.currentTime,
                playing: !audio.paused && !audio.ended && !audio.error,
            });
        };
        const onTime = () => {
            if (Date.now() - lastPositionUpdate < 1000) return;
            lastPositionUpdate = Date.now();
            publish();
        };
        const events = ['loadedmetadata', 'playing', 'pause', 'ended', 'error', 'seeked', 'durationchange', 'ratechange'] as const;
        events.forEach(event => audio.addEventListener(event, publish));
        audio.addEventListener('timeupdate', onTime);
        publish();
        const abort = new AbortController();
        void prepareArtwork(cachedCoverUrl || getSongCoverUrl(currentSong) || '', abort.signal).then(value => {
            if (!disposed) { artwork = value; publish(); }
        }).catch(error => { if (!disposed) console.warn('[AndroidMediaSession] Artwork unavailable', error); });
        return () => {
            disposed = true;
            abort.abort();
            events.forEach(event => audio.removeEventListener(event, publish));
            audio.removeEventListener('timeupdate', onTime);
        };
    }, [audioRef, getDisplayAudioElement, audioSrc, currentSong, cachedCoverUrl, unknownArtistLabel, isNowPlayingStageActive]);
}

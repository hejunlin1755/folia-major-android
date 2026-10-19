import { useEffect, useRef } from 'react';
import { isAndroidMusicRuntime } from '../services/androidMusicRuntime';
import { useAppViewStore, type AppView } from '../stores/useAppViewStore';
import { useSearchNavigationStore } from '../stores/useSearchNavigationStore';
import { useCollectionNavigationStore } from '../stores/useCollectionNavigationStore';
import { hasBlockingWindow, hasOpenSheet } from '../utils/keyboardTargets';

// Android Back uses the same Escape handlers and navigation actions as the visible application.
type Navigation = {
    closeSearchView: () => void;
    backCollection: () => void;
    navigateBackFromPlayer: () => void;
    navigateBackFromLattice: () => void;
    navigateToHome: () => void;
};
type BackWindow = Window & { __foliaAndroidBack?: () => boolean };

export const resolveAndroidBackAction = ({ escapeHandled, blocking, search, collection, view }: {
    escapeHandled: boolean;
    blocking: boolean;
    search: boolean;
    collection: boolean;
    view: AppView;
}) => {
    if (escapeHandled || blocking) return 'handled';
    if (search) return 'search';
    if (collection) return 'collection';
    if (view === 'player') return 'player';
    if (view === 'lattice') return 'lattice';
    return view === 'home' ? 'background' : 'home';
};

export function useAndroidBackNavigation(navigation: Navigation): void {
    const latest = useRef(navigation);
    latest.current = navigation;
    useEffect(() => {
        if (!isAndroidMusicRuntime()) return;
        const targetWindow = window as BackWindow;
        const handler = () => {
            const blocking = hasBlockingWindow() || hasOpenSheet();
            const escape = new KeyboardEvent('keydown', { key: 'Escape', code: 'Escape', bubbles: true, cancelable: true });
            // An unexpanded wall tile's keyboard focus must not consume a navigation Back.
            if (blocking || useAppViewStore.getState().view === 'player' || document.querySelector('[data-bravais-expanded="true"]')) {
                (document.activeElement ?? document.body).dispatchEvent(escape);
            }
            const action = resolveAndroidBackAction({
                escapeHandled: escape.defaultPrevented,
                blocking,
                search: useSearchNavigationStore.getState().isSearchOpen,
                collection: Boolean(useCollectionNavigationStore.getState().snapshot),
                view: useAppViewStore.getState().view,
            });
            switch (action) {
                case 'handled': return true;
                case 'search': latest.current.closeSearchView(); return true;
                case 'collection': latest.current.backCollection(); return true;
                case 'player': latest.current.navigateBackFromPlayer(); return true;
                case 'lattice': latest.current.navigateBackFromLattice(); return true;
                case 'home': latest.current.navigateToHome(); return true;
                case 'background': return false;
            }
        };
        targetWindow.__foliaAndroidBack = handler;
        return () => { if (targetWindow.__foliaAndroidBack === handler) delete targetWindow.__foliaAndroidBack; };
    }, []);
}

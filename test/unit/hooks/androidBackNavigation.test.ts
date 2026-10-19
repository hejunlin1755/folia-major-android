import { describe, expect, it } from 'vitest';
import { resolveAndroidBackAction } from '../../../src/hooks/useAndroidBackNavigation';

// A hardware Back must peel the current layer before allowing the root task to go to the background.
describe('Android Back navigation priority', () => {
    const root = { escapeHandled: false, blocking: false, search: false, collection: false, view: 'home' as const };
    it('leaves a handled or blocking dialog above navigation', () => {
        expect(resolveAndroidBackAction({ ...root, blocking: true, search: true, collection: true })).toBe('handled');
        expect(resolveAndroidBackAction({ ...root, escapeHandled: true, collection: true })).toBe('handled');
    });
    it('closes search before leaving a collection, and a collection before the player', () => {
        expect(resolveAndroidBackAction({ ...root, search: true, collection: true, view: 'player' })).toBe('search');
        expect(resolveAndroidBackAction({ ...root, collection: true, view: 'player' })).toBe('collection');
        expect(resolveAndroidBackAction({ ...root, view: 'player' })).toBe('player');
        expect(resolveAndroidBackAction({ ...root, view: 'lattice' })).toBe('lattice');
    });
    it('only backgrounds the task at the home root', () => {
        expect(resolveAndroidBackAction(root)).toBe('background');
    });
});

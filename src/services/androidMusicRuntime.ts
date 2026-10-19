// Routes the Android app's own music API requests through its native loopback bridge.
type NativeBridge = { getPlatform?: () => string; nativePromise?: (plugin: string, method: string, options: Record<string, unknown>) => Promise<any> };
const getBridge = (): NativeBridge | undefined => (globalThis as typeof globalThis & { Capacitor?: NativeBridge }).Capacitor;
export const isAndroidMusicRuntime = (): boolean => getBridge()?.getPlatform?.() === 'android' && typeof getBridge()?.nativePromise === 'function';
export const getAndroidMusicApiBase = (provider: string): string | null => isAndroidMusicRuntime() ? `/android-music/${encodeURIComponent(provider)}` : null;
export const ANDROID_MUSIC_BACKEND_UNAVAILABLE = 'ANDROID_MUSIC_BACKEND_UNAVAILABLE';
let installed = false;

export function installAndroidMusicRuntime(): void {
    if (installed || !isAndroidMusicRuntime()) return;
    installed = true;
    document.documentElement.dataset.android = 'true';
    const browserFetch = globalThis.fetch.bind(globalThis);
    globalThis.fetch = async (input, init) => {
        const address = input instanceof Request ? input.url : String(input);
        const url = new URL(address, location.href);
        if (url.origin !== location.origin || !url.pathname.startsWith('/android-music/')) return browserFetch(input, init);
        const request = new Request(input instanceof Request ? input : new URL(address, location.href), init);
        if (request.signal.aborted) throw request.signal.reason;
        const body = ['GET', 'HEAD'].includes(request.method) ? undefined : await request.text();
        let onAbort: (() => void) | undefined;
        const aborted = new Promise<never>((_, reject) => {
            onAbort = () => reject(request.signal.reason ?? new DOMException('Aborted', 'AbortError'));
            request.signal.addEventListener('abort', onAbort, { once: true });
        });
        try {
            const answer = await Promise.race([getBridge()!.nativePromise!('FoliaMusicBackend', 'request', {
                path: url.pathname.slice('/android-music'.length) + url.search,
                method: request.method,
                headers: Object.fromEntries(request.headers), body,
            }), aborted]);
            return new Response([204, 205, 304].includes(answer.status) ? null : answer.body, { status: answer.status, headers: answer.headers });
        } catch (error) {
            if (request.signal.aborted) throw request.signal.reason ?? error;
            throw Object.assign(new Error(`${ANDROID_MUSIC_BACKEND_UNAVAILABLE}: ${error instanceof Error ? error.message : 'Native request failed'}`), { code: ANDROID_MUSIC_BACKEND_UNAVAILABLE });
        } finally {
            if (onAbort) request.signal.removeEventListener('abort', onAbort);
        }
    };
    void getBridge()!.nativePromise!('FoliaMusicBackend', 'ready', {}).catch(error => console.warn('[AndroidMusic] Built-in service startup failed', error));
}

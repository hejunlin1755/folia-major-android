// Validates public backend addresses before bundling the Android app.
export function validateAndroidEnvironment(env) {
    for (const key of ['VITE_NETEASE_API_BASE', 'VITE_KUGOU_API_BASE', 'VITE_QQ_API_BASE', 'VITE_LYRIC_PROXY_ORIGIN']) {
        const value = env[key];
        if (!value) continue;
        let url;
        try {
            url = new URL(value);
        } catch {
            throw new Error(`[Android] ${key} must be an absolute HTTPS URL, or empty to disable it.`);
        }
        if (url.protocol !== 'https:' || ['localhost', '127.0.0.1', '[::1]'].includes(url.hostname)
            || url.hostname.endsWith('.localhost') || /^127\./.test(url.hostname)
            || url.username || url.password || url.hash || url.search) {
            throw new Error(`[Android] ${key} must use reachable HTTPS without credentials, query or fragment; localhost points to the phone.`);
        }
        if (key === 'VITE_LYRIC_PROXY_ORIGIN' && url.pathname !== '/') {
            throw new Error('[Android] VITE_LYRIC_PROXY_ORIGIN must be an origin without a path.');
        }
    }
}

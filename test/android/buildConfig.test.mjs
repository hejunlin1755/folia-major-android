import { test } from 'node:test';
import assert from 'node:assert/strict';
import { validateAndroidEnvironment } from '../../packaging/android/buildConfig.mjs';

// Android serves assets locally; its API requests must reach actual remote HTTPS services.
test('offline and individually disabled providers are allowed', () => {
    assert.doesNotThrow(() => validateAndroidEnvironment({}));
    assert.doesNotThrow(() => validateAndroidEnvironment({ VITE_QQ_API_BASE: '' }));
});
test('remote HTTPS API paths are preserved', () => {
    assert.doesNotThrow(() => validateAndroidEnvironment({ VITE_QQ_API_BASE: 'https://music.example.com/api/qq' }));
});
for (const value of ['/api/qq', 'http://music.example.com', 'https://localhost/api', 'https://127.0.0.2', 'https://[::1]', 'https://user:secret@music.example.com', 'https://music.example.com?token=secret', 'https://music.example.com/#fragment']) {
    test(`rejects unsuitable backend: ${value}`, () => {
        assert.throws(() => validateAndroidEnvironment({ VITE_QQ_API_BASE: value }), /\[Android\]/);
    });
}
test('lyric proxy is an origin, not an endpoint', () => {
    assert.doesNotThrow(() => validateAndroidEnvironment({ VITE_LYRIC_PROXY_ORIGIN: 'https://folia.example.com/' }));
    assert.throws(() => validateAndroidEnvironment({ VITE_LYRIC_PROXY_ORIGIN: 'https://folia.example.com/api' }));
});

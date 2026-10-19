const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { createKugou } = require('../../packaging/android/backend/kugou.cjs');

// Exercise device registration, account forwarding and restart stability without real credentials.
test('KuGou programmatic calls share a registered identity while retaining the account supplied by the app', async () => {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'folia-kugou-'));
    let registrations = 0;
    const calls = [];
    const api = {
        register_dev: async p => { registrations++; assert.ok(p.cookie.KUGOU_API_MID); return { status: 200, body: { data: { dfid: 'device-test' } } }; },
        song_url: async p => { calls.push(p); return p.cookie.dfid ? { status: 200, body: { url: 'https://fs.test.kugou.com/a.mp3' } } : { status: 200, body: { error_code: 20028 } }; },
    };
    try {
        const first = createKugou(dir, api);
        await Promise.all([first.song_url({ cookie: 'token=opaque-test;userid=123' }), first.song_url({})]);
        assert.equal(registrations, 1);
        assert.ok(calls.some(call => call.cookie.dfid === 'device-test'));
        assert.equal(calls[0].cookie.token, 'opaque-test');
        await createKugou(dir, api).song_url({});
        assert.equal(registrations, 1);
        assert.equal(calls[0].cookie.KUGOU_API_MID, calls.at(-1).cookie.KUGOU_API_MID);
        assert.equal(fs.readFileSync(path.join(dir, 'kugou-device.json'), 'utf8').includes('opaque-test'), false);
    } finally { fs.rmSync(dir, { recursive: true, force: true }); }
});

test('an authenticated empty legacy tracker result is followed by account and track authorization', async () => {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'folia-kugou-auth-'));
    const operations = [];
    const api = {
        song_url: async () => ({ status: 200, body: { status: 2 } }),
        user_verify: async p => { operations.push('account'); assert.equal(p.cookie.token, 'opaque-test'); return { status: 200, body: { data: { auth: 'account-test-auth' } } }; },
        song_auth: async p => { operations.push('track'); assert.equal(p.auth, 'account-test-auth'); assert.equal(p.hash, 'TRACK-HASH'); return { status: 200, body: { data: { auth: 'track-test-auth', open_time: 123 } } }; },
        song_url_auth: async p => { operations.push('url'); assert.equal(p.auth, 'track-test-auth'); assert.equal(p.open_time, 123); assert.equal(p.quality, '128'); return { status: 200, body: { url: ['https://fs.test.kugou.com/song.mp3'] } }; },
    };
    try {
        const result = await createKugou(dir, api).song_url({ hash: 'TRACK-HASH', quality: '128', cookie: 'token=opaque-test;userid=123' });
        assert.deepEqual(operations, ['account', 'track', 'url']);
        assert.equal(result.body.url.length, 1);
        assert.equal(fs.readFileSync(path.join(dir, 'kugou-device.json'), 'utf8').includes('test-auth'), false);
    } finally { fs.rmSync(dir, { recursive: true, force: true }); }
});

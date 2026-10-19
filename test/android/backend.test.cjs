const { test } = require('node:test');
const assert = require('node:assert/strict');
const { createGateway, allowedProxyTarget } = require('../../packaging/android/backend/main.cjs');
const os = require('node:os');
const fs = require('node:fs');
const path = require('node:path');

// Check the actual loopback HTTP boundary, route mapping and credential isolation without calling a user's account.
test('authenticated gateway routes all three providers and rejects unauthenticated callers', async () => {
    const stateDir = fs.mkdtempSync(path.join(os.tmpdir(), 'folia-gateway-'));
    const providers = {
        prepareNetease: async () => {},
        netease: { login_qr_key: async p => ({ status: 200, body: { code: 200, data: { key: p.test } } }) },
        kugou: { login_qr_key: async () => ({ status: 200, body: { error_code: 0 } }) },
        qqApp: (req, res) => { res.setHeader('Content-Type', 'application/json'); res.end(JSON.stringify({ path: req.url, session: req.headers['x-qq-session'] })); },
    };
    const server = await createGateway({ stateDir, token: 'app-private-test-key', providers });
    const base = `http://127.0.0.1:${server.address().port}`;
    const headers = { 'X-Folia-Local-Auth': 'app-private-test-key' };
    try {
        assert.equal((await fetch(base + '/health')).status, 403);
        assert.equal((await fetch(base + '/health', { headers })).status, 200);
        const body = await (await fetch(base + '/netease/login/qr/key?test=expected', { headers })).json();
        assert.equal(body.data.key, 'expected');
        assert.equal((await fetch(base + '/kugou/login/qr/key', { headers })).status, 200);
        const qq = await (await fetch(base + '/qq/login/status', { headers: { ...headers, 'X-QQ-Session': 'opaque-session' } })).json();
        assert.equal(qq.path, '/login/status');
        assert.equal(qq.session, 'opaque-session');
        assert.equal((await fetch(base + '/netease/toString', { headers })).status, 404);
    } finally { await new Promise(resolve => server.close(resolve)); fs.rmSync(stateDir, { recursive: true, force: true }); }
});
test('lyric proxy checks every target before forwarding', () => {
    assert.equal(allowedProxyTarget('https://y.qq.com/test').hostname, 'y.qq.com');
    for (const url of ['http://127.0.0.1/', 'http://192.168.1.1/', 'https://qq.com.evil.example/', 'https://secret@example.com/']) assert.throws(() => allowedProxyTarget(url));
});

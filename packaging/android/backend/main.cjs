const fs = require('node:fs');
const path = require('node:path');
const http = require('node:http');
const crypto = require('node:crypto');
const { pipeline } = require('node:stream/promises');
const { Readable } = require('node:stream');

// Three pinned provider APIs run inside the app; only its authenticated loopback gateway is exposed.
function json(res, status, body) {
    res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' });
    res.end(JSON.stringify(body));
}

function constantEqual(left, right) {
    const a = Buffer.from(left || '');
    const b = Buffer.from(right || '');
    return a.length === b.length && crypto.timingSafeEqual(a, b);
}

function allowedProxyTarget(value) {
    const url = new URL(value);
    const host = url.hostname.toLowerCase();
    const suffixes = ['qq.com', 'gtimg.cn', 'gtimg.com', 'kugou.com', 'kgimg.com', '163.com', '126.net', 'music.126.net'];
    const exact = ['raw.githubusercontent.com', 'amll-ttml-db.stevexmh.net', 'amlldb.bikonoo.com'];
    if (!['https:', 'http:'].includes(url.protocol) || url.username || url.password ||
        ![...exact].includes(host) && !suffixes.some(domain => host === domain || host.endsWith('.' + domain))) {
        throw new Error('Proxy target is not allowed');
    }
    return url;
}

async function proxy(req, res, url) {
    let target = allowedProxyTarget(url.searchParams.get('url'));
    const headers = {};
    for (const [key, value] of Object.entries(req.headers)) {
        if (!['host', 'origin', 'referer', 'cookie', 'authorization', 'x-folia-local-auth', 'connection'].includes(key)) headers[key] = value;
    }
    for (let redirects = 0; redirects < 5; redirects++) {
        const response = await fetch(target, { headers, redirect: 'manual', signal: AbortSignal.timeout(25000) });
        if ([301, 302, 303, 307, 308].includes(response.status)) {
            target = allowedProxyTarget(new URL(response.headers.get('location'), target).href);
            await response.body?.cancel();
            continue;
        }
        res.writeHead(response.status, { 'Content-Type': response.headers.get('content-type') || 'application/octet-stream' });
        if (response.body) await pipeline(Readable.fromWeb(response.body), res);
        else res.end();
        return;
    }
    throw new Error('Too many redirects');
}

async function readBody(req) {
    const chunks = [];
    let length = 0;
    for await (const chunk of req) {
        length += chunk.length;
        if (length > 2 * 1024 * 1024) throw new Error('Request body too large');
        chunks.push(chunk);
    }
    if (!length) return {};
    const text = Buffer.concat(chunks).toString('utf8');
    return req.headers['content-type']?.includes('application/json') ? JSON.parse(text) : Object.fromEntries(new URLSearchParams(text));
}

function createProviders(stateDir) {
    process.env.TMPDIR = path.join(stateDir, 'tmp');
    process.env.TEMP = process.env.TMPDIR;
    process.env.TMP = process.env.TMPDIR;
    fs.mkdirSync(process.env.TMPDIR, { recursive: true });
    process.env.HOME = stateDir;
    process.chdir(stateDir);
    process.env.AUTO_OPEN_EXPLORER = 'false';
    process.env.QQ_DISABLE_UPDATE_CHECK = 'true';
    process.env.LOG_LEVEL = 'silent';
    const sessionSecretPath = path.join(stateDir, 'qq-session-key');
    if (!fs.existsSync(sessionSecretPath)) fs.writeFileSync(sessionSecretPath, crypto.randomBytes(32).toString('hex'), { mode: 0o600 });
    process.env.QQ_SESSION_SECRET = fs.readFileSync(sessionSecretPath, 'utf8');
    process.env.QQ_AUTH_SESSION_PATH = path.join(stateDir, 'qq-session.enc.json');
    const previousEnv = process.env.NODE_ENV;
    process.env.NODE_ENV = 'test'; // The package exports its Koa app without opening a second, unauthenticated socket.
    const qqApp = require('@yakult-green-tea/qq-music-api').default.callback();
    if (previousEnv === undefined) delete process.env.NODE_ENV;
    else process.env.NODE_ENV = previousEnv;
    const { api: netease, prepare: prepareNetease } = require('./netease.cjs').createNetease(stateDir);
    const kugou = require('kugoumusicapi');
    return { qqApp, netease, kugou, prepareNetease };
}

async function createGateway({ stateDir, token, providers = createProviders(stateDir) }) {
    fs.mkdirSync(stateDir, { recursive: true });
    const server = http.createServer(async (req, res) => {
        if (!constantEqual(req.headers['x-folia-local-auth'], token)) return json(res, 403, { error: 'Local authentication required' });
        try {
            const url = new URL(req.url, 'http://127.0.0.1');
            if (url.pathname === '/health') return json(res, 200, { ready: true, node: process.version, providers: ['netease', 'qq', 'kugou'] });
            if (url.pathname === '/api/lyric-proxy') return await proxy(req, res, url);
            if (url.pathname.startsWith('/qq/')) {
                req.url = req.url.slice(3);
                providers.qqApp(req, res);
                return;
            }
            const match = /^\/(netease|kugou)\/([a-zA-Z0-9/_]+)$/.exec(url.pathname);
            if (!match) return json(res, 404, { error: 'Unknown route' });
            const [, id, route] = match;
            const operation = route.replace(/\//g, '_');
            const params = { ...Object.fromEntries(url.searchParams), ...await readBody(req) };
            if (id === 'netease') await providers.prepareNetease();
            const api = providers[id];
            if (!Object.hasOwn(api, operation) || typeof api[operation] !== 'function') return json(res, 404, { error: 'Unknown operation' });
            const answer = await api[operation](params);
            const body = answer?.body ?? answer;
            if (answer?.cookie?.length) res.setHeader('Set-Cookie', answer.cookie);
            json(res, Number(answer?.status) || 200, body);
        } catch (error) {
            if (res.headersSent) return res.destroy();
            const status = Number(error?.status);
            if (status >= 400 && status <= 599 && error.body) return json(res, status, error.body);
            json(res, 502, { code: 502, message: 'The built-in music service could not reach the music platform. Check your network and retry.' });
        }
    });
    await new Promise((resolve, reject) => { server.once('error', reject); server.listen(0, '127.0.0.1', resolve); });
    return server;
}

if (require.main === module) {
    const [, , stateDir, token, readyFile] = process.argv;
    createGateway({ stateDir, token }).then(server => {
        const temporary = readyFile + '.tmp';
        fs.writeFileSync(temporary, JSON.stringify({ port: server.address().port }), { mode: 0o600 });
        fs.renameSync(temporary, readyFile);
    }).catch(() => { fs.writeFileSync(readyFile + '.error', 'Built-in music service initialization failed'); process.exitCode = 1; });
}
module.exports = { createGateway, createProviders, allowedProxyTarget };

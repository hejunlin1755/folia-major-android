const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');

// Programmatic KuGou calls skip the HTTP server's device-cookie middleware; supply its stable identity here.
function createKugou(stateDir, api) {
    // Match the desktop integration's supported lite-client protocol before loading upstream modules.
    process.env.platform = 'lite';
    api ??= require('kugoumusicapi');
    const filename = path.join(stateDir, 'kugou-device.json');
    let device;
    try { device = JSON.parse(fs.readFileSync(filename, 'utf8')); } catch {}
    if (!device?.KUGOU_API_MID || !device?.KUGOU_API_GUID) {
        const guid = crypto.randomUUID().replaceAll('-', '').toUpperCase();
        device = {
            KUGOU_API_PLATFORM: 'lite', KUGOU_API_GUID: guid,
            KUGOU_API_MID: BigInt('0x' + crypto.createHash('md5').update(guid).digest('hex')).toString(),
            KUGOU_API_DEV: crypto.randomBytes(5).toString('hex').toUpperCase(),
            KUGOU_API_MAC: crypto.randomBytes(6).toString('hex').match(/../g).join(':').toUpperCase(),
            KUGOU_API_WEBGL: BigInt('0x' + crypto.randomBytes(8).toString('hex')).toString(),
        };
    }
    device.KUGOU_API_PLATFORM = 'lite';
    const save = () => fs.writeFileSync(filename, JSON.stringify(device), { mode: 0o600 });
    save();
    let registering;
    const authorizations = new Map();
    async function authorizedSongUrl(params, cookie) {
        const key = crypto.createHash('sha256').update(String(cookie.userid) + ':' + cookie.token).digest('hex');
        let account = authorizations.get(key);
        if (!account || Date.now() - account.at > 240000) {
            const verified = await api.user_verify({ cookie });
            const auth = verified?.body?.data?.auth;
            if (!auth) return null;
            account = { auth, at: Date.now() };
            authorizations.set(key, account);
            if (authorizations.size > 4) authorizations.delete(authorizations.keys().next().value);
        }
        const song = await api.song_auth({ ...params, auth: account.auth, cookie: { ...cookie, auth: account.auth } });
        const data = song?.body?.data;
        if (!data?.auth || !data?.open_time) return null;
        return api.song_url_auth({ ...params, auth: data.auth, open_time: data.open_time, cookie });
    }
    const parseCookie = value => typeof value === 'string'
        ? Object.fromEntries(value.split(';').map(entry => {
            const at = entry.indexOf('=');
            return at > 0 ? [entry.slice(0, at).trim(), entry.slice(at + 1).trim()] : null;
        }).filter(Boolean)) : value || {};
    async function register(cookie) {
        if (device.dfid) return;
        if (!registering) registering = (async () => {
            const result = await api.register_dev({ cookie: { ...cookie, ...device } });
            const body = result?.body ?? result;
            const dfid = body?.data?.dfid ?? body?.dfid;
            if (!dfid) throw new Error('KuGou device registration did not return dfid');
            device.dfid = String(dfid);
            save();
        })().finally(() => { registering = null; });
        await registering;
    }
    return Object.fromEntries(Object.entries(api).filter(([, value]) => typeof value === 'function').map(([operation, call]) => [operation, async (params = {}) => {
        const incoming = parseCookie(params.cookie);
        // Reads must remain available when the optional risk-registration endpoint is unreachable.
        // Retry registration only when the platform explicitly requests verification.

        const cookie = { ...incoming, ...device };
        let result;
        try { result = await call({ ...params, cookie }); } catch (error) {
            if (operation === 'register_dev' || Number(error?.body?.errcode ?? error?.body?.error_code) !== 20028) throw error;
            delete device.dfid;
            await register(incoming);
            result = await call({ ...params, cookie: { ...incoming, ...device } });
        }
        let body = result?.body ?? result;
        if (operation !== 'register_dev' && Number(body?.errcode ?? body?.error_code) === 20028) {
            delete device.dfid;
            await register(incoming);
            result = await call({ ...params, cookie: { ...incoming, ...device } });
            body = result?.body ?? result;
        }
        // Current clients authorize each track before requesting its URL; v1.6.0's legacy tracker can answer status:2 with no URL.
        const urls = body?.url ?? body?.data?.url;
        if (operation === 'song_url' && cookie.token && cookie.userid && (!urls || Array.isArray(urls) && !urls.length)
            && api.user_verify && api.song_auth && api.song_url_auth) {
            const authorized = await authorizedSongUrl(params, cookie);
            if (authorized) { result = authorized; body = result?.body ?? result; }
        }
        const dfid = body?.data?.dfid ?? body?.dfid;
        if (dfid) { device.dfid = String(dfid); save(); }
        // The renderer already retains account credentials; keep its device hint in sync without persisting tokens here.
        if (operation === 'login_qr_check' && body?.data?.status === 4 && body.data.token) body.data.dfid = device.dfid;
        return result;
    }]));
}
module.exports = { createKugou };

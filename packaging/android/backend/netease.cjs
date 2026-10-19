const fs = require('node:fs');
const path = require('node:path');

// Keep the desktop sign-in fixes: one stable identity, no invented client IP, and readable QR polling errors.
function createNetease(stateDir) {
    const packageId = '@neteasecloudmusicapienhanced/api';
    const temp = process.env.TMPDIR;
    const tokenPath = path.join(temp, 'anonymous_token');
    if (!fs.existsSync(tokenPath)) fs.writeFileSync(tokenPath, '', { mode: 0o600 });
    const util = require(packageId + '/util');
    const deviceFile = path.join(stateDir, 'netease-device-id');
    if (!fs.existsSync(deviceFile)) fs.writeFileSync(deviceFile, util.generateDeviceId(), { mode: 0o600 });
    global.deviceId = fs.readFileSync(deviceFile, 'utf8');
    const requestPath = require.resolve(packageId + '/util/request');
    const request = require(requestPath);
    const send = (uri, data, options = {}) => request(uri, { ...data }, { timeout: 20000, ...options, ...(options.randomCNIP ? {} : { ip: '' }) });
    require.cache[requestPath].exports = async (uri, data, options) => {
        try { return await send(uri, data, options); }
        catch (error) {
            if (!uri.startsWith('/api/login/qrcode/') || Number(error?.status) !== 502) throw error;
            await new Promise(resolve => setTimeout(resolve, 300));
            return send(uri, data, options);
        }
    };
    const checkPath = require.resolve(packageId + '/module/login_qr_check');
    require(checkPath);
    const createOption = require(packageId + '/util/option');
    require.cache[checkPath].exports = async (query, perform) => {
        const answer = await perform('/api/login/qrcode/client/login', { key: query.key, type: 3 }, createOption(query));
        return { status: 200, body: { ...answer.body, cookie: (answer.cookie || []).join(';') }, cookie: answer.cookie || [] };
    };
    const api = require(packageId);
    let keyReady = null;
    const prepare = () => {
        if (!keyReady) keyReady = (async () => {
            const keyPath = path.join(temp, 'xeapi_public_key');
            let cached = {};
            try { cached = JSON.parse(fs.readFileSync(keyPath, 'utf8')); } catch {}
            const { getXeapiPublicKey } = require(packageId + '/util/xeapiKey');
            let timer;
            try {
                const key = await Promise.race([
                    getXeapiPublicKey(cached, global.deviceId),
                    new Promise((_, reject) => { timer = setTimeout(() => reject(new Error('Key refresh timed out')), 12000); }),
                ]);
                fs.writeFileSync(keyPath, JSON.stringify(key), { mode: 0o600 });
            } catch (error) { if (!cached.sk) throw error; }
            finally { clearTimeout(timer); }
        })().catch(error => { keyReady = null; throw error; });
        return keyReady;
    };
    return { api, prepare };
}
module.exports = { createNetease };

import { createHash } from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { createRequire } from 'node:module';

// Bundle pinned JS backends and a checksum-verified, 16-KB-aligned Android Node runtime.
const directory = path.dirname(fileURLToPath(import.meta.url));
const require = createRequire(import.meta.url);
const { zipSync, unzipSync } = require('fflate');
const backend = path.join(directory, 'backend');
const cache = path.join(directory, '.cache');
const runtime = path.join(cache, 'runtime');
const runtimeHash = '9b068f4ad4c755f26c04b31883c536f037a4831e6575847a699cdf5fa72dde25';
if (!fs.existsSync(path.join(backend, 'node_modules/@neteasecloudmusicapienhanced/api/package.json'))) {
    throw new Error('[AndroidMusic] Run npm ci --prefix packaging/android/backend --ignore-scripts first.');
}
fs.mkdirSync(cache, { recursive: true });
const archivePath = path.join(cache, 'node-mobile24.zip');
if (!fs.existsSync(archivePath)) {
    console.log('[AndroidMusic] Downloading pinned Android Node 24 runtime.');
    const response = await fetch('https://github.com/fogtape/nodejs-mobile/releases/download/v24.22.0-0/nodejs-mobile-android-24.22.0-0.zip');
    if (!response.ok) throw new Error(`[AndroidMusic] Runtime download failed: ${response.status}`);
    fs.writeFileSync(archivePath, Buffer.from(await response.arrayBuffer()));
}
const archive = fs.readFileSync(archivePath);
if (createHash('sha256').update(archive).digest('hex') !== runtimeHash) throw new Error('[AndroidMusic] Runtime checksum mismatch.');
if (!fs.existsSync(path.join(runtime, '.verified'))) {
    for (const [name, bytes] of Object.entries(unzipSync(archive))) {
        if (!/^(include\/|bin\/(arm64-v8a|armeabi-v7a|x86_64)\/libnode\.so$)/.test(name) || name.includes('..') || name.endsWith('/')) continue;
        const destination = path.join(runtime, name);
        fs.mkdirSync(path.dirname(destination), { recursive: true });
        fs.writeFileSync(destination, bytes);
    }
    fs.writeFileSync(path.join(runtime, '.verified'), runtimeHash);
}
const entries = {};
function collect(location, prefix = '') {
    for (const item of fs.readdirSync(location, { withFileTypes: true })) {
        if (item.name === '.bin' || item.isSymbolicLink()) continue;
        const name = prefix + item.name;
        if (item.isDirectory()) collect(path.join(location, item.name), name + '/');
        else if (item.isFile()) entries[name] = fs.readFileSync(path.join(location, item.name));
    }
}
collect(backend);
const zip = zipSync(entries, { level: 6 });
const assets = path.join(directory, 'android/app/src/main/assets');
fs.mkdirSync(assets, { recursive: true });
fs.writeFileSync(path.join(assets, 'music-backend.zip'), zip);
fs.writeFileSync(path.join(assets, 'music-backend.sha256'), createHash('sha256').update(zip).digest('hex'));
console.log(`[AndroidMusic] Bundled ${Object.keys(entries).length} files (${(zip.length / 1048576).toFixed(1)} MiB).`);

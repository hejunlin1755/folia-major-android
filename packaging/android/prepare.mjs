import { existsSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';
import path from 'node:path';

// Builds the bundled Web app, then creates and synchronizes the isolated Android project.
const directory = path.dirname(fileURLToPath(import.meta.url));
const root = path.resolve(directory, '../..');
const vite = path.join(root, 'node_modules/vite/bin/vite.js');
const capacitor = path.join(directory, 'node_modules/@capacitor/cli/bin/capacitor');

function run(script, args, cwd, env = process.env) {
    const result = spawnSync(process.execPath, [script, ...args], { cwd, env, stdio: 'inherit' });
    if (result.error) throw result.error;
    if (result.status !== 0) process.exit(result.status ?? 1);
}

if (!existsSync(vite) || !existsSync(capacitor)) {
    throw new Error('[Android] Run npm ci and pnpm --dir packaging/android install --frozen-lockfile --ignore-scripts first.');
}
run(vite, ['build', '--mode', 'android'], root, { ...process.env, ELECTRON: 'false' });
if (!existsSync(path.join(directory, 'android'))) {
    run(capacitor, ['add', 'android'], directory);
}
run(capacitor, ['sync', 'android'], directory);
run(path.join(directory, 'prepareBackend.mjs'), [], root);

package top.izuna.folia.android;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.util.Log;
import android.webkit.WebView;
import java.io.InputStream;
import java.security.MessageDigest;

// Retire the previous APK's PWA shell on upgrade while keeping accounts, music and local covers.
final class AndroidWebAssets {
    static final String PREFERENCES = "folia-web-assets";
    static final String DIGEST = "installed-shell-digest";
    private final SharedPreferences preferences;
    private final String digest;
    private boolean complete;
    private boolean running;

    AndroidWebAssets(Context context) {
        preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
        String bundledDigest = null;
        try (InputStream stream = context.getAssets().open("public/index.html")) {
            MessageDigest hash = MessageDigest.getInstance("SHA-256");
            byte[] bytes = new byte[8192];
            for (int count; (count = stream.read(bytes)) != -1;) hash.update(bytes, 0, count);
            StringBuilder result = new StringBuilder();
            for (byte value : hash.digest()) result.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
            bundledDigest = result.toString();
            String previous = preferences.getString(DIGEST, null);
            complete = bundledDigest.equals(previous);
            PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            if (previous == null && info.firstInstallTime == info.lastUpdateTime) {
                preferences.edit().putString(DIGEST, bundledDigest).apply();
                complete = true;
            }
        } catch (Exception error) {
            Log.e("FoliaWebAssets", "[Android] Could not identify bundled Web assets", error);
        }
        digest = bundledDigest;
    }

    void onPageFinished(WebView view, String url) {
        if (complete || running || digest == null || !"localhost".equals(Uri.parse(url).getHost())) return;
        running = true;
        // This script comes from native code: an old cached index cannot opt out of the migration.
        // Only Workbox app files are removed; localStorage, IndexedDB, OPFS and cover caches survive.
        view.evaluateJavascript("window.__foliaAndroidAssetsReset='pending';(async()=>{"
            + "if('serviceWorker' in navigator){const registrations=await navigator.serviceWorker.getRegistrations();await Promise.all(registrations.map(r=>r.unregister()));}"
            + "if('caches' in window){const names=await caches.keys();await Promise.all(names.filter(n=>n.startsWith('workbox-')).map(n=>caches.delete(n)));}"
            + "window.__foliaAndroidAssetsReset='done';})().catch(()=>{window.__foliaAndroidAssetsReset='failed';});void 0;", null);
        awaitReset(view, 0);
    }

    private void awaitReset(WebView view, int attempt) {
        // Retiring large Workbox stores can exceed ten seconds on a busy mobile storage device.
        // Evaluate a boolean so the callback does not depend on WebView's string quoting.
        view.postDelayed(() -> view.evaluateJavascript("window.__foliaAndroidAssetsReset==='done'", result -> {
            if ("true".equals(result)) {
                preferences.edit().putString(DIGEST, digest).apply();
                complete = true;
                view.clearCache(true);
                view.reload();
            } else if (attempt < 479) {
                awaitReset(view, attempt + 1);
            } else {
                running = false;
                Log.w("FoliaWebAssets", "[Android] Web shell refresh did not complete; will retry next launch");
            }
        }), 250);
    }
}

package top.izuna.folia.android;

import android.content.Context;
import android.content.SharedPreferences;
import android.webkit.WebView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

// An APK upgrade must remove a stale app shell without deleting a saved account or cover.
@RunWith(AndroidJUnit4.class)
public class WebAssetsUpgradeTest {
    private String evaluate(WebView view, String script) throws Exception {
        CountDownLatch finished = new CountDownLatch(1);
        AtomicReference<String> value = new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> view.evaluateJavascript(script, result -> { value.set(result); finished.countDown(); }));
        assertTrue("WebView did not respond", finished.await(15, TimeUnit.SECONDS));
        return value.get();
    }

    private void waitFor(WebView view, String condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(40);
        while (System.nanoTime() < deadline) {
            if ("true".equals(evaluate(view, condition))) return;
            Thread.sleep(250);
        }
        fail("WebView condition timed out: " + condition + "; " + evaluate(view, "JSON.stringify({ready:document.readyState,android:document.documentElement.dataset.android,splash:!!document.getElementById('app-splash'),reset:window.__foliaAndroidAssetsReset,fixture:window.__upgradeFixture,error:window.__upgradeFixtureError})"));
    }

    @Test(timeout = 120000) public void staleShellIsRetiredAndSavedDataSurvives() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            AtomicReference<WebView> reference = new AtomicReference<>();
            AtomicReference<SharedPreferences> preferencesReference = new AtomicReference<>();
            scenario.onActivity(activity -> {
                reference.set(activity.getBridge().getWebView());
                preferencesReference.set(activity.getSharedPreferences(AndroidWebAssets.PREFERENCES, Context.MODE_PRIVATE));
            });
            SharedPreferences preferences = preferencesReference.get();
            WebView view = reference.get();
            MessageDigest hash = MessageDigest.getInstance("SHA-256");
            try (InputStream input = context.getAssets().open("public/index.html")) {
                byte[] buffer = new byte[8192];
                for (int length; (length = input.read(buffer)) != -1;) hash.update(buffer, 0, length);
            }
            StringBuilder expected = new StringBuilder();
            for (byte value : hash.digest()) expected.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
            // Seed the current install before arranging an upgrade; an existing singleTask activity
            // may retain the completed migration owner from the preceding manual UI checks.
            assertTrue(preferences.edit().putString(AndroidWebAssets.DIGEST, expected.toString()).commit());
            waitFor(view, "document.readyState === 'complete' && document.documentElement.dataset.android === 'true' && !document.getElementById('app-splash')");
            evaluate(view, "window.__upgradeFixture=false;(async()=>{localStorage.setItem('__foliaUpgradeSavedAccount','keep');await(await caches.open('workbox-upgrade-regression')).put('/stale-app-shell',new Response('old'));await(await caches.open('folia-cover-upgrade-regression')).put('/saved-cover',new Response('keep'));window.__upgradeFixture=true;})().catch(error=>{window.__upgradeFixtureError=String(error);});void 0;");
            waitFor(view, "window.__upgradeFixture===true");
            assertTrue(preferences.edit().putString(AndroidWebAssets.DIGEST, "previous-apk").commit());
            // An APK upgrade starts a new migration owner. Exercise it against the loaded WebView;
            // ActivityScenario.recreate also restores Capacitor's unrelated saved bridge state.
            scenario.onActivity(activity -> {
                WebView current = activity.getBridge().getWebView();
                new AndroidWebAssets(activity).onPageFinished(current, current.getUrl());
            });
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(40);
            while (System.nanoTime() < deadline && "previous-apk".equals(preferences.getString(AndroidWebAssets.DIGEST, null))) Thread.sleep(250);
            assertNotEquals("The old shell was not retired: " + evaluate(view, "window.__foliaAndroidAssetsReset"), "previous-apk", preferences.getString(AndroidWebAssets.DIGEST, null));
            waitFor(view, "document.readyState === 'complete' && document.documentElement.dataset.android === 'true' && !document.getElementById('app-splash') && typeof window.__upgradeFixture === 'undefined'");
            evaluate(view, "window.__upgradeVerified=false;(async()=>{const old=await caches.has('workbox-upgrade-regression');const cover=await caches.open('folia-cover-upgrade-regression');const saved=await cover.match('/saved-cover');window.__upgradeVerified=!old&&!!saved&&(await saved.text())==='keep'&&localStorage.getItem('__foliaUpgradeSavedAccount')==='keep';})();void 0;");
            waitFor(view, "window.__upgradeVerified===true");
            evaluate(view, "localStorage.removeItem('__foliaUpgradeSavedAccount');void caches.delete('folia-cover-upgrade-regression');void 0;");
        }
    }
}

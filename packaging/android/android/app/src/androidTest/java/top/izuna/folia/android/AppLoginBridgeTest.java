package top.izuna.folia.android;

import android.webkit.WebView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

// Exercise production JavaScript -> Capacitor plugin -> packaged Node -> each platform's QR endpoint.
@RunWith(AndroidJUnit4.class)
public class AppLoginBridgeTest {
    private String evaluate(WebView view, String script) throws Exception {
        CountDownLatch finished = new CountDownLatch(1);
        AtomicReference<String> value = new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> view.evaluateJavascript(script, result -> { value.set(result); finished.countDown(); }));
        if (!finished.await(5, TimeUnit.SECONDS)) throw new AssertionError("WebView JavaScript did not respond");
        return value.get();
    }
    @Test(timeout = 240000) public void productionPageCanInitializeAllThreeSignIns() throws Exception {
        NodeMusicRuntime.ready(InstrumentationRegistry.getInstrumentation().getTargetContext()).get(90, TimeUnit.SECONDS);
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            AtomicReference<WebView> webView = new AtomicReference<>();
            scenario.onActivity(activity -> webView.set(activity.getBridge().getWebView()));
            WebView view = webView.get();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(40);
            while (System.nanoTime() < deadline && !"true".equals(evaluate(view, "document.documentElement.dataset.android === 'true'"))) Thread.sleep(500);
            assertEquals("Android music fetch bridge was not installed", "true", evaluate(view, "document.documentElement.dataset.android === 'true'"));
            assertEquals("WebView edge flash must remain disabled", android.view.View.OVER_SCROLL_NEVER, view.getOverScrollMode());
            String script = "window.__foliaQrSmoke={pending:true};Promise.all(['netease','qq','kugou'].map(async id=>{const r=await fetch('/android-music/'+id+'/login/qr/key');const b=await r.json();const d=b.data||b;return {provider:id,status:r.status,hasKey:!!(d.unikey||d.qrcode||d.qrkey||d.key||d.ticket)};})).then(results=>window.__foliaQrSmoke={results}).catch(e=>window.__foliaQrSmoke={error:e.message});void 0;";
            evaluate(view, script);
            deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(75);
            String passed = "false";
            while (System.nanoTime() < deadline) {
                passed = evaluate(view, "!!window.__foliaQrSmoke.results && window.__foliaQrSmoke.results.length===3 && window.__foliaQrSmoke.results.every(x=>x.status===200&&x.hasKey)");
                if ("true".equals(passed) || "true".equals(evaluate(view, "!!window.__foliaQrSmoke.error"))) break;
                Thread.sleep(1000);
            }
            assertEquals("QR bridge result: " + evaluate(view, "JSON.stringify(window.__foliaQrSmoke)"), "true", passed);
        }
    }
}
package top.izuna.folia.android;

import android.graphics.Color;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import androidx.activity.OnBackPressedCallback;
import androidx.core.view.WindowCompat;
import android.webkit.ServiceWorkerClient;
import android.webkit.ServiceWorkerController;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import com.getcapacitor.BridgeActivity;
import com.getcapacitor.BridgeWebViewClient;
import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.util.Collections;

// Dark native surfaces and one inset owner prevent white navigation strips and overscroll flashes.
public class MainActivity extends BridgeActivity {
    private static final int BACKGROUND = Color.rgb(9, 9, 11);
    @Override public void onCreate(Bundle savedInstanceState) {
        registerPlugin(FoliaMusicBackendPlugin.class);
        registerPlugin(FoliaPlaybackPlugin.class);
        super.onCreate(savedInstanceState);
        applyDarkSystemBars();
        View content = findViewById(android.R.id.content);
        content.setBackgroundColor(BACKGROUND);
        ViewCompat.setOnApplyWindowInsetsListener(content, (view, insets) -> {
            Insets safe = insets.getInsets(WindowInsetsCompat.Type.ime());
            if (view.getPaddingLeft() != safe.left || view.getPaddingTop() != safe.top || view.getPaddingRight() != safe.right || view.getPaddingBottom() != safe.bottom) view.setPadding(safe.left, safe.top, safe.right, safe.bottom);
            if (bridge != null) {
                Insets cutout = insets.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.displayCutout());
                float density = getResources().getDisplayMetrics().density;
                bridge.getWebView().evaluateJavascript("document.documentElement.style.setProperty('--folia-native-safe-top','" + cutout.top / density + "px');document.documentElement.style.setProperty('--folia-native-safe-left','" + cutout.left / density + "px');document.documentElement.style.setProperty('--folia-native-safe-right','" + cutout.right / density + "px');void 0;", null);
            }
            return WindowInsetsCompat.CONSUMED;
        });
        ViewCompat.requestApplyInsets(content);
        if (bridge == null) return;
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                WindowInsetsCompat insets = ViewCompat.getRootWindowInsets(content);
                if (insets != null && insets.isVisible(WindowInsetsCompat.Type.ime())) {
                    new WindowInsetsControllerCompat(getWindow(), content).hide(WindowInsetsCompat.Type.ime());
                    return;
                }
                bridge.getWebView().evaluateJavascript("typeof window.__foliaAndroidBack==='function'?window.__foliaAndroidBack():true", result -> {
                    if ("false".equals(result)) moveTaskToBack(true);
                });
            }
        });
        bridge.getWebView().setBackgroundColor(BACKGROUND);
        bridge.getWebView().setOverScrollMode(View.OVER_SCROLL_NEVER);
        if (Build.VERSION.SDK_INT >= 26) bridge.getWebView().setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false);
        AndroidWebAssets webAssets = new AndroidWebAssets(this);
        bridge.setWebViewClient(new BridgeWebViewClient(bridge) {
            @Override public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                webAssets.onPageFinished(view, url);
                ViewCompat.requestApplyInsets(content);
            }
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                WebResourceResponse proxy = interceptProxy(request);
                return proxy == null ? super.shouldInterceptRequest(view, request) : proxy;
            }
        });
        ServiceWorkerController.getInstance().setServiceWorkerClient(new ServiceWorkerClient() {
            @Override public WebResourceResponse shouldInterceptRequest(WebResourceRequest request) {
                WebResourceResponse proxy = interceptProxy(request);
                return proxy == null ? bridge.getLocalServer().shouldInterceptRequest(request) : proxy;
            }
        });
    }
    private void applyDarkSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        if (Build.VERSION.SDK_INT >= 28) {
            WindowManager.LayoutParams attributes = getWindow().getAttributes();
            attributes.layoutInDisplayCutoutMode = Build.VERSION.SDK_INT >= 30
                ? WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                : WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(attributes);
        }
        getWindow().getDecorView().setBackgroundColor(BACKGROUND);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        if (Build.VERSION.SDK_INT >= 29) getWindow().setNavigationBarContrastEnforced(false);
        WindowInsetsControllerCompat controller = new WindowInsetsControllerCompat(getWindow(), getWindow().getDecorView());
        controller.setAppearanceLightNavigationBars(false);
        controller.setAppearanceLightStatusBars(false);
        controller.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        controller.hide(WindowInsetsCompat.Type.systemBars());
    }
    @Override public void onResume() {
        super.onResume();
        applyDarkSystemBars();
    }
    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) applyDarkSystemBars();
    }
    @Override public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        applyDarkSystemBars();
        ViewCompat.requestApplyInsets(findViewById(android.R.id.content));
    }
    private WebResourceResponse interceptProxy(WebResourceRequest request) {
        if (!"localhost".equals(request.getUrl().getHost()) || !("/api/lyric-proxy".equals(request.getUrl().getPath()) || "/api/music-media".equals(request.getUrl().getPath()))) return null;
        try {
            HttpURLConnection connection = NodeMusicRuntime.connect(this, request.getUrl().getEncodedPath() + "?" + request.getUrl().getEncodedQuery(), "GET", request.getRequestHeaders(), null);
            int status = connection.getResponseCode();
            String type = connection.getContentType();
            InputStream input = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            InputStream closing = new FilterInputStream(input == null ? new ByteArrayInputStream(new byte[0]) : input) {
                @Override public void close() throws java.io.IOException { try { super.close(); } finally { connection.disconnect(); } }
            };
            java.util.Map<String, String> headers = new java.util.HashMap<>();
            headers.put("Cache-Control", "no-store");
            for (String name : new String[] { "Content-Range", "Content-Length", "Accept-Ranges", "ETag", "Last-Modified" }) {
                if (connection.getHeaderField(name) != null) headers.put(name, connection.getHeaderField(name));
            }
            return new WebResourceResponse(type == null ? "application/octet-stream" : type.split(";")[0], null, status, status < 400 ? "OK" : "Upstream error", headers, closing);
        } catch (Exception error) {
            return new WebResourceResponse("application/json", "UTF-8", 502, "Music service unavailable", Collections.emptyMap(), new ByteArrayInputStream("{\"error\":\"Built-in proxy unavailable\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        }
    }
}

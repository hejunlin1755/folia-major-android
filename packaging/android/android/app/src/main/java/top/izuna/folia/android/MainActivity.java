package top.izuna.folia.android;

import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
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
        super.onCreate(savedInstanceState);
        getWindow().getDecorView().setBackgroundColor(BACKGROUND);
        getWindow().setNavigationBarColor(BACKGROUND);
        getWindow().setStatusBarColor(BACKGROUND);
        if (Build.VERSION.SDK_INT >= 29) getWindow().setNavigationBarContrastEnforced(false);
        WindowInsetsControllerCompat controller = new WindowInsetsControllerCompat(getWindow(), getWindow().getDecorView());
        controller.setAppearanceLightNavigationBars(false);
        controller.setAppearanceLightStatusBars(false);
        View content = findViewById(android.R.id.content);
        content.setBackgroundColor(BACKGROUND);
        ViewCompat.setOnApplyWindowInsetsListener(content, (view, insets) -> {
            Insets safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout() | WindowInsetsCompat.Type.ime());
            if (view.getPaddingLeft() != safe.left || view.getPaddingTop() != safe.top || view.getPaddingRight() != safe.right || view.getPaddingBottom() != safe.bottom) view.setPadding(safe.left, safe.top, safe.right, safe.bottom);
            return WindowInsetsCompat.CONSUMED;
        });
        ViewCompat.requestApplyInsets(content);
        if (bridge == null) return;
        bridge.getWebView().setBackgroundColor(BACKGROUND);
        bridge.getWebView().setOverScrollMode(View.OVER_SCROLL_NEVER);
        bridge.setWebViewClient(new BridgeWebViewClient(bridge) {
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
    private WebResourceResponse interceptProxy(WebResourceRequest request) {
        if (!"localhost".equals(request.getUrl().getHost()) || !"/api/lyric-proxy".equals(request.getUrl().getPath())) return null;
        try {
            HttpURLConnection connection = NodeMusicRuntime.connect(this, request.getUrl().getEncodedPath() + "?" + request.getUrl().getEncodedQuery(), "GET", request.getRequestHeaders(), null);
            int status = connection.getResponseCode();
            String type = connection.getContentType();
            InputStream input = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            InputStream closing = new FilterInputStream(input == null ? new ByteArrayInputStream(new byte[0]) : input) {
                @Override public void close() throws java.io.IOException { try { super.close(); } finally { connection.disconnect(); } }
            };
            return new WebResourceResponse(type == null ? "application/octet-stream" : type.split(";")[0], null, status, status < 400 ? "OK" : "Upstream error", Collections.singletonMap("Cache-Control", "no-store"), closing);
        } catch (Exception error) {
            return new WebResourceResponse("application/json", "UTF-8", 502, "Music service unavailable", Collections.emptyMap(), new ByteArrayInputStream("{\"error\":\"Built-in proxy unavailable\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        }
    }
}

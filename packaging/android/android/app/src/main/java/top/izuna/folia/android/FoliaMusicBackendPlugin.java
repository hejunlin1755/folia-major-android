package top.izuna.folia.android;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;

// Exposes app-private requests, never the local authentication key or an arbitrary network destination.
@CapacitorPlugin(name = "FoliaMusicBackend")
public class FoliaMusicBackendPlugin extends Plugin {
    private final ExecutorService requests = Executors.newFixedThreadPool(6);
    @Override public void load() { NodeMusicRuntime.ready(getContext()); }
    @PluginMethod public void ready(PluginCall call) {
        NodeMusicRuntime.ready(getContext()).whenComplete((endpoint, error) -> {
            if (error != null) call.reject("ANDROID_MUSIC_BACKEND_UNAVAILABLE");
            else { JSObject result = new JSObject(); result.put("ready", true); call.resolve(result); }
        });
    }
    @PluginMethod public void request(PluginCall call) {
        requests.execute(() -> {
            HttpURLConnection connection = null;
            try {
                String path = call.getString("path", "");
                if (!(path.startsWith("/netease/") || path.startsWith("/qq/") || path.startsWith("/kugou/") || path.equals("/health"))) throw new IllegalArgumentException("Unsupported local route");
                Map<String, String> headers = new HashMap<>();
                JSObject rawHeaders = call.getObject("headers", new JSObject());
                for (Iterator<String> keys = rawHeaders.keys(); keys.hasNext();) { String key = keys.next(); headers.put(key, rawHeaders.getString(key)); }
                connection = NodeMusicRuntime.connect(getContext(), path, call.getString("method", "GET"), headers, call.getString("body"));
                int status = connection.getResponseCode();
                JSObject responseHeaders = new JSObject();
                for (Map.Entry<String, List<String>> header : connection.getHeaderFields().entrySet()) if (header.getKey() != null) responseHeaders.put(header.getKey(), android.text.TextUtils.join(", ", header.getValue()));
                String body;
                try (InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream()) { body = stream == null ? "" : new String(NodeMusicRuntime.readAll(stream), StandardCharsets.UTF_8); }
                JSObject result = new JSObject(); result.put("status", status); result.put("headers", responseHeaders); result.put("body", body); call.resolve(result);
            } catch (Exception error) { call.reject("ANDROID_MUSIC_BACKEND_UNAVAILABLE: " + error.getClass().getSimpleName()); }
            finally { if (connection != null) connection.disconnect(); }
        });
    }
}

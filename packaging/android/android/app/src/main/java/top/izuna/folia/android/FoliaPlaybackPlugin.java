package top.izuna.folia.android;

import android.content.Intent;
import androidx.core.content.ContextCompat;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import java.lang.ref.WeakReference;

// The native media session controls the same audio deck and queue as the application UI.
@CapacitorPlugin(name = "FoliaPlayback")
public class FoliaPlaybackPlugin extends Plugin {
    private static WeakReference<FoliaPlaybackPlugin> active = new WeakReference<>(null);
    @Override public void load() { active = new WeakReference<>(this); }

    static void dispatch(String action, long position) {
        FoliaPlaybackPlugin plugin = active.get();
        if (plugin == null || plugin.getActivity() == null) return;
        JSObject event = new JSObject();
        event.put("action", action);
        event.put("position", position / 1000.0);
        plugin.getActivity().runOnUiThread(() -> plugin.getBridge().getWebView().evaluateJavascript(
            "window.__foliaAndroidMediaAction?.(" + event.toString() + ");void 0;", null));
    }

    @PluginMethod public void update(PluginCall call) {
        if (call.getBoolean("clear", false)) {
            getContext().stopService(new Intent(getContext(), FoliaPlaybackService.class));
            call.resolve();
            return;
        }
        boolean playing = call.getBoolean("playing", false);
        Intent intent = new Intent(getContext(), FoliaPlaybackService.class);
        intent.putExtra("state", call.getData().toString());
        try {
            // A paused track must not start a new background foreground service.
            if (playing) ContextCompat.startForegroundService(getContext(), intent);
            else if (FoliaPlaybackService.isRunning()) getContext().startService(intent);
            call.resolve();
        } catch (RuntimeException error) { call.reject("Native playback service unavailable", error); }
    }

    @Override protected void handleOnDestroy() {
        if (active.get() == this) {
            active.clear();
            getContext().stopService(new Intent(getContext(), FoliaPlaybackService.class));
        }
    }
}

package top.izuna.folia.android;

import android.content.Intent;
import android.Manifest;
import android.os.Build;
import android.content.Context;
import android.content.pm.PackageManager;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;
import androidx.core.content.ContextCompat;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import java.lang.ref.WeakReference;

// The native media session controls the same audio deck and queue as the application UI.
@CapacitorPlugin(name = "FoliaPlayback", permissions = { @Permission(alias = "playbackNotifications", strings = { Manifest.permission.POST_NOTIFICATIONS }) })
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
            // Ask once, while the app is visible, without making audio depend on the answer.
            android.content.SharedPreferences preferences = getContext().getSharedPreferences("folia-playback", Context.MODE_PRIVATE);
            if (playing && Build.VERSION.SDK_INT >= 33 && getBridge().getApp().isActive()
                && ContextCompat.checkSelfPermission(getContext(), Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                && !preferences.getBoolean("notificationPermissionRequested", false)) {
                preferences.edit().putBoolean("notificationPermissionRequested", true).apply();
                getActivity().runOnUiThread(() -> {
                    try { requestPermissionForAlias("playbackNotifications", call, "notificationPermissionResult"); }
                    catch (RuntimeException error) { call.resolve(); }
                });
                return;
            }
            call.resolve();
        } catch (RuntimeException error) { call.reject("Native playback service unavailable", error); }
    }

    @PermissionCallback private void notificationPermissionResult(PluginCall call) { call.resolve(); }

    @Override protected void handleOnDestroy() {
        if (active.get() == this) {
            active.clear();
            getContext().stopService(new Intent(getContext(), FoliaPlaybackService.class));
        }
    }
}

package top.izuna.folia.android;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Base64;
import org.json.JSONObject;

// A media foreground service keeps the WebView audio deck alive and publishes Android controls.
public class FoliaPlaybackService extends Service {
    private static final int NOTIFICATION = 731;
    private static final String CHANNEL = "folia-playback";
    private static boolean running;
    private MediaSession session;
    private PowerManager.WakeLock wakeLock;
    private boolean playing;
    private boolean foreground;
    private String title = "Folia", artist = "", album = "", artwork = "";
    private Bitmap cover;
    private long duration, position;
    private final BroadcastReceiver noisy = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { command("pause", 0); }
    };
    static boolean isRunning() { return running; }

    @Override public void onCreate() {
        super.onCreate();
        running = true;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(CHANNEL, "Folia", NotificationManager.IMPORTANCE_LOW);
            channel.setSound(null, null);
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
        wakeLock = ((PowerManager) getSystemService(POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Folia:Playback");
        wakeLock.setReferenceCounted(false);
        session = new MediaSession(this, "Folia");
        session.setSessionActivity(openApp());
        session.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { command("play", 0); }
            @Override public void onPause() { command("pause", 0); }
            @Override public void onSkipToNext() { command("nexttrack", 0); }
            @Override public void onSkipToPrevious() { command("previoustrack", 0); }
            @Override public void onSeekTo(long value) { command("seekto", value); }
            @Override public void onStop() { command("pause", 0); stopSelf(); }
        });
        session.setActive(true);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(noisy, new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(noisy, new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY));
    }

    private void command(String action, long value) {
        // Android 15 requires foreground playback before the WebView can regain audio focus.
        if ("play".equals(action) && !foreground) { startForeground(NOTIFICATION, notification()); foreground = true; }
        FoliaPlaybackPlugin.dispatch(action, value);
    }
    private PendingIntent openApp() {
        return PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
    private Notification.Action action(String name, int icon, int request) {
        Intent intent = new Intent(this, FoliaPlaybackService.class).setAction(name);
        return new Notification.Action.Builder(icon, name, PendingIntent.getService(this, request, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE)).build();
    }
    private Notification notification() {
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        return builder.setSmallIcon(R.drawable.ic_stat_playback)
            .setContentTitle(title).setContentText(artist).setSubText(album).setLargeIcon(cover)
            .setContentIntent(openApp()).setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true).setShowWhen(false).setOngoing(playing)
            .addAction(action("previoustrack", android.R.drawable.ic_media_previous, 1))
            .addAction(action(playing ? "pause" : "play", playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play, 2))
            .addAction(action("nexttrack", android.R.drawable.ic_media_next, 3))
            .setStyle(new Notification.MediaStyle().setMediaSession(session.getSessionToken()).setShowActionsInCompactView(0, 1, 2))
            .build();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) { stopSelf(); return START_NOT_STICKY; }
        if (intent.getAction() != null) { command(intent.getAction(), 0); return START_NOT_STICKY; }
        try {
            JSONObject state = new JSONObject(intent.getStringExtra("state"));
            title = state.optString("title", "Folia"); artist = state.optString("artist"); album = state.optString("album");
            playing = state.optBoolean("playing");
            duration = Math.max(0, (long) (state.optDouble("duration", 0) * 1000));
            position = Math.max(0, Math.min(duration, (long) (state.optDouble("position", 0) * 1000)));
            String nextArtwork = state.optString("artwork");
            if (!nextArtwork.equals(artwork)) {
                artwork = nextArtwork;
                cover = null;
                if (artwork.startsWith("data:image/") && artwork.length() < 800000) {
                    byte[] bytes = Base64.decode(artwork.substring(artwork.indexOf(',') + 1), Base64.DEFAULT);
                    cover = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                }
            }
            session.setMetadata(new MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, artist).putString(MediaMetadata.METADATA_KEY_ALBUM, album)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, duration).putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, cover).build());
            session.setPlaybackState(new PlaybackState.Builder().setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE |
                PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS |
                PlaybackState.ACTION_SEEK_TO | PlaybackState.ACTION_STOP)
                .setState(playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED, position, playing ? 1 : 0, SystemClock.elapsedRealtime()).build());
            if (playing) {
                startForeground(NOTIFICATION, notification());
                foreground = true;
                if (!wakeLock.isHeld()) wakeLock.acquire();
            } else {
                if (wakeLock.isHeld()) wakeLock.release();
                if (foreground) { stopForeground(false); foreground = false; }
                getSystemService(NotificationManager.class).notify(NOTIFICATION, notification());
            }
        } catch (Exception error) { stopSelf(); }
        return START_NOT_STICKY;
    }
    @Override public void onTaskRemoved(Intent rootIntent) { command("pause", 0); stopSelf(); }
    @Override public void onDestroy() {
        running = false;
        unregisterReceiver(noisy);
        if (wakeLock.isHeld()) wakeLock.release();
        session.setActive(false);
        session.release();
        getSystemService(NotificationManager.class).cancel(NOTIFICATION);
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}

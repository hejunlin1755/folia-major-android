package top.izuna.folia.android;

import android.content.Context;
import org.json.JSONObject;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import java.security.SecureRandom;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

// A single app-private Node instance. The only socket binds to loopback and requires a random per-process key.
public final class NodeMusicRuntime {
    private static CompletableFuture<Endpoint> startup;
    private static native int startNode(String[] arguments);
    public static final class Endpoint {
        public final int port;
        private final String token;
        Endpoint(int port, String token) { this.port = port; this.token = token; }
    }

    public static synchronized CompletableFuture<Endpoint> ready(Context context) {
        if (startup != null) return startup;
        Context app = context.getApplicationContext();
        startup = CompletableFuture.supplyAsync(() -> {
            try {
                String digest;
                try (InputStream in = app.getAssets().open("music-backend.sha256")) {
                    digest = new String(readAll(in), StandardCharsets.UTF_8).trim();
                }
                File directory = new File(app.getFilesDir(), "music-runtime-" + digest.substring(0, 12));
                File complete = new File(directory, ".complete");
                if (!complete.isFile()) {
                    directory.mkdirs();
                    String prefix = directory.getCanonicalPath() + File.separator;
                    try (ZipInputStream zip = new ZipInputStream(app.getAssets().open("music-backend.zip"))) {
                        ZipEntry entry;
                        while ((entry = zip.getNextEntry()) != null) {
                            File target = new File(directory, entry.getName());
                            if (!target.getCanonicalPath().startsWith(prefix)) throw new IOException("Invalid backend asset path");
                            if (entry.isDirectory()) { target.mkdirs(); continue; }
                            target.getParentFile().mkdirs();
                            try (OutputStream out = new FileOutputStream(target)) { copy(zip, out); }
                        }
                    }
                    complete.createNewFile();
                }
                File state = new File(app.getFilesDir(), "music-state");
                state.mkdirs();
                File readyFile = new File(app.getCacheDir(), "music-" + System.nanoTime() + ".json");
                byte[] random = new byte[32];
                new SecureRandom().nextBytes(random);
                StringBuilder token = new StringBuilder();
                for (byte value : random) token.append(String.format("%02x", value & 255));
                System.loadLibrary("node");
                System.loadLibrary("folia-node");
                Thread engine = new Thread(() -> startNode(new String[] { "node", "--dns-result-order=ipv4first", new File(directory, "main.cjs").getAbsolutePath(), state.getAbsolutePath(), token.toString(), readyFile.getAbsolutePath() }), "FoliaMusicNode");
                engine.setDaemon(true);
                engine.start();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(75);
                while (System.nanoTime() < deadline) {
                    if (readyFile.isFile()) {
                        JSONObject info; try (InputStream in = new FileInputStream(readyFile)) { info = new JSONObject(new String(readAll(in), StandardCharsets.UTF_8)); }
                        readyFile.delete();
                        return new Endpoint(info.getInt("port"), token.toString());
                    }
                    if (!engine.isAlive() || new File(readyFile.getPath() + ".error").exists()) throw new IOException("Built-in music service could not start");
                    Thread.sleep(100);
                }
                throw new IOException("Built-in music service startup timed out");
            } catch (Throwable error) {
                throw new RuntimeException("ANDROID_MUSIC_BACKEND_UNAVAILABLE: " + error.getClass().getSimpleName(), error);
            }
        });
        return startup;
    }

    public static HttpURLConnection connect(Context context, String path, String method, Map<String, String> headers, String body) throws Exception {
        if (!path.startsWith("/") || path.startsWith("//") || path.contains("\r") || path.contains("\n")) throw new IOException("Invalid local request path");
        Endpoint endpoint = ready(context).get(80, TimeUnit.SECONDS);
        HttpURLConnection connection = (HttpURLConnection) new URL("http://127.0.0.1:" + endpoint.port + path).openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(20000);
        connection.setReadTimeout(75000);
        connection.setRequestMethod(method);
        for (Map.Entry<String, String> header : headers.entrySet()) {
            String name = header.getKey().toLowerCase(java.util.Locale.ROOT);
            if (!name.equals("host") && !name.equals("connection") && !name.equals("content-length") && !name.equals("x-folia-local-auth")) connection.setRequestProperty(header.getKey(), header.getValue());
        }
        connection.setRequestProperty("X-Folia-Local-Auth", endpoint.token);
        if (body != null && !method.equals("GET") && !method.equals("HEAD")) {
            connection.setDoOutput(true);
            try (OutputStream out = connection.getOutputStream()) { out.write(body.getBytes(StandardCharsets.UTF_8)); }
        }
        return connection;
    }

    static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        copy(in, out);
        return out.toByteArray();
    }
    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[32768];
        int length;
        while ((length = in.read(buffer)) != -1) out.write(buffer, 0, length);
    }
    private NodeMusicRuntime() {}
}

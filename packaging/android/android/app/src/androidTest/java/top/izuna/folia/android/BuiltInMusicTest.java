package top.izuna.folia.android;

import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

// Boot the actual packaged native Node library, load all three APIs and verify the private socket boundary on Android.
@RunWith(AndroidJUnit4.class)
public class BuiltInMusicTest {
    @Test(timeout = 120000) public void packagedRuntimeStartsAndLoadsAllProviders() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        NodeMusicRuntime.ready(context).get(90, TimeUnit.SECONDS);
        HttpURLConnection connection = NodeMusicRuntime.connect(context, "/health", "GET", Collections.emptyMap(), null);
        try {
            assertEquals(200, connection.getResponseCode());
            try (InputStream in = connection.getInputStream()) {
                JSONObject info = new JSONObject(new String(NodeMusicRuntime.readAll(in), StandardCharsets.UTF_8));
                assertTrue(info.getBoolean("ready"));
                assertTrue(info.getString("node").startsWith("v24."));
                assertEquals(3, info.getJSONArray("providers").length());
            }
        } finally { connection.disconnect(); }
    }
    @Test(timeout = 120000) public void otherAppsCannotReadTheMusicGateway() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        NodeMusicRuntime.Endpoint endpoint = NodeMusicRuntime.ready(context).get(90, TimeUnit.SECONDS);
        HttpURLConnection unauthenticated = (HttpURLConnection) new URL("http://127.0.0.1:" + endpoint.port + "/health").openConnection();
        try { unauthenticated.setReadTimeout(5000); assertEquals(403, unauthenticated.getResponseCode()); }
        finally { unauthenticated.disconnect(); }
    }
}

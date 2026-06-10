package com.twitchnotify.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import androidx.core.app.NotificationManagerCompat;

import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Handles the "Ignore streamer" notification action. Runs in the background (no UI):
 * dismisses the notification immediately, then tells the backend to ignore the streamer.
 *
 * Native code can't read the WebView session cookie, so the request uses the signed,
 * short-lived action token delivered in the notification payload.
 */
public class IgnoreStreamerReceiver extends BroadcastReceiver {

    private static final String BACKEND_BASE_URL = "https://twitch-app-grn6.onrender.com";

    @Override
    public void onReceive(Context context, Intent intent) {
        String streamerId = intent.getStringExtra("streamerId");
        String actionToken = intent.getStringExtra("actionToken");
        int notificationId = intent.getIntExtra("notificationId", 0);

        // Dismiss the notification immediately for responsive UX.
        NotificationManagerCompat.from(context).cancel(notificationId);

        if (streamerId == null || streamerId.isEmpty() || actionToken == null || actionToken.isEmpty()) {
            return;
        }

        final PendingResult result = goAsync();
        postIgnore(actionToken, result);
    }

    private void postIgnore(String actionToken, PendingResult result) {
        new Thread(() -> {
            HttpURLConnection conn = null;
            try {
                JSONObject payload = new JSONObject();
                payload.put("actionToken", actionToken);

                URL url = new URL(BACKEND_BASE_URL + "/api/push/ignore-streamer");
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(15000);

                try (OutputStream os = conn.getOutputStream()) {
                    os.write(payload.toString().getBytes("UTF-8"));
                }
                conn.getResponseCode();
            } catch (Exception e) {
                // Best-effort: the streamer can still be ignored from within the app.
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
                result.finish();
            }
        }).start();
    }
}

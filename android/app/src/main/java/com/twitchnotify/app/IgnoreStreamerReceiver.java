package com.twitchnotify.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import androidx.core.app.NotificationManagerCompat;

import com.google.firebase.messaging.FirebaseMessaging;

import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Handles the "Ignore streamer" notification action. Runs in the background (no UI):
 * dismisses the notification immediately, then tells the backend to ignore the streamer.
 *
 * Native code can't read the WebView session cookie, so the request is authenticated by
 * the device's FCM token, which the backend maps to the user via push_subscriptions.
 */
public class IgnoreStreamerReceiver extends BroadcastReceiver {

    private static final String BACKEND_BASE_URL = "https://twitch-app-grn6.onrender.com";

    @Override
    public void onReceive(Context context, Intent intent) {
        String streamerId = intent.getStringExtra("streamerId");
        String streamerLogin = intent.getStringExtra("streamerLogin");
        String streamerName = intent.getStringExtra("streamerName");
        int notificationId = intent.getIntExtra("notificationId", 0);

        // Dismiss the notification immediately for responsive UX.
        NotificationManagerCompat.from(context).cancel(notificationId);

        if (streamerId == null || streamerId.isEmpty()) {
            return;
        }

        final PendingResult result = goAsync();

        String token = context
            .getSharedPreferences(TwitchMessagingService.PREFS_NAME, Context.MODE_PRIVATE)
            .getString(TwitchMessagingService.FCM_TOKEN_KEY, null);

        if (token != null) {
            postIgnore(token, streamerId, streamerLogin, streamerName, result);
        } else {
            // Fall back to fetching the current token from Firebase.
            FirebaseMessaging.getInstance().getToken().addOnCompleteListener(task -> {
                if (task.isSuccessful() && task.getResult() != null) {
                    postIgnore(task.getResult(), streamerId, streamerLogin, streamerName, result);
                } else {
                    result.finish();
                }
            });
        }
    }

    private void postIgnore(String token, String streamerId, String streamerLogin,
                            String streamerName, PendingResult result) {
        new Thread(() -> {
            HttpURLConnection conn = null;
            try {
                JSONObject payload = new JSONObject();
                payload.put("token", token);
                payload.put("streamerId", streamerId);
                payload.put("streamerLogin", streamerLogin != null ? streamerLogin : "");
                payload.put("streamerName", streamerName != null ? streamerName : "");

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

package com.twitchnotify.app;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;

import androidx.core.app.NotificationCompat;

import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

public class TwitchMessagingService extends FirebaseMessagingService {

    private static final String CHANNEL_ID = "stream_alerts";
    static final String PREFS_NAME = "twitch_push";
    static final String FCM_TOKEN_KEY = "fcm_token";

    @Override
    public void onCreate() {
        super.onCreate();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Stream Alerts",
                NotificationManager.IMPORTANCE_HIGH
            );
            channel.setDescription("Notifications when followed streamers go live");
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
    }

    @Override
    public void onNewToken(String token) {
        super.onNewToken(token);
        // Persist so IgnoreStreamerReceiver can authenticate its background request.
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putString(FCM_TOKEN_KEY, token)
            .apply();
    }

    @Override
    public void onMessageReceived(RemoteMessage message) {
        String title = message.getData().containsKey("title")
            ? message.getData().get("title") : "Stream is live!";
        String body = message.getData().containsKey("body")
            ? message.getData().get("body") : "";
        String streamerLogin = message.getData().get("streamerLogin");
        String streamerName = message.getData().containsKey("streamerName")
            ? message.getData().get("streamerName") : "";
        String streamerId = message.getData().containsKey("streamerId")
            ? message.getData().get("streamerId") : "0";
        int notificationId = streamerId.hashCode();

        Intent intent;
        if (streamerLogin != null) {
            intent = new Intent(Intent.ACTION_VIEW, Uri.parse("twitch://stream/" + streamerLogin));
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        } else {
            intent = new Intent(this, MainActivity.class);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        }

        PendingIntent pendingIntent = PendingIntent.getActivity(
            this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        // "Ignore streamer" action: fires a background broadcast that calls the backend
        // and dismisses the notification without opening the app.
        Intent ignoreIntent = new Intent(this, IgnoreStreamerReceiver.class);
        ignoreIntent.putExtra("streamerId", streamerId);
        ignoreIntent.putExtra("streamerLogin", streamerLogin != null ? streamerLogin : "");
        ignoreIntent.putExtra("streamerName", streamerName);
        ignoreIntent.putExtra("notificationId", notificationId);
        PendingIntent ignorePendingIntent = PendingIntent.getBroadcast(
            this, notificationId, ignoreIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .addAction(0, "Ignore streamer", ignorePendingIntent)
            .setAutoCancel(true);

        getSystemService(NotificationManager.class)
            .notify(notificationId, builder.build());
    }
}

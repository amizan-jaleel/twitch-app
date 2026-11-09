import { CapacitorConfig } from '@capacitor/cli';

const config: CapacitorConfig = {
  appId: 'com.twitchnotify.app',
  appName: 'Twitch Category Tracker',
  webDir: 'www',
  ios: {
    // AppDelegate owns the UNUserNotificationCenterDelegate so the "Ignore streamer"
    // action runs reliably in the background and notification taps are handled natively.
    // With Capacitor's default handler, a background action could complete before the JS
    // bridge ran the request.
    handleApplicationNotifications: false
  },
  server: {
    // On native, load the app from the production server so relative
    // API paths (/api/user, /api/config, etc.) resolve correctly.
    // Capacitor native plugins (push notifications) still work because
    // they run in the native layer, not the WebView.
    url: 'https://twitch-app-grn6.onrender.com',
    cleartext: true,
    // Keep Twitch OAuth flow inside the WebView instead of opening external browser
    allowNavigation: ['id.twitch.tv', '*.twitch.tv']
  }
};

export default config;

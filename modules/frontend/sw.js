const CACHE_VERSION = 'v4';
const CACHE_NAME = `twitch-tracker-${CACHE_VERSION}`;

const PRECACHE_URLS = [
  '/',
  '/manifest.json',
  '/register-sw.js',
  '/icons/icon.svg'
];

// Install: pre-cache the app shell
self.addEventListener('install', (event) => {
  event.waitUntil(
    caches.open(CACHE_NAME)
      .then((cache) =>
        Promise.allSettled(PRECACHE_URLS.map((url) => cache.add(url)))
      )
      .then((results) => {
        results.forEach((result, i) => {
          if (result.status === 'rejected') {
            console.warn('SW precache skipped:', PRECACHE_URLS[i], result.reason);
          }
        });
        return self.skipWaiting();
      })
  );
});

// Activate: clean up old caches
self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches.keys()
      .then((keys) => Promise.all(
        keys
          .filter((key) => key.startsWith('twitch-tracker-') && key !== CACHE_NAME)
          .map((key) => caches.delete(key))
      ))
      .then(() => self.clients.claim())
  );
});

// Push: handle FCM push notifications (web PWA fallback)
self.addEventListener('push', (event) => {
  if (!event.data) return;

  let payload;
  try {
    payload = event.data.json();
  } catch (e) {
    payload = { notification: { title: 'Twitch Category Tracker', body: event.data.text() } };
  }

  const data = payload.data || payload.notification || {};

  const title = data.title || 'Stream is live!';
  const options = {
    body: data.body || '',
    icon: '/icons/icon.svg',
    badge: '/icons/icon.svg',
    data: data,
    tag: data.streamerId || 'twitch-notification',
    renotify: true,
    actions: [{ action: 'ignore', title: 'Ignore streamer' }]
  };

  event.waitUntil(self.registration.showNotification(title, options));
});

// Notification click: handle the "Ignore streamer" action, or open the stream.
self.addEventListener('notificationclick', (event) => {
  event.notification.close();

  const data = event.notification.data || {};

  if (event.action === 'ignore') {
    const ignoreRequest = data.actionToken
      ? fetch('/api/push/ignore-streamer', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ actionToken: data.actionToken })
      })
      : fetch('/api/ignored-streamers/add', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        credentials: 'same-origin',
        body: JSON.stringify({
          streamerId: data.streamerId,
          streamerLogin: data.streamerLogin,
          streamerName: data.streamerName
        })
      });

    event.waitUntil(
      ignoreRequest.then((response) => {
        if (!response.ok) throw new Error('ignore failed: ' + response.status);
        // Tell any open page to refresh its ignore list so the UI stays in sync.
        return self.clients.matchAll({ type: 'window', includeUncontrolled: true }).then((windows) => {
          windows.forEach((w) =>
            w.postMessage({ type: 'streamer-ignored', streamerId: data.streamerId })
          );
        });
      }).catch(() =>
        // Surface failures (e.g. an expired session) instead of dismissing silently.
        self.registration.showNotification("Couldn't ignore streamer", {
          body: 'Open the app and try again.',
          icon: '/icons/icon.svg'
        })
      )
    );
    return;
  }

  const streamer = data.streamerLogin;

  if (!streamer) {
    event.waitUntil(clients.openWindow('/'));
    return;
  }

  // Open the stream on twitch.tv. In the browser the https URL works on desktop and
  // mobile; twitch:// would open a blank page when no Twitch protocol handler is
  // registered (e.g. desktop without the Twitch app). Native iOS/Android handle their
  // own notification taps and deep-link to the Twitch app separately.
  event.waitUntil(clients.openWindow(`https://twitch.tv/${streamer}`));
});

// Fetch: route requests to cache or network
self.addEventListener('fetch', (event) => {
  const url = new URL(event.request.url);

  // Only handle same-origin requests. Cross-origin resources like Twitch CDN
  // images must load directly via the browser's img-src policy; fetching them
  // here is governed by connect-src and breaks under a self-only policy.
  if (url.origin !== self.location.origin) return;

  // Never persist private auth/API responses in CacheStorage.
  if (url.pathname.startsWith('/api/') || url.pathname.startsWith('/auth/')) {
    event.respondWith(fetch(event.request));
    return;
  }

  // Cache-first for static assets (CSS, JS, icons, HTML)
  event.respondWith(
    caches.match(event.request)
      .then((cached) => {
        if (cached) {
          return cached;
        }
        return fetch(event.request).then((response) => {
          if (response.ok) {
            const clone = response.clone();
            caches.open(CACHE_NAME).then((cache) => cache.put(event.request, clone));
          }
          return response;
        });
      })
  );
});

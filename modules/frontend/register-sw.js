if ('serviceWorker' in navigator) {
  navigator.serviceWorker.register('/sw.js')
    .then((reg) => console.log('SW registered, scope:', reg.scope))
    .catch((err) => console.warn('SW registration failed:', err));
}

/* ZYRAXON service worker — offline shell for the PWA */
const CACHE = 'zyraxon-v3';
const SHELL = [
  '/', '/index.html', '/manifest.webmanifest',
  '/css/app.css', '/css/aura.css', '/css/console.css',
  '/js/i18n.js', '/js/db.js', '/js/characters.js', '/js/skills.js', '/js/platform.js', '/js/call.js', '/js/monitor.js',
  '/js/bg.js', '/js/native.js', '/js/voice.js', '/js/tools.js', '/js/agent.js', '/js/ui.js', '/js/app.js',
  '/knowledge/zyraxon-system-prompt.md', '/assets/icon.svg',
];

self.addEventListener('install', (e) => {
  e.waitUntil(caches.open(CACHE).then((c) => c.addAll(SHELL).catch(() => {})).then(() => self.skipWaiting()));
});
self.addEventListener('activate', (e) => {
  e.waitUntil(caches.keys().then((ks) => Promise.all(ks.filter((k) => k !== CACHE).map((k) => caches.delete(k)))).then(() => self.clients.claim()));
});
self.addEventListener('fetch', (e) => {
  const u = new URL(e.request.url);
  // never cache API or streamed responses
  if (e.request.method !== 'GET' || u.pathname.startsWith('/api/')) return;
  // Network-first for the app shell: a shipped fix must take effect on the very
  // next launch. The cache is only a fallback for when the phone is offline —
  // cache-first here would pin users to old JavaScript forever.
  const req = new Request(e.request, { cache: 'no-store' });
  e.respondWith(
    fetch(req).then((res) => {
      if (res.ok && u.origin === location.origin) {
        const copy = res.clone();
        caches.open(CACHE).then((c) => c.put(e.request, copy)).catch(() => {});
      }
      return res;
    }).catch(() => caches.match(e.request).then((hit) => hit || caches.match('/index.html')))
  );
});

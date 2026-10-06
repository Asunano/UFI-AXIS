/**
 * UFI-AXIS web Service Worker（PWA 化，2026-10-06）。
 *
 * 缓存策略（刻意保守，别自作聪明改激进）：
 * - **API/WS 一律 network-only**：`/api` 是鉴权实时数据，`/ws` 根本不是 fetch；
 *   缓存了会出「设备都重启了页面还是旧数据」的灵异问题。
 * - **导航请求（index.html）network-first**：离线时回退缓存的壳，保证「添加到
 *   主屏幕」后没网也能打开界面（数据层自然会显示连接失败）。
 * - **静态资源（带 content hash 的 assets）cache-first**：文件名带 hash，缓存即正确。
 *
 * 版本更新：`skipWaiting` + `clients.claim` 简单粗暴——新 SW 立即接管。配合
 * index.html 里的 controllerchange 一次性 reload，避免旧页面跑在新壳上的半新半旧态。
 * 注意 core 的 WebUpdateManager 独立更新整个 web 目录（zip 替换），sw.js 也会被
 * 一并换掉，浏览器按字节对比自动发现新版本 —— 两条更新链路不打架。
 */

const CACHE = 'ufi-shell-v1';
const SHELL = ['/', '/index.html', '/favicon.svg', '/manifest.webmanifest'];

self.addEventListener('install', (event) => {
  event.waitUntil(
    caches.open(CACHE).then((c) => c.addAll(SHELL)).then(() => self.skipWaiting()),
  );
});

self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches
      .keys()
      .then((keys) => Promise.all(keys.filter((k) => k !== CACHE).map((k) => caches.delete(k))))
      .then(() => self.clients.claim()),
  );
});

self.addEventListener('fetch', (event) => {
  const req = event.request;
  if (req.method !== 'GET') return;

  const url = new URL(req.url);
  if (url.origin !== self.location.origin) return;

  // API 与 WebSocket 升级：绝不缓存
  if (url.pathname.startsWith('/api/') || url.pathname.startsWith('/ws')) return;

  // 导航请求：network-first，离线回退壳
  if (req.mode === 'navigate') {
    event.respondWith(
      fetch(req)
        .then((res) => {
          const copy = res.clone();
          caches.open(CACHE).then((c) => c.put('/index.html', copy));
          return res;
        })
        .catch(() => caches.match('/index.html')),
    );
    return;
  }

  // 带 hash 的静态资源：cache-first
  if (url.pathname.startsWith('/assets/')) {
    event.respondWith(
      caches.match(req).then(
        (hit) =>
          hit ||
          fetch(req).then((res) => {
            const copy = res.clone();
            caches.open(CACHE).then((c) => c.put(req, copy));
            return res;
          }),
      ),
    );
  }
  // 其余（favicon 等）：直接走网络，不值得为它们写分支
});

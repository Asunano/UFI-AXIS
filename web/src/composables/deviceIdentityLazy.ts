/**
 * 设备身份模块的懒加载入口。
 *
 * `@/composables/deviceIdentity` 静态依赖 `@noble/curves` / `@noble/hashes`（约 6–10KB gzip），
 * 而签名只在「已登录请求 / WebSocket 握手 / 配对挑战」时才需要。改为动态 import 后，这部分
 * crypto 会脱离首屏 chunk，按首个签名需求（通常是登录后第一次 API 请求）才加载。
 *
 * 所有调用方共用同一个缓存的 Promise，因此模块在运行时只会被拉取一次；
 * Rollup 也会把它打成单独的共享异步 chunk，不会在各视图 chunk 间重复打包。
 */
let modulePromise: Promise<typeof import('@/composables/deviceIdentity')> | null = null;

export function loadDeviceIdentity() {
  if (!modulePromise) {
    modulePromise = import('@/composables/deviceIdentity');
  }
  return modulePromise;
}

/**
 * 上传前的体积预检（全站共用）。
 *
 * ## 为什么必须在客户端挡
 * core 的 `RequestBodyLimit` 插件在 `onCall` 阶段就读 `Content-Length` 抛 413，
 * **并没有真的收下整个文件**。但 HTTP 的现实是：客户端在没有 `Expect: 100-continue`
 * 协商时，请求头发出后就无条件开始推 body，而浏览器要等 body 发完才去处理响应 ——
 * 于是 `xhr.upload.onprogress` / axios 的 `onUploadProgress` 一路跑到 100%，
 * 才在 `onload` 里拿到 413。
 *
 * 用户看到的是「进度跑满才提示超出限制」，而这中间的上行带宽是**白烧的**。
 * 设备端是一台跑着 Ktor 的手机，几十 MB 的无效上传既费用户时间也费设备电量。
 *
 * ## 上限一律从设备端下发，不在前端硬编码
 * 五条上传路径的上限各不相同，而且其中一条是**动态的**：
 * - `/api/files/upload` 及其分片端点 → `GET /api/files/status` 的
 *   `max_upload_bytes` / `max_chunked_upload_bytes`
 * - `/api/update/upload` → `GET /api/update/status` 的 `upload_limit_bytes`（按清单 apkSize 动态算）
 * - `/api/web/update`    → `GET /api/web/status` 的 `max_upload_bytes`
 * - `/api/components/{id}/upload` → `GET /api/components` 的 `max_upload_bytes`
 * - `/api/backup/import` → `GET /api/backup/info` 的 `max_upload_bytes`
 *
 * 前端硬编码的后果是「前端放行但服务端 413」或「前端误拦本可上传的文件」，
 * 两种都会让人怀疑功能坏了。老固件不回这些 key 时各调用方自己给一个保守回落值。
 */

/** 人类可读的体积上限文案：≥1GB 用 GB，否则用 MB。 */
export function formatUploadLimit(bytes: number): string {
  if (!Number.isFinite(bytes) || bytes <= 0) return '未知';
  if (bytes >= 1024 * 1024 * 1024) {
    const gb = bytes / 1024 / 1024 / 1024;
    return `${gb.toFixed(bytes % (1024 * 1024 * 1024) === 0 ? 0 : 1)} GB`;
  }
  return `${Math.round(bytes / 1024 / 1024)} MB`;
}

/** 与 [formatUploadLimit] 同一口径，但用于报"这个文件多大"（保留 1 位小数）。 */
export function formatFileSize(bytes: number): string {
  if (bytes >= 1024 * 1024 * 1024) return `${(bytes / 1024 / 1024 / 1024).toFixed(1)} GB`;
  if (bytes >= 1024 * 1024) return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
  if (bytes >= 1024) return `${Math.round(bytes / 1024)} KB`;
  return `${bytes} B`;
}

/**
 * 超限时返回一条可直接显示的中文原因，未超限返回 null。
 *
 * 文案里**同时带上文件实际大小与上限**：只说"超出限制"的话用户还得自己去查文件多大，
 * 而这恰恰是他判断"换个文件还是放弃"所需要的信息。
 *
 * `limit <= 0` 视为"上限未知"（能力探测失败 / 老固件），此时**放行** ——
 * 宁可让服务端去拦，也不要因为探测失败就把功能锁死。
 */
export function uploadSizeError(file: { name: string; size: number }, limit: number): string | null {
  if (!Number.isFinite(limit) || limit <= 0) return null;
  if (file.size <= limit) return null;
  return `${file.name} 为 ${formatFileSize(file.size)}，超过上限 ${formatUploadLimit(limit)}，未上传`;
}

/**
 * 从任意响应对象里读一个字节数字段；读不到返回 0（= 上限未知，见 [uploadSizeError]）。
 *
 * 四个端点的字段名不完全一致（`max_upload_bytes` / `upload_limit_bytes`），
 * 所以允许传多个候选键。
 */
export function readByteLimit(data: unknown, ...keys: string[]): number {
  if (!data || typeof data !== 'object') return 0;
  const obj = data as Record<string, unknown>;
  for (const k of keys) {
    const n = Number(obj[k]);
    if (Number.isFinite(n) && n > 0) return n;
  }
  return 0;
}

/**
 * 视频格式分档：全站**唯一**的"这个文件浏览器能不能放"判据。
 *
 * ## 为什么需要它
 * 两处播放点（媒体页 / 文件预览）此前都是裸 `<video :src>`，既不判格式也不听 `error`。
 * 浏览器解不出来时就是一个**黑框** —— 用户无法区分"文件坏了""网断了""格式不支持"。
 *
 * ## 判据是 `canPlayType()`，不是静态黑名单（2026-09-19 修正）
 *
 * ### 之前错在哪
 * 第一版用一张静态 `UNSUPPORTED_EXTS` 表，把 `mkv` 判成"不支持"，理由写的是
 * 「MSE 不认 matroska 容器」。**这个推理混淆了两条完全不同的管线**：
 *
 *  · **MSE**（`MediaSource.isTypeSupported`）：JS 通过 `SourceBuffer` 喂分片时用的通道。
 *    它确实不认 Matroska。
 *  · **`<video src="…">`**：**根本不走 MSE**，走的是浏览器自己的原生解复用+解码管线。
 *    Chromium 那条管线基于 FFmpeg，**支持 Matroska**。
 *
 * 我们绝大多数播放走的是后者（直接给票据 URL），所以拿前者的能力做判断就把
 * 本来能播的 MKV 挡掉了 —— 而 MKV 恰好是设备视频库里最常见的容器。
 *
 * 连带纠正：`filesShared.PREVIEW_VIDEO_EXTS` 里包含 `mkv` 是**正确的**，
 * 之前把它当成"自相矛盾"是误判。
 *
 * ### 为什么静态表这个做法本身就站不住
 * 同一个扩展名在不同浏览器、不同内部编码下结论不同：
 *  · MKV + H.264/AAC → Chromium 能放；MKV + H.265/AC3 → 不能
 *  · MKV 在 Firefox / Safari → 不能（没有 Matroska 解复用器）
 *
 * 一张表只能表达"所有浏览器统一的结论"，而这件事根本不统一。
 * 所以改成**问浏览器自己**：`HTMLVideoElement.canPlayType(mime)` 正是为此存在的 API，
 * 返回 `'probably'` / `'maybe'` / `''`（空串 = 确定不行）。
 *
 * ## 三档
 *  · **native**：`canPlayType()` 非空 → 直接给 `<video>`，**仍需 `error` 兜底**
 *    （`'maybe'` 的字面意思就是"容器行、编码不保证"）；
 *  · **custom**：flv / m3u8 / mpd / ts → JS 解复用库喂 MSE（ArtPlayer 的 `customType`）；
 *  · **unsupported**：两条路都不通 → **不启动播放器**，给明确说明 + 下载入口。
 *
 * C 档必须如实劝退而不是"试一下"，因为**服务端转码这条路是断的**：
 * core 侧 ffmpeg 方案已放弃（预编译 .so 按 16KB page size 链接，本机 Android 12 是
 * 4KB 页，linker 给 RELRO 段 mprotect 时报 `Out of memory`，详见 `MediaRoutes.kt`）。
 */

/** 分档结果。 */
export type VideoTier =
  /** 浏览器原生可播（仍需 error 兜底：容器对了不代表编码对了） */
  | 'native'
  /** 需要 JS 解复用库（ArtPlayer customType） */
  | 'custom'
  /** 浏览器无法解码，不要启动播放器 */
  | 'unsupported';

/**
 * 扩展名 → 容器 MIME，供 [canPlayExt] 拿去问浏览器。
 *
 * 这里**只写容器**、不带 `codecs=` 参数。带上 codecs 能问得更准，但我们只有文件名、
 * 拿不到内部编码 —— 猜一个 codecs 反而会把结论引偏（比如给 mkv 写死 `avc1` 就会
 * 把 VP9 的 mkv 误判成不支持）。容器层过了之后由 `error` 兜底处理编码问题。
 *
 * `video/x-matroska` 是 Chromium 认的那个写法（`video/mkv` 它不认）。
 */
const MIME_BY_EXT: Record<string, string> = {
  mp4: 'video/mp4',
  m4v: 'video/mp4',
  mov: 'video/quicktime',
  webm: 'video/webm',
  ogv: 'video/ogg',
  ogg: 'video/ogg',
  // Matroska：Chromium 能放，Firefox/Safari 不能 —— 正是交给 canPlayType 判的典型
  mkv: 'video/x-matroska',
  // ISO-BMFF 家族，通常能放
  '3gp': 'video/3gpp',
  '3g2': 'video/3gpp2',
  f4v: 'video/mp4',
  // 下面这些主流浏览器基本都不认，但**仍然走 canPlayType 问一遍**：
  // 万一某个环境支持，没道理替它拒绝
  avi: 'video/x-msvideo',
  wmv: 'video/x-ms-wmv',
  asf: 'video/x-ms-asf',
  rm: 'application/vnd.rn-realmedia',
  rmvb: 'application/vnd.rn-realmedia-vbr',
  vob: 'video/mpeg',
  mpg: 'video/mpeg',
  mpeg: 'video/mpeg',
  divx: 'video/divx',
};

/**
 * B 档 → ArtPlayer `customType` 的 key。
 *
 * `ts` / `m2ts` / `mts` 都是 MPEG-TS，走 mpegts.js。
 * （第一版把 `m2ts` / `mts` 直接判成不支持，是错的 —— 它们与 `.ts` 同一个解复用器。）
 */
const CUSTOM_TYPE_BY_EXT: Record<string, 'flv' | 'm3u8' | 'mpd' | 'ts'> = {
  flv: 'flv',
  m3u8: 'm3u8',
  mpd: 'mpd',
  ts: 'ts',
  m2ts: 'ts',
  mts: 'ts',
};

/** 取小写扩展名（不含点）。没有扩展名返回空串。 */
export function videoExtOf(name: string): string {
  const dot = name.lastIndexOf('.');
  if (dot < 0 || dot === name.length - 1) return '';
  return name.slice(dot + 1).toLowerCase();
}

/**
 * 复用一个**离屏** `<video>` 元素问 `canPlayType`。
 *
 * 复用而不是每次新建：这个判定在列表渲染里可能被调很多次，
 * 每次 `createElement('video')` 都会让浏览器初始化一套媒体栈。
 * 不挂到 document 上，所以不产生任何渲染开销。
 *
 * SSR / 非浏览器环境（单测）下没有 `document`，此时返回空串 ——
 * 调用方会据此落到"按扩展名保守判断"，不会抛。
 */
let probeEl: HTMLVideoElement | null = null;
function canPlayMime(mime: string): string {
  if (!mime) return '';
  if (typeof document === 'undefined') return '';
  if (!probeEl) probeEl = document.createElement('video');
  // 返回 'probably' | 'maybe' | ''
  return probeEl.canPlayType(mime);
}

/**
 * 这个扩展名当前浏览器能不能直接放。
 *
 * `'maybe'` 也算能 —— 它的字面含义就是"容器我认识，编码不敢保证"，
 * 而编码问题本来就由 `error` 兜底。为了 `'maybe'` 就劝退会误伤大量正常文件。
 */
function canPlayExt(ext: string): boolean {
  return canPlayMime(MIME_BY_EXT[ext] ?? '') !== '';
}

/**
 * 判档。优先级：**能原生放 > 有解复用库 > 劝退**。
 *
 * "能原生放"排第一而不是"先看是不是 B 档"：`.ts` 在某些环境下原生可放，
 * 那就没必要为它拉 277KB 的 mpegts.js。
 *
 * 未登记的扩展名（`MIME_BY_EXT` 里没有、也不是 B 档）按 **native 试一次** ——
 * 设备上常有改过后缀的正常 mp4，一律劝退会把本能播的挡掉；真不行由 `error` 兜底。
 */
export function videoTierOf(name: string): VideoTier {
  const ext = videoExtOf(name);
  if (canPlayExt(ext)) return 'native';
  if (CUSTOM_TYPE_BY_EXT[ext]) return 'custom';
  // 登记过 MIME 但浏览器明确说不行 ⇒ 劝退（avi / rmvb / wmv 这类会落在这里）
  if (MIME_BY_EXT[ext]) return 'unsupported';
  // 完全没登记过：试一次
  return 'native';
}

/**
 * 给 ArtPlayer 的 `type` 值。
 *
 * **必须显式传**，不能让 ArtPlayer 自己按后缀猜：我们的播放地址是
 * `/media/stream?ticket=…`（见 `mediaShared.fetchStreamUrl`），**没有文件后缀**。
 * ArtPlayer 文档专门警告过这一点 —— 它只能解析 `/path/video.m3u8` 这种，
 * 解析不了 `/path/video?type=m3u8`。
 *
 * 只有真正走 customType 的才返回非空：一个原生可放的 `.ts` 不该被塞进 mpegts.js。
 */
export function artplayerTypeOf(name: string): string {
  if (videoTierOf(name) !== 'custom') return '';
  return CUSTOM_TYPE_BY_EXT[videoExtOf(name)] ?? '';
}

/**
 * C 档的劝退文案。
 *
 * 不再写"容器不被 Media Source 支持" —— 那个解释是错的（`<video src>` 不走 MSE），
 * 而且对用户没有任何用。改成说清"**此浏览器**"（而不是"浏览器"）并给出两条出路：
 * 换 Chromium 系浏览器、或下载到本地播放。
 */
export function unsupportedReasonOf(name: string): string {
  const ext = videoExtOf(name).toUpperCase();
  const what = ext ? `${ext} 格式` : '该格式';
  return `当前浏览器无法解码${what}。可换用 Chrome / Edge 再试，或下载后用本地播放器打开。`;
}

/**
 * 把 `HTMLMediaElement.error.code` 翻成人话。
 *
 * 这是 native 档的第二道兜底，而且**必不可少**：`canPlayType` 只看容器，
 * 里面装的可能是 HEVC/H.265、AC3/DTS 音轨、ProRes —— 都会在这里失败。
 * 码值语义见 MDN `MediaError.code`。
 */
export function mediaErrorTextOf(err: MediaError | null | undefined, name = ''): string {
  const ext = videoExtOf(name).toUpperCase();
  switch (err?.code) {
    case 1: // MEDIA_ERR_ABORTED
      return '播放已中断。';
    case 2: // MEDIA_ERR_NETWORK
      return '网络中断，视频加载失败。设备端连接不稳定时会出现这种情况。';
    case 3: // MEDIA_ERR_DECODE
      // 容器能识别但解码失败：典型是 HEVC/H.265 视频或 AC3/DTS 音轨
      return `视频已开始加载但解码失败${ext ? `（${ext}）` : ''}，通常是内部编码（如 H.265 视频、AC3/DTS 音轨）不被当前浏览器支持。请下载后用本地播放器打开。`;
    case 4: // MEDIA_ERR_SRC_NOT_SUPPORTED
      return `当前浏览器不支持该视频${ext ? `（${ext}）` : ''}，可换用 Chrome / Edge 再试，或下载后用本地播放器打开。`;
    default:
      return '视频无法播放，请下载后用本地播放器打开。';
  }
}

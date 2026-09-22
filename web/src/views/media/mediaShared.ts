/**
 * 媒体三页（视频 / 音乐 / 图片）的共享类型、取数与格式化。
 *
 * 为什么拆出来：2026-09-19 按 app 的结构把原来那个"一页三个标签"的 `MediaView.vue`
 * 拆成三个独立页面（app 侧是 `MEDIA_LIBRARY_VIDEO` / `_AUDIO` / `_IMAGE` 三条路由）。
 * 拆完之后「缩略图怎么取」「播放地址怎么换票据」「分页怎么翻」这三件事被三页共用，
 * 留在任一个 SFC 里都会被另外两页复制一遍。
 *
 * ──── 浏览器侧的两条硬约束（决定了下面所有函数的形状）────
 *
 * 1. **二进制端点不能直接喂给 `src`**。`<img>` / `<video>` / `<audio>` 发的是裸 GET，
 *    带不上 Bearer 头与设备签名，而 core 的 AuthMiddleware 对所有 `/api` 强制验签 ⇒ 444。
 *    所以缩略图/封面一律走 axios 取 Blob 再 `createObjectURL`，用完必须 `revoke`。
 * 2. **播放要换票据**。`POST /api/files/stream-ticket` 回一个 `url`
 *    （`/media/stream?ticket=…`，刻意挂在 `/api` 之外的免鉴权区），票据可重复使用、
 *    滑动过期 10 分钟、只授权那一个文件。**必须用响应里的 `url`**，自己拼
 *    `/api/files/stream?path=…` 既不在免鉴权区、也不带 path 参数语义，只会 401/403。
 */
import type { AxiosInstance } from 'axios';
import { Endpoints } from '@/api/contract';
import { useAppStore } from '@/stores/app';

/** 媒体类型。取值与 core `MediaRoutes.Kind.key` 逐字一致，写错回 400。 */
export type MediaKind = 'video' | 'audio' | 'image';

/**
 * 一个媒体条目。字段**按类型出现**（core 的 `MediaRoutes.itemOf`）：
 * - `duration_ms`：视频 + 音频
 * - `width` / `height`：视频 + 图片
 * - `album` / `artist` / `title`：仅音频（`title` 是内嵌标签，MediaStore 只回文件名时为空串）
 *
 * `date_modified` 已由 core 乘成**毫秒**，不要再 ×1000。
 */
export interface MediaItem {
  id: number;
  name: string;
  path: string;
  size: number;
  date_modified: number;
  mime: string;
  duration_ms?: number;
  width?: number;
  height?: number;
  album?: string;
  artist?: string;
  title?: string;
}

/** `GET /api/media/list` 的响应。 */
export interface MediaListResponse {
  type: string;
  items: MediaItem[];
  total: number;
  limit: number;
  offset: number;
  scan_dirs: string[];
}

/**
 * 每页条数。core 侧 `MAX_PAGE_SIZE = 500`，这里取 60 ——
 * 网格一屏放得下十几个，60 足够铺满两三屏又不会一次拉几百张缩略图。
 */
export const MEDIA_PAGE_SIZE = 60;

/** 缩略图请求的边长。core 侧夹在 96..1024，256 在 2 倍屏下仍然清楚。 */
export const MEDIA_THUMB_SIZE = 256;

/** 只用得到这几个方法，收窄成最小接口以便传 `useCancellableApi()` 的包装客户端。 */
export type MediaApiClient = Pick<AxiosInstance, 'get' | 'post'>;

/**
 * 拉一页列表。
 *
 * 排序固定「按修改时间倒序」：媒体库里"最近加进来的"几乎总是用户要找的，
 * 而 app 端首页也是这个口径。需要别的排序时再把参数提上来。
 *
 * 权限不足时 core 回 **HTTP 403**（`code` 仍是 `BAD_REQUEST`，没有专用错误码），
 * axios 会抛出 —— 调用方据 `status === 403` 显示"未授权"，不要把它当普通网络错误。
 */
export async function fetchMediaPage(
  api: MediaApiClient,
  kind: MediaKind,
  offset = 0,
  limit = MEDIA_PAGE_SIZE
): Promise<MediaListResponse | null> {
  const { data } = await api.get<MediaListResponse>(Endpoints.media.list, {
    params: { type: kind, limit, offset, sort: 'date', order: 'desc' },
  });
  return data ?? null;
}

/**
 * 取缩略图字节并转成 `blob:` URL。取不到返回空串。
 *
 * 缩略图不可用是**常态**而不是异常：设备 ROM 解不出视频帧、图片格式浏览器不认、
 * core 还没生成缓存都会落到这里。所以失败一律静默，由调用方渲染占位图标。
 */
export async function fetchThumbUrl(api: MediaApiClient, kind: MediaKind, id: number): Promise<string> {
  try {
    const { data } = await api.get(Endpoints.media.thumbnail, {
      params: { type: kind, id, size: MEDIA_THUMB_SIZE },
      responseType: 'blob',
    });
    if (data instanceof Blob && data.size > 0) return URL.createObjectURL(data);
  } catch {
    /* 静默 */
  }
  return '';
}

/**
 * 取音频内嵌封面（原图字节，不重新编码）并转成 `blob:` URL。取不到返回空串。
 *
 * 与 [fetchThumbUrl] 是两个端点：`/cover` 给的是 ID3 APIC / FLAC PICTURE 的原图，
 * 播放页要的就是这个最高画质；`/thumbnail` 是压过的小图，列表用。
 */
export async function fetchCoverUrl(api: MediaApiClient, id: number): Promise<string> {
  try {
    const { data } = await api.get(Endpoints.media.cover, { params: { id }, responseType: 'blob' });
    if (data instanceof Blob && data.size > 0) return URL.createObjectURL(data);
  } catch {
    /* 没有内嵌封面很常见，静默 */
  }
  return '';
}

/** `GET /api/media/lyrics` 的响应。`found: false` 时 `text` 是空串。 */
export interface MediaLyricsResponse {
  found: boolean;
  source: string;
  text: string;
}

/** `GET /api/media/tags` 的响应（core `audioTagsOf`）。缺失的字段是**空串**而不是 undefined。 */
export interface MediaTagsResponse {
  id: number;
  name: string;
  path: string;
  title: string;
  artist: string;
  album: string;
  duration_ms: number;
}

/**
 * 单首音频的精确标签。取不到返回 null。
 *
 * ## 为什么要多问这一条，而不是在前端解字节
 * `/list` 的 `title/artist/album` 来自 MediaStore，**会漏**（刚拷进来还没扫、
 * 某些 FLAC/APE 只填了部分列）。此时正确的做法是问 core 的 `/tags` ——
 * 它内部先用自己的 `AudioTagReader` 解文件字节（ID3 / FLAC，最准），
 * 还缺才用 `MediaMetadataRetriever` 兜一次。
 *
 * **不要照搬文件管理器那套前端字节解析**（`views/files/audioMetaProbe.ts` 的
 * `probeTagsAndCover`）：文件管理器走 `/api/files/*`，那条路上没有标签端点，只能自己解；
 * 音乐页有 `/tags` 可用，在前端再解一遍就是把同一个解析器实现两份，
 * 而且 web 那份**不支持 MP4/M4A**（只认 ID3 与 FLAC 魔数），比 core 的还弱。
 */
export async function fetchAudioTags(api: MediaApiClient, id: number): Promise<MediaTagsResponse | null> {
  try {
    const { data } = await api.get<MediaTagsResponse>(Endpoints.media.tags, { params: { id } });
    return data ?? null;
  } catch {
    // 404（这首不在库里了）/ 403（权限）都退回 /list 那份，静默
    return null;
  }
}

/**
 * 取旁挂歌词文件（`.lrc` / `.txt`）的内容。没有就返回空串。
 *
 * core 只负责"把旁边那个文件读出来"，**时间轴解析留在客户端**
 * （走 `views/files/id3Lyrics.ts` 的 `parseLyrics`，与文件预览共用同一份实现 ——
 * 两端对同一个 .lrc 必须解出同样的时间轴）。
 */
export async function fetchLyricsText(api: MediaApiClient, id: number): Promise<string> {
  try {
    const { data } = await api.get<MediaLyricsResponse>(Endpoints.media.lyrics, { params: { id } });
    return data?.found ? data.text || '' : '';
  } catch {
    return '';
  }
}

/**
 * 换一张播放票据，返回可直接放进 `src` 的绝对/相对 URL。
 *
 * 见文件头约束 2：**用响应里的 `url`**，不要自己拼。`baseUrl` 为空表示同源部署，
 * core 回的相对路径本身就能用。
 */
export async function fetchStreamUrl(api: MediaApiClient, path: string): Promise<string> {
  const { data } = await api.post('/api/files/stream-ticket', { path });
  const url = data?.url;
  if (!url) throw new Error('无法获取播放地址');
  return `${useAppStore().baseUrl || ''}${url}`;
}

/**
 * `blob:` URL 的回收器。
 *
 * object URL 会一直持有整个 Blob，浏览器不会自动回收 —— 三个页面都要在卸载时清一遍，
 * 所以做成一个小对象而不是让每页各写一份 `for (const u of urls) revoke(u)`。
 */
export function createBlobUrlPool() {
  const urls = new Set<string>();
  return {
    /** 登记一个 URL（空串直接忽略，省得调用方判空）。 */
    keep(url: string): string {
      if (url) urls.add(url);
      return url;
    },
    /** 回收单个。 */
    release(url: string) {
      if (url && urls.delete(url)) URL.revokeObjectURL(url);
    },
    /** 全部回收。组件 `onUnmounted` 里必须调。 */
    releaseAll() {
      for (const u of urls) URL.revokeObjectURL(u);
      urls.clear();
    },
  };
}

// ──────────────────────────── 格式化 ────────────────────────────

/**
 * 时长 `mm:ss` / `h:mm:ss`。
 *
 * 与播放进度条共用同一个函数：进度条的"已播/总长"和列表里的时长必须是同一种写法，
 * 各写一份必然分叉成 `3:07` 与 `03:07` 两种。
 */
export function formatDuration(ms: number | undefined): string {
  if (!ms || ms < 0) return '0:00';
  const total = Math.floor(ms / 1000);
  const h = Math.floor(total / 3600);
  const m = Math.floor((total % 3600) / 60);
  const s = total % 60;
  const mm = h > 0 ? String(m).padStart(2, '0') : String(m);
  return h > 0 ? `${h}:${mm}:${String(s).padStart(2, '0')}` : `${mm}:${String(s).padStart(2, '0')}`;
}

/** 文件大小。这里不复用 `composables/utils` 的 formatBytes：媒体页要的是 1 位小数的紧凑写法。 */
export function formatMediaSize(bytes: number): string {
  if (bytes >= 1073741824) return `${(bytes / 1073741824).toFixed(1)} GB`;
  if (bytes >= 1048576) return `${(bytes / 1048576).toFixed(1)} MB`;
  if (bytes >= 1024) return `${(bytes / 1024).toFixed(0)} KB`;
  return `${bytes} B`;
}

export function formatMediaDate(ts: number): string {
  if (!ts) return '--';
  return new Date(ts).toLocaleString('zh-CN', { hour12: false });
}

/**
 * 显示用的曲目名：**优先内嵌标签 `title`**，没有才用文件名（去掉扩展名）。
 *
 * core 已经保证"MediaStore 只是把文件名回显成 title"时给空串，所以这里不用再去比对。
 */
export function trackTitleOf(item: MediaItem): string {
  if (item.title) return item.title;
  const dot = item.name.lastIndexOf('.');
  return dot > 0 ? item.name.slice(0, dot) : item.name;
}

/** 艺人显示名。空标签统一成「未知艺人」——三处列表都要同一种说法。 */
export function trackArtistOf(item: MediaItem): string {
  return item.artist || '未知艺人';
}

// ──────────────────────────── 本机偏好 ────────────────────────────

/**
 * 音乐页音量的 localStorage 键。
 *
 * 单开一个键、不塞进 `ufi.video.prefs`：那个文件的语义是"视频播放器偏好"
 * （字幕外观、字幕轨记忆都在里面），把音频音量混进去只会让两边都不好读。
 * 音量是**这台浏览器上的**习惯，不写回 core —— 手机想小声、电脑想大声，
 * 同步反而互相覆盖。
 */
const LS_AUDIO_VOLUME = 'ufi.audio.volume';

/** 默认音量。不用 1.0：满音量开场对耳机用户是一次惊吓。 */
const DEFAULT_AUDIO_VOLUME = 0.85;

export function readAudioVolume(): number {
  try {
    const v = Number(localStorage.getItem(LS_AUDIO_VOLUME));
    return Number.isFinite(v) && v >= 0 && v <= 1 ? v : DEFAULT_AUDIO_VOLUME;
  } catch {
    return DEFAULT_AUDIO_VOLUME;
  }
}

export function writeAudioVolume(v: number) {
  if (!Number.isFinite(v) || v < 0 || v > 1) return;
  try {
    localStorage.setItem(LS_AUDIO_VOLUME, String(v));
  } catch {
    /* 隐私模式下不可写，丢了只是回默认值 */
  }
}

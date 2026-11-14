/**
 * 媒体三页（视频 / 音乐 / 图片）的共享类型、取数与格式化。
 *
 * 三页分别对应 app 侧的 `MEDIA_LIBRARY_VIDEO` / `MEDIA_LIBRARY_AUDIO` /
 * `MEDIA_LIBRARY_IMAGE` 三条路由。「缩略图获取」「播放地址换票据」「分页」由三页共用，
 * 若保留在任一 SFC 中会被另外两页重复实现。
 *
 * ## 浏览器侧的两条硬性约束（决定下述函数的形态）
 *
 * 1. 二进制端点不得直接作为 `src`。`<img>` / `<video>` / `<audio>` 发起裸 GET，
 *    无法携带 Bearer 头与设备签名，而 core 的 AuthMiddleware 对所有 `/api` 强制验签，
 *    结果为 444。因此缩略图与封面一律通过 axios 取 Blob 再 `createObjectURL`，
 *    使用完毕后必须 `revoke`。
 * 2. 播放必须换取票据。`POST /api/files/stream-ticket` 返回 `url`
 *    （`/media/stream?ticket=…`，位于 `/api` 之外的免鉴权区），票据可重复使用、
 *    滑动过期 10 分钟、仅授权单个文件。必须使用响应中的 `url`：自行拼接
 *    `/api/files/stream?path=…` 既不在免鉴权区，也不具备 path 参数语义，
 *    只会返回 401/403。
 */
import type { AxiosInstance } from 'axios';
import { Endpoints } from '@/api/contract';
import { useAppStore } from '@/stores/app';

/** 媒体类型。取值与 core `MediaRoutes.Kind.key` 逐字一致，不一致时返回 400。 */
export type MediaKind = 'video' | 'audio' | 'image';

/**
 * 一个媒体条目。字段按类型出现（core 的 `MediaRoutes.itemOf`）：
 * - `duration_ms`：视频与音频
 * - `width` / `height`：视频与图片
 * - `album` / `artist` / `title`：仅音频（`title` 为内嵌标签；MediaStore 仅返回文件名时为空串）
 *
 * `date_modified` 已由 core 换算为毫秒，不得再次乘以 1000。
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
  /**
   * 旁挂字幕条数。
   *
   * core 的 `/list` 会计算该字段（`MediaRoutes` 按同名规则匹配 `.srt`/`.ass` 等扩展名），
   * app 的列表据此显示「字幕 3」。声明该字段不代表一定有值：它仅出现在视频条目上。
   */
  subtitle_count?: number;
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
 * 每页条数。core 侧 `MAX_PAGE_SIZE = 500`，此处取 60：网格一屏可容纳十余项，
 * 60 足以铺满两三屏，同时避免一次请求数百张缩略图。
 */
export const MEDIA_PAGE_SIZE = 60;

/** 缩略图请求的边长。core 侧限定在 96..1024，256 在 2 倍像素密度下仍然清晰。 */
export const MEDIA_THUMB_SIZE = 256;

/** 仅使用这两个方法，收窄为最小接口，以便传入 `useCancellableApi()` 的包装客户端。 */
export type MediaApiClient = Pick<AxiosInstance, 'get' | 'post'>;

/**
 * `/list` 的服务端筛选参数，供音乐页的分组浏览回查使用。
 *
 * 三个字段与 core 的 query 参数同名同义。`dir` 不命名为 `folder`：`folder` 是分组的
 * 维度名（`/groups?by=folder`），回查 `/list` 时该参数名为 `dir`。命名不一致是 core 侧
 * 的既有事实（见 `MediaRoutes` 的 `queryParameters["dir"]`），此处沿用以避免在两端
 * 各加一层名称转换。
 */
export interface MediaFilter {
  album?: string;
  artist?: string;
  dir?: string;
}

/**
 * 排序维度。取值与 core 的 `listComparator` 逐字一致（`name` / `size`，其余一律按 `date`）。
 * 取值错误不会报错：core 的分支匹配落入 `else`，静默按日期排序，表现为「排序未生效」。
 */
export type MediaSortKey = 'date' | 'name' | 'size';

/** 排序方向。core 侧 `order != "asc"` 即为降序，因此仅接受 `asc` 与 `desc`。 */
export type MediaSortOrder = 'asc' | 'desc';

export interface MediaSort {
  key: MediaSortKey;
  order: MediaSortOrder;
}

/**
 * 默认排序：修改时间倒序。
 *
 * 与 core 的兜底分支一致（未传 `sort` 时按 `date_modified` 排序）；此处仅使其可配置，
 * 默认行为不变。
 */
export const DEFAULT_MEDIA_SORT: MediaSort = { key: 'date', order: 'desc' };

/**
 * 六档排序，与 app 的 `MediaLibraryPage` 的 `UfiSortAction` 一一对应
 * （时间↑↓ / 名称↑↓ / 大小↑↓）。
 *
 * 采用六个平铺选项，而非「三个维度 + 一个方向开关」：后者需两次操作才能表达
 * 「名称升序」，且两个控件之间的联动（切换维度时是否重置方向）会引入额外状态。
 */
export const MEDIA_SORT_OPTIONS: ReadonlyArray<{ label: string; value: string } & MediaSort> = [
  { label: '最近修改', value: 'date-desc', key: 'date', order: 'desc' },
  { label: '最早修改', value: 'date-asc', key: 'date', order: 'asc' },
  { label: '名称 A→Z', value: 'name-asc', key: 'name', order: 'asc' },
  { label: '名称 Z→A', value: 'name-desc', key: 'name', order: 'desc' },
  { label: '从大到小', value: 'size-desc', key: 'size', order: 'desc' },
  { label: '从小到大', value: 'size-asc', key: 'size', order: 'asc' },
];

/** 将 `MediaSort` 转换为下拉选项的 value，用于 `n-select` 的 `v-model`。 */
export function mediaSortValue(s: MediaSort): string {
  return `${s.key}-${s.order}`;
}

/** 将下拉选项的 value 转换为 `MediaSort`。无法识别时返回默认值，不得抛出异常。 */
export function mediaSortOf(value: string): MediaSort {
  return MEDIA_SORT_OPTIONS.find((o) => o.value === value) ?? DEFAULT_MEDIA_SORT;
}

/**
 * 拉取一页列表。
 *
 * 默认按修改时间倒序：媒体库中「最近加入」的条目通常是用户要查找的内容，app 端首页
 * 亦采用该口径。排序可由用户调整（见 [MediaSortKey]）。
 *
 * 权限不足时 core 返回 HTTP 403（`code` 仍为 `BAD_REQUEST`，没有专用错误码），
 * axios 会抛出异常；调用方据 `status === 403` 显示「未授权」，不得按普通网络错误处理。
 *
 * ## 筛选参数在服务端执行的原因
 *
 * core 侧先按标签筛选，再排序分页（等价于 SQL 的 `WHERE album = ?`）。若在客户端筛选，
 * 必须先将整库数千首分页拉完才能得到正确结果；而分组浏览已从 `/groups` 得知「该组共
 * N 首」，却仍需拉取全库才能凑出这 N 首，相当于在浏览器中重做服务端已完成的工作。
 * 因此筛选必须随 `offset` / `limit` 一起下推到 core。
 *
 * `filter` 中为空串的字段不得并入 params：core 的 `filterParam` 将空串视为未传，
 * 传入 `album=` 会静默退回整库（而非「筛选出空集」），结果错误程度高于不筛选。
 *
 * 排序同样必须下推：客户端排序只能作用于已翻出的 60 条，按名称排序时呈现为
 * 「页内有序、页间无序」，比不排序更具误导性。
 */
export async function fetchMediaPage(
  api: MediaApiClient,
  kind: MediaKind,
  offset = 0,
  limit = MEDIA_PAGE_SIZE,
  filter?: MediaFilter,
  sort?: MediaSort
): Promise<MediaListResponse | null> {
  const params: Record<string, string | number> = {
    type: kind,
    limit,
    offset,
    sort: sort?.key ?? DEFAULT_MEDIA_SORT.key,
    order: sort?.order ?? DEFAULT_MEDIA_SORT.order,
  };
  if (filter?.album) params.album = filter.album;
  if (filter?.artist) params.artist = filter.artist;
  if (filter?.dir) params.dir = filter.dir;
  const { data } = await api.get<MediaListResponse>(Endpoints.media.list, { params });
  return data ?? null;
}

/**
 * 取缩略图字节并转换为 `blob:` URL。取不到时返回空串。
 *
 * 缩略图不可用属于常态而非异常：设备 ROM 无法解码视频帧、浏览器不支持图片格式、
 * core 尚未生成缓存，都会进入该分支。因此失败一律静默，由调用方渲染占位图标。
 */
export async function fetchThumbUrl(api: MediaApiClient, kind: MediaKind, id: number, bust?: number): Promise<string> {
  try {
    const { data } = await api.get(Endpoints.media.thumbnail, {
      // bust：手动重抽时传入时间戳以绕开浏览器 HTTP 缓存（/thumbnail 带 max-age=86400）
      params: { type: kind, id, size: MEDIA_THUMB_SIZE, ...(bust ? { v: bust } : {}) },
      responseType: 'blob',
    });
    if (data instanceof Blob && data.size > 0) return URL.createObjectURL(data);
  } catch {
    /* 静默 */
  }
  return '';
}

/**
 * 取音频内嵌封面（原始字节，不重新编码）并转换为 `blob:` URL。取不到时返回空串。
 *
 * 与 [fetchThumbUrl] 使用不同端点：`/cover` 返回 ID3 APIC / FLAC PICTURE 原图，
 * 供播放页使用；`/thumbnail` 返回压缩后的小图，供列表使用。
 */
export async function fetchCoverUrl(api: MediaApiClient, id: number): Promise<string> {
  try {
    const { data } = await api.get(Endpoints.media.cover, { params: { id }, responseType: 'blob' });
    if (data instanceof Blob && data.size > 0) return URL.createObjectURL(data);
  } catch {
    /* 无内嵌封面属常见情况，静默处理 */
  }
  return '';
}

/** `GET /api/media/lyrics` 的响应。`found: false` 时 `text` 为空串。 */
export interface MediaLyricsResponse {
  found: boolean;
  source: string;
  text: string;
}

/** `GET /api/media/tags` 的响应（core `audioTagsOf`）。缺失字段为空串，而非 `undefined`。 */
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
 * 单首音频的精确标签。取不到时返回 `null`。
 *
 * ## 通过端点获取而非在前端解析字节
 *
 * `/list` 的 `title/artist/album` 来自 MediaStore，可能缺失（文件刚复制进来尚未扫描，
 * 部分 FLAC/APE 只填充了部分字段）。此时应请求 core 的 `/tags`：该端点先用
 * `AudioTagReader` 解析文件字节（ID3 / FLAC，准确度最高），仍缺失时再用
 * `MediaMetadataRetriever` 兜底。
 *
 * 不得复用文件管理器的前端字节解析实现（`views/files/audioMetaProbe.ts` 的
 * `probeTagsAndCover`）：文件管理器经由 `/api/files/*` 访问，该路径没有标签端点，
 * 只能自行解析；音乐页有 `/tags` 可用，在前端重复解析会使同一解析器存在两份实现，
 * 且 web 版本不支持 MP4/M4A（仅识别 ID3 与 FLAC 魔数），能力弱于 core。
 */
export async function fetchAudioTags(api: MediaApiClient, id: number): Promise<MediaTagsResponse | null> {
  try {
    const { data } = await api.get<MediaTagsResponse>(Endpoints.media.tags, { params: { id } });
    return data ?? null;
  } catch {
    // 404（该曲目已不在库中）与 403（权限不足）均退回 /list 的结果，静默处理
    return null;
  }
}

/**
 * 取旁挂歌词文件（`.lrc` / `.txt`）的内容。文件不存在时返回空串。
 *
 * core 只负责读取旁挂文件，时间轴解析留在客户端（使用 `views/files/id3Lyrics.ts` 的
 * `parseLyrics`，与文件预览共用同一实现：同一 `.lrc` 必须解析出相同的时间轴）。
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
 * 换取播放票据，返回可直接用于 `src` 的 URL。
 *
 * 见文件头约束 2：必须使用响应中的 `url`，不得自行拼接。`baseUrl` 为空表示同源部署，
 * core 返回的相对路径可直接使用。
 */
export async function fetchStreamUrl(api: MediaApiClient, path: string): Promise<string> {
  const { data } = await api.post('/api/files/stream-ticket', { path });
  const url = data?.url;
  if (!url) throw new Error('无法获取播放地址');
  return `${useAppStore().baseUrl || ''}${url}`;
}

/**
 * `blob:` URL 回收器。
 *
 * object URL 会持续持有整个 Blob，浏览器不会自动回收。三个页面都需在卸载时清理，
 * 因此封装为独立对象，避免各页重复实现遍历回收逻辑。
 */
export function createBlobUrlPool() {
  const urls = new Set<string>();
  return {
    /** 登记一个 URL；空串直接忽略，调用方无需判空。 */
    keep(url: string): string {
      if (url) urls.add(url);
      return url;
    },
    /** 回收单个 URL。 */
    release(url: string) {
      if (url && urls.delete(url)) URL.revokeObjectURL(url);
    },
    /** 回收全部 URL。必须在组件的 `onUnmounted` 中调用。 */
    releaseAll() {
      for (const u of urls) URL.revokeObjectURL(u);
      urls.clear();
    },
  };
}

// ──────────────────────────── 格式化 ────────────────────────────

/**
 * 时长格式：`mm:ss` / `h:mm:ss`。
 *
 * 与播放进度条共用同一函数：进度条的已播 / 总时长与列表时长必须采用同一写法，
 * 分别实现会产生 `3:07` 与 `03:07` 两种结果。
 *
 * 缺值显示 `--`，不显示 `0:00`。`duration_ms` 为 0 或字段缺失属于常态：本机 MediaStore
 * 对部分视频与无损音频无法提供时长，core 的 `/list` 原样返回 0。将此类条目显示为
 * 「0:00」等于声明文件时长为零，与「尚未读取到时长」语义不同。该口径与
 * `formatMediaDate` 一致（缺值同样为 `--`）。
 */
export function formatDuration(ms: number | undefined): string {
  if (ms == null || !Number.isFinite(ms) || ms <= 0) return '--';
  const total = Math.floor(ms / 1000);
  const h = Math.floor(total / 3600);
  const m = Math.floor((total % 3600) / 60);
  const s = total % 60;
  const mm = h > 0 ? String(m).padStart(2, '0') : String(m);
  return h > 0 ? `${h}:${mm}:${String(s).padStart(2, '0')}` : `${mm}:${String(s).padStart(2, '0')}`;
}

/** 文件大小。不复用 `composables/utils` 的 `formatBytes`：媒体页需要保留 1 位小数的紧凑格式。 */
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
 * 显示用曲目名：优先使用内嵌标签 `title`，缺失时使用去扩展名的文件名。
 *
 * core 已保证「MediaStore 仅回显文件名」时 `title` 为空串，此处无需再作比较。
 */
export function trackTitleOf(item: MediaItem): string {
  if (item.title) return item.title;
  const dot = item.name.lastIndexOf('.');
  return dot > 0 ? item.name.slice(0, dot) : item.name;
}

/** 艺人显示名。标签为空时统一为「未知艺人」，三处列表需保持相同文案。 */
export function trackArtistOf(item: MediaItem): string {
  return item.artist || '未知艺人';
}

// ──────────────────────────── 本机偏好 ────────────────────────────

/**
 * 音乐页音量的 localStorage 键。
 *
 * 使用独立键，不并入 `ufi.video.prefs`：后者的语义是视频播放器偏好（字幕外观、
 * 字幕轨记忆），混入音频音量会降低两边的可读性。音量属于本浏览器的本地偏好，
 * 不回写 core：各设备对音量的期望不同，同步会相互覆盖。
 */
const LS_AUDIO_VOLUME = 'ufi.audio.volume';

/** 默认音量。不使用 1.0：以满音量开始播放对耳机用户不友好。 */
const DEFAULT_AUDIO_VOLUME = 0.85;

export function readAudioVolume(): number {
  try {
    const raw = localStorage.getItem(LS_AUDIO_VOLUME);
    // 未保存过音量时返回默认值。Number(null) 为 0，直接转换会使首次打开音乐页时
    // 音量为 0。
    if (raw === null) return DEFAULT_AUDIO_VOLUME;
    const v = Number(raw);
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
    /* 隐私模式下不可写；写入失败时仅回退到默认值 */
  }
}

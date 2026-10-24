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
  /**
   * 旁挂字幕条数（2026-10-14 补）。
   *
   * core 的 `/list` 一直在算这个字段（`MediaRoutes` 里按同名规则匹配 `.srt`/`.ass`…），
   * app 的列表也一直在用它显示「字幕 3」；web 的 `MediaItem` 此前**没有这个字段**，
   * 所以数据到了前端也用不上。**声明类型不等于有数据** —— 它只在视频条目上出现。
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
 * 每页条数。core 侧 `MAX_PAGE_SIZE = 500`，这里取 60 ——
 * 网格一屏放得下十几个，60 足够铺满两三屏又不会一次拉几百张缩略图。
 */
export const MEDIA_PAGE_SIZE = 60;

/** 缩略图请求的边长。core 侧夹在 96..1024，256 在 2 倍屏下仍然清楚。 */
export const MEDIA_THUMB_SIZE = 256;

/** 只用得到这几个方法，收窄成最小接口以便传 `useCancellableApi()` 的包装客户端。 */
export type MediaApiClient = Pick<AxiosInstance, 'get' | 'post'>;

/**
 * `/list` 的服务端筛选参数（2026-10-14 加，给音乐页的分组浏览回查用）。
 *
 * 三个字段与 core 的 query 参数**同名同义**，其中 `dir` 刻意不叫 `folder`：
 * `folder` 是分组的**维度**名（`/groups?by=folder`），而回查 `/list` 时那个参数叫 `dir`。
 * 名字不一致是 core 侧的既有事实（见 `MediaRoutes` 的 `queryParameters["dir"]`），
 * 这里照抄而不是"统一"——统一就得在两端各加一层翻译，只会多一个出错的地方。
 */
export interface MediaFilter {
  album?: string;
  artist?: string;
  dir?: string;
}

/**
 * 排序维度。取值与 core 的 `listComparator` **逐字一致**（`name` / `size` / 其余一律按 `date`）。
 * 写错不会报错 —— core 的 `when` 走 `else` 分支静默按日期排，用户会以为"排序没生效"。
 */
export type MediaSortKey = 'date' | 'name' | 'size';

/** 排序方向。core 侧 `order != "asc"` 即降序，所以只认 `asc` 与 `desc` 两个值。 */
export type MediaSortOrder = 'asc' | 'desc';

export interface MediaSort {
  key: MediaSortKey;
  order: MediaSortOrder;
}

/**
 * 默认排序：修改时间倒序。
 *
 * 与 core 的兜底分支一致（不传 `sort` 就是按 `date_modified`），也是三页拆分之前
 * 硬编码的那个口径 —— 拆出来只是让它可改，默认行为一点没变。
 */
export const DEFAULT_MEDIA_SORT: MediaSort = { key: 'date', order: 'desc' };

/**
 * 六档排序，与 app 的 `MediaLibraryPage` 的 `UfiSortAction` 一一对应
 * （时间↑↓ / 名称↑↓ / 大小↑↓）。**做成"六个平铺选项"而不是"三个维度 + 一个方向开关"**：
 * 后者要点两次才能表达"名称升序"，而且两个控件之间的联动（换维度时方向要不要重置）
 * 是额外的状态，六档一次点清更省事。
 */
export const MEDIA_SORT_OPTIONS: ReadonlyArray<{ label: string; value: string } & MediaSort> = [
  { label: '最近修改', value: 'date-desc', key: 'date', order: 'desc' },
  { label: '最早修改', value: 'date-asc', key: 'date', order: 'asc' },
  { label: '名称 A→Z', value: 'name-asc', key: 'name', order: 'asc' },
  { label: '名称 Z→A', value: 'name-desc', key: 'name', order: 'desc' },
  { label: '从大到小', value: 'size-desc', key: 'size', order: 'desc' },
  { label: '从小到大', value: 'size-asc', key: 'size', order: 'asc' },
];

/** `MediaSort` → 上面那个下拉的 value（用于 `n-select` 的 `v-model`）。 */
export function mediaSortValue(s: MediaSort): string {
  return `${s.key}-${s.order}`;
}

/** 下拉的 value → `MediaSort`。认不出回默认，不要抛。 */
export function mediaSortOf(value: string): MediaSort {
  return MEDIA_SORT_OPTIONS.find((o) => o.value === value) ?? DEFAULT_MEDIA_SORT;
}

/**
 * 拉一页列表。
 *
 * 排序**默认**「按修改时间倒序」：媒体库里"最近加进来的"几乎总是用户要找的，
 * 而 app 端首页也是这个口径。2026-10-14 起排序可以由用户改（见 [MediaSortKey]）——
 * 此前三页都硬编码 `sort:'date', order:'desc'`，而 app 侧 `MediaLibraryPage` 有六档排序，
 * web 一个入口都没有。
 *
 * 权限不足时 core 回 **HTTP 403**（`code` 仍是 `BAD_REQUEST`，没有专用错误码），
 * axios 会抛出 —— 调用方据 `status === 403` 显示"未授权"，不要把它当普通网络错误。
 *
 * ──── 为什么筛选参数放在这里，而不是在客户端过滤 ────
 * core 侧是"先按标签筛完、再排序分页"（等价于原来的 SQL `WHERE album = ?`）。
 * 客户端自己筛的话，得先把整库几千首翻页拉完才可能筛对 —— 而分组浏览明明已经从
 * `/groups` 知道"这一组共 N 首"，却还要拉全库才能凑出这 N 首，纯属把服务端已经做完的事
 * 在浏览器里重做一遍。所以筛选必须跟着 `offset` / `limit` 一起下推到 core。
 *
 * `filter` 里为空串的字段**不并进 params**：core 的 `filterParam` 把空串当"没传"，
 * 传一个 `album=` 会静默退回整库（而不是"筛选出空集"），结果比不筛更错。
 *
 * **排序同理必须下推**：客户端排序只能排"已经翻出来的那 60 条"，
 * 用户按名称排序时看到的会是"每页内部有序、页与页之间乱序"——比没有排序更误导。
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
 * 取缩略图字节并转成 `blob:` URL。取不到返回空串。
 *
 * 缩略图不可用是**常态**而不是异常：设备 ROM 解不出视频帧、图片格式浏览器不认、
 * core 还没生成缓存都会落到这里。所以失败一律静默，由调用方渲染占位图标。
 */
export async function fetchThumbUrl(api: MediaApiClient, kind: MediaKind, id: number, bust?: number): Promise<string> {
  try {
    const { data } = await api.get(Endpoints.media.thumbnail, {
      // bust：手动重抽时传时间戳绕开浏览器 HTTP 缓存（/thumbnail 带 max-age=86400）
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
 *
 * **缺值显示 `--`，不显示 `0:00`**（2026-10-21）。`duration_ms` 为 0 / 缺字段是**常态**：
 * 本机 MediaStore 对不少视频与无损音频给不出时长，core 的 `/list` 就原样回 0。
 * 界面上把这种条目写成「0:00」是在说"这是一个零秒的文件"，与「还没读到时长」是两件事；
 * 口径与 `formatMediaDate` 一致（那里缺值也是 `--`）。
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
    const raw = localStorage.getItem(LS_AUDIO_VOLUME);
    // 没有存过 → 用默认音量。不能直接 Number(null)：它是 0，
    // 会让第一次打开音乐页的用户以为"播放器没声音"（2026-10-23 修）。
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
    /* 隐私模式下不可写，丢了只是回默认值 */
  }
}

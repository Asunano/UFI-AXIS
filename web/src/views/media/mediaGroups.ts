/**
 * 音乐页「分组浏览」（专辑 / 歌手 / 文件夹）的类型与取数（2026-10-14）。
 *
 * ## 为什么单独一个模块，而不是塞进 mediaShared / useMediaLibrary
 * `/api/media/groups` 的响应与 `/list` **不是同一套形状**（分组 vs 条目），而且 `by`
 * 同时决定两件事：`key` 的语义（专辑名 / 艺人名 / **目录绝对路径**）、以及回查 `/list`
 * 时那个参数叫什么（`album` / `artist` / `dir`）。这三条映射写在组件里，组件就得同时
 * 知道端点、参数名与错误码翻译；塞进 `useMediaLibrary` 又会把它从"平铺列表 + 分页"
 * 变成两种取数混在一起。所以照 `playlistShared.ts` 的先例，单独一个纯函数模块：
 * 组件只管渲染，这里只管"问谁、怎么问、问回来的东西长什么样"。
 *
 * ## 否决过的替代方案
 * 1. **客户端自己 groupBy**：得先把整库几千首翻页拉完才可能分组正确，而界面要显示的
 *    只是一屏分组。core 侧是一次内存索引遍历 + 聚合（`MediaRoutes` 的 `/groups`），
 *    把这份统计搬到浏览器里做第二遍没有任何好处。
 * 2. **复用 `useMediaLibrary('audio')` 再按 album 本地归堆**：那个 composable 管的是
 *    "一页 60 条 + 加载更多 + 缩略图闸门"，分组要的是"一次全量 + 另一套封面键"，
 *    硬塞进去会得到第二个 `items` 真源和第二个 blob 池（卸载时漏回收其中一个）。
 * 3. **`fetchGroupTracks` 只拉一页、由界面提供"加载更多"**：一个分组最多几百首，
 *    分页只会让"点开专辑只看到前 60 首"成为常态。这里一次要 500
 *    （core 的 `MAX_PAGE_SIZE`）—— 超过 500 首的分组只能显示前 500 首，那是 core 的
 *    硬上限，不是这里的取舍；真要改得先改 core。
 */
import { Endpoints } from '@/api/contract';
import { fetchMediaPage, type MediaApiClient, type MediaFilter, type MediaItem } from './mediaShared';

/**
 * 分组维度。取值与 core `MediaRoutes.GroupBy.key` 逐字一致 ——
 * 写错不是"没结果"，core 直接回 400。
 */
export type MediaGroupBy = 'album' | 'artist' | 'folder';

/**
 * 一个分组。
 *
 * `key` 是**回查用**的值，语义随 `by` 变：`album` → 专辑名、`artist` → 艺人名、
 * `folder` → 目录绝对路径（不是目录名：几十个 `Music/Download` 重名，只有全路径唯一）。
 * 展示要用 `title` / `subtitle`，不要拿 `key` 当标题 —— `folder` 的 `key` 是全路径，
 * 直接显示会把列表撑爆。
 */
export interface AudioGroup {
  key: string;
  /** 展示名。`folder` 取目录名；`album` / `artist` 为空标签时是「未知专辑」/「未知歌手」。 */
  title: string;
  /**
   * 副标题，可能为空串：
   * `album` → 该专辑的主要歌手（组内出现最多的那个，合辑靠它避免"取第一首"随排序漂移）；
   * `artist` → `"N 张专辑"`；`folder` → 目录的父路径。
   */
  subtitle: string;
  /** 组内曲目数。 */
  count: number;
  /** 组内任意一首的 MediaStore id，拿去取缩略图。**`<= 0` = 组内没有可用条目，不要发请求。** */
  cover_id: number;
}

/** `GET /api/media/groups` 的响应。`total` 是**分组总数**，不是曲目总数。 */
export interface AudioGroupsResponse {
  type: string;
  by: string;
  groups: AudioGroup[];
  total: number;
}

/** 三个维度的中文名。页签文案与空列表引导都用这一份，免得两处各写一套说法。 */
export const GROUP_BY_LABELS: Record<MediaGroupBy, string> = {
  album: '专辑',
  artist: '歌手',
  folder: '文件夹',
};

/**
 * 回查组内曲目时一次要多少首。
 *
 * 取 core 的 `MAX_PAGE_SIZE`（500）：这是端点允许的最大值，再大写进 `limit` 也会被
 * `coerceIn(1, MAX_PAGE_SIZE)` 夹回来。**不是**"一屏放得下 500 个"——
 * 这里的 500 是"别让用户看到半截分组"的上限，滚动交给界面。
 */
const GROUP_TRACK_LIMIT = 500;

/**
 * 按维度取全部分组。取不到返回 null（`useCancellableApi` 取消在途请求时就是这种情况，
 * 按"无结果"处理，不要当失败弹错）。
 *
 * 失败时的 403 / 400 / 网络错误都往外抛：三种的界面处置完全不同
 * （去授权 / 参数写错了 / 重试），在取数层吞掉就再也分不出来。文案翻译见 [groupErrorText]。
 */
export async function fetchAudioGroups(api: MediaApiClient, by: MediaGroupBy): Promise<AudioGroupsResponse | null> {
  const { data } = await api.get<AudioGroupsResponse>(Endpoints.media.groups, {
    params: { type: 'audio', by },
  });
  return data ?? null;
}

/**
 * 取某个分组里的曲目。
 *
 * `key` 原样送回 `/list`，参数名随 `by` 变（`folder` → **`dir`**，见 `mediaShared.MediaFilter`）。
 *
 * ⚠️ `album` / `artist` 维度上 `key` 可能是**空串**（没有标签的曲目聚成的那一组，
 * 标题是「未知专辑」/「未知歌手」）。空串会被 core 当成"没传筛选"而返回**整库**，
 * 所以这种分组不能当筛选条件用。拦截放在界面（`GroupBrowserPanel` 把它们画成不可点），
 * **不放在这里**：在这里悄悄改成"回空数组"会让调用方以为"这一组真的没有歌"。
 */
export async function fetchGroupTracks(api: MediaApiClient, by: MediaGroupBy, key: string): Promise<MediaItem[]> {
  const filter: MediaFilter = by === 'album' ? { album: key } : by === 'artist' ? { artist: key } : { dir: key };
  const data = await fetchMediaPage(api, 'audio', 0, GROUP_TRACK_LIMIT, filter);
  return data?.items ?? [];
}

/**
 * 分组浏览的失败文案。
 *
 * 403 单独说：它的下一步是"去设备上授权"，而"加载音乐分组失败：Request failed with
 * status code 403"只会让人以为程序坏了。core 在这个状态下没有专用错误码
 * （`code` 仍是 `BAD_REQUEST`），所以判据只能用 **HTTP 状态码**，
 * 不能用 `message` —— 后者是给人看的，随时会改（与 `playlistErrorText` 同一理由）。
 */
export function groupErrorText(action: string, e: any): string {
  if (e?.response?.status === 403) return '媒体权限未授权，无法读取音乐库';
  return `${action}失败：${e?.message || '未知错误'}`;
}

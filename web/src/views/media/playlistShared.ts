/**
 * 音乐页「歌单」的类型与取数。
 *
 * ## 歌单存在 core，前端只是镜像
 * `/api/playlists` 一组端点（core 侧 `PlaylistRoutes`）。app 与 web 看的是同一份 ——
 * 所以这里没有任何本地持久化，也没有"先改本地再同步"的乐观更新：加歌的真实结果
 * （跳过了几条、有没有撞上限）只有 core 知道，写完重拉才是准的。
 *
 * ## 曲目以真实路径为标识
 * 不是 MediaStore 的 `id` —— 那是媒体库的行号，重扫 / 换卡之后会变，拿它存歌单等于
 * 存一个会失效的引用。所以所有写操作的 body 都是 `{ paths: [...] }`。
 *
 * ## `missing` 是这一组端点独有的字段
 * 文件被删 / 卡没插 / 还没被系统扫到时，core 仍然把那一条返回，只是带 `missing: true`
 * 且 `id` 为 0。UI 必须画成"已失效"并禁止播放，**不要自动帮用户移出** ——
 * 拔一次卡就清空歌单是不可接受的。
 */
import type { AxiosInstance } from 'axios';
import { Endpoints } from '@/api/contract';
import type { MediaItem } from './mediaShared';

/** 歌单写操作要用到 put/delete，比 `MediaApiClient` 宽一点。 */
export type PlaylistApiClient = Pick<AxiosInstance, 'get' | 'post' | 'put' | 'delete'>;

/** 歌单列表里的一项（不含曲目明细）。 */
export interface PlaylistEntry {
  id: string;
  name: string;
  /** 曲目数，**含已失效的** —— 拔一次卡不该让"共 23 首"变少。 */
  count: number;
  /** 首曲的 MediaStore id，给封面用。0 = 空歌单或首曲已失效，此时不要发缩略图请求。 */
  cover_id: number;
  created_at: number;
  updated_at: number;
}

/** 歌单里的一条曲目：与 `/api/media/list` 的 item 同构，多一个 `missing`。 */
export interface PlaylistTrack extends MediaItem {
  missing?: boolean;
}

/** `GET /api/playlists/:id/items` 的响应。`items` 的顺序就是播放顺序。 */
export interface PlaylistItemsResponse {
  id: string;
  name: string;
  items: PlaylistTrack[];
  total: number;
  missing_count: number;
}

/** 加歌的结果。三个计数对应三种"没进去"的原因，要如实说给用户。 */
export interface AddResult {
  added: number;
  skipped: number;
  truncated: boolean;
}

export async function fetchPlaylists(api: PlaylistApiClient): Promise<PlaylistEntry[]> {
  const { data } = await api.get<{ playlists?: PlaylistEntry[] }>(Endpoints.playlists.root);
  // useCancellableApi 取消在途请求时 data 为 undefined，按"没结果"处理（不算失败）
  return data?.playlists ?? [];
}

export async function fetchPlaylistItems(
  api: PlaylistApiClient,
  id: string
): Promise<PlaylistItemsResponse | null> {
  const { data } = await api.get<PlaylistItemsResponse>(Endpoints.playlists.items(id));
  return data ?? null;
}

/** 新建歌单，回 core 生成的 id。名字重复时 core 回 409，由调用方翻译文案。 */
export async function createPlaylist(api: PlaylistApiClient, name: string): Promise<string> {
  const { data } = await api.post<{ id?: string }>(Endpoints.playlists.root, { name });
  return data?.id ?? '';
}

export async function renamePlaylist(api: PlaylistApiClient, id: string, name: string): Promise<void> {
  await api.put(Endpoints.playlists.one(id), { name });
}

export async function deletePlaylist(api: PlaylistApiClient, id: string): Promise<void> {
  await api.delete(Endpoints.playlists.one(id));
}

export async function addPlaylistItems(
  api: PlaylistApiClient,
  id: string,
  paths: string[]
): Promise<AddResult> {
  const { data } = await api.post<AddResult>(Endpoints.playlists.items(id), { paths });
  return { added: data?.added ?? 0, skipped: data?.skipped ?? 0, truncated: data?.truncated ?? false };
}

/**
 * 移出曲目。
 *
 * body 走 axios 的 `data` 选项 —— DELETE 的请求体只能这么带。core 同时也接受
 * `?path=` 重复参数（app 端用那种），两边不必一致。
 */
export async function removePlaylistItems(
  api: PlaylistApiClient,
  id: string,
  paths: string[]
): Promise<number> {
  const { data } = await api.delete<{ removed?: number }>(Endpoints.playlists.items(id), {
    data: { paths },
  });
  return data?.removed ?? 0;
}

/**
 * 歌单写操作的失败文案。
 *
 * 409 与 400 各有明确的下一步（换个名字 / 删几个歌单），不能都说成"操作失败"。
 * 判据用 HTTP 状态码而不是 core 的 message —— 后者是给人看的，会改。
 */
export function playlistErrorText(action: string, e: any): string {
  const status = e?.response?.status;
  if (status === 409) return '已有同名歌单，换个名字再试';
  if (status === 404) return '歌单不存在（可能已在别处被删除）';
  if (status === 403) return '媒体权限未授权，无法读取歌单曲目';
  if (status === 400) return `${action}失败：${e?.response?.data?.message || '参数不合法'}`;
  return `${action}失败：${e?.message || '未知错误'}`;
}

/** 加歌结果 → 一句话。把跳过与截断说出来，否则"选了 20 首只进 3 首"会被当成丢歌。 */
export function addResultText(playlistName: string, r: AddResult): string {
  const head = `已加入「${playlistName}」${r.added} 首`;
  const tail: string[] = [];
  if (r.skipped > 0) tail.push(`${r.skipped} 首已在歌单里或不在媒体库`);
  if (r.truncated) tail.push('歌单条数已达上限');
  return tail.length ? `${head}（${tail.join('，')}）` : head;
}

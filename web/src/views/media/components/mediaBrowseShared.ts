/**
 * `/api/media/browse`（媒体库的「文件夹视图」）的类型、取数与错误文案（2026-10-14）。
 *
 * ## 为什么单独一个模块
 * 与 `mediaConfigShared.ts` / `mediaGroups.ts` 同一条边界：端点、参数名、响应形状、
 * 失败分支的翻译都收在这里，组件只管渲染。视频页与图片页共用同一个
 * `MediaFolderBrowser.vue`，两边配一套取数 —— 抄两份的结果是其中一页对
 * 「多个根」「403 的两种语义」处理得不一样。
 *
 * ## 为什么类型是手抄的
 * core 的 `/browse` 不用 `@Serializable` 实体，而是 `mapOf(...)` + `toJsonElement` 手拼
 * （`MediaRoutes.kt` 的 `get("/browse")`，分支见 2144-2157 与 2246-2257 两处 `call.respond`），
 * 所以没有可镜像的 Kotlin data class。下面的接口逐字段照抄那两段 `mapOf`，
 * **改响应时先回去看它**。
 *
 * ## 响应形状（三个分支，客户端必须都认）
 * ```jsonc
 * // ① 配了多个根且没传 path：这是「先选一个根」，不是「这个目录是空的」
 * { "type":"video", "path":"", "parent":null, "roots":["/a","/b"], "folders":[], "items":[] }
 * // ② 正常一层（items 与 /list 的 items 逐字段相同，含视频的 subtitle_count）
 * { "type":"video", "path":"/a", "parent":null|"/a/b",
 *   "roots":["/a","/b"],
 *   "folders":[{ "name":"c", "path":"/a/c", "count":7, "cover_id":123|null, "date_modified":1712... }],
 *   "items":[ ... ] }
 * ```
 * 三个必须照做的点：
 * ① `path` 为空 = **选根**状态（`roots` 非空），不要渲染成"空文件夹"；
 * ② 回读响应里的 `path` 当当前目录（core 回的是 `canonicalPath`，与请求值可能不同：
 *    传 `/a/b/` 会回 `/a/b`），面包屑必须用回读值；
 * ③ `parent` 为 null = 已经在某个根上，此时「上一级」要禁用 —— 不能再往上退
 *    （根之上不是这一类的目录，退了也会被 core 以 403 拒掉）。
 *
 * ## 否决过的替代方案
 * · **把 `fetchMediaBrowse` 塞进 `mediaShared.ts`**：那个文件是三个页面（含音乐页）
 *   共用的取数，而 `/browse` 只有视频/图片用 —— 音乐页的「文件夹」走 `/groups?by=folder`
 *   （core 侧 `/browse` 对 audio 虽然不报错，但音乐页的既有入口是分组浏览）。
 *   混进去会让音乐页也"看得见"一个它不该用的端点。
 * · **在组件里内联 axios 调用**：403 有**两种**语义（媒体权限未授权 / 目录不在扫描范围内），
 *   文案与后续处置完全不同，判定规则必须只有一处。
 */
import { Endpoints } from '@/api/contract';
import {
  DEFAULT_MEDIA_SORT,
  type MediaApiClient,
  type MediaItem,
  type MediaKind,
  type MediaSort,
} from '../mediaShared';

/**
 * 一个子目录条目。
 *
 * `count` 是**子树所有深度**里同类文件的条数（core 侧按 `DATA LIKE '<sub>/%'` 数），
 * 不是"这一层有几个"；空文件夹（该类型一个文件都没有）core 直接不返回。
 */
export interface MediaBrowseFolder {
  name: string;
  path: string;
  count: number;
  /**
   * 文件夹封面：子树里**最近修改**的那一条媒体的 MediaStore `_ID`（不是路径），
   * 所以直接喂 `fetchThumbUrl(api, kind, cover_id)` 即可 —— 与列表页同一个端点。
   * `null` = 子树里没有可用条目（core 侧是 `coverId` 从未被赋值）。
   */
  cover_id: number | null;
  /** 目录自身的 `lastModified()`（毫秒），不是里面文件的修改时间。 */
  date_modified: number;
}

/** `GET /api/media/browse` 的响应。 */
export interface MediaBrowseResponse {
  type: string;
  /** 当前目录（`canonicalPath`）。**空串 = 请先选一个根**，见文件头 ①。 */
  path: string;
  /** 上一级；`null` = 已经在根上，不能再退。 */
  parent: string | null;
  /** 这一类的根目录集合（配置了扫描目录就是它们，没配就是外置存储根）。 */
  roots: string[];
  folders: MediaBrowseFolder[];
  /** 与 `/list` 的 `items` 逐字段相同（core 侧同一个 `itemOf`）。 */
  items: MediaItem[];
}

/**
 * 列一层目录。
 *
 * `path` 为空时**不传这个参数**：core 侧是 `takeIf { it.isNotBlank() }`，
 * 空串与没传等价，传 `path=` 只会让 URL 多一个没有意义的参数。
 *
 * 排序与 `/list` 认**同一组** `sort`/`order`（core 侧同一个 `sortListItems`），
 * 所以页面的排序直接透传 —— 文件夹视图里"这一层的文件"与网格里的顺序口径一致。
 *
 * 返回 `null` = 请求被取消（组件卸载 / 换了目录，`useCancellableApi` 把取消 resolve 成
 * `data === undefined`），调用方按"这次没拿到"处理，**不要当成失败**。
 */
export async function fetchMediaBrowse(
  api: MediaApiClient,
  kind: MediaKind,
  path: string,
  sort?: MediaSort
): Promise<MediaBrowseResponse | null> {
  const params: Record<string, string | number> = {
    type: kind,
    sort: sort?.key ?? DEFAULT_MEDIA_SORT.key,
    order: sort?.order ?? DEFAULT_MEDIA_SORT.order,
  };
  if (path) params.path = path;
  const { data } = await api.get<MediaBrowseResponse>(Endpoints.media.browse, { params });
  return data ?? null;
}

/**
 * 是不是「媒体权限未授权」。
 *
 * **必须按响应体里的 `permission` 字段判，不能只看 403**：`/browse` 的 403 有两种，
 * 另一种是「目录不在允许范围内：请先把它加进本类型的扫描目录」——
 * 后者是"用户点到了扫描范围之外的路径"，与权限无关，文案与处置完全不同。
 * （core 的失败信封是**扁平**的：`respondFail` 把 `extra` 直接摊在顶层，
 * 所以是 `data.permission` 而不是 `data.extra.permission`。见 `ResponseHelper.fail`。）
 */
export function isMediaPermissionDenied(e: any): boolean {
  return e?.response?.status === 403 && !!e?.response?.data?.permission;
}

/**
 * `/browse` 的失败文案。
 *
 * 403（目录不在范围内）与 404（目录不存在或不可读）的 `message` **原样透出**：
 * 那两句话是唯一能告诉用户"到底哪里不对"的东西，统一改写成"加载失败"等于把它扔掉。
 * 403 未授权走 `permissionDenied` 分支（见 [isMediaPermissionDenied]），不经过这里。
 */
export function mediaBrowseErrorText(e: any): string {
  const status = e?.response?.status;
  const coreMessage = e?.response?.data?.message || e?.response?.data?.error;
  if ((status === 403 || status === 404) && coreMessage) return String(coreMessage);
  return `读取文件夹失败：${e?.message || '未知错误'}`;
}

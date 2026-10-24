/**
 * 音乐页「已从音乐库移除的歌曲」（排除名单）的取数与结果文案（2026-10-14）。
 *
 * ## 为什么存在
 * `GET /api/media/excluded` / `POST /api/media/exclude` 是 core 从 2026-09-23 就有的
 * 一组端点（`MediaRoutes` 里那一段 KDoc 写清了语义边界：**只动名单，永远不动文件**），
 * 但 web 侧此前没有任何界面 —— 被排除的歌只在 app 里能撤销。这一条按「各回各家」
 * 落到音乐页：它是**只对 `type=audio` 生效**的，视频页与图片页没有对应物，
 * 所以不做成三页共用的组件，只作为音乐页「设置」浮层里的第二个区块。
 *
 * ## 为什么单独一个模块
 * 与 `mediaConfigShared.ts` 同一条边界。这里额外多一件事：`affected < requested` 的
 * 解读随 `action` 而变（见 [excludeResultText]），那段判断必须只有一份 ——
 * 写在组件里，两个调用点（单条移回 / 全部清空）就会各写一遍，而它们对"没生效"
 * 的解释并不相同。
 *
 * ## 否决过的替代方案
 * 1. **把名单存进 Pinia / localStorage 做乐观更新**：名单的真源在设备，app 也在改；
 *    乐观更新会让"我点了移回但列表里还是少一首"变成看不到的错。写完重拉才是准的
 *    （与 `playlistShared.ts` 同一条理由）。
 * 2. **在客户端算 `full`**（`total >= 某个写死的 300`）：`max` 是响应里给的
 *    （`MediaExclusionStore.MAX_EXCLUSIONS`），而且那条约束的由来已经变过两次
 *    （SQLite 绑定变量 → 产品约束）。客户端写死一个数，core 一改就会提前/滞后报警。
 * 3. **把被排除的路径拿去 `/list` 回查元数据**：查不到 —— 被排除的路径**从 `/list`
 *    的结果里直接消失**（`filterListItems` 的第四层过滤），这正是 `contract.ts` 里
 *    写明"管理界面只能按路径显示文件名"的原因。所以列表就是路径列表。
 */
import type { AxiosInstance } from 'axios';
import { Endpoints } from '@/api/contract';

/** 只用到 get 与 post（与 `mediaShared.MediaApiClient` 同形状，但语义不同，故另起一个名字）。 */
export type MediaExcludedApiClient = Pick<AxiosInstance, 'get' | 'post'>;

/** `POST /api/media/exclude` 的三种动作。 */
export type ExcludeAction = 'add' | 'remove' | 'clear';

/** `GET /api/media/excluded` 的响应。 */
export interface ExcludedResponse {
  /** 被排除的真实路径（不是 id：id 重扫会变，路径不会）。 */
  paths: string[];
  /** 名单条数，等于 `paths.length`。 */
  total: number;
  /**
   * 名单上限，由 core 给出（当前是 300）。
   * **不要在前端写死这个数** —— 它历史上变过两次口径（见 core 的 `MediaExclusionStore`）。
   */
  max: number;
  /** core 算好的 `total >= max`。用它**提前**提示，而不是等用户点了才发现没生效。 */
  full: boolean;
}

/** `POST /api/media/exclude` 的响应。 */
export interface ExcludeResult {
  success: boolean;
  action: string;
  /** 实际生效条数。 */
  affected: number;
  /** 请求条数（`clear` 时是 0，因为不需要传 paths）。 */
  requested: number;
  /** 操作后的名单总数。 */
  total: number;
  /** 操作后的名单是否已满。 */
  full: boolean;
}

/**
 * 读排除名单。
 *
 * 取不到返回 `null`（`useCancellableApi` 取消在途请求时 `data` 为 undefined）。
 * **不要当成空名单** —— 那会让"名单里有 20 首"显示成"名单是空的"。
 *
 * 名单未启用时 core 回 **503**（`排除名单未启用`），这里照常抛出，由
 * [excludedErrorText] 把 core 的文案透出去。
 */
export async function fetchExcluded(api: MediaExcludedApiClient): Promise<ExcludedResponse | null> {
  const { data } = await api.get<ExcludedResponse>(Endpoints.media.excluded);
  if (!data || !Array.isArray(data.paths)) return null;
  return {
    paths: data.paths,
    total: Number(data.total) || data.paths.length,
    max: Number(data.max) || 0,
    full: !!data.full,
  };
}

/**
 * 改排除名单（加 / 移回 / 清空），回 core 的结果计数。
 *
 * 返回 `null` = 请求被取消（组件卸载），调用方不要据此改界面状态。
 * 失败往外抛：503（名单未启用）与 400（`paths` 为空 / `action` 不认）的处置完全不同，
 * 翻译见 [excludedErrorText]。
 *
 * `clear` 时 core 允许空 `paths`（`action != "clear" && paths.isEmpty()` 才回 400），
 * 这里仍然照发一个空数组 —— 与 core 的入参形状保持一致，不做"clear 就不带 paths"的特例。
 */
export async function excludePaths(
  api: MediaExcludedApiClient,
  action: ExcludeAction,
  paths: string[] = []
): Promise<ExcludeResult | null> {
  const { data } = await api.post<ExcludeResult>(Endpoints.media.exclude, { paths, action });
  if (!data) return null;
  return {
    success: !!data.success,
    action: String(data.action ?? action),
    affected: Number(data.affected) || 0,
    requested: Number(data.requested) || 0,
    total: Number(data.total) || 0,
    full: !!data.full,
  };
}

/**
 * 一次写操作之后给用户看的那句话。
 *
 * ## 为什么 `affected < requested` 要分三种情况说
 * 它是客户端**唯一**能发现"只生效了一部分"的途径（见 `contract.ts`），
 * 但"少了几条"的**原因随 action 不同**，说错就是把用户往错的方向指：
 *
 * - `add`：`MediaExclusionStore.add` 既会跳过已在名单里的、又会在撞上限时 `break`。
 *   所以缺口要么是"本来就在名单里"（`full === false`），要么是"名单满了"（`full === true`）。
 *   后者才是需要用户去删几条的那种，必须点名。
 * - `remove`：`remove` 只移除**确实在名单里**的。`affected < requested` 表示
 *   有路径已经不在名单里了（界面是旧的 / 另一端刚移回），**与上限无关**，
 *   说成"撞上限被截断"会让人去删根本不存在的条数。
 * - `clear`：`requested` 是 0（不需要传 paths），只有 `affected`（清掉了几条）有意义，
 *   不能拿它去和 `requested` 比 —— 那会得出"一条都没生效"的错误结论。
 */
export function excludeResultText(action: ExcludeAction, res: ExcludeResult): string {
  const missing = res.requested - res.affected;
  if (action === 'clear') return res.affected > 0 ? `已清空 ${res.affected} 首` : '名单本来就是空的';
  if (action === 'remove') {
    if (res.affected === 0) return '这些曲目已不在名单里（列表可能已过期）';
    if (missing > 0) return `已移回 ${res.affected} 首，另有 ${missing} 首已不在名单里`;
    return `已移回 ${res.affected} 首`;
  }
  // add
  if (res.affected === 0) {
    return res.full ? '名单已满，一首都没加进去，请先移除一些' : '没有新增（这些曲目本来就在名单里）';
  }
  if (missing > 0) {
    return res.full
      ? `已加入 ${res.affected} 首，另有 ${missing} 首因名单已满未生效`
      : `已加入 ${res.affected} 首，另有 ${missing} 首本来就在名单里`;
  }
  return `已加入 ${res.affected} 首`;
}

/**
 * 排除名单读写的失败文案。
 *
 * core 的失败信封把同一句话写在 `error` 与 `message` 两个键上，且这两句都是
 * 可以原样给用户看的（「排除名单未启用」「paths 不能为空」），所以能读到就原样透出；
 * 403 判 **HTTP 状态码**（与 `mediaConfigErrorText` 同一理由：`code` 在这个端点上
 * 也没有专用值）。
 */
export function excludedErrorText(action: string, e: any): string {
  const status = e?.response?.status;
  const coreMessage = e?.response?.data?.message || e?.response?.data?.error;
  if (coreMessage) return String(coreMessage);
  if (status === 403) return '媒体权限未授权，无法读取音乐库';
  return `${action}失败：${e?.message || '未知错误'}`;
}

/**
 * 路径的显示名（末段）。
 *
 * 被排除的路径拿不到元数据（`/list` 里已经没有了），所以列表只能按路径显示 ——
 * 这里给"文件名当主标题、全路径当副标题"用。全路径可能以 `/` 结尾（core 的
 * `remove`/`add` 只 `trim()` 不去尾斜杠），所以先去掉尾部斜杠再取末段。
 */
export function excludedFileName(path: string): string {
  const p = path.replace(/\/+$/, '');
  const idx = p.lastIndexOf('/');
  return idx >= 0 ? p.slice(idx + 1) || p : p;
}

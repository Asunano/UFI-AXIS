/**
 * 媒体扫描范围（`GET/PUT /api/media/config`）的取数与错误文案（2026-10-14）。
 *
 * ## 为什么存在
 * core 的 `MediaRoutes.put("/config")` 从 2026-09-16 起就是**按类型分开写**的
 * （注释原文：「视频页改视频的、音乐页改音乐的，互不覆盖」），也就是说设计意图一直是
 * "这个开关放在各媒体页上"。但 web 侧此前**一个调用点都没有** —— 用户只能在 app 里
 * 配扫描目录，而 `Endpoints.media.config` 在 web 上是个从没被用过的声明。
 * 本次「各回各家」把它补上：视频 / 音乐 / 图片三页各有一个入口，三页共用本模块
 * 与同一个组件（`components/MediaScanDirsSettings.vue`）。
 *
 * ## 为什么单独一个模块
 * 与 `mediaGroups.ts` / `playlistShared.ts` 同一条边界：端点、参数名、core 的清洗规则、
 * 错误翻译都收在这里，页面与共用组件只管渲染。三份界面配一套取数，是"写三份必然分叉"
 * 的最小代价版本。
 *
 * ## 否决过的替代方案
 * 1. **取数内联进共用组件的 setup**：`useCancellableApi()` 必须在 setup 里调，
 *    于是组件里会出现 axios 调用 + 文案 + 清洗三件事混在模板旁边；而 core 的清洗规则
 *    （`trim` / 去尾斜杠 / 去重 / 上限）在"保存前判断改没改"和"保存后回读"两处都要用，
 *    放模块里只有一份。
 * 2. **在客户端预校验目录是否在白名单内**（省一次 400）：白名单口径在 core
 *    （`/storage`、`/sdcard`、`/mnt/media_rw` 三个前缀，且先 `canonicalPath` 再比，
 *    用来挡符号链接旁路），前端复刻一份迟早与 core 漂开；而 core 的 400 文案里带着
 *    **第一个非法目录**，原样透出比前端猜准得多。
 * 3. **本地缓存一份 dirs**：配置的真源在设备（app 端也能改），本地缓存只会让两端
 *    各说一套。每次打开浮层重拉一次，代价是一次极小的 GET。
 */
import type { AxiosInstance } from 'axios';
import { Endpoints } from '@/api/contract';
import type { MediaKind } from './mediaShared';

/**
 * 读写扫描目录用 `get` / `put`；2026-10-14 起多一个 `post`（重新扫描是 POST）。
 *
 * 不复用 `mediaShared.MediaApiClient`：那个刻意只收 `get | post`（列表与票据），
 * 为了这里加 `put` 会把它变宽，而"列表取数用不到 put"这条信息本身就是有用的约束。
 * 与 `playlistShared.PlaylistApiClient` 同一种做法。
 */
export type MediaConfigApiClient = Pick<AxiosInstance, 'get' | 'put' | 'post'>;

/**
 * 三个类型的界面名。
 *
 * 空列表文案（「未配置 = 不限目录，扫全部 X」）、区块标题、成功提示都取这一份 ——
 * 三页各写一套说法必然分叉成"视频/影片"、"音乐/音频"两种叫法。
 */
export const MEDIA_KIND_LABELS: Record<MediaKind, string> = {
  video: '视频',
  audio: '音乐',
  image: '图片',
};

/**
 * 扫描目录条数上限。
 *
 * **与 core `MediaRoutes.MAX_SCAN_DIRS` 同值（16），改一边必须改另一边。**
 * 前端拿它做两件事：输入框旁的计数提示、以及"还能不能再加"的即时反馈。
 * 但它**不是判据** —— 真正的判据是 core 的 400（`扫描目录最多 16 个`），
 * 那句话由 [mediaConfigErrorText] 原样透出。core 改了上限而这里忘了跟，
 * 用户看到的报错仍然正确，只是计数提示会短暂地说错。
 */
export const MAX_SCAN_DIRS = 16;

/** `GET /api/media/config` 的响应。`PUT` 成功时回的 `dirs` 是**清洗后**的那一份。 */
export interface MediaConfigResponse {
  type: string;
  dirs: string[];
}

/**
 * 按 core 的口径规范化一份目录列表：`trim()` → 去尾部斜杠 → 丢空串 → 去重。
 *
 * 逐字对齐 `MediaRoutes.put("/config")` 里的 `cleaned`。用途只有一个：
 * 判断"用户改过没有"（保存按钮要不要亮）。**不要**拿它当提交前的清洗 ——
 * 提交的是用户输入的原样列表，清洗是 core 的职责，前端再洗一遍只会多出
 * "界面上显示的是这个、存下去的是另一个"这种对不上的可能。
 */
export function normalizeScanDirs(dirs: string[]): string[] {
  return Array.from(new Set(dirs.map((d) => d.trim().replace(/\/+$/, '')).filter((d) => d !== '')));
}

/**
 * 读某一类已配置的扫描目录。
 *
 * **空数组 = 这一类不限目录**（core `scanDirs` 的 KDoc：「空 = 这一类不限目录」，
 * `/list` 与 `/groups` 的过滤都是 `dirs.isNotEmpty() && …` 才生效），不是"什么都不扫"。
 *
 * 取不到返回 `null`（`useCancellableApi` 取消在途请求时 `data` 就是 undefined），
 * 调用方按"这次没拿到"处理 —— **不要当成空数组**，那会把用户已有的配置显示成没配过，
 * 接着一次保存就把配置清空了。
 */
export async function fetchScanDirs(api: MediaConfigApiClient, kind: MediaKind): Promise<string[] | null> {
  const { data } = await api.get<MediaConfigResponse>(Endpoints.media.config, { params: { type: kind } });
  if (!data || !Array.isArray(data.dirs)) return null;
  return data.dirs;
}

/**
 * 写某一类的扫描目录，回 core **清洗后**的那一份（去重 / trim / 去尾斜杠的结果）。
 *
 * 失败往外抛：400 的原因（哪个目录不在白名单、是否超上限）只有 core 知道，
 * 文案翻译见 [mediaConfigErrorText]；吞掉就再也分不出"目录非法"和"网络断了"。
 *
 * 返回 `null` = 请求被取消（组件卸载），调用方不要据此改界面状态。
 */
export async function saveScanDirs(
  api: MediaConfigApiClient,
  kind: MediaKind,
  dirs: string[]
): Promise<string[] | null> {
  const { data } = await api.put<MediaConfigResponse>(Endpoints.media.config, { dirs }, { params: { type: kind } });
  if (!data || !Array.isArray(data.dirs)) return null;
  return data.dirs;
}

/**
 * 触发一次媒体库重新扫描（2026-10-14 加）。
 *
 * ## 为什么需要它
 * 用户刚用文件管理器拷进来的文件，在 MediaStore 里要等系统扫描才出现 ——
 * "我明明放进去了却看不到"必然遇到，而 web 此前**没有任何出口**（app 有「重新扫描」）。
 *
 * ## 为什么返回 void 而不是新的列表
 * core 侧是**异步触发**：立刻回 200，扫描在后台跑。所以调用方**不能**紧接着 reload ——
 * 那时扫描还没完，拉到的还是旧列表。正确做法是隔几秒再刷新一次（或靠 WS 推送），
 * 这件事由调用方决定（各页的刷新时机不一样：视频页有封面预热要等更久）。
 *
 * 失败（403 未授权 / 网络）照常抛出，文案交给 [mediaConfigErrorText]。
 */
export async function rescanMedia(api: MediaConfigApiClient, kind: MediaKind): Promise<void> {
  // 必须发 {}，不能发 null：core 的 receiveJsonObject() 会把字面量 null 解析成
  // JsonNull，再取 .jsonObject 就报 "Element class ... is not a JsonObject"。
  // 这个端点本身不要求参数，空对象就是“没有 dirs，使用已保存目录”。
  await api.post(Endpoints.media.rescan, {}, { params: { type: kind } });
}

/**
 * 扫描目录读写的失败文案。
 *
 * **400 的 `message` 原样透出**（core 的 `respondFail` 把同一句话写在 `error` 与
 * `message` 两个键上）：这是这一组端点里唯一能告诉用户"到底哪一条不合法"的东西 ——
 * 「目录不在用户存储范围内: /data/local/tmp」直接点出了第一个非法目录，
 * 「扫描目录最多 16 个」直接给了上限。统一改写成"保存失败"等于把这些信息全扔掉，
 * 用户只能反复试。
 *
 * 403 判的是 **HTTP 状态码**而不是文案：媒体权限未授予时 core 回的 `code` 仍是
 * `BAD_REQUEST`（没有专用码），而文案是给人看的、随时会改（与 `playlistErrorText`
 * 同一理由）。
 */
export function mediaConfigErrorText(action: string, e: any): string {
  const status = e?.response?.status;
  const coreMessage = e?.response?.data?.message || e?.response?.data?.error;
  if (status === 400 && coreMessage) return String(coreMessage);
  if (status === 403) return '媒体权限未授权，无法读取或修改扫描范围';
  return `${action}失败：${e?.message || '未知错误'}`;
}

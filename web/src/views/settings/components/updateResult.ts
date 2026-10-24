/**
 * 「上次更新结果」这条通道（2026-10-14）：形状 + sessionStorage 落盘 + 卡片上那一行文案。
 *
 * ## 要修的那个病：结论只活在"设置弹窗开着"的那段时间里
 * 更新结论原来只有两条**易失**通道：store 的 `notice`（面板 `watch` 消费后立刻
 * `clearNotice()`）与 naive 的 toast。而设置页是**弹窗**（`SettingsView.vue` 的 `n-modal`，
 * 默认 `display-directive="if"`，关掉即卸载面板）—— 关着弹窗时更新完成，没有任何人消费
 * notice；若中间还刷新过整页，store 整个重建，那条结论**永远看不到**。
 * 更新本身不受影响（core 侧是自主完成的），受影响的只是"用户能不能看到结论"。
 * 所以结论要落进 store 的一个**持久字段**，并且扛得住刷新 —— 见下面的 sessionStorage。
 *
 * ## 为什么单独一个文件（而不是塞进 updateShared.ts）
 * `updateShared.ts` 是「状态 → 标签 / 失败 → 原因+建议」的**展示口径**，它的文件头写明
 * "store 里是状态，这里是中文展示口径"。本文件是**结果这条通道**：一个形状（两条更新链路
 * 共用）、一套落盘手法、一行成文。把落盘塞进展示口径文件，会让"谁负责写 sessionStorage"
 * 变模糊 —— 那正是本仓在"同一个状态两个名字"上踩过的同一类坑。
 *
 * ## 为什么放 `views/settings/components/` 而不是 `stores/`
 * 两条链路（`stores/update.ts` 的 core APK 与 `stores/webUpdate.ts` 的前端资源）都要用它，
 * 而本仓已有 store 反向引用 views 的先例（`stores/player.ts` → `views/media/mediaShared.ts`）。
 *
 * ## 键名与"为什么不存 URL"
 * 键：`ufi.update.lastResult`（core APK）/ `ufi.webUpdate.lastResult`（前端资源），
 * 前缀手法与 `stores/player.ts` 的 `ufi.player.session` 一致。
 * 快照里**只有四个字段**（成败 / 版本号 / 时间 / 文案），绝不整份序列化 store 状态：
 * 那份状态里有 `status.apkPath`（设备上的绝对路径）、`BackendUpdateInfo.apkUrl`（上游直链，
 * 可能带签名参数）、更新源地址 —— 要么是设备内部信息，要么是会过期的票据。
 * 把票据存进浏览器快照，刷新后拿到的是一张**必然失效、却又看着像能用**的票
 * （`stores/player.ts` 的文件头为 blob / 票据 URL 写过同一条：存下来必然是张废票）。
 *
 * ## 为什么用 sessionStorage 而不是 localStorage
 * 与 `stores/player.ts` 同一手法：**每标签页**一份。更新结论是设备侧事实，但它只对
 * "发起这次更新的这个页面"有意义 —— 另一个标签页在跑更新时不该把这一页刚读到的结论顶掉；
 * 关掉标签页后也不必留着一句可能早已过期的"上次更新成功"，那时 core 的
 * `/api/update/status` 才是真源（卡片上的「状态」行本来就如实显示它）。
 */
import { classifyPushError } from './updateShared';

/**
 * 一次更新的结论。
 *
 * 与 `UpdateNotice`（一次性提示）的分工：notice 是**这一刻弹一下**的即时反馈，
 * 本类型是**结论**——常驻在卡片上、写进 sessionStorage、扛得住刷新。
 * 同一次终态两处都写：结论进这里，即时反馈走 notice。谁都不许只写一处，
 * 更不许拿 notice 反推结论（它消费一次就没了）。
 */
export interface UpdateLastResult {
  /**
   * 是否成功。
   *
   * `false` 覆盖三种终态：失败（core 自报 failed / 上传被拒）、超时（前端等不到结论）、
   * 以及 `need_push`（更新源不可达，得用户手动上传 —— 它不是"成功"，因为这次更新没发生）。
   */
  ok: boolean;
  /** 成功时的新版本号（已剥掉 `v` 前缀）。空串 = 读不到版本（例如本地包安装，清单没参与）。 */
  version: string;
  /**
   * 结论的**确认时刻**（不是"安装完成时刻"）。
   *
   * core APK 那条链路要跨一次 core 重启：`done` 只是"包装完了"，必须等新进程把 `/health`
   * 拉起来并复核版本号才算成了 —— 所以时间戳记的是"我们确认它的那一刻"，
   * 手动点「重新检查状态」复核成功时也会刷新成那一刻。
   */
  at: number;
  /** core 的原话（失败原因 / 成功文案）。失败时优先原话，见 [formatLastResult]。 */
  message: string;
}

/** 两条链路各自的键。分开存：一条链路的结论不该把另一条的顶掉（它们可以各有一份）。 */
export const UPDATE_RESULT_KEYS = {
  backend: 'ufi.update.lastResult',
  web: 'ufi.webUpdate.lastResult',
  /**
   * 可选组件（2026-10-14 资源中心接入）。它们共用一套端点，但 frpc 与 cloudflared
   * 是**两张卡**、各自要有一行「上次结果」，所以一个组件一个键，而不是一条链路一个键。
   * 前缀与另两条一致（`ufi.<链路>.…`），沿用 `stores/player.ts` 的 `ufi.player.session` 手法。
   */
  frpc: 'ufi.components.frpc.lastResult',
  cloudflared: 'ufi.components.cloudflared.lastResult',
} as const;

/**
 * 资源 id → 快照键。
 *
 * 为什么要有这个函数：资源中心的描述符只声明自己的 `id`，不该再各自抄一份键名
 * （抄一份的后果是"改了 id 却忘了改键"，而那种漂移的表现是**结论静默消失**）。
 * 表里没有的 id 落一条**通用键** —— 于是"加一个新资源"这件事在本文件里是零改动，
 * 只有想与设置页共用同一行结论时才需要往 [UPDATE_RESULT_KEYS] 里补一条。
 */
export function resultKeyFor(id: string): string {
  const known = (UPDATE_RESULT_KEYS as Record<string, string>)[id];
  return known ?? `ufi.resource.${id}.lastResult`;
}

/**
 * 快照版本。字段语义一旦要改就 +1，读到旧版**直接丢弃** ——
 * 宁可暂时没有结论，也不要拿旧语义的字段渲染出一句错话。
 */
const SS_VERSION = 1;

/** 清单里是 `v0.0.5`、BuildConfig 里是 `0.0.5`：文案会自己补 `v`，所以存之前先剥前缀。 */
function normalizeVersion(v: string): string {
  return String(v ?? '')
    .trim()
    .replace(/^[vV]/, '');
}

function asString(v: unknown): string {
  return typeof v === 'string' ? v : '';
}

/**
 * 读快照。隐私模式 / 配额 / 脏数据一律当成"没有记录"（返回 `null`），**绝不抛** ——
 * 丢的只是一行历史结论，不该让设置页打不开（与 `player.persist` 同一口径）。
 */
export function readLastResult(key: string): UpdateLastResult | null {
  let raw: string | null = null;
  try {
    raw = sessionStorage.getItem(key);
  } catch {
    return null;
  }
  if (!raw) return null;
  let snap: { v?: unknown; r?: Record<string, unknown> } | null = null;
  try {
    snap = JSON.parse(raw) as { v?: unknown; r?: Record<string, unknown> } | null;
  } catch {
    return null;
  }
  const r = snap?.r;
  // 形状不对（改过版本 / 手改过 / 别的代码写过同一个键）就当没有记录
  if (snap?.v !== SS_VERSION || !r || typeof r.ok !== 'boolean' || typeof r.at !== 'number') return null;
  return { ok: r.ok, version: asString(r.version), at: r.at, message: asString(r.message) };
}

/** 写快照。失败只吞掉（丢的是"刷新后还能看见结论"，不该影响更新本身）。 */
function writeLastResult(key: string, result: UpdateLastResult): void {
  try {
    sessionStorage.setItem(key, JSON.stringify({ v: SS_VERSION, r: result }));
  } catch {
    /* 隐私模式 / 配额满：见上 */
  }
}

/**
 * 归一 + 落盘，并**返回存下来的那一份** —— 调用方直接把它塞进响应式字段，
 * 避免"ref 里归一一次、快照里再归一一次"这种两处各写一遍的漂移。
 */
export function saveLastResult(key: string, ok: boolean, version: string, message: string): UpdateLastResult {
  const result: UpdateLastResult = { ok, version: normalizeVersion(version), at: Date.now(), message };
  writeLastResult(key, result);
  return result;
}

/**
 * `2026-10-15 22:31`。
 *
 * 刻意不用 `toLocaleString`：它的输出随 locale / 浏览器设置变（`2026/10/15 22:31:00`），
 * 而这一行是"结论"，格式要稳定、要短（秒级精度在这里没有意义）。
 */
function formatAt(at: number): string {
  if (!Number.isFinite(at) || at <= 0) return '';
  const d = new Date(at);
  const p = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`;
}

/**
 * 卡片上那一行的文案。`null` → 空串（调用方据此 `v-if`，**不摆"暂无记录"占位**：
 * 没有记录就是"这次会话没跑过更新"，摆一行占位只会让卡片多一句废话）。
 *
 * @param artifact 载体名词，只影响失败分类里那四处带"APK"的文案（APK / ZIP / BIN），
 *   口径见 `updateShared.ts` 的 `classifyPushError`。
 */
export function formatLastResult(result: UpdateLastResult | null, artifact: 'APK' | 'ZIP' | 'BIN'): string {
  if (!result) return '';
  if (result.ok) {
    const version = result.version ? ` v${result.version}` : '';
    const when = formatAt(result.at);
    return `上次更新结果：成功${version}${when ? ` · ${when}` : ''}`;
  }
  /*
   * 失败原因**优先用 core 的原话**：`classifyPushError` 的兜底分支就是原样返回 message
   * （core 的中文原因通常已经是一句完整的话），只有认得出的那几类（413/502/连接失败/
   * 磁盘满/SHA 校验失败…）才换成分类文案。那几类必须与两个弹窗说同一句话 ——
   * 同一个失败在卡片上是一种说法、在弹窗里是另一种，用户就没法互相印证
   * （移植口径与"不要顺手优化措辞"的要求见 updateShared.ts 的文件头）。
   */
  return `上次更新结果：失败：${classifyPushError(result.message, artifact).cause}`;
}

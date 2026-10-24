/**
 * 资源卡片共用的**任务型 runner**（2026-10-14 新建；2026-10-23 服务 / 插件分家后仍共用）。
 *
 * ## 用途
 * 驱动 `ResourceCard` 的异步动作：**服务更新里的 Web 前端包**（设置 → 服务更新）与
 * **资源中心里的插件组件**（`/resources`）。
 * **提交一个动作 → 轮询服务端自报的状态 → 落到终态**。
 * 动作有五种（在线更新 / 本地上传 / 卸载 / 回滚 / 恢复内置），前两种是"任务"，
 * 后三种是同步端点（提交即完成）。状态放在**模块作用域**，所以切页、关弹窗、
 * 重开页面都还在 —— 手法照 `views/files/useBatchOperation.ts`。
 *
 * ## 为什么不复用 `useApiOperation`（这是本文件存在的唯一理由）
 * `useApiOperation` 是**两段式确认器**：`submit()` 下发，然后反复 `verify()`
 * **回读目标值**直到命中，成败由客户端判定。它假设"目标是一个能读回来的确定状态"
 * （WiFi 开了没有、频段锁没锁上）。本次三类资源的形状正好相反：
 *
 * · 服务端**自报**进度与百分比（`/api/web/status`、`/api/components` 的进度槽），
 *   客户端不需要知道"目标值"是什么，只需照抄 core 的 `state` / `percent` / `message`；
 * · 成败也由 core 判定（`done` / `failed` / `need_push`），不是"回读到目标值"；
 * · 进度是**连续量**（下载 / 解压 / 安装一路 0→100），而 `useApiOperation` 复用的是
 *   `NetworkModeSwitchProbe` 那套「600ms 首延迟 + 13 次有上限」的短探测预算 ——
 *   几十 MB 的组件下载会在这套预算里被判定成"未在预期时间内完成"；
 * · 上传（浏览器 → core）与安装（core → 设备）是**两条不同的进度**，
 *   `useApiOperation` 只有一个 `running` 态，结构上装不下这条区分。
 * 硬塞进去的表现是"每次都在预算内超时 + 永远弹一条 warning"，所以另做这一个。
 *
 * ## 与 `useApiOperation` / 两个 update store 刻意保持一致的几条
 * · 状态比页面活得久（模块作用域，不是组件 ref）；
 * · 用 `getApiClient()`（**不带**取消），切页不会掐掉请求，也不会把"取消"误当失败；
 * · 终态写进 `updateResult.ts` 的 `lastResult`（卡片上那行**常驻**结论，并落 sessionStorage），
 *   toast 只是"这一刻弹一下"的即时反馈 —— 分工与 `stores/update.ts` 逐字相同；
 * · 轮询**只在有任务时**打网络（`load()` 之外不发空请求），单飞（上一轮没回来就跳过本轮），
 *   并有一个总时长上限。
 *
 * ## 否决过的替代方案
 * · **每个描述符自己起定时器**：句柄会随描述符散落，没有统一上限、没有统一收摊点，
 *   两个资源同时跑的时候还会各写各的 toast。
 * · **把状态提成 Pinia store**：唯一的消费者就是资源页，没有任何跨模块读取者
 *   （对照 `stores/update.ts`：它进 store 是因为**服务端**持有作业状态，`resume()` 是把
 *   服务端的真相捞回来）。这里提到 store 只是换一个**同样活不过刷新**的容器，
 *   还要在全局 store 里放一堆与其它模块无关的 UI 状态。水平定在"模块级单例"。
 */
import { reactive } from 'vue';
import { toApiErrorMessage } from '@/composables/utils';
import { uploadSizeError } from '@/composables/uploadLimit';
import {
  readLastResult,
  resultKeyFor,
  saveLastResult,
  type UpdateLastResult,
} from '@/views/settings/components/updateResult';
import type { ResourceDescriptor, ResourceStatus } from './resourceRegistry';

/** 一次性提示：产生后由资源页消费并立刻清掉（避免重挂载时把旧提示再弹一遍）。 */
export interface ResourceNotice {
  id: number;
  type: 'success' | 'error' | 'warning' | 'info';
  text: string;
}

/** 一个资源的运行时状态。模块作用域持有，见文件头。 */
export interface ResourceTaskState {
  /** 最近一次读到的服务端状态（列表与轮询共用同一个字段） */
  status: ResourceStatus | null;
  /** 正在读状态（首屏骨架 / 「检查更新」转圈用；轮询期间不置位，否则卡片一直闪） */
  loading: boolean;
  /**
   * 最近一次 `load()` 没读懂（请求失败或响应不是状态对象）。
   * 与「还没有状态」不同：卡片据此显示「读取失败 + 重试」，而不是把失败渲染成
   * 「未安装 / 版本 --」—— 那两行字会被读成"设备上确实没有这个资源"。
   */
  failed: boolean;
  /** 同步动作在途（在线更新 / 卸载 / 回滚 / 恢复内置的请求还没回） */
  submitting: boolean;
  /** 浏览器 → core 的上传在途 */
  uploading: boolean;
  uploadPercent: number;
  uploadingName: string;
  /** 正在盯服务端状态（轮询中）。卡片据此禁用按钮、显示"查看进度" */
  watching: boolean;
  notice: ResourceNotice | null;
  /** 「上次结果」那一行（常驻，扛得住刷新；初值从 sessionStorage 恢复） */
  lastResult: UpdateLastResult | null;
  /** 最近一次动作失败的原文（弹窗的失败态用它，不需要认识具体端点） */
  failReason: string;
}

/**
 * "任务还在走"的状态词表。
 *
 * ★ 这是**core 两个状态机的公共词汇**，不是按资源分的分支：
 * `WebUpdateManager.State` 与 `ComponentManager.State` 取的是同一套名字
 * （后者只多一档 `EXTRACTING`），`UPDATE_STATE_META` 这张公共词表的存在本身就是这条约定。
 * `checking` / `uploading` 两档**只存在于前端**（core 没有它们），但它们同样是"忙"。
 * 新增一个资源若引入了新的忙态名，往这里加一个词即可 —— 不需要动卡片或弹窗。
 */
const BUSY_STATES = ['checking', 'downloading', 'verifying', 'extracting', 'installing', 'uploading'];

/** 服务端状态是否表示"任务还在走"。卡片用它决定进度条画不画。 */
function isBusyState(state: string): boolean {
  return BUSY_STATES.includes(state);
}

/** 轮询间隔（沿用两条更新链路改造前的 1000ms） */
const POLL_MS = 1000;
/**
 * 盯守上限。三类资源里最慢的是 36MB 的 cloudflared（设备 Wi-Fi 下十几秒到一两分钟），
 * 10 分钟足够；超过说明设备侧卡住了或页面已经连不上它，停表并给结论，别让定时器永久挂着。
 * 与 `stores/webUpdate.ts` 的 `WATCH_DEADLINE_MS` 同一取值、同一用意。
 */
const WATCH_DEADLINE_MS = 10 * 60 * 1000;
/**
 * 连续多少次读状态失败就放弃。
 *
 * 轮询失败**不能**当成"任务失败"（core 只读内存状态，偶发抖动重试下一轮即可，真正的失败
 * 由 core 自己置 `failed` 表达）。但连续失败到一定次数说明设备已经不可达，继续空转到
 * 10 分钟上限没有意义。`needsHostRestart` 的资源例外：装完宿主会重启，这段时间连不上
 * **属预期**，所以要一直等到上限（见下面 `tick`）。
 */
const MAX_FETCH_FAILURES = 5;

// ── 模块级状态（见文件头"状态比页面活得久"）──

/**
 * id → 运行时状态。
 *
 * 用**普通 Map + 每个值单独 `reactive()`**，而不是 `reactive<Record<...>>({})`：
 * 后者在赋值后读回来的是 Vue 现包的代理，而首次创建时若把裸对象直接 `return` 出去，
 * 第一个调用方拿到的就是一份**不会被追踪**的副本（第二个调用方拿到的却是代理）——
 * 两边看着是同一份状态，实际一个响应式一个不响应式，症状是"偶尔不更新"。
 * Map 本身不需要响应式：我们从不遍历它渲染，只按 id 取。
 */
const states = new Map<string, ResourceTaskState>();
/** id → 轮询定时器句柄。刻意不放响应式对象：句柄本身不需要被渲染 */
const timers = new Map<string, ReturnType<typeof setInterval>>();
/** id → 本轮盯守的截止时间戳 */
const deadlines = new Map<string, number>();
/** id → 连续读状态失败次数 */
const failures = new Map<string, number>();
/** 单飞：上一轮 tick 还没回来就跳过本轮 */
const ticking = new Set<string>();
let noticeSeq = 0;

/**
 * 取（必要时建）一个资源的状态。
 *
 * 为什么惰性建而不是在模块加载时把 [COMPONENT_RESOURCES] 全建出来：本文件是通用 runner，
 * 不应该反向依赖注册表。调用方（`ResourcesView` / `UpdatePanel` 的 setup）会**在渲染前**为每条描述符
 * 调一次本函数，所以模板里读到的永远是已经建好的那一份 —— 渲染期间不写响应式对象。
 */
function stateOf(id: string): ResourceTaskState {
  const existing = states.get(id);
  if (existing) return existing;
  const created = reactive<ResourceTaskState>({
    status: null,
    loading: false,
    failed: false,
    submitting: false,
    uploading: false,
    uploadPercent: 0,
    uploadingName: '',
    watching: false,
    notice: null,
    lastResult: readLastResult(resultKeyFor(id)),
    failReason: '',
  });
  states.set(id, created);
  return created;
}

function notify(d: ResourceDescriptor, type: ResourceNotice['type'], text: string): void {
  noticeSeq += 1;
  stateOf(d.id).notice = { id: noticeSeq, type, text };
}

/**
 * 记一条结论：进响应式字段（卡片那一行立刻跟着变）+ 落 sessionStorage（扛刷新）。
 * 与两条更新链路的分工一致：**结论只在这里**，`notice` 只管"这一刻弹一下"。
 */
function record(d: ResourceDescriptor, ok: boolean, version: string, message: string): void {
  stateOf(d.id).lastResult = saveLastResult(resultKeyFor(d.id), ok, version, message);
}

/** 消费一次性提示（资源页 watch 到之后调用） */
function clearNotice(id: string): void {
  stateOf(id).notice = null;
}

/** 清掉失败原文（弹窗按「知道了」时调，下次打开不该还挂着上一次的原因） */
function clearFailReason(id: string): void {
  stateOf(id).failReason = '';
}

// ── 轮询 ──

function startWatch(d: ResourceDescriptor): void {
  if (timers.has(d.id)) return;
  deadlines.set(d.id, Date.now() + WATCH_DEADLINE_MS);
  failures.set(d.id, 0);
  stateOf(d.id).watching = true;
  timers.set(
    d.id,
    setInterval(() => {
      if (ticking.has(d.id)) return;
      ticking.add(d.id);
      void tick(d).finally(() => ticking.delete(d.id));
    }, POLL_MS)
  );
}

function stopWatch(id: string): void {
  const timer = timers.get(id);
  if (timer) {
    clearInterval(timer);
    timers.delete(id);
  }
  deadlines.delete(id);
  failures.delete(id);
  ticking.delete(id);
  stateOf(id).watching = false;
}

async function tick(d: ResourceDescriptor): Promise<void> {
  const state = stateOf(d.id);
  const deadline = deadlines.get(d.id) ?? 0;
  if (deadline > 0 && Date.now() >= deadline) {
    stopWatch(d.id);
    const text = `${d.name} 长时间没有结果，已停止轮询。可点「检查更新」或「刷新」再确认一次状态。`;
    record(d, false, '', text);
    notify(d, 'warning', text);
    return;
  }

  let next: ResourceStatus | null = null;
  try {
    next = await d.fetch();
  } catch {
    next = null;
  }
  if (!next) {
    const count = (failures.get(d.id) ?? 0) + 1;
    failures.set(d.id, count);
    // 会重启宿主的资源：连不上属预期，一直等到总时长上限（见 MAX_FETCH_FAILURES 的说明）
    if (!d.needsHostRestart && count >= MAX_FETCH_FAILURES) {
      stopWatch(d.id);
      const text = `读不到 ${d.name} 的状态（设备可能不可达），已停止轮询。`;
      record(d, false, '', text);
      notify(d, 'warning', text);
    }
    return;
  }
  failures.set(d.id, 0);
  state.status = next;
  if (isBusyState(next.state)) return;
  stopWatch(d.id);
  settle(d, next);
}

/**
 * 落到终态。
 *
 * 三分支与 `stores/webUpdate.ts` 的 `settle()` 逐字对齐（同一批状态名、同一套判定）：
 * · `done`      → 成功结论 + 回读一次（installed / 版本号变了）；
 * · `failed`    → 失败结论，原因用 core 的原话；
 * · `need_push` → **算"没成"**：更新源不可达，这次更新根本没发生（要用户本地上传兜底）；
 * · 其余（`idle` 等）→ core 比完版本认为无事可做，**不是一次更新结果**，不写 `lastResult`。
 */
function settle(d: ResourceDescriptor, status: ResourceStatus): void {
  if (status.state === 'done') {
    const version = status.latestVersion || status.currentVersion;
    const text = status.message || (version ? `${d.name} 已更新到 v${version}` : `${d.name} 已更新`);
    record(d, true, version, text);
    notify(d, 'success', text);
    d.onInstalled?.();
    d.onFinished?.(true);
    void load(d);
    return;
  }
  if (status.state === 'failed') {
    const text = status.message || `${d.name} 更新失败`;
    record(d, false, '', text);
    notify(d, 'error', text);
    d.onFinished?.(false);
    return;
  }
  if (status.state === 'need_push') {
    const text = status.message || '更新源不可用，可改用「本地上传」';
    record(d, false, '', text);
    notify(d, 'warning', text);
    d.onFinished?.(false);
    return;
  }
  notify(d, 'info', status.message || `${d.name} 已是最新版本`);
  void load(d);
}

// ── 对外动作 ──

/**
 * 读一次状态。首屏、手动「刷新」、动作结束后的回读都走它。
 * **顺带 resume**：读到忙态就自动续上轮询 —— 刷新页面 / 从别处发起的更新也能被接住。
 *
 * 失败**不再静默**：`fetch()` 抛异常或返回 null 时置 `state.failed`，卡片据此显示
 * 「读取失败 + 重试」。旧实现在这里 `if (!next) return;`，卡片于是永远停在
 * 「版本 -- / 未安装」—— 把"没读到"显示成了"设备上没有"。
 */
async function load(d: ResourceDescriptor): Promise<void> {
  const state = stateOf(d.id);
  state.loading = true;
  try {
    let next: ResourceStatus | null = null;
    try {
      next = await d.fetch();
    } catch {
      next = null;
    }
    if (!next) {
      state.failed = true;
      return;
    }
    state.failed = false;
    state.status = next;
    if (isBusyState(next.state)) startWatch(d);
  } finally {
    state.loading = false;
  }
}

/** 「检查更新」：重拉版本清单（描述符自己决定打哪个端点），再回读一次状态。 */
async function refreshList(d: ResourceDescriptor): Promise<void> {
  if (!d.refresh) return;
  const state = stateOf(d.id);
  state.loading = true;
  try {
    await d.refresh();
    await load(d);
  } catch (e) {
    notify(d, 'error', toApiErrorMessage(e, `检查 ${d.name} 更新失败`));
  } finally {
    state.loading = false;
  }
}

/**
 * 在线更新（core 自己从更新源拉包并安装）。
 *
 * **提交成功一律起轮询，不由提交响应判定终态**：`POST /api/web/check` 的语义是
 * "异步受理后立刻回当前状态"，而 `scope.launch` 与 HTTP 响应之间存在竞态 ——
 * 响应里的 `state` 完全可能还是上一次的 `idle`。让轮询来判终态就没有这个竞态，
 * 代价只是最坏多等一个 1s 周期。
 */
async function install(d: ResourceDescriptor): Promise<void> {
  if (!d.install) return;
  const state = stateOf(d.id);
  if (state.submitting || state.uploading || state.watching) return;
  state.submitting = true;
  state.failReason = '';
  try {
    const accepted = await d.install();
    if (!accepted) {
      // 受理被拒（未知组件 / 已有任务在跑 / 网络失败）：这是**一次没打成的触发**，
      // 不是"更新失败"—— 所以不写 lastResult（否则卡片会谎报一次不存在的更新失败）。
      state.failReason = `${d.name} 未被设备受理`;
      notify(d, 'error', state.failReason);
      return;
    }
    notify(d, 'info', `已开始更新 ${d.name}，可离开本页，进度会继续`);
    startWatch(d);
  } catch (e) {
    state.failReason = toApiErrorMessage(e, `触发 ${d.name} 更新失败`);
    notify(d, 'error', state.failReason);
  } finally {
    state.submitting = false;
  }
}

/**
 * 本地上传（浏览器 → core）。
 *
 * 三类的上传端点都是**同步**的（core 收完 body 就地安装再回包），所以成功后不需要轮询；
 * 也正因为是同步的，上传进度只能是**浏览器这一侧**的字节数 —— 它与 core 自报的 percent
 * 是两条不同的进度，`uploadPercent` 单独放在状态里（见 `ResourceStatus` 的说明）。
 */
async function upload(d: ResourceDescriptor, file: File): Promise<void> {
  if (!d.upload) return;
  const state = stateOf(d.id);
  if (state.uploading || state.submitting || state.watching) return;

  // 上限按**描述符**读（三类的端点与字段名都不同）。选文件时就挡住超限：
  // 几十 MB 白传一遍才看到 413 是最没必要的等待，详见 composables/uploadLimit.ts。
  const tooBig = uploadSizeError(file, d.uploadLimit?.(state.status) ?? 0);
  if (tooBig) {
    notify(d, 'warning', tooBig);
    return;
  }

  state.uploading = true;
  state.uploadPercent = 0;
  state.uploadingName = file.name;
  state.failReason = '';
  try {
    const ok = await d.upload(file, (pct) => {
      state.uploadPercent = Math.max(0, Math.min(100, Math.round(pct)));
    });
    state.uploadPercent = 100;
    if (!ok) throw new Error(`${d.name} 上传安装失败：服务端没有返回结果`);
    const text = `${d.name} 已安装`;
    record(d, true, '', text);
    notify(d, 'success', text);
    d.onInstalled?.();
    d.onFinished?.(true);
    void load(d);
  } catch (e) {
    const text = toApiErrorMessage(e, `${d.name} 上传安装失败`);
    state.failReason = text;
    // 上传被 core 明确拒掉（包坏了 / 超限 / 校验失败）：这是**一次安装的失败终态**，
    // 结论与即时提示两处都写 —— 与 stores/webUpdate.ts 的 failWith 同一口径。
    record(d, false, '', text);
    notify(d, 'error', text);
    d.onFinished?.(false);
  } finally {
    state.uploading = false;
    state.uploadPercent = 0;
    state.uploadingName = '';
  }
}

/** 卸载。同步端点，失败（如 409 还有实例在跑）只弹提示 —— 它不是一次"更新"的结论。 */
async function uninstall(d: ResourceDescriptor): Promise<void> {
  if (!d.uninstall) return;
  const state = stateOf(d.id);
  if (state.submitting || state.uploading || state.watching) return;
  state.submitting = true;
  try {
    const ok = await d.uninstall();
    if (ok) {
      notify(d, 'success', `${d.name} 已卸载`);
      d.onInstalled?.();
    } else {
      notify(d, 'error', `${d.name} 卸载失败`);
    }
  } catch (e) {
    notify(d, 'error', toApiErrorMessage(e, `${d.name} 卸载失败`));
  } finally {
    state.submitting = false;
    void load(d);
  }
}

/** 回滚 / 恢复内置：web 专有的两个同步动作，成功同样要走 `onFinished(true)`（它会刷新页面）。 */
async function simpleAction(d: ResourceDescriptor, action: 'rollback' | 'clear'): Promise<void> {
  const fn = action === 'rollback' ? d.rollback : d.clear;
  if (!fn) return;
  const state = stateOf(d.id);
  if (state.submitting) return;
  const verb = action === 'rollback' ? '回滚' : '恢复内置版本';
  state.submitting = true;
  state.failReason = '';
  try {
    await fn();
    notify(d, 'success', `${d.name} 已${verb}`);
    d.onFinished?.(true);
    void load(d);
  } catch (e) {
    state.failReason = toApiErrorMessage(e, `${d.name} ${verb}失败`);
    notify(d, 'error', state.failReason);
  } finally {
    state.submitting = false;
  }
}

function rollback(d: ResourceDescriptor): Promise<void> {
  return simpleAction(d, 'rollback');
}

function clear(d: ResourceDescriptor): Promise<void> {
  return simpleAction(d, 'clear');
}

/**
 * 资源页与卡片拿到的唯一入口。
 *
 * 形状照 `views/files/useBatchOperation.ts`：**函数只是取用口，状态在模块作用域**。
 * 所以 `ResourcesView` 卸载再挂载、卡片被 v-for 重建，读到的都是同一份进度 ——
 * 这正是"轮询比页面活得久"的落点（本文件没有 `onScopeDispose`：资源任务不像
 * `useBatchOperation` 那样需要页面注入回调，所以没有任何东西要解绑）。
 */
export function useResourceTask() {
  return {
    stateOf,
    isBusyState,
    load,
    refreshList,
    install,
    upload,
    uninstall,
    rollback,
    clear,
    clearNotice,
    clearFailReason,
  };
}

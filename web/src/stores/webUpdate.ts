import { defineStore } from 'pinia';
import { computed, ref } from 'vue';
import { getApiClient } from '@/composables/useApi';
import { readByteLimit } from '@/composables/uploadLimit';
import { Endpoints } from '@/api/contract';
import {
  UPDATE_RESULT_KEYS,
  readLastResult,
  saveLastResult,
  type UpdateLastResult,
} from '@/views/settings/components/updateResult';

/**
 * 前端资源（web 资源）更新的全局状态（2026-10-14）。
 *
 * ## 用途
 * `/api/web/*` 这一套的状态、轮询与上传进度：core 自己从更新源拉 ZIP 覆盖（自动通道），
 * 以及管理员手选 ZIP 上传覆盖（手动通道）。UI 侧是设置页「前端资源」卡 +
 * `views/settings/components/WebUpdateModal.vue`。
 *
 * ## 为什么必须提到 store（与 `stores/update.ts` 的三条理由同源）
 * ① 状态原来只活在 `UpdatePanel.vue` 的组件局部 `reactive` 里，**刷新页面 / 关掉设置弹窗
 *    再回来**组件重建即归零 —— 而 core 侧的下载/校验/安装其实还在跑；
 * ② 轮询原来用 `useInterval`（组件级，`onUnmounted` 主动 `stop()`）+ `useCancellableApi`
 *    （卸载 abort 在途请求），离开设置页就没人盯着这次更新，且**没有任何入口恢复现场**；
 * ③ ZIP 上传进度原来也是组件局部 ref，关掉弹窗就丢，用户看不到"还在传"。
 *
 * 所以这里与 `stores/update.ts` 同一套骨架：`getApiClient()`（**不带**取消）+ 自己的
 * `setInterval`，生命周期跟着 store 走，不跟着任何组件走。
 *
 * ## 为什么不能并进 `stores/update.ts`（core APK 那套）
 * · 失败信封不同：`/api/web/*` 只回 `{ error }`，`/api/update/*` 回真实 HTTP 码 +
 *   `{success,ok,error,message,code}`；
 * · 安装后果不同：core APK 安装会杀掉 core 进程，轮询必须容忍"连不上属预期"并跨重启等
 *   `/health`；web 资源是 ZIP 原子覆盖、**不重启**，没有这一段；
 * · 时序不同：`POST /api/web/update` 是**同步**端点（core 收完 body 就地解压安装再回包），
 *   所以上传成功即安装完成，没有第二条"安装"请求 —— 与 APK 的
 *   `upload` → `install-local` 两段式完全不同。合并只会得到一个到处 `if (isWeb)` 的 store。
 *
 * ## 否决过的替代方案
 * · 只给 `UpdatePanel.vue` 补一个 `resume()`、状态仍留组件：设置页是弹窗，关掉即卸载面板，
 *   状态与轮询照样丢 —— 那正是本次要修的 P0；
 * · 让 `WebUpdateModal.vue` 自己持有状态：同上，"关掉弹窗 = 更新消失"。
 */

/**
 * `/api/web/status` 的 `state`。
 *
 * core 的 `WebUpdateManager.State` 只有
 * `IDLE / DOWNLOADING / VERIFYING / INSTALLING / DONE / FAILED / NEED_PUSH` 七档
 * （见 `core/api/.../update/WebUpdateManager.kt` 的枚举），**没有** `checking` 与
 * `uploading`：前者是前端发起 `POST /api/web/check` 那一下的本地态，后者是
 * 「浏览器正在把 ZIP 推给 core」的本地态（core 的手动通道根本不进状态机）。
 * 两档都保留在类型里，是因为卡片的标签表要按同一个键取值 —— 认不出的键会退化成裸 state 串。
 */
export type WebUpdateState =
  | 'idle'
  | 'checking'
  | 'downloading'
  | 'verifying'
  | 'installing'
  | 'uploading'
  | 'done'
  | 'failed'
  | 'need_push';

/** 「这次更新还在走」的 core 侧状态。`resume()` 据此决定要不要重新起轮询。 */
export const WEB_BUSY_STATES: WebUpdateState[] = ['checking', 'downloading', 'verifying', 'installing', 'uploading'];

/** 终态：轮询到此为止。`idle` 也算（core 比对后认为已是最新）。 */
const WEB_TERMINAL_STATES: WebUpdateState[] = ['idle', 'done', 'failed', 'need_push'];

const KNOWN_STATES: WebUpdateState[] = [...WEB_BUSY_STATES, ...WEB_TERMINAL_STATES];

/** 轮询 `/api/web/status` 的间隔（沿用改造前的 1000ms） */
const STATUS_POLL_MS = 1000;
/**
 * 盯守上限。
 *
 * web 更新是「下载 ZIP + 原子覆盖」，正常几十秒内结束。超过这个时间还停在忙态，说明设备侧
 * 卡住了（或页面已经连不上它）—— 停表并提示，别让一个 1s 的定时器永久挂着。
 * 与 `stores/update.ts` 的 deadline 同一用意，只是 web 侧只有一段（不重启 core）。
 * 用户想再确认一次：点卡片上的「检查更新」（core 对重复触发是静默忽略并回当前状态，
 * 所以那颗按钮天然就是"重新检查状态"）。
 */
const WATCH_DEADLINE_MS = 10 * 60 * 1000;
/** 单次状态请求超时：core 只读内存状态，慢到 5s 就是有问题，快速失败进入下一轮 */
const STATUS_TIMEOUT_MS = 5000;
/**
 * 手动上传成功后刷新页面的延迟。
 *
 * 与改造前逐字一致（原来 `handleFileSelect` 里是 `message.success` 后
 * `setTimeout(reload, 1200)`）：core 侧 web 资源已覆盖，但**当前页面跑的还是旧 bundle**，
 * 而 `/assets/*` 带 immutable 长缓存，不刷新用户会以为"点了没反应"。
 */
const RELOAD_DELAY_MS = 1200;

/**
 * 一次性提示：store 里产生，由面板消费后调 `clearNotice()`（避免重挂载重复弹）。
 *
 * 2026-10-14 **降级**：它只承载"这一刻弹一下"的即时反馈（用户在页面上时的 toast），
 * **不再是结论的载体** —— 结论在 [UpdateLastResult]，由「前端资源」卡上那一行常驻渲染。
 * 分工的理由与 core APK 那条链路逐字相同（见 `stores/update.ts` 的 `UpdateNotice`）：
 * notice 易失（面板一卸载就没人消费、`clearNotice()` 之后即消失、刷新整页随 store 归零），
 * 而结论必须活过弹窗开合与整页刷新。每个终态两处都写，谁都不许只写一处。
 */
export interface WebUpdateNotice {
  id: number;
  type: 'success' | 'error' | 'warning' | 'info';
  text: string;
}

/** `/api/web/*` 的失败信封只有 `{ error }`（部分端点另带 `message`），两个键都要认。 */
function webErr(e: unknown, fallback: string): string {
  const d = (e as { response?: { data?: { error?: string; message?: string } } })?.response?.data;
  if (d?.error) return d.error;
  if (d?.message) return d.message;
  if (e instanceof Error && e.message) return e.message;
  return fallback;
}

function normalizeState(raw: unknown): WebUpdateState {
  const s = String(raw ?? '').toLowerCase();
  return (KNOWN_STATES as string[]).includes(s) ? (s as WebUpdateState) : 'idle';
}

export const useWebUpdateStore = defineStore('webUpdate', () => {
  /** core 侧 `GET /api/web/status` 的镜像 */
  const state = ref<WebUpdateState>('idle');
  const progress = ref(0);
  const message = ref('');
  const currentVersion = ref('');
  const latestVersion = ref('');
  /**
   * ZIP 上传上限（`/api/web/status` 的 `max_upload_bytes`，core 侧 50MB）。
   * 0 = 还没读到（老 core 不回这个字段），此时预检放行、交给服务端拦
   * （判定见 `composables/uploadLimit.ts`）。
   */
  const maxUploadBytes = ref(0);
  /** `POST /api/web/check` 在途（按钮转圈用；core 没有 checking 这一档状态） */
  const checking = ref(false);
  /** ZIP 上传在途（浏览器 → core） */
  const uploading = ref(false);
  const uploadPercent = ref(0);
  const uploadBytes = ref(0);
  const uploadTotalBytes = ref(0);
  const notice = ref<WebUpdateNotice | null>(null);
  /**
   * 「上次更新结果」—— 结论的**唯一**落点（2026-10-14 修"关掉设置弹窗 / 刷新整页就再也
   * 看不到更新结论"）。与 `stores/update.ts` 的 `lastResult` 同一形状、同一手法，
   * 只是键与"载体名词"不同（ZIP）。成功 / 失败 / 超时三个终态都写，并落 sessionStorage。
   *
   * 为什么不能只靠 notice / core 的 `state`：设置页是弹窗（关掉即卸载面板），那一刻更新完成
   * 就没人消费 notice 了；而 `state` 会被下一次 check 覆盖、刷新后还要重新问一次 core。
   * 详见 `views/settings/components/updateResult.ts` 的文件头。
   *
   * 初值从 sessionStorage 恢复：刷新后重进设置页，卡片上那一行直接就有内容。
   */
  const lastResult = ref<UpdateLastResult | null>(readLastResult(UPDATE_RESULT_KEYS.web));
  /**
   * 「本次更新会话」：用户从卡片或弹窗发起的这次更新，还没被「完成 / 知道了」结束。
   *
   * 为什么需要它：`/api/web/status` 是**历史结论的载体** —— 上一次检查成功后它会一直停在
   * `done`。弹窗只按 core 状态决定显示什么的话，用户下次点「本地更新」看到的是上一次的结论、
   * 连选文件的机会都没有。会话标记让"这次的结论"与"上次的结论"分得开。
   */
  const session = ref(false);

  // 定时器句柄不进 return（仿 stores/update.ts：非响应式内部状态用闭包变量）
  let timer: ReturnType<typeof setInterval> | null = null;
  let ticking = false;
  let noticeSeq = 0;
  /** 本轮盯守的截止时间戳（0 = 没在盯） */
  let deadlineAt = 0;

  /** 是否在盯守。定时器句柄本身不是响应式的，而 UI 需要判断"这个忙态还有人在跟" */
  const watching = ref(false);

  /** 是否在盯这次更新（含 ZIP 上传这一段本地链路） */
  const busy = computed(() => checking.value || uploading.value || WEB_BUSY_STATES.includes(state.value));

  /**
   * 给 UI 用的状态键。
   *
   * 为什么需要它：`uploading` 只存在于本地（core 的手动通道不进状态机），
   * 不合成的话卡片会在上传期间显示上一次的 `idle`，看着像"没在传"。
   */
  const displayState = computed<WebUpdateState>(() => (uploading.value ? 'uploading' : state.value));

  /** 给 UI 用的百分比：上传期间是**浏览器 → core** 的本地进度，其余时候是 core 侧进度 */
  const displayProgress = computed(() => (uploading.value ? uploadPercent.value : progress.value));

  /** 卡片上那块状态区要不要出现（空闲且没在传时不显示） */
  const showStatus = computed(() => busy.value || state.value !== 'idle');

  function pushNotice(type: WebUpdateNotice['type'], text: string) {
    noticeSeq += 1;
    notice.value = { id: noticeSeq, type, text };
  }

  function clearNotice() {
    notice.value = null;
  }

  /**
   * 记一条结论：进响应式字段（卡片那一行立刻跟着变）+ 落 sessionStorage（扛刷新）。
   * 版本号在 `saveLastResult` 里统一剥 `v` 前缀（core 回的可能是 `v0.0.6`，而卡片文案会自己补 `v`）。
   */
  function recordResult(ok: boolean, version: string, message: string) {
    lastResult.value = saveLastResult(UPDATE_RESULT_KEYS.web, ok, version, message);
  }

  /**
   * 合并 core 状态。
   *
   * 空响应体（`undefined`）**保持原状态不动** —— 与 `stores/update.ts` 的 `applyStatus`
   * 同一口径：`d?.state || 'idle'` 那种写法会在请求被取消/无 body 时把忙态静默打回
   * `idle`，界面看起来"更新凭空结束了"。
   * `max_upload_bytes` 同理只在服务端给出正数时才覆盖（老 core 不回该字段）。
   */
  function applyStatus(d: unknown): boolean {
    if (!d || typeof d !== 'object') return false;
    const raw = d as Record<string, unknown>;
    if (raw.state === undefined) return false;
    state.value = normalizeState(raw.state);
    progress.value = typeof raw.progress === 'number' ? raw.progress : 0;
    message.value = typeof raw.message === 'string' ? raw.message : '';
    currentVersion.value = typeof raw.current_version === 'string' ? raw.current_version : '';
    latestVersion.value = typeof raw.latest_version === 'string' ? raw.latest_version : '';
    const limit = readByteLimit(raw, 'max_upload_bytes');
    if (limit > 0) maxUploadBytes.value = limit;
    return true;
  }

  // ── 轮询骨架（与 stores/update.ts 同一套单飞写法）──

  function stopTimer() {
    if (timer) {
      clearInterval(timer);
      timer = null;
    }
    ticking = false;
    deadlineAt = 0;
    watching.value = false;
  }

  async function pollTick() {
    if (deadlineAt && Date.now() >= deadlineAt) {
      stopTimer();
      // 超时也是"结论"：写进 lastResult，否则用户错过这条 toast 就再也看不到这次更新怎么了
      const text = '前端资源更新长时间没有结果，已停止轮询。可点「检查更新」再确认一次状态。';
      recordResult(false, '', text);
      pushNotice('warning', text);
      return;
    }
    try {
      const { data } = await getApiClient().get(Endpoints.webAssets.status, { timeout: STATUS_TIMEOUT_MS });
      if (applyStatus(data) && WEB_TERMINAL_STATES.includes(state.value)) settle();
    } catch {
      // 轮询失败不能当成"更新失败"：core 只读内存状态，偶发抖动重试下一轮即可。
      // 真正的失败由 core 自己置 state=failed 表达（改造前这里直接 stop + 弹错，
      // 一次网络抖动就会让进度永远停在原地）。
    }
  }

  /** 单飞轮询：上一轮没跑完就跳过本轮 */
  function startTimer() {
    stopTimer();
    deadlineAt = Date.now() + WATCH_DEADLINE_MS;
    watching.value = true;
    timer = setInterval(() => {
      if (ticking) return;
      ticking = true;
      void pollTick().finally(() => {
        ticking = false;
      });
    }, STATUS_POLL_MS);
  }

  /** 停止盯守（不改 core 侧任何东西，仅收摊本地定时器） */
  function stopWatch() {
    stopTimer();
  }

  /** 落到终态：停表 + 把结论写进 lastResult（常驻）+ 交给面板做即时 toast */
  function settle() {
    stopTimer();
    const s = state.value;
    const v = latestVersion.value;
    if (s === 'done') {
      const text = v ? `前端资源已更新到 v${v}` : '前端资源已更新';
      recordResult(true, v, text);
      pushNotice('success', text);
    } else if (s === 'failed') {
      const text = message.value || '前端资源更新失败';
      recordResult(false, '', text);
      pushNotice('error', text);
    } else if (s === 'need_push') {
      // need_push 算"没成"：更新源不可达，这次自动更新根本没发生（要用户手动传 ZIP）
      const text = message.value || '更新源不可用，可手动上传 ZIP 安装包';
      recordResult(false, '', text);
      pushNotice('warning', text);
    } else {
      // idle = core 比完版本认为无事可做（已是最新）：**不是一次更新结果**，不写 lastResult
      pushNotice('info', message.value || '前端资源已是最新版本');
    }
  }

  /**
   * 手动刷新一次状态（面板「刷新」按钮 / `resume()` 的第一步）。
   * 该端点无副作用，顺带把 `max_upload_bytes` 读进来。
   */
  async function refreshStatus(): Promise<boolean> {
    try {
      const { data } = await getApiClient().get(Endpoints.webAssets.status, { timeout: STATUS_TIMEOUT_MS });
      return applyStatus(data);
    } catch {
      return false;
    }
  }

  /**
   * 「检查更新」= 让 core 自己去更新源拉包并安装（自动通道）。
   * `POST /api/web/check` 立即返回当前状态，进度靠轮询。
   */
  async function check(): Promise<void> {
    if (checking.value) return;
    checking.value = true;
    session.value = true;
    // 乐观置 checking：POST 在途时卡片要显示「检查中」，否则按钮转圈但状态行还是上一次的结论
    const prevState = state.value;
    const prevMessage = message.value;
    state.value = 'checking';
    message.value = '正在检查更新源…';
    try {
      const { data } = await getApiClient().post(Endpoints.webAssets.check, undefined, { timeout: 0 });
      if (!data) return;
      applyStatus(data);
      if (WEB_TERMINAL_STATES.includes(state.value)) settle();
      else startTimer();
    } catch (e) {
      // 失败**回到检查前的状态**，不是置 failed：这是"检查"没打成（网络/更新源问题），
      // 与"更新失败"是两件事，置 failed 会让卡片谎报一次不存在的更新失败。
      // 真正的失败由 core 自己置 state=failed 表达（它比客户端更清楚装没装上）。
      // 同理**不写 lastResult**：这是一次查询失败，不是一次更新的结论。
      state.value = prevState;
      message.value = prevMessage;
      pushNotice('error', webErr(e, '检查前端资源更新失败'));
    } finally {
      checking.value = false;
    }
  }

  /**
   * 手动上传 ZIP（手动通道）。**同步端点**：core 收完 body 就地解压安装再回包，
   * 所以 2xx + `success` 就是"已装好"，没有第二步。
   *
   * 为什么用 `getApiClient()` 而不是 `useCancellableApi()`：上传必须活过弹窗与设置页
   * （「后台运行」/ 关掉设置页都不能把上传掐掉）。代价是拿不到"取消"语义 ——
   * 因此这里的 `data === undefined` 只可能是服务端回了空 body，一律按失败处理，
   * 不会出现"把取消当失败弹错"那条坑。
   *
   * @param onProgress 可选的本地进度回调（当前 UI 直接读 store 的 `uploadPercent`，
   *   留这个口子是为了将来别的入口复用上传能力时不改签名）
   * @returns 是否上传并安装成功
   */
  async function upload(file: File, onProgress?: (percent: number) => void): Promise<boolean> {
    if (uploading.value) return false;
    session.value = true;
    uploading.value = true;
    uploadPercent.value = 0;
    uploadBytes.value = 0;
    uploadTotalBytes.value = file.size;
    try {
      const formData = new FormData();
      // 字段名必须是 file：core 只认这一个 multipart 字段名
      formData.append('file', file);
      const { data } = await getApiClient().post(Endpoints.webAssets.update, formData, {
        headers: { 'Content-Type': 'multipart/form-data' },
        // 默认 15s 超时对 50MB 的 ZIP 太短：设备 Wi-Fi 下传到一半就被客户端掐断，
        // 而 core 其实还在收（改造前没有这一行，正是这个坑）
        timeout: 0,
        onUploadProgress: (ev: { loaded: number; total?: number }) => {
          // e.total 在个别浏览器/代理下缺失，回落到 file.size（与 APK 上传同一处理）
          const total = ev.total || file.size;
          if (!total) return;
          const loaded = Math.min(ev.loaded, total);
          uploadBytes.value = loaded;
          uploadTotalBytes.value = total;
          uploadPercent.value = Math.floor((loaded / total) * 100);
          onProgress?.(uploadPercent.value);
        },
      });
      if (!data) {
        failWith('上传失败：服务端没有返回结果');
        return false;
      }
      if (data.success) {
        state.value = 'done';
        progress.value = 100;
        const v = String(data.version ?? '');
        message.value = v ? `前端资源已更新到 v${v}` : '前端资源已更新';
        // 手动通道**不走 settle()**（它不经过 core 的状态机），所以结论要在这里补记：
        // 上传成功即安装完成（同步端点），这就是终态
        recordResult(true, v, message.value);
        pushNotice('success', '前端已更新，即将刷新页面…');
        scheduleReload();
        return true;
      }
      failWith(String(data.error || '更新失败'));
      return false;
    } catch (e) {
      failWith(webErr(e, '上传失败'));
      return false;
    } finally {
      uploading.value = false;
      uploadPercent.value = 0;
    }
  }

  function failWith(text: string) {
    state.value = 'failed';
    message.value = text;
    // 上传/安装被 core 明确拒掉：这是一次更新的失败终态，两处都写（结论 + 即时 toast）
    recordResult(false, '', text);
    pushNotice('error', text);
  }

  /**
   * 结束本次更新会话（弹窗按「完成 / 知道了」时调）。
   *
   * **不动 core 状态**：卡片上的「状态」行要继续如实显示上一次的结论
   * （`done` / `failed` 都是事实），这里只是让弹窗回到"可以重新选文件"的状态。
   */
  function endSession() {
    session.value = false;
  }

  /**
   * 刷新页面让新 bundle 生效。
   *
   * 为什么放在 store 而不是弹窗里：上传会活过弹窗（「后台运行」关掉弹窗、或用户离开设置页），
   * 而"当前页面跑的是旧 bundle"这件事与弹窗无关 —— 刷新不能因为弹窗没了就不做。
   * 时机与改造前一致（成功后 1200ms），留出时间让用户看见"更新完成"。
   */
  function scheduleReload() {
    window.setTimeout(() => window.location.reload(), RELOAD_DELAY_MS);
  }

  /**
   * 恢复现场：拉一次 `/api/web/status`，**core 若仍在进行中就重新起轮询**。
   *
   * 这就是「刷新页面 / 关掉设置弹窗再回来，进行中的 web 更新仍然看得见」的关键
   * （改造前完全没有：状态只在组件里，轮询还挂在组件生命周期上）。
   * 已经在盯守（`timer` 非空）或本地正在传 ZIP 时原地不动，别重复起表。
   */
  async function resume(): Promise<void> {
    if (timer || uploading.value) return;
    const ok = await refreshStatus();
    if (!ok) return;
    if (WEB_BUSY_STATES.includes(state.value)) startTimer();
    // 终态（done/failed/need_push）**不**自动起轮询：那是上次更新的结论，
    // 页面每次打开都重放一遍只会重复弹提示。要看结论读 `lastResult` 就够了
    // （它的初值从 sessionStorage 恢复）—— 这里省掉的只是"重新问一遍"，
    // 不是"让用户看不到结果"。
  }

  return {
    state,
    displayState,
    displayProgress,
    progress,
    message,
    currentVersion,
    latestVersion,
    maxUploadBytes,
    checking,
    uploading,
    uploadPercent,
    uploadBytes,
    uploadTotalBytes,
    notice,
    lastResult,
    session,
    watching,
    busy,
    showStatus,
    applyStatus,
    refreshStatus,
    check,
    upload,
    resume,
    endSession,
    stopWatch,
    clearNotice,
    recordResult,
  };
});

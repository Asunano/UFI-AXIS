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
 * 前端资源（web 资源）更新的全局状态。
 *
 * ## 用途
 * 覆盖 `/api/web/*` 的状态、轮询与上传进度，包含两条通道：
 * core 从更新源拉取 ZIP 覆盖（自动通道），以及管理员手选 ZIP 上传覆盖（手动通道）。
 * UI 侧为设置页「前端资源」卡与 `views/settings/components/WebUpdateModal.vue`。
 *
 * ## 状态放在 store 中的原因
 * · 组件局部状态在组件卸载后归零，而 core 侧的下载、校验与安装仍在进行；
 * · 轮询与请求取消原绑定于组件生命周期，离开设置页后该次更新即无观察者，
 *   且没有入口恢复现场；
 * · ZIP 上传进度原为组件局部状态，关闭弹窗即丢失，用户无法看到传输仍在进行。
 *
 * 因此本 store 与 `stores/update.ts` 使用同一套骨架：`getApiClient()`（不绑定取消）
 * 与自持的 `setInterval`，生命周期跟随 store，不跟随任何组件。
 *
 * ## 不能并入 `stores/update.ts` 的原因
 * core APK 更新与 web 资源更新的端点语义不同：
 * · 失败信封：`/api/web/*` 只返回 `{ error }`（部分端点另带 `message`），
 *   `/api/update/*` 返回真实 HTTP 状态码与 `{success,ok,error,message,code}`；
 * · 安装后果：core APK 安装会终止 core 进程，轮询必须容忍连接失败并跨重启等待
 *   `/health`；web 资源是 ZIP 原子覆盖，不重启进程，无此阶段；
 * · 时序：`POST /api/web/update` 是同步端点，core 收完 body 就地解压安装再回包，
 *   上传成功即安装完成，没有第二个安装请求；APK 更新为
 *   `upload` → `install-local` 两段式。合并后只能得到内部按资源类型分支的 store。
 *
 * ## 未采用的替代方案
 * · 仅在 `UpdatePanel.vue` 内补充 `resume()`、状态仍留在组件：设置页为弹窗，
 *   关闭即卸载面板，状态与轮询同样丢失；
 * · 由 `WebUpdateModal.vue` 自行持有状态：同样存在关闭弹窗即丢失更新状态的问题。
 */

/**
 * `/api/web/status` 的 `state`。
 *
 * core 的 `WebUpdateManager.State` 仅含
 * `IDLE / DOWNLOADING / VERIFYING / INSTALLING / DONE / FAILED / NEED_PUSH` 七种取值
 * （见 `core/api/.../update/WebUpdateManager.kt` 的枚举），不包含 `checking` 与
 * `uploading`：前者是前端发起 `POST /api/web/check` 时的本地状态，后者是
 * 浏览器向 core 推送 ZIP 时的本地状态（core 的手动通道不进入状态机）。
 * 两种本地状态保留在类型中，因为卡片的标签表按同一键取值，未知键会退化为原始 state 字符串。
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

/** 表示更新仍在进行的 core 侧状态；`resume()` 据此决定是否重新启动轮询。 */
export const WEB_BUSY_STATES: WebUpdateState[] = ['checking', 'downloading', 'verifying', 'installing', 'uploading'];

/** 终态：轮询就此停止。`idle` 同样属于终态（core 比对后认为已是最新）。 */
const WEB_TERMINAL_STATES: WebUpdateState[] = ['idle', 'done', 'failed', 'need_push'];

const KNOWN_STATES: WebUpdateState[] = [...WEB_BUSY_STATES, ...WEB_TERMINAL_STATES];

/** 轮询 `/api/web/status` 的间隔（1000ms）。 */
const STATUS_POLL_MS = 1000;
/**
 * 盯守上限。
 *
 * web 更新是「下载 ZIP + 原子覆盖」，正常在数十秒内结束。超过该时长仍处于忙态，
 * 说明设备侧阻塞或页面已无法连接设备：此时停止计时并提示，避免 1s 定时器无限运行。
 * 与 `stores/update.ts` 的 deadline 用意相同，但 web 侧只有一段（不重启 core）。
 * 需要重新确认状态时可点击卡片上的「检查更新」：core 对重复触发静默忽略并返回当前状态，
 * 因此该按钮同时承担重新检查状态的作用。
 */
const WATCH_DEADLINE_MS = 10 * 60 * 1000;
/** 单次状态请求超时：core 只读取内存状态，耗时超过 5s 即异常，应快速失败进入下一轮。 */
const STATUS_TIMEOUT_MS = 5000;
/**
 * 手动上传成功后刷新页面的延迟（1200ms）。
 *
 * core 侧 web 资源已覆盖，但当前页面运行的仍是旧 bundle，且 `/assets/*` 使用
 * immutable 长缓存，不刷新会使操作看起来没有生效。
 */
const RELOAD_DELAY_MS = 1200;

/**
 * 一次性提示：由 store 产生，面板消费后调用 `clearNotice()`（避免重新挂载时重复弹出）。
 *
 * 该字段只承载即时反馈（用户停留在页面时的 toast），不是结论的载体：
 * 结论存放在 [UpdateLastResult]，由「前端资源」卡上的常驻行渲染。
 * 与 core APK 链路的分工相同（见 `stores/update.ts` 的 `UpdateNotice`）：
 * notice 易失（面板卸载后无人消费、`clearNotice()` 后消失、整页刷新后随 store 归零），
 * 而结论必须跨弹窗开合与整页刷新存活。每个终态必须同时写入两处。
 */
export interface WebUpdateNotice {
  id: number;
  type: 'success' | 'error' | 'warning' | 'info';
  text: string;
}

/** `/api/web/*` 的失败信封只有 `{ error }`（部分端点另带 `message`），两个键都需要读取。 */
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
  /** core 侧 `GET /api/web/status` 的镜像。 */
  const state = ref<WebUpdateState>('idle');
  const progress = ref(0);
  const message = ref('');
  const currentVersion = ref('');
  const latestVersion = ref('');
  /**
   * ZIP 上传上限（`/api/web/status` 的 `max_upload_bytes`，core 侧 50MB）。
   * 0 表示尚未读到（老 core 不返回该字段），此时预检放行，由服务端拦截
   * （判定见 `composables/uploadLimit.ts`）。
   */
  const maxUploadBytes = ref(0);
  /** `POST /api/web/check` 在途（用于按钮加载态；core 没有 `checking` 这一档状态）。 */
  const checking = ref(false);
  /** ZIP 上传在途（浏览器 → core）。 */
  const uploading = ref(false);
  const uploadPercent = ref(0);
  const uploadBytes = ref(0);
  const uploadTotalBytes = ref(0);
  const notice = ref<WebUpdateNotice | null>(null);
  /**
   * 「上次更新结果」：结论的唯一存放位置，用于在设置弹窗关闭或整页刷新后仍能看到更新结论。
   * 与 `stores/update.ts` 的 `lastResult` 同一形状、同一手法，仅键与载体名词不同（ZIP）。
   * 成功、失败、超时三个终态都写入，并持久化到 sessionStorage。
   *
   * 不能只依赖 notice 或 core 的 `state`：设置页是弹窗，关闭即卸载面板，
   * 此时更新完成无人消费 notice；而 `state` 会被下一次 check 覆盖，刷新后还需重新查询 core。
   * 详见 `views/settings/components/updateResult.ts` 的文件头。
   *
   * 初值从 sessionStorage 恢复，刷新后重进设置页即可在卡片上看到结论。
   */
  const lastResult = ref<UpdateLastResult | null>(readLastResult(UPDATE_RESULT_KEYS.web));
  /**
   * 「本次更新会话」：用户从卡片或弹窗发起、尚未被「完成 / 知道了」结束的一次更新。
   *
   * `/api/web/status` 承载的是历史结论：上一次检查成功后它会一直停留在 `done`。
   * 弹窗若只按 core 状态决定显示内容，下次点击「本地更新」看到的会是上一次的结论，
   * 没有选择文件的机会。会话标记用于区分本次结论与上次结论。
   */
  const session = ref(false);

  // 定时器句柄不暴露到返回值：非响应式内部状态使用闭包变量（与 stores/update.ts 一致）。
  let timer: ReturnType<typeof setInterval> | null = null;
  let ticking = false;
  let noticeSeq = 0;
  /** 当前盯守的截止时间戳（0 = 未在盯守）。 */
  let deadlineAt = 0;

  /** 是否处于盯守状态。定时器句柄本身不是响应式数据，UI 需据此判断忙态是否仍有观察者。 */
  const watching = ref(false);

  /** 本次更新是否在进行（包含 ZIP 上传这一段本地链路）。 */
  const busy = computed(() => checking.value || uploading.value || WEB_BUSY_STATES.includes(state.value));

  /**
   * 提供给 UI 的状态键。
   *
   * `uploading` 只存在于本地（core 的手动通道不进入状态机）。若不合成，
   * 卡片会在上传期间显示上一次的 `idle`，表现为没有在上传。
   */
  const displayState = computed<WebUpdateState>(() => (uploading.value ? 'uploading' : state.value));

  /** 提供给 UI 的百分比：上传期间为浏览器 → core 的本地进度，其余时间为 core 侧进度。 */
  const displayProgress = computed(() => (uploading.value ? uploadPercent.value : progress.value));

  /** 卡片状态区是否显示（空闲且未上传时不显示）。 */
  const showStatus = computed(() => busy.value || state.value !== 'idle');

  function pushNotice(type: WebUpdateNotice['type'], text: string) {
    noticeSeq += 1;
    notice.value = { id: noticeSeq, type, text };
  }

  function clearNotice() {
    notice.value = null;
  }

  /**
   * 记录一条结论：写入响应式字段（卡片行立即更新）并持久化到 sessionStorage（跨刷新）。
   * 版本号在 `saveLastResult` 中统一去除 `v` 前缀（core 可能返回 `v0.0.6`，卡片文案会自行补 `v`）。
   */
  function recordResult(ok: boolean, version: string, message: string) {
    lastResult.value = saveLastResult(UPDATE_RESULT_KEYS.web, ok, version, message);
  }

  /**
   * 合并 core 状态。
   *
   * 空响应体（`undefined`）保持原状态不变，与 `stores/update.ts` 的 `applyStatus` 同一规则：
   * `d?.state || 'idle'` 这类写法会在请求被取消或无 body 时把忙态静默改回 `idle`，
   * 界面表现为更新意外结束。
   * `max_upload_bytes` 同理，仅在服务端返回正数时覆盖（老 core 不返回该字段）。
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

  // ── 轮询骨架（与 stores/update.ts 相同的单飞实现）──

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
      // 超时同样构成结论：写入 lastResult，否则错过 toast 后无法再获知本次更新结果。
      const text = '前端资源更新长时间没有结果，已停止轮询。可点「检查更新」再确认一次状态。';
      recordResult(false, '', text);
      pushNotice('warning', text);
      return;
    }
    try {
      const { data } = await getApiClient().get(Endpoints.webAssets.status, { timeout: STATUS_TIMEOUT_MS });
      if (applyStatus(data) && WEB_TERMINAL_STATES.includes(state.value)) settle();
    } catch {
      // 轮询失败不能视为更新失败：core 只读取内存状态，偶发抖动重试下一轮即可。
      // 真正的失败由 core 置 state=failed 表达；在此停止轮询并报错会使一次网络抖动
      // 导致进度永久停住。
    }
  }

  /** 单飞轮询：上一次轮询未完成时跳过本次触发。 */
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

  /** 停止盯守：不改动 core 侧状态，仅清理本地定时器。 */
  function stopWatch() {
    stopTimer();
  }

  /** 进入终态：停止计时，把结论写入 lastResult（常驻），并交由面板显示即时 toast。 */
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
      // need_push 视为未成功：更新源不可达，本次自动更新没有发生，需手动上传 ZIP。
      const text = message.value || '更新源不可用，可手动上传 ZIP 安装包';
      recordResult(false, '', text);
      pushNotice('warning', text);
    } else {
      // idle 表示 core 比对版本后认为无需更新（已是最新），不构成一次更新结果，不写入 lastResult。
      pushNotice('info', message.value || '前端资源已是最新版本');
    }
  }

  /**
   * 手动刷新一次状态（面板「刷新」按钮与 `resume()` 的第一步）。
   * 该端点无副作用，同时读取 `max_upload_bytes`。
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
   * 「检查更新」：由 core 从更新源拉取并安装（自动通道）。
   * `POST /api/web/check` 立即返回当前状态，进度通过轮询获取。
   */
  async function check(): Promise<void> {
    if (checking.value) return;
    checking.value = true;
    session.value = true;
    // 乐观置为 checking：POST 在途时卡片需显示「检查中」，否则按钮加载态与状态行不一致。
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
      // 失败时回到检查前的状态，而不是置为 failed：这是检查请求失败（网络或更新源问题），
      // 与更新失败不同；置为 failed 会使卡片报告一次并不存在的更新失败。
      // 真正的失败由 core 置 state=failed 表达（它比客户端更清楚安装是否成功）。
      // 同理不写 lastResult：这是一次查询失败，不是一次更新的结论。
      state.value = prevState;
      message.value = prevMessage;
      pushNotice('error', webErr(e, '检查前端资源更新失败'));
    } finally {
      checking.value = false;
    }
  }

  /**
   * 手动上传 ZIP（手动通道）。同步端点：core 收完 body 就地解压安装再回包，
   * 因此 2xx 且 `success` 即表示安装完成，没有第二步。
   *
   * 使用 `getApiClient()` 而非 `useCancellableApi()`：上传必须存活于弹窗与设置页之外
   * （「后台运行」或关闭设置页都不能中断上传）。代价是拿不到取消语义，
   * 因此这里的 `data === undefined` 只可能是服务端返回空 body，一律按失败处理。
   *
   * @param onProgress 可选的本地进度回调（当前 UI 直接读取 store 的 `uploadPercent`，
   *   保留该参数以便其他入口复用上传能力时不改动签名）
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
      // 字段名必须为 file：core 仅接受该 multipart 字段名。
      formData.append('file', file);
      const { data } = await getApiClient().post(Endpoints.webAssets.update, formData, {
        headers: { 'Content-Type': 'multipart/form-data' },
        // 默认 15s 超时对 50MB 的 ZIP 过短：设备 Wi-Fi 下传输到一半会被客户端中断，
        // 而 core 仍在接收。
        timeout: 0,
        onUploadProgress: (ev: { loaded: number; total?: number }) => {
          // ev.total 在部分浏览器或代理下缺失，回退到 file.size（与 APK 上传处理一致）。
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
        // 手动通道不经过 settle()（它不进入 core 的状态机），因此结论在此补记：
        // 上传成功即安装完成（同步端点），此时即为终态。
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
    // 上传或安装被 core 明确拒绝：这是本次更新的失败终态，两处都写入（结论与即时 toast）。
    recordResult(false, '', text);
    pushNotice('error', text);
  }

  /**
   * 结束本次更新会话（弹窗点击「完成 / 知道了」时调用）。
   *
   * 不改动 core 状态：卡片的状态行需继续如实显示上一次的结论（`done` / `failed` 均为事实），
   * 此函数只让弹窗回到可以重新选择文件的状态。
   */
  function endSession() {
    session.value = false;
  }

  /**
   * 刷新页面使新 bundle 生效。
   *
   * 放在 store 而非弹窗中：上传会存活于弹窗之外（「后台运行」关闭弹窗或离开设置页），
   * 而当前页面运行旧 bundle 与弹窗无关，刷新不能因弹窗关闭而跳过。
   * 时机为成功后 1200ms，留出时间显示更新完成。
   */
  function scheduleReload() {
    window.setTimeout(() => window.location.reload(), RELOAD_DELAY_MS);
  }

  /**
   * 恢复现场：读取一次 `/api/web/status`，若 core 仍在进行中则重新启动轮询。
   *
   * 这是刷新页面或关闭设置弹窗后重新进入时仍能看到进行中 web 更新的关键。
   * 已在盯守（`timer` 非空）或本地正在上传 ZIP 时直接返回，避免重复启动定时器。
   */
  async function resume(): Promise<void> {
    if (timer || uploading.value) return;
    const ok = await refreshStatus();
    if (!ok) return;
    if (WEB_BUSY_STATES.includes(state.value)) startTimer();
    // 终态（done/failed/need_push）不自动启动轮询：那是上次更新的结论，
    // 每次打开页面重放只会重复弹出提示。结论由 `lastResult` 提供
    // （其初值从 sessionStorage 恢复）。
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

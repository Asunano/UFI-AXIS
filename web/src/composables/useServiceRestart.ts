/**
 * 「重启后端服务」的看护状态（2026-10-14 新建）。
 *
 * ## 2026-10-14 之前是什么样、为什么不对
 * 原来是 `ServicePanel.vue` 里的「组件局部 ref + 一个写死的 13s 定时器」
 * （原 `:139-140` 的 `serviceRestarting` / `serviceRestartTimer`、`:224-229` 的 `setTimeout`）：
 * ① `13000` 是个**猜的**数：`POST /api/service/restart` 只是「先回响应、再触发重启」
 *    （core 侧 `ServiceRoutes.kt:79-93`），真正的拉起有两条路——detached shell（4s 后执行，
 *    `BackendService.kt:169-187`）与 AlarmManager 精确闹钟（注释口径见 `BackendService.kt:129-153`），
 *    谁先到算谁。设备慢一点 13s 就不够，回读到的是"未知"，而且**再也不会自己恢复**；
 * ② 状态是**组件实例级**的，`onUnmounted` 还会把定时器清掉（原 `:365-367`）——
 *    用户切走再回来只剩 `service.loaded = false` 的"未知"，**没有任何"上次重启到底成没成"的记录**。
 *
 * ## 现在怎么改
 * 照 `stores/update.ts:305-347`（core 自更新的重启看护）的做法：**轮询 `/health` 直到就绪 +
 * 明确截止时间**（逻辑抽在 `composables/useHealthProbe.ts`），并且**通了还要复核**
 * （见 `verifyRestarted`）。失败/超时都带原因，面板给「重试」。
 *
 * ## 状态为什么放在模块作用域（而不是 Pinia store）
 * 与 `views/files/useBatchOperation.ts` 同一套取舍：
 * ① 这份状态的唯一消费者就是设置页的「服务控制」卡，没有跨模块读取者；
 * ② core 侧没有"重启作业"这种东西可查（只有"服务在不在跑"），提成 store 只是换一个
 *    **同样活不过刷新**的容器，还得在一个全局 store 里放一份与其它模块无关的 UI 态。
 * 所以水平定在「模块级单例」：**切走再回来仍能看到上次重启的结果**。
 * 刷新页面仍会丢（浏览器里没有地方能持久化它），但刷新后 `onMounted` 会重新回读服务状态，
 * 不会像原来那样停在"未知"。
 *
 * 为什么不塞进 `stores/service.ts`：① 它不在本次写权限内；② 它管的是**服务开关的全局真相**
 * （布局层据此把正文换成「服务已停止」提示页），"重启看护"是设置页自己的事，混进去会让
 * 那个 store 的语义变糊。
 *
 * ## 为什么用 `getApiClient()` 而不是 `useCancellableApi()`
 * 看护必须**活得比组件久**（这正是"切页回来还能看到结果"的前提）。`useCancellableApi`
 * 会在组件卸载时 abort 在途请求，本件不能要那个行为；而且它把取消 resolve 成
 * `data === undefined`——本件里"接口不可达"是**重启中的正常状态**，要如实反映为"还在等"，
 * 不能和"取消"混成一个口径。所以全程走全局单例（同 `stores/update.ts:17` 的理由）。
 */
import { computed, onScopeDispose, ref } from 'vue';
import { getApiClient } from '@/composables/useApi';
import { Endpoints } from '@/api/contract';
import {
  HEALTH_DEADLINE_MS,
  HEALTH_POLL_MS,
  HEALTH_PROBE_TIMEOUT_MS,
  probeErrorText,
  waitForHealthReady,
} from '@/composables/useHealthProbe';

export type ServiceRestartPhase = 'idle' | 'restarting' | 'ready' | 'failed' | 'timeout';

export interface ServiceRestartHooks {
  /**
   * 重启请求已被受理（POST 已发出、且**没有被服务端明确拒绝**）时回调一次。
   * 面板据此提示"已请求重启"——放在这里而不是点确认时，是为了避免"请求被拒"时
   * 先弹一句"已请求重启"再弹一句"重启失败"。
   */
  onRequested?: () => void;
  /** 复核通过、确认服务已就绪时回调一次。面板据此回读状态 + 同步全局 store + 提示 */
  onReady?: (elapsedMs: number) => void;
  /** 失败 / 超时时回调一次，参数是给人看的原因 */
  onFailed?: (reason: string) => void;
}

// ── 模块级状态（见文件头「状态为什么放在模块作用域」）──
const phase = ref<ServiceRestartPhase>('idle');
/** 本轮看护的开始时刻（重试会重置：那是一个新的等待窗口） */
const startedAt = ref(0);
const elapsedMs = ref(0);
const failureReason = ref('');
/** 最近一轮探测的失败原因（连不上 / 复核没过），超时文案会带上它 */
const lastProbeError = ref('');

/** 重启前的服务运行时长基线，复核用；-1 = 没读到（见 `verifyRestarted`） */
let preRestartUptimeMs = -1;
/** 是否亲眼看到过接口中断（连不上）。没有基线时的退化判据 */
let sawDown = false;
/** 「已等 Ns」的 1s 心跳（探针是 2s 一次，计数要更平滑） */
let ticker: ReturnType<typeof setInterval> | null = null;
/** 轮次号：重试会 ++，让还在跑的老一轮结果作废（不打断它的请求，只是不再采纳） */
let seq = 0;
/** 当前页面注入的回调；面板卸载时解绑（见 useServiceRestart） */
let hooks: ServiceRestartHooks = {};

function startTicker() {
  stopTicker();
  // 心跳放模块作用域、跟着看护走（不跟组件走），于是切页回来计数仍从 startedAt 连续算
  ticker = setInterval(() => {
    elapsedMs.value = Date.now() - startedAt.value;
  }, 1000);
}

function stopTicker() {
  if (ticker) {
    clearInterval(ticker);
    ticker = null;
  }
}

function hasResponse(e: unknown): boolean {
  return !!(e as { response?: unknown })?.response;
}

function settleReady(ms: number) {
  stopTicker();
  phase.value = 'ready';
  elapsedMs.value = ms;
  failureReason.value = '';
  lastProbeError.value = '';
  hooks.onReady?.(ms);
}

function settleFailed(kind: 'failed' | 'timeout', reason: string) {
  stopTicker();
  phase.value = kind;
  failureReason.value = reason;
  hooks.onFailed?.(reason);
}

/** 重启前的运行时长基线。读不到返回 -1（复核退化成"必须亲眼看到接口断过"） */
async function readUptimeBaseline(): Promise<number> {
  try {
    const { data } = await getApiClient().get(Endpoints.service.status, { timeout: HEALTH_PROBE_TIMEOUT_MS });
    const v = Number(data?.uptime_ms ?? 0);
    return Number.isFinite(v) && v > 0 ? v : -1;
  } catch {
    return -1;
  }
}

/**
 * `/health` 通了之后的复核 —— **这一步是"有依据"的关键，别删**。
 *
 * 为什么不能只信 `/health`：重启的落地是 `context.stopService(...)`（`BackendService.kt:148-153`），
 * **销毁是异步的**，请求返回后旧实例还会应答一小会儿；而 `onDestroy` 又是先置
 * `isRunning = false`（`BackendService.kt:577-578`）再把停组件丢进独立协程（`:584-589`），
 * 所以这中间连 `uptime_ms` 都会先变成 0。只按"通了就算好"，面板会立刻回读到旧实例的数据、
 * 随后接口才真断 —— 又回到"显示未知"的老毛病。
 *
 * 判据用**服务运行时长**：`uptime_ms` 由 Service 实例的启动时刻算出（`BackendService.kt:121-127`），
 * 同一实例内单调递增、`isRunning=false` 时为 0；而新实例的 `isRunning` / `startedAtElapsed`
 * 是在 `onStartCommand` 里**同步**置位的（`:507-512`），先于 Ktor 起来。于是
 * 「`0 < uptime < 重启前基线`」就证明**确实是一个新实例在应答**。
 * 这与 `stores/update.ts:322-339` 复核版本号是同一个道理：那边也写着「只报"已重启"不够」。
 *
 * 返回空串 = 确认完成；返回非空 = 还没到位的原因（继续等，超时时会一并报出）。
 */
async function verifyRestarted(): Promise<string> {
  let uptime: number;
  try {
    const { data } = await getApiClient().get(Endpoints.service.status, { timeout: HEALTH_PROBE_TIMEOUT_MS });
    uptime = Number(data?.uptime_ms ?? -1);
  } catch (e) {
    return `core 已就绪但服务状态读不到（${probeErrorText(e, 'status 不可达')}）`;
  }
  if (!Number.isFinite(uptime) || uptime <= 0) {
    return 'core 已应答但服务尚未进入运行态（运行时长仍为 0）';
  }
  if (preRestartUptimeMs < 0) {
    // POST 前的回读就失败了，没有基线可比 → 退化为"必须亲眼看见接口断过"
    return sawDown ? '' : 'core 始终可答且未观察到接口中断，无法确认重启已发生';
  }
  if (uptime < preRestartUptimeMs) return '';
  return (
    `core 已应答但服务运行时长未重置（${Math.round(uptime / 1000)}s ≥ 重启前 ` +
    `${Math.round(preRestartUptimeMs / 1000)}s），应答的很可能还是旧实例`
  );
}

/** 只负责"看护到就绪"，不发重启请求（首次与重试共用） */
async function watchUntilReady(mySeq: number): Promise<void> {
  const result = await waitForHealthReady({
    pollMs: HEALTH_POLL_MS,
    deadlineMs: HEALTH_DEADLINE_MS,
    requestTimeoutMs: HEALTH_PROBE_TIMEOUT_MS,
    unreachableText: 'core 暂时不可达（重启中）',
    confirm: verifyRestarted,
    onProgress: (info) => {
      if (mySeq !== seq) return;
      // 失败轮次出现过一次就记下：它既是"接口真的断过"的证据，也是没有基线时的复核判据
      if (info.failures > 0) sawDown = true;
      lastProbeError.value = info.lastError;
    },
  });
  if (mySeq !== seq) return;
  if (result.canceled) return;
  if (result.ok) {
    settleReady(result.elapsedMs);
    return;
  }
  const reason = result.lastError || lastProbeError.value || 'core 在等待期内始终未就绪';
  settleFailed('timeout', `等待就绪超过 ${Math.round(HEALTH_DEADLINE_MS / 1000)} 秒：${reason}`);
}

export function useServiceRestart(h: ServiceRestartHooks = {}) {
  hooks = h;
  onScopeDispose(() => {
    // 面板卸载只解绑回调，**看护本身继续跑完**（请求走全局 axios 单例，不随组件取消）——
    // 这正是"切走再回来还能看到结果"的前提。回来时新实例会重新注入回调。
    if (hooks === h) hooks = {};
  });

  const restarting = computed(() => phase.value === 'restarting');
  /** 超时或服务端明确拒绝；两种都不是成功，面板要显示原因 + 重试 */
  const failed = computed(() => phase.value === 'timeout' || phase.value === 'failed');

  const statusText = computed(() => {
    const sec = Math.round(elapsedMs.value / 1000);
    if (phase.value === 'restarting') return `重启中，正在等待就绪（已等 ${sec} 秒）`;
    if (phase.value === 'ready') return `上次重启：已完成（耗时 ${sec} 秒）`;
    if (phase.value === 'timeout') return `重启未确认：${failureReason.value}`;
    if (phase.value === 'failed') return `重启失败：${failureReason.value}`;
    return '';
  });

  /**
   * 发起一次重启并看护到就绪。
   *
   * 顺序：先读一次运行时长基线（复核要用）→ POST → 轮询 `/health`。
   * `phase` 在第一个 await 之前就置为 `restarting`，所以调用方点完立刻能看到"重启中"。
   */
  async function request(): Promise<void> {
    if (phase.value === 'restarting') return;
    const mySeq = ++seq;
    phase.value = 'restarting';
    startedAt.value = Date.now();
    elapsedMs.value = 0;
    failureReason.value = '';
    lastProbeError.value = '';
    sawDown = false;
    startTicker();
    preRestartUptimeMs = await readUptimeBaseline();
    if (mySeq !== seq) return;
    try {
      await getApiClient().post(Endpoints.service.restart, undefined, { timeout: HEALTH_PROBE_TIMEOUT_MS });
    } catch (e) {
      if (mySeq !== seq) return;
      if (hasResponse(e)) {
        // 服务端**明确回了错**（4xx/5xx）：重启没被受理，不必再白等 90 秒
        settleFailed('failed', `重启请求被拒绝：${probeErrorText(e, '未知错误')}`);
        return;
      }
      // 没有响应 = 连接被断开/超时。这是**预期**的：core 先回响应再销毁自己，
      // 快的时候响应还没送到连接就断了，不代表重启没发生 → 继续看护，只是记下原因。
      lastProbeError.value = '重启请求未收到响应（连接被断开，属预期）';
    }
    if (mySeq !== seq) return;
    hooks.onRequested?.();
    await watchUntilReady(mySeq);
  }

  /**
   * 重试。两种失败的处理**不一样**，别合并：
   *
   * · `failed`（服务端明确拒绝了请求）：什么都没在跑，重试就得**重新发一次**；
   * · `timeout`（请求被受理了但没等到就绪）：超时最常见的原因是设备比预期慢、
   *   服务其实已经在起来了，这时再 POST 一次只会把刚起来的服务按回去 →
   *   **只重新看护**，不再打一次重启。真要从头重启，用户点「重启服务」按钮即可
   *   （那是明确的第二次决定，弹窗还会再确认一次）。
   *
   * 两条路都沿用第一次读到的运行时长基线，所以复核口径不变。
   */
  async function retry(): Promise<void> {
    if (phase.value === 'restarting') return;
    if (phase.value === 'failed') {
      await request();
      return;
    }
    const mySeq = ++seq;
    phase.value = 'restarting';
    startedAt.value = Date.now();
    elapsedMs.value = 0;
    failureReason.value = '';
    lastProbeError.value = '';
    startTicker();
    await watchUntilReady(mySeq);
  }

  return { restarting, failed, statusText, request, retry };
}

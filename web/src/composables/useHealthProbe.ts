/**
 * core `/health` 就绪探针（2026-10-14 新建）。
 *
 * ## 它是从哪抄来的
 * `stores/update.ts` 的「阶段二：等 core 重启就绪」已经是一份**写对了**的实现：
 * ① 轮询 `/health` 直到 `status === 'ok'`（`:313-321`，`/health` 免鉴权、不碰设备，是最轻的探针）；
 * ② **通了之后还要复核**——`:322-339`，那边的原话是「只报"已重启"不够，装失败也会重启回旧版」，
 *    所以复核版本号，没对上就退回继续等，并把原因记进 `lastProbeError`；
 * ③ 连不上**不终止**，只累加失败数 + 记原因（`:340-343`）——重启期间连不上是预期状态；
 * ④ 最后卡一个**明确的截止时间**（`:344-346`），上限是 `RESTART_DEADLINE_MS = 90_000`（`:85`）。
 * 本文件把「① 轮询 + ② 复核钩子 + ③ 记原因 + ④ 截止时间」抽成通用件，
 * 常量取值与 `stores/update.ts` 的 `:81`（`HEALTH_POLL_MS`）、`:85`、`:87`（`PROBE_TIMEOUT_MS`）
 * 逐一对齐，不另立一套数字。
 *
 * ## 为什么 `stores/update.ts` 那边没跟着改
 * 2026-10-14 该文件正由他人改动，不在本次写权限内，所以这里是「抽公共 + 单边引用」：
 * ServicePanel 走本件，`stores/update.ts` 保持原样。**后续应当**把它的 `restartTick`
 * 收敛到本件（结构一一对应：`probeHealth` ↔ `:316-319`、`confirm` ↔ `:322-339`、
 * 失败计数与超时 ↔ `:340-346`），届时两边只剩一份实现、一套常量。
 */
import { getApiClient } from '@/composables/useApi';
import { Endpoints } from '@/api/contract';

/** `/health` 轮询间隔（对齐 `stores/update.ts:81` 的 `HEALTH_POLL_MS`） */
export const HEALTH_POLL_MS = 2000;

/**
 * 单次探测请求超时（对齐 `stores/update.ts:87` 的 `PROBE_TIMEOUT_MS`）。
 * 重启期间连不上要**快速失败**进入下一轮，不能让一次请求把整个轮询间隔占满。
 */
export const HEALTH_PROBE_TIMEOUT_MS = 5000;

/** 就绪等待上限（对齐 `stores/update.ts:85` 的 `RESTART_DEADLINE_MS = 90_000`） */
export const HEALTH_DEADLINE_MS = 90_000;

export interface HealthProgress {
  /** 从开始等待到现在的毫秒数 */
  elapsedMs: number;
  /** 已探测轮次 */
  attempts: number;
  /** 其中失败的轮次（连不上 / 未就绪 / 复核未通过都算） */
  failures: number;
  /** 最近一轮失败或未到位的原因；'' = 无 */
  lastError: string;
}

export interface HealthWaitResult extends HealthProgress {
  ok: boolean;
  /**
   * 调用方主动中止（`signal` 已 abort）。既不是成功也不是失败，
   * 调用方**不该**把它报成错误——与 `useCancellableApi` 把取消 resolve 成
   * `data === undefined` 是同一条口径。
   */
  canceled: boolean;
}

export interface HealthWaitOptions {
  /**
   * `/health` 通了之后的复核钩子。返回**空串 = 确认完成**；
   * 返回非空 = 还没到位的原因（继续等，超时时会一并报出）。
   * 抛错按「这一轮探测失败」处理（原因走 `probeErrorText`）。
   */
  confirm?: () => Promise<string>;
  /** 每轮结束回调一次（成功/失败都调），供调用方刷新「已等 Ns」与失败原因 */
  onProgress?: (info: HealthProgress) => void;
  pollMs?: number;
  deadlineMs?: number;
  requestTimeoutMs?: number;
  signal?: AbortSignal;
  /** 连不上（没有 HTTP 响应）时的兜底文案 */
  unreachableText?: string;
}

/**
 * 单次探测。判据与 `stores/update.ts:317` 一致：**只有 `status === 'ok'` 才算就绪**——
 * core 已应答但未就绪时要继续等，不能把"HTTP 能连上"当成"服务好了"。
 */
export async function probeHealth(timeoutMs: number = HEALTH_PROBE_TIMEOUT_MS): Promise<void> {
  const { data } = await getApiClient().get(Endpoints.health, { timeout: timeoutMs });
  if ((data as { status?: string } | undefined)?.status !== 'ok') {
    throw new Error('core 已应答但 /health 未就绪');
  }
}

/**
 * 从异常里取可读原因。
 *
 * 与 `stores/update.ts:102-108` 的 `updateErr` 同口径（信封 `{ error, message }` 两个键都认），
 * 但多一条**关键**判断：**没有 `response` 就不是接口报错**。重启期间连接被拒 / 请求超时
 * （axios 的 `Network Error` / `ECONNABORTED`）都没有 `response`，那属于「服务正在重启」这个
 * 预期状态，统一说成"暂时不可达"，不要把它当失败、更不要把 `Network Error` 甩给用户。
 */
export function probeErrorText(e: unknown, fallback: string): string {
  const resp = (e as { response?: { data?: { message?: string; error?: string } } })?.response;
  if (!resp) return fallback;
  if (resp.data?.message) return resp.data.message;
  if (resp.data?.error) return resp.data.error;
  if (e instanceof Error && e.message) return e.message;
  return fallback;
}

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

/**
 * 轮询 `/health` 直到就绪（或超时/中止）。
 *
 * 顺序与 `stores/update.ts:313-346` 的 `restartTick` 一致：
 * 探测 → 成功则复核（复核不过就当这一轮没成功）→ 失败则记原因 → 判断是否到截止时间 → 睡到下一轮。
 */
export async function waitForHealthReady(opts: HealthWaitOptions = {}): Promise<HealthWaitResult> {
  const pollMs = opts.pollMs ?? HEALTH_POLL_MS;
  const deadlineMs = opts.deadlineMs ?? HEALTH_DEADLINE_MS;
  const requestTimeoutMs = opts.requestTimeoutMs ?? HEALTH_PROBE_TIMEOUT_MS;
  const unreachableText = opts.unreachableText ?? 'core 暂时不可达（重启中）';
  const startedAt = Date.now();
  const deadlineAt = startedAt + deadlineMs;
  let attempts = 0;
  let failures = 0;
  let lastError = '';

  for (;;) {
    if (opts.signal?.aborted) {
      return { ok: false, canceled: true, elapsedMs: Date.now() - startedAt, attempts, failures, lastError };
    }
    attempts += 1;
    try {
      await probeHealth(requestTimeoutMs);
      const reason = opts.confirm ? await opts.confirm() : '';
      if (!reason) {
        const elapsedMs = Date.now() - startedAt;
        opts.onProgress?.({ elapsedMs, attempts, failures, lastError: '' });
        return { ok: true, canceled: false, elapsedMs, attempts, failures, lastError: '' };
      }
      // 复核没过：`/health` 能答不代表重启已经发生（见调用方 confirm 的注释）
      lastError = reason;
    } catch (e) {
      failures += 1;
      lastError = probeErrorText(e, unreachableText);
    }
    const elapsedMs = Date.now() - startedAt;
    opts.onProgress?.({ elapsedMs, attempts, failures, lastError });
    if (Date.now() >= deadlineAt) {
      return { ok: false, canceled: false, elapsedMs, attempts, failures, lastError };
    }
    // 睡到下一个轮询点，但**不睡过截止时间**，否则超时判定会被拖到 deadline 之后
    await sleep(Math.max(0, Math.min(pollMs, deadlineAt - Date.now())));
  }
}

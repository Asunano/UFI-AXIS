import { defineStore } from 'pinia';
import { computed, ref } from 'vue';
import { getApiClient } from '@/composables/useApi';
import { Endpoints } from '@/api/contract';

/**
 * 后端 core 自更新的全局状态（2026-09-06）。
 *
 * 为什么必须提到 store：
 * ① 状态原来只活在 `UpdatePanel.vue` 的局部 `reactive` 里，**刷新页面 / 切走再回来**
 *    组件重建即归零，进度条冻在原地，而 core 侧其实还在下载/安装；
 * ② 轮询原来用 `useInterval`（组件级，卸载即停）+ `useCancellableApi`（卸载 abort），
 *    离开设置页就没人盯着这次更新了；
 * ③ 判「更新完成」需要跨 core 重启：`state=done` 之后还得等新进程把 `/health` 拉起来
 *    并复核版本号，这段窗口比任何单个组件的生命周期都长。
 *
 * 所以这里用 `getApiClient()`（不带取消）+ 自己的 `setInterval`，生命周期跟着 store 走。
 */

/** core 侧 `UpdateManager.State` 的小写镜像（`statusToMap` 已统一小写）。 */
export type BackendUpdateState =
  | 'idle'
  | 'downloading'
  | 'verifying'
  | 'installing'
  | 'uploading'
  | 'done'
  | 'failed'
  | 'need_push';

/**
 * 本地流程阶段 —— core 状态之外的一层。
 *
 * `done` 只代表「core 自报安装完成」，此时进程可能正在被 watchdog 重启，接口会中断十几秒。
 * `restarting` / `verifying` 这两段服务端**没有状态可查**（要么连不上，要么答的是旧进程），
 * 只能由前端自己计时，所以必须单独建模，不能直接把 `done` 显示成"更新完成"。
 */
export type UpdatePhase = 'idle' | 'installing' | 'restarting' | 'verifying' | 'finished' | 'timeout';

export interface BackendStatus {
  state: BackendUpdateState;
  progress: number;
  message: string;
  currentVersion: string;
  latestVersion: string;
  apkPath: string;
}

/** `GET /api/update/backend-info` 响应（只检查，不下载不安装）。 */
export interface BackendUpdateInfo {
  currentVersion: string;
  latestVersion: string;
  hasUpdate: boolean;
  changelog: string;
  apkUrl: string;
  apkSize: number;
  sha256: string;
}

/** 一次性提示：store 里产生，由面板消费后调 `clearNotice()`（避免重挂载重复弹）。 */
export interface UpdateNotice {
  id: number;
  type: 'success' | 'error' | 'warning' | 'info';
  text: string;
}

export const BACKEND_BUSY_STATES: BackendUpdateState[] = ['downloading', 'verifying', 'installing', 'uploading'];

const KNOWN_STATES: BackendUpdateState[] = [...BACKEND_BUSY_STATES, 'idle', 'done', 'failed', 'need_push'];

/** 忙态轮询 `/api/update/status` 的间隔 */
const STATUS_POLL_MS = 1500;
/** 重启就绪探测 `/health` 的间隔 */
const HEALTH_POLL_MS = 2000;
/** 阶段一（下载+校验+安装，直到 core 自报 done）上限 3.5 分钟 */
const INSTALL_DEADLINE_MS = 210_000;
/** 阶段二（等 core 重启就绪 + 复核版本号）上限 1.5 分钟；两段合计 5 分钟 */
const RESTART_DEADLINE_MS = 90_000;
/** 单次探测请求超时：重启期间连不上要快速失败进入下一轮，不能占满整个间隔 */
const PROBE_TIMEOUT_MS = 5000;

function emptyStatus(): BackendStatus {
  return { state: 'idle', progress: 0, message: '', currentVersion: '', latestVersion: '', apkPath: '' };
}

/** `/api/update/*` 的失败信封是 `{ error, message, code }`，两个键都要认。 */
function updateErr(e: unknown, fallback: string): string {
  const d = (e as { response?: { data?: { message?: string; error?: string } } })?.response?.data;
  if (d?.message) return d.message;
  if (d?.error) return d.error;
  if (e instanceof Error && e.message) return e.message;
  return fallback;
}

/** 清单里常见 `v0.0.3` 与 BuildConfig 的 `0.0.3` 是同一个版本，比较前先剥掉前缀。 */
function normalizeVersion(v: string): string {
  return String(v || '')
    .trim()
    .replace(/^[vV]/, '');
}

function normalizeState(raw: unknown): BackendUpdateState {
  const s = String(raw ?? '').toLowerCase();
  return (KNOWN_STATES as string[]).includes(s) ? (s as BackendUpdateState) : 'idle';
}
export const useUpdateStore = defineStore('update', () => {
  /** core 侧 `GET /api/update/status` 的镜像 */
  const status = ref<BackendStatus>(emptyStatus());
  const phase = ref<UpdatePhase>('idle');
  /** 本次更新的目标版本（来自 backend-info 的 latest_version；本地包安装时为空） */
  const targetVersion = ref('');
  /** 复核成功后读到的实际版本 */
  const installedVersion = ref('');
  const notice = ref<UpdateNotice | null>(null);
  /**
   * 最近一次探测失败原因。安装/重启期间连不上是**预期**的（core 在自杀重启），
   * 所以不当错误终止，但必须记下来 —— 超时时要把它一起报出去，而不是像原来那样 `catch {}` 全吞。
   */
  const lastProbeError = ref('');
  /** 连续探测失败次数（仅用于展示/排查） */
  const probeFailures = ref(0);
  /** 当前阶段的截止时间戳（0 = 未在盯） */
  const deadlineAt = ref(0);

  // 定时器句柄不进 return（仿 stores/service.ts：非响应式内部状态用闭包变量）
  let timer: ReturnType<typeof setInterval> | null = null;
  let ticking = false;
  let noticeSeq = 0;

  /** 是否处于「前端正在盯着这次更新」的阶段 */
  const watching = computed(
    () => phase.value === 'installing' || phase.value === 'restarting' || phase.value === 'verifying'
  );
  /** core 正在重启/复核版本 —— UI 必须显示「core 正在重启，请稍候」而不是"完成" */
  const restarting = computed(() => phase.value === 'restarting' || phase.value === 'verifying');
  const busy = computed(() => watching.value || BACKEND_BUSY_STATES.includes(status.value.state));

  /**
   * 给 UI 用的状态键：重启/复核期间盖掉 core 的 `done`（那只是"包装完了"，不是"更新完成"），
   * 超时单独一个键（面板据此显示「重新检查状态」入口）。
   */
  const displayState = computed<BackendUpdateState | 'restarting' | 'timeout'>(() => {
    if (phase.value === 'timeout') return 'timeout';
    if (restarting.value) return 'restarting';
    return status.value.state;
  });

  function pushNotice(type: UpdateNotice['type'], text: string) {
    noticeSeq += 1;
    notice.value = { id: noticeSeq, type, text };
  }

  function clearNotice() {
    notice.value = null;
  }

  /**
   * 合并 core 状态。
   *
   * 空响应体（请求被取消 / 无 body）**保持原状态不动**：原实现 `d?.state || 'idle'` 会在
   * `useCancellableApi` 卸载 abort 后把忙态静默打回 `idle`，界面看起来"更新凭空结束了"。
   * `apk_path` 同理只在服务端给出非空值时才覆盖（老 core 的 /status 不回该字段），
   * 显式清空由 [reset] 负责。
   */
  function applyStatus(d: unknown): boolean {
    if (!d || typeof d !== 'object') return false;
    const raw = d as Record<string, unknown>;
    if (raw.state === undefined) return false;
    const apkPath = typeof raw.apk_path === 'string' && raw.apk_path ? raw.apk_path : status.value.apkPath;
    status.value = {
      state: normalizeState(raw.state),
      progress: typeof raw.progress === 'number' ? raw.progress : 0,
      message: typeof raw.message === 'string' ? raw.message : '',
      currentVersion: typeof raw.current_version === 'string' ? raw.current_version : '',
      latestVersion: typeof raw.latest_version === 'string' ? raw.latest_version : '',
      apkPath,
    };
    return true;
  }

  /** 手动刷新（面板「刷新」按钮）。该端点有副作用：INSTALLING 态时 core 会顺带回读 watchdog 日志。 */
  async function refreshStatus(): Promise<BackendStatus | null> {
    try {
      const { data } = await getApiClient().get(Endpoints.update.status);
      const ok = applyStatus(data);
      lastProbeError.value = '';
      return ok ? status.value : null;
    } catch (e) {
      lastProbeError.value = updateErr(e, '查询更新状态失败');
      return null;
    }
  }

  /**
   * 只检查 core 自身版本（不下载、不安装）。失败向上抛，由面板提示
   * （502 UPSTREAM_FAILED = 更新源不可用）。
   */
  async function fetchBackendInfo(): Promise<BackendUpdateInfo> {
    const { data } = await getApiClient().get(Endpoints.update.backendInfo);
    const raw = (data ?? {}) as Record<string, unknown>;
    return {
      currentVersion: String(raw.current_version ?? ''),
      latestVersion: String(raw.latest_version ?? ''),
      hasUpdate: raw.has_update === true,
      changelog: String(raw.changelog ?? ''),
      apkUrl: String(raw.apk_url ?? ''),
      apkSize: Number(raw.apk_size ?? 0),
      sha256: String(raw.sha256 ?? ''),
    };
  }
  // ── 轮询骨架 ──

  function stopTimer() {
    if (timer) {
      clearInterval(timer);
      timer = null;
    }
    ticking = false;
  }

  /** 单飞轮询：上一轮没跑完就跳过本轮（重启期间单次请求可能拖到 5s） */
  function startTimer(fn: () => Promise<void>, delay: number) {
    stopTimer();
    timer = setInterval(() => {
      if (ticking) return;
      ticking = true;
      void fn().finally(() => {
        ticking = false;
      });
    }, delay);
  }

  /** 停止盯守（不改 core 侧任何东西，仅收摊本地定时器） */
  function stopWatch() {
    stopTimer();
    deadlineAt.value = 0;
    if (watching.value) phase.value = 'idle';
  }

  // ── 阶段一：下载 / 校验 / 安装 ──

  function beginInstallWatch() {
    phase.value = 'installing';
    deadlineAt.value = Date.now() + INSTALL_DEADLINE_MS;
    probeFailures.value = 0;
    lastProbeError.value = '';
    startTimer(installTick, STATUS_POLL_MS);
  }

  async function installTick() {
    let fresh = false;
    try {
      const { data } = await getApiClient().get(Endpoints.update.status, { timeout: PROBE_TIMEOUT_MS });
      fresh = applyStatus(data);
      if (fresh) {
        probeFailures.value = 0;
        lastProbeError.value = '';
      }
    } catch (e) {
      // 安装会杀掉 core 进程，连接失败属预期 → 继续重试，但记录原因供超时时报出
      probeFailures.value += 1;
      lastProbeError.value = updateErr(e, 'core 暂时不可达（安装中）');
    }
    if (fresh) {
      const s = status.value.state;
      // done 只是 core 自报安装完成，还要等新进程真的把服务拉起来
      if (s === 'done') {
        beginRestartWatch();
        return;
      }
      if (!BACKEND_BUSY_STATES.includes(s)) {
        finishNonDone(s);
        return;
      }
    }
    if (Date.now() >= deadlineAt.value) {
      failTimeout(`安装阶段超过 ${Math.round(INSTALL_DEADLINE_MS / 1000)} 秒仍未完成（core 状态：${status.value.state}）`);
    }
  }

  // ── 阶段二：等 core 重启就绪 + 复核版本号 ──

  function beginRestartWatch() {
    phase.value = 'restarting';
    deadlineAt.value = Date.now() + RESTART_DEADLINE_MS;
    probeFailures.value = 0;
    lastProbeError.value = '';
    startTimer(restartTick, HEALTH_POLL_MS);
  }

  async function restartTick() {
    try {
      // /health 免鉴权且不碰设备，是最轻的就绪探针
      const { data } = await getApiClient().get(Endpoints.health, { timeout: PROBE_TIMEOUT_MS });
      if ((data as { status?: string } | undefined)?.status !== 'ok') {
        throw new Error('core 已应答但 /health 未就绪');
      }
      probeFailures.value = 0;
      lastProbeError.value = '';
      // 就绪 → 复核版本号是否真的生效（只报"已重启"不够，装失败也会重启回旧版）
      phase.value = 'verifying';
      const actual = await readInstalledVersion();
      const target = normalizeVersion(targetVersion.value);
      if (!target) {
        // 本地包安装（install-local）拿不到目标版本号，就绪即认为完成
        succeed(actual);
        return;
      }
      if (actual && normalizeVersion(actual) === target) {
        succeed(actual);
        return;
      }
      // 版本还没变：很可能应答的是尚未被 watchdog 换掉的旧进程 → 回到等待，直到 deadline
      phase.value = 'restarting';
      lastProbeError.value = actual
        ? `core 已就绪但版本仍为 v${actual}（期望 v${target}）`
        : 'core 已就绪但读不到版本号';
    } catch (e) {
      probeFailures.value += 1;
      lastProbeError.value = updateErr(e, 'core 尚未重启就绪');
    }
    if (Date.now() >= deadlineAt.value) {
      failTimeout(`core 重启就绪等待超过 ${Math.round(RESTART_DEADLINE_MS / 1000)} 秒`);
    }
  }

  /**
   * 读当前实际运行的版本号。
   * 首选 `/api/update/status` 的 `current_version` —— 它来自 BuildConfig，**不走外网**；
   * 读不到才退到 `backend-info`（那个要拉更新清单，设备离线时会 502）。
   */
  async function readInstalledVersion(): Promise<string> {
    try {
      const { data } = await getApiClient().get(Endpoints.update.status, { timeout: PROBE_TIMEOUT_MS });
      if (applyStatus(data) && status.value.currentVersion) return status.value.currentVersion;
    } catch (e) {
      lastProbeError.value = updateErr(e, '版本复核失败（status 不可达）');
    }
    try {
      const info = await fetchBackendInfo();
      if (info.currentVersion) return info.currentVersion;
    } catch (e) {
      lastProbeError.value = updateErr(e, '版本复核失败（更新源不可用）');
    }
    return '';
  }
  // ── 终态 ──

  function succeed(actualVersion: string) {
    stopTimer();
    phase.value = 'finished';
    deadlineAt.value = 0;
    installedVersion.value = actualVersion || targetVersion.value;
    probeFailures.value = 0;
    lastProbeError.value = '';
    pushNotice(
      'success',
      installedVersion.value ? `更新完成，已更新到 v${installedVersion.value}` : '更新完成，core 已重启就绪'
    );
  }

  /** core 脱离忙态但不是 done：failed / need_push / idle（idle = 清单里没有更新） */
  function finishNonDone(state: BackendUpdateState) {
    stopTimer();
    phase.value = 'idle';
    deadlineAt.value = 0;
    if (state === 'failed') pushNotice('error', status.value.message || '后端更新失败');
    else if (state === 'need_push') pushNotice('warning', status.value.message || '更新源不可达，请手动上传 APK');
    else pushNotice('info', status.value.message || '后端已是最新版本');
  }

  /** 超时：停止轮询，把最后一次探测失败原因一起报出（不做无限静默重试） */
  function failTimeout(reason: string) {
    stopTimer();
    phase.value = 'timeout';
    deadlineAt.value = 0;
    const detail = lastProbeError.value ? `，最后一次探测：${lastProbeError.value}` : '';
    pushNotice('error', `${reason}${detail}。已停止轮询，可点「重新检查状态」再确认一次。`);
  }

  // ── 对外动作 ──

  /**
   * 真正触发更新（**必须**在前端已用 [fetchBackendInfo] 展示版本并让用户二次确认之后调用）。
   * `POST /api/update/check` 的语义是「检查+下载+校验+安装+重启」一条龙。
   * 注意 core 对重复触发是静默忽略（回 200 + 旧状态），响应无法区分「已接受」与「被忽略」。
   */
  async function startInstall(target: string): Promise<void> {
    const { data } = await getApiClient().post(Endpoints.update.check);
    applyStatus(data);
    targetVersion.value = normalizeVersion(target) || normalizeVersion(status.value.latestVersion);
    installedVersion.value = '';
    if (BACKEND_BUSY_STATES.includes(status.value.state)) beginInstallWatch();
    else if (status.value.state === 'done') beginRestartWatch();
    else finishNonDone(status.value.state);
  }

  /** 安装已上传的本地 APK（兜底通道）。返回 false = core 没能启动安装。 */
  async function installLocal(apkPath: string): Promise<boolean> {
    const { data } = await getApiClient().post(Endpoints.update.installLocal, { apk_path: apkPath });
    const body = (data ?? {}) as Record<string, unknown>;
    applyStatus(body.status);
    if (body.ok === false) return false;
    // 本地包的版本号未知（清单没参与），完成判定退化为「/health 就绪即完成」
    targetVersion.value = '';
    installedVersion.value = '';
    if (BACKEND_BUSY_STATES.includes(status.value.state)) beginInstallWatch();
    else if (status.value.state === 'done') beginRestartWatch();
    return true;
  }

  /**
   * 面板挂载时调用：拉一次状态，**若 core 仍处忙态就自动续上轮询**
   * —— 这就是"刷新页面 / 切走再回来，进度继续走"的关键。
   * 已经在盯守中则原地不动（别重复起定时器）。
   */
  async function resume(): Promise<void> {
    if (watching.value) return;
    const s = await refreshStatus();
    if (!s) return;
    if (BACKEND_BUSY_STATES.includes(s.state)) {
      if (!targetVersion.value) targetVersion.value = normalizeVersion(s.latestVersion);
      beginInstallWatch();
    }
    // state=done 时**不**自动进入重启复核：那是历史遗留终态（上次更新的结论），
    // 页面每次打开都去复核一遍只会重复弹"更新完成"。需要复核请点「重新检查状态」。
  }

  /** 「重新检查状态」入口：超时或失败后手动再确认一次，忙态则重新续上轮询。 */
  async function recheck(): Promise<void> {
    stopTimer();
    phase.value = 'idle';
    deadlineAt.value = 0;
    lastProbeError.value = '';
    probeFailures.value = 0;
    const s = await refreshStatus();
    if (!s) {
      pushNotice('error', lastProbeError.value || '状态查询失败，core 可能仍在重启');
      return;
    }
    if (BACKEND_BUSY_STATES.includes(s.state)) {
      beginInstallWatch();
      return;
    }
    if (s.state === 'done') {
      // 手动复核：走一遍 /health + 版本比对，确认新版本确实生效
      beginRestartWatch();
      return;
    }
    pushNotice('info', s.message || `当前状态：${s.state}`);
  }

  /** 重置 core 侧状态（"忘掉这次更新"），同时清掉本地阶段与 apkPath。 */
  async function reset(): Promise<void> {
    stopTimer();
    const { data } = await getApiClient().post(Endpoints.update.reset);
    phase.value = 'idle';
    deadlineAt.value = 0;
    targetVersion.value = '';
    installedVersion.value = '';
    lastProbeError.value = '';
    probeFailures.value = 0;
    status.value = emptyStatus();
    applyStatus(data);
  }

  /** 上传 APK 成功后把路径记进状态（老 core 的 /status 不回 apk_path，靠这里保住按钮）。 */
  function setApkPath(path: string) {
    status.value = { ...status.value, apkPath: path };
  }

  return {
    status,
    phase,
    targetVersion,
    installedVersion,
    notice,
    lastProbeError,
    probeFailures,
    deadlineAt,
    watching,
    restarting,
    busy,
    displayState,
    applyStatus,
    refreshStatus,
    fetchBackendInfo,
    startInstall,
    installLocal,
    resume,
    recheck,
    reset,
    setApkPath,
    stopWatch,
    clearNotice,
  };
});
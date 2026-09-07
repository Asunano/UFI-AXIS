/**
 * 实时数据 composable —— 定时轮询与 WebSocket 主题订阅。
 *
 * 设计边界（旧版实现踩过的坑，勿回退）：
 * - **不在这里 connect / disconnect**。WS 是全局单例连接，由应用壳 `DefaultLayout` 独占
 *   管理生命周期。若某个视图卸载时调 disconnect，会把其他视图的订阅一起断掉。
 *   本文件只提供「订阅 + 自动退订」。
 * - **不代替视图处理数据**。handler 由调用方提供，视图各自决定是写 store 还是喂本地图表缓冲。
 * - **不返回 store 的解包值**。`wsStore.status` 取出来是字符串快照而非 ref，需要响应式状态
 *   请直接在视图里读 `wsStore.status`（Pinia 的 state 访问本身就是响应式的）。
 */
import { computed, getCurrentInstance, onMounted, onUnmounted, ref, unref, watch, type Ref } from 'vue';
import { useWebSocketStore } from '@/stores/websocket';

/** 可以是常量、ref，或每次读取时计算的 getter（供 `prefs.refreshIntervalSec * 1000` 这类场景）。 */
type MaybeReactive<T> = T | Ref<T> | (() => T);

function readReactive<T>(value: MaybeReactive<T>): T {
  return typeof value === 'function' ? (value as () => T)() : unref(value);
}

/**
 * 全局「浏览器标签页隐藏即暂停轮询」机制。
 *
 * 设备 Web UI 常被留在手机后台标签页，若仍按 2–10s 轮询会持续消耗热点蜂窝带宽与电量。
 * 这里维护一份所有活跃 `useInterval` 句柄，在 `visibilitychange` 时统一 stop / 恢复。
 * 仅对「隐藏前处于活跃」的轮询做恢复；手动 stop 的不受影响。
 */
interface IntervalHandle {
  start: () => void;
  stop: () => void;
  isActive: () => boolean;
  hiddenWasActive: boolean;
}

const registeredIntervals = new Set<IntervalHandle>();
let visibilityInstalled = false;

function ensureVisibilityPause() {
  if (visibilityInstalled || typeof document === 'undefined') return;
  visibilityInstalled = true;
  document.addEventListener('visibilitychange', () => {
    const hidden = document.hidden;
    registeredIntervals.forEach((h) => {
      if (hidden) {
        if (h.isActive()) {
          h.hiddenWasActive = true;
          h.stop();
        }
      } else if (h.hiddenWasActive) {
        h.hiddenWasActive = false;
        h.start();
      }
    });
  });
}

export interface UseIntervalOptions {
  /** 组件挂载后是否自动启动。默认 true；需要手动控制的轮询（如更新进度）传 false。 */
  autoStart?: boolean;
  /** 启动时是否立刻执行一次，而不是等第一个间隔到点。默认 false。 */
  immediate?: boolean;
}

/**
 * 定时轮询：自动跟随组件生命周期，delay 变化时自动重建，并跳过重入。
 *
 * 相比各视图手写的 `let timer = null` + `setInterval` + `onUnmounted(clearInterval)`：
 * - delay 支持响应式，改刷新间隔不用自己拆装定时器；
 * - `fn` 返回 Promise 时会等它结束才计入下一轮，慢接口不会堆积并发请求；
 * - delay <= 0 视为「不轮询」，可用于把间隔设为 0 来临时关闭。
 *
 * @example
 * // 固定 10 秒，挂载即拉一次
 * useInterval(loadSummary, 10_000, { immediate: true })
 * // 间隔跟随用户设置
 * useInterval(loadAll, () => prefs.value.refreshIntervalSec * 1000)
 * // 手动控制：等后端进入终态再停
 * const poll = useInterval(checkStatus, 1000, { autoStart: false })
 */
export function useInterval(fn: () => unknown, delay: MaybeReactive<number>, options: UseIntervalOptions = {}) {
  const { autoStart = true, immediate = false } = options;

  const active = ref(false);
  let timer: ReturnType<typeof setInterval> | null = null;
  // 上一轮未结束时跳过本轮：慢接口下避免请求堆积（原各视图的裸 setInterval 都没有这层保护）
  let inFlight = false;

  const delayMs = computed(() => {
    const raw = readReactive(delay);
    return Number.isFinite(raw) && raw > 0 ? raw : 0;
  });

  async function tick() {
    if (inFlight) return;
    inFlight = true;
    try {
      await fn();
    } finally {
      inFlight = false;
    }
  }

  function clearTimer() {
    if (timer) {
      clearInterval(timer);
      timer = null;
    }
  }

  function start() {
    clearTimer();
    active.value = true;
    if (immediate) void tick();
    if (delayMs.value <= 0) return;
    timer = setInterval(tick, delayMs.value);
  }

  function stop() {
    clearTimer();
    active.value = false;
  }

  // 间隔变化时重建（仅在运行中）；停止状态下只记录新值，下次 start 生效
  watch(delayMs, () => {
    if (active.value) {
      clearTimer();
      if (delayMs.value > 0) timer = setInterval(tick, delayMs.value);
    }
  });

  const instance = getCurrentInstance();
  const handle: IntervalHandle = {
    start,
    stop,
    isActive: () => active.value,
    hiddenWasActive: false,
  };
  registeredIntervals.add(handle);
  ensureVisibilityPause();
  if (instance) {
    if (autoStart) onMounted(start);
    onUnmounted(() => {
      registeredIntervals.delete(handle);
      stop();
    });
  }

  return { active, start, stop, trigger: tick };
}

/**
 * 订阅单个 WebSocket 频道，组件卸载时自动退订。
 *
 * 频道名取值见 `api/contract.ts` 的 `WsChannel`。注意 `WS_NEVER_BROADCAST` 里的频道
 * （battery / sms_contacts）core 从不广播，订阅了也不会有回调。
 *
 * @returns 手动退订函数（一般不需要调用，卸载时已自动处理）
 */
export function useWsTopic(type: string, handler: (data: any) => void): () => void {
  const wsStore = useWebSocketStore();
  const unsubscribe = wsStore.on(type, handler);
  if (getCurrentInstance()) onUnmounted(unsubscribe);
  return unsubscribe;
}

/**
 * 批量订阅，语义同 [useWsTopic]。
 *
 * @example
 * useWsTopics({
 *   cpu: d => dashboardStore.updateRealtime('cpu', d),
 *   memory: d => dashboardStore.updateRealtime('memory', d),
 * })
 */
export function useWsTopics(handlers: Record<string, (data: any) => void>): () => void {
  const wsStore = useWebSocketStore();
  const unsubscribes = Object.entries(handlers).map(([type, handler]) => wsStore.on(type, handler));
  const unsubscribeAll = () => unsubscribes.forEach((fn) => fn());
  if (getCurrentInstance()) onUnmounted(unsubscribeAll);
  return unsubscribeAll;
}

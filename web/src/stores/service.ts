import { defineStore } from 'pinia';
import { ref, computed } from 'vue';
import { getApiClient } from '@/composables/useApi';
import { toApiErrorMessage } from '@/composables/utils';
import { Endpoints } from '@/api/contract';

/**
 * 后端服务开关的全局状态（2026-09-03）。
 *
 * 为什么要提到全局：`POST /api/service/stop` 停的是 core 侧**除 HTTP 之外的全部自主活动**
 * （采集、告警、定时任务、短信转发、下载轮询、隧道看护）。此时各页面的 10s/5s/2s 轮询既拿不到
 * 新数据，也不该继续发 —— 所以布局层要据此把正文换成提示页（页面被卸载 ⇒ `useInterval`
 * 的 `onUnmounted(stop)` 自动收摊，不必逐个页面加门控）。
 *
 * 状态原来只活在 ServicePanel 的局部 reactive 里，离开设置页就丢。
 *
 * 轮询 `/api/service/status` 是安全的：它只读 AppSettings 开关 + DataScheduler 的内存标志，
 * 不碰 goform，也不会因缓存过期触发设备查询。
 */
const WATCH_INTERVAL_MS = 30_000;

export const useServiceStore = defineStore('service', () => {
  /** 是否成功读到过状态。false 时**按"运行中"处理**，避免网络抖一下整站变提示页。 */
  const loaded = ref(false);
  /** 后台服务开关（持久化真源在 core 的 AppSettings.backgroundServiceEnabled）。 */
  const enabled = ref(true);
  /** 采集循环实际是否在跑（停止后的 5s flush 尾巴期间可能与 enabled 短暂不一致）。 */
  const collecting = ref(false);
  const uptimeMs = ref(0);
  const autoStartOnBoot = ref(false);
  const busy = ref(false);
  const errorMessage = ref('');

  /** 「用户主动停掉了服务」——布局层据此替换正文。 */
  const stopped = computed(() => loaded.value && !enabled.value);

  // 定时器句柄不进 return（仿 stores/websocket.ts 的做法：非响应式内部状态用闭包变量）
  let timer: ReturnType<typeof setInterval> | null = null;

  async function refresh() {
    try {
      const { data } = await getApiClient().get(Endpoints.service.status);
      enabled.value = !!data.enabled;
      collecting.value = !!data.collecting;
      uptimeMs.value = Number(data.uptime_ms ?? 0);
      autoStartOnBoot.value = !!data.auto_start_on_boot;
      loaded.value = true;
      errorMessage.value = '';
    } catch (e) {
      // 读不到就维持上一次已知值；只有从未读到过时才保持 loaded=false（=按运行中处理）
      errorMessage.value = toApiErrorMessage(e, '服务状态读取失败');
    }
  }

  /** 开/停后台服务。无论成功失败都回读真实状态，不留乐观的假开关。 */
  async function setEnabled(value: boolean) {
    busy.value = true;
    try {
      await getApiClient().post(value ? Endpoints.service.start : Endpoints.service.stop);
      errorMessage.value = '';
    } catch (e) {
      errorMessage.value = toApiErrorMessage(e, '操作失败');
    } finally {
      await refresh();
      busy.value = false;
    }
  }

  async function setAutoStart(value: boolean) {
    try {
      await getApiClient().post(Endpoints.service.autostart, { enabled: value });
      autoStartOnBoot.value = value;
    } catch (e) {
      errorMessage.value = toApiErrorMessage(e, '设置失败');
      await refresh();
    }
  }

  /** 由 DefaultLayout 在挂载时启动：30s 一次的轻量状态巡检（另一端改了开关这边也能感知）。 */
  function startWatch() {
    if (timer) return;
    void refresh();
    timer = setInterval(() => void refresh(), WATCH_INTERVAL_MS);
  }

  function stopWatch() {
    if (timer) {
      clearInterval(timer);
      timer = null;
    }
  }

  return {
    loaded,
    enabled,
    collecting,
    uptimeMs,
    autoStartOnBoot,
    busy,
    errorMessage,
    stopped,
    refresh,
    setEnabled,
    setAutoStart,
    startWatch,
    stopWatch,
  };
});

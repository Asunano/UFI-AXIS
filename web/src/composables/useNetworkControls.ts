/**
 * 设备网络类写操作的唯一实现处。
 *
 * 为什么要有这个文件：这批 handler 原来全挤在 `views/network/NetworkView.vue` 里，
 * 仪表盘想加同样的快捷开关只能再抄一份。而抄一份必错 —— 每个写操作背后有三条
 * 「不写出来就一定会踩」的设备约定：
 *
 *   1. **写完不能立刻回读**。设备 goform 写入到查询接口能看到新值之间有延迟（~600ms），
 *      立刻回读拿到的是旧值，会把开关刷回改动前的状态。
 *   2. **移动数据 / 飞行模式 / 拨号要等更久**（2–3s）：settings 落盘后 ppp 状态才跟上。
 *   3. **HTTP 200 不等于成功**。core 有一批端点走「信封失败」：`{ success: false, error }`
 *      配 200 返回，只 catch 异常会把失败报成成功。
 *
 * ── 怎么加一个新的控制（这是本文件的扩展点）──
 *
 * 一律走 `runWrite()`，不要自己写 try/catch/toast：
 *
 *   const ledSaving = ref(false);
 *   const setLed = (enabled: boolean) =>
 *     runWrite({
 *       setBusy: (on) => (ledSaving.value = on),
 *       request: () => api.post('/api/device/led', { enabled }),
 *       ok: enabled ? '指示灯已开启' : '指示灯已关闭',
 *       after: { reload: loadDeviceSettings, always: true },
 *     });
 *
 * 然后在下面 return 里加一行导出即可。要删一个控制就删掉这两处，没有别的地方要同步。
 * `after` 那四个字段已经覆盖了目前设备侧全部的回读节奏，新控制基本只需要挑组合：
 *   · 普通开关       → `{ reload: 回读函数, always: true }`
 *   · ppp / 飞行模式 → `{ delayMs: 2000, reload: refreshNetworkStatus, detached: true }`
 *   · 只写不读       → 省略 `after`
 *
 * 本文件不碰任何布局，也不 import 任何组件：调用方自己决定用什么控件、放在哪张卡。
 */
import { ref, computed } from 'vue';
import { useMessage } from 'naive-ui';
import { useDashboardStore } from '@/stores/dashboard';
import { useCancellableApi } from '@/composables/useCancellableApi';
import { normalizeWifiSettings, normalizeWifiClients } from '@/composables/utils';
import { NetworkMode, NetworkModeOptions, BearerToNetworkMode } from '@/api/contract';
import type { WifiSettings, WifiClient, WifiAcl } from '@/types';

/** 设备 goform 写入到查询接口可见的延迟补偿。见文件头约定 1。 */
const SETTLE_MS = 600;
/** ppp 相关状态（移动数据 / 飞行模式）落盘后要更久才反映出来。见约定 2。 */
const PPP_SETTLE_MS = 2_000;
/** 拨号动作最慢，给 3s。 */
const DIAL_SETTLE_MS = 3_000;

/** 写完之后怎么回读。四个字段覆盖了目前全部节奏，加新控制时挑组合即可。 */
export interface AfterWrite {
  /** 等多久再回读，默认 SETTLE_MS */
  delayMs?: number;
  /** 回读动作 */
  reload?: () => unknown;
  /**
   * true = 成功失败都回读。
   * 用在「写请求报错但设备状态可能已经变了」的场景（漫游、拨号模式、WiFi 开关都是），
   * 不回读就会让 UI 停在一个设备上并不成立的状态。
   */
  always?: boolean;
  /**
   * true = 不 await，改用 setTimeout 挂到后台。
   * 给 2–3s 才生效的动作用：否则按钮的 loading 要转满 3 秒。
   */
  detached?: boolean;
}

interface RunWriteOptions<T> {
  /** 置位/复位 loading。布尔开关传 `(on) => flag.value = on`；按行 pending 传 `(on) => key.value = on ? mac : ''` */
  setBusy?: (on: boolean) => void;
  request: () => Promise<{ data: T }>;
  ok: string;
  fail?: string;
  after?: AfterWrite;
  /** 成功时额外处理响应体（如 ACL 用响应里的真实名单直接覆盖本地） */
  onOk?: (data: T) => void;
  /**
   * 判定「这次响应算成功吗」。默认 `data?.success !== false` ——
   * 见文件头约定 3：core 有一批端点用 200 + `success:false` 表示失败。
   * 没有 success 字段的端点不受影响（undefined !== false）。
   */
  okWhen?: (data: T) => boolean;
}

export function useNetworkControls() {
  const message = useMessage();
  const api = useCancellableApi();
  const dashboardStore = useDashboardStore();

  /** 写操作统一外壳：toast、loading、信封失败判定、回读节奏都只在这里实现一次。 */
  async function runWrite<T>(opts: RunWriteOptions<T>): Promise<boolean> {
    const { setBusy, request, ok, fail = '操作失败', after, onOk, okWhen } = opts;
    setBusy?.(true);

    let outcome: 'ok' | 'fail' | 'canceled' = 'fail';
    let data: T | undefined;
    /** 失败文案。能拿到 core 给的原因就用它 —— 一律只报「操作失败」等于没法排查。 */
    let failText = fail;

    try {
      const res = await request();
      // useCancellableApi 把「请求被取消」resolve 成 { __canceled: true, data: undefined }
      // 而不是抛错，所以必须显式识别：它既不是成功也不是失败。
      if ((res as any)?.__canceled === true) {
        outcome = 'canceled';
      } else {
        data = res.data;
        const good = okWhen ? okWhen(data as T) : (data as any)?.success !== false;
        outcome = good ? 'ok' : 'fail';
        // 信封失败时 core 会在 error 字段里说明原因
        if (!good) failText = (data as any)?.error || fail;
      }
    } catch (e: any) {
      outcome = 'fail';
      failText = e?.response?.data?.error || e?.message || fail;
    } finally {
      setBusy?.(false);
    }

    // 被取消（组件卸载 / 路由切走）视为无结果：不 toast、不回读。
    // 注意这个分支不能省 —— data 为 undefined 时 `undefined?.success !== false` 为真，
    // 会掉进成功分支弹一条假的成功提示。
    if (outcome === 'canceled') return false;

    if (outcome === 'ok') {
      // onOk 放在 try 之外：调用方回调里抛错不该被误报成「请求失败」
      onOk?.(data as T);
      message.success(ok);
    } else {
      message.error(failText);
    }

    const succeeded = outcome === 'ok';
    if (after?.reload && (succeeded || after.always)) {
      const wait = after.delayMs ?? SETTLE_MS;
      if (after.detached) {
        setTimeout(() => after.reload?.(), wait);
      } else {
        await new Promise((r) => setTimeout(r, wait));
        after.reload();
      }
    }
    return succeeded;
  }

  // ══════════════════════════════════════════════════════════
  // 读：设备设置（网络模式 / 漫游 / 拨号模式 / 休眠定时都在同一个端点里）
  // ══════════════════════════════════════════════════════════

  /**
   * 网络模式别名集与反向映射统一取自 contract（与 core NetworkMode.toBearer 同源）。
   * 提交的一定是别名，core 负责映射成 BearerPreference。
   */
  const networkModes = NetworkModeOptions;
  const selectedMode = ref<string>(NetworkMode.AUTO);
  const modeLoading = ref(false);
  const modeLabel = computed(
    () => networkModes.find((m) => m.value === selectedMode.value)?.label || selectedMode.value
  );

  const roamingEnabled = ref(false);
  const connectionMode = ref('auto');
  const connModeOptions = [
    { label: '自动拨号', value: 'auto' },
    { label: '手动拨号', value: 'manual' },
  ];

  /**
   * WiFi 休眠时间在 /api/device/settings 的 sleep_sysIdleTimeToSleep，不在 /api/wifi/settings。
   * **单位是分钟**（core 的 WriteSpec.validate 明写「非负整数（分钟）」）——
   * 曾按秒展示过，点一次「5 分钟」实际下发 300 分钟 = 5 小时。
   */
  const sleepTime = ref(0);
  const sleepLoading = ref(false);
  const sleepSaving = ref(false);

  /**
   * 一次请求把 /api/device/settings 里用到的字段全取出来。
   * 注意：网络模式/漫游在这个端点（BearerPreference / roam_setting_option / connection_mode），
   * 而不是 /api/device/info（后者只有 brand/model/android_version 等静态字段）。
   */
  async function loadDeviceSettings() {
    try {
      const { data } = await api.get('/api/device/settings');
      const raw = String(data?.BearerPreference ?? '');
      // 未知取值不静默显示成「自动」，直接透出原始值，避免误导
      selectedMode.value = BearerToNetworkMode[raw] || raw || 'AUTO';
      const roam = String(data?.roam_setting_option ?? data?.dial_roam_setting_option ?? '');
      roamingEnabled.value = roam === 'on' || roam === '1';
      const mode = String(data?.connection_mode ?? '').toLowerCase();
      connectionMode.value = ['manual', '1', 'hand'].includes(mode) ? 'manual' : 'auto';
      if (data?.sleep_sysIdleTimeToSleep != null) {
        sleepTime.value = Number(data.sleep_sysIdleTimeToSleep) || 0;
      }
    } catch {
      /* 保持默认 */
    }
  }

  /** 只为休眠弹窗单独回读一次（带自己的 loading） */
  async function loadSleepTimer() {
    sleepLoading.value = true;
    try {
      await loadDeviceSettings();
    } finally {
      sleepLoading.value = false;
    }
  }

  /** 网络状态写回 store：拨号 / 移动数据 / 飞行模式写完都要刷这个 */
  async function refreshNetworkStatus() {
    try {
      const { data } = await api.get('/api/network/status');
      if (dashboardStore.summary) {
        dashboardStore.summary.network_status = data;
      } else {
        dashboardStore.updateSummary({ network_status: data } as any);
      }
    } catch {
      /* 静默 */
    }
  }

  // ══════════════════════════════════════════════════════════
  // 写：连接与网络
  // ══════════════════════════════════════════════════════════

  const mobileDataSaving = ref(false);
  const roamingSaving = ref(false);
  const connModeSaving = ref(false);

  const setMobileData = (enabled: boolean) =>
    runWrite({
      setBusy: (on) => (mobileDataSaving.value = on),
      request: () => api.post('/api/network/data', { enabled }),
      ok: enabled ? '移动数据已开启' : '移动数据已关闭',
      after: { delayMs: PPP_SETTLE_MS, reload: refreshNetworkStatus, detached: true },
    });

  /** core /api/network/airplane 只有写、没有读，所以调用方要用两个明确动作而不是一个开关 */
  const setAirplane = (enabled: boolean) =>
    runWrite({
      request: () => api.post('/api/network/airplane', { enabled }),
      ok: enabled ? '飞行模式已开启' : '飞行模式已关闭',
      after: { delayMs: PPP_SETTLE_MS, reload: refreshNetworkStatus, detached: true },
    });

  const setRoaming = (enabled: boolean) =>
    runWrite({
      setBusy: (on) => (roamingSaving.value = on),
      request: () => api.post('/api/device/roaming', { enabled }),
      ok: enabled ? '数据漫游已开启' : '数据漫游已关闭',
      // core setRoaming 会顺手把 ConnectionMode 置为 auto_dial（GoformNetworkClient.kt:238-246），
      // 所以必须回读，不能就地假设状态
      after: { reload: loadDeviceSettings, always: true },
    });

  const setConnectionMode = (mode: string) =>
    runWrite({
      setBusy: (on) => (connModeSaving.value = on),
      // goform ConnectionMode 取值为 auto_dial / manual_dial，core 原样透传（GoformNetworkClient.kt:59-64）
      request: () =>
        api.post('/api/network/connection-mode', { mode: mode === 'manual' ? 'manual_dial' : 'auto_dial' }),
      ok: '拨号模式已切换',
      fail: '切换失败',
      after: { reload: loadDeviceSettings, always: true },
    });

  const pppConnect = () =>
    runWrite({
      request: () => api.post('/api/network/connect'),
      ok: '拨号中...',
      fail: '拨号失败',
      after: { delayMs: DIAL_SETTLE_MS, reload: refreshNetworkStatus, detached: true },
    });

  const pppDisconnect = () =>
    runWrite({
      request: () => api.post('/api/network/disconnect'),
      ok: '已断开',
      fail: '断开失败',
      // 断开在设备上不是立即生效，立刻回读还是「已连接」
      after: { reload: refreshNetworkStatus, always: true },
    });

  const applyNetworkMode = () =>
    runWrite({
      setBusy: (on) => (modeLoading.value = on),
      request: () => api.post('/api/network/mode', { mode: selectedMode.value }),
      ok: '网络模式已切换',
      fail: '切换失败',
      after: { reload: loadDeviceSettings, always: true },
    });

  const saveSleepTimer = () =>
    runWrite({
      setBusy: (on) => (sleepSaving.value = on),
      request: () => api.post('/api/wifi/sleep', { time: String(sleepTime.value) }),
      ok: '休眠定时器已保存',
      fail: '保存失败',
      // 写入响应只回显请求值，真实值要等设备把配置刷进 /api/device/settings
      onOk: (data: any) => {
        if (data?.time != null) sleepTime.value = Number(data.time) || 0;
      },
      after: { reload: loadSleepTimer, always: true },
    });

  // ══════════════════════════════════════════════════════════
  // WiFi
  // ══════════════════════════════════════════════════════════

  const wifiSettings = ref<WifiSettings | null>(null);
  const wifiClients = ref<WifiClient[]>([]);
  const wifiLoading = ref(false);
  const wifiEnabled = ref(true);

  async function loadWifiSettings() {
    wifiLoading.value = true;
    try {
      const { data } = await api.get('/api/wifi/settings');
      // core 返回 goform 原始扁平键，必须归一化后再用（否则 ssid / auth_mode 全是 undefined）
      const normalized = normalizeWifiSettings(data);
      wifiSettings.value = normalized;
      wifiEnabled.value = normalized.enabled;
    } catch {
      /* 静默 */
    } finally {
      wifiLoading.value = false;
    }
  }

  async function loadWifiClients() {
    try {
      const { data } = await api.get('/api/wifi/clients');
      // station_list（WiFi 侧）+ lan_station_list（LAN 侧）的合并与双形态解析在 normalizeWifiClients
      wifiClients.value = normalizeWifiClients(data);
    } catch {
      wifiClients.value = [];
    }
  }

  const toggleWifiEnabled = (enabled: boolean) =>
    runWrite({
      request: () => api.post('/api/wifi/enable', { enabled }),
      ok: enabled ? 'WiFi 已开启' : 'WiFi 已关闭',
      onOk: () => (wifiEnabled.value = enabled),
      // WiFi 模块开关在设备上要过一会儿才反映到查询接口，立刻回读会把开关刷回原样
      after: { reload: loadWifiSettings, always: true },
    });

  /**
   * WiFi 设置保存成功后的后续动作。
   * 二维码按当前 SSID/密码实时生成，配置一改旧图就是错的，所以丢弃缓存并视情况重拉。
   * [qrVisible] 由调用方给出（弹窗是否还开着），composable 不持有弹窗显隐。
   */
  async function onWifiSaved(qrVisible = false) {
    await new Promise((r) => setTimeout(r, SETTLE_MS));
    await loadWifiSettings();
    releaseQrUrl();
    if (qrVisible) loadQrCode();
  }

  // ── WiFi 二维码 ──
  // 不能直接 <img src="/api/wifi/qrcode">：img 请求不带 Authorization 头，会被 core 的
  // AuthMiddleware 拦掉；只能走注入 Bearer 的 axios 实例，把字节流转成 object URL 再喂给 <img>。
  const qrUrl = ref('');
  const qrLoading = ref(false);
  const qrError = ref('');

  /** object URL 会一直持有整个 blob，浏览器不会自动回收，必须显式 revoke 才不泄漏内存 */
  function releaseQrUrl() {
    if (qrUrl.value) URL.revokeObjectURL(qrUrl.value);
    qrUrl.value = '';
  }

  /**
   * [stillVisible] 用来判断「请求回来时弹窗还开着吗」：
   * 关掉后再建 URL 就没人 revoke 了。调用方传一个读当前显隐的函数进来。
   */
  async function loadQrCode(stillVisible: () => boolean = () => true) {
    releaseQrUrl();
    qrError.value = '';
    qrLoading.value = true;
    try {
      const chip = wifiSettings.value?.chip_index === '2' ? 'chip2' : 'chip1';
      const { data } = await api.get('/api/wifi/qrcode', {
        params: { chip, ssid_index: 1 },
        responseType: 'blob',
      });
      if (!stillVisible()) return;
      qrUrl.value = URL.createObjectURL(data as Blob);
    } catch {
      // 失败路径同样要清掉上一张已生成的 URL，否则它会随重试次数一直累积
      releaseQrUrl();
      qrError.value = '获取二维码失败，请重试';
    } finally {
      qrLoading.value = false;
    }
  }

  // ══════════════════════════════════════════════════════════
  // WiFi 接入控制（拉黑 / 解除）
  // ══════════════════════════════════════════════════════════
  //
  // 设备侧只有「整表替换」一条命令，读-改-写全在 core：这里只发单台设备的 mac/name，
  // 写完拿响应里 core 回读的真实名单直接覆盖，**不本地增删推算**
  // （否则会和 app 端、设备自带 UI 互相覆盖）。

  const wifiAcl = ref<WifiAcl | null>(null);
  /** 正在下发的 MAC（小写）。只给那一行的按钮转圈，不锁整页。清空操作用 '*'。 */
  const aclPending = ref('');
  const blockedList = computed(() => wifiAcl.value?.black_list ?? []);
  const blockedMacs = computed(() => new Set(blockedList.value.map((e) => e.mac.toLowerCase())));

  async function loadWifiAcl() {
    try {
      const { data } = await api.get('/api/wifi/acl');
      wifiAcl.value = data;
    } catch {
      /* 静默：读失败时保留上一份名单，避免面板闪成空表 */
    }
  }

  /** 三个 ACL 写操作的共同点：按 MAC 标 pending、用响应覆盖名单、顺带刷在线列表（拉黑会踢下线） */
  function writeAcl(pendingKey: string, request: () => Promise<{ data: WifiAcl }>, ok: string) {
    return runWrite<WifiAcl>({
      setBusy: (on) => (aclPending.value = on ? pendingKey : ''),
      request,
      ok,
      onOk: (data) => {
        wifiAcl.value = data;
        loadWifiClients();
      },
    });
  }

  function blockDevice(c: WifiClient) {
    if (!c.mac) return;
    writeAcl(
      c.mac.toLowerCase(),
      () => api.post('/api/wifi/acl/block', { mac: c.mac, name: c.hostname || '' }),
      '已拉黑，该设备已断开'
    );
  }

  function unblockDevice(mac: string) {
    writeAcl(mac.toLowerCase(), () => api.post('/api/wifi/acl/unblock', { mac }), '已解除拉黑');
  }

  function clearBlockedDevices() {
    writeAcl('*', () => api.post('/api/wifi/acl/clear'), '已全部解除');
  }

  return {
    // 扩展点：加新控制时用它，不要自己写 try/catch/toast
    runWrite,

    // 读
    loadDeviceSettings,
    loadSleepTimer,
    loadWifiSettings,
    loadWifiClients,
    loadWifiAcl,
    refreshNetworkStatus,

    // 网络模式
    networkModes,
    selectedMode,
    modeLoading,
    modeLabel,
    applyNetworkMode,

    // 连接
    roamingEnabled,
    roamingSaving,
    setRoaming,
    connectionMode,
    connModeOptions,
    connModeSaving,
    setConnectionMode,
    mobileDataSaving,
    setMobileData,
    setAirplane,
    pppConnect,
    pppDisconnect,

    // WiFi
    wifiSettings,
    wifiClients,
    wifiLoading,
    wifiEnabled,
    toggleWifiEnabled,
    onWifiSaved,
    sleepTime,
    sleepLoading,
    sleepSaving,
    saveSleepTimer,

    // 二维码
    qrUrl,
    qrLoading,
    qrError,
    loadQrCode,
    releaseQrUrl,

    // 接入控制
    blockedList,
    blockedMacs,
    aclPending,
    blockDevice,
    unblockDevice,
    clearBlockedDevices,
  };
}

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
 *   · ppp 拨号/断开  → `{ delayMs: DIAL_SETTLE_MS, reload: refreshNetworkStatus, detached: true }`
 *     （飞行模式已不在这一档：它要等重新注册，走 A 级 `ops.run()` 轮询确认，见下面两类操作）
 *   · 只写不读       → 省略 `after`
 *
 * 本文件不碰任何布局，也不 import 任何组件：调用方自己决定用什么控件、放在哪张卡。
 *
 * ── 两类操作，两种"状态放哪"（2026-10-14）──
 *
 *   · **B 级（普通开关）** 走 `runWrite()`：一次写 + 一次回读，秒级完成，
 *     状态位（`xxxSaving`）留在本 composable 的实例里是对的 —— 切页时那次写已经结束了。
 *   · **A 级（WiFi 总开关 / 移动数据 / 飞行模式 / 制式切换 / WiFi 频段 / 基站锁定·解锁）**
 *     走 `ops.run()`：下发后要**轮询回读**十几秒才知道设备有没有生效，这段"进行中"比页面活得久。
 *     所以状态位与执行器都在 `stores/networkOps`（由 DefaultLayout 挂载），
 *     本文件只负责"这次操作做什么"：标题文案、submit、verify。
 *
 *     2026-10-14：后两项（WiFi 频段、基站锁定）是本次补进来的 —— 它们原来在各自的弹窗里
 *     `POST` 完等 600ms 单读一次就渲染，而设备侧这两条命令都会重启 WiFi 模块 / 重新驻留小区，
 *     600ms 时界面拿到的还是旧值（详见 [switchWifiBand] 与 [lockCell] 的注释）。
 *
 * ⚠ A 级操作的 submit / verify 一律用 [opApi]（**不随页面卸载取消**）而不是 [api]。
 * 这是 2026-10-14 这次改造的关键一步：改前页面一卸载，`useCancellableApi` 就把在途请求
 * abort 成 `{ __canceled: true }`，回读被当成"没到达"，轮询空转到预算用尽 —— 于是"切页"
 * 会直接把一次正在进行的操作判死。现在这次操作的生命周期跟着 store 走，请求自然也不能被页面带走。
 */
import { ref, computed, onScopeDispose, toRef, watch } from 'vue';
import { useMessage } from 'naive-ui';
import type { AxiosInstance } from 'axios';
import { useDashboardStore } from '@/stores/dashboard';
import { useCancellableApi } from '@/composables/useCancellableApi';
import { getApiClient } from '@/composables/useApi';
import { normalizeWifiSettings, normalizeWifiClients } from '@/composables/utils';
import {
  NetworkMode,
  NetworkModeOptions,
  bearerToNetworkMode,
  cellFieldKey,
  cellInfoLockedCells,
  cellInfoServingCell,
  wifiBandFromChipIndex,
} from '@/api/contract';
import type { WifiBand } from '@/api/contract';
// 2026-10-05 P3 修复：A 级操作（WiFi 总开关 / 移动数据 / 飞行模式 / 制式切换）接入确认器。
// 2026-10-14：确认器的**状态**移到 stores/networkOps（切页不丢），这里只留"这次操作做什么"。
import { useNetworkOpsStore } from '@/stores/networkOps';
import type { WifiSettings, WifiClient, WifiAcl } from '@/types';

/** 设备 goform 写入到查询接口可见的延迟补偿。见文件头约定 1。 */
const SETTLE_MS = 600;
/** ppp 相关状态（移动数据 / 飞行模式）落盘后要更久才反映出来。见约定 2。 */
// 2026-10-05 P3 修复：这两类操作改走确认器轮询回读，固定 2s 延迟补偿不再需要
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
  /**
   * A 级操作的宿主（状态 + 执行器）。**每次调用 useNetworkControls() 拿到的是同一个 store**，
   * 这正是"两个页面共用一份进度"的实现方式（改前是各页一份实例级 ref，见文件头 2026-10-14）。
   */
  const ops = useNetworkOpsStore();
  /**
   * A 级操作的读写客户端：**不随页面卸载取消**。
   *
   * 为什么必须单独一个：这批操作的生命周期已经跟着 store 走了（切页要继续轮询回读），
   * 而 `useCancellableApi` 会在页面卸载时 abort 掉在途请求、把结果变成 `{ __canceled: true }`，
   * 回读于是恒判"没到达"。用它就等于"页面一走，这次操作自动判失败"——那正是本次要修的毛病。
   * B 级操作（runWrite）不受影响，继续用 [api]：那些写完就结束，切页时本来就该收摊。
   */
  const opApi = getApiClient();

  /**
   * detached 回读的 timer 句柄：卸载后这次回读没有意义，dispose 里统一清掉。
   *
   * 2026-10-14：这里原来还有一个 `disposed` 标志（"本页还在不在"），A 级操作的轮询循环
   * 靠它提前收摊。那批操作的状态与轮询已经搬到 `stores/networkOps`（由应用壳承载），
   * **页面卸载不再意味着这次操作作废**，于是这个标志没有读者了，删掉。
   * B 级写操作（runWrite）本来就不需要它：`useCancellableApi` 把卸载后的请求
   * resolve 成 `{ __canceled: true }`，runWrite 显式识别这个分支（不 toast、不回读）。
   */
  const detachedTimers = new Set<ReturnType<typeof setTimeout>>();
  onScopeDispose(() => {
    for (const t of detachedTimers) clearTimeout(t);
    detachedTimers.clear();
  });

  /**
   * 这次回读拿到的到底是不是「一份有内容的设备字段集合」。
   *
   * 为什么每次 verify 都要先问一句（2026-10-14）：A 级操作的判据都是从回读值里**取出一个位**
   * 再与目标比，而"取不到"与"取到了相反的值"在归一化之后长得一模一样 ——
   * `normalizeWifiSettings({})` 的 `enabled` 是 `false`、`chip_index` 是 `'1'`，
   * `cellInfoLockedCells({})` 是空表。于是一次读失败（200 + 空体、或设备只回了个错误信封）
   * 会被当成"设备已经是目标状态"，直接弹成功 —— 正是这一批修复要消灭的假开关。
   * core 在真读不到时通常回 5xx，这里兜的是剩下那条不报错的路径。
   */
  function readbackHasFields(data: unknown): boolean {
    return !!data && typeof data === 'object' && Object.keys(data as Record<string, unknown>).length > 0;
  }

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
        // 句柄登记进 detachedTimers：不登记的话卸载后这个 setTimeout 仍会醒来回读
        const timer = setTimeout(() => {
          detachedTimers.delete(timer);
          after.reload?.();
        }, wait);
        detachedTimers.add(timer);
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

  /**
   * **设备当前真实档位**（别名）。空串 = 还没读到。
   *
   * 与 [selectedMode] 是两个语义不同的值，2026-09-19 拆开：
   * 原来只有一个 `selectedMode`，而弹窗用 `v-model` 直接改它 —— 于是用户在弹窗里点一下
   * （还没应用、甚至点了取消），"更多设置"卡上的制式标签就跟着变了，显示的是一个
   * 设备上并不成立的档位。标签一律读这个，弹窗的待选项读 [selectedMode]。
   */
  const currentMode = ref('');
  /**
   * 设备当前档位的中文名，**取 core 下发的 `network_mode_label`**。
   *
   * core 的 `NetworkMode.LABELS` 是全仓唯一文案真源（`GET /api/device/settings` 会注入
   * 这个 key）。老固件的 core 不回它时回落到本地 [networkModes] 查表。
   */
  const currentModeLabel = ref('');

  /** 用户在弹窗里正要应用的档位。初值等设备读回来后由 [loadDeviceSettings] 同步。 */
  const selectedMode = ref<string>(NetworkMode.AUTO);
  const modeLoading = ref(false);
  /** 只为弹窗的「重新读取」按钮转圈 */
  const modeRefreshing = ref(false);

  /**
   * 制式标签（"更多设置"卡上那一行）。**读设备真值，不读待选项** —— 见 [currentMode] 的注释。
   *
   * 优先用 core 给的中文名；没有时按别名查本地表；两者都没有才显示原始值。
   */
  const modeLabel = computed(() => {
    if (currentModeLabel.value) return currentModeLabel.value;
    const alias = currentMode.value;
    if (!alias) return '--';
    return networkModes.find((m) => m.value === alias)?.label || alias;
  });

  /**
   * 正在切换中的**目标**制式；null = 没有进行中的切换。
   *
   * 它同时是 [loadDeviceSettings] 的闸门：切换期间设备仍报旧档位，回读值**不许**覆盖
   * [selectedMode]，否则界面会被弹回切换前的档位（2026-09-11 真机缺陷）。
   *
   * 2026-10-14：从本 composable 的实例级 ref 挪到 store（用 toRef 取回同一个 ref）。
   * "切换中"是一次 A 级操作的进行中状态，必须跨页可见 —— 改前在 A 页发起切换、切到 B 页，
   * B 页的闸门是 null，于是它的回读会把待选档位刷回旧值；现在闸门是全站一份。
   */
  const switchingMode = toRef(ops, 'switchingMode');
  /** 上一次切换在 `NetworkModeSwitchProbe` 的预算内没等到设备报出目标档位。同样跨页可见。 */
  const modeSwitchTimedOut = toRef(ops, 'modeSwitchTimedOut');

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
   * 注意：网络模式/漫游在这个端点（net_select / BearerPreference / roam_setting_option /
   * connection_mode），而不是 /api/device/info（后者只有 brand/model/android_version 等静态字段）。
   *
   * @param client 用哪个客户端读。默认 [api]（随页面取消）；A 级操作的轮询回读传 [opApi]
   *   —— 那次回读必须活过页面（见文件头 2026-10-14）。两者都是 AxiosInstance 的 `get` 子集。
   * @returns 这次读到的网络制式别名（读失败返回 null），供切换回读确认比对目标档位。
   */
  async function loadDeviceSettings(client: Pick<AxiosInstance, 'get'> = api): Promise<string | null> {
    try {
      const res = await client.get('/api/device/settings');
      // 请求被取消（组件卸载 / 路由切走）时 useCancellableApi resolve 成 { __canceled: true }，
      // data 为 undefined。不显式识别的话制式会被读成 ''、回读值塌成 'AUTO'：
      // 既会把选中项刷成"自动"，也会让切换回读把"没读到"误判成"已经到目标档位"。
      if ((res as { __canceled?: boolean })?.__canceled === true) return null;
      const data = res.data;
      if (data == null) return null;
      // 制式回读**以 net_select 为准**，BearerPreference 只作回落 —— 与 app
      // （DeviceSettingsResponse.networkMode）同一口径。原来只读 BearerPreference：
      // 真机切换后变的是 net_select，而 BearerPreference 缺失时这里会塌成 'AUTO'，
      // 于是 app 显示新档位、web 恒显示「5G/4G/3G」，回读确认也永远判超时。
      const raw = String(data?.net_select || data?.BearerPreference || '');
      // 未知取值不静默显示成「自动」，直接透出原始值，避免误导；空值也不当成 AUTO。
      const readback = bearerToNetworkMode(raw);
      if (readback) {
        // 设备真值一律更新（**不受切换闸门影响**）：标签读的是它，而切换期间也该能看到
        // 设备还停在旧档位这个事实。
        currentMode.value = readback;
        // 中文名优先用 core 注入的 network_mode_label（唯一文案真源）；老固件没有这个 key
        // 时清空，由 modeLabel 回落到本地查表。
        currentModeLabel.value = String(data?.network_mode_label || '');
      }
      // 待选项在切换期间**不许**被回读值覆盖：设备重新注册时报的还是旧档位，
      // 覆盖就会把弹窗里的选择弹回切换前的档位（这正是用户看到的"界面仍显示仅 5G"）。
      if (switchingMode.value === null && readback) selectedMode.value = readback;
      const roam = String(data?.roam_setting_option ?? data?.dial_roam_setting_option ?? '');
      roamingEnabled.value = roam === 'on' || roam === '1';
      const mode = String(data?.connection_mode ?? '').toLowerCase();
      connectionMode.value = ['manual', 'manual_dial', '1', 'hand'].includes(mode) ? 'manual' : 'auto';
      if (data?.sleep_sysIdleTimeToSleep != null) {
        sleepTime.value = Number(data.sleep_sysIdleTimeToSleep) || 0;
      }
      // 读到空串时返回 null（= 没读到），不要让回读确认把它当成一个档位去比对
      return readback || null;
    } catch {
      /* 保持默认 */
      return null;
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

  /**
   * 给「网络模式」弹窗的手动重新读取（带自己的 loading）。
   *
   * 注意它**绕不过设备端的读缓存**：`GET /api/device/settings` 在 core 侧的 TTL 是 5 分钟
   * （`CacheTTL.DEVICE_SETTINGS`），且只在 core 自己写入后才失效。从手机端或设备自带面板
   * 改过档位时，这里点几次也仍会拿到同一份快照 —— 弹窗里那句提示就是为了把这件事说清楚，
   * 而不是让用户以为按钮没生效。
   */
  async function refreshDeviceSettings() {
    modeRefreshing.value = true;
    try {
      await loadDeviceSettings();
    } finally {
      modeRefreshing.value = false;
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

  // 2026-10-05 P3 修复：移动数据接成 A 级——verify 判 ppp 连接态与目标一致（E-13：关数据轮询全失败时 connected 判 false 即命中）
  // 2026-10-14：状态与执行器移到 stores/networkOps；submit/verify 改用 opApi（切页不中断回读）
  const setMobileData = (enabled: boolean) =>
    ops.run({
      title: '移动数据',
      running: enabled ? '正在开启移动数据' : '正在关闭移动数据',
      success: enabled ? '移动数据已开启' : '移动数据已关闭',
      hint: '拨号状态更新需要几秒钟',
      submit: () => opApi.post('/api/network/data', { enabled }),
      verify: async () => {
        // /api/network/status 的连接态真源是 ppp_status（非空且非 disconnected），
        // 不是不存在的顶层 connected 字段——类型见 web/src/types 的 NetworkStatus
        const { data } = await opApi.get('/api/network/status');
        const ppp = String((data as any)?.ppp_status ?? '');
        const connected = !!ppp && !/disconnect/i.test(ppp);
        return connected === enabled;
      },
      // onApplied 里的"刷本地状态源"由下面 finishedSeq 的统一回读接管（见那里的说明）
    });

  /** core /api/network/airplane 只有写、没有读，所以调用方要用两个明确动作而不是一个开关 */
  // 2026-10-05 P3 修复：飞行模式同构接成 A 级（开启=断网；关闭=等重新连上）
  const setAirplane = (enabled: boolean) =>
    ops.run({
      title: '飞行模式',
      running: enabled ? '正在开启飞行模式' : '正在关闭飞行模式',
      success: enabled ? '飞行模式已开启' : '飞行模式已关闭',
      hint: '拨号状态更新需要几秒钟',
      submit: () => opApi.post('/api/network/airplane', { enabled }),
      verify: async () => {
        const { data } = await opApi.get('/api/network/status');
        const ppp = String((data as any)?.ppp_status ?? '');
        const connected = !!ppp && !/disconnect/i.test(ppp);
        return connected === !enabled;
      },
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
      /*
       * 断开在设备上不是立即生效：ppp 掉线要走完拆链流程，`SETTLE_MS`（600ms）那次回读
       * 拿到的**一定**还是「已连接」，界面于是停在错误状态直到下一轮 10s 轮询 —— 与
       * [pppConnect] 用 [DIAL_SETTLE_MS] 是同一条理由，两个方向必须对称。
       * detached 的理由同 [pppConnect]：不让按钮的 loading 白转满 3 秒。
       */
      after: { delayMs: DIAL_SETTLE_MS, reload: refreshNetworkStatus, detached: true, always: true },
    });

  /**
   * 切换网络制式（A 级）。
   *
   * 与其他写操作不同，这里**不用** `runWrite` 的 `after` 回读：`after` 只读一次，而设备
   * 在重新注册期间报的还是旧档位，读一次就渲染 = 界面停在切换前的档位。
   * 需要的是"下发 → 有上限地轮询回读 → 终态"。
   *
   * 2026-10-14：这段轮询原来由本文件的 [probeNetworkMode] 自己实现了一遍 —— 它和
   * `useApiOperation` 第 2 步用的是**同一套 contract 预算**（`NetworkModeSwitchProbe` +
   * `modeProbeIntervalMs` + `shouldKeepProbingMode`），两份实现迟早会分叉；
   * 而且它跑在页面作用域里，切页就停（`disposed` 一置真，回读恒判"没到达"，
   * 于是刚切走就显示"尚未完成"）。现在整条链交给 store 的确认器：
   * 预算仍是 contract 那一套，只是执行者换成了比页面活得久的那个。
   */
  const applyNetworkMode = async (): Promise<boolean> => {
    const target = selectedMode.value;
    /** 回读是否真的跑过：只有"跑过但没等到"才算"尚未完成"，下发被拒是另一回事（原因在弹窗里） */
    let probed = false;
    return ops.run({
      title: '网络模式',
      running: '正在切换网络模式',
      success: '网络模式已切换',
      hint: '设备重新搜网需要一点时间',
      onStart: () => {
        // 下发前就置"切换中"：这一整段（含 POST 本身）设备都还报着旧档位
        switchingMode.value = target;
        modeSwitchTimedOut.value = false;
        probed = false;
      },
      submit: async () => {
        // loading 只覆盖这一次 POST（与改前 runWrite 的 setBusy 同宽）：
        // 回读可能十几秒，让按钮转那么久没有意义，进度由进度弹窗呈现
        modeLoading.value = true;
        try {
          return await opApi.post('/api/network/mode', { mode: target });
        } finally {
          modeLoading.value = false;
        }
      },
      // 回读走 opApi：关掉弹窗、切到别的页面之后，这次确认还得继续问设备
      verify: async () => {
        probed = true;
        return (await loadDeviceSettings(opApi)) === target;
      },
      onSettled: (reached) => {
        // 闸门放开：成功时待选项等于 target，超时时由下面的统一回读把它拉回设备当前值
        switchingMode.value = null;
        modeSwitchTimedOut.value = probed && !reached;
      },
    });
  };

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

  // 2026-10-05 P3 修复：WiFi 总开关接成 A 级——模块重启级操作，回读命中前不给成功反馈
  // 2026-10-14：状态/执行器在 stores/networkOps（进度弹窗全仓只挂 DefaultLayout 一份），
  // 这里只剩"这次操作做什么"；回读用 opApi，切页不中断
  const toggleWifiEnabled = (enabled: boolean) =>
    ops.run({
      title: 'WiFi 开关',
      running: '正在切换 WiFi',
      success: enabled ? 'WiFi 已开启' : 'WiFi 已关闭',
      hint: 'WiFi 模块重启期间已连接设备会短暂掉线',
      submit: () => opApi.post('/api/wifi/enable', { enabled }),
      verify: async () => {
        // 复用 loadWifiSettings 的回读端点，但只关心 enabled 一位。
        // 注意这个端点有 120s 缓存（见 [switchWifiBand] 里那段 core 侧限制的说明）：
        // 第一次回读拿到什么，后面 12 次基本就是同一份。
        const { data } = await opApi.get('/api/wifi/settings');
        // 2026-10-14：先确认这次读有内容再比 —— 空体时 normalized.enabled 恒为 false，
        // 「关 WiFi」会被立刻判成已生效（见 readbackHasFields）
        if (!readbackHasFields(data)) return false;
        return normalizeWifiSettings(data).enabled === enabled;
      },
    });

  /**
   * 切换 WiFi 频段（A 级，2026-10-14 新增）。
   *
   * 为什么必须走确认器：`POST /api/wifi/band` 在设备侧就是
   * `switchWiFiChip&ChipEnum=X&GuestEnable=0`（`GoformWifiClient.kt:490-521`），
   * 语义是「在该频段上**启用** WiFi」—— 它会重启 WiFi 模块。
   * core 写成功只清了读缓存（`WifiRoutes.kt:199-208` 的 `invalidateWifi()`，所以回读确实打设备），
   * 但模块这时还没起来：改前 `WifiEditModal` 发完 `emit('saved')` → `onWifiSaved` 等 600ms
   * 单读一次就渲染，界面拿到的仍是旧频段，要等下一轮 10s 轮询才自己变对
   * ——「切了频段但界面没变」就是这么来的。
   *
   * 判据（**先归一再比**，同 [bearerToNetworkMode] 的做法）：
   *   回读的展示字段 `chip_index`（`'1'`/`'2'`）经 [wifiBandFromChipIndex] 翻成写侧词汇
   *   `chip1`/`chip2`，再与目标比 —— 两侧编码不同，直接比原始串永远不相等。
   *   `wifi_chip` 是设备的**生效频段**，模块在新频段上起来之前它不会变，所以它一命中
   *   就等于「设备已经在目标频段上服务了」，这正是这条命令要确认的事实。
   *
   * ⚠ 一处 core 侧的**已知限制**（不是本入口能修的，记在这里免得下次当成"轮询没生效"）：
   * `GET /api/wifi/settings` 走 `DataHub.getWifiSettingsMerged` 的 `ResponseCache`，
   * TTL 是 `CacheTTL.WIFI_SETTINGS` = **120s**（`ResponseCache.kt:548`），而写成功后 core 只
   * `invalidateWifi()` 一次。于是第一次回读（600ms）若是旧值，那份旧值会被**缓存 120s**
   * ——比本确认器的 30.6s 预算长得多，后面 12 次回读拿到的都是同一份缓存。
   * core 里已经有现成的正确做法（`WifiRoutes.kt:320-352` 的 `respondWifiConfigResult`：
   * 写完立刻回读时**刻意绕开 dataHub**，不把"可能还是旧值"的结果写进缓存）；
   * 这条读路径要彻底解决得在 core 侧补一个同样的旁路，web 侧无解。
   * 本入口仍然值得存在：命中判定、终态 toast、失败原因与统一回读都由它收敛，
   * 而不是像改前那样"发完就当成功"。
   *
   * ⚠ 判据里**刻意不含 SSID**（与最初设想不同，理由写在下面，别顺手加回来）：
   * 设备把每个 chip 的 AP 当**各自独立的一份配置**（`queryAccessPointInfo` 的 `ResponseList`
   * 里每个 chip 一个主 AP，各带自己的 `SSID`/`ChipIndex`，由 `AccessPointSwitchStatus` 选出生效那个
   * ——见 `ZteGoformProfile.liftActiveAccessPoint` 与 `ZteGoformProfileTest` 的夹具
   * `Idle2G` / `Live5G`）。而写侧固定发 `AccessPointIndex=0` + `ChipIndex=<当前生效 chip>`
   * （`ZteGoformProfile.kt:1293-1295` 的 encode，`chip_index` 取值来自写前回读的生效 AP），
   * 即**SSID 落在切换前那个 chip 的 AP 上**；切过去之后回读到的自然是目标 chip 那个 AP 自己的 SSID。
   * 把它当判据，会让「改了 SSID 又切频段」这一类**正常操作**每次都白等 30.6s 再报「未完成」
   * ——正是本次要消灭的那类假失败。若哪天确认真机上两个频段共享同一个 SSID，
   * 再把 SSID 比对加成判据也不迟（那时它才是"更强的证据"而不是"必然对不上"）。
   */
  const switchWifiBand = (target: WifiBand) =>
    ops.run({
      title: 'WiFi 频段',
      running: '正在切换 WiFi 频段',
      success: 'WiFi 频段已切换',
      hint: 'WiFi 模块重启期间已连接设备会短暂掉线',
      submit: () => opApi.post('/api/wifi/band', { chip: target }),
      verify: async () => {
        const { data } = await opApi.get('/api/wifi/settings');
        // 空体时 normalizeWifiSettings 会把 chip_index 塌成 '1'（= chip1）：
        // 目标是 chip1 的那次会被读失败误判成已生效 —— 先确认这次读有内容
        if (!readbackHasFields(data)) return false;
        return wifiBandFromChipIndex(normalizeWifiSettings(data).chip_index) === target;
      },
    });

  /**
   * A 级操作收敛后的统一回读（2026-10-14）。
   *
   * 为什么需要它：改前每个 A 级操作在自己的 `onApplied` 里刷本地状态源
   * （WiFi 开关刷 wifiSettings / wifiClients，移动数据与飞行模式刷 network_status）。
   * 那些回调捕获的是**发起它的那一页**的 ref —— 状态外置之后，一次操作完全可能
   * 由 A 页发起、在 B 页结束，回调就刷到了一个已经卸载的页面。
   *
   * 所以改成订阅 store 的终态计数器：**谁活着谁刷新**。
   * 一个页面同时只有一份 useNetworkControls，两个页面的实例各订一次，不会重复。
   * 计数器在成功、失败、超时三种终态上都会 +1（见 stores/networkOps 的 run），
   * 所以超时那一路也能把界面拉回设备的真实值。
   */
  watch(
    () => ops.finishedSeq,
    () => {
      loadWifiSettings();
      loadWifiClients();
      refreshNetworkStatus();
      loadDeviceSettings();
    }
  );

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
      const chip = wifiBandFromChipIndex(wifiSettings.value?.chip_index);
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

  // ══════════════════════════════════════════════════════════
  // 基站锁定（GET /api/network/cell-info 回读确认）
  // ══════════════════════════════════════════════════════════
  //
  // 2026-10-14：这两个写操作原来是 `CellInfoModal.vue` 里直发的 —— 写完等 600ms **单读一次、
  // 无判据**，界面停在旧小区直到下一次偶然刷新。而设备侧「锁定后重新驻留」要十几秒：
  // 600ms 时服务小区还是原来那个，锁定看起来"没生效"。现在统一走确认器。
  //
  // 契约来源（不是猜的）：`core/api/.../routes/DeviceRoutes.kt:618-671` 写明入参是
  // `pci` + `earfcn` + `network_type`（`LTE`|`NR`），写成功会清 `network:cell-info`
  // 与 `device:settings` 缓存（所以下面的回读确实打设备）；
  // 回读字段名见 `core/contract/.../DeviceFields.kt:245-268`（`Lte_pci` / `Lte_fcn` /
  // `locked_cell_info`，元素键 `pci` / `earfcn`）。
  // 回读端点自身有 20s 缓存（`CacheTTL.CELL_INFO`，`ResponseCache.kt:590`）：比 30.6s 预算短，
  // 所以预算内至少能真打设备两次（600ms 那次与 20s 后那次）。代价是**确认的分辨率就是这 20s**
  // ——设备 1 秒就驻留好了，界面也要等到 20s 后那次真读才命中。这是缓存粒度决定的，
  // 不是轮询写错了；要更快就得在 core 侧给这条读路径加一个"写后绕开缓存"的旁路
  // （同 `WifiRoutes.kt:320-352` 的 respondWifiConfigResult）。

  /**
   * 锁定一个邻区（A 级）。
   *
   * 判据：**服务小区的 pci + earfcn 同时等于目标** —— 锁定的目的就是让设备驻留到那个小区，
   * 而 `locked_cell_info` 里出现目标只说明命令被记下了，不代表已经驻留（那正是"受理≠生效"）。
   *
   * ⚠ NR 目标走一条**明确的兜底**：`/api/network/cell-info` 的服务小区只有 LTE 那一组字段
   * （`Lte_pci` / `Lte_fcn`；NR 的 `Nr_pci` 归 SIGNAL 分组，见 `DeviceFields.CellInfo` 与
   * `ServingCell.kt`），NSA 下锁 NR 小区时 LTE 锚点不变 —— 严格判据**永远**不可能命中，
   * 那会让每一次 5G 锁定都白等 30.6s 再报「未完成」。所以 NR 目标额外接受
   * 「设备已把目标记进 `locked_cell_info`」这一较弱但仍来自设备的证据。
   * 不确定处：真机上锁 NR 小区后 cell-info 的服务小区会不会变，本次没有真机可验。
   */
  const lockCell = (cell: { pci: string; earfcn: string; networkType: 'LTE' | 'NR' }) =>
    ops.run({
      title: '基站锁定',
      running: '正在锁定基站',
      success: '基站已锁定',
      hint: '设备重新驻留到目标小区需要一点时间',
      // network_type 的取值域由 core 校验（非法值 400 + 原因，见 DeviceRoutes.kt:640-644）
      submit: () =>
        opApi.post('/api/device/cell-lock', {
          pci: cell.pci,
          earfcn: cell.earfcn,
          network_type: cell.networkType,
        }),
      verify: async () => {
        const { data } = await opApi.get('/api/network/cell-info');
        if (!readbackHasFields(data)) return false;
        const targetPci = cellFieldKey(cell.pci);
        const targetEarfcn = cellFieldKey(cell.earfcn);
        const serving = cellInfoServingCell(data);
        // 服务小区读不到（''）时不算命中：那是"没读到"，不是"就是目标"
        if (serving.pci !== '' && serving.pci === targetPci && serving.earfcn === targetEarfcn) return true;
        return (
          cell.networkType === 'NR' &&
          cellInfoLockedCells(data).some((c) => c.pci === targetPci && c.earfcn === targetEarfcn)
        );
      },
    });

  /**
   * 全部解锁（A 级）。
   *
   * 判据：`locked_cell_info` 变成空表 —— 解锁没有"目标小区"可比，设备侧的终态就是锁定名单清空
   * （`POST /api/device/cell-unlock` → `unlockAllCell()`，`DeviceRoutes.kt:655-671`）。
   * 注意它**不是**「服务小区变了」：解锁后设备可能仍驻留在原小区（它本来就是个合法小区），
   * 拿服务小区当判据会永远判不中。
   */
  const unlockCells = () =>
    ops.run({
      title: '基站解锁',
      running: '正在解锁基站',
      success: '基站已全部解锁',
      hint: '设备重新选网需要一点时间',
      submit: () => opApi.post('/api/device/cell-unlock'),
      verify: async () => {
        const { data } = await opApi.get('/api/network/cell-info');
        if (!readbackHasFields(data)) return false;
        return cellInfoLockedCells(data).length === 0;
      },
    });

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
    // 设备真值 + core 给的中文名：制式标签一律读这两个，不读 selectedMode
    currentMode,
    currentModeLabel,
    modeLoading,
    modeRefreshing,
    refreshDeviceSettings,
    modeLabel,
    applyNetworkMode,
    // 「切换中」中间态：调用方据此显示"正在切换 / 尚未完成"，不要自己去猜回读值。
    // 2026-10-14：两个都是 store 里的 ref（本页只是转接），所以两个页面看到的是同一份状态。
    switchingMode,
    modeSwitchTimedOut,
    // 2026-10-14：原来这里还导出 wifiOp（useApiOperation 实例）与 modeOpState（进度状态位），
    // 供各页自己挂一份 OperationProgressModal。进度状态与弹窗现在统一在
    // stores/networkOps + DefaultLayout，页面不再需要它们 —— 删掉是为了让"只能有一份"
    // 这件事在类型上就无法绕过（没有第二个入口可以挂第二份弹窗）。

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
    // 2026-10-14 新增：切频段（模块重启级）必须走确认器，见 [switchWifiBand]
    switchWifiBand,
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

    // 基站锁定（2026-10-14 新增：原来是弹窗里 600ms 单次回读）
    lockCell,
    unlockCells,
  };
}

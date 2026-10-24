/**
 * 设备能力门禁（2026-10-14）。
 *
 * ──── 为什么要有这个 store ────
 * core 的每个设备插件在装配时就声明了自己支持哪些能力（`Capability`），
 * `GET /api/device/capabilities` 把当前插件声明的那几项以 wire 名数组下发。
 * **app 侧一直在用**（`DeviceControlScreen` 按 `Capability.PERFORMANCE_MODE` 门禁、
 * `NetworkScreen` 按 `BAND_LOCK`、`SmsScreen` 按 `SMS`…共 57 处），web 此前完全没用，
 * 于是会把设备根本不支持的开关照常画出来 —— 用户点下去只会失败，而且失败原因
 * （"这台设备没有这个能力"）在界面上永远说不出来。
 *
 * 能力集**不是**每页都要拉一次的数据：它在组件图装配时就定死了，一次会话内不会变
 * （core 侧注释：「不发任何设备查询：取值在装配组件图时就定死了」）。
 * 所以在应用壳里拉一次、各页读同一个 store —— 三个页面各拉一次只会得到三份可能
 * 不一致的快照（其中一份是加载失败的空集），而空集在门禁里的含义是"全都不支持"。
 *
 * ──── 失效方向：**失败一律放行** ────
 * 这是本模块最重要的决定。三种"不知道"的情况：
 *   ① 还没加载完（首屏那几个请求和它并行）；
 *   ② 请求失败（旧 core 没这个端点 / 网络抖动）；
 *   ③ 服务端下发了我们还不认识的 wire（旧客户端遇到新能力）。
 * 三种都**当成"支持"**，界面照常渲染。理由：
 *   · 门禁的作用是"别让用户点了才知道不行"，它是**体验优化**，不是安全边界 ——
 *     真正的拒绝在 core（`respondRejected`）那一层，绕过界面也越不过去；
 *   · 反过来（失败即全部禁用）的后果严重得多：一次网络抖动会让整页开关集体变灰，
 *     用户完全没法用，而且看不出为什么。
 * 所以判断用 [supports]（fail-open），**不要**用 `!unsupported()` 之外的写法。
 * 需要"明确不支持"时才用 [unsupported]（它要求真的加载成功且确实不在集合里）。
 *
 * ──── 为什么不用 map 形状 ────
 * core 刻意回**数组**而不是 `{sms: true}`：map 让客户端分不出"这是个我不认识的新能力"
 * 还是"设备不支持"，而这两者的界面行为完全相反（前者放行、后者禁用）。
 * 客户端这边同理，一律 `list.includes(wire)`。
 */
import { defineStore } from 'pinia';
import { computed, ref, shallowRef } from 'vue';
import { getApiClient } from '@/composables/useApi';
import { Endpoints, type CapabilityWire } from '@/api/contract';

/** `GET /api/device/capabilities` 的响应。 */
interface CapabilitiesResponse {
  plugin_id?: string;
  capabilities?: string[];
}

/**
 * 能力的中文名与"不支持时怎么说"。
 *
 * 只有**要拿它门禁界面**的那几项才需要在这里登记；没登记的项 [hintOf] 会退回一句通用文案。
 * `hint` 必须说清"下一步"：只说「不支持」用户会以为是坏了，说「该机型没有此能力」
 * 才是如实 —— 这不是故障，是硬件事实。
 */
const CAPABILITY_META: Record<string, { label: string; hint: string }> = {
  sms: { label: '短信', hint: '该机型未声明短信能力，短信相关操作不可用' },
  sim_slot_switch: { label: '双卡切换', hint: '该机型未声明双卡切换能力' },
  band_lock: { label: '锁频段', hint: '该机型未声明锁频段能力' },
  cell_lock: { label: '锁小区', hint: '该机型未声明锁小区能力' },
  network_mode: { label: '网络模式', hint: '该机型未声明网络模式切换能力' },
  samba: { label: 'Samba 共享', hint: '该机型未声明 Samba 能力' },
  usb_debug: { label: 'USB 调试', hint: '该机型未声明 USB 调试能力' },
  fota: { label: 'FOTA 升级', hint: '该机型未声明 FOTA 能力' },
  performance_mode: { label: '性能模式', hint: '该机型未声明性能模式能力' },
  traffic_limit: { label: '流量限额', hint: '该机型未声明流量限额能力' },
  battery: { label: '电池', hint: '该机型未声明电池能力（可能无电池或检测不到）' },
};

export const useCapabilitiesStore = defineStore('capabilities', () => {
  /** 已声明的能力 wire 名。**空数组 + `loaded=false` 才是"不知道"**。 */
  const list = shallowRef<string[]>([]);
  /** 是否成功拿到过一次真实答案。false 时所有门禁一律放行（见文件头）。 */
  const loaded = ref(false);
  const loading = ref(false);
  /** 设备插件 id，与 `/api/diagnose` 的 `device_profile.plugin_id` 同源同值。 */
  const pluginId = ref('');

  /** 取数用非取消版客户端：应用壳卸载时它不该被 abort（与 player store 同一理由）。 */
  const api = getApiClient();
  /** 代数守卫：切服务器 / 重新登录后旧响应必须丢掉。 */
  let seq = 0;

  /**
   * 拉一次能力集。
   *
   * 幂等：已经成功加载过就直接返回，除非 `force`。应用壳挂载时调一次即可。
   * 失败**静默**：能力集拿不到不是用户能处理的事，弹 toast 只会让人以为出故障了 ——
   * 而且失败时门禁本来就全放行，界面上没有任何东西被挡住。
   */
  async function load(force = false): Promise<void> {
    if (loading.value) return;
    if (loaded.value && !force) return;
    const my = ++seq;
    loading.value = true;
    try {
      const { data } = await api.get<CapabilitiesResponse>(Endpoints.device.capabilities);
      if (my !== seq) return;
      list.value = Array.isArray(data?.capabilities) ? data.capabilities.filter((x) => typeof x === 'string') : [];
      pluginId.value = data?.plugin_id ?? '';
      loaded.value = true;
    } catch {
      // 旧 core 没有这个端点（404）或网络抖动：保持 loaded=false ⇒ 全部放行
    } finally {
      if (my === seq) loading.value = false;
    }
  }

  /**
   * 能不能用这一项。**fail-open**：没加载成功过一律回 true（见文件头）。
   *
   * 传入未登记的 wire 也可以（比如将来 core 加了新能力而我们还没写 meta）。
   */
  function supports(wire: string): boolean {
    if (!loaded.value) return true;
    return list.value.includes(wire);
  }

  /**
   * 是否**明确**不支持 —— 只有它适合用来禁用控件 / 显示提示。
   *
   * 与 `!supports()` 的区别：加载失败时 `supports()` 回 true 而这里回 false，
   * 所以 `!supports()` 会在"不知道"时禁用，那正是文件头说的那种集体变灰。
   */
  function unsupported(wire: string): boolean {
    return loaded.value && !list.value.includes(wire);
  }

  /** 这一项的展示名。没登记就回 wire 原文（宁可难看也别显示空）。 */
  function labelOf(wire: string): string {
    return CAPABILITY_META[wire]?.label ?? wire;
  }

  /** 不支持时的说明文案，用于 tooltip / 灰字提示。 */
  function hintOf(wire: string): string {
    return CAPABILITY_META[wire]?.hint ?? `该机型未声明「${labelOf(wire)}」能力`;
  }

  /** 至少要有一项支持（用来决定整块卡片要不要画）。 */
  function anySupported(wires: readonly CapabilityWire[]): boolean {
    return wires.some((w) => supports(w));
  }

  /**
   * 清空。切换服务器 / 退出登录时调用 —— 换一台设备后旧能力集是**错的**，
   * 留着会让新设备被上一台的声明门禁掉。
   */
  function reset() {
    seq++;
    list.value = [];
    pluginId.value = '';
    loaded.value = false;
    loading.value = false;
  }

  /** 界面提示用：有没有拿到过真实答案。 */
  const known = computed(() => loaded.value);

  return { list, loaded, loading, pluginId, known, load, supports, unsupported, labelOf, hintOf, anySupported, reset };
});

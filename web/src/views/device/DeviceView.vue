<template>
  <div class="device-page">
    <!--
      #/device 布局（2026-11-11 按 docs/mockups/device-page-layout-proposal-2026-11-11.html 重排）。

      与原版的差别只在**结构与容器**，读数来源一个没改：
        行 1 = 设备身份 ｜ 硬件状态   （原来 SIM / 电池 / 温度传感器三张卡）
        行 2 = 诊断指标（通栏一条）   （原来「连接统计」卡 + 「承载 QoS」通栏卡）
        行 3 = 设备控制（通栏，3/2/1 列）
        行 4 = 更多（页面级列表）      （原来「应用管理」通栏卡只装一行）

      为什么要合：连接计数与承载参数都是诊断量，原来各占一张卡 —— 承载 QoS 一条通栏
      只放 3 个键值对（标签与数值相距 356px），连接统计在 390 视口退化成 5 个满宽瓦片
      （500px，整页 22%）。合起来后同一批数字占一条带子，窄屏只多两行。

      「更多」不再用卡片：卡片是容器，为一个入口包一层容器不表达任何分组关系。
    -->
    <div class="page-actions">
      <!-- 页面级唯一的刷新：原来 SIM / 电池 / QoS 各有一个「刷新」，动作相同却看起来范围不同 -->
      <button type="button" class="refresh-all" :disabled="refreshing" @click="refreshAll">
        <span v-if="refreshing" class="refresh-dot" aria-hidden="true" />
        {{ refreshing ? '刷新中' : '刷新' }}
      </button>
    </div>

    <div class="device-grid">
      <!-- 行 1 左：设备身份（SIM 状态 + 本机号码做摘要，三个号码做明细） -->
      <GridCard title="设备身份" :loading="simCardState === 'loading'">
        <div v-if="simCardState === 'failed'" class="state-empty">
          设备信息读取失败
          <button type="button" class="state-retry" @click="refreshSummary">重试</button>
        </div>
        <div v-else>
          <div class="id-summary">
            <div class="id-cell">
              <span class="id-k">SIM 状态</span>
              <span class="id-state" :class="{ ok: simStateOk }">{{ simState }}</span>
            </div>
            <div class="id-cell">
              <span class="id-k">本机号码</span>
              <span class="id-v">{{ deviceText('identity.msisdn') }}</span>
            </div>
          </div>
          <InfoRow class="id-row-first" label="IMEI" :value="deviceText('identity.imei')" />
          <InfoRow label="IMSI" :value="deviceText('identity.imsi')" />
          <InfoRow label="ICCID" :value="deviceText('identity.iccid')" />
        </div>
      </GridCard>

      <!-- 行 1 右：硬件状态（电量/温度升为大数字，电源/电压降为行，传感器入口留在行末） -->
      <GridCard title="硬件状态" :loading="batteryCardState === 'loading'">
        <!-- 充放电状态放卡头：它回答「现在是在充还是在放」，与下面那行「电源来源」不是同一件事 -->
        <template #extra>
          <span class="hw-hint">{{ batteryStatus }}</span>
        </template>
        <div v-if="batteryCardState === 'failed'" class="state-empty">
          设备信息读取失败
          <button type="button" class="state-retry" @click="refreshSummary">重试</button>
        </div>
        <div v-else>
          <div class="metrics">
            <div class="metric">
              <span class="metric-k">
                电量
                <!-- core 的 battery.supported=false = 这个型号没声明电池能力：
                     读数照常显示，但必须说明可信度（F50 会恒报系统给的 50%） -->
                <span
                  v-if="batterySupported === false"
                  class="metric-note"
                  title="该机型可能没有电池，读数是系统占位值"
                >
                  读数可能不准
                </span>
              </span>
              <span class="metric-v"> {{ batteryPercent ?? '--' }}<small v-if="batteryPercent != null">%</small> </span>
            </div>
            <div class="metric">
              <span class="metric-k">温度</span>
              <span class="metric-v"> {{ batteryTemp ?? '--' }}<small v-if="batteryTemp != null">°C</small> </span>
            </div>
          </div>
          <InfoRow label="电源" :value="batteryPlugged" />
          <InfoRow label="电压" :value="batteryVoltageText" />
          <div class="thermal-line">
            <span class="thermal-text">{{ thermalSummaryText }}</span>
            <button type="button" class="thermal-link" @click="showThermal = true">查看详情 ›</button>
          </div>
        </div>
      </GridCard>

      <!-- 行 2：诊断指标（连接计数 + 承载参数） -->
      <DiagnosticStrip
        class="full-width"
        :connections="connections"
        :qos-qci="qosText(qos.qci)"
        :qos-down="qosText(qos.downlinkDisplay)"
        :qos-up="qosText(qos.uplinkDisplay)"
        :failed-text="diagFailedText"
        @retry="retryDiagnostics"
      />

      <!--
        行 3：设备控制 —— 六项平铺（用户 2026-10-14 要求从弹窗搬回页面）。
        写入逻辑一字未改：成功后乐观置位 → 等 600ms → 静默回读（见 postToggle）。
        变化只在版式：三列紧凑行（ToggleRow inline），标签与开关的视线行程收在一列内。
      -->
      <GridCard class="full-width" title="设备控制" :loading="controlLoading">
        <div v-if="controlFailed" class="state-empty">
          设备设置读取失败，开关状态可能不是最新的
          <button type="button" class="state-retry" @click="loadControlSettings()">重试</button>
        </div>
        <div class="ctl">
          <div class="ctl-cell">
            <ToggleRow
              inline
              label="LED 指示灯"
              description="控制设备前面板 LED 指示灯的开关"
              :model-value="controlSettings.ledEnabled"
              :loading="toggleLoading.led"
              @update:model-value="(v: boolean) => postToggle('/api/device/led', v, 'led', 'ledEnabled')"
            />
          </div>
          <!--
            能力门禁（2026-10-14）：性能模式 / Samba / FOTA 三项按设备声明的 Capability 门禁。
            ① 判据一律 caps.unsupported(wire)；② 置灰必须给出原因（tooltip 包整行）；
            ③ tooltip 的 trigger 直接是 ToggleRow 根节点，悬停行内任意位置都能出提示。
          -->
          <div class="ctl-cell">
            <n-tooltip :disabled="!performanceUnsupported" :title="performanceHint">
              <template #trigger>
                <ToggleRow
                  inline
                  label="性能模式"
                  description="启用高性能模式，提升处理器运行频率"
                  :model-value="controlSettings.performanceEnabled"
                  :loading="toggleLoading.performance"
                  :disabled="performanceUnsupported"
                  @update:model-value="
                    (v: boolean) => postToggle('/api/device/performance', v, 'performance', 'performanceEnabled')
                  "
                />
              </template>
            </n-tooltip>
          </div>
          <div class="ctl-cell">
            <n-tooltip :disabled="!sambaUnsupported" :title="sambaHint">
              <template #trigger>
                <ToggleRow
                  inline
                  label="Samba 共享"
                  description="启用 Samba 文件共享服务，允许局域网访问存储"
                  :model-value="controlSettings.sambaEnabled"
                  :loading="toggleLoading.samba"
                  :disabled="sambaUnsupported"
                  @update:model-value="(v: boolean) => postToggle('/api/device/samba', v, 'samba', 'sambaEnabled')"
                />
              </template>
            </n-tooltip>
          </div>
          <div class="ctl-cell">
            <ToggleRow
              inline
              label="USB 端口"
              description="对应设备 usb_port_switch，关闭后 USB 数据口停用"
              :model-value="controlSettings.usbPortEnabled"
              :loading="toggleLoading.usbPort"
              @update:model-value="(v: boolean) => postToggle('/api/device/debug', v, 'usbPort', 'usbPortEnabled')"
            />
          </div>
          <div class="ctl-cell">
            <ToggleRow
              inline
              label="定时重启"
              description="设置每日自动重启时间，保持系统稳定运行"
              :model-value="controlSettings.restartScheduleEnabled"
              :loading="toggleLoading.restartSchedule"
              @update:model-value="(v: boolean) => postRestartSchedule(v)"
            />
          </div>
          <div class="ctl-cell">
            <n-tooltip :disabled="!fotaUnsupported" :title="fotaHint">
              <template #trigger>
                <ToggleRow
                  inline
                  label="FOTA 自动检查更新"
                  description="开启后设备按运营商策略自动检查并下载固件更新（UpgMode）"
                  :model-value="controlSettings.fotaAutoUpdate"
                  :loading="toggleLoading.fota"
                  :disabled="fotaUnsupported"
                  @update:model-value="(v: boolean) => setFotaAutoUpdate(v)"
                />
              </template>
            </n-tooltip>
          </div>
        </div>
        <!-- 定时重启时间：只在开启时出现（条件与原版一致） -->
        <div v-if="controlSettings.restartScheduleEnabled" class="restart-time">
          <label>每日重启时间</label>
          <n-input
            v-model:value="controlSettings.restartScheduleTime"
            class="restart-input"
            placeholder="HH:MM"
            size="small"
            @blur="postRestartScheduleTime"
          />
          <span class="restart-hint">格式 HH:MM，失焦即保存</span>
        </div>
      </GridCard>

      <!-- 行 4：更多（页面级列表，不再包一张只装一行的卡） -->
      <section class="more">
        <h2 class="more-title">更多</h2>
        <button type="button" class="entry" @click="router.push({ name: 'apps' })">
          <span class="entry-ico" aria-hidden="true">
            <svg
              viewBox="0 0 24 24"
              width="16"
              height="16"
              fill="none"
              stroke="currentColor"
              stroke-width="1.8"
              stroke-linecap="round"
              stroke-linejoin="round"
            >
              <rect x="3.5" y="3.5" width="7" height="7" rx="1.6" />
              <rect x="13.5" y="3.5" width="7" height="7" rx="1.6" />
              <rect x="3.5" y="13.5" width="7" height="7" rx="1.6" />
              <rect x="13.5" y="13.5" width="7" height="7" rx="1.6" />
            </svg>
          </span>
          <span class="entry-text">
            <b>应用管理</b>
            <span>查看 / 安装 / 卸载设备上的应用包</span>
          </span>
          <span class="entry-chev" aria-hidden="true">›</span>
        </button>
      </section>
    </div>

    <!-- 温度明细弹窗（本页唯一保留的弹窗；其余入口已按用户要求移出） -->
    <ThermalModal v-model:show="showThermal" />
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted } from 'vue';
import { useMessage } from 'naive-ui';
import { useRouter } from 'vue-router';
import { useInterval } from '@/composables/useRealtime';
import { useDashboardStore } from '@/stores/dashboard';
import { useCapabilitiesStore } from '@/stores/capabilities';
import { useCancellableApi } from '@/composables/useCancellableApi';
import { get } from '@/composables/utils';
import { Capability, Endpoints } from '@/api/contract';
import GridCard from '@/components/GridCard.vue';
import InfoRow from '@/components/InfoRow.vue';
import ToggleRow from '@/components/ToggleRow.vue';

import DiagnosticStrip from './components/DiagnosticStrip.vue';
import ThermalModal from './components/modals/ThermalModal.vue';
import { formatThermalTemp, normalizeThermalZones, type ThermalZone } from './useThermal';

const dashboardStore = useDashboardStore();
// 能力集在应用壳（DefaultLayout）里已经拉过一次；各页只读同一个 store，
// 自己再拉一份会得到可能不一致的第二份快照（见 stores/capabilities.ts 文件头）。
const caps = useCapabilitiesStore();
const router = useRouter();
const api = useCancellableApi();
const message = useMessage();

const showThermal = ref(false);

// ── SIM / 电池：都来自 dashboard summary ──
/** 最近一次 summary 补拉是否失败 —— 失败且 store 里也没有快照时，卡片必须说「读失败」而不是一直转圈。 */
const summaryFailed = ref(false);

async function refreshSummary() {
  try {
    const { data } = await api.get('/api/dashboard/summary');
    dashboardStore.updateSummary(data);
    summaryFailed.value = false;
  } catch {
    // 不再全静默：布局层会继续周期刷新，但本页两张卡要能说明「这次没读到」
    summaryFailed.value = true;
  }
}

const deviceInfo = computed(() => get(dashboardStore.summary, 'device_info', null));
const batteryInfo = computed(() => get(dashboardStore.summary, 'battery', null));

/**
 * SIM / 电池两张卡的三态判定。
 *
 * `loading` = summary 还没到（骨架）；`failed` = summary 拿不到，或 summary 到了但
 * 这一段是 null（core 侧 goform 超时会静默返回 null）；`ready` = 有数据可渲染。
 */
function cardState(info: unknown): 'loading' | 'failed' | 'ready' {
  if (!dashboardStore.summary) return summaryFailed.value ? 'failed' : 'loading';
  return info == null ? 'failed' : 'ready';
}
const simCardState = computed(() => cardState(deviceInfo.value));
const batteryCardState = computed(() => cardState(batteryInfo.value));

/**
 * core 如实转述的「本型号是否声明了电池能力」（`battery.supported`）。
 *
 * `undefined` = 老版本 core 没这个键 —— 此时**什么都不提示**，不拿默认值冒充真值
 * （与 app 侧 `thumbPrewarm` 那条约定同一个口径）。
 */
const batterySupported = computed(() => get(batteryInfo.value, 'supported', undefined));

const simState = computed(() => {
  const sim = deviceInfo.value?.sim;
  if (typeof sim === 'string') return sim;
  return sim?.sim_state || '--';
});

/** `Ready` 才点亮状态点；其它取值（PIN 锁定 / 未插卡 / 未知）保持中性灰，不假装正常。 */
const simStateOk = computed(() => simState.value.toLowerCase() === 'ready');

/**
 * goform 对「未写入」的字段返回空串而不是缺字段（例如 SIM 未写 MSISDN），
 * 而 get() 只在 undefined 时兜 fallback，空串会被原样渲染成一个空白格。
 * 这里统一把 null / undefined / 空串都显示为 `--`。
 */
function deviceText(path: string): string {
  const v = get(deviceInfo.value, path, '');
  return v === null || v === undefined || String(v).trim() === '' ? '--' : String(v);
}

// core SystemCollector.getBatteryInfo 返回 level/scale/percent/temperature/voltage/is_charging/plugged，
// 没有 status / technology 字段
const batteryStatus = computed(() => {
  const b = batteryInfo.value;
  if (!b || b.is_charging == null) return '--';
  return b.is_charging ? '充电中' : '放电中';
});

/**
 * 电源来源的中文映射。
 *
 * `plugged` 是 core 拼出来的**字符串枚举**（`AC` / `USB` / `Wireless` / `None`），
 * 不是布尔：所以不能用 `|| '--'` 判空（那样 `None` 会原样上屏英文，而缺值又会被
 * `false` 这类合法值糊掉）。判空统一 `!= null`，取值走这张表。
 */
const BATTERY_PLUGGED_LABELS: Record<string, string> = {
  AC: '交流电源',
  USB: 'USB 供电',
  Wireless: '无线充电',
  None: '未接通',
};
const batteryPlugged = computed(() => {
  const p = batteryInfo.value?.plugged;
  if (p == null || String(p).trim() === '') return '--';
  const key = String(p);
  return BATTERY_PLUGGED_LABELS[key] ?? key;
});

/**
 * 大数字只渲染**有效读数**：`null` 时模板显示 `--` 且不拼单位。
 *
 * 口径与原版一致（`!= null && >= 0`）：core 对温度/电压的哨兵值是 `-1.0`，
 * 真值判断会把 `-1` 当成有值渲染成「-1°C / -0.00V」——一个看着像故障的假读数。
 */
const batteryPercent = computed<number | null>(() => {
  const v = batteryInfo.value?.percent;
  return typeof v === 'number' && v >= 0 ? v : null;
});
const batteryTemp = computed<number | null>(() => {
  const v = batteryInfo.value?.temperature;
  return typeof v === 'number' && v >= 0 ? v : null;
});
const batteryVoltageText = computed(() => {
  const v = batteryInfo.value?.voltage;
  return typeof v === 'number' && v >= 0 ? `${v.toFixed(2)} V` : '--';
});

// ── 连接统计 ──
/** 最近一次读取是否失败（读数保留，卡片上另给一句说明）。 */
const connectionsFailed = ref(false);
// core SystemCollector.getConnectionCounts 返回 tcp / tcp6 / udp / udp6 / unix，IPv6 两项之前没展示
const connections = reactive<Record<'tcp' | 'tcp6' | 'udp' | 'udp6' | 'unix', number | null>>({
  tcp: null,
  tcp6: null,
  udp: null,
  udp6: null,
  unix: null,
});
async function loadConnections() {
  try {
    const { data } = await api.get('/api/device/connections');
    connections.tcp = data.tcp ?? null;
    connections.tcp6 = data.tcp6 ?? null;
    connections.udp = data.udp ?? null;
    connections.udp6 = data.udp6 ?? null;
    connections.unix = data.unix ?? null;
    connectionsFailed.value = false;
  } catch {
    // 读数保留上一次的（10s 轮询里一次抖动不该清空整张卡），但要把失败标出来
    connectionsFailed.value = true;
  }
}

// ── 温度传感器：页面上只留摘要（最高温 + 传感器数），明细在弹窗里 ──
// 名称映射 / 缺值口径 / 三档颜色统一走 useThermal.ts（弹窗用同一份，避免两处显示不一致）
const thermalLoading = ref(false);
/** 最近一次读取是否失败（失败时保留上一次的摘要，但不再显示成「暂无数据」）。 */
const thermalFailed = ref(false);
const thermalZones = ref<ThermalZone[]>([]);

async function loadThermal() {
  thermalLoading.value = true;
  try {
    const { data } = await api.get('/api/device/thermal');
    thermalZones.value = normalizeThermalZones(data?.zones);
    thermalFailed.value = false;
  } catch {
    thermalFailed.value = true;
  } finally {
    thermalLoading.value = false;
  }
}

/** 最高温：只统计有有效读数的传感器；一个都没有时返回 null（页面显示空态）。 */
const maxTemp = computed<number | null>(() => {
  const valid = thermalZones.value.map((z) => z.temp).filter((t): t is number => t !== null);
  return valid.length ? Math.max(...valid) : null;
});

/** 最高温的展示形态：文案 / 颜色 / 是否渲染一起算好（模板里不必再从可空数字里二次判断）。 */
const maxTempView = computed(() => {
  const t = maxTemp.value;
  if (t === null) return { visible: false, text: '' };
  return { visible: true, text: formatThermalTemp(t) };
});

/**
 * 传感器摘要（原「温度传感器」卡的那两行，搬进硬件状态卡的行末）。
 *
 * 失败与「真的没有传感器」必须分开说：原版两张卡各自判过这两态，
 * 合并后仍然保留同样的四分法，只是空间从一张卡缩到一行。
 */
const thermalSummaryText = computed(() => {
  if (maxTempView.value.visible) {
    return `传感器 ${thermalZones.value.length} 个 · 最高 ${maxTempView.value.text}`;
  }
  if (thermalFailed.value) return '温度读取失败';
  if (thermalLoading.value) return '正在读取温度…';
  return '设备没有返回温度传感器';
});

// ── 承载 QoS（EPS bearer QoS）──
//
// `GET /api/device/qos` → `{ available, cid, qci, downlink_kbps, uplink_kbps,
// downlink_display, uplink_display }`。
//
// 两个必须照做的点（contract.ts 的 Endpoints.device.qos 里也写了）：
//   ① **失败也是 HTTP 200**，只是 `available:false` + 空字段（AT 通道在很多机型上不可用是
//      预期状态）⇒ 这里据 `available` 显示 `—`，不按网络错误处理、不弹 toast；
//   ② `*_display` 是 core 已经换算好的文案（如 `500 Mbps`），**直接用**，
//      不在这里把 kbps 再换算一次 —— 两端各写一份换算必然分叉（一个显示 500 Mbps、
//      一个显示 500.0 Mbps）。
/** 是否已经拿到过一次响应（用于区分"还没拉到"与"拉到了但查不到"，后者才显示灰字说明）。 */
/** 最近一次读取是否失败：一次都没读到过时，三个 `—` 不能被读成「机型不支持」。 */
const qosFailed = ref(false);
const qos = reactive({
  available: false,
  qci: '' as string | number,
  downlinkDisplay: '',
  uplinkDisplay: '',
});

async function loadQos() {
  try {
    const { data } = await api.get(Endpoints.device.qos);
    // 组件卸载导致请求被取消时 data 是 undefined：此时**不能**把 available 写成 false，
    // 否则卡片会闪一下"本机型不支持查询"（与 NetworkView.refreshSignal 的守卫同一个口径）。
    if (!data) return;
    qos.available = data.available === true;
    qos.qci = data.qci ?? '';
    qos.downlinkDisplay = data.downlink_display ?? '';
    qos.uplinkDisplay = data.uplink_display ?? '';
    qosFailed.value = false;
  } catch {
    // 仍不弹 toast（AT 不可用是预期状态），但记下来让卡片能区分「没读到」与「不支持」
    qosFailed.value = true;
  }
}

/** 三行取值统一走这里：`available:false` / 缺字段 / 空串都显示 `—`，绝不渲染空白格。 */
function qosText(v: string | number): string {
  if (!qos.available) return '—';
  const s = String(v ?? '').trim();
  return s === '' ? '—' : s;
}

/** 诊断条的行末说明：两个端点各自可能失败，合并成一句，避免两行噪音。 */
const diagFailedText = computed(() => {
  const parts: string[] = [];
  if (connectionsFailed.value) parts.push('连接数读取失败');
  if (qosFailed.value) parts.push('承载参数读取失败');
  return parts.join('；');
});

/** 诊断条上的「重试」：只补拉失败的那一侧，不顺手把没失败的那半也打一遍。 */
function retryDiagnostics() {
  if (connectionsFailed.value) loadConnections();
  if (qosFailed.value) loadQos();
}

// ── 页面级刷新 ──
// 原来 SIM / 电池 / QoS 三个「刷新」按钮调用的是同一批端点，看起来却像三种范围。
// 现在只有一个入口：把这一页所有来源都拉一遍（读数、连接、温度、控制项、承载）。
const refreshing = ref(false);
async function refreshAll() {
  if (refreshing.value) return;
  refreshing.value = true;
  try {
    await Promise.allSettled([
      refreshSummary(),
      loadConnections(),
      loadThermal(),
      loadControlSettings(true),
      loadQos(),
    ]);
  } finally {
    refreshing.value = false;
  }
}

// ── 设备控制的能力门禁 ──
//
// 三项各对应一个 `Capability`（core/contract 的 Capabilities.kt 写明：性能模式 →
// `/device/performance`、Samba → `/device/samba`、FOTA → `/device/fota`）。
// LED 指示灯**没有**对应能力（那 11 项里没有 LED），所以不做门禁 —— 不为了"整齐"
// 编一个不存在的 wire 出来。
//
// 判据一律 `unsupported()`：只有"设备确实没声明"才置灰；"还没加载完 / 请求失败"
// （`supports()` 回 true 而 `unsupported()` 回 false）时全部照常可用。
const performanceUnsupported = computed(() => caps.unsupported(Capability.PERFORMANCE_MODE));
const performanceHint = computed(() => caps.hintOf(Capability.PERFORMANCE_MODE));
const sambaUnsupported = computed(() => caps.unsupported(Capability.SAMBA));
const sambaHint = computed(() => caps.hintOf(Capability.SAMBA));
const fotaUnsupported = computed(() => caps.unsupported(Capability.FOTA));
const fotaHint = computed(() => caps.hintOf(Capability.FOTA));

// ── 设备控制（六项平铺；逻辑与注释来自原 ControlTab，经 DeviceControlModal 中转，一字未改） ──
const controlLoading = ref(false);
/** 最近一次读设备设置是否失败 —— 失败时六个开关会一起停在「关」，那是一个看着像真值的假状态。 */
const controlFailed = ref(false);

const controlSettings = reactive({
  ledEnabled: false,
  performanceEnabled: false,
  sambaEnabled: false,
  usbPortEnabled: false,
  restartScheduleEnabled: false,
  restartScheduleTime: '04:00',
  fotaAutoUpdate: false,
});

const toggleLoading = reactive<Record<string, boolean>>({
  led: false,
  performance: false,
  samba: false,
  usbPort: false,
  restartSchedule: false,
  fota: false,
});

/**
 * 设备 goform 写入成功到「查询能读到新值」之间存在延迟：
 * 零延迟回读会拿到旧值、覆盖掉刚做的乐观置位，UI 弹回原样，
 * 用户以为没生效就反复点击。600ms 对齐 Android app（NetworkModule.setWifiSleep 等
 * 都是写入后 delay(500) 再 load），多留一点余量。只用于「写入后回读」，纯查询不加。
 */
const GOFORM_SETTLE_MS = 600;
const settleAfterWrite = () => new Promise((resolve) => setTimeout(resolve, GOFORM_SETTLE_MS));

async function loadControlSettings(silent = false) {
  if (!silent) controlLoading.value = true;
  try {
    // core GET /api/device/settings 是**归一化后的契约字段**（allowlist 默认拒绝，未登记的
    // 设备字段不会出现），键名见 contract.ts 的 DeviceFields.deviceSettings：
    // indicator_light_switch / performance_mode / samba_switch / usb_port_switch /
    // restart_schedule_switch / restart_time / UpgMode。值全是字符串 "1"/"0"（不能用 !! 判断）。
    const { data } = await api.get('/api/device/settings');
    const on = (k: string) => String(data?.[k] ?? '') === '1';
    controlSettings.ledEnabled = on('indicator_light_switch');
    controlSettings.performanceEnabled = on('performance_mode');
    controlSettings.sambaEnabled = on('samba_switch');
    controlSettings.usbPortEnabled = on('usb_port_switch');
    controlSettings.restartScheduleEnabled = on('restart_schedule_switch');
    // FOTA 自动检查更新：设备侧 UpgMode，"1" = 开（与写侧 auto_update 同向）
    controlSettings.fotaAutoUpdate = on('UpgMode');
    controlSettings.restartScheduleTime = String(data?.restart_time || '') || '04:00';
    controlFailed.value = false;
  } catch {
    // 不再全静默：读失败时开关值全是默认的「关」，卡片要给一句可信度说明
    controlFailed.value = true;
  } finally {
    if (!silent) controlLoading.value = false;
  }
}

// ToggleRow 是完全受控组件（:value="modelValue"），父组件不回写状态开关就不会动，
// 所以这里成功后先本地置位、再静默回读设备真实状态；
// 回读必须等 GOFORM_SETTLE_MS，否则会读回旧值把乐观置位冲掉（见 settleAfterWrite 注释）
async function postToggle(
  url: string,
  enabled: boolean,
  loadingKey: string,
  stateKey: 'ledEnabled' | 'performanceEnabled' | 'sambaEnabled' | 'usbPortEnabled'
) {
  toggleLoading[loadingKey] = true;
  let posted = false;
  try {
    await api.post(url, { enabled });
    posted = true;
    // 只有成功才乐观置位：失败时保持原值，随后的回读会给出设备真实状态
    controlSettings[stateKey] = enabled;
    message.success('设置已更新');
  } catch {
    message.error('设置失败');
  } finally {
    if (posted) await settleAfterWrite();
    await loadControlSettings(true);
    toggleLoading[loadingKey] = false;
  }
}

async function postRestartSchedule(enabled: boolean) {
  toggleLoading.restartSchedule = true;
  let posted = false;
  try {
    await api.post('/api/device/restart-schedule', {
      enabled,
      time: controlSettings.restartScheduleTime,
    });
    posted = true;
    controlSettings.restartScheduleEnabled = enabled;
    message.success('定时重启设置已更新');
  } catch {
    message.error('设置失败');
  } finally {
    if (posted) await settleAfterWrite();
    await loadControlSettings(true);
    toggleLoading.restartSchedule = false;
  }
}

async function postRestartScheduleTime() {
  if (!controlSettings.restartScheduleEnabled) return;
  if (!/^\d{1,2}:\d{2}$/.test(controlSettings.restartScheduleTime)) {
    message.warning('重启时间格式应为 HH:MM');
    return;
  }
  toggleLoading.restartSchedule = true;
  let posted = false;
  try {
    await api.post('/api/device/restart-schedule', {
      enabled: true,
      time: controlSettings.restartScheduleTime,
    });
    posted = true;
    message.success('重启时间已更新');
  } catch {
    message.error('设置失败');
  } finally {
    if (posted) await settleAfterWrite();
    await loadControlSettings(true);
    toggleLoading.restartSchedule = false;
  }
}

// 设备侧就是 goformId=SetUpgAutoSetting&UpgMode=0|1（0 = 关闭自动检查更新，1 = 开启），
// core 把它同时登记成读写字段：写 POST /api/device/fota { auto_update }，
// 读 GET /api/device/settings 的 UpgMode，所以这里能像其它开关一样回显真实状态。
//
// 规范入参是 auto_update（**正向**：true = 允许自动升级）。旧入参 enabled 是反的
// （true = 禁用），core 仍兼容但会打 warn，这里已改用 auto_update。
async function setFotaAutoUpdate(autoUpdate: boolean) {
  toggleLoading.fota = true;
  let posted = false;
  try {
    await api.post('/api/device/fota', { auto_update: autoUpdate });
    posted = true;
    // 与 postToggle 同一套：成功才乐观置位，失败保持原值等回读纠正
    controlSettings.fotaAutoUpdate = autoUpdate;
    message.success(autoUpdate ? '已开启 FOTA 自动检查更新' : '已关闭 FOTA 自动检查更新');
  } catch {
    message.error('设置失败');
  } finally {
    if (posted) await settleAfterWrite();
    await loadControlSettings(true);
    toggleLoading.fota = false;
  }
}

onMounted(() => {
  loadThermal();
  loadConnections();
  loadControlSettings();
  // QoS 只在这里拉一次（+ 卡片上的手动刷新）：core 侧 5 分钟缓存，它是承载建立时协商出来的
  // 低频参数，不该跟着下面的 10s 轮询每 10s 打一次必然命中缓存的请求。
  loadQos();
  // summary 只在 DefaultLayout 挂载时取一次；直接进入本页或那次请求失败时
  // SIM / 电池卡会没有数据，所以本页自己补拉（与 DashboardView / NetworkView 一致）
  refreshSummary();
});

// 温度与连接数是实时量，之前只在挂载时取一次就再也不更新。
// 温度弹窗打开时跳过页面侧那一轮：弹窗自己在轮询同一个端点，重复请求纯浪费热点流量。
// 设备控制项**不轮询**：它们只在用户操作时变（原 ControlTab 也是只在挂载时读一次）。
// 承载 QoS 同样不在这里（低频 + core 5 分钟缓存，理由见 loadQos 调用处）。
useInterval(() => {
  if (!showThermal.value) loadThermal();
  loadConnections();
}, 10_000);
</script>

<style scoped>
/*
  设备页版式（2026-11-11，对应 docs/mockups/device-page-layout-proposal-2026-11-11.html）

  两列断点从 768 提到 900：1024 时两列各 367px 已经成立，而 768 时可用 734px
  却被压成单列（页面 1947px，只比 390 手机短 355px）。900 是「两张卡各 ≥400px」的下界。

  小字用公共 --text-secondary(#666)：全站 --text-muted(#999) 在白底只有 2.85:1，
  这一页的说明文字是 11–12px，达不到 AA 4.5:1；--text-secondary 在明暗两档都 ≥5:1。
  全站令牌没动 —— 那是另一轮。
*/
.device-page {
  /* ui-baseline-allow: 本页两个小字链接/警示色是公共色对比度不足的替代，取值同账单页 */
  /* 小字一律用公共 --text-secondary(#666)：全站 --text-muted(#999) 在白底只有 2.85:1，
     这一页的说明文字是 11–12px，达不到 AA 4.5:1。--text-secondary 本就 ≥5:1，
     不需要再造一个本页私有的灰色令牌。 */
  /* 两个本页局部色，都是公共色在浅底做小字时对比度不够的替代：
     · --dv-link：公共 --accent-color(#18a058) 约 3.2:1，12px 链接达不到 AA 4.5:1；
     · --dv-warn：公共 --warning(#f0a020) 约 1.9:1，11px 的「读数可能不准」不可读。
     取值与账单页 --bill-warn 同一档。除此之外的字体/间距/表面色全部走公共令牌。 */
  --dv-link: #0f7a43;
  --dv-warn: #b45309;
  display: grid;
  gap: var(--space-4);
}
.dark .device-page {
  /* ui-baseline-allow: 暗色档的链接/警示色对应取值，浅底那组在深底不可读 */
  --dv-link: #4ade80;
  --dv-warn: #f0a020;
}

/* ── 页面级刷新 ── */
.page-actions {
  display: flex;
  justify-content: flex-end;
  margin-bottom: calc(var(--space-2) * -1);
}
.refresh-all {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  appearance: none;
  border: 1px solid var(--border-subtle);
  background: var(--card-bg);
  color: var(--text-secondary);
  font: inherit;
  font-size: var(--font-sm);
  padding: var(--space-1) var(--space-3);
  border-radius: var(--radius-sm);
  cursor: pointer;
}
.refresh-all:hover:not(:disabled) {
  color: var(--text-primary);
  background: var(--surface-hover);
}
.refresh-all:disabled {
  cursor: default;
  color: var(--text-secondary);
}
.refresh-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  border: 1.5px solid currentColor;
  border-top-color: transparent;
  animation: dv-spin 0.8s linear infinite;
}
@keyframes dv-spin {
  to {
    transform: rotate(360deg);
  }
}

/* ── 栅格 ── */
.device-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--space-4);
  align-items: start;
}
/* 通栏：类名落在子组件（GridCard）根节点上，所以要 :deep() 穿透 */
.device-grid > :deep(.full-width) {
  grid-column: 1 / -1;
}
@media (max-width: 900px) {
  .device-grid {
    grid-template-columns: 1fr;
  }
}

/* ── 设备身份 ── */
.id-summary {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: var(--space-3);
  padding-bottom: var(--space-3);
}
.id-cell {
  display: grid;
  gap: 2px;
  min-width: 0;
}
.id-k {
  font-size: var(--font-xs);
  color: var(--text-secondary);
}
.id-v {
  font-size: var(--font-md);
  font-weight: 600;
  font-variant-numeric: tabular-nums;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
/* SIM 状态：只有 Ready 才点亮状态点（中性灰 = 未知/未就绪，不假装正常） */
.id-state {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  font-size: var(--font-base);
  font-weight: 600;
  color: var(--text-primary);
}
.id-state::before {
  content: '';
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--text-secondary);
}
.id-state.ok::before {
  background: var(--accent-color);
}
/* 摘要与明细之间的那条线画在第一个 InfoRow 上：InfoRow 自己是「行间有底线、末行没有」 */
.id-row-first {
  border-top: 1px solid var(--border-subtle);
}

/* ── 硬件状态 ── */
.metrics {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: var(--space-4);
  padding-bottom: var(--space-2);
}
.metric {
  display: grid;
  gap: 1px;
  min-width: 0;
}
.metric-k {
  display: flex;
  align-items: baseline;
  gap: 6px;
  font-size: var(--font-xs);
  color: var(--text-secondary);
  white-space: nowrap;
}
.metric-note {
  font-size: var(--font-xs);
  color: var(--dv-warn, var(--warning));
}
.metric-v {
  font-size: var(--font-2xl);
  font-weight: 650;
  line-height: 1.2;
  letter-spacing: -0.02em;
  font-variant-numeric: tabular-nums;
  color: var(--text-primary);
}
.metric-v small {
  margin-left: 1px;
  font-size: var(--font-base);
  font-weight: 600;
  letter-spacing: 0;
  color: var(--text-secondary);
}
.hw-hint {
  font-size: var(--font-xs);
  color: var(--text-secondary);
}
.thermal-line {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  margin-top: var(--space-1);
  padding-top: var(--space-3);
  border-top: 1px solid var(--border-subtle);
}
.thermal-text {
  min-width: 0;
  font-size: var(--font-sm);
  color: var(--text-secondary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.thermal-link {
  flex: none;
  margin-left: auto;
  appearance: none;
  border: 0;
  background: none;
  padding: 0;
  font: inherit;
  font-size: var(--font-sm);
  color: var(--dv-link);
  cursor: pointer;
}
.thermal-link:hover {
  text-decoration: underline;
  text-underline-offset: 3px;
}

/* ── 设备控制：三列 / 两列 / 单列 ──
   边框与间距由栅格接管（ToggleRow inline 变体不带边框）：多列里组件自带的
   「首行去上内距 / 末行去底边框」会错位，这里用单元格自己画行首线。 */
.ctl {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  column-gap: var(--space-5);
}
.ctl-cell {
  display: flex;
  align-items: center;
  min-width: 0;
  min-height: 58px;
  padding: var(--space-3) 0;
  border-top: 1px solid var(--border-subtle);
}
.ctl-cell:nth-child(-n + 3) {
  border-top: 0;
  padding-top: 0;
}
/* ToggleRow 是 flex:1 的行，放进单元格后要占满（tooltip 的 trigger 也才是整格可悬停） */
.ctl-cell > :deep(.toggle-row) {
  width: 100%;
  min-width: 0;
}

@media (max-width: 1060px) {
  .ctl {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
  .ctl-cell:nth-child(-n + 3) {
    border-top: 1px solid var(--border-subtle);
    padding-top: var(--space-3);
  }
  .ctl-cell:nth-child(-n + 2) {
    border-top: 0;
    padding-top: 0;
  }
}
@media (max-width: 700px) {
  .ctl {
    /* 单列也要 minmax(0,1fr)：默认的 1fr 最小尺寸是 min-content，
       长描述（FOTA 那条）会把整格撑到 379px，超出卡内 336px 的内容区。 */
    grid-template-columns: minmax(0, 1fr);
  }
  /* 单列时每一格都是「行首」，只把第一格的上内距去掉 */
  .ctl-cell,
  .ctl-cell:nth-child(-n + 2),
  .ctl-cell:nth-child(-n + 3) {
    border-top: 1px solid var(--border-subtle);
    padding-top: var(--space-3);
  }
  .ctl-cell:first-child {
    border-top: 0;
    padding-top: 0;
  }
}
/* 触摸屏：行高再放一点，整行仍是热区（开关可见尺寸不变） */
@media (pointer: coarse) {
  .ctl-cell {
    min-height: 52px;
  }
}

.restart-time {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--space-3);
  margin-top: var(--space-2);
  padding-top: var(--space-3);
  border-top: 1px solid var(--border-subtle);
}
.restart-time label {
  font-size: var(--font-base);
  color: var(--text-secondary);
}
.restart-time :deep(.restart-input) {
  width: 96px;
}
.restart-hint {
  font-size: var(--font-sm);
  color: var(--text-secondary);
}

/* ── 失败态 ── */
.state-empty {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--space-2);
  font-size: var(--font-sm);
  color: var(--text-secondary);
}
.state-retry {
  appearance: none;
  border: 0;
  background: none;
  padding: 0;
  font: inherit;
  font-size: var(--font-sm);
  color: var(--dv-link);
  text-decoration: underline;
  text-underline-offset: 3px;
  cursor: pointer;
}

/* ── 更多：页面级列表（不是一张只装一行的卡） ── */
.more {
  grid-column: 1 / -1;
  display: grid;
  gap: var(--space-2);
}
.more-title {
  margin: 0;
  font-size: var(--font-sm);
  font-weight: 600;
  color: var(--text-secondary);
}
.entry {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr) auto;
  align-items: center;
  gap: var(--space-3);
  width: 100%;
  padding: var(--space-3) var(--space-4);
  text-align: left;
  font: inherit;
  color: inherit;
  background: var(--card-bg);
  border: 1px solid var(--border-subtle);
  border-radius: var(--radius-md);
  cursor: pointer;
}
.entry:hover {
  background: var(--surface-hover);
}
.entry-ico {
  display: grid;
  place-items: center;
  width: 30px;
  height: 30px;
  border-radius: var(--radius-sm);
  background: var(--accent-color-light);
  color: var(--accent-color);
}
.entry-text {
  display: grid;
  gap: 1px;
  min-width: 0;
}
.entry-text b {
  font-size: var(--font-base);
  font-weight: 600;
}
.entry-text span {
  font-size: var(--font-xs);
  color: var(--text-secondary);
}
.entry-chev {
  color: var(--text-secondary);
  font-size: var(--font-lg);
}
</style>

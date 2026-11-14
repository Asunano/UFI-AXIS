<template>
  <div class="network-view">
    <!--
      网络页栅格：两列、两行（2×2），按 DOM 顺序填充，无需 grid-area。
        行 1 = 网络信息 ｜ 网络设置
        行 2 = WiFi 热点 ｜ 局域网设备

      「网络设置」与「网络信息」并排，两行各两张卡片，四张卡权重均衡。
      「网络设置」不是通栏卡：卡内 6 个入口瓦片按 `auto-fit, minmax(240px, 1fr)` 排列。

      固定布局：两列栅格的行是独立轨道，第 2 行的局域网设备再多，也不会改变第 1 行的
      卡片位置。这是 grid 与 `flex-wrap` 的关键差异：flex-wrap 中某个子项变高会改变同一
      行的排布，而 grid 的行轨道只由本行内容决定。行内使用 `align-items: stretch` 使同一行
      两张卡等高，矮卡以内部留白补齐，不露出页面底色。
      局域网设备清单在卡内封顶并自行滚动（见 LanDevicesCard 的 `.lan-scroll`），
      因此该行高度有上界，不随在线设备数增长。

      卡片内部的自适应：
        · WiFi 热点 —— `.kv-grid` 为 `minmax(260px, 1fr)`，半宽（约 550px）时回落为 2 列 4 行，
          按半宽设计，无需调整；
        · 局域网设备 —— 四列行需要约 460px，塌陷断点须按卡片宽度而非视口宽度判断：
          断点 768px 时，视口 900px 下卡片宽度仅约 430px 仍不塌陷。当前断点为 1024px，
          见卡片内注释。
    -->
    <div class="settings-panel network-grid">
      <!-- 行 1 左：网络信息（连接状态、开关、信号质量） -->
      <NetworkInfoCard
        :net-status="netStatus"
        :signal="signal"
        :mobile-data-saving="mobileDataSaving"
        :mode-text="modeTileLabel"
        :mode-unsupported="networkModeUnsupported"
        :mode-hint="networkModeHint"
        @update:mobile-data="setMobileData"
        @airplane-on="setAirplane(true)"
        @airplane-off="setAirplane(false)"
        @ppp-connect="pppConnect"
        @ppp-disconnect="pppDisconnect"
        @open-mode="showModeModal = true"
      />

      <!-- 行 1 右：网络设置 —— 原「更多设置」并入本卡；网络模式入口移至「网络信息」，
           本卡保留 6 个入口。
           布局为 2×2（行 1：网络信息 | 网络设置；行 2：WiFi 热点 | 局域网设备），
           本卡不是通栏卡。
           能力门禁不挂在瓦片上（限额栏读取配置仍可用），改为传入弹窗，用于门禁校准栏，
           见下方 TrafficAndCalibrationModal 的绑定。 -->
      <NetworkSettingsCard
        :band-unsupported="bandLockUnsupported"
        :band-hint="bandLockHint"
        @open-band="bandModal.open()"
        @open-cell="cellModal.open()"
        @open-diag="openDiagnostics"
        @open-speed="speedModal.open()"
        @open-traffic-calibration="showTrafficCalibrationModal = true"
        @open-lan="showLanModal = true"
      />

      <!-- 行 2 左：WiFi 热点（半宽；其 .kv-grid 按半宽设计，见顶部说明） -->
      <WifiHotspotCard
        :loading="wifiLoading"
        :wifi-enabled="wifiEnabled"
        :settings="wifiSettings"
        :client-count="wifiClients.length"
        @toggle-wifi="toggleWifiEnabled"
        @open-qr="openQrModal"
        @edit="showWifiEditModal = true"
      />

      <!-- 行 2 右：局域网设备。设备清单在卡内封顶并自行滚动（见卡片内 `.lan-scroll`），
           该行高度不随在线设备数增长；行内依靠 align-items: stretch 与左卡等高 -->
      <LanDevicesCard
        :clients="wifiClients"
        :blocked-list="blockedList"
        :blocked-macs="blockedMacs"
        :acl-pending="aclPending"
        @open-acl="showAclModal = true"
        @block="blockDevice"
        @unblock="unblockDevice"
      />
    </div>

    <!-- 弹窗子组件（逻辑位于 components/modals/；4 个重型弹窗按需懒加载） -->
    <NetworkModeModal
      v-model:show="showModeModal"
      v-model:selected-mode="selectedMode"
      :mode-loading="modeLoading"
      :network-modes="networkModes"
      :current-mode="currentMode"
      :current-label="modeLabel"
      :refreshing="modeRefreshing"
      :switching="switchingMode !== null"
      :timed-out="modeSwitchTimedOut"
      @apply="applyModeAndClose"
      @refresh="refreshDeviceSettings"
    />

    <!-- 懒加载弹窗：组件本体与显隐时序由 useLazyModal 管理，
         避免进入时首帧即为 show=true，导致进出场动画丢失 -->
    <component :is="bandComponent" v-if="bandComponent" :show="bandShow" @update:show="bandModal.setShow" />
    <component :is="speedComponent" v-if="speedComponent" :show="speedShow" @update:show="speedModal.setShow" />

    <!-- `switch-band` 为切换频段分支（A 级操作，见 handleSwitchBand）；
         `saved` 为仅修改 SSID / 密码的分支（不重启模块，仍为一次写入加一次回读） -->
    <WifiEditModal
      v-model:show="showWifiEditModal"
      :wifi-settings="wifiSettings"
      :sleep-time="sleepTime"
      :sleep-saving="sleepSaving"
      @saved="handleWifiSaved"
      @switch-band="handleSwitchBand"
      @update:sleep-time="sleepTime = $event"
      @save-sleep="saveSleepTimer"
    />

    <QrModal
      v-model:show="showQrModal"
      :qr-url="qrUrl"
      :qr-loading="qrLoading"
      :qr-error="qrError"
      :ssid="wifiSettings?.ssid"
      @refresh="reloadQr"
      @released="releaseQrUrl"
    />

    <AclModal
      v-model:show="showAclModal"
      :blocked-list="blockedList"
      :acl-pending="aclPending"
      @unblock="unblockDevice"
      @clear="clearBlockedDevices"
    />

    <!-- 基站信息弹窗：锁定 / 解锁两个写操作由本页以函数形式传入（A 级操作，见 lockCell /
         unlockCells）。弹窗不得自行调用 useNetworkControls()：该 composable 每次调用都会
         新建一套页面级 ref，再次调用会产生额外的状态副本与一次 finishedSeq 订阅。 -->
    <component
      :is="cellComponent"
      v-if="cellComponent"
      :show="cellShow"
      :lock-cell="lockCell"
      :unlock-cells="unlockCells"
      @update:show="cellModal.setShow"
    />

    <!-- 迁移自 #/device 的配置弹窗（组件位于共享目录 @/components/modals/） -->
    <LanModal v-model:show="showLanModal" />
    <!--
      流量限额与流量校准合并为一个弹窗、两栏（限额 / 校准）。
      组件为本页私有，位于 `./components/modals/`，不在共享目录。

      两个动作的语义未合并（保存配置不等于立即修改设备计数器），弹窗底部按钮随当前栏变化。
      校准成功后仍会重新请求 `/api/device/traffic-limit` 刷新「当前已用流量 / 时长」：
      校准修改的正是这两个数值，界面必须同步（core 成功时同样会失效该缓存）。
      两栏的数据均在打开时各读取一次。
    -->
    <TrafficAndCalibrationModal
      v-model:show="showTrafficCalibrationModal"
      :calibration-unsupported="trafficLimitUnsupported"
      :calibration-hint="trafficLimitHint"
    />
    <!--
      网络诊断：四个工具各自独立路由，共用同一弹窗外壳。
      `show` 完全由路由派生（选择器或具体工具）；`tool` 为空时弹窗内为四项列表。
    -->
    <NetworkDiagnosticsModal
      :show="diagShow"
      :tool="diagTool"
      @update:show="(v: boolean) => !v && closeDiagnostics()"
      @select="selectDiagTool"
    />

    <!--
      进度弹窗全仓仅在 DefaultLayout 挂载一份，状态位于 stores/networkOps：
      无论从哪一页发起、切换到哪一页，呈现的都是同一份进度与结论。
      本页不再挂载 OperationProgressModal，也不负责其显示内容。
    -->
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { useInterval } from '@/composables/useRealtime';
import { useDashboardStore } from '@/stores/dashboard';
import { useWebSocketStore } from '@/stores/websocket';
import { useServiceStore } from '@/stores/service';
import { useCapabilitiesStore } from '@/stores/capabilities';
import { useCancellableApi } from '@/composables/useCancellableApi';
import { useNetworkControls } from '@/composables/useNetworkControls';
import { useLazyModal } from '@/composables/useLazyModal';
import { get } from '@/composables/utils';
import { Capability } from '@/api/contract';
import type { WifiBand } from '@/api/contract';
// A 级操作的进度弹窗统一挂载在 DefaultLayout（状态位于 stores/networkOps），
// 本页不导入 OperationProgressModal，见模板末尾说明。

// 主卡片（受控子组件：props 下行，事件上行）
import NetworkInfoCard from './components/cards/NetworkInfoCard.vue';
import WifiHotspotCard from './components/cards/WifiHotspotCard.vue';
import LanDevicesCard from './components/cards/LanDevicesCard.vue';
import NetworkSettingsCard from './components/cards/NetworkSettingsCard.vue';

// 受控弹窗（模板与本地 UI 状态位于子组件）
// NetworkMode / Qr / Acl / BandLock 位于 src/components/modals/：仪表盘同样使用，
// 不得放在 views/network 内部，否则仪表盘将导入网络页私有组件。
// LAN / DHCP 弹窗同理：其使用方不再是单一 view，因此位于共享目录。
import NetworkModeModal from '@/components/modals/NetworkModeModal.vue';
import QrModal from '@/components/modals/QrModal.vue';
import AclModal from '@/components/modals/AclModal.vue';
import LanModal from '@/components/modals/LanModal.vue';
import WifiEditModal from './components/modals/WifiEditModal.vue';
// 流量限额与流量校准合并为一个弹窗，栏内分为「限额 / 校准」。
import TrafficAndCalibrationModal from './components/modals/TrafficAndCalibrationModal.vue';
import NetworkDiagnosticsModal from './components/modals/NetworkDiagnosticsModal.vue';
import type { NetworkDiagTool } from './diagTools';

// 频段锁定 / 测速 / 基站信息 3 个重型弹窗按需加载：组件本体与显隐时序由 useLazyModal
// 管理（见下方 bandModal 等），不使用 defineAsyncComponent。
// 「WiFi 模块信息」位于设置页（设置 › 设备），「网络诊断」归入诊断中心，本页均不再挂载。

const dashboardStore = useDashboardStore();
const wsStore = useWebSocketStore();
// data_changed 订阅需判断服务是否已停止：core 重启期间不刷新
const serviceStore = useServiceStore();
// 设备能力集：应用外壳（DefaultLayout）已拉取，此处只读取同一个 store（见 capabilities.ts 文件头）
const caps = useCapabilitiesStore();
const api = useCancellableApi();

/**
 * 本页的能力门禁。
 *
 * 覆盖两个入口瓦片（弹窗本体位于共享目录）：
 *   · 网络模式 → `Capability.NETWORK_MODE`（core 的 Capabilities.kt：覆盖
 *     `POST /api/network/mode` 与 `/network/bearer`）；
 *   · 频段锁定 → `Capability.BAND_LOCK`（覆盖 `POST /api/network/band` 的 lock / unlock）。
 *
 * 判据一律为 `caps.unsupported()`：仅在设备确实未声明该能力时置灰。不得使用
 * `!caps.supports()`：该判据在「能力集尚未拉取」或「请求失败」时同样为真，
 * 会使这两个入口一并不可用（见 stores/capabilities.ts 文件头，该约束为硬性要求）。
 * 置灰时须一并传入 `hintOf()` 作为 tooltip，仅置灰而无提示会使界面表现为故障。
 *
 * 本页不实现「点击后返回 501」的兜底提示：core 的路由门禁已在设备之前拦截请求
 * （501 + NOT_SUPPORTED），界面层的职责仅为避免用户执行必然失败的操作。
 */
const networkModeUnsupported = computed(() => caps.unsupported(Capability.NETWORK_MODE));
const networkModeHint = computed(() => caps.hintOf(Capability.NETWORK_MODE));
const bandLockUnsupported = computed(() => caps.unsupported(Capability.BAND_LOCK));
const bandLockHint = computed(() => caps.hintOf(Capability.BAND_LOCK));
/**
 * 流量校准的门禁使用 `Capability.TRAFFIC_LIMIT`（`hintOf` 的文案同为「流量限额」）。
 * 语义细节见 TrafficAndCalibrationModal 校准栏注释：core 的能力清单仅将
 * `/api/device/data-limit` 计为 TRAFFIC_LIMIT，`/flow-calibration` 不在其中。
 *
 * 门禁位于弹窗的校准栏，而非入口瓦片：限额栏读取配置与查看用量不依赖该能力，
 * 置灰整个瓦片会使该路径一并不可用。
 */
const trafficLimitUnsupported = computed(() => caps.unsupported(Capability.TRAFFIC_LIMIT));
const trafficLimitHint = computed(() => caps.hintOf(Capability.TRAFFIC_LIMIT));

/**
 * 全部设备写操作与配套回读来自 useNetworkControls，本页与仪表盘共用同一实现。
 * 不得在本文件另行实现 handler：回读前等待多久、哪些失败仍需回读、哪些端点以
 * 200 + success:false 表示失败，这三条设备约定都位于该 composable，重复实现必然遗漏。
 */
const {
  loadDeviceSettings,
  loadWifiSettings,
  loadWifiClients,
  loadWifiAcl,
  refreshNetworkStatus,
  networkModes,
  selectedMode,
  currentMode,
  modeLoading,
  modeRefreshing,
  refreshDeviceSettings,
  modeLabel,
  applyNetworkMode,
  // 弹窗打开期间，回读不得覆盖待选档位（见 composable 的 modeEditing）
  beginModeEdit,
  endModeEdit,
  switchingMode,
  modeSwitchTimedOut,
  mobileDataSaving,
  setMobileData,
  setAirplane,
  pppConnect,
  pppDisconnect,
  wifiSettings,
  wifiClients,
  wifiLoading,
  wifiEnabled,
  toggleWifiEnabled,
  // 切频段按 A 级操作处理：需轮询回读确认生效频段，单次回读不足以判定
  switchWifiBand,
  // A 级操作的状态与进度弹窗统一由 stores/networkOps 与 DefaultLayout 承担，
  // 本页不持有 wifiOp / modeOpState（见 useNetworkControls 的导出说明）
  onWifiSaved,
  sleepTime,
  sleepSaving,
  saveSleepTimer,
  qrUrl,
  qrLoading,
  qrError,
  loadQrCode,
  releaseQrUrl,
  blockedList,
  blockedMacs,
  aclPending,
  blockDevice,
  unblockDevice,
  clearBlockedDevices,
  // 基站锁定 / 解锁为 A 级操作，由本页转交给懒加载的 CellInfoModal；
  // 弹窗不得自行调用 useNetworkControls，否则会多出一份页面级 ref 与一次 finishedSeq 订阅
  lockCell,
  unlockCells,
} = useNetworkControls();

const netStatus = computed(() => get(dashboardStore.summary, 'network_status', null));
const signal = computed(() => dashboardStore.realtimeSignal);

// ── 弹窗开启标志（弹窗逻辑位于子组件，本组件仅负责显隐）──
const showModeModal = ref(false);
/**
 * 制式弹窗的显隐须通知 composable：编辑期间，10 秒轮询不得把回读值写回待选档位，
 * 否则用户选择的档位会被覆盖。打开弹窗时还会将待选项同步为设备当前值，
 * 清除上一次取消或超时遗留的选择。
 */
watch(showModeModal, (open) => (open ? beginModeEdit() : endModeEdit()));
const showWifiEditModal = ref(false);
const showQrModal = ref(false);
const showAclModal = ref(false);
// 两个迁移自 #/device 的配置入口（弹窗组件位于共享目录）
const showLanModal = ref(false);
// 「流量限额」与「流量校准」共用一个弹窗与一个开关（栏内分两栏）
const showTrafficCalibrationModal = ref(false);

/**
 * 4 个懒加载弹窗的挂载与显隐时序统一由 useLazyModal 管理。
 * 直接使用 `v-if="showXxx" v-model:show="showXxx"` 会丢失进出场动画：
 * 进场时首帧 `show` 即为 true（Transition 默认不在挂载时播放动画），
 * 出场时 `v-if` 与 `show` 在同一 tick 变为 false，组件被直接销毁。
 */
const bandModal = useLazyModal(() => import('@/components/modals/BandLockModal.vue'));
const speedModal = useLazyModal(() => import('./components/modals/SpeedTestModal.vue'));
// 网络诊断（ping / DNS / TCP / traceroute）四个工具归入诊断中心
// （`#/diagnostics`，入口位于「设置 → 关于 → 诊断中心」），本页对应瓦片改为跳转页面。
// 原因：这四个工具会向外部网络发包、产生数十行原始输出，并需与诊断包一同导出，
// 单个弹窗无法承载（详见 DiagnosticsView.vue 的文件头）。
const route = useRoute();
const router = useRouter();
const cellModal = useLazyModal(() => import('./components/modals/CellInfoModal.vue'));
const bandComponent = bandModal.component;
const bandShow = bandModal.show;
const speedComponent = speedModal.component;
const speedShow = speedModal.show;
const cellComponent = cellModal.component;
const cellShow = cellModal.show;

/**
 * 网络诊断：入口先打开「四选一」路由，选中后进入该工具的独立路由。
 * 两步均只改变路由，具体弹窗由下方 <NetworkDiagnosticsModal> 按 route.meta.diagTool 渲染。
 */
const diagTool = computed<NetworkDiagTool | null>(() => {
  const t = route.meta.diagTool;
  return typeof t === 'string' ? (t as NetworkDiagTool) : null;
});
const diagShow = computed(() => route.name === 'network-diagnostics' || diagTool.value !== null);

function openDiagnostics() {
  router.push({ name: 'network-diagnostics' });
}

function selectDiagTool(tool: NetworkDiagTool) {
  router.push({ name: `network-diagnostics-${tool}` });
}

/** 关闭弹窗并返回网络页：使用 replace，避免「打开→关闭」在浏览器历史中留下无意义记录。 */
function closeDiagnostics() {
  router.replace({ name: 'network' });
}

function applyModeAndClose() {
  // 请求被受理后即关闭弹窗：回读确认需等待设备重新注册（最长十余秒），
  // 不应让用户等待静止的进度弹窗；「切换中」由制式标签呈现。
  showModeModal.value = false;
  applyNetworkMode();
}

/**
 * 网络页的制式标签。
 *
 * 切换期间显示目标档位与「切换中」，超时显示「尚未完成」。若直接显示回读值，
 * 界面会停留在切换前的档位。
 *
 * 「切换中」必须基于 [switchingMode] 中的目标档位，不得使用 [modeLabel]：
 * 后者是设备真值（切换完成前仍为旧档位），会显示为「5G/4G/3G · 切换中」。
 * 目标档位仅存在于 [switchingMode] 中，据此查询中文名。
 */
const switchingModeLabel = computed(() => {
  const target = switchingMode.value;
  if (target === null) return '';
  return networkModes.find((m) => m.value === target)?.label || target;
});

const modeTileLabel = computed(() => {
  if (switchingMode.value !== null) return `${switchingModeLabel.value} · 切换中`;
  if (modeSwitchTimedOut.value) return `${modeLabel.value} · 尚未完成`;
  return modeLabel.value;
});

/**
 * 二维码取数需判断「请求返回时弹窗是否仍打开」：关闭后再创建 object URL 将无人回收。
 * 弹窗显隐状态由本组件持有，因此以回调形式传入 composable。
 */
const reloadQr = () => loadQrCode(() => showQrModal.value);
function openQrModal() {
  showQrModal.value = true;
  if (!qrUrl.value) reloadQr();
}
/** WiFi 配置变更后旧二维码失效：composable 负责丢弃缓存，此处告知其弹窗是否仍打开。 */
const handleWifiSaved = () => onWifiSaved(showQrModal.value);

/**
 * 切换 WiFi 频段。
 *
 * 弹窗提交目标频段后自行关闭（A 级回读最长 30.6 s，不应让配置弹窗持续等待），
 * 实际操作交由 `useNetworkControls.switchWifiBand`：下发并轮询回读，确认生效频段为目标值
 * （判据只比较频段，不含 SSID，理由见 composable 中的说明）。
 * 本函数不重复执行回读确认：收敛后 wifiSettings / wifiClients 由 composable 的
 * finishedSeq 订阅统一刷新；本函数只更新二维码，因为它不在统一回读范围内，
 * 且频段变更后基于旧频段生成的二维码已失效。
 */
async function handleSwitchBand(chip: WifiBand) {
  await switchWifiBand(chip);
  releaseQrUrl();
  if (showQrModal.value) reloadQr();
}

/**
 * 信号质量的 REST 兜底。
 *
 * 「信号质量」卡读取 dashboardStore.realtimeSignal，正常情况下由 WS 的 signal 频道推送
 * （订阅统一位于 DefaultLayout）。以下两种情况下仅靠 WS 会无数据：
 * ① core 的采集循环约 10 s 推送一次，直接进入本页需等待下一次推送；
 * ② WS 断线重连期间没有推送。
 * 因此挂载时补充一次 REST 请求，并在 WS 未连接时随 10 s 轮询执行。
 *
 * 该逻辑保留在本页而不移入 composable：它仅为该卡的兜底读取，不属于设备控制。
 */
async function refreshSignal() {
  try {
    const { data } = await api.get('/api/network/signal');
    if (data) dashboardStore.updateRealtime('signal', data);
  } catch {
    /* 静默处理：卡片自身显示占位符 */
  }
}

onMounted(() => {
  loadWifiSettings();
  loadWifiClients();
  loadWifiAcl();
  // 网络模式 / 漫游 / 拨号模式 / 休眠定时均包含在 /api/device/settings 的同一响应中，
  // 一次读取即可，无需为休眠时间单独请求
  loadDeviceSettings();
  refreshNetworkStatus();
  refreshSignal();
});

// 写操作缓存失效事件驱动的精确刷新（core 的 invalidateAll 合并串按逗号拆分）。
// DefaultLayout 的监听仅覆盖仪表盘 summary；本页数据源不同，须自行订阅。
// 保留 10 s 轮询，用于兜底广播丢失与设备面板直接修改。
const unsubNet = wsStore.on('data_changed', (payload: any) => {
  if (serviceStore.stopped) return;
  const changed: string = payload?.changed ?? '';
  for (const key of changed.split(',')) {
    if (key.startsWith('wifi:')) {
      loadWifiSettings();
      loadWifiClients();
    } else if (
      key === 'network:band-status' ||
      key === 'hub:network-type-info' ||
      // core 的 invalidateNetwork() 还会发送通配 key（具体 key 未命中缓存时仅剩此条）；
      // 飞行模式同样经由该 key 触发失效。
      key === 'network:*'
    ) {
      refreshNetworkStatus();
      refreshSignal();
    } else if (key === 'device:settings') {
      // 制式切换的写成功事件发生在设备尚未完成切换的时间窗内：此处的普通读会把旧档位
      // 缓存 5 分钟，影响后续回读确认与界面。切换期间由确认器执行 fresh 读。
      if (switchingMode.value === null) loadDeviceSettings();
    }
  }
});

// 仅挂载时读取一次可能得到 DefaultLayout 那次 summary 的旧状态；
// 因此采用与仪表盘一致的 10 s 轮询。
useInterval(() => {
  refreshNetworkStatus();
  loadWifiClients();
  // 制式切换期间不读取 device settings：普通读会把设备尚未完成切换时的旧档位写入
  // core 的 5 分钟缓存，从而覆盖确认器刚通过 fresh 直读取得的目标值。
  // 切换期间的回读由确认器负责（fresh 读），界面不会停止更新。
  if (switchingMode.value === null) loadDeviceSettings();
  // WS 已连接时由推送提供数据，避免重复请求；仅在断线时使用 REST 兜底
  if (wsStore.status !== 'connected') refreshSignal();
}, 10_000);

onUnmounted(() => {
  // 注销 data_changed 订阅，避免离开页面后仍执行刷新与弹出 toast
  unsubNet?.();
  // 组件销毁时弹窗的 @after-leave 不一定触发（例如整页路由切换），此处兜底 revoke，防止 blob 泄漏
  releaseQrUrl();
});
</script>

<style scoped>
.network-view {
  display: flex;
  flex-direction: column;
  gap: var(--space-4);
}

/* 两列栅格：DOM 顺序即位置（网络信息 | 网络设置 / WiFi 热点 | 局域网设备）。
   栅格本体（2 列、gap、`.full-width` 通栏、1024px 折单列）来自全局 `.settings-panel`；
   scoped 选择器特异性更高，此处仅用于覆盖与全局规则的差异。 */
.network-grid {
  /* stretch（而非全局的 start）：同一行两张卡片等高。
     使用 start 时矮卡贴顶，下方露出页面底色；stretch 使卡片边框补齐到行高，
     高度差表现为卡内留白而非页面底色外露。
     该属性仅作用于行内：行与行是独立轨道，第 2 行的设备清单再长也不会改变第 1 行
     卡片的排布（清单另有 max-height 封顶，见 LanDevicesCard）。
     折列断点为 1024px，由全局 `.settings-panel` 提供；`max-width: 900px` 断点已退役。 */
  align-items: stretch;
}
</style>

<template>
  <div class="network-view">
    <!--
      四卡布局（2026-09-03 建立，2026-09-10 按栅格等高约束重排）：
        行 1 = 网络信息 ｜ 更多设置      两列
        行 2 = WiFi 热点                 通栏
        行 3 = 局域网设备                 通栏
      顺序即位置 —— 两列网格按 DOM 顺序自动填格，不需要写 grid-area；通栏卡用 .full-width。
      窄屏（<1024px）塌成单列，顺序不变。

      为什么这么排（基于实测数据）：
      `.network-grid` 同一行的轨道等高、矮卡贴顶，所以同行两卡的高度差就是矮卡下方那块空白。
      改造前的顺序是「网络信息 ｜ WiFi 热点 / 局域网设备 ｜ 更多设置」，
      实测右列在「WiFi 热点」下面留了 68.5px 空洞、第二行两卡差 175px（局域网设备高度随
      在线设备数变化，0 台时只有 130px）。
      重排后两张参与配对的卡是「网络信息(316px) ｜ 更多设置(305px)」，差 11.5px；
      剩下两张各自通栏，不受行高约束：
        · WiFi 热点 —— 8 项 KV，通栏后 .kv-grid 从 2 列变 4 列，反而从 4 行压到 2 行；
        · 局域网设备 —— 本来就是「名称 / IP / MAC / 操作」四段式表格，582px 一列时最挤，
          通栏是它该有的宽度。
      主卡逻辑已抽到 components/cards/（props 下行 + 事件上行）；
      设备写操作与回读统一由 useNetworkControls 提供（与仪表盘共用同一份实现）。
    -->
    <div class="network-grid">
      <!-- 行 1 左：网络信息（连接状态 + 开关 + 信号质量） -->
      <NetworkInfoCard
        :net-status="netStatus"
        :signal="signal"
        :wifi-enabled="wifiEnabled"
        :mobile-data-saving="mobileDataSaving"
        :roaming-enabled="roamingEnabled"
        :roaming-saving="roamingSaving"
        :connection-mode="connectionMode"
        :conn-mode-options="connModeOptions"
        :conn-mode-saving="connModeSaving"
        @update:mobile-data="setMobileData"
        @toggle-wifi="toggleWifiEnabled"
        @update:roaming="setRoaming"
        @update:connection-mode="setConnectionMode"
        @airplane-on="setAirplane(true)"
        @airplane-off="setAirplane(false)"
        @ppp-connect="pppConnect"
        @ppp-disconnect="pppDisconnect"
      />

      <!-- 行 1 右：更多设置（六个入口瓦片） -->
      <MoreSettingsCard
        :mode-label="modeTileLabel"
        :sleep-time="sleepTime"
        @open-mode="showModeModal = true"
        @open-band="bandModal.open()"
        @open-speed="speedModal.open()"
        @open-sleep="showSleepModal = true"
        @open-module="moduleModal.open()"
        @open-cell="cellModal.open()"
      />

      <!-- 行 2（通栏）：WiFi 热点。class 透传到 GridCard 根节点，配合 main.css 的
           `.network-grid > .full-width` 生效。 -->
      <WifiHotspotCard
        class="full-width"
        :loading="wifiLoading"
        :wifi-enabled="wifiEnabled"
        :settings="wifiSettings"
        :client-count="wifiClients.length"
        @open-qr="openQrModal"
        @edit="showWifiEditModal = true"
      />

      <!-- 行 3（通栏）：局域网设备。高度随在线设备数变化，通栏后不参与等高配对。 -->
      <LanDevicesCard
        class="full-width"
        :clients="wifiClients"
        :blocked-list="blockedList"
        :blocked-macs="blockedMacs"
        :acl-pending="aclPending"
        @open-acl="showAclModal = true"
        @block="blockDevice"
        @unblock="unblockDevice"
      />
    </div>

    <!-- 弹窗子组件（逻辑已抽到 components/modals/，重型 4 个按需懒加载） -->
    <NetworkModeModal
      v-model:show="showModeModal"
      v-model:selected-mode="selectedMode"
      :mode-loading="modeLoading"
      :network-modes="networkModes"
      :switching="switchingMode !== null"
      :timed-out="modeSwitchTimedOut"
      @apply="applyModeAndClose"
    />

    <!-- 懒加载弹窗：组件本体与显隐都由 useLazyModal 管，
         这样进出场动画不会因为「首帧 show 就是 true」而丢掉 -->
    <component :is="bandComponent" v-if="bandComponent" :show="bandShow" @update:show="bandModal.setShow" />
    <component :is="speedComponent" v-if="speedComponent" :show="speedShow" @update:show="speedModal.setShow" />

    <WifiEditModal v-model:show="showWifiEditModal" :wifi-settings="wifiSettings" @saved="handleWifiSaved" />

    <QrModal
      v-model:show="showQrModal"
      :qr-url="qrUrl"
      :qr-loading="qrLoading"
      :qr-error="qrError"
      :ssid="wifiSettings?.ssid"
      @refresh="reloadQr"
      @released="releaseQrUrl"
    />

    <SleepModal
      v-model:show="showSleepModal"
      v-model:sleep-time="sleepTime"
      :sleep-loading="sleepLoading"
      :sleep-saving="sleepSaving"
      @save="saveSleepTimer"
    />

    <AclModal
      v-model:show="showAclModal"
      :blocked-list="blockedList"
      :acl-pending="aclPending"
      @unblock="unblockDevice"
      @clear="clearBlockedDevices"
    />

    <component :is="moduleComponent" v-if="moduleComponent" :show="moduleShow" @update:show="moduleModal.setShow" />
    <component :is="cellComponent" v-if="cellComponent" :show="cellShow" @update:show="cellModal.setShow" />
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted } from 'vue';
import { useInterval } from '@/composables/useRealtime';
import { useDashboardStore } from '@/stores/dashboard';
import { useWebSocketStore } from '@/stores/websocket';
import { useCancellableApi } from '@/composables/useCancellableApi';
import { useNetworkControls } from '@/composables/useNetworkControls';
import { useLazyModal } from '@/composables/useLazyModal';
import { get } from '@/composables/utils';

// 主卡（受控子组件：props 下行 + 事件上行）
import NetworkInfoCard from './components/cards/NetworkInfoCard.vue';
import WifiHotspotCard from './components/cards/WifiHotspotCard.vue';
import LanDevicesCard from './components/cards/LanDevicesCard.vue';
import MoreSettingsCard from './components/cards/MoreSettingsCard.vue';

// 受控弹窗（模板 + 本地 UI 态在子组件）
// NetworkMode / Qr / Acl / BandLock 已提到 src/components/modals/：仪表盘也要用，
// 不能让它们继续住在 views/network 内部（否则就是「仪表盘 import 网络页私有组件」）。
import NetworkModeModal from '@/components/modals/NetworkModeModal.vue';
import QrModal from '@/components/modals/QrModal.vue';
import AclModal from '@/components/modals/AclModal.vue';
import WifiEditModal from './components/modals/WifiEditModal.vue';
import SleepModal from './components/modals/SleepModal.vue';

// 频段锁定 / 测速 / 基站 / 模块信息 4 个重型弹窗按需加载，
// 组件本体与显隐时序由 useLazyModal 管（见下方 bandModal 等），不用 defineAsyncComponent。

const dashboardStore = useDashboardStore();
const wsStore = useWebSocketStore();
const api = useCancellableApi();

/**
 * 全部设备写操作与配套回读来自 useNetworkControls —— 本页与仪表盘共用同一份实现。
 * **不要在本文件另写 handler**：写完等多久才回读、哪些失败也要回读、哪些端点用
 * 200 + success:false 表示失败，这三条设备约定都写在那个 composable 里，抄一份必漏。
 */
const {
  loadDeviceSettings,
  loadWifiSettings,
  loadWifiClients,
  loadWifiAcl,
  refreshNetworkStatus,
  networkModes,
  selectedMode,
  modeLoading,
  modeLabel,
  applyNetworkMode,
  switchingMode,
  modeSwitchTimedOut,
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
} = useNetworkControls();

const netStatus = computed(() => get(dashboardStore.summary, 'network_status', null));
const signal = computed(() => dashboardStore.realtimeSignal);

// ── 弹窗开启标志（弹窗自身逻辑在子组件，本组件只负责开门）──
const showModeModal = ref(false);
const showWifiEditModal = ref(false);
const showQrModal = ref(false);
const showSleepModal = ref(false);
const showAclModal = ref(false);

/**
 * 4 个懒加载弹窗的挂载 / 显隐时序统一交给 useLazyModal。
 * 原来是 `v-if="showXxx" v-model:show="showXxx"`，进出场动画全被吃掉：
 * 进场时首帧 show 就是 true（Transition 默认不在挂载时播动画），
 * 出场时 v-if 与 show 同一 tick 变 false，组件直接被销毁。
 */
const bandModal = useLazyModal(() => import('@/components/modals/BandLockModal.vue'));
const speedModal = useLazyModal(() => import('./components/modals/SpeedTestModal.vue'));
const cellModal = useLazyModal(() => import('./components/modals/CellInfoModal.vue'));
const moduleModal = useLazyModal(() => import('./components/modals/ModuleInfoModal.vue'));
const bandComponent = bandModal.component;
const bandShow = bandModal.show;
const speedComponent = speedModal.component;
const speedShow = speedModal.show;
const cellComponent = cellModal.component;
const cellShow = cellModal.show;
const moduleComponent = moduleModal.component;
const moduleShow = moduleModal.show;

function applyModeAndClose() {
  // 下发一被受理就关弹窗：回读确认要等设备重新注册（最长十几秒），
  // 不该让用户对着一个空转的弹窗等 —— 「切换中」由更多设置卡上的制式标签呈现。
  showModeModal.value = false;
  applyNetworkMode();
}

/**
 * 更多设置卡上的制式标签。
 *
 * 切换期间显示目标档位 +「切换中」，超时显示「尚未完成」——
 * 直接显示回读值会让界面停在切换前的档位（2026-09-11 真机缺陷）。
 */
const modeTileLabel = computed(() => {
  if (switchingMode.value !== null) return `${modeLabel.value} · 切换中`;
  if (modeSwitchTimedOut.value) return `${modeLabel.value} · 尚未完成`;
  return modeLabel.value;
});

/**
 * 二维码取数要知道「请求回来时弹窗还开着吗」：关掉后再建 object URL 就没人 revoke 了。
 * 弹窗显隐归本组件，所以这个判断以回调形式传进 composable。
 */
const reloadQr = () => loadQrCode(() => showQrModal.value);
function openQrModal() {
  showQrModal.value = true;
  if (!qrUrl.value) reloadQr();
}
/** WiFi 配置一改，旧二维码就是错的：composable 负责丢缓存，这里告诉它弹窗是否还开着 */
const handleWifiSaved = () => onWifiSaved(showQrModal.value);

/**
 * 信号质量的 REST 兜底。
 *
 * 「信号质量」卡读的是 dashboardStore.realtimeSignal，正常由 WS 的 signal 频道喂养
 * （订阅已统一放在 DefaultLayout）。但两种情况下光靠 WS 会空着：
 * ① core 的采集循环约 10s 一推，直接进本页要等下一次推送；
 * ② WS 断线重连期间完全没有推送。
 * 所以挂载时补一次 REST，并在 WS 未连接时跟着 10s 轮询走。
 *
 * 留在本页而不进 composable：它只是这张卡的兜底读，不属于「设备控制」。
 */
async function refreshSignal() {
  try {
    const { data } = await api.get('/api/network/signal');
    if (data) dashboardStore.updateRealtime('signal', data);
  } catch {
    /* 静默：卡片自己会显示 — */
  }
}

onMounted(() => {
  loadWifiSettings();
  loadWifiClients();
  loadWifiAcl();
  // 网络模式 / 漫游 / 拨号模式 / 休眠定时都在 /api/device/settings 这一个响应里，
  // 一次读全，不再为休眠时间单独发一遍请求
  loadDeviceSettings();
  refreshNetworkStatus();
  refreshSignal();
});

// 之前本页只在挂载时取一次，进页面看到的可能是 DefaultLayout 那次 summary 的旧状态；
// 与仪表盘保持一致的 10s 轮询
useInterval(() => {
  refreshNetworkStatus();
  loadWifiClients();
  loadDeviceSettings();
  // WS 在跑就让它推，避免重复请求；断线时才用 REST 顶上
  if (wsStore.status !== 'connected') refreshSignal();
}, 10_000);

onUnmounted(() => {
  // 组件销毁时弹窗的 @after-leave 不一定会触发（如整页路由切换），兜底 revoke 防止 blob 泄漏
  releaseQrUrl();
});
</script>

<style scoped>
.network-view {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

/* 四卡两列：DOM 顺序即位置（网络信息/WiFi/局域网设备/更多设置），窄屏塌成单列 */
.network-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 16px;
  /* 同一行等高、矮卡贴顶：矮卡下方会留出页面底色，所以同一行的两张卡高度必须接近。
     高度没法保证的卡（WiFi 热点 / 局域网设备）走通栏，见模板顶部注释。 */
  align-items: start;
}

/* 通栏卡。用 :deep() 而不是裸类名：这里的子元素是卡片组件，
   类名落在子组件根节点上，写 [data-v-本组件] 的直系子选择器才不依赖
   「父组件 scope id 会加到子组件根节点」这条规则。 */
.network-grid > :deep(.full-width) {
  grid-column: 1 / -1;
}

@media (max-width: 1024px) {
  .network-grid {
    grid-template-columns: 1fr;
  }
}
</style>

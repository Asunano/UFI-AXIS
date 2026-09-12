<template>
  <div class="dashboard">
    <!-- 顶栏：流量 + 网络 横向铺开。
       这两块是最常看的概览，单独占最上面一整栏；右栏只留 WiFi 与连接设备，
       否则右栏 4 张卡纵向堆下来会比左栏高出一大截。 -->
    <div class="dashboard-top">
      <TrafficUsageCard @open-limit="showTrafficModal = true" />
      <NetworkInfoCard
        :roaming-enabled="roamingEnabled"
        :connection-mode="connectionMode"
        :mobile-data-saving="mobileDataSaving"
        @set-mobile-data="setMobileData"
        @open-mode="showModeModal = true"
        @open-band="bandModal.open()"
      />
    </div>

    <div class="dashboard-grid">
      <!-- 左侧：系统状态 + 网络详情 -->
      <div class="dashboard-left">
        <SystemStatusCard />
        <!-- 网络详情 / 信号详情 折线图（独立组件承载整段 echarts 机件） -->
        <NetDetailChartCard />
      </div>

      <!-- 右侧：WiFi 信息 → 连接设备（流量与网络已提到顶栏） -->
      <div class="dashboard-right">
        <WifiInfoCard
          :wifi-settings="wifiSettings"
          :wifi-enabled="wifiEnabled"
          :qr-url="qrUrl"
          :qr-loading="qrLoading"
          :qr-error="qrError"
          @toggle-enabled="toggleWifiEnabled"
          @open-settings="showWifiModal = true"
        />
        <ClientsCard
          :clients="wifiClients"
          :blocked-list="blockedList"
          :blocked-macs="blockedMacs"
          :acl-pending="aclPending"
          @block="blockDevice"
          @unblock="unblockDevice"
          @open-acl="showAclModal = true"
        />
      </div>
    </div>

    <!-- 弹窗：受控子组件，保存逻辑留在父组件以保证行为不变 -->
    <WifiModal v-model:show="showWifiModal" :wifi-settings="wifiSettings" @save="saveWifiEdit" />
    <TrafficLimitModal v-model:show="showTrafficModal" :traffic-limit="trafficLimit" @save="saveTrafficLimit" />

    <!-- 与网络页共用的弹窗（同一份组件、同一份 handler） -->
    <NetworkModeModal
      v-model:show="showModeModal"
      v-model:selected-mode="selectedMode"
      :mode-loading="modeLoading"
      :network-modes="networkModes"
      @apply="applyModeAndClose"
    />
    <!-- 懒加载弹窗一律走 useLazyModal，否则首帧 show 就是 true，进出场动画全丢 -->
    <component :is="bandComponent" v-if="bandComponent" :show="bandShow" @update:show="bandModal.setShow" />
    <AclModal
      v-model:show="showAclModal"
      :blocked-list="blockedList"
      :acl-pending="aclPending"
      @unblock="unblockDevice"
      @clear="clearBlockedDevices"
    />
  </div>
</template>

<script setup lang="ts">
/**
 * 仪表盘：只负责取数编排、设备写操作与弹窗，卡片全部拆到 components/cards/。
 *
 * 为什么设备状态不下沉到卡里：`useNetworkControls()` **每次调用都新建一套 ref**
 * （见该文件，里面没有模块级单例）。卡片自己调一次就会拿到第二份互不相干的状态 ——
 * 这里轮询刷新的是自己那份，卡片永远显示初始值；拉黑写完覆盖的名单也和弹窗看到的不是同一份。
 * 所以 composable 只在本文件调用一次，卡片收 props、把动作 emit 回来。
 *
 * 反过来，纯读 dashboardStore 的卡（流量、系统状态、网络信息里的 network_status）
 * 直接读 store —— Pinia 是单例，不存在分叉，多传一层 props 只是噪音。
 */
import { ref, onMounted, onUnmounted, computed } from 'vue';
import { useMessage } from 'naive-ui';
import { useDashboardStore } from '@/stores/dashboard';
import { useCancellableApi } from '@/composables/useCancellableApi';
import { useInterval } from '@/composables/useRealtime';
import { useNetworkControls } from '@/composables/useNetworkControls';
import { useLazyModal } from '@/composables/useLazyModal';
import { get } from '@/composables/utils';
import TrafficUsageCard from './components/cards/TrafficUsageCard.vue';
import NetworkInfoCard from './components/cards/NetworkInfoCard.vue';
import SystemStatusCard from './components/cards/SystemStatusCard.vue';
import WifiInfoCard from './components/cards/WifiInfoCard.vue';
import ClientsCard from './components/cards/ClientsCard.vue';
import NetDetailChartCard from './components/NetDetailChartCard.vue';
import WifiModal from './components/modals/WifiModal.vue';
import TrafficLimitModal from './components/modals/TrafficLimitModal.vue';
// 与网络页共用的弹窗（已从 views/network 提到 src/components/modals）
import NetworkModeModal from '@/components/modals/NetworkModeModal.vue';
import AclModal from '@/components/modals/AclModal.vue';
// 频段锁定较重，按需加载；组件本体与显隐时序由 useLazyModal 管（见 bandModal）
import type { WifiSettings } from '@/types';

const dashboardStore = useDashboardStore();
const message = useMessage();
const api = useCancellableApi();

// 设备 goform 写入到查询接口能看到新值之间有延迟，写完立刻回读拿到的还是旧值，
// 会把界面刷回改动前的状态；回读前先等一下（Android app 同样等 500ms 左右）
const settleDelay = () => new Promise((resolve) => setTimeout(resolve, 600));

/** 只给限额弹窗回填用；卡片自己从 store 读，不经这里 */
const trafficLimit = computed(() => get(dashboardStore.summary, 'traffic_limit', null));

// ── 设备控制 ──
// 移动数据 / 网络模式 / WiFi 开关 / 二维码 / 拉黑名单全部走 useNetworkControls，
// 与网络页共用同一份实现。**不要在本文件另写 handler**：写完等多久回读、哪些失败也要回读、
// 哪些端点用 200 + success:false 表示失败，这些约定都在那个 composable 里。
const {
  loadDeviceSettings,
  loadWifiSettings,
  loadWifiClients,
  loadWifiAcl,
  refreshNetworkStatus,
  networkModes,
  selectedMode,
  modeLoading,
  applyNetworkMode,
  roamingEnabled,
  connectionMode,
  mobileDataSaving,
  setMobileData,
  wifiSettings,
  wifiClients,
  wifiEnabled,
  toggleWifiEnabled,
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

// ── 弹窗显隐（打开由卡片按钮置位，表单回填由子组件在 watch(show) 内完成）──
const showWifiModal = ref(false);
const showTrafficModal = ref(false);
const showModeModal = ref(false);
const showAclModal = ref(false);

// 频段锁定是懒加载弹窗：挂载与显隐必须分两拍，否则首帧 show 就是 true、进出场动画全丢
const bandModal = useLazyModal(() => import('@/components/modals/BandLockModal.vue'));
const bandComponent = bandModal.component;
const bandShow = bandModal.show;

function applyModeAndClose() {
  applyNetworkMode().finally(() => {
    showModeModal.value = false;
  });
}

// ── WiFi 设置保存（逻辑保持原样：带 encryp_type / max_sta_num / chip_index 当前值，避免改 SSID 顺手改坏）──
interface WifiSavePayload {
  enabled: boolean;
  ssid: string;
  password: string;
  auth_mode: string;
  broadcastHidden: boolean;
  cur: WifiSettings | null;
}
async function saveWifiEdit(payload: WifiSavePayload) {
  try {
    const cur = payload.cur;
    // core WifiRoutes.kt 读的是 passphrase（不是 password）；encryp_type / max_sta_num 不传时
    // core 会把 EncrypType 硬写成 CCMP 且完全不下发 ApMaxStationNumber
    // （GoformWifiClient.kt:108,121），所以带上设备当前值，别让改 SSID 顺手改掉这两项。
    // chip_index 在写接口里是 "0"/"1"（chip1=2.4G → "0"，chip2=5G → "1"）
    await api.post('/api/wifi/config', {
      ssid: payload.ssid,
      passphrase: payload.password,
      auth_mode: payload.auth_mode,
      encryp_type: cur?.encryp_type || undefined,
      max_sta_num: cur?.max_sta_num || undefined,
      chip_index: cur?.chip_index === '2' ? '1' : '0',
      broadcast_disabled: payload.broadcastHidden ? 1 : 0,
    });
    // 开关是独立接口，只有真的变了才下发，避免每次保存都重启一遍 WiFi 模块
    if (cur && payload.enabled !== cur.enabled) {
      await api.post('/api/wifi/enable', { enabled: payload.enabled });
    }
    message.success('WiFi 设置已保存');
    showWifiModal.value = false;
  } catch {
    message.error('保存失败');
  }
  // 设备写入到查询接口可见有延迟，立刻回读会拿回改动前的 SSID / 加密方式
  await settleDelay();
  loadWifiSettings();
  loadWifiClients();
  // WiFi 配置一改，按旧 SSID/密码生成的二维码就是错的：丢掉旧 blob 重新取一张
  releaseQrUrl();
  loadQrCode();
}

// ── 流量限额保存 ──
// goform 限额单位靠乘数表达：1=MB、1024=GB、1048576=TB
interface TrafficSavePayload {
  enabled: boolean;
  limit_size: number;
  limit_unit: string;
  alert_percent: number;
  auto_clear: boolean;
  clear_date: number;
}
async function saveTrafficLimit(payload: TrafficSavePayload) {
  try {
    // 结构化下发：数值 + 单位。设备侧的「数值_乘数」复合串（如 "470_1024"）由 core 拼
    // （core 2.8），前端不再关心乘数映射。
    await api.post('/api/device/data-limit', {
      enabled: payload.enabled,
      limit_value: payload.limit_size,
      limit_unit: payload.limit_unit,
      alert_percent: payload.alert_percent,
      auto_clear: payload.auto_clear,
      clear_date: payload.clear_date,
    });
    message.success('流量限额已保存');
    showTrafficModal.value = false;
    // 设备写入到查询接口可见有延迟，立刻回读会拿到旧值
    await settleDelay();
    loadSummary();
  } catch {
    message.error('保存失败');
    await settleDelay();
    loadSummary();
  }
}

// ── 数据加载 ──
onMounted(() => {
  loadSummary();
  loadMemory();
  loadCpu();
  loadTrafficSummary();
  loadDeviceInfo();
  refreshNetworkStatus();
  loadDeviceSettings();
  loadWifiClients();
  loadWifiAcl();
  // 二维码要按当前频段（chip1/chip2）取，所以等 WiFi 设置到手再拉
  loadWifiSettings().then(() => loadQrCode());
});

useInterval(() => {
  loadSummary();
  loadTrafficSummary();
}, 10_000);
useInterval(() => {
  loadDeviceInfo();
  refreshNetworkStatus();
  loadDeviceSettings();
}, 10_000);
useInterval(() => {
  // 二维码不跟着轮询重取：它是 blob，只在挂载与改完 WiFi 配置后取
  loadWifiSettings();
  loadWifiClients();
}, 10_000);

onUnmounted(() => {
  // 弹窗的 @after-leave 在整页路由切换时不一定触发，兜底 revoke 防止 blob 泄漏
  releaseQrUrl();
});

async function loadSummary() {
  dashboardStore.loading = true;
  try {
    const { data } = await api.get('/api/dashboard/summary');
    dashboardStore.updateSummary(data);
  } catch {
    // 静默失败
  } finally {
    dashboardStore.loading = false;
  }
}

async function loadMemory() {
  try {
    const { data } = await api.get('/api/system/memory');
    dashboardStore.updateRealtime('memory', data);
  } catch {
    // 静默失败
  }
}

async function loadCpu() {
  try {
    const { data } = await api.get('/api/system/cpu');
    dashboardStore.updateRealtime('cpu', data);
  } catch {
    // 静默失败
  }
}

async function loadTrafficSummary() {
  try {
    const { data } = await api.get('/api/traffic/summary');
    const summary = dashboardStore.summary;
    if (summary) {
      summary.traffic_summary = data;
      dashboardStore.updateSummary({ ...summary });
    }
  } catch {
    // 静默失败
  }
}

/**
 * 只取 /api/device/info（品牌/型号/固件等静态信息），合并进 summary 供顶部设备条使用。
 * 网络状态与设备设置（漫游/拨号模式/网络模式）分别由 composable 的
 * refreshNetworkStatus / loadDeviceSettings 负责 —— 那两个端点写操作后也要回读，
 * 放在 composable 里才能和写操作共用同一份回读逻辑。
 */
async function loadDeviceInfo() {
  try {
    const { data: device } = await api.get('/api/device/info');
    if (!device) return;
    if (dashboardStore.summary) {
      dashboardStore.summary.device_info = device;
      dashboardStore.updateSummary({ ...dashboardStore.summary });
    } else {
      dashboardStore.updateSummary({ device_info: device } as any);
    }
  } catch {
    // 静默失败
  }
}
</script>

<style scoped>
.dashboard {
  display: flex;
  flex-direction: column;
  gap: 16px;
  /* 宽屏下整页刚好铺满内容区、自身不滚动：高度交给内部按 flex 分配，
     多出来的空间给折线图卡吃掉（见 .chart-card）。窄屏恢复自然高度并允许滚动。 */
  height: 100%;
  min-height: 0;
}

/* 顶栏：流量 + 网络，两张卡等宽等高并排（窄屏折成单列，见下方媒体查询） */
.dashboard-top {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 16px;
  /* stretch 而不是 start：两张卡内容行数不同，必须拉到同高，否则一高一矮 */
  align-items: stretch;
}
.dashboard-top > .panel-card {
  height: 100%;
}

.dashboard-grid {
  display: grid;
  /* 右栏宽度跟着窗口走（原来写死 360px：窗口一变只压缩左栏，右栏几张卡完全不自适应）。
     clamp 兜住上下限，免得超宽屏时右栏拉得比左栏还宽、窄屏时挤成一条。 */
  grid-template-columns: minmax(0, 1fr) clamp(280px, 26%, 420px);
  gap: 16px;
  align-items: stretch;
  flex: 1;
  min-height: 0;
}

.dashboard-left,
.dashboard-right {
  display: flex;
  flex-direction: column;
  gap: 16px;
  min-height: 0;
}
/* flex 项默认 flex-shrink:1 —— 空间不够时会把卡片本身压扁（内容溢出到卡片外，
   看起来就像 WiFi 与连接设备糊成一张卡、折线图也被挤变形）。
   两栏各有一张「吃掉剩余高度」的卡（左：折线图，右：连接设备），其余锁成自然高度。
   `.panel-card` / `.chart-card` / `.clients-card` 现在写在各自的卡组件里，
   但子组件根节点会同时带上本文件的 scope id，所以这些选择器照样命中。 */
.dashboard-left > .panel-card:not(.chart-card),
.dashboard-right > .panel-card:not(.clients-card) {
  flex: 0 0 auto;
}
/* 连接设备：与左栏折线图卡对称地吃掉本栏剩余高度，设备再多也只是列表内部滚动，
   不会把右栏越撑越长、跟左栏错位。min-height 兜住极矮窗口。
   规则留在本文件而不是 ClientsCard：它属于「右栏怎么分高度」的布局决策，
   且下面两处媒体查询要把它解锁，基础规则与覆盖规则分居两个文件更难读。 */
.clients-card {
  flex: 1 1 auto;
  min-height: 200px;
  display: flex;
  flex-direction: column;
}
.clients-card :deep(.grid-card-body) {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
}
/* 两栏都不再自己滚：各自那张伸缩卡已经把溢出吃在内部了。
   矮屏 / 窄屏回退时（见下方媒体查询）才需要整页滚动。 */

/* .panel-card 现在只是布局钩子：卡壳（圆角/描边/头部/内边距）全部由 GridCard 提供。
   原来这里自绘 n-card + 手抄一份 12px 16px 16px 的 body padding，与 GridCard 的默认档
   逐字相同，是同一个卡壳的第二份实现。 */

/* 视口太矮时"刚好铺满"会把折线图压到不可读：回退成整页滚动，
   两栏的伸缩卡也解锁成自然高度（设备列表直接列全，不再内部滚）。
   阈值与 DefaultLayout 的矮屏压缩共用 720px（原先这里写 760，是第四套断点）。 */
@media (max-height: 720px) {
  .dashboard {
    height: auto;
  }
  .dashboard-grid {
    flex: none;
  }
  .clients-card {
    flex: none;
  }
  .clients-card :deep(.grid-card-body) {
    overflow-y: visible;
  }
}

@media (max-width: 1024px) {
  /* 单列排布时内容必然高于视口，恢复自然高度、交回给内容区滚动 */
  .dashboard {
    height: auto;
  }
  .dashboard-top {
    grid-template-columns: 1fr;
  }
  .dashboard-grid {
    grid-template-columns: 1fr;
    flex: none;
  }
  /* 单列时上下堆叠，「跟折线图齐平」不成立，设备列表列全即可 */
  .clients-card {
    flex: none;
  }
  .clients-card :deep(.grid-card-body) {
    overflow-y: visible;
  }
}
</style>

<template>
  <div class="dashboard">
    <!-- 顶栏：流量 + 网络 横向铺开。
       这两块是最常看的概览，单独占最上面一整栏；右栏只留 WiFi 与连接设备，
       否则右栏 4 张卡纵向堆下来会比左栏高出一大截。 -->
    <div class="dashboard-top">
      <TrafficUsageCard @open-limit="showTrafficModal = true" />
      <NetworkInfoCard
        :roaming-enabled="roamingEnabled"
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
          :wifi-loading="wifiPending"
          :qr-url="qrUrl"
          :qr-loading="qrLoading"
          :qr-error="qrError"
          @toggle-enabled="toggleWifiEnabled"
          @open-settings="showWifiModal = true"
          @retry="refreshWifiSettings"
        />
        <ClientsCard
          :clients="wifiClients"
          :loading="clientsLoading"
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
    <WifiModal ref="wifiModalRef" v-model:show="showWifiModal" :wifi-settings="wifiSettings" @save="saveWifiEdit" />
    <TrafficLimitModal v-model:show="showTrafficModal" :traffic-limit="trafficLimit" @save="saveTrafficLimit" />

    <!-- 与网络页共用的弹窗（同一份组件、同一份 handler）
         switching / timed-out 必须传：不传的话仪表盘这份弹窗看不到「正在切换 / 尚未完成」，
         同一个操作在两个页面上的反馈会不一致 -->
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
    <!-- 懒加载弹窗一律走 useLazyModal，否则首帧 show 就是 true，进出场动画全丢 -->
    <component :is="bandComponent" v-if="bandComponent" :show="bandShow" @update:show="bandModal.setShow" />
    <AclModal
      v-model:show="showAclModal"
      :blocked-list="blockedList"
      :acl-pending="aclPending"
      @unblock="unblockDevice"
      @clear="clearBlockedDevices"
    />

    <!--
      2026-10-14：这里原来挂着两份 OperationProgressModal（WiFi 开关一份、制式切换一份），
      而进度状态是各页自己的 composable 实例 —— "在仪表盘点 WiFi 开关、切到网络页"进度就没了
      （反过来一样）。现在**全仓只有 DefaultLayout 挂一份**，状态在 stores/networkOps：
      从哪一页发起、切到哪一页，看到的都是同一份进度与结论。
    -->
  </div>
</template>

<script setup lang="ts">
/**
 * 仪表盘：只负责取数编排、设备写操作与弹窗，卡片全部拆到 components/cards/。
 *
 * 为什么设备状态不下沉到卡里：`useNetworkControls()` **每次调用都新建一套 ref**
 * （见该文件，里面没有模块级单例）—— 唯一的例外是 A 级操作的进度状态：2026-10-14 起
 * 它住在 `stores/networkOps`（那批操作比页面活得久，弹窗也只在 DefaultLayout 挂一份）。
 * 卡片自己调一次 composable 就会拿到第二份互不相干的状态 ——
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
import { buildWifiConfigPayload } from '@/api/contract';
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
// 2026-10-14：A 级操作的进度弹窗已统一挂到 DefaultLayout（状态在 stores/networkOps），
// 本页不再 import OperationProgressModal —— 见模板末尾的说明。
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
  currentMode,
  modeLoading,
  modeRefreshing,
  refreshDeviceSettings,
  modeLabel,
  applyNetworkMode,
  // 「切换中 / 尚未完成」中间态：与网络页共用同一份 composable 状态
  switchingMode,
  modeSwitchTimedOut,
  roamingEnabled,
  mobileDataSaving,
  setMobileData,
  wifiSettings,
  wifiClients,
  wifiEnabled,
  // `wifiLoading` 只用来给 WiFi 信息卡区分「还在读」与「读取失败」；
  // 连不上时 wifiSettings 恒为 null（loadWifiSettings 的 catch 不回写），所以 null 就是失败态
  wifiLoading,
  toggleWifiEnabled,
  // 2026-10-14：A 级操作的状态与进度弹窗统一在 stores/networkOps + DefaultLayout，
  // 本页不再持有 wifiOp / modeOpState（见 useNetworkControls 的导出说明）
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

// ── 卡片三态的加载位 ──
/*
 * WiFi 信息卡：`loadWifiSettings` 失败时只吞掉错误、不回写 `wifiSettings`（首次失败就是 null），
 * 所以「null = 没读到」成立，用它当失败态。但首帧（setup 到 onMounted 之间）也是 null 且不在加载中，
 * 只按这两个信号判会闪一下「读取失败」—— 因此额外记一个「是否已经尝试过」。
 */
const wifiAttempted = ref(false);
const wifiPending = computed(() => wifiLoading.value || !wifiAttempted.value);

function refreshWifiSettings() {
  return loadWifiSettings().finally(() => {
    wifiAttempted.value = true;
  });
}

/*
 * 连接设备卡：composable 的 `loadWifiClients` 把失败吞成空数组（catch 里 `= []`），
 * 空列表与读取失败在 web 侧无法区分 —— 这里只能维护「首次取数是否结束」，
 * 至少让加载期不再显示「暂无设备连接」。彻底区分需要改 composable（报告里已列出）。
 */
const clientsLoading = ref(true);

function refreshWifiClients() {
  return loadWifiClients().finally(() => {
    clientsLoading.value = false;
  });
}

// ── 弹窗显隐（打开由卡片按钮置位，表单回填由子组件在 watch(show) 内完成）──
const showWifiModal = ref(false);
const showTrafficModal = ref(false);
const showModeModal = ref(false);
const showAclModal = ref(false);

// 频段锁定是懒加载弹窗：挂载与显隐必须分两拍，否则首帧 show 就是 true、进出场动画全丢
const bandModal = useLazyModal(() => import('@/components/modals/BandLockModal.vue'));
const bandComponent = bandModal.component;
const bandShow = bandModal.show;

// 2026-10-14：原来这里还有 closeModeOp 与一个 `const noop = () => {}` —— 前者收敛各页自己那份
// 进度状态位，后者只用来喂制式切换弹窗的 `:to-background`（一颗点了没反应的假按钮）。
// 进度状态与弹窗都统一到 stores/networkOps + DefaultLayout 之后，两者都没有存在意义了。
const wifiModalRef = ref<InstanceType<typeof WifiModal> | null>(null);

function applyModeAndClose() {
  /*
   * 2026-10-14：改成与网络页同构 —— **先关配置弹窗，再下发**。
   *
   * 改前是 `applyNetworkMode().finally(() => (showModeModal = false))`：制式切换的回读确认
   * 最长十几秒，于是仪表盘上这张配置弹窗会一直开着，同时全局进度弹窗也在跑 —— 两个模态叠在一起，
   * 后挂载的那个还压在进度上面（用户看不到真正的进度）。网络页一直是"先关再下发"，
   * 现在两页一致：进度由全局进度弹窗呈现，配置弹窗立即退场。
   */
  showModeModal.value = false;
  applyNetworkMode();
}

// ── WiFi 设置保存（报文统一由 contract.buildWifiConfigPayload 拼，规则见那里）──
interface WifiSavePayload {
  enabled: boolean;
  ssid: string;
  password: string;
  auth_mode: string;
  maxStaNum: number | null;
  broadcastHidden: boolean;
  cur: WifiSettings | null;
}
async function saveWifiEdit(payload: WifiSavePayload) {
  // 2026-10-05 P3 修复：保存结果回传 WifiModal（notifySaved）——ok 才关窗，失败显示 core 具体原因
  const cur = payload.cur;
  // 开关是独立接口，只有真的变了才下发，避免每次保存都重启一遍 WiFi 模块
  const switchChanged = !!cur && payload.enabled !== cur.enabled;
  let ok = false;
  let failText: string | undefined;
  try {
    // 报文里 passphrase 的 OPEN 特例、encryp_type 的配对/透传、broadcast_disabled 的方向
    // 都在 buildWifiConfigPayload 里（与网络页的 WifiEditModal 共用同一份规则）。
    // 频段不在这个表单里（本弹窗没有频段控件），换频段是 POST /api/wifi/band 的独立动作
    await api.post(
      '/api/wifi/config',
      buildWifiConfigPayload({
        ssid: payload.ssid,
        authMode: payload.auth_mode,
        passphrase: payload.password,
        maxStaNum: payload.maxStaNum,
        hidden: payload.broadcastHidden,
        fallbackEncrypType: cur?.encryp_type,
      })
    );
    ok = true;
    message.success('WiFi 设置已保存');
  } catch (e: any) {
    // 具体原因替代笼统文案：能拿到 core 的 error 就透出
    failText = e?.response?.data?.error || e?.message || '保存失败';
  }

  /*
   * 2026-10-14 修（审计 G1：这个弹窗的 WiFi 开关绕过了 A 级入口）。
   *
   * 改前：这里直发 `POST /api/wifi/enable`，然后 `settleDelay()`（600ms）+ **单次**
   * `loadWifiSettings()`。而设备侧的「开/关 WiFi」是 `switchWiFiChip` / `switchWiFiModule`，
   * 会重启 WiFi 模块（`GoformWifiClient.kt:469-489`）——600ms 时模块还没起来，这次回读拿到的
   * 仍是旧状态，界面停在改动前直到下一轮 10s 轮询。同一个开关在网络页卡片上走的是
   * `useNetworkControls.toggleWifiEnabled`（下发 + 13 次回读确认，见 contract 的
   * `NetworkModeSwitchProbe`）：**一个开关两条写路径、两套节奏**，两边表现还不一样。
   *
   * 现在：本文件**不再自己发 `/api/wifi/enable`**，开关唯一写路径 = `toggleWifiEnabled`（A 级）。
   * 先关窗再下发（与 applyModeAndClose 同构）：A 级回读最长 30.6s，不该让配置弹窗空转，
   * 进度与结论交给全局进度弹窗（DefaultLayout 挂的唯一一份）。
   *
   * 已知取舍：A 级有全站并发闸门，若此刻已有别的 A 级操作在跑，这次开关会被拒
   * （弹「已有操作正在进行」）——配置已经保存成功，开关需要用户稍后再点一次。
   * 这与网络页卡片上点开关的行为完全一致，不再出现"两个入口两套结果"。
   */
  if (ok && switchChanged) {
    // 旧二维码立刻作废：SSID/密码这次一起改了，按旧配置生成的图已经是错的
    releaseQrUrl();
    wifiModalRef.value?.notifySaved(true);
    await toggleWifiEnabled(payload.enabled);
    // 开关收敛后 wifiSettings / wifiClients 由 useNetworkControls 的 finishedSeq 统一回读刷新
    // （见那里的说明），这里只补二维码 —— 它不在那次统一回读里
    loadQrCode();
    return;
  }

  // 设备写入到查询接口可见有延迟，立刻回读会拿回改动前的 SSID / 加密方式。
  // 这一支**不需要** A 级：`/api/wifi/config` 是 AP 配置表的普通写入，不重启模块
  // （会重启模块的是 `/api/wifi/enable` 与 `/api/wifi/band`，那两条已各自走 A 级），
  // 与其它 B 级写操作同属"一次写 + 600ms 后一次回读"这一档。
  await settleDelay();
  refreshWifiSettings().then(() => {
    // 回读落地后再通知弹窗关/留，避免窗口已关还显示 loading
    wifiModalRef.value?.notifySaved(ok, failText);
  });
  refreshWifiClients();
  // WiFi 配置一改，按旧 SSID/密码生成的二维码就是错的：丢掉旧 blob 重新取一张
  releaseQrUrl();
  loadQrCode();
}

// ── 流量限额保存 ──
// goform 限额单位靠乘数表达：1=MB、1024=GB、1048576=TB
interface TrafficSavePayload {
  // 套餐模式（2026-10-07）：档案写入由 TrafficLimitModal 内直接 POST /api/device/plan，
  // 父组件只负责设备侧 data-limit；planMode/plan 仅透传不使用
  planMode?: 'monthly' | 'fixed';
  plan?: { start_date: string; duration_days: number; notify_days: number } | null;
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
  } catch (e: any) {
    // 与上面 saveWifiEdit 同一口径：能拿到 core 的 error 就透出它，
    // 笼统的「保存失败」让用户不知道是数值越界还是设备拒绝
    message.error(e?.response?.data?.error || e?.message || '保存失败');
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
  refreshWifiClients();
  loadWifiAcl();
  // 二维码要按当前频段（chip1/chip2）取，所以等 WiFi 设置到手再拉
  refreshWifiSettings().then(() => loadQrCode());
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
  refreshWifiSettings();
  refreshWifiClients();
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
  gap: var(--space-4);
  /* 宽屏下整页刚好铺满内容区、自身不滚动：高度交给内部按 flex 分配，
     多出来的空间给折线图卡吃掉（见 .chart-card）。窄屏恢复自然高度并允许滚动。

     2026-10-21：原来是 `height: 100%` —— 内容（两栏各自最小高度之和）装不下时，盒子仍是
     一屏高，卡片被压进 GridCard 的 overflow:hidden 里静默裁掉，而页面又不滚（721–900px 高度档
     就是这样，720 以下才有媒体查询兜底）。改成 `min-height: 100%`：装得下时行为与 height:100%
     完全一致（flex 子项仍按剩余空间分配），装不下时盒子跟着长高、交给内容区滚动。
     这样 721–900 档不必新增第四档断点（§3.2 只允许 768 / 1024 / max-height:720）。 */
  min-height: 100%;
  height: auto;
}

/* 顶栏：流量 + 网络，两张卡等宽等高并排（窄屏折成单列，见下方媒体查询） */
.dashboard-top {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--space-4);
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
  gap: var(--space-4);
  align-items: stretch;
  flex: 1;
  min-height: 0;
}

.dashboard-left,
.dashboard-right {
  display: flex;
  flex-direction: column;
  gap: var(--space-4);
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
   阈值与 DefaultLayout 的矮屏压缩共用 720px（原先这里写 760，是第四套断点）。
   页面高度那一层不必再写：.dashboard 已经是 min-height:100% + 自然高度。 */
@media (max-height: 720px) {
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
  /* 单列排布时内容必然高于视口，解锁伸缩（页面高度由 .dashboard 的 min-height 兜住） */
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

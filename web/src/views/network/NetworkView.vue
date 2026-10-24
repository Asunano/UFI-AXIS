<template>
  <div class="network-view">
    <!--
      网络页栅格（2026-10-14 用户裁决，第 3 次调整）：
        行 1 = 网络信息 ｜ 更多设置
        行 2 = 网络设置（通栏）
        行 3 = WiFi 热点 ｜ 局域网设备
      顺序即位置 —— 两列网格按 DOM 顺序自动填格，不需要写 grid-area。

      本轮变化：把「网络设置」从**最后一行**上移到 WiFi 热点 / 局域网设备**之上**
      （用户原话：「网络设置栏放在 WiFi 热点和局域网设备栏上方」）。
      它仍然是通栏卡：卡里那 2 个入口瓦片是 `auto-fit, minmax(240px, 1fr)` 排的
      （2026-10-22 起「流量限额」与「流量校准」合并为一格，瓦片 3 → 2）。
      为什么不干脆提到最顶部：用户只点名了"WiFi 热点 / 局域网设备"这两张卡，
      「网络信息」是本页唯一显示连接状态与开关的卡，没要求动就不动它 ——
      让「网络设置」紧贴那两张卡的上方，既满足要求，也不把状态卡挤到第二屏。

      固定布局（同一条要求的后半句「注意固定布局」）：
      两列栅格的行是**独立轨道** —— 第 3 行的局域网设备再多，也不会把第 1、2 行的卡挤走。
      这是 grid 与 `flex-wrap` 的关键差别：flex-wrap 里某个子项变高会改变同一行的排布，
      而 grid 的行轨道只由本行内容决定。行内再用 `align-items: stretch` 让同一行两张卡等高，
      矮卡补留白而不是露出页面底色（上一版就是被这个空洞逼着把两张卡改成通栏的）。
      再加上局域网设备清单在卡内封顶 + 自己滚动（见 LanDevicesCard 的 `.lan-scroll`），
      第 3 行的高度因此有上界，不随在线设备数增长。

      卡片内部的自适应（本次一并核对过）：
        · WiFi 热点 —— `.kv-grid` 是 `minmax(260px, 1fr)`，半宽（~550px）时回落 2 列 4 行，
          本来就是按半宽设计过的，无需改动；
        · 局域网设备 —— 四列行需要约 460px，其塌陷断点原为**视口** 768px，与"卡片只有半宽"
          失配（视口 900px 时卡片才 ~430px 却不塌）。已把该断点提到 1024px，见卡片内注释。
    -->
    <div class="settings-panel network-grid">
      <!-- 行 1 左：网络信息（连接状态 + 开关 + 信号质量） -->
      <NetworkInfoCard
        :net-status="netStatus"
        :signal="signal"
        :mobile-data-saving="mobileDataSaving"
        :roaming-enabled="roamingEnabled"
        :roaming-saving="roamingSaving"
        @update:mobile-data="setMobileData"
        @update:roaming="setRoaming"
        @airplane-on="setAirplane(true)"
        @airplane-off="setAirplane(false)"
        @ppp-connect="pppConnect"
        @ppp-disconnect="pppDisconnect"
      />

      <!-- 行 1 右：更多设置（六个入口瓦片）
           网络模式 / 频段锁定两个瓦片按设备能力门禁（`Capability.NETWORK_MODE` /
           `Capability.BAND_LOCK`）：明确不支持时整块置灰 + tooltip 说明原因。
           门禁值在本组件算好传下去（本页卡片一律是"受控子组件"，见下方 useNetworkControls 注释）。 -->
      <MoreSettingsCard
        :mode-label="modeTileLabel"
        :sleep-time="sleepTime"
        :mode-unsupported="networkModeUnsupported"
        :mode-hint="networkModeHint"
        :band-unsupported="bandLockUnsupported"
        :band-hint="bandLockHint"
        @open-mode="showModeModal = true"
        @open-band="bandModal.open()"
        @open-speed="speedModal.open()"
        @open-diag="openDiagnostics"
        @open-sleep="showSleepModal = true"
        @open-cell="cellModal.open()"
      />

      <!-- 行 2（通栏）：网络设置 —— 会写设备的配置入口（2026-10-22 起「流量限额」与「流量校准」
           合并为一项「流量与校准」，瓦片 3 个 → 2 个）。
           位置是本轮用户裁决：**在 WiFi 热点 / 局域网设备之上**（见顶部说明）。
           仍是单独一张卡而不是塞进「更多设置」：用户要求那张卡保持 3×2（6 个瓦片），
           而这些配置项是从 #/device 搬来的，加进去就变第 4 行。
           能力门禁不再挂在瓦片上（限额栏读配置仍可用），改为传进弹窗门禁校准栏，
           见下方 TrafficAndCalibrationModal 的绑定。 -->
      <NetworkSettingsCard
        class="full-width"
        @open-traffic-calibration="showTrafficCalibrationModal = true"
        @open-lan="showLanModal = true"
      />

      <!-- 行 3 左：WiFi 热点（半宽；其 .kv-grid 本就按半宽设计，见顶部说明） -->
      <WifiHotspotCard
        :loading="wifiLoading"
        :wifi-enabled="wifiEnabled"
        :settings="wifiSettings"
        :client-count="wifiClients.length"
        @toggle-wifi="toggleWifiEnabled"
        @open-qr="openQrModal"
        @edit="showWifiEditModal = true"
      />

      <!-- 行 3 右：局域网设备。设备清单在卡内封顶并自己滚动（见卡片内 `.lan-scroll`），
           所以这一行的高度**不随在线设备数增长**；行内仍靠 align-items:stretch 与左卡等高 -->
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

    <!-- 弹窗子组件（逻辑已抽到 components/modals/，重型 4 个按需懒加载） -->
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

    <!-- 懒加载弹窗：组件本体与显隐都由 useLazyModal 管，
         这样进出场动画不会因为「首帧 show 就是 true」而丢掉 -->
    <component :is="bandComponent" v-if="bandComponent" :show="bandShow" @update:show="bandModal.setShow" />
    <component :is="speedComponent" v-if="speedComponent" :show="speedShow" @update:show="speedModal.setShow" />

    <!-- 2026-10-14：`switch-band` 是切频段那一支（A 级，见 handleSwitchBand）；
         `saved` 是只改 SSID/密码那一支（不重启模块，仍是一次写 + 一次回读） -->
    <WifiEditModal
      v-model:show="showWifiEditModal"
      :wifi-settings="wifiSettings"
      @saved="handleWifiSaved"
      @switch-band="handleSwitchBand"
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

    <!-- 基站信息弹窗：锁定 / 解锁两个写操作由本页以函数形式传下去（A 级，见 lockCell/unlockCells）。
         弹窗不能自己调 useNetworkControls()：那个 composable 每次调用都新建一套页面级 ref，
         弹窗里再调一次就会多出一份状态与一次 finishedSeq 订阅。 -->
    <component
      :is="cellComponent"
      v-if="cellComponent"
      :show="cellShow"
      :lock-cell="lockCell"
      :unlock-cells="unlockCells"
      @update:show="cellModal.setShow"
    />

    <!-- 2026-10-14：从 #/device 搬来的配置弹窗（组件在共享目录 @/components/modals/） -->
    <LanModal v-model:show="showLanModal" />
    <!--
      2026-10-22：流量限额 + 流量校准合并为**一个弹窗、两栏**（限额 / 校准）。
      组件是本页私有组件（只被本页用），住 `./components/modals/` 而不是共享目录。

      两个动作的语义没有合并（保存配置 ≠ 立刻改设备计数器），所以弹窗里底部按钮随当前栏走；
      校准成功后仍会自己重拉 `/api/device/traffic-limit` 刷新「当前已用流量 / 时长」——
      校准改的就是这两个数，界面必须跟着变（core 成功时也会失效该缓存）。
      两栏的数据读取都在打开时各拉一次（与原两个弹窗各自的行为一致）。
    -->
    <TrafficAndCalibrationModal
      v-model:show="showTrafficCalibrationModal"
      :calibration-unsupported="trafficLimitUnsupported"
      :calibration-hint="trafficLimitHint"
    />
    <!--
      网络诊断（2026-10-22）：四条工具各自独立路由 + 同一个弹窗外壳。
      `show` 完全由路由派生（选择器或具体工具），`tool` 为空时弹窗内是四项列表。
    -->
    <NetworkDiagnosticsModal
      :show="diagShow"
      :tool="diagTool"
      @update:show="(v: boolean) => !v && closeDiagnostics()"
      @select="selectDiagTool"
    />

    <!--
      2026-10-14：这里原来挂着两份 OperationProgressModal（WiFi 开关一份、制式切换一份），
      仪表盘也同样挂两份 —— 而进度状态是各页自己的 composable 实例，于是"在仪表盘点 WiFi 开关、
      切到网络页"进度就没了（反过来一样）。现在**全仓只有 DefaultLayout 挂一份**，
      状态在 stores/networkOps：从哪一页发起、切到哪一页，看到的都是同一份进度与结论。
      本页不需要再挂，也不需要再关心"弹窗该显示什么"。
    -->
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted } from 'vue';
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
// 2026-10-14：A 级操作的进度弹窗已统一挂到 DefaultLayout（状态在 stores/networkOps），
// 本页不再 import OperationProgressModal —— 见模板末尾的说明。

// 主卡（受控子组件：props 下行 + 事件上行）
import NetworkInfoCard from './components/cards/NetworkInfoCard.vue';
import WifiHotspotCard from './components/cards/WifiHotspotCard.vue';
import LanDevicesCard from './components/cards/LanDevicesCard.vue';
import MoreSettingsCard from './components/cards/MoreSettingsCard.vue';
import NetworkSettingsCard from './components/cards/NetworkSettingsCard.vue';

// 受控弹窗（模板 + 本地 UI 态在子组件）
// NetworkMode / Qr / Acl / BandLock 已提到 src/components/modals/：仪表盘也要用，
// 不能让它们继续住在 views/network 内部（否则就是「仪表盘 import 网络页私有组件」）。
// 2026-10-14：从 #/device 搬来的 LAN·DHCP 同理 —— 它的使用方不再是单一 view，
// 所以也落在共享目录（原先在 views/device/components/modals/ 下，已随本次改动迁移）。
import NetworkModeModal from '@/components/modals/NetworkModeModal.vue';
import QrModal from '@/components/modals/QrModal.vue';
import AclModal from '@/components/modals/AclModal.vue';
import LanModal from '@/components/modals/LanModal.vue';
import WifiEditModal from './components/modals/WifiEditModal.vue';
import SleepModal from './components/modals/SleepModal.vue';
// 2026-10-22：流量限额（原共享目录 TrafficLimitModal）+ 流量校准（原本页 FlowCalibrationModal）
// 合并成一个弹窗，栏内分「限额 / 校准」。
import TrafficAndCalibrationModal from './components/modals/TrafficAndCalibrationModal.vue';
import NetworkDiagnosticsModal from './components/modals/NetworkDiagnosticsModal.vue';
import type { NetworkDiagTool } from './diagTools';

// 频段锁定 / 测速 / 基站信息 3 个重型弹窗按需加载，
// 组件本体与显隐时序由 useLazyModal 管（见下方 bandModal 等），不用 defineAsyncComponent。
// 「WiFi 模块信息」原先是第 5 个，已随本次改动移到设置页（设置 › 设备），本页不再需要。
// 「网络诊断」原先是第 4 个，2026-10-14 随那四条工具一起归到诊断中心（见下方 router 那段注释）。

const dashboardStore = useDashboardStore();
const wsStore = useWebSocketStore();
// 2026-10-05 P4 修复：data_changed 订阅需要判断服务是否已停止（core 重启期间不拉）
const serviceStore = useServiceStore();
// 设备能力集：应用壳（DefaultLayout）已经拉过一次，这里只读同一个 store（见 capabilities.ts 文件头）
const caps = useCapabilitiesStore();
const api = useCancellableApi();

/**
 * 本页的能力门禁（2026-10-14）。
 *
 * 覆盖两个**入口瓦片**（弹窗本体在共享目录，本次不改）：
 *   · 网络模式 → `Capability.NETWORK_MODE`（core 的 Capabilities.kt：覆盖
 *     `POST /api/network/mode` 与 `/network/bearer`）；
 *   · 频段锁定 → `Capability.BAND_LOCK`（覆盖 `POST /api/network/band` 的 lock / unlock）。
 *
 * 判据一律 `caps.unsupported()` —— **只有"设备确实没声明"才置灰**。绝不用
 * `!caps.supports()`：后者在"能力集还没拉到 / 那次请求失败"时同样为真，会把这两个入口
 * 一起锁死（见 stores/capabilities.ts 文件头，那条是硬要求）。
 * 置灰的同时把 `hintOf()` 一并传下去做 tooltip，只灰不说等于让用户以为界面坏了。
 *
 * 为什么不在本页做"点下去就 501"的兜底提示：core 的 route 门禁已经把请求拦在设备之前
 * （501 + NOT_SUPPORTED），界面这一层的职责只是"别让用户白点一次"。
 */
const networkModeUnsupported = computed(() => caps.unsupported(Capability.NETWORK_MODE));
const networkModeHint = computed(() => caps.hintOf(Capability.NETWORK_MODE));
const bandLockUnsupported = computed(() => caps.unsupported(Capability.BAND_LOCK));
const bandLockHint = computed(() => caps.hintOf(Capability.BAND_LOCK));
/**
 * 流量校准的门禁用的是 `Capability.TRAFFIC_LIMIT`（`hintOf` 的文案也是"流量限额"那一句）。
 * 语义存疑点写在 TrafficAndCalibrationModal 的校准栏注释里：core 的能力清单只把
 * `/api/device/data-limit` 算作 TRAFFIC_LIMIT，`/flow-calibration` 不在其中。
 *
 * 2026-10-22 合并入口后，门禁从瓦片下沉到弹窗的校准栏（传下去即可）：
 * 限额栏读配置 / 看用量不依赖这个能力，置灰整颗瓦片会把那条路一起堵死。
 */
const trafficLimitUnsupported = computed(() => caps.unsupported(Capability.TRAFFIC_LIMIT));
const trafficLimitHint = computed(() => caps.hintOf(Capability.TRAFFIC_LIMIT));

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
  currentMode,
  modeLoading,
  modeRefreshing,
  refreshDeviceSettings,
  modeLabel,
  applyNetworkMode,
  switchingMode,
  modeSwitchTimedOut,
  roamingEnabled,
  roamingSaving,
  setRoaming,
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
  // 2026-10-14：切频段接成 A 级（原来由 WifiEditModal 直发 + 父组件 600ms 单读一次）
  switchWifiBand,
  // 2026-10-14：A 级操作的状态与进度弹窗统一在 stores/networkOps + DefaultLayout，
  // 本页不再持有 wifiOp / modeOpState（见 useNetworkControls 的导出说明）
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
  // 2026-10-14：基站锁定 / 解锁接成 A 级，由本页转交给懒加载的 CellInfoModal
  // （弹窗自己不能调 useNetworkControls —— 那会多出一份页面级 ref 与一次 finishedSeq 订阅）
  lockCell,
  unlockCells,
} = useNetworkControls();

const netStatus = computed(() => get(dashboardStore.summary, 'network_status', null));
const signal = computed(() => dashboardStore.realtimeSignal);

// ── 弹窗开启标志（弹窗自身逻辑在子组件，本组件只负责开门）──
const showModeModal = ref(false);
const showWifiEditModal = ref(false);
const showQrModal = ref(false);
const showSleepModal = ref(false);
const showAclModal = ref(false);
// 2026-10-14：两项从 #/device 搬来的配置入口（弹窗组件在共享目录）
const showLanModal = ref(false);
// 2026-10-22：「流量限额」与「流量校准」合并成一个弹窗、一个开关（栏内分两栏）
const showTrafficCalibrationModal = ref(false);

/**
 * 4 个懒加载弹窗的挂载 / 显隐时序统一交给 useLazyModal。
 * 原来是 `v-if="showXxx" v-model:show="showXxx"`，进出场动画全被吃掉：
 * 进场时首帧 show 就是 true（Transition 默认不在挂载时播动画），
 * 出场时 v-if 与 show 同一 tick 变 false，组件直接被销毁。
 */
const bandModal = useLazyModal(() => import('@/components/modals/BandLockModal.vue'));
const speedModal = useLazyModal(() => import('./components/modals/SpeedTestModal.vue'));
// 2026-10-14：网络诊断（ping / DNS / TCP / traceroute）**四个工具已归诊断中心**
// （`#/diagnostics`，从「设置 → 关于 → 诊断中心」进）。原来的 `DiagnosticModal.vue`
// 随本次改动删除，本页「更多设置」里那颗瓦片改成跳页 ——
// 理由：那四个工具会真的向外部发包、有几十行原始输出、还应该能连同一份诊断包一起导出，
// 这些都不是一个弹窗装得下的（详情见 DiagnosticsView.vue 的文件头）。
// 2026-10-21：该瓦片的标题/描述已同步改成「诊断中心」，与实际跳转一致（见 MoreSettingsCard）。
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
 * 网络诊断（2026-10-22）：入口先打开「四选一」路由，选中后进入该工具自己的路由。
 * 这两步都只是路由变化 —— 具体弹窗由下方 <NetworkDiagnosticsModal> 按 route.meta.diagTool 渲染。
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

/** 关闭弹窗回网络页：用 replace，避免"开→关"在浏览器历史里留下一对无意义记录。 */
function closeDiagnostics() {
  router.replace({ name: 'network' });
}

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
 * 切换 WiFi 频段（2026-10-14 新增入口）。
 *
 * 弹窗把目标频段交上来、自己先关掉（A 级回读最长 30.6s，不该让配置弹窗空转），
 * 这里交给 `useNetworkControls.switchWifiBand`：下发 + 轮询回读确认生效频段已是目标
 * （判据只认频段，不含 SSID —— 理由见 composable 里那段说明）。
 * 回读确认本身不需要本函数再做一次 —— 收敛后 wifiSettings / wifiClients 由
 * composable 的 finishedSeq 订阅统一刷新，本函数只补二维码（它不在那次统一回读里）：
 * 频段变了，按旧频段生成的图已经不是当前这一张。
 */
async function handleSwitchBand(chip: WifiBand) {
  await switchWifiBand(chip);
  releaseQrUrl();
  if (showQrModal.value) reloadQr();
}

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

// 2026-10-05 P4 修复：写操作缓存失效事件精准刷新（core invalidateAll 合并串按逗号拆）。
// DefaultLayout 的监听只管仪表盘 summary；本页数据源不同必须自己订。
// 保留 10s 轮询：兜底广播丢失与设备面板直改，不删。
const unsubNet = wsStore.on('data_changed', (payload: any) => {
  if (serviceStore.stopped) return;
  const changed: string = payload?.changed ?? '';
  for (const key of changed.split(',')) {
    if (key.startsWith('wifi:')) {
      loadWifiSettings();
      loadWifiClients();
    } else if (key === 'network:band-status' || key === 'hub:network-type-info') {
      refreshNetworkStatus();
      refreshSignal();
    } else if (key === 'device:settings') {
      loadDeviceSettings();
    }
  }
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
  // 2026-10-05 P4 修复：注销 data_changed 订阅，避免切走页面后仍在刷新与弹 toast
  unsubNet?.();
  // 组件销毁时弹窗的 @after-leave 不一定会触发（如整页路由切换），兜底 revoke 防止 blob 泄漏
  releaseQrUrl();
});
</script>

<style scoped>
.network-view {
  display: flex;
  flex-direction: column;
  gap: var(--space-4);
}

/* 两列栅格：DOM 顺序即位置（网络信息 | 更多设置 / 网络设置(通栏) / WiFi 热点 | 局域网设备）。
   栅格本体（2 列、gap、`.full-width` 通栏、1024px 折单列）全部来自全局 `.settings-panel`
   —— 原先本文件那份与全局规则逐字相同，已删掉；scoped 选择器特异性更高，只用来覆盖差异。 */
.network-grid {
  /* stretch（不是全局的 start）：同一行两张卡**等高**。
     用 start 时矮卡贴顶、下方露出页面底色（上一版为此把两张卡改成通栏）；
     stretch 让卡片边框补齐到行高，高度差表现为卡内留白而不是页面底色外露。
     注意它只影响**行内**：行与行之间是各自独立的轨道，第 3 行的设备清单再长
     也不会把第 1、2 行的卡挤走（清单本身另有 max-height 封顶，见 LanDevicesCard）。
     另外：原先的 `max-width: 900px` 折列断点已随收敛删除（900 已在 main.css 明令退役），
     1024px 那一档由全局 `.settings-panel` 提供。 */
  align-items: stretch;
}
</style>

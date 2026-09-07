<template>
  <div class="dashboard">
    <!-- 顶栏：流量 + 网络 横向铺开。
       这两块是最常看的概览，单独占最上面一整栏；右栏只留 WiFi 与连接设备，
       否则右栏 4 张卡纵向堆下来会比左栏高出一大截。 -->
    <div class="dashboard-top">
      <!-- 流量使用情况 -->
      <GridCard class="panel-card" title="流量使用情况">
        <template #extra>
          <n-tag size="small" :type="usageStatusType" :bordered="false" round>{{ usageStatusText }}</n-tag>
          <n-button size="small" quaternary type="primary" @click="openTrafficModal">设限额</n-button>
        </template>

        <div class="usage-wide">
          <!-- 左：今日流量（进度条上方）+ 使用进度条 -->
          <div class="usage-bar-col">
            <div class="usage-bar-head">
              <span class="usage-bar-label">今日</span>
              <span class="usage-bar-value">{{ todayUsageText }}</span>
            </div>
            <!-- 进度条按「剩余额度」倒数：100% → 0%，越少越红（颜色见 usageColor） -->
            <template v-if="hasMonthLimit">
              <n-progress
                class="usage-bar"
                type="line"
                :percentage="monthRemainPercent"
                :color="usageColor"
                rail-color="var(--border-subtle)"
                :height="8"
                :border-radius="4"
                :show-indicator="false"
              />
              <div class="usage-bar-meta">
                <span :style="{ color: usageColor }">剩余 {{ monthRemainPercentText }}%</span>
                <span>提醒线 {{ alertPercent }}%</span>
              </div>
            </template>
            <div v-else class="usage-bar-meta usage-bar-meta-only">
              {{ trafficLimitEnabled ? '已启用限额，但设备未设置额度' : '未启用流量限额' }}
            </div>
          </div>

          <!-- 右：本月总流量。已用值独占一行当主角，额度与说明各自换行退到次要位置 -->
          <div class="usage-hero">
            <span class="usage-hero-value" :style="{ color: usageColor }">{{ monthUsageText }}</span>
            <span v-if="hasMonthLimit" class="usage-hero-total">/ {{ monthLimitText }}</span>
            <span class="usage-hero-label">本月总流量</span>
          </div>
        </div>
      </GridCard>

      <!-- 网络信息：横向铺开时用「标签在上、值在下」的格子，比左右对齐的行更好读 -->
      <GridCard class="panel-card" title="网络信息">
        <template #extra>
          <n-tag :type="netStatus?.network?.is_connected ? 'success' : 'error'" size="small" :bordered="false">
            {{ netStatus?.network?.is_connected ? '已连接' : '未连接' }}
          </n-tag>
          <n-button size="small" quaternary type="primary" @click="showModeModal = true">网络模式</n-button>
          <n-button size="small" quaternary type="primary" @click="bandModal.open()">频段锁定</n-button>
        </template>

        <div class="stat-grid">
          <div class="stat-cell">
            <span class="stat-label">网络类型</span>
            <span class="stat-value">{{ networkTypeWithBand(netStatus?.network_type || '', signal) }}</span>
          </div>
          <div class="stat-cell">
            <span class="stat-label">运营商</span>
            <span class="stat-value">{{ netStatus?.operator || '--' }}</span>
          </div>
          <!-- 开关直接当作信息格的「值」：不额外增加卡片高度，网络卡才不会比流量卡高一截 -->
          <div class="stat-cell">
            <span class="stat-label">移动数据</span>
            <n-switch
              size="small"
              :value="!!netStatus?.mobile_data"
              :loading="mobileDataSaving"
              @update:value="setMobileData"
            />
          </div>
          <div class="stat-cell">
            <span class="stat-label">数据漫游</span>
            <span class="stat-value">{{ roamingEnabled ? '开启' : '关闭' }}</span>
          </div>
          <div class="stat-cell">
            <span class="stat-label">连接模式</span>
            <span class="stat-value">{{ connectionMode === 'auto' ? '自动拨号' : '手动拨号' }}</span>
          </div>
        </div>
      </GridCard>
    </div>

    <div class="dashboard-grid">
      <!-- 左侧：系统状态 + 网络详情 -->
      <div class="dashboard-left">
        <!-- 系统状态 -->
        <GridCard class="panel-card" title="系统状态">
          <div class="gauge-grid">
            <div class="gauge-cell">
              <RingGauge
                :value="loadPercent"
                :max="100"
                label="负载"
                unit="%"
                :color="loadColor"
                :size="130"
                :stroke-width="10"
              />
              <div class="gauge-subtitle">{{ cpuSubtitle }}</div>
            </div>
            <div class="gauge-cell">
              <RingGauge
                :value="cpuTemp"
                :max="100"
                label="CPU"
                unit="°C"
                :color="tempColor"
                :size="130"
                :stroke-width="10"
              />
              <div class="gauge-subtitle">{{ cpuFreqText }}</div>
            </div>
            <div class="gauge-cell">
              <RingGauge
                :value="memoryPercent ?? 0"
                :max="100"
                label="内存"
                unit="%"
                :color="memColor"
                :size="130"
                :stroke-width="10"
              />
              <div class="gauge-subtitle">{{ memorySubtitle }}</div>
            </div>
            <div class="gauge-cell">
              <RingGauge
                :value="storagePercent"
                :max="100"
                label="存储"
                unit="%"
                :color="storageColor"
                :size="130"
                :stroke-width="10"
              />
              <div class="gauge-subtitle">{{ storageSubtitle }}</div>
            </div>
          </div>
        </GridCard>

        <!-- 网络详情 / 信号详情 折线图（独立组件承载整段 echarts 机件） -->
        <NetDetailChartCard />
      </div>

      <!-- 右侧：WiFi 信息 → 连接设备（流量与网络已提到顶栏） -->
      <div class="dashboard-right">
        <!-- WiFi 信息；WiFi 与局域网设置都走弹窗，不在概览里铺开 -->
        <GridCard class="panel-card" title="WiFi 信息">
          <template #extra>
            <!-- 开关即状态：不再另放一个「已开启/已关闭」的 tag，避免两处说同一件事却可能不一致 -->
            <n-switch :value="wifiEnabled" size="small" @update:value="toggleWifiEnabled" />
            <n-button size="small" quaternary type="primary" @click="openWifiModal">WiFi 设置</n-button>
          </template>

          <!--
            信息与二维码左右并排：二维码是这张卡最常用的东西，占右侧固定列，
            信息列自适应剩余宽度（窄屏回落成上下，见 @media）。
            图不能直接 <img src="/api/wifi/qrcode">：img 请求不带 Authorization 头会被 core 拦掉，
            所以走 composable 里注入 Bearer 的 axios 取 blob 再转 object URL。
          -->
          <div class="wifi-layout">
            <div class="wifi-info">
              <div v-if="wifiSettings" class="stat-grid wifi-stat-grid">
                <div class="stat-cell">
                  <span class="stat-label">SSID</span>
                  <span class="stat-value">{{ wifiSettings.ssid || '--' }}</span>
                </div>
                <div class="stat-cell">
                  <span class="stat-label">加密方式</span>
                  <span class="stat-value">{{ wifiAuthText }}</span>
                </div>
                <div class="stat-cell">
                  <span class="stat-label">频段</span>
                  <span class="stat-value">{{ wifiSettings.chip_index === '2' ? '5 GHz' : '2.4 GHz' }}</span>
                </div>
                <div class="stat-cell">
                  <span class="stat-label">SSID 广播</span>
                  <span class="stat-value">{{ wifiSettings.broadcast_disabled ? '已隐藏' : '开启' }}</span>
                </div>
                <div class="stat-cell">
                  <span class="stat-label">最大接入数</span>
                  <span class="stat-value">{{ wifiSettings.max_sta_num || '--' }}</span>
                </div>
              </div>
              <div v-else class="client-empty">WiFi 信息读取失败</div>
            </div>

            <div class="wifi-qr">
              <img v-if="qrUrl" class="wifi-qr-img" :src="qrUrl" alt="WiFi 二维码" />
              <n-spin v-else-if="qrLoading" size="small" />
              <span v-else class="wifi-qr-hint">{{ qrError || '二维码不可用' }}</span>
              <span v-if="qrUrl" class="wifi-qr-hint">扫码连接 {{ wifiSettings?.ssid || 'WiFi' }}</span>
            </div>
          </div>
        </GridCard>

        <!-- 连接设备。高度锁死跟左栏折线图卡齐平：设备多了由列表内部滚，
             不让这张卡把右栏越撑越长（见 .clients-card）。 -->
        <GridCard class="panel-card clients-card" title="连接设备">
          <template #extra>
            <n-tag size="small" type="info" :bordered="false">{{ wifiClients.length }} 台在线</n-tag>
            <n-button size="small" quaternary type="primary" @click="showAclModal = true">
              已拉黑 {{ blockedList.length }}
            </n-button>
          </template>

          <!-- 卡片高度已锁死、列表自己滚，所以不再截断到 8 条 ——
               原来的「+N 更多」是为了防止卡片被撑长，现在这个约束由 .clients-card 承担。 -->
          <div v-if="wifiClients.length" class="client-preview">
            <div v-for="(c, i) in wifiClients" :key="c.mac || i" class="client-preview-item">
              <span class="client-name">{{ c.hostname || c.ip_addr }}</span>
              <span class="client-mac">{{ c.mac }}</span>
              <!-- MAC 为空没法拉黑（设备名单以 MAC 为主键）；aclPending 只锁正在下发的那一行 -->
              <n-button
                v-if="c.mac"
                class="client-acl-btn"
                size="tiny"
                :type="blockedMacs.has(c.mac.toLowerCase()) ? 'default' : 'error'"
                :loading="aclPending === c.mac.toLowerCase()"
                :disabled="!!aclPending"
                @click="blockedMacs.has(c.mac.toLowerCase()) ? unblockDevice(c.mac) : blockDevice(c)"
              >
                {{ blockedMacs.has(c.mac.toLowerCase()) ? '解除' : '拉黑' }}
              </n-button>
            </div>
          </div>
          <div v-else class="client-empty">暂无设备连接</div>
        </GridCard>
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
import { ref, computed, onMounted, onUnmounted } from 'vue';
import { useMessage } from 'naive-ui';
import { useDashboardStore } from '@/stores/dashboard';
import { useCancellableApi } from '@/composables/useCancellableApi';
import { useInterval } from '@/composables/useRealtime';
import { useChartColors } from '@/composables/chartTheme';
import { useNetworkControls } from '@/composables/useNetworkControls';
import { useLazyModal } from '@/composables/useLazyModal';
import { formatBytes, networkTypeWithBand, get } from '@/composables/utils';
import RingGauge from '@/components/RingGauge.vue';
import GridCard from '@/components/GridCard.vue';
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

// ── 颜色常量 ──
// canvas 不解析 CSS 变量（赋给 ctx.strokeStyle 会被静默忽略），所以这里统一走
// useChartColors()：内部用 getComputedStyle 把变量解析成具体色值，并随暗色模式重算。
const colors = useChartColors();

// ── 实时数据 ──
const cpuData = computed(() => dashboardStore.realtimeCpu);
const memoryData = computed(() => dashboardStore.realtimeMemory);
const signal = computed(() => dashboardStore.realtimeSignal);
const netStatus = computed(() => get(dashboardStore.summary, 'network_status', null));
const trafficSummary = computed(() => get(dashboardStore.summary, 'traffic_summary', null));
const trafficLimit = computed(() => get(dashboardStore.summary, 'traffic_limit', null));

// 流量使用情况数据源说明（对齐 core）：
// - traffic_limit.used_bytes / limit_bytes 由 core 算好（与 /api/device/traffic-limit 同一契约），
//   前端不再自己拿 monthly_rx/tx 相加、也不自己换算单位，避免与设备判定限额的口径出现偏差
// - /api/traffic/summary 的 total_* 在 core 里同样取当月 goform 值（TrafficRoutes.buildSummary），
//   仅在 traffic_limit 还没到达时兜底
// - 今日用量取 traffic_summary.today_rx_display / today_tx_display（core 同时给了
//   today_rx_bytes / today_tx_bytes）。core 侧算法：当前月累计 − 今天第一条采样的月累计，
//   基线读 traffic_records，跨 core 重启仍然是「今天 00:00 起」

// - 不使用 /api/device/data-usage：core 的 getCellularDataUsage 忽略时间区间、按 /sys/class/net 累计，
//   today 与 month 返回同一个"自开机以来全网口累计"值，无法代表当月蜂窝用量
const monthUsedBytes = computed(() => {
  const used = Number(trafficLimit.value?.used_bytes ?? 0);
  if (Number.isFinite(used) && used > 0) return used;
  const s = trafficSummary.value;
  return Number(s?.total_rx_bytes ?? 0) + Number(s?.total_tx_bytes ?? 0);
});

const monthUsageText = computed(() => (monthUsedBytes.value ? formatBytes(monthUsedBytes.value) : '--'));
const todayUsageText = computed(() => {
  const s = trafficSummary.value;
  if (!s?.today_rx_display) return '--';
  return `${s.today_rx_display}↓ / ${s.today_tx_display}↑`;
});

// limit_bytes 由 core 算好（1024 进制），0 = 未设置额度。
// 设备侧的复合格式（如 "470_1024"）不会出现在响应里，前端不做任何单位换算。
const monthLimitBytes = computed(() => {
  const n = Number(trafficLimit.value?.limit_bytes ?? 0);
  return Number.isFinite(n) && n > 0 ? n : 0;
});
const trafficLimitEnabled = computed(() => !!trafficLimit.value?.enabled);
// 「启用了限额」和「额度有效」是两件事：开关开着但额度为 0 时没有百分比可言
const hasMonthLimit = computed(() => trafficLimitEnabled.value && monthLimitBytes.value > 0);
// 无有效额度时返回 null，让模板走「未设置限额」占位，而不是显示 0%（会被误读成没用流量）或 NaN%
const monthLimitPercent = computed(() => {
  if (!hasMonthLimit.value) return null;
  const p = (monthUsedBytes.value / monthLimitBytes.value) * 100;
  if (!Number.isFinite(p)) return null;
  return Math.min(100, Math.max(0, p));
});
/**
 * 剩余额度百分比：进度条与文案都按「倒数」呈现（满额 100% → 用尽 0%），
 * 比「已用 N%」更贴合「还能用多少」这个真正要看的问题。
 * 无有效额度时按满额处理 —— 此时进度条本身不渲染，这个值只是兜底不让模板拿到 null。
 */
const monthRemainPercent = computed(() => (monthLimitPercent.value === null ? 100 : 100 - monthLimitPercent.value));
const monthRemainPercentText = computed(() => String(Math.round(monthRemainPercent.value)));
const monthLimitText = computed(() => {
  if (!hasMonthLimit.value) return '未设置限额';
  return `${trafficLimit.value?.limit_value ?? ''} ${trafficLimit.value?.limit_unit_display ?? ''}`.trim();
});

// 流量状态显示
// alert_percent 是 goform 原样字符串，非法值兜底 80
const alertPercent = computed(() => {
  const n = Number(trafficLimit.value?.alert_percent);
  return Number.isFinite(n) && n > 0 && n <= 100 ? n : 80;
});
/**
 * 额度色阶。判定基准仍是「已用百分比 vs 用户设的提醒线」，但呈现是倒数的剩余量，
 * 所以读起来就是「剩余越少越红」：
 *   已用 ≥ 提醒线        → error（红，剩余告急）
 *   已用 ≥ 提醒线 × 0.6  → warning（橙，剩余偏少）
 *   否则                 → success（绿，剩余充足）
 * 进度条、剩余百分比文字、右侧总流量数值共用这一个颜色。
 */
const usageColor = computed(() => {
  const p = monthLimitPercent.value;
  if (p === null) return colors.value.primary;
  const alert = alertPercent.value;
  if (p >= alert) return colors.value.error;
  if (p >= alert * 0.6) return colors.value.warning;
  return colors.value.success;
});
const usageStatusType = computed(() => {
  const p = monthLimitPercent.value;
  if (p === null) return 'info';
  const alert = alertPercent.value;
  if (p >= alert) return 'error';
  if (p >= alert * 0.6) return 'warning';
  return 'success';
});
const usageStatusText = computed(() => {
  if (!trafficLimitEnabled.value) return '未启用限额';
  const p = monthLimitPercent.value;
  if (p === null) return '已启用但未设置额度';
  const alert = alertPercent.value;
  if (p >= alert) return '已达上限';
  if (p >= alert * 0.6) return '用量偏高';
  return '正常';
});

const storageData = computed(() => {
  const s = get(dashboardStore.summary, 'storage', null);
  if (!s) return { percent: 0, used: 0, total: 0, available: 0 };
  const percent = s.total > 0 ? (s.used / s.total) * 100 : 0;
  return { ...s, percent };
});

// ── 仪表盘计算 ──
const loadPercent = computed(() => cpuData.value?.usage_percent ?? 0);
const cpuTemp = computed(() => cpuData.value?.temperature ?? 0);
const memoryPercent = computed(() => memoryData.value?.usage_percent ?? null);
const storagePercent = computed(() => storageData.value.percent);

const loadColor = computed(() => {
  const v = loadPercent.value;
  if (v >= 80) return colors.value.error;
  if (v >= 60) return colors.value.warning;
  return colors.value.primary;
});
const tempColor = computed(() => {
  const t = cpuTemp.value;
  if (t >= 75) return colors.value.error;
  if (t >= 55) return colors.value.warning;
  return colors.value.success;
});
const memColor = computed(() => {
  const v = memoryPercent.value ?? 0;
  if (v >= 85) return colors.value.error;
  if (v >= 70) return colors.value.warning;
  return colors.value.primary;
});
const storageColor = computed(() => {
  const v = storagePercent.value;
  if (v >= 85) return colors.value.error;
  if (v >= 70) return colors.value.warning;
  return colors.value.primary;
});

const cpuSubtitle = computed(() => `${cpuData.value?.core_count ?? '--'} 核 · ${cpuFreqText.value}`);
const cpuFreqText = computed(() => {
  const cores = cpuData.value?.cores as Array<{ freq_mhz: number; freq_display: string }> | undefined;
  if (!cores?.length) return '--';
  const avg = cores.reduce((sum, c) => sum + (c.freq_mhz || 0), 0) / cores.length;
  if (avg >= 1000) return `${(avg / 1000).toFixed(2)} GHz`;
  return `${avg.toFixed(0)} MHz`;
});

const memorySubtitle = computed(() => {
  const m = memoryData.value;
  if (!m?.total) return '加载中...';
  const used = (m.used / 1_073_741_824).toFixed(1);
  const total = (m.total / 1_073_741_824).toFixed(1);
  return `${used} / ${total} GB`;
});

const storageSubtitle = computed(() => {
  const s = storageData.value;
  if (!s.total) return '--';
  const fmt = (b: number) => (b / 1_073_741_824).toFixed(1) + ' GB';
  return `${fmt(s.used)} / ${fmt(s.total)}`;
});

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

/** goform 的 auth_mode / encryp_type 原样拼接；两者都为空或 NONE 视为开放网络 */
const wifiAuthText = computed(() => {
  const s = wifiSettings.value;
  if (!s) return '--';
  const parts = [s.auth_mode, s.encryp_type]
    .map((v) => String(v ?? '').trim())
    .filter((v) => v && v.toUpperCase() !== 'NONE');
  return parts.length ? parts.join(' / ') : '开放';
});

// ── 弹窗显隐（打开由卡片按钮置位，表单回填由子组件在 watch(show) 内完成）──
const showWifiModal = ref(false);
const showTrafficModal = ref(false);
const showModeModal = ref(false);
const showAclModal = ref(false);
function openWifiModal() {
  showWifiModal.value = true;
}
function openTrafficModal() {
  showTrafficModal.value = true;
}

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
   两栏各有一张「吃掉剩余高度」的卡（左：折线图，右：连接设备），其余锁成自然高度。 */
.dashboard-left > .panel-card:not(.chart-card),
.dashboard-right > .panel-card:not(.clients-card) {
  flex: 0 0 auto;
}
/* 连接设备：与左栏折线图卡对称地吃掉本栏剩余高度，设备再多也只是列表内部滚动，
   不会把右栏越撑越长、跟左栏错位。min-height 兜住极矮窗口。 */
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

/* ── 环形仪表盘网格 ── */
.gauge-grid {
  display: grid;
  /* auto-fit：按可用宽度连续换行（4 / 3 / 2 / 1 列），不再依赖断点硬切 */
  grid-template-columns: repeat(auto-fit, minmax(140px, 1fr));
  gap: 16px;
}
/* 原名 .gauge-card —— 它并不是一张卡，只是仪表 + 副标题的居中单元格，改名避免与卡壳混淆 */
.gauge-cell {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
}
.gauge-subtitle {
  font-size: 12px;
  color: var(--text-muted);
  text-align: center;
}

/* ── 流量使用情况卡片 ──
   左：今日流量 + 使用进度条（吃掉剩余宽度）；右：本月总流量大数值。
   「剩余」已按需求去掉 —— 有进度条和「总/额度」并排就够读出剩余，多一格反而分散重点。 */
.usage-hero {
  display: flex;
  flex-direction: column;
  /* 靠右对齐：这块被放到卡片右侧，标签与数值都贴右边才不会看着像左栏的续行 */
  align-items: flex-end;
  gap: 2px;
}
.usage-hero-label {
  font-size: 12px;
  color: var(--text-muted);
}
/* 已用值 / 额度 / 说明各占一行（.usage-hero 是竖排 flex），
   已用值靠字号与字重独占视觉重心，额度退成一行小字参照 */
.usage-hero-value {
  font-size: 26px;
  font-weight: 700;
  line-height: 1.15;
  letter-spacing: -0.02em;
  font-variant-numeric: tabular-nums;
}
.usage-hero-total {
  font-size: 13px;
  color: var(--text-secondary);
  font-variant-numeric: tabular-nums;
}
/* 横向排布：进度条列（吃掉剩余宽度）| 总流量数值块 */
.usage-wide {
  display: flex;
  align-items: center;
  gap: 24px;
  flex-wrap: wrap;
}
.usage-bar-col {
  flex: 1;
  min-width: 180px;
}
/* 进度条上方的今日流量：标签 + 值同一行，不占额外高度 */
.usage-bar-head {
  display: flex;
  align-items: baseline;
  gap: 6px;
  margin-bottom: 6px;
}
.usage-bar-label {
  font-size: 12px;
  color: var(--text-muted);
}
.usage-bar-value {
  font-size: 13px;
  font-weight: 600;
  color: var(--text-primary);
  font-variant-numeric: tabular-nums;
}
.usage-bar-meta {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  margin-top: 6px;
  font-size: 12px;
  color: var(--text-muted);
}
/* 没有有效额度时只有一行说明文字，不需要两端对齐 */
.usage-bar-meta-only {
  justify-content: flex-start;
}
/* 标签在上、值在下的信息格；网络信息与 WiFi 信息共用 */
.stat-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(120px, 1fr));
  gap: 14px 20px;
}
.stat-cell {
  display: flex;
  flex-direction: column;
  /* flex-start 而不是默认的 stretch：这一格的「值」可能是开关或按钮组，
     拉伸会把它们撑成整格宽 */
  align-items: flex-start;
  gap: 3px;
  min-width: 0;
}
.stat-label {
  font-size: 12px;
  color: var(--text-muted);
}
.stat-value {
  font-size: 13px;
  font-weight: 600;
  color: var(--text-primary);
  font-variant-numeric: tabular-nums;
  word-break: break-all;
}

/* ── WiFi 信息：左信息 / 右二维码 ── */
.wifi-layout {
  display: flex;
  align-items: flex-start;
  gap: 20px;
}
.wifi-info {
  /* min-width:0 是必须的：里面是 grid，不给它就会被最长的 SSID 撑出卡片 */
  flex: 1 1 auto;
  min-width: 0;
}
/* 信息列只剩半卡宽，auto-fit 的 120px 下限会挤成 1 列。
   固定两列更稳：五个字段排成 3 行，高度刚好和右侧二维码相当 */
.wifi-stat-grid {
  grid-template-columns: repeat(2, minmax(0, 1fr));
}

/* ── WiFi 二维码（卡内右侧固定列，不走弹窗）── */
.wifi-qr {
  flex: 0 0 auto;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
}
.wifi-qr-img {
  width: 140px;
  max-width: 100%;
  height: auto;
  display: block;
  border-radius: var(--radius-sm);
  background: #fff;
}
.wifi-qr-hint {
  font-size: 12px;
  color: var(--text-muted);
  text-align: center;
}

/* ── 已连接设备预览 ── */
/* 每行三段：名称（吃掉剩余宽度）| MAC | 拉黑按钮 */
.client-preview {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(260px, 1fr));
  gap: 4px 16px;
}
.client-preview-item {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto auto;
  align-items: center;
  gap: 8px;
  padding: 4px 0;
  font-size: 13px;
  color: var(--text-primary);
}
.client-name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.client-mac {
  color: var(--text-muted);
  font-family: monospace;
  font-size: 12px;
}
.client-empty {
  padding: 16px 0;
  font-size: 13px;
  color: var(--text-muted);
  text-align: center;
}

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

@media (max-width: 768px) {
  /* 窄屏时横排放不下：进度条列与总流量数值上下堆叠，数值改回左对齐 */
  .usage-wide {
    flex-direction: column;
    align-items: stretch;
    gap: 14px;
  }
  .usage-hero {
    align-items: flex-start;
  }

  /* ── 系统状态 ──
     auto-fit + minmax(140px) 在 360px 手机上恰好挤成 2 列 ~142px，环塞得下但副标题会折行。
     显式锁 2 列并收紧间距，排布可预期；4 个仪表刚好两行。 */
  .gauge-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 12px;
  }

  /* ── 网络信息 / WiFi 信息的信息格 ──
     标签在上值在下本来就是竖排，2 列已是合适密度，这里只收紧行距免得卡片过高 */
  .stat-grid {
    gap: 10px 14px;
  }

  /* ── 连接设备 ──
     原来一行三段（名称 | MAC | 按钮）在 360px 上放不下，名称只剩几十像素。
     改成两列：左侧名称与 MAC 上下叠，右侧按钮跨两行居中。 */
  .client-preview {
    grid-template-columns: 1fr;
  }
  .client-preview-item {
    grid-template-columns: minmax(0, 1fr) auto;
    row-gap: 1px;
    padding: 6px 0;
  }
  .client-name {
    grid-area: 1 / 1 / 2 / 2;
  }
  .client-mac {
    grid-area: 2 / 1 / 3 / 2;
  }
  .client-acl-btn {
    grid-area: 1 / 2 / 3 / 3;
    align-self: center;
  }

  /* 窄屏放不下左右两列：回落成「信息在上、二维码在下」，
     二维码这时可以放大一点 —— 它是整张卡里最有用的东西 */
  .wifi-layout {
    flex-direction: column;
    gap: 12px;
  }
  .wifi-qr {
    align-self: center;
    padding-top: 12px;
    border-top: 1px solid var(--border-subtle);
    width: 100%;
  }
  .wifi-qr-img {
    width: 168px;
  }
}
</style>

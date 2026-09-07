<template>
  <div class="settings-panel">
    <!-- Card 1: 版本信息 -->
    <GridCard title="版本信息">
      <InfoRow label="应用版本" :value="versionInfo.version" />
      <InfoRow label="最低客户端版本" :value="versionInfo.minClientVersion" />
      <InfoRow label="更新地址" :value="versionInfo.updateUrl || '--'" />
    </GridCard>

    <!-- Card 1.2: 系统信息（core /api/system/*）
         四个端点都无参数、恒 200、无失败信封；battery / storage 采集异常时返回 {}。
         刻意不轮询：设备只有 256MB 内存，仅在挂载与手动点「刷新」时各拉一次。 -->
    <GridCard title="系统信息">
      <template #extra>
        <n-button size="tiny" quaternary :loading="sysLoading" @click="loadSystemInfo">刷新</n-button>
      </template>
      <InfoRow label="Root 权限">
        <template #default>
          <span :class="sysInfo.hasRoot ? 'text-success' : 'text-error'">{{ sysRootText }}</span>
        </template>
      </InfoRow>
      <InfoRow label="服务启动时间" :value="sysStartupTimeText" />
      <InfoRow label="已运行" :value="sysStartupUptimeText" />
      <InfoRow label="电池电量" :value="sysBatteryPercentText" />
      <InfoRow label="充电状态" :value="sysBatteryChargingText" />
      <InfoRow label="电源类型" :value="sysBatteryPluggedText" />
      <InfoRow label="电池温度" :value="sysBatteryTempText" />
      <InfoRow label="电池电压" :value="sysBatteryVoltageText" />
      <InfoRow label="存储（已用 / 总量）" :value="sysStorageText" />
      <n-progress
        v-if="sysInfo.storageLoaded"
        type="line"
        :percentage="sysStoragePercent"
        :height="6"
        style="margin-top: 8px"
      />
    </GridCard>

    <!-- Card 6: 诊断信息 -->
    <GridCard title="诊断信息">
      <template #extra>
        <n-button size="tiny" quaternary @click="loadDiagnostics">刷新</n-button>
      </template>
      <InfoRow label="服务器时间" :value="diag.serverTime || '--'" />
      <InfoRow label="应用版本" :value="diag.appVersion || '--'" />
      <InfoRow label="Root 状态">
        <template #default>
          <span :class="diag.root ? 'text-success' : 'text-error'">{{ diag.root ? '已获取' : '未获取' }}</span>
        </template>
      </InfoRow>
      <InfoRow label="ADB 守护进程">
        <template #default>
          <span :class="adbdRunning ? 'text-success' : 'text-error'">{{ diag.adbd || '--' }}</span>
        </template>
      </InfoRow>
      <InfoRow label="移动数据">
        <template #default>
          <span :class="mobileDataOn ? 'text-success' : 'text-error'">{{ mobileDataLabel }}</span>
        </template>
      </InfoRow>
      <InfoRow label="网关" :value="diag.gateway || '--'" />
    </GridCard>

    <!-- Card 7: 开源信息
         为什么 core 侧必须有：那几个预编译二进制（aria2c / socat / adb / ttyd / curl / jq / sendat）
         是 **core APK** 打包并释放到设备执行的，其中 aria2c / socat 属 GPL 系 —— 分发义务
         （附许可证 + 提供对应源码）产生在 core 这一侧，声明就得在 core 的界面里能点到。
         app 端「关于」也放了同一个入口（AboutDeviceScreen 的「开源许可」），两处指向同一份文件，
         不各写一份文案，避免漂移。 -->
    <GridCard title="开源信息">
      <InfoRow label="项目仓库">
        <template #default>
          <a :href="REPO_URL" target="_blank" rel="noopener noreferrer">github.com/Asunano/UFI-AXIS</a>
        </template>
      </InfoRow>
      <InfoRow label="开源许可">
        <template #default>
          <a :href="THIRD_PARTY_NOTICES_URL" target="_blank" rel="noopener noreferrer">第三方组件与许可证声明</a>
        </template>
      </InfoRow>
    </GridCard>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted } from 'vue';
import { getApiClient } from '@/composables/useApi';
import GridCard from '@/components/GridCard.vue';
import InfoRow from '@/components/InfoRow.vue';
import { formatBytes } from '@/composables/utils';
import { formatUptime } from '@/views/settings/settingsShared';

const api = getApiClient();

// ── 开源信息（单一真源在仓库里，这里只做跳转）──
const REPO_URL = 'https://github.com/Asunano/UFI-AXIS';
const THIRD_PARTY_NOTICES_URL =
  'https://github.com/Asunano/UFI-AXIS/blob/main/core/src/main/assets/shell/THIRD-PARTY-NOTICES.md';

// ── 版本信息 ──
const versionInfo = reactive({ version: '--', minClientVersion: '--', updateUrl: '' });

// ── 系统信息（core /api/system/*）──
// contract.ts 的 Endpoints 目前没有 system 分组，路径就近集中在这里，避免散落在函数体内。
const SYSTEM_ENDPOINTS = {
  battery: '/api/system/battery',
  storage: '/api/system/storage',
  rootCheck: '/api/system/root-check',
  startupTime: '/api/system/startup-time',
} as const;

const PLUGGED_LABELS: Record<string, string> = {
  AC: '电源适配器',
  USB: 'USB',
  Wireless: '无线充电',
  None: '未接入',
};

const sysLoading = ref(false);
// 采集异常时 core 回 {}，所以每个字段都要能表达"缺失"：数值用 null，percent 沿用 core 的 -1 = 未知。
const sysInfo = reactive({
  rootLoaded: false,
  hasRoot: false,
  startupTimeMs: 0,
  batteryLoaded: false,
  batteryPercent: -1,
  batteryCharging: null as boolean | null,
  batteryPlugged: '',
  batteryTemp: null as number | null,
  batteryVoltage: null as number | null,
  storageLoaded: false,
  storageTotal: 0,
  storageUsed: 0,
  storageUsagePercent: 0,
});
// 「已运行」按拉取时刻计算：不写 Date.now() 进 computed，否则同一 startupTimeMs 重复刷新不会重算。
const sysFetchedAt = ref(0);

/** 只接受有限数值，其余（undefined / null / NaN / 空串）统一当缺失 */
function sysNumber(v: any): number | null {
  if (v === null || v === undefined || v === '') return null;
  const n = Number(v);
  return Number.isFinite(n) ? n : null;
}

const sysRootText = computed(() => (sysInfo.rootLoaded ? (sysInfo.hasRoot ? '已获取' : '未获取') : '--'));
const sysStartupTimeText = computed(() =>
  sysInfo.startupTimeMs > 0 ? new Date(sysInfo.startupTimeMs).toLocaleString('zh-CN', { hour12: false }) : '--'
);
const sysStartupUptimeText = computed(() => {
  if (sysInfo.startupTimeMs <= 0 || sysFetchedAt.value <= 0) return '--';
  // 共享版 settingsShared.formatUptime 与拆分前的实现一致，收的是**毫秒**（内部自己 /1000），
  // 所以这里直接传毫秒差，不要再换算。
  return formatUptime(sysFetchedAt.value - sysInfo.startupTimeMs);
});
const sysBatteryPercentText = computed(() =>
  sysInfo.batteryLoaded && sysInfo.batteryPercent >= 0 ? `${sysInfo.batteryPercent}%` : '--'
);
const sysBatteryChargingText = computed(() =>
  sysInfo.batteryCharging === null ? '--' : sysInfo.batteryCharging ? '充电中' : '未充电'
);
const sysBatteryPluggedText = computed(
  () => PLUGGED_LABELS[sysInfo.batteryPlugged] ?? (sysInfo.batteryPlugged || '--')
);
const sysBatteryTempText = computed(() =>
  sysInfo.batteryTemp === null ? '--' : `${sysInfo.batteryTemp.toFixed(1)}°C`
);
const sysBatteryVoltageText = computed(() =>
  sysInfo.batteryVoltage === null ? '--' : `${sysInfo.batteryVoltage.toFixed(2)} V`
);
const sysStorageText = computed(() =>
  sysInfo.storageLoaded ? `${formatBytes(sysInfo.storageUsed)} / ${formatBytes(sysInfo.storageTotal)}` : '--'
);
const sysStoragePercent = computed(() => Number(Math.min(100, Math.max(0, sysInfo.storageUsagePercent)).toFixed(1)));

// ── 诊断 ──
const diag = reactive({
  serverTime: '',
  appVersion: '',
  root: false,
  adbd: '',
  mobileData: '',
  gateway: '',
});

// diagnose 的 adbd / mobile_data 是 shell 原始输出字符串
const adbdRunning = computed(() => diag.adbd === 'running');
const mobileDataOn = computed(() => diag.mobileData === '1' || diag.mobileData.toLowerCase() === 'true');
const mobileDataLabel = computed(() => {
  if (!diag.mobileData || diag.mobileData === 'unknown') return '未知';
  return mobileDataOn.value ? '已开启' : '已关闭';
});

// ══════════════════════════════════════════════
//  Data Loading
// ══════════════════════════════════════════════

async function loadVersion() {
  try {
    const { data } = await api.get('/api/config/version');
    versionInfo.version = data.version || '--';
    versionInfo.minClientVersion = data.min_client_version || '--';
    versionInfo.updateUrl = data.update_url || '';
  } catch {
    /* silent */
  }
}

// 四个 /api/system/* 端点并发拉取：任何一个失败（网络/超时）都不影响其余卡片内容，
// 因此用 allSettled 而不是 all；core 侧这些端点恒 200，失败只会来自传输层。
async function loadSystemInfo() {
  sysLoading.value = true;
  try {
    const [battery, storage, rootCheck, startup] = await Promise.allSettled([
      api.get(SYSTEM_ENDPOINTS.battery),
      api.get(SYSTEM_ENDPOINTS.storage),
      api.get(SYSTEM_ENDPOINTS.rootCheck),
      api.get(SYSTEM_ENDPOINTS.startupTime),
    ]);

    if (battery.status === 'fulfilled') {
      const d = battery.value.data ?? {};
      const percent = sysNumber(d.percent);
      const temp = sysNumber(d.temperature);
      const voltage = sysNumber(d.voltage);
      // 空对象 {} = 采集失败：percent 缺失时按未知(-1)处理，其余保持 null 显示 --
      sysInfo.batteryLoaded = percent !== null || temp !== null || voltage !== null;
      sysInfo.batteryPercent = percent ?? -1;
      sysInfo.batteryCharging = typeof d.is_charging === 'boolean' ? d.is_charging : null;
      sysInfo.batteryPlugged = typeof d.plugged === 'string' ? d.plugged : '';
      sysInfo.batteryTemp = temp;
      sysInfo.batteryVoltage = voltage;
    }

    if (storage.status === 'fulfilled') {
      const d = storage.value.data ?? {};
      const total = sysNumber(d.total);
      const used = sysNumber(d.used);
      // total 为 0 时进度条无意义（也除不出百分比），按未加载处理
      sysInfo.storageLoaded = total !== null && total > 0;
      sysInfo.storageTotal = total ?? 0;
      sysInfo.storageUsed = used ?? 0;
      sysInfo.storageUsagePercent = sysNumber(d.usage_percent) ?? 0;
    }

    if (rootCheck.status === 'fulfilled') {
      // 字段是 camelCase 的 hasRoot（与 /api/diagnose 的 root 不同源，各自独立展示）
      sysInfo.hasRoot = !!rootCheck.value.data?.hasRoot;
      sysInfo.rootLoaded = true;
    }

    if (startup.status === 'fulfilled') {
      sysInfo.startupTimeMs = sysNumber(startup.value.data?.startupTimeMs) ?? 0;
    }

    sysFetchedAt.value = Date.now();
  } finally {
    sysLoading.value = false;
  }
}

async function loadDiagnostics() {
  try {
    const { data } = await api.get('/api/diagnose');
    // server_time 是 epoch 毫秒；旧代码直接渲染原始数字
    diag.serverTime = data.server_time
      ? new Date(Number(data.server_time)).toLocaleString('zh-CN', { hour12: false })
      : '--';
    diag.appVersion = data.app_version || '--';
    diag.root = !!data.root;
    // adbd / mobile_data / gateway 都是 shell 输出的字符串（如 "running"/"stopped"/"1"/"0"/"unknown"），
    // 旧代码用 !! 判断 → "stopped"、"0"、"unknown" 全部被当成 true
    diag.adbd = String(data.adbd ?? '').trim();
    diag.mobileData = String(data.mobile_data ?? '').trim();
    diag.gateway = String(data.gateway ?? '').trim();
  } catch {
    /* silent */
  }
}

// ══════════════════════════════════════════════
//  Lifecycle
// ══════════════════════════════════════════════
// 本面板全部只读且无轮询（系统信息刻意只在挂载与手动刷新时各拉一次），因此没有需要
// 在 onUnmounted 里清理的定时器。

onMounted(() => {
  loadVersion();
  loadSystemInfo();
  loadDiagnostics();
});
</script>

<style scoped>
/* .settings-panel 栅格与断点已统一到 src/styles/main.css（全局，8 个面板共用一份） */

/* ── Text helpers ── */
.text-success {
  color: #18a058;
}
.text-error {
  color: #d03050;
}
</style>

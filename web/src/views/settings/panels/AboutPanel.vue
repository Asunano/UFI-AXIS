<template>
  <div class="settings-panel">
    <!--
      卡片编排约束（2026-09-10 起，改这一页前先读）：
      `.settings-panel` 是 2 列栅格，同一行的轨道等高、矮卡贴顶（main.css 的 align-items:start），
      所以**同一行两张卡的高度差必须 ≤ 1 行**（InfoRow 一行 ≈ 38px），
      否则矮卡下方会露出一整块页面底色 —— 改造前这里就有一块 263px 的空洞。
      当前配对（DOM 顺序即栅格顺序，行优先）：
        行 1 = 版本与来源(5 行) ｜ 运行与存储(4 行 + 进度条)
        行 2 = 电池(5 行)        ｜ 诊断信息(5 行)
      增删行之前先按这个约束重算，不要让单张卡独自变长。
    -->
    <!-- Card 1: 版本与来源 —— 原「版本信息」+「开源信息」合并。
         合并的首要理由是排版：开源信息只有 2 行，独立成卡时它与「诊断信息」同行，
         而 2 列栅格的同一行是**等高**的（main.css 的 .settings-panel），矮卡下方会
         露出 147px 整块页面底色（2026-09-10 实测）。
         其次两者语义同源：都是「这个版本从哪来、许可是什么」。 -->
    <GridCard title="版本与来源">
      <InfoRow label="应用版本" :value="versionInfo.version" />
      <InfoRow label="最低客户端版本" :value="versionInfo.minClientVersion" />
      <!-- 长 URL 单独一行并放开 InfoRow 默认的 65% 值宽 -->
      <InfoRow class="row-url" label="更新地址">
        <template #default>
          <code class="url-text" :title="versionInfo.updateUrl || '--'">{{ versionInfo.updateUrl || '--' }}</code>
        </template>
      </InfoRow>
      <InfoRow label="项目仓库">
        <template #default>
          <a :href="REPO_URL" target="_blank" rel="noopener noreferrer">github.com/Asunano/UFI-AXIS</a>
        </template>
      </InfoRow>
      <!-- 为什么这一行必须在 core 侧界面上能点到：那几个预编译二进制
           （aria2c / socat / adb / ttyd / curl / jq / sendat）是 **core APK** 打包并释放到
           设备执行的，其中 aria2c / socat 属 GPL 系 —— 分发义务（附许可证 + 提供对应源码）
           产生在 core 这一侧。app 端「关于」也放了同一个入口（AboutDeviceScreen 的「开源许可」），
           两处指向同一份文件，不各写一份文案，避免漂移。 -->
      <InfoRow label="开源许可">
        <template #default>
          <a :href="THIRD_PARTY_NOTICES_URL" target="_blank" rel="noopener noreferrer">第三方组件与许可证声明</a>
        </template>
      </InfoRow>
    </GridCard>

    <!-- Card 1.2a: 运行与存储 / Card 1.2b: 电池 —— 原「系统信息」一卡 9 行拆成两张 5 行内外的卡。
         拆的理由同样在排版：9 行让这张卡高达 420px，与同行的 3 行卡（173px）差 247px，
         矮的那张下方整块空白（2026-09-10 实测该页空洞 263px）。
         拆成两张后每张 4~5 行，与同行卡的高度差收敛到 1 行以内。
         数据源不变（core /api/system/*）：四个端点都无参数、恒 200、无失败信封；
         battery / storage 采集异常时返回 {}。
         刻意不轮询：设备只有 256MB 内存，仅在挂载与手动点「刷新」时各拉一次。 -->
    <GridCard title="运行与存储">
      <template #extra>
        <n-button size="tiny" quaternary :loading="sysLoading" @click="loadSystemInfo">刷新</n-button>
      </template>
      <!-- 标签说明：这个值来自 /api/system/root-check 的 hasRoot，判据是
           ShellExecutor.hasRootAccess() —— 即「ADB 特权通道是否可用」（底层 ADB shell 是
           uid 2000，不是 uid=0）。它与下面「诊断信息」里那个 Root（uid=0，来自 /api/diagnose
           的真实执行结果）**不是同一个探针**，实测会出现「通道可用但 shell 不是 root」，
           所以两个都保留、标签必须能区分开。 -->
      <InfoRow label="ADB 特权通道">
        <template #default>
          <span :class="sysInfo.hasRoot ? 'text-success' : 'text-error'">{{ sysRootText }}</span>
        </template>
      </InfoRow>
      <InfoRow label="服务启动时间" :value="sysStartupTimeText" />
      <InfoRow label="已运行" :value="sysStartupUptimeText" />
      <InfoRow label="存储（已用 / 总量）" :value="sysStorageText" />
      <n-progress
        v-if="sysInfo.storageLoaded"
        type="line"
        :percentage="sysStoragePercent"
        :height="6"
        style="margin-top: 8px"
      />
    </GridCard>

    <GridCard title="电池">
      <InfoRow label="电池电量" :value="sysBatteryPercentText" />
      <InfoRow label="充电状态" :value="sysBatteryChargingText" />
      <InfoRow label="电源类型" :value="sysBatteryPluggedText" />
      <InfoRow label="电池温度" :value="sysBatteryTempText" />
      <InfoRow label="电池电压" :value="sysBatteryVoltageText" />
    </GridCard>

    <!-- Card 6: 诊断信息
         2026-09-10 去重两行：
         · 「应用版本」删掉 —— 它与「版本与来源」的应用版本是同一件事（都读已安装包版本），
           canonical 来源是 /api/config/version，diagnose 那份原本还写死过 "0.1"（见
           HttpServer.kt 的注释），留着只会让人以为是两个数。
         · 「Root 状态」保留但改名为「Root（uid=0）」—— 它与「运行与存储」的 ADB 特权通道
           **不是同一探针**（这里是 executeAsRoot("id") 的真实结果，含 uid=0 才算），
           原来两个都叫 Root 却给出相反结论（实测「已获取 / 未获取」同屏），所以要分开命名。 -->
    <GridCard title="诊断信息">
      <template #extra>
        <n-button size="tiny" quaternary @click="loadDiagnostics">刷新</n-button>
      </template>
      <InfoRow label="服务器时间" :value="diag.serverTime || '--'" />
      <InfoRow label="Root（uid=0）">
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

// 「ADB 特权通道」用 可用/不可用 而不是 已获取/未获取：它判的是通道，
// 不是 shell 的 uid（uid 那个在「诊断信息」的 Root（uid=0）里）。
const sysRootText = computed(() => (sysInfo.rootLoaded ? (sysInfo.hasRoot ? '可用' : '不可用') : '--'));
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
// 刻意不收 app_version：它与「版本与来源」的应用版本同源（都读已安装包版本），
// 展示两份只会制造「哪个是真的」的疑问。canonical 来源是 /api/config/version。
const diag = reactive({
  serverTime: '',
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

/* ── 更新地址行 ──
   InfoRow 默认给值限了 65% 宽并省略号（InfoRow.vue 的 .info-value），对短值是对的，
   但更新地址是本页唯一需要看清 / 抄走的长字符串，65% 会把它截成 "…/ma…"。
   这里只对这一行放开到整行，并把字号降到 12px —— 实测同一条 URL 在 12px 下能整行放下。
   不用 <code> 等宽：68 个字符在 12px 等宽下约 490px，会超出 482px 的可用宽。 */
.row-url :deep(.info-value) {
  max-width: 100%;
}
.url-text {
  font-size: 12px;
  color: var(--text-secondary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* ── Text helpers ── */
.text-success {
  color: var(--success);
}
.text-error {
  color: var(--error);
}
</style>

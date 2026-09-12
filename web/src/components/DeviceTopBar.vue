<script setup lang="ts">
import { ref, computed } from 'vue';
import { useRouter } from 'vue-router';
import { useDashboardStore } from '@/stores/dashboard';
import { getApiClient } from '@/composables/useApi';
import { formatUptime, networkTypeWithBand, signalColor, get } from '@/composables/utils';

const router = useRouter();
const dashboardStore = useDashboardStore();

// ── 设备概览（型号 + 悬浮详情，复用 summary.device_info）──
const summaryDeviceInfo = computed(() => get(dashboardStore.summary, 'device_info', null));
const firmwareVersion = ref('--');
const magiskStatus = ref('--');
const selinuxStatus = ref('--');
const imeiFromIdentity = ref<string | null>(null);

const deviceModelText = computed(() => {
  const d = get(summaryDeviceInfo.value, 'device', null);
  if (!d) return '--';
  if (typeof d === 'string') return d;
  // 后端也可能返回 { brand, model }
  return [d.brand, d.model].filter(Boolean).join(' ') || d.manufacturer || '--';
});
const firmwareText = computed(() => firmwareVersion.value);
/**
 * 运行时间。
 * core 给的键是 `uptime_seconds` / `uptime_display`（SystemCollector），
 * 之前这里读的是 `seconds` / `display` —— 键名对不上，所以**任何页面都永远显示 `--`**。
 * 根源是 types/index.ts 的 UptimeInfo 声明就写错了（已一并改正）。
 * 优先读 summary 顶层的 uptime：它和 device_info.uptime 同源，但不依赖设备页/仪表盘去刷。
 */
const uptimeText = computed(() => {
  const u = get(dashboardStore.summary, 'uptime', null) || get(summaryDeviceInfo.value, 'uptime', null);
  if (u?.uptime_seconds != null && Number(u.uptime_seconds) >= 0) return formatUptime(Number(u.uptime_seconds));
  if (u?.uptime_display) return String(u.uptime_display);
  return '--';
});
const selinuxText = computed(() => selinuxStatus.value || '--');
const magiskText = computed(() => magiskStatus.value || '--');
const imeiText = computed(() => {
  const fromInfo = get(summaryDeviceInfo.value, 'identity.imei', null) || get(summaryDeviceInfo.value, 'imei', null);
  if (typeof fromInfo === 'string' && fromInfo) return fromInfo;
  if (typeof imeiFromIdentity.value === 'string' && imeiFromIdentity.value) return imeiFromIdentity.value;
  return '--';
});

async function loadDeviceExtra(): Promise<boolean> {
  let ok = true;
  try {
    const api = getApiClient();
    const [{ data: ver }, { data: mag }, { data: sel }] = await Promise.all([
      api.get('/api/device/version'),
      api.get('/api/device/magisk'),
      api.get('/api/device/selinux'),
    ]);
    const parts = [ver.language, ver.cr_version, ver.wa_inner_version].filter(Boolean);
    firmwareVersion.value = parts.join(' / ') || '--';
    magiskStatus.value = mag.hasRoot ? `已 Root (${mag.magiskVersion || '未知版本'})` : '未 Root';
    selinuxStatus.value = sel.selinux || '--';
  } catch {
    ok = false; /* 静默，交给调用方决定是否下次重试 */
  }

  // 如果 version 接口没拿到固件版本，尝试从 device_info.versions 取
  if (firmwareVersion.value === '--') {
    const v = get(summaryDeviceInfo.value, 'versions', null) || get(summaryDeviceInfo.value, 'version', null);
    if (v) {
      const parts = [v.language, v.cr_version, v.wa_inner_version, v.version].filter(Boolean);
      firmwareVersion.value = parts.join(' / ') || '--';
    }
  }
  return ok;
}

async function loadDeviceIdentity(): Promise<boolean> {
  try {
    const api = getApiClient();
    const { data } = await api.get('/api/device/identity');
    imeiFromIdentity.value = data?.imei || null;
    return true;
  } catch {
    return false;
  }
}

/*
  这四个接口（version / magisk / selinux / identity）只服务于悬浮面板，
  但本组件挂在 DefaultLayout 上 —— 原来放在 onMounted 里，等于**每次打开任意页面都白打 4 个请求**。
  面板既然已经降级成「简略详情」，就改成首次展开时再拉，之后缓存不再重复请求；
  失败时不置 loaded，下次 hover 会重试。
*/
const detailLoaded = ref(false);
const detailLoading = ref(false);
/** 只在「确实还没有值」时才显示占位，已从 summary 拿到的字段照常显示 */
const detailPending = computed(() => detailLoading.value && !detailLoaded.value);

async function ensureDeviceDetail() {
  if (detailLoaded.value || detailLoading.value) return;
  detailLoading.value = true;
  try {
    const results = await Promise.all([loadDeviceExtra(), loadDeviceIdentity()]);
    detailLoaded.value = results.every(Boolean);
  } finally {
    detailLoading.value = false;
  }
}

/** 值缺失且正在拉取时给个占位，避免面板一打开满屏 `--` */
function orPending(v: string): string {
  return v === '--' && detailPending.value ? '加载中…' : v;
}

// ── 顶栏实时芯片 ──
const signalInfo = computed(() => dashboardStore.realtimeSignal || null);
/** RSRP。用 `!= null` 而不是真值判断：0 虽然不是合法 dBm，但真值判断会把它也吞成 `--` */
const signalText = computed(() => {
  const r = signalInfo.value?.rsrp;
  return r != null ? `${r} dBm` : '--';
});
/** 信号值染色：与仪表盘的 RSRP 读数同一套阈值，不用额外背景块表达强弱 */
const signalTint = computed(() => {
  const r = signalInfo.value?.rsrp;
  return r != null ? signalColor(Number(r)) : 'var(--text-secondary)';
});

const networkStatus = computed(() => get(dashboardStore.summary, 'network_status', null));
/**
 * 网络制式。原来读的是 `realtimeSignal.rat` —— 那个字段虽然存在，但 goform 的
 * `network_type` 为空串时整个键会被丢掉（SignalCollector 只在非空时写入），
 * 所以标签经常整块不渲染；而且它的值域里混着中文和 `未知(xx)`。
 * 全站其它地方（仪表盘、网络页）表示制式都用 network_status.network.network_type
 * 配 networkTypeWithBand()，这里跟它们对齐。
 */
const netTypeText = computed(() => {
  const t = get(networkStatus.value, 'network.network_type', '');
  if (!t) return '';
  return networkTypeWithBand(String(t), signalInfo.value);
});

/**
 * 运营商。信号帧里的 `operator` 是驻网即有的稳定字段，优先用它；
 * WS 还没推第一帧时退回 summary.device_info.network.operator。
 */
const operatorText = computed(() => {
  const fromSignal = signalInfo.value?.operator;
  if (fromSignal) return String(fromSignal);
  return String(get(summaryDeviceInfo.value, 'network.operator', '') || '--');
});

const online = computed(() => {
  const net = get(summaryDeviceInfo.value, 'network', null);
  if (net?.connected != null) return !!net.connected;
  // 原来这里读 network_status.is_connected —— 那个键不存在（在 network 子对象里），
  // 所以这条 fallback 一直是死代码，恒为 false
  return !!get(networkStatus.value, 'network.is_connected', false);
});

function goDetail() {
  router.push({ name: 'dashboard' });
}
</script>

<template>
  <div class="device-top-bar">
    <n-popover
      trigger="hover"
      placement="bottom"
      :show-arrow="false"
      raw
      :style="{ padding: '0', borderRadius: 'var(--radius-md)', overflow: 'hidden' }"
      content-style="padding:0"
      @update:show="(v: boolean) => v && ensureDeviceDetail()"
    >
      <template #trigger>
        <!--
          2026-09-05 重排：原来是「大圆角胶囊里再套两个小圆角胶囊」，
          三层边框 + 三层背景叠在一起，顶栏本来就窄，看着很碎。
          现在只保留最外层一个容器，内部改成用 1px 竖分隔线切段、没有任何内层底色，
          颜色只留给状态点和信号值（都带语义）。
        -->
        <div class="dtb-bar">
          <!-- 设备主体 + 在线状态点 -->
          <div class="dtb-seg dtb-seg-device">
            <span class="dtb-ico" aria-hidden="true">
              <svg
                viewBox="0 0 24 24"
                fill="none"
                stroke="currentColor"
                stroke-width="2"
                stroke-linecap="round"
                stroke-linejoin="round"
              >
                <rect x="6" y="2" width="12" height="20" rx="2.5" />
                <line x1="11" y1="18" x2="13" y2="18" />
              </svg>
            </span>
            <span class="dtb-name">{{ deviceModelText }}</span>
            <span class="dtb-dot" :class="online ? 'is-online' : 'is-offline'" :title="online ? '在线' : '离线'"></span>
          </div>

          <!-- 网络制式：拿不到时整段不占位，而不是显示一个空标签 -->
          <span v-if="netTypeText" class="dtb-seg dtb-seg-net">{{ netTypeText }}</span>

          <!-- 信号：值本身按强弱染色，不再套底色块 -->
          <span class="dtb-seg">
            <span class="dtb-val" :style="{ color: signalTint }">{{ signalText }}</span>
          </span>

          <!-- 运行时间：移动端第一个被隐藏的段（信息价值最低） -->
          <span class="dtb-seg dtb-seg-uptime">
            <span class="dtb-val">{{ uptimeText }}</span>
          </span>

          <n-button text size="tiny" type="primary" class="dtb-more" @click.stop="goDetail">详情 ›</n-button>
        </div>
      </template>
      <!--
        简略设备详情。定位就是「顶栏塞不下、但值得一眼看到」的几项，不再是完整设备信息的入口：
        · 型号提到标题位（原来它独占一整行 + 一条分隔线，对速览来说太重）
        · 去掉「查看完整信息」出口 —— 完整信息在设备页，这里不做二次导航
        · 去掉内核版本（字符串最长、最不 actionable），换上运营商（顶栏没有、又常要看）
        · 固件版本与 IMEI 通栏：这两项值本身很长，挤在半栏里会折成三行
      -->
      <div class="dev-panel">
        <div class="dev-panel-header">
          <div class="dev-panel-icon">
            <svg
              width="16"
              height="16"
              viewBox="0 0 24 24"
              fill="none"
              stroke="currentColor"
              stroke-width="2"
              stroke-linecap="round"
              stroke-linejoin="round"
            >
              <rect x="4" y="2" width="16" height="20" rx="2" />
              <line x1="12" y1="18" x2="12" y2="18" />
            </svg>
          </div>
          <span class="dev-panel-title">{{ deviceModelText }}</span>
          <span class="dev-panel-state" :class="online ? 'is-online' : 'is-offline'">
            {{ online ? '在线' : '离线' }}
          </span>
        </div>
        <div class="dev-panel-body">
          <div class="dev-grid">
            <div class="dev-cell">
              <span class="dev-label">运营商</span>
              <span class="dev-val">{{ operatorText }}</span>
            </div>
            <div class="dev-cell">
              <span class="dev-label">运行时间</span>
              <span class="dev-val">{{ uptimeText }}</span>
            </div>
            <div class="dev-cell dev-cell-wide">
              <span class="dev-label">固件版本</span>
              <span class="dev-val" :class="{ 'dev-val-pending': detailPending }">{{ orPending(firmwareText) }}</span>
            </div>
            <div class="dev-cell">
              <span class="dev-label">SELinux</span>
              <span class="dev-val">
                <span v-if="detailPending && selinuxText === '--'" class="dev-val-pending">加载中…</span>
                <span
                  v-else
                  class="dev-badge"
                  :class="'dev-badge-' + (selinuxStatus === 'Enforcing' ? 'warn' : 'ok')"
                  >{{ selinuxText }}</span
                >
              </span>
            </div>
            <div class="dev-cell">
              <span class="dev-label">Magisk</span>
              <span class="dev-val">
                <span v-if="detailPending && magiskText === '--'" class="dev-val-pending">加载中…</span>
                <span
                  v-else
                  class="dev-badge"
                  :class="'dev-badge-' + (magiskStatus.includes('已 Root') ? 'root' : 'info')"
                  >{{ magiskText }}</span
                >
              </span>
            </div>
            <div class="dev-cell dev-cell-wide">
              <span class="dev-label">IMEI</span>
              <span class="dev-val" :class="detailPending && imeiText === '--' ? 'dev-val-pending' : 'dev-val-mono'">{{
                orPending(imeiText)
              }}</span>
            </div>
          </div>
        </div>
      </div>
    </n-popover>
  </div>
</template>

<!-- 非 scoped：popover 渲染在 body 下，需全局样式 -->
<style>
/* ── 顶部设备信息条 ── */
.device-top-bar {
  display: inline-flex;
}
.dtb-bar {
  display: inline-flex;
  align-items: stretch;
  /* 段与段之间不留 gap：间距由每段自己的 padding + 竖分隔线负责，
     这样分隔线能贴满整条的高度、不出现断头 */
  gap: 0;
  padding: 0 6px 0 0;
  background: var(--surface-elevated);
  border: 1px solid var(--border-subtle);
  border-radius: var(--radius-md);
  cursor: default;
  font-size: 13px;
  line-height: 1;
  transition:
    background 0.2s ease,
    border-color 0.2s ease;
}
.dtb-bar:hover {
  background: var(--surface-hover);
  border-color: var(--border-subtle);
}

/* 一段信息。除第一段外都带左侧发丝线 —— 这是唯一的分隔手段，不再靠内层胶囊 */
.dtb-seg {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 7px 10px;
  white-space: nowrap;
}
.dtb-seg + .dtb-seg {
  border-left: 1px solid var(--border-subtle);
}
.dtb-seg-device {
  gap: 7px;
  padding-left: 11px;
}

.dtb-ico {
  display: inline-flex;
  color: var(--accent-color);
}
.dtb-ico svg {
  width: 15px;
  height: 15px;
}
.dtb-name {
  color: var(--text-primary);
  font-weight: 600;
  max-width: 180px;
  overflow: hidden;
  text-overflow: ellipsis;
}
/* 制式：小一号、字距略开，读起来像标签但没有底色 */
.dtb-seg-net {
  color: var(--text-secondary);
  font-size: 12px;
  letter-spacing: 0.02em;
}
.dtb-val {
  color: var(--text-secondary);
  font-weight: 600;
  font-variant-numeric: tabular-nums;
}

/* 在线状态点：唯一带光环的元素，离线时只压暗不发光 */
.dtb-dot {
  width: 7px;
  height: 7px;
  border-radius: var(--radius-pill);
  flex-shrink: 0;
}
.dtb-dot.is-online {
  background: var(--success);
  box-shadow: 0 0 0 3px rgba(24, 160, 88, 0.18);
}
.dtb-dot.is-offline {
  background: var(--text-muted);
  opacity: 0.45;
}

.dtb-more {
  align-self: center;
  margin-left: 6px;
  font-size: 12px;
}

/*
  移动端：**不再把信号与运行时间整块 display:none**（那是之前「有的信息看不到」的一半原因）。
  只藏掉运行时间这一段 —— 它是四项里最不需要在顶栏盯着的，
  型号 / 在线点 / 制式 / 信号都保留。
*/
@media (max-width: 768px) {
  .dtb-seg {
    padding: 6px 8px;
  }
  .dtb-seg-device {
    padding-left: 9px;
  }
  .dtb-seg-uptime {
    display: none;
  }
  .dtb-name {
    max-width: 96px;
  }
  .dtb-more {
    margin-left: 2px;
  }
}

/* ── 设备详情面板（Popover 内）── */
.dev-panel {
  width: 320px;
  background: var(--card-bg);
  border-radius: var(--radius-md);
  box-shadow:
    0 6px 24px rgba(0, 0, 0, 0.12),
    0 2px 8px rgba(0, 0, 0, 0.06);
  overflow: hidden;
}
.dev-panel-header {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 12px 16px;
  background: var(--surface-elevated);
  border-bottom: 1px solid var(--border-subtle);
}
.dev-panel-icon {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  border-radius: var(--radius-sm);
  background: var(--accent-color-light);
  color: var(--accent-color);
  flex-shrink: 0;
}
.dev-panel-icon svg {
  width: 15px;
  height: 15px;
}
.dev-panel-title {
  /* 现在这里放的是设备型号，可能很长：吃掉剩余宽度并截断，不要把状态标签挤出去 */
  flex: 1;
  min-width: 0;
  font-size: 13px;
  font-weight: 600;
  color: var(--text-primary);
  letter-spacing: 0.01em;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
/* 在线状态：标题右侧的小字，不用点也不用底色（面板里已经不缺颜色了） */
.dev-panel-state {
  flex: none;
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 0.02em;
}
.dev-panel-state.is-online {
  color: var(--success);
}
.dev-panel-state.is-offline {
  color: var(--text-muted);
}
.dev-panel-body {
  padding: 12px 16px;
}

/* 两列网格 */
.dev-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 10px 20px;
}
.dev-cell {
  display: flex;
  flex-direction: column;
  gap: 3px;
}
/* 固件版本 / IMEI 这类长值通栏，挤在半栏里会折成三行 */
.dev-cell-wide {
  grid-column: 1 / -1;
}

/* 标签 / 值 */
.dev-label {
  font-size: 11px;
  color: var(--text-muted);
  text-transform: uppercase;
  letter-spacing: 0.04em;
  line-height: 1.2;
}
.dev-val {
  font-size: 13px;
  color: var(--text-primary);
  font-weight: 500;
  line-height: 1.35;
  word-break: break-all;
}
.dev-val-mono {
  font-family: 'SF Mono', 'Fira Code', 'Cascadia Code', Consolas, monospace;
  font-size: 12px;
  letter-spacing: -0.01em;
}
/* 首次展开时的加载占位：压暗即可，不用骨架屏（面板小、请求快） */
.dev-val-pending {
  color: var(--text-muted);
  font-weight: 400;
}

/* 状态徽章 */
.dev-badge {
  display: inline-flex;
  align-items: center;
  padding: 1px 8px;
  border-radius: 999px;
  font-size: 11px;
  font-weight: 600;
  line-height: 1.7;
  letter-spacing: 0.02em;
}
/* 四个徽标的淡底/文字色都走语义令牌。`--*-light` 自带暗色档，
   所以原先那 4 条 `.dark .dev-badge-*` 覆盖（0.1→0.14 / 0.08→0.12）已删除 ——
   取值与令牌逐字相同，行为不变，少一份要同步的拷贝。 */
.dev-badge-ok {
  background: var(--success-light);
  color: var(--success);
}
.dev-badge-warn {
  background: var(--warning-light);
  color: var(--warning);
}
.dev-badge-root {
  background: var(--error-light);
  color: var(--error);
}
.dev-badge-info {
  background: var(--accent-color-light);
  color: var(--accent-color);
}

/* 暗色模式微调 */
.dark .dev-panel {
  box-shadow:
    0 8px 32px rgba(0, 0, 0, 0.35),
    0 2px 8px rgba(0, 0, 0, 0.2);
}
</style>

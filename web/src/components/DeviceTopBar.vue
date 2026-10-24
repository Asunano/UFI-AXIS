<script setup lang="ts">
import { ref, computed } from 'vue';
import { useDashboardStore } from '@/stores/dashboard';
import { getApiClient } from '@/composables/useApi';
import { formatUptime, networkTypeWithBand, signalColor, get } from '@/composables/utils';

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

/**
 * 四个 `/api/device/*` 接口（version / magisk / selinux / identity）的失败标记。
 *
 * 之前 catch 里什么都不做，于是「请求失败」和「设备没有这个字段」在面板上是同一个样子（都是 `--`），
 * 用户分不出该刷新还是该接受。现在按字段记下来，缺值时显示「未取到」。
 */
const versionFailed = ref(false);
const magiskFailed = ref(false);
const selinuxFailed = ref(false);
const imeiFailed = ref(false);

/** 单个请求的结果。失败只影响自己那几行，不连带把并发里另外两个的已取到的值丢掉。 */
async function settle<T>(p: Promise<T>): Promise<{ ok: boolean; data: any }> {
  try {
    const res = (await p) as { data?: any };
    return { ok: true, data: res?.data };
  } catch {
    return { ok: false, data: null };
  }
}

async function loadDeviceExtra(): Promise<boolean> {
  const api = getApiClient();
  versionFailed.value = false;
  magiskFailed.value = false;
  selinuxFailed.value = false;
  // 三个请求仍然并发（与改前的 Promise.all 同一时刻发出），只是各自结算
  const [ver, mag, sel] = await Promise.all([
    settle(api.get('/api/device/version')),
    settle(api.get('/api/device/magisk')),
    settle(api.get('/api/device/selinux')),
  ]);

  if (ver.ok) {
    const v = ver.data ?? {};
    const parts = [v.language, v.cr_version, v.wa_inner_version].filter(Boolean);
    firmwareVersion.value = parts.join(' / ') || '--';
  } else {
    versionFailed.value = true;
  }

  if (mag.ok) {
    const m = mag.data ?? {};
    magiskStatus.value = m.hasRoot ? `已 Root (${m.magiskVersion || '未知版本'})` : '未 Root';
  } else {
    magiskFailed.value = true;
  }

  if (sel.ok) {
    selinuxStatus.value = sel.data?.selinux || '--';
  } else {
    selinuxFailed.value = true;
  }

  // 如果 version 接口没拿到固件版本，尝试从 device_info.versions 取
  if (firmwareVersion.value === '--') {
    const v = get(summaryDeviceInfo.value, 'versions', null) || get(summaryDeviceInfo.value, 'version', null);
    if (v) {
      const parts = [v.language, v.cr_version, v.wa_inner_version, v.version].filter(Boolean);
      firmwareVersion.value = parts.join(' / ') || '--';
    }
  }
  return ver.ok && mag.ok && sel.ok;
}

async function loadDeviceIdentity(): Promise<boolean> {
  try {
    const api = getApiClient();
    const { data } = await api.get('/api/device/identity');
    imeiFromIdentity.value = data?.imei || null;
    imeiFailed.value = false;
    return true;
  } catch {
    imeiFailed.value = true;
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

/** 一项详情值的显示态：真实值 / 加载中 / 未取到 / 设备无此字段（`--`）。 */
interface FieldState {
  /** 要显示的文字 */
  display: string;
  /** true = 不是真实值（要压暗，且不能套状态徽标） */
  placeholder: boolean;
}

/**
 * 让「没取到」与「设备没有这个字段」在界面上分开。
 *
 * 判据顺序不能换：`--` 且正在拉 → 加载中…；`--` 且这次请求失败 → 未取到；
 * 其余（含设备确实没给该字段）→ `--`。已知字段有值时一律显示真实值，
 * 哪怕同时有别的接口失败（部分失败不该把已拿到的值也抹成占位）。
 */
function fieldStateOf(text: string, failed: boolean): FieldState {
  if (text !== '--') return { display: text, placeholder: false };
  if (detailPending.value) return { display: '加载中…', placeholder: true };
  if (failed) return { display: '未取到', placeholder: true };
  return { display: '--', placeholder: true };
}

const firmwareState = computed(() => fieldStateOf(firmwareText.value, versionFailed.value));
const selinuxState = computed(() => fieldStateOf(selinuxText.value, selinuxFailed.value));
const magiskState = computed(() => fieldStateOf(magiskText.value, magiskFailed.value));
const imeiState = computed(() => fieldStateOf(imeiText.value, imeiFailed.value));

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
/** 4 格信号：沿用 RSRP 的常用阈值，只用于顶栏小条，不参与任何业务判断。 */
const signalLevel = computed(() => {
  const r = Number(signalInfo.value?.rsrp);
  if (!Number.isFinite(r)) return 0;
  if (r >= -85) return 4;
  if (r >= -95) return 3;
  if (r >= -105) return 2;
  if (r >= -115) return 1;
  return 0;
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
</script>

<template>
  <div class="device-top-bar">
    <!--
      trigger="click" 而不是 hover（2026-09-19 改）：这层浮层里装的是运营商 / 固件版本 /
      SELinux / Magisk / IMEI —— 触屏设备上没有 hover 事件，改之前这些字段在手机与
      平板上**根本打不开**。桌面端点击同样自然（顶栏那颗「详情」本来就长得像按钮）。
    -->
    <n-popover
      trigger="click"
      placement="bottom"
      :show-arrow="false"
      raw
      :style="{ padding: '0', borderRadius: 'var(--radius-md)', overflow: 'hidden' }"
      content-style="padding:0"
      @update:show="(v: boolean) => v && ensureDeviceDetail()"
    >
      <template #trigger>
        <!--
          2026-10-24：从「分段胶囊」改成无底色内联状态。
          设备图标、型号、在线点、制式、信号和展开箭头排成一行；
          不再给整条加底色，也不再给图标套绿色方块。
        -->
        <button type="button" class="dtb-inline" aria-label="设备状态与详情">
          <span class="dtb-ico" aria-hidden="true">
            <svg
              viewBox="0 0 24 24"
              fill="none"
              stroke="currentColor"
              stroke-width="1.8"
              stroke-linecap="round"
              stroke-linejoin="round"
            >
              <rect x="6.5" y="2" width="11" height="20" rx="2.2" />
              <path d="M10.5 18.5h3" />
            </svg>
          </span>
          <span class="dtb-name">{{ deviceModelText }}</span>
          <span class="dtb-dot" :class="online ? 'is-online' : 'is-offline'" :title="online ? '在线' : '离线'"></span>
          <span class="dtb-divider" aria-hidden="true"></span>
          <span v-if="netTypeText || signalText !== '--'" class="dtb-net">
            <span class="dtb-signal-bars" :style="{ color: signalTint }" aria-hidden="true">
              <i v-for="level in 4" :key="level" :class="{ on: level <= signalLevel }"></i>
            </span>
            <span v-if="netTypeText" class="dtb-net-type">{{ netTypeText }}</span>
            <span v-if="signalText !== '--'" class="dtb-signal-text">{{ signalText }}</span>
          </span>
          <span class="dtb-chev" aria-hidden="true">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round">
              <path d="m6 9 6 6 6-6" />
            </svg>
          </span>
        </button>
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
              <span class="dev-val" :class="{ 'dev-val-pending': firmwareState.placeholder }">{{
                firmwareState.display
              }}</span>
            </div>
            <div class="dev-cell">
              <span class="dev-label">SELinux</span>
              <span class="dev-val">
                <span v-if="selinuxState.placeholder" class="dev-val-pending">{{ selinuxState.display }}</span>
                <span
                  v-else
                  class="dev-badge"
                  :class="'dev-badge-' + (selinuxStatus === 'Enforcing' ? 'warn' : 'ok')"
                  >{{ selinuxState.display }}</span
                >
              </span>
            </div>
            <div class="dev-cell">
              <span class="dev-label">Magisk</span>
              <span class="dev-val">
                <span v-if="magiskState.placeholder" class="dev-val-pending">{{ magiskState.display }}</span>
                <span
                  v-else
                  class="dev-badge"
                  :class="'dev-badge-' + (magiskStatus.includes('已 Root') ? 'root' : 'info')"
                  >{{ magiskState.display }}</span
                >
              </span>
            </div>
            <div class="dev-cell dev-cell-wide">
              <span class="dev-label">IMEI</span>
              <span class="dev-val" :class="imeiState.placeholder ? 'dev-val-pending' : 'dev-val-mono'">{{
                imeiState.display
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
.dtb-inline {
  height: 40px;
  max-width: 260px;
  display: inline-flex;
  align-items: center;
  gap: 7px;
  padding: 0 7px;
  border: 0;
  border-radius: 8px;
  background: transparent;
  color: var(--text-primary);
  cursor: pointer;
  min-width: 0;
  text-align: left;
  transition: background 0.16s ease;
}
.dtb-inline:hover {
  background: var(--surface-hover);
}
.dtb-ico {
  width: 16px;
  height: 16px;
  flex: none;
  display: inline-flex;
  color: var(--text-secondary);
}
.dtb-ico svg {
  width: 16px;
  height: 16px;
}
.dtb-name {
  max-width: 120px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 12.5px;
  font-weight: 600;
  line-height: 1;
}
.dtb-dot {
  width: 6px;
  height: 6px;
  border-radius: var(--radius-pill);
  flex-shrink: 0;
}
.dtb-dot.is-online {
  background: var(--success);
  box-shadow: 0 0 0 2px var(--accent-ring);
}
.dtb-dot.is-offline {
  background: var(--text-muted);
  opacity: 0.45;
}
.dtb-divider {
  width: 1px;
  height: 14px;
  background: var(--border-subtle);
  flex: none;
}
.dtb-net {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  min-width: 0;
  font-size: 10.5px;
  line-height: 1;
  color: var(--text-muted);
  white-space: nowrap;
}
.dtb-signal-bars {
  height: 10px;
  display: inline-flex;
  align-items: flex-end;
  gap: 1.5px;
  flex: none;
}
.dtb-signal-bars i {
  width: 2px;
  border-radius: 1px;
  background: currentColor;
  opacity: 0.22;
}
.dtb-signal-bars i.on {
  opacity: 1;
}
.dtb-signal-bars i:nth-child(1) {
  height: 3px;
}
.dtb-signal-bars i:nth-child(2) {
  height: 5px;
}
.dtb-signal-bars i:nth-child(3) {
  height: 7px;
}
.dtb-signal-bars i:nth-child(4) {
  height: 9px;
}
.dtb-net-type {
  max-width: 86px;
  overflow: hidden;
  text-overflow: ellipsis;
}
.dtb-signal-text {
  color: var(--text-muted);
  font-variant-numeric: tabular-nums;
}
.dtb-chev {
  width: 12px;
  height: 12px;
  display: inline-flex;
  color: var(--text-muted);
  flex: none;
}
.dtb-chev svg {
  width: 12px;
  height: 12px;
}
@media (max-width: 1180px) {
  .dtb-net,
  .dtb-divider {
    display: none;
  }
  .dtb-name {
    max-width: 96px;
  }
}
@media (max-width: 900px) {
  .dtb-inline {
    gap: 5px;
    padding: 0 5px;
  }
  .dtb-name {
    max-width: 78px;
  }
}
@media (max-width: 720px) {
  .dtb-chev {
    display: none;
  }
  .dtb-name {
    max-width: 68px;
  }
}
/* ── 设备详情面板（Popover 内）── */
/* 阴影颜色走令牌（--shadow-color-* 自带暗色档），所以下面不再需要
   `.dark .dev-panel` 那条只为了加深阴影而存在的覆盖 */
.dev-panel {
  /* 固定 320px 是桌面端的阅读宽度，但视口只有 300 多时它会顶出屏幕
     （popover 的定位容器不会替它收窄），所以给一个随视口收缩的上限 */
  width: min(320px, calc(100vw - 24px));
  background: var(--card-bg);
  border-radius: var(--radius-md);
  box-shadow:
    0 6px 24px var(--shadow-color-strong),
    0 2px 8px var(--shadow-color-soft);
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
</style>

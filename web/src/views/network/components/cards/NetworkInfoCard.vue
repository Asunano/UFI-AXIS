<template>
  <GridCard title="网络信息">
    <!-- 头部横跨两栏：左=制式/状态/运营商，右=信号条。
         2026-11-08 改版：去掉环形仪表（读弧长不如读数字快），参数与控制分左右两列。 -->
    <div class="ni-head">
      <div class="ni-id">
        <div class="ni-title">
          <span class="net-dot" :class="isConnected ? 'on' : 'off'" />
          <span class="conn-type">{{ connType }}</span>
          <span class="conn-state">{{ connState }}</span>
        </div>
        <div class="conn-operator">{{ operator }}</div>
      </div>
      <div class="sig-bars" :title="`RSRP ${rsrpText} dBm`" :aria-label="`信号 ${signalBars} 格`">
        <i v-for="n in 5" :key="n" :class="{ on: n <= signalBars }" />
      </div>
    </div>

    <div class="ni-grid">
      <!-- 左：只读参数 -->
      <div class="ni-col">
        <div v-for="m in paramRows" :key="m.label" class="ni-row">
          <span class="ni-ic"><component :is="m.icon" /></span>
          <span class="ni-lab">{{ m.label }}</span>
          <span class="ni-track"><i :style="{ width: m.percent + '%' }" /></span>
          <span class="ni-val">{{ m.display }}</span>
        </div>
      </div>

      <!-- 右：可写控制 -->
      <div class="ni-col is-rhs">
        <div class="ni-row">
          <span class="ni-ic"><CellularOutline /></span>
          <span class="ni-lab">移动数据</span>
          <n-switch
            class="ni-switch"
            :value="!!mobileData"
            size="small"
            :rubber-band="false"
            :loading="mobileDataSaving"
            @update:value="emit('update:mobile-data', $event)"
          />
        </div>
        <div class="ni-row">
          <span class="ni-ic"><AirplaneOutline /></span>
          <span class="ni-lab">飞行模式</span>
          <n-switch
            class="ni-switch"
            :value="!!airplaneOn"
            size="small"
            :rubber-band="false"
            :loading="airplaneSaving"
            @update:value="(v: boolean) => (v ? emit('airplane-on') : emit('airplane-off'))"
          />
        </div>
        <button
          type="button"
          class="ni-row ni-link"
          :disabled="modeUnsupported"
          :title="modeHint"
          @click="emit('open-mode')"
        >
          <span class="ni-ic"><SwapHorizontalOutline /></span>
          <span class="ni-lab">网络模式</span>
          <span class="ni-val" :class="{ muted: !modeText }">{{ modeText || '--' }}</span>
          <span class="ni-chev"><ChevronForwardOutline /></span>
        </button>
      </div>
    </div>
  </GridCard>
</template>

<script setup lang="ts">
import { computed } from 'vue';
import GridCard from '@/components/GridCard.vue';
import {
  AirplaneOutline,
  CellularOutline,
  ChevronForwardOutline,
  PulseOutline,
  SpeedometerOutline,
  SwapHorizontalOutline,
} from '@vicons/ionicons5';
import { networkTypeWithBand } from '@/composables/utils';

const props = defineProps<{
  netStatus: any;
  signal: any;
  mobileDataSaving: boolean;
  /** 飞行模式开关是否在写（core 的 airplane 接口没有读，读侧 2026-11-08 才补进 /network/status） */
  airplaneSaving?: boolean;
  /** 「网络模式」当前值文案（父组件已有 modeTileLabel） */
  modeText?: string;
  /** 当前设备/固件是否支持切网络模式 */
  modeUnsupported?: boolean;
  /** 不支持时的原因（title 提示） */
  modeHint?: string;
  /** 「频段锁定」当前值文案 */
  bandText?: string;
}>();

const emit = defineEmits<{
  'update:mobile-data': [boolean];
  'airplane-on': [];
  'airplane-off': [];
  'ppp-connect': [];
  'ppp-disconnect': [];
  'open-mode': [];
}>();

const isConnected = computed(() => !!props.netStatus?.network?.is_connected);
const mobileData = computed(() => !!props.netStatus?.mobile_data);
const connType = computed(() => networkTypeWithBand(props.netStatus?.network_type || '', props.signal));
const connState = computed(() => (isConnected.value ? '已连接' : '未连接'));
const operator = computed(() => props.netStatus?.operator || '--');

// RSRP 仪表盘：归一化 -140~-44 dBm 到 0-100。
// 判空一律 `!= null`：0 是合法数值（家规第 5 条），不能当"没有值"。

/** 飞行模式：读 /api/network/status 的 airplane 字段（core 2026-11-08 新增读侧） */
const airplaneOn = computed(() => !!props.netStatus?.airplane);

/** 信号格数（1–5）：按 RSRP 分档；无读数时一格不亮，不用满格误导 */
const signalBars = computed(() => {
  const r = Number(props.signal?.rsrp);
  if (!Number.isFinite(r) || r === 0) return 0;
  if (r >= -80) return 5;
  if (r >= -90) return 4;
  if (r >= -100) return 3;
  if (r >= -110) return 2;
  return 1;
});
const rsrpText = computed(() => (props.signal?.rsrp != null ? String(props.signal.rsrp) : '--'));

/**
 * 三个参数的百分比：只是为了给条一个长度，范围按工程经验取
 * （RSRP −110~−60 / SINR 0~30 / RSRQ −20~0），越界夹紧到 0–100。
 * 拿不到值时不画条（percent 为 null，模板里 v-if 掉）。
 */
function pct(v: number | undefined | null, min: number, max: number): number | null {
  if (v == null || !Number.isFinite(Number(v))) return null;
  return Math.max(0, Math.min(100, ((Number(v) - min) / (max - min)) * 100));
}

const paramRows = computed(() => {
  const s = props.signal;
  return [
    {
      label: '信号强度',
      display: s?.rsrp != null ? `${s.rsrp} dBm` : '--',
      percent: pct(s?.rsrp, -110, -60),
      icon: CellularOutline,
    },
    {
      label: '信噪比',
      display: s?.sinr != null ? `${s.sinr} dB` : '--',
      percent: pct(s?.sinr, 0, 30),
      icon: PulseOutline,
    },
    {
      label: '接收质量',
      display: s?.rsrq != null ? `${s.rsrq} dB` : '--',
      percent: pct(s?.rsrq, -20, 0),
      icon: SpeedometerOutline,
    },
  ];
});
</script>

<style scoped>
/* ── 上排：gauge + facts 水平化 ── */
.conn-horizontal {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr);
  gap: 16px;
  align-items: center;
}
.conn-gauge {
  flex-shrink: 0;
}
.conn-facts {
  display: flex;
  flex-direction: column;
  gap: 6px;
  min-width: 0;
}
.conn-title-row {
  display: flex;
  align-items: center;
  gap: 10px;
}
.net-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  flex-shrink: 0;
}
.net-dot.on {
  background: var(--success);
  box-shadow: 0 0 0 4px var(--accent-color-light);
}
.net-dot.off {
  background: var(--text-muted);
  opacity: 0.4;
}
.conn-type {
  font-size: 18px;
  font-weight: 700;
  color: var(--text-primary);
}
.conn-state {
  font-size: 10.5px;
  color: var(--accent-color);
  background: var(--accent-color-light);
  border-radius: 999px;
  padding: 2px 7px;
  line-height: 1.4;
}
.conn-operator {
  font-size: 12.5px;
  color: var(--text-secondary);
}

/* ── 控件条：2 项一行（WiFi 热点开关已移到下方 WiFi 卡）──
   单元格版式（标签左 / 控件右、对齐与间距）来自全局 `.config-item.switch-item`，
   这里只补背景/边框，让它看起来是紧凑的快捷开关，不再是一片裸文字。 */
.ctrl-bar {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--space-2);
  margin-top: 3px;
}
.ctrl-bar .config-item {
  min-height: 30px;
  padding: 0 9px;
  border: 1px solid var(--border-subtle);
  border-radius: 8px;
  background: var(--surface-elevated);
}
.ctrl-bar .config-label {
  white-space: nowrap;
}

/* ── 中段：3 个指标一行；用分隔线代替三个小盒子 ── */
.metric-strip {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 0;
  margin-top: 12px;
  padding-top: 10px;
  border-top: 1px solid var(--border-subtle);
}
.metric-cell {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
  padding: 0 12px;
  border-left: 1px solid var(--border-subtle);
}
.metric-cell:first-child {
  border-left: 0;
  padding-left: 0;
}
.metric-cell:last-child {
  padding-right: 0;
}
.metric-value-row {
  display: flex;
  align-items: baseline;
  gap: 4px;
}
.metric-value {
  font-size: 20px;
  font-weight: 700;
  color: var(--text-primary);
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
}
.metric-unit {
  font-size: 11px;
  color: var(--text-secondary);
}
.metric-label {
  font-size: 11px;
  color: var(--text-muted);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

/* ── 768–1024 中间档：指标字号降一档，开关仍两列 ── */
@media (max-width: 1024px) {
  .metric-value {
    font-size: var(--font-lg);
  }
  .metric-cell {
    padding: 0 8px;
  }
}

/* ── 窄屏：保持仪表 + 两开关横向，指标仍是一条三列，避免卡片被拉得过高 ── */
@media (max-width: 768px) {
  .conn-horizontal {
    grid-template-columns: auto minmax(0, 1fr);
    gap: 12px;
  }
  .metric-value {
    font-size: var(--font-lg);
  }
  .metric-cell {
    padding: 0 7px;
  }
}

/* ── 2026-11-08 改版：头部跨栏 + 左右两列等高行 ── */
.ni-head {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  padding-bottom: 10px;
}
.ni-id {
  min-width: 0;
}
.ni-title {
  display: flex;
  align-items: center;
  gap: 8px;
}
.ni-head .sig-bars {
  margin-left: auto;
  display: flex;
  align-items: flex-end;
  gap: 4px;
  height: 24px;
}
.sig-bars i {
  width: 6px;
  border-radius: 2px;
  background: var(--border-subtle);
}
.sig-bars i.on {
  background: var(--accent-color);
}
.sig-bars i:nth-child(1) {
  height: 6px;
}
.sig-bars i:nth-child(2) {
  height: 11px;
}
.sig-bars i:nth-child(3) {
  height: 16px;
}
.sig-bars i:nth-child(4) {
  height: 20px;
}
.sig-bars i:nth-child(5) {
  height: 24px;
}

/* 两列共用同一行高：左侧第 n 行与右侧第 n 行永远对齐 */
.ni-grid {
  /* 默认双栏：左参数 / 右控制。右列只剩两个开关，高度天然矮于左列，
     用 justify-content:center 让它们垂直居中，空白对称分布在上下而不是全堆在底部。 */
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
}
.ni-col {
  display: flex;
  flex-direction: column;
  min-width: 0;
}
.ni-col.is-rhs {
  border-left: 1px solid var(--border-subtle);
  justify-content: center;
}
.ni-col.is-rhs .ni-row:first-child {
  border-top: 0;
}
.ni-row {
  display: flex;
  align-items: center;
  gap: 9px;
  height: 40px;
  padding: 0 12px;
  border-top: 1px solid var(--border-subtle);
  min-width: 0;
  width: 100%;
  background: transparent;
  border-left: 0;
  border-right: 0;
  border-bottom: 0;
  font: inherit;
  color: inherit;
  text-align: left;
}
.ni-col .ni-row {
  padding-left: 0;
  padding-right: 0;
}
/* 分隔线两侧留内距（2026-11-10）：上面把两列行的左右 padding 都清零后，
   左列的值（"-85 dBm" 等）直接顶在分隔线上、右列的图标紧贴分隔线另一侧，
   看起来就是左值被"截断"、右栏内容挤在一起。只在双列布局下补内距，单列不缩进。 */
@media (min-width: 601px) {
  /* 内距加在**行内容**上而不是列上：行边框要横贯到中间那条竖分隔线，
     加在列上会让每行的上边框在分隔线前断掉 14px。 */
  .ni-col:not(.is-rhs) .ni-row {
    padding-right: 14px;
  }
  .ni-col.is-rhs .ni-row {
    padding-left: 14px;
  }
}
/* 右列是控制行：开关贴右，与左列参数值的右对齐落在同一条视觉轴上 */
.ni-switch {
  margin-left: auto;
}
.ni-col .ni-row:first-child {
  border-top: 1px solid var(--border-subtle);
}
.ni-head + .ni-grid .ni-col .ni-row:first-child {
  border-top: 1px solid var(--border-subtle);
}
.ni-link {
  cursor: pointer;
}
.ni-link:hover .ni-lab {
  color: var(--accent-color);
}
.ni-row:disabled {
  opacity: 0.55;
  cursor: not-allowed;
}
.ni-row:disabled:hover .ni-lab {
  color: var(--text-muted);
}
.ni-ic {
  flex: none;
  width: 20px;
  height: 20px;
  display: grid;
  place-items: center;
  border-radius: 5px;
  color: var(--text-muted);
  background: var(--surface-elevated, var(--card-bg));
}
.ni-ic > svg {
  width: 13px;
  height: 13px;
}
.ni-lab {
  /* 四个字（信号强度 / 接收质量）和三个字（信噪比）必须占同一列宽，
     否则进度条起点会因标签长度差一个字而左移一格。 */
  flex: 0 0 4em;
  width: 4em;
  white-space: nowrap;
  font-size: var(--font-sm);
  color: var(--text-muted);
}
.ni-track {
  flex: 1;
  min-width: 24px;
  max-width: 110px;
  height: 5px;
  border-radius: 3px;
  background: var(--border-subtle);
  overflow: hidden;
}
.ni-track > i {
  display: block;
  height: 100%;
  border-radius: 3px;
  background: var(--accent-color);
}
.ni-val {
  margin-left: auto;
  font-size: 12.5px;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
  color: var(--text-primary);
  white-space: nowrap;
}
.ni-val.muted {
  color: var(--text-muted);
  font-weight: 500;
}
.ni-chev {
  display: inline-flex;
  align-items: center;
  color: var(--text-muted);
  font-size: 15px;
  margin-left: -4px;
}
.ni-chev > svg {
  width: 14px;
  height: 14px;
}

/* 窄屏：两列拆成一列，控制行接在参数行之后 */
@media (max-width: 600px) {
  /* 窄屏堆叠：右侧控制列接在参数列下面，补一条上边框当分隔 */
  .ni-grid {
    grid-template-columns: 1fr;
  }
  .ni-col.is-rhs {
    border-left: 0;
    border-top: 1px solid var(--border-subtle);
    justify-content: flex-start;
    margin-top: 4px;
  }
}
</style>

<template>
  <GridCard title="网络信息">
    <!-- 上排：gauge(左,96px) + 标题/运营商/3 项开关(右)。
         拨号模式 n-select 移到 footer 与飞行/连接按钮同行，避免 4 列等分把 select 挤窄。 -->
    <div class="conn-horizontal">
      <div class="conn-gauge">
        <GaugeChart
          :value="rsrpGaugeValue"
          :max="100"
          :size="96"
          :stroke-width="8"
          :color="rsrpColor || 'auto'"
          :display-value="rsrpDisplay"
          label="RSRP"
          unit="dBm"
        />
      </div>

      <div class="conn-facts">
        <div class="conn-title-row">
          <span class="net-dot" :class="isConnected ? 'on' : 'off'"></span>
          <span class="conn-type">{{ connType }}</span>
          <span class="conn-state">{{ connState }}</span>
        </div>
        <div class="conn-operator">{{ operator }}</div>

        <!-- 3 项一行：移动数据 / WiFi 热点 / 数据漫游（拨号模式已移走） -->
        <div class="ctrl-bar">
          <div class="ctrl-item">
            <span class="ctrl-label">移动数据</span>
            <n-switch
              :value="!!mobileData"
              size="small"
              :rubber-band="false"
              :loading="mobileDataSaving"
              @update:value="emit('update:mobile-data', $event)"
            />
          </div>
          <div class="ctrl-item">
            <span class="ctrl-label">WiFi 热点</span>
            <n-switch
              :value="wifiEnabled"
              size="small"
              :rubber-band="false"
              @update:value="emit('toggle-wifi', $event)"
            />
          </div>
          <div class="ctrl-item">
            <span class="ctrl-label">数据漫游</span>
            <n-switch
              :value="roamingEnabled"
              size="small"
              :rubber-band="false"
              :loading="roamingSaving"
              @update:value="emit('update:roaming', $event)"
            />
          </div>
        </div>
      </div>
    </div>

    <!-- 中段：3 个信号 metric 一行横排（margin-top 收敛 16→10，与上方紧凑） -->
    <div class="metric-strip">
      <div v-for="m in signalMetrics" :key="m.key" class="metric-cell sub-panel">
        <div class="metric-head">
          <span class="metric-dot" :style="{ background: m.color || 'var(--text-muted)' }"></span>
          <span class="metric-key">{{ m.key }}</span>
        </div>
        <div class="metric-value-row">
          <span class="metric-value">{{ m.display }}</span>
          <span class="metric-unit">{{ m.unit }}</span>
        </div>
        <div class="metric-label">{{ m.label }}</div>
      </div>
    </div>

    <template #footer>
      <!-- GridCard.footer 默认 flex-end；这里包一个 wrapper 让左 select / 右 按钮空间分布 -->
      <div class="footer-row">
        <div class="footer-conn">
          <n-select
            :value="connectionMode"
            :options="connModeOptions"
            size="small"
            :loading="connModeSaving"
            class="footer-mode-select"
            @update:value="emit('update:connection-mode', $event)"
          />
        </div>
        <div class="footer-actions">
          <n-popconfirm @positive-click="emit('airplane-on')">
            <template #trigger><n-button size="small">开启飞行模式</n-button></template>
            开启飞行模式会切断蜂窝网络与当前拨号连接，确认继续？
          </n-popconfirm>
          <n-button size="small" @click="emit('airplane-off')">关闭飞行模式</n-button>
          <n-button v-if="!isConnected" size="small" type="primary" @click="emit('ppp-connect')">拨号连接</n-button>
          <n-popconfirm v-else @positive-click="emit('ppp-disconnect')">
            <template #trigger><n-button size="small">断开连接</n-button></template>
            断开后设备将失去蜂窝上网，确认继续？
          </n-popconfirm>
        </div>
      </div>
    </template>
  </GridCard>
</template>

<script setup lang="ts">
import { computed } from 'vue';
import GridCard from '@/components/GridCard.vue';
import GaugeChart from '@/components/GaugeChart.vue';
import { networkTypeWithBand, signalColor } from '@/composables/utils';

const props = defineProps<{
  netStatus: any;
  signal: any;
  wifiEnabled: boolean;
  mobileDataSaving: boolean;
  roamingEnabled: boolean;
  roamingSaving: boolean;
  connectionMode: string;
  connModeOptions: { label: string; value: string }[];
  connModeSaving: boolean;
}>();

const emit = defineEmits<{
  'update:mobile-data': [boolean];
  'toggle-wifi': [boolean];
  'update:roaming': [boolean];
  'update:connection-mode': [string];
  'airplane-on': [];
  'airplane-off': [];
  'ppp-connect': [];
  'ppp-disconnect': [];
}>();

const isConnected = computed(() => !!props.netStatus?.network?.is_connected);
const mobileData = computed(() => !!props.netStatus?.mobile_data);
const connType = computed(() => networkTypeWithBand(props.netStatus?.network_type || '', props.signal));
const connState = computed(() => (isConnected.value ? '已连接' : '未连接'));
const operator = computed(() => props.netStatus?.operator || '--');

// RSRP 仪表盘：归一化 -140~-44 dBm 到 0-100
const rsrpGaugeValue = computed(() => {
  const r = props.signal?.rsrp;
  if (r == null || r === 0) return 0;
  return Math.max(0, Math.min(100, ((r + 140) / 96) * 100));
});
const rsrpDisplay = computed(() => (props.signal?.rsrp ? `${props.signal.rsrp}` : '--'));
const rsrpColor = computed(() => (props.signal?.rsrp ? signalColor(props.signal.rsrp) : undefined));

// 状态点只对 dBm 量纲着色；SINR/RSRQ 无既有阈值，留中性色只呈现数值。
const signalMetrics = computed(() => {
  const s = props.signal;
  return [
    { key: 'SINR', label: '信噪比', unit: 'dB', display: s?.sinr != null ? String(s.sinr) : '--', color: '' },
    { key: 'RSRQ', label: '参考信号接收质量', unit: 'dB', display: s?.rsrq != null ? String(s.rsrq) : '--', color: '' },
    {
      key: 'RSSI',
      label: '接收信号强度',
      unit: 'dBm',
      display: s?.rssi ? String(s.rssi) : '--',
      color: s?.rssi ? signalColor(s.rssi) : '',
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
  font-size: 12px;
  color: var(--text-secondary);
}
.conn-operator {
  font-size: 13px;
  color: var(--text-secondary);
}

/* ── 控件条：3 项一行（拨号模式已移走） ── */
.ctrl-bar {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 8px 16px;
  margin-top: 2px;
}
.ctrl-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  min-height: 28px;
  min-width: 0;
}
.ctrl-label {
  font-size: 13px;
  color: var(--text-secondary);
  white-space: nowrap;
}

/* ── 中段：3 个 metric 一行横排 ── */
.metric-strip {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 10px;
  margin-top: 10px;
}
/* 描边/内距/圆角/底色走 main.css 的全局 .sub-panel */
.metric-cell {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.metric-head {
  display: flex;
  align-items: center;
  gap: 6px;
}
.metric-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  flex-shrink: 0;
}
.metric-key {
  font-size: 11px;
  font-weight: 600;
  color: var(--text-secondary);
  letter-spacing: 0.04em;
}
.metric-value-row {
  display: flex;
  align-items: baseline;
  gap: 4px;
}
.metric-value {
  font-size: 22px;
  font-weight: 700;
  color: var(--text-primary);
  font-variant-numeric: tabular-nums;
}
.metric-unit {
  font-size: 12px;
  color: var(--text-secondary);
}
.metric-label {
  font-size: 11px;
  color: var(--text-muted);
}

/* ── footer 改造：拨号模式 select 在左、3 按钮在右 ── */
.footer-row {
  display: flex;
  width: 100%;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}
.footer-conn {
  flex: 1;
  min-width: 0;
  display: flex;
  align-items: center;
}
.footer-mode-select {
  /* select 宽度自适应,但不窄于 140px / 不宽于 200px,
     保留视觉舒适区,与右侧 3 按钮平衡 */
  min-width: 140px;
  max-width: 200px;
}
.footer-actions {
  display: flex;
  gap: 8px;
  flex-shrink: 0;
}

/* ── 窄屏回退 ── */
@media (max-width: 768px) {
  .conn-horizontal {
    grid-template-columns: 1fr;
    justify-items: center;
  }
  .ctrl-bar {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
  .metric-strip {
    grid-template-columns: 1fr;
  }
  .footer-conn {
    width: 100%;
  }
  .footer-mode-select {
    width: 100%;
    max-width: 100%;
  }
}
</style>

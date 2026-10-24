<template>
  <GridCard title="网络信息">
    <!-- 上排：gauge(左,96px) + 标题/运营商/2 项开关(右)。
         拨号模式下拉已按用户裁决删除（2026-10-10）。
         WiFi 热点开关已移到下方「WiFi 热点」卡的状态行，避免同一状态出现两次。 -->
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

        <!-- 2 项一行：移动数据 / 数据漫游。WiFi 热点开关归下方 WiFi 卡。 -->
        <div class="ctrl-bar">
          <div class="config-item switch-item">
            <span class="config-label">移动数据</span>
            <n-switch
              :value="!!mobileData"
              size="small"
              :rubber-band="false"
              :loading="mobileDataSaving"
              @update:value="emit('update:mobile-data', $event)"
            />
          </div>
          <div class="config-item switch-item">
            <span class="config-label">数据漫游</span>
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

    <!-- 中段：3 个信号指标一行横排；只保留一个中文标签，英文键放在 title 里 -->
    <div class="metric-strip">
      <div v-for="m in signalMetrics" :key="m.key" class="metric-cell" :title="`${m.key} · ${m.label}`">
        <div class="metric-label">{{ m.label }}</div>
        <div class="metric-value-row">
          <span class="metric-value">{{ m.display }}</span>
          <span class="metric-unit">{{ m.unit }}</span>
        </div>
      </div>
    </div>

    <template #footer>
      <!--
        2026-10-10 用户裁决：删掉页脚那个连接模式下拉（自动拨号 / 手动拨号）。
        **只删 UI**：`/api/device/settings` 里的 `dial_mode` 与 composable 的下发逻辑都保留
        （用户裁决 A）。2026-10-21 补齐：已无 UI 的三个 props 与 `update:connection-mode`
        事件也一并从本组件删除，父组件不再透传。
        footer 现在只剩这一组按钮，GridCard 的 footer 自带 flex-end + wrap，
        原先那层 `.footer-row`(space-between) 会把它推到左边，已去掉。
      -->
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
  mobileDataSaving: boolean;
  roamingEnabled: boolean;
  roamingSaving: boolean;
}>();

const emit = defineEmits<{
  'update:mobile-data': [boolean];
  'update:roaming': [boolean];
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

// RSRP 仪表盘：归一化 -140~-44 dBm 到 0-100。
// 判空一律 `!= null`：0 是合法数值（家规第 5 条），不能当"没有值"。
const rsrpGaugeValue = computed(() => {
  const r = props.signal?.rsrp;
  if (r == null) return 0;
  return Math.max(0, Math.min(100, ((r + 140) / 96) * 100));
});
const rsrpDisplay = computed(() => (props.signal?.rsrp != null ? `${props.signal.rsrp}` : '--'));
const rsrpColor = computed(() => (props.signal?.rsrp != null ? signalColor(props.signal.rsrp) : undefined));

// 指标只保留中文标签；英文键（SINR/RSRQ/RSSI）放 title，避免同一行出现两次说明。
const signalMetrics = computed(() => {
  const s = props.signal;
  return [
    { key: 'SINR', label: '信噪比', unit: 'dB', display: s?.sinr != null ? String(s.sinr) : '--' },
    { key: 'RSRQ', label: '接收质量', unit: 'dB', display: s?.rsrq != null ? String(s.rsrq) : '--' },
    { key: 'RSSI', label: '信号强度', unit: 'dBm', display: s?.rssi != null ? String(s.rssi) : '--' },
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
</style>

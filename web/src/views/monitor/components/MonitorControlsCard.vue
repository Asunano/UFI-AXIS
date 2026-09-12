<template>
  <GridCard title="系统监控">
    <div class="controls-bar">
      <n-button-group>
        <n-button
          v-for="r in TIME_RANGES"
          :key="r.hours"
          :type="hours === r.hours ? 'primary' : 'default'"
          size="small"
          @click="emit('update:hours', r.hours)"
          >{{ r.label }}</n-button
        >
      </n-button-group>
      <n-button size="small" :loading="loading" @click="emit('refresh')">刷新</n-button>
      <n-button size="small" quaternary @click="showSettings = !showSettings">
        {{ showSettings ? '收起设置' : '监控设置' }}
      </n-button>
    </div>

    <!--
      监控个性化设置：真源在 core（GET/PUT /api/monitor/preferences），与 app 共享同一份。
      即改即存；未在此展示的 exportZip 是 app 专有项，
      web 只做字段级 PUT，不会把它们覆盖掉。
    -->
    <div v-if="showSettings" class="monitor-prefs">
      <div class="settings-row">
        <span class="settings-label">默认时间范围</span>
        <n-button-group>
          <n-button
            v-for="r in TIME_RANGES"
            :key="`def-${r.hours}`"
            :type="prefs.defaultHours === r.hours ? 'primary' : 'default'"
            size="tiny"
            @click="emit('save', { defaultHours: r.hours })"
            >{{ r.label }}</n-button
          >
        </n-button-group>
      </div>

      <div class="settings-row">
        <span class="settings-label">自动刷新间隔</span>
        <n-button-group>
          <n-button
            v-for="s in REFRESH_CHOICES"
            :key="`ri-${s}`"
            :type="prefs.refreshIntervalSec === s ? 'primary' : 'default'"
            size="tiny"
            @click="emit('save', { refreshIntervalSec: s })"
            >{{ s }}s</n-button
          >
        </n-button-group>
      </div>

      <div class="settings-row">
        <span class="settings-label">显示指标</span>
        <div class="settings-chips">
          <n-button
            v-for="m in METRIC_CHOICES"
            :key="m.key"
            :type="prefs.enabledTypes.includes(m.key) ? 'primary' : 'default'"
            size="tiny"
            @click="toggleMetric(m.key)"
            >{{ m.label }}</n-button
          >
        </div>
      </div>

      <!--
        采集总开关（POST /api/monitor/control）。
        core 侧真源是 AppSettings.backgroundServiceEnabled，与 POST /api/service/start|stop
        是同一个开关的两个入口；GET /api/monitor/preferences 的响应里**没有**任何能表达
        采集状态的字段（core MonitorRoutes 注释明确「采集总开关 collectEnabled 不在本模型内」），
        所以无状态可回读 —— 不凭空假设默认值，改用两个明确动作，同 NetworkView 飞行模式的惯例。
      -->
      <div class="settings-row">
        <span class="settings-label">数据采集</span>
        <n-popconfirm @positive-click="setCollect(true)">
          <template #trigger>
            <n-button size="tiny" type="primary" :loading="collectSaving">开启采集</n-button>
          </template>
          开启后 core 恢复写入监控历史，图表继续增长。确认继续？
        </n-popconfirm>
        <n-popconfirm @positive-click="setCollect(false)">
          <template #trigger>
            <n-button size="tiny" :loading="collectSaving">停止采集</n-button>
          </template>
          关闭后 core 停止写入监控历史（已有数据不删除，图表将不再增长）。确认继续？
        </n-popconfirm>
        <span class="settings-hint">
          关闭后 core 停止写入监控历史：已有数据不会删除，图表将不再增长； core 未提供状态读取接口，此处不展示当前状态。
        </span>
      </div>

      <div class="settings-row">
        <span class="settings-label">Y 轴固定</span>
        <n-switch
          :value="prefs.fixedYAxis"
          size="small"
          @update:value="(v: boolean) => emit('save', { fixedYAxis: v })"
        />
        <span class="settings-hint">关闭 = 按数据自适应缩放</span>
      </div>

      <div class="settings-row">
        <span class="settings-label">填充透明度</span>
        <n-slider
          :value="prefs.fillAlpha"
          :min="0"
          :max="1"
          :step="0.05"
          style="max-width: 220px"
          @update:value="(v: number) => emit('save', { fillAlpha: Number(v.toFixed(2)) })"
        />
        <span class="settings-hint">{{ prefs.fillAlpha.toFixed(2) }}</span>
      </div>
    </div>
  </GridCard>
</template>

<script setup lang="ts">
/**
 * 时间范围 + 监控偏好设置卡片。
 *
 * 偏好不自持：prefs 由父组件回读并下发，改动一律 emit('save', patch) 交给父组件做字段级 PUT
 * —— 图表的 fillAlpha / fixedYAxis / enabledTypes 用的是同一份 prefs，
 * 拆成两份状态必然出现「设置里改了、图没跟着变」。
 *
 * 采集总开关是例外，留在本组件：它没有可回读的状态，只是两个一次性动作，
 * 与父组件的任何状态都不耦合。
 */
import { ref } from 'vue';
import { useMessage } from 'naive-ui';
import { useCancellableApi } from '@/composables/useCancellableApi';
import { Endpoints } from '@/api/contract';
import GridCard from '@/components/GridCard.vue';
import { METRIC_CHOICES, METRIC_KEYS, REFRESH_CHOICES, TIME_RANGES, type MonitorPrefs } from '../monitorShared';

const props = defineProps<{
  prefs: MonitorPrefs;
  hours: number;
  /** 任一图表在取数中 —— 只用于刷新按钮的 loading */
  loading: boolean;
}>();

const emit = defineEmits<{
  (e: 'update:hours', hours: number): void;
  (e: 'refresh'): void;
  (e: 'save', patch: Partial<MonitorPrefs>): void;
}>();

const message = useMessage();
const api = useCancellableApi();

const showSettings = ref(false);

function toggleMetric(key: string) {
  const cur = props.prefs.enabledTypes;
  const next = cur.includes(key) ? cur.filter((k) => k !== key) : [...cur, key];
  // enabledTypes 是完整集合语义：改单个指标也要传整份集合，且按 METRIC_KEYS 的顺序
  emit('save', { enabledTypes: METRIC_KEYS.filter((k) => next.includes(k)) });
}

const collectSaving = ref(false);

async function setCollect(enabled: boolean) {
  collectSaving.value = true;
  try {
    // enabled 必须是严格的 JSON boolean：core 用 jsonPrimitive.booleanOrNull 取值，
    // 字符串 "true" 会被当成缺参直接 400（error: enabled (boolean) required）。
    const { data } = await api.post(Endpoints.monitor.control, { enabled });
    // 成功信封是 { ok: true, enabled } —— 注意是 ok 不是 success（PUT /preferences 才是 success）
    if (data?.ok === false) {
      message.error(data?.message || data?.error || '操作失败');
      return;
    }
    const eff = typeof data?.enabled === 'boolean' ? data.enabled : enabled;
    message.success(eff ? '已开启数据采集' : '已停止数据采集');
  } catch (e: any) {
    const d = e?.response?.data;
    message.error(d?.message || d?.error || '操作失败');
  } finally {
    collectSaving.value = false;
  }
}
</script>

<style scoped>
.controls-bar {
  display: flex;
  align-items: center;
  gap: 12px;
}

/* 原名 .settings-panel —— 与 settings/panels 的同名栅格类语义完全不同（那边是 auto-fit 卡片栅格，
   这里只是折叠出来的偏好设置竖列）。该类已提升为 main.css 的全局类，故此处改名避免撞车。 */
.monitor-prefs {
  display: flex;
  flex-direction: column;
  gap: 10px;
  margin-top: 12px;
  padding-top: 12px;
  border-top: 1px solid var(--border-subtle);
}

.settings-row {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
}

.settings-label {
  min-width: 96px;
  font-size: 13px;
  color: var(--text-secondary);
}

.settings-chips {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.settings-hint {
  font-size: 12px;
  color: var(--text-muted);
}
</style>

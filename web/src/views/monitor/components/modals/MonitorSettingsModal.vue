<template>
  <n-modal
    :show="show"
    preset="card"
    title="监控设置"
    class="modal-fit"
    style="--modal-w: 560px; --modal-h: 720px"
    :bordered="false"
    @update:show="emit('update:show', $event)"
  >
    <!--
      监控个性化设置：真源在 core（GET/PUT /api/monitor/preferences），与 app 共享同一份。
      即改即存；未在此展示的 exportZip 是 app 专有项，
      web 只做字段级 PUT，不会把它们覆盖掉。
    -->
    <div class="monitor-prefs">
      <!--
        偏好回读失败时不能把本地默认值当真值展示：用户会以为设备就是这个配置，
        照着改一遍反而把 core 的真实设置覆盖掉。这里明说失败并给重试。
      -->
      <div v-if="prefsFailed" class="prefs-failed">
        <span class="prefs-failed-text">监控设置读取失败，下方显示的是本地默认值</span>
        <n-button size="tiny" quaternary type="primary" @click="emit('reload-prefs')">重试</n-button>
      </div>

      <div class="config-item pref-row">
        <span class="config-label">默认时间范围</span>
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

      <div class="config-item pref-row">
        <span class="config-label">自动刷新间隔</span>
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

      <!--
        智能采集节拍（SmartLoop，2026-10-14 接线）。
        core 里这四个键（`smart_loop_*`）早就定义好了读写函数：
        DataScheduler 的两条自适应循环只能跑默认档位，其中"关掉就退回固定间隔"这个
        故障回退开关现场按不到。放在「自动刷新间隔」旁边 —— 两者回答的是同一个问题
        （"它多久采一次"），分到别处只会让人以为要多调一处。
        开关排在这一组第一位：它是回退手段，不是调优项。
      -->
      <ToggleRow
        label="智能采集节拍"
        description="按流量自动加快/放慢采集。关闭后所有采集循环退回固定间隔（更费电但更保守）——出问题时的一键回退开关。"
        :model-value="prefs.smartLoopEnabled"
        @update:model-value="(v: boolean) => emit('save', { smartLoopEnabled: v })"
      />

      <div class="config-item pref-row">
        <span class="config-label">零流量阈值</span>
        <n-select
          :value="prefs.smartLoopIdleBps"
          size="tiny"
          class="pref-select"
          :options="smartLoopThresholdOptions('smartLoopIdleBps', prefs)"
          @update:value="(v: number) => emit('save', { smartLoopIdleBps: v })"
        />
        <span class="pref-hint">低于它按零流量放慢（省电）</span>
      </div>

      <div class="config-item pref-row">
        <span class="config-label">中流量阈值</span>
        <n-select
          :value="prefs.smartLoopActiveBps"
          size="tiny"
          class="pref-select"
          :options="smartLoopThresholdOptions('smartLoopActiveBps', prefs)"
          @update:value="(v: number) => emit('save', { smartLoopActiveBps: v })"
        />
        <span class="pref-hint">超过它按基准节拍采集</span>
      </div>

      <div class="config-item pref-row">
        <span class="config-label">大流量阈值</span>
        <n-select
          :value="prefs.smartLoopHeavyBps"
          size="tiny"
          class="pref-select"
          :options="smartLoopThresholdOptions('smartLoopHeavyBps', prefs)"
          @update:value="(v: number) => emit('save', { smartLoopHeavyBps: v })"
        />
        <span class="pref-hint">超过它进最快档，降低数据延迟</span>
      </div>

      <div class="config-item pref-row">
        <span class="config-label">显示指标</span>
        <div class="pref-chips">
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
      <div class="config-item pref-row">
        <span class="config-label">数据采集</span>
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
        <span class="pref-hint">
          关闭后 core 停止写入监控历史：已有数据不会删除，图表将不再增长； core 未提供状态读取接口，此处不展示当前状态。
        </span>
      </div>

      <!-- 告警/事件检查间隔（2026-10-07 审计修复：core+app 早有，web 补 UI 对齐双端口径） -->
      <div class="config-item pref-row">
        <span class="config-label">套餐限额检查</span>
        <n-select
          :value="prefs.trafficLimitCheckSec"
          size="tiny"
          class="pref-select pref-select--narrow"
          :options="[
            { label: '1 分钟', value: 60 },
            { label: '5 分钟', value: 300 },
            { label: '15 分钟', value: 900 },
            { label: '30 分钟', value: 1800 },
            { label: '1 小时', value: 3600 },
          ]"
          @update:value="(v: number) => emit('save', { trafficLimitCheckSec: v })"
        />
        <span class="pref-hint">用量百分比预警的检查频率</span>
      </div>
      <div class="config-item pref-row">
        <span class="config-label">设备事件检查</span>
        <n-select
          :value="prefs.deviceEventCheckSec"
          size="tiny"
          class="pref-select pref-select--narrow"
          :options="[
            { label: '15 秒', value: 15 },
            { label: '30 秒', value: 30 },
            { label: '1 分钟', value: 60 },
            { label: '5 分钟', value: 300 },
            { label: '10 分钟', value: 600 },
          ]"
          @update:value="(v: number) => emit('save', { deviceEventCheckSec: v })"
        />
        <span class="pref-hint">接入/离开提醒的比对频率</span>
      </div>

      <div class="config-item pref-row">
        <span class="config-label">Y 轴固定</span>
        <n-switch
          :value="prefs.fixedYAxis"
          size="small"
          @update:value="(v: boolean) => emit('save', { fixedYAxis: v })"
        />
        <span class="pref-hint">关闭 = 按数据自适应缩放</span>
      </div>

      <div class="config-item pref-row">
        <span class="config-label">填充透明度</span>
        <n-slider
          :value="prefs.fillAlpha"
          :min="0"
          :max="1"
          :step="0.05"
          class="pref-slider"
          @update:value="(v: number) => emit('save', { fillAlpha: Number(v.toFixed(2)) })"
        />
        <span class="pref-hint">{{ prefs.fillAlpha.toFixed(2) }}</span>
      </div>
    </div>

    <template #footer>
      <div class="modal-footer">
        <n-button size="small" @click="emit('update:show', false)">关闭</n-button>
      </div>
    </template>
  </n-modal>
</template>

<script setup lang="ts">
/**
 * 监控设置弹窗（2026-10-22 从 MonitorControlsCard 的内嵌展开区搬来）。
 *
 * 为什么搬：原先点「监控设置」会在控件条下方展开十来行设置，展开过就一直占着半屏，
 * 把图表挤下去；而设置是「调一次就走」的动作，与常驻的图表不是同一类信息。
 *
 * 状态仍归 MonitorView（prefs / prefsFailed / savePrefs / reloadPrefs），本弹窗只渲染 + emit：
 * 图表的 fillAlpha / fixedYAxis / enabledTypes 用的是同一份 prefs，
 * 拆成两份状态必然出现「设置里改了、图没跟着变」。即改即存（字段级 PUT），没有保存按钮，
 * 关闭弹窗不会回滚任何已生效的设置。
 *
 * 采集总开关是例外，逻辑留在本组件：core 侧没有可回读的状态字段
 * （GET /api/monitor/preferences 不含 collectEnabled），它只是两个一次性动作，
 * 与父组件的任何状态都不耦合。
 *
 * 智能节拍三个阈值用**下拉档位**而不是输入框，正是为了不让每次按键都变成一个请求 ——
 * 本仓的约定是"攒够一次用户动作再发一次"（见 `MediaScanDirsSettings` 的文件头）。
 *
 * 宽高走 `.modal-fit`（`--modal-w` / `--modal-h`）：矮屏时内容区自己滚、标题与「关闭」固定。
 */
import { ref } from 'vue';
import { useMessage } from 'naive-ui';
import { useCancellableApi } from '@/composables/useCancellableApi';
import { Endpoints } from '@/api/contract';
import ToggleRow from '@/components/ToggleRow.vue';
import {
  METRIC_CHOICES,
  METRIC_KEYS,
  REFRESH_CHOICES,
  TIME_RANGES,
  smartLoopThresholdOptions,
  type MonitorPrefs,
} from '../../monitorShared';

const props = defineProps<{
  show: boolean;
  prefs: MonitorPrefs;
  /** 偏好回读失败：此时 prefs 是本地默认值，不是设备真值 */
  prefsFailed?: boolean;
}>();

const emit = defineEmits<{
  (e: 'update:show', show: boolean): void;
  (e: 'save', patch: Partial<MonitorPrefs>): void;
  (e: 'reload-prefs'): void;
}>();

const message = useMessage();
const api = useCancellableApi();

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
/* 偏好设置竖列。类名沿用 `.monitor-prefs`（main.css 里有一条关于它不与 `.settings-panel`
   撞名的说明），弹窗壳已经提供了外边距与滚动，所以这里不再画分隔线、不再加 margin-top。 */
.monitor-prefs {
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
}

.prefs-failed {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: var(--space-2);
  padding: var(--space-1) var(--space-2);
  border-radius: var(--radius-sm);
  background: var(--error-light);
}

.prefs-failed-text {
  font-size: var(--font-sm);
  color: var(--error);
}

/* 偏好行借用全局 `.config-item` / `.config-label`（与设置页的两列表单同源），
   只覆盖排列方向：这里每项是「标签 + 控件 + 说明」同排的整行，不是上下叠的格子。
   对应全局注释里的「整行设置项」版本，而不是 `.switch-item` 那种两列栅格单元格。 */
.pref-row {
  flex-direction: row;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--space-2);
}

/* 标签列宽对齐：`.config-label` 只管字号与颜色（与设置页一致），
   96px 是让各行控件左边缘对齐的列宽，属本弹窗局部版式。 */
.monitor-prefs .config-label {
  min-width: 96px;
}

.pref-select {
  width: 110px;
}

.pref-select--narrow {
  width: 100px;
}

.pref-slider {
  max-width: 220px;
}

.pref-chips {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-1);
}

.pref-hint {
  font-size: var(--font-sm);
  color: var(--text-muted);
}

.modal-footer {
  display: flex;
  justify-content: flex-end;
  gap: var(--space-2);
}

@media (max-width: 768px) {
  /* 96px 的标签下限在 360px 弹窗里会把「开启采集/停止采集 + 长提示」压到很窄，
     提示折成 5 行。窄屏改成标签独占一行。 */
  .monitor-prefs .config-label {
    min-width: 100%;
  }
}
</style>

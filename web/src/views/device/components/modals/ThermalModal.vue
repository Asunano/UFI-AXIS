<template>
  <n-modal
    v-model:show="show"
    class="modal-fit"
    preset="card"
    title="温度传感器"
    style="--modal-w: 520px; --modal-h: 600px"
  >
    <n-spin :show="loading">
      <div v-if="zones.length" class="thermal-grid">
        <div v-for="zone in zones" :key="zone.key" class="thermal-item">
          <span class="thermal-name">
            {{ zone.label }}
            <span v-if="zone.raw" class="thermal-raw">{{ zone.raw }}</span>
          </span>
          <!-- 缺值的传感器渲染 `--` 且不着色（口径见 useThermal.ts：0°C 是合法读数，
               负数才是 core 的「读到了但无效」哨兵） -->
          <span class="thermal-temp" :style="zone.temp != null ? { color: thermalColor(zone.temp) } : undefined">
            {{ zone.temp != null ? formatThermalTemp(zone.temp) : '--' }}
          </span>
        </div>
      </div>
      <!-- 三态齐备：加载中由上面的 n-spin 表示，这里区分「读取失败（可重试）」与「真的没有传感器」 -->
      <div v-else-if="failed" class="client-empty">
        温度读取失败
        <n-button size="tiny" quaternary @click="loadThermal">重试</n-button>
      </div>
      <!-- 首次读取期间不要先闪一句「没有传感器」 -->
      <div v-else-if="loading" class="client-empty">正在读取温度…</div>
      <div v-else class="client-empty">设备没有返回任何温度传感器</div>
    </n-spin>
    <template #footer>
      <div class="modal-footer">
        <n-button size="small" @click="show = false">关闭</n-button>
        <n-button size="small" type="primary" :loading="loading" @click="loadThermal">刷新</n-button>
      </div>
    </template>
  </n-modal>
</template>

<script setup lang="ts">
/**
 * 温度传感器详情弹窗（2026-10-14 从 `OverviewTab` 的右列整卡搬进弹窗）。
 *
 * 为什么搬：`#/device` 改成「单页 + 入口瓦片 + 弹窗」之后，传感器条目数不定的这张卡
 * 放在页面上会把整页高度拉得不可预期（原注释："温度传感器条目数不定"），
 * 而它平时只需要一个"最高多少度"的摘要。摘要留在页面上，明细进弹窗。
 *
 * 轮询只在弹窗**打开期间**跑：`useInterval` 传 `autoStart: false`，靠 watch(show) 起停 ——
 * 关着弹窗还每 10s 打一次 `/api/device/thermal` 纯属浪费热点流量。
 *
 * 名称映射 / 缺值口径 / 三档颜色都从 `useThermal.ts` 取：摘要卡与这里必须是同一份，
 * 否则同一颗传感器会在两处显示不同的名字与颜色（见该文件头）。
 */
import { ref, computed, watch } from 'vue';
import { useInterval } from '@/composables/useRealtime';
import { useCancellableApi } from '@/composables/useCancellableApi';
import { formatThermalTemp, normalizeThermalZones, thermalColor, type ThermalZone } from '../../useThermal';

const props = defineProps<{ show: boolean }>();
const emit = defineEmits<{ 'update:show': [boolean] }>();

const show = computed({
  get: () => props.show,
  set: (v) => emit('update:show', v),
});

const api = useCancellableApi();

const loading = ref(false);
/** 最近一次读取是否失败 —— 失败时保留上一次的结果，但要能说明「这次没读到」。 */
const failed = ref(false);
const zones = ref<ThermalZone[]>([]);

async function loadThermal() {
  loading.value = true;
  try {
    const { data } = await api.get('/api/device/thermal');
    zones.value = normalizeThermalZones(data?.zones);
    failed.value = false;
  } catch {
    // 保留上一次的结果，但把「这次没读到」标出来：静默失败会让用户把旧读数当新读数。
    failed.value = true;
  } finally {
    loading.value = false;
  }
}

const poll = useInterval(loadThermal, 10_000, { autoStart: false });
watch(
  () => props.show,
  (v) => {
    if (v) {
      loadThermal();
      poll.start();
    } else {
      poll.stop();
    }
  },
  { immediate: true }
);
</script>

<style scoped>
.thermal-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(180px, 1fr));
  gap: var(--space-2);
}
.thermal-item {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: var(--space-2);
  padding: var(--space-2) var(--space-3);
  background: var(--page-bg);
  border-radius: var(--radius-sm);
}
/* 名称 + 原始标识压成一行，超长时整体省略号截断（截断的是整行，不是两个碎片） */
.thermal-name {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: var(--font-sm);
  color: var(--text-secondary);
}
/* 原始标识（如 `cpu-0-0-usr`）：中文名只是「这一族」，同族多颗传感器要靠它区分 */
.thermal-raw {
  margin-left: var(--space-1);
  font-size: var(--font-xs);
  color: var(--text-muted);
}
.thermal-temp {
  flex-shrink: 0;
  font-size: var(--font-md);
  font-weight: 600;
  font-variant-numeric: tabular-nums;
}
.modal-footer {
  display: flex;
  justify-content: flex-end;
  gap: var(--space-2);
}
@media (max-width: 768px) {
  .thermal-grid {
    grid-template-columns: 1fr;
  }
}
</style>

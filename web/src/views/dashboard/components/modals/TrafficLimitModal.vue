<template>
  <n-modal
    :show="show"
    preset="card"
    title="流量限额设置"
    style="width: 440px; max-width: calc(100vw - 32px)"
    @update:show="(v: boolean) => emit('update:show', v)"
  >
    <div class="modal-form-col">
      <div class="form-item">
        <n-switch v-model:value="trafficForm.enabled" />
        <span class="form-inline-label">启用流量限额</span>
      </div>
      <div class="form-item">
        <label>限额大小</label>
        <n-input-number v-model:value="trafficForm.limit_size" :min="1" size="small" style="width: 100%" />
      </div>
      <div class="form-item">
        <label>限额单位</label>
        <n-select
          v-model:value="trafficForm.limit_unit"
          :options="[
            { label: 'MB', value: 'MB' },
            { label: 'GB', value: 'GB' },
            { label: 'TB', value: 'TB' },
          ]"
          size="small"
        />
      </div>
      <div class="form-item">
        <label>提醒百分比</label>
        <n-input-number
          v-model:value="trafficForm.alert_percent"
          :min="1"
          :max="100"
          size="small"
          style="width: 100%"
        />
      </div>
      <div class="form-item">
        <n-switch v-model:value="trafficForm.auto_clear" />
        <span class="form-inline-label">每月自动清除</span>
      </div>
      <div class="form-item">
        <label>清除日期</label>
        <n-input-number v-model:value="trafficForm.clear_date" :min="1" :max="31" size="small" style="width: 100%" />
      </div>
    </div>
    <template #footer>
      <n-space justify="end">
        <n-button size="small" @click="emit('update:show', false)">取消</n-button>
        <n-button size="small" type="primary" :loading="saving" @click="onSave">保存</n-button>
      </n-space>
    </template>
  </n-modal>
</template>

<script setup lang="ts">
import { ref, reactive, watch } from 'vue';

const props = defineProps<{ show: boolean; trafficLimit: Record<string, any> }>();
const emit = defineEmits<{ 'update:show': [boolean]; save: [form: TrafficForm] }>();

const saving = ref(false);

// goform 限额单位靠乘数表达：1=MB、1024=GB、1048576=TB
const LIMIT_UNIT_MULTIPLIER: Record<string, string> = { MB: '1', GB: '1024', TB: '1048576' };

const trafficForm = reactive({
  enabled: false,
  limit_size: 10,
  limit_unit: 'GB' as string,
  alert_percent: 80,
  auto_clear: false,
  clear_date: 1,
});

interface TrafficForm {
  enabled: boolean;
  limit_size: number;
  limit_unit: string;
  alert_percent: number;
  auto_clear: boolean;
  clear_date: number;
}

// 打开时回填（用 core 已拆好的 limit_value / limit_unit_display，响应里没有设备侧复合串）
watch(
  () => props.show,
  (v) => {
    if (!v) return;
    const t = props.trafficLimit || {};
    trafficForm.enabled = !!t.enabled;
    trafficForm.limit_size = Number(t.limit_value) || 10;
    trafficForm.limit_unit = LIMIT_UNIT_MULTIPLIER[String(t.limit_unit_display || '').toUpperCase()]
      ? String(t.limit_unit_display).toUpperCase()
      : 'GB';
    trafficForm.alert_percent = Number(t.alert_percent) || 80;
    trafficForm.auto_clear = !!t.auto_clear;
    trafficForm.clear_date = Number(t.clear_date) || 1;
  },
  { immediate: true }
);

function onSave() {
  saving.value = true;
  try {
    emit('save', { ...trafficForm });
    emit('update:show', false);
  } finally {
    saving.value = false;
  }
}
</script>

<style scoped>
.modal-form-col {
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 4px 0;
}
.form-item {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.form-item label {
  font-size: 12px;
  color: var(--text-secondary);
  font-weight: 500;
}
.form-inline-label {
  font-size: 13px;
  color: var(--text-primary);
  margin-left: 8px;
}
</style>

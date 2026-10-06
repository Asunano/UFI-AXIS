<template>
  <n-modal
    :show="show"
    preset="card"
    title="流量限额设置"
    style="width: 440px; max-width: calc(100vw - 32px)"
    @update:show="(v: boolean) => emit('update:show', v)"
  >
    <div class="modal-form-col">
      <!-- 套餐模式（2026-10-07 到量/到期融合）：monthly=循环月包（现状语义）；
           fixed=累计有效期包（如 100G×3 个月），用量走 core traffic_hourly 累计，
           到期靠 core 日历提醒（goform 无套餐概念，档案存 core AppSettings） -->
      <div class="form-item">
        <label>套餐模式</label>
        <n-radio-group v-model:value="planMode">
          <n-radio-button value="monthly">每月循环</n-radio-button>
          <n-radio-button value="fixed">固定有效期</n-radio-button>
        </n-radio-group>
      </div>

      <template v-if="planMode === 'fixed'">
        <div class="form-item">
          <label>生效日期</label>
          <n-input v-model:value="planForm.start_date" placeholder="2026-10-06" size="small" />
        </div>
        <div class="form-item">
          <label>有效期（天）</label>
          <n-select
            v-model:value="planForm.duration_days"
            :options="[
              { label: '30 天', value: 30 },
              { label: '90 天', value: 90 },
              { label: '180 天', value: 180 },
              { label: '365 天', value: 365 },
            ]"
            size="small"
          />
        </div>
        <div class="form-item">
          <label>到期前提醒（天）</label>
          <n-input-number v-model:value="planForm.notify_days" :min="0" :max="30" size="small" style="width: 100%" />
        </div>
        <div class="plan-hint">
          固定有效期套餐按「生效日起累计」计算用量，保存时会自动关闭设备的「每月自动清除」，
          否则月度计数器清零会打断累计口径。
          <template v-if="planExpiryText">当前套餐：{{ planExpiryText }}</template>
        </div>
      </template>

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
      <template v-if="planMode === 'monthly'">
        <div class="form-item">
          <n-switch v-model:value="trafficForm.auto_clear" />
          <span class="form-inline-label">每月自动清除</span>
        </div>
        <div class="form-item">
          <label>清除日期</label>
          <n-input-number v-model:value="trafficForm.clear_date" :min="1" :max="31" size="small" style="width: 100%" />
        </div>
      </template>
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
import { useCancellableApi } from '@/composables/useCancellableApi';
import { Endpoints } from '@/api/contract';

const api = useCancellableApi();

const props = defineProps<{ show: boolean; trafficLimit: Record<string, any> }>();
const emit = defineEmits<{ 'update:show': [boolean]; save: [form: TrafficForm] }>();

const saving = ref(false);
const planMode = ref<'monthly' | 'fixed'>('monthly');
const planLoaded = ref(false);
/** 当前套餐状态行（fixed 模式） */
const planExpiryText = ref('');

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

const planForm = reactive({
  start_date: '',
  duration_days: 90,
  notify_days: 3,
});

interface TrafficForm {
  planMode: 'monthly' | 'fixed';
  plan: { start_date: string; duration_days: number; notify_days: number } | null;
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
  async (v) => {
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
    // 套餐档案每次打开都拉一次（一次性设置，量小无缓存压力）
    try {
      const { data: plan } = await api.get<Record<string, any>>(Endpoints.plan.read);
      planMode.value = plan.mode === 'fixed' ? 'fixed' : 'monthly';
      planLoaded.value = true;
      planForm.start_date = plan.start_date || todayIso();
      planForm.duration_days = Number(plan.duration_days) || 90;
      planForm.notify_days = Number(plan.notify_days) || 3;
      if (plan.mode === 'fixed' && plan.days_left !== undefined) {
        planExpiryText.value =
          plan.days_left >= 0 ? `剩余 ${plan.days_left} 天` : `已过期 ${-plan.days_left} 天`;
      } else {
        planExpiryText.value = '';
      }
    } catch {
      planLoaded.value = false;
    }
  },
  { immediate: true }
);

function todayIso(): string {
  const d = new Date();
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
}

async function onSave() {
  saving.value = true;
  try {
    // 先写套餐档案（core 侧），失败则不打设备——两段写入避免「档案 fixed 但 auto_clear 还开着」
    if (planLoaded.value || planMode.value === 'fixed') {
      await api.post(Endpoints.plan.write, {
        mode: planMode.value,
        ...(planMode.value === 'fixed'
          ? {
              start_date: planForm.start_date.trim(),
              duration_days: planForm.duration_days,
              notify_days: planForm.notify_days,
            }
          : {}),
      });
    }
    emit('save', {
      planMode: planMode.value,
      plan: planMode.value === 'fixed' ? { ...planForm } : null,
      ...trafficForm,
      // fixed 模式强制关掉设备侧月清（累计口径），父组件透传给 data-limit
      auto_clear: planMode.value === 'fixed' ? false : trafficForm.auto_clear,
    });
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
.plan-hint {
  font-size: 12px;
  color: var(--text-secondary);
  line-height: 1.5;
}
</style>

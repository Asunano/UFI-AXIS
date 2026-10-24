<template>
  <n-modal
    v-model:show="show"
    preset="card"
    title="流量限额"
    class="modal-fit"
    style="--modal-w: 560px; --modal-h: 720px"
  >
    <n-spin :show="loading">
      <ToggleRow
        label="流量限额"
        description="启用后，达到流量限额时将收到提醒或自动断网"
        :model-value="form.enabled"
        @update:model-value="(v: boolean) => (form.enabled = v)"
      />
      <div class="form-grid">
        <div class="form-item">
          <label>限额大小</label>
          <n-input-number v-model:value="form.limit_size" :min="1" size="small" />
        </div>
        <div class="form-item">
          <label>限额单位</label>
          <n-select
            v-model:value="form.limit_unit"
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
          <n-input-number v-model:value="form.alert_percent" :min="1" :max="100" size="small" />
        </div>
        <div class="form-item">
          <label>自动清除</label>
          <!-- ui-baseline-allow: 弹窗行内表单的一个字段，整行 ToggleRow 会把两列表单撑成一行一项 -->
          <n-switch v-model:value="form.auto_clear" />
        </div>
        <div class="form-item">
          <label>清除日期</label>
          <n-input-number v-model:value="form.clear_date" :min="1" :max="31" size="small" />
        </div>
        <div class="form-item">
          <label>到达阈值关闭移动数据</label>
          <!-- ui-baseline-allow: 同上，与相邻的数值字段并排，属于弹窗行内表单 -->
          <n-switch v-model:value="form.auto_off" />
        </div>
        <div v-if="form.auto_off" class="form-item">
          <label>清零后自动重新打开</label>
          <!-- ui-baseline-allow: 同上，且它是条件出现的子项，必须紧跟父开关的版式 -->
          <n-switch v-model:value="form.auto_off_restore" />
        </div>
      </div>
      <div v-if="form.auto_off" class="form-hint">
        达到提醒百分比后先发邮件（需在邮件设置里勾选「流量预警」场景），发送成功 1 分钟后关闭移动数据；
        邮件发送失败则不会关闭网络。
        <template v-if="form.auto_off_triggered">本计费周期已触发过，用量清零前不会再次关闭。</template>
      </div>

      <div class="monthly-stats">
        <InfoRow label="限额总量" :value="form.limit_bytes > 0 ? formatBytes(form.limit_bytes) : '未设置限额'" />
        <InfoRow label="本月已用" :value="formatBytes(form.used_bytes)" />
        <InfoRow label="剩余" :value="form.limit_bytes > 0 ? formatBytes(remainingBytes) : '--'" />
        <InfoRow label="本月已用（下行）" :value="formatBytes(form.monthly_rx_bytes)" />
        <InfoRow label="本月已用（上行）" :value="formatBytes(form.monthly_tx_bytes)" />
        <!-- 未启用限额时百分比没有意义，不显示进度条 -->
        <div v-if="form.enabled" class="limit-progress">
          <n-progress
            v-if="usedPercent !== null"
            type="line"
            :percentage="usedPercent"
            :height="8"
            :status="usedPercent >= 100 ? 'error' : usedPercent >= 80 ? 'warning' : 'success'"
          />
          <span v-else class="limit-progress-hint">未设置限额</span>
        </div>
      </div>
    </n-spin>
    <template #footer>
      <div class="modal-footer">
        <n-button size="small" @click="show = false">关闭</n-button>
        <n-button size="small" type="primary" :loading="saving" @click="save">保存</n-button>
      </div>
    </template>
  </n-modal>
</template>

<script setup lang="ts">
/**
 * 流量限额弹窗（2026-10-14 从 `NetworkTab` 的「流量限额」整卡搬进弹窗，逻辑逐字保留）。
 *
 * 这一项本来就是「一组表单 + 保存」的形态（不是即时生效的开关），最适合收进弹窗：
 * 在页面上它占着一张卡却只在偶尔配置时才用，且字段多、把卡片撑得很高。
 */
import { ref, reactive, computed, watch } from 'vue';
import { useMessage } from 'naive-ui';
import { useCancellableApi } from '@/composables/useCancellableApi';
import { formatBytes } from '@/composables/utils';
import InfoRow from '@/components/InfoRow.vue';
import ToggleRow from '@/components/ToggleRow.vue';

const props = defineProps<{ show: boolean }>();
const emit = defineEmits<{ 'update:show': [boolean] }>();

const show = computed({
  get: () => props.show,
  set: (v) => emit('update:show', v),
});

const message = useMessage();
const api = useCancellableApi();

// 设备 goform 写入成功到「查询能读到新值」之间存在延迟，零延迟回读会拿到旧值。600ms 对齐 Android app。
const GOFORM_SETTLE_MS = 600;
const settleAfterWrite = () => new Promise((resolve) => setTimeout(resolve, GOFORM_SETTLE_MS));

const loading = ref(false);
const saving = ref(false);
const form = reactive({
  enabled: false,
  limit_size: 10,
  limit_unit: 'GB',
  alert_percent: 80,
  auto_clear: false,
  clear_date: 1,
  limit_bytes: 0,
  used_bytes: 0,
  monthly_rx_bytes: 0,
  monthly_tx_bytes: 0,
  // core 自制的到达阈值自动关网（响应里的 auto_off 块，不是设备字段）
  auto_off: false,
  auto_off_restore: false,
  auto_off_triggered: false,
});

const remainingBytes = computed(() => Math.max(0, form.limit_bytes - form.used_bytes));

// null = 未设置限额（limit_bytes 为 0），此时百分比无意义
const usedPercent = computed<number | null>(() => {
  if (form.limit_bytes <= 0) return null;
  const pct = (form.used_bytes / form.limit_bytes) * 100;
  return Math.round(Math.min(100, Math.max(0, pct)) * 10) / 10;
});

async function load() {
  loading.value = true;
  try {
    const { data } = await api.get('/api/device/traffic-limit');
    form.enabled = !!data.enabled;
    form.limit_size = Number(data.limit_value) || 10;
    form.limit_unit = data.limit_unit_display || 'GB';
    form.alert_percent = Number(data.alert_percent) || 80;
    form.auto_clear = !!data.auto_clear;
    form.clear_date = Number(data.clear_date) || 1;
    form.limit_bytes = Number(data.limit_bytes) || 0;
    form.used_bytes = Number(data.used_bytes) || 0;
    form.monthly_rx_bytes = Number(data.monthly_rx_bytes) || 0;
    form.monthly_tx_bytes = Number(data.monthly_tx_bytes) || 0;
    form.auto_off = !!data.auto_off?.enabled;
    form.auto_off_restore = !!data.auto_off?.restore_on_reset;
    form.auto_off_triggered = !!data.auto_off?.triggered;
  } catch {
    /* 静默 */
  } finally {
    loading.value = false;
  }
}

async function save() {
  saving.value = true;
  let posted = false;
  try {
    await api.post('/api/device/data-limit', {
      enabled: form.enabled,
      // 结构化下发：数值 + 单位。设备侧的 "470_1024" 复合串由 core 拼（core 2.8），
      // 前端不再关心乘数映射。
      limit_value: form.limit_size,
      limit_unit: form.limit_unit,
      alert_percent: form.alert_percent,
      auto_clear: form.auto_clear,
      clear_date: form.clear_date,
      // core 自制：达到 alert_percent 后先发邮件，发信成功 1 分钟后关闭移动数据
      auto_off_enabled: form.auto_off,
      auto_off_restore: form.auto_off_restore,
    });
    posted = true;
    message.success('流量限额已保存');
  } catch {
    message.error('保存失败');
  } finally {
    if (posted) await settleAfterWrite();
    await load();
    saving.value = false;
  }
}

watch(
  () => props.show,
  (v) => {
    if (v) load();
  },
  { immediate: true }
);
</script>

<style scoped>
.form-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(200px, 1fr));
  gap: 12px;
  margin: 8px 0 12px;
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
.form-hint {
  font-size: 12px;
  color: var(--text-muted);
  line-height: 1.6;
  margin-bottom: 12px;
}
.monthly-stats {
  padding-top: 12px;
  border-top: 1px solid var(--border-subtle);
}
.limit-progress {
  margin-top: 8px;
}
.limit-progress-hint {
  font-size: 12px;
  color: var(--text-muted);
}
.modal-footer {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}
@media (max-width: 768px) {
  .form-grid {
    grid-template-columns: 1fr;
  }
}
</style>

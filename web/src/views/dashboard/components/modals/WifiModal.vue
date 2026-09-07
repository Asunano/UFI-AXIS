<template>
  <n-modal
    :show="show"
    preset="card"
    title="WiFi 设置"
    style="width: 460px; max-width: calc(100vw - 32px)"
    @update:show="(v: boolean) => emit('update:show', v)"
  >
    <div class="modal-form-col">
      <div class="form-item">
        <n-switch v-model:value="wifiForm.enabled" />
        <span class="form-inline-label">启用 WiFi</span>
      </div>
      <div class="form-item">
        <label>SSID</label>
        <n-input v-model:value="wifiForm.ssid" placeholder="网络名称" size="small" />
      </div>
      <div class="form-item">
        <label>加密方式</label>
        <n-select v-model:value="wifiForm.auth_mode" :options="authModeOptions" size="small" />
      </div>
      <div v-if="wifiForm.auth_mode !== 'OPEN'" class="form-item">
        <label>密码</label>
        <n-input
          v-model:value="wifiForm.password"
          type="password"
          show-password-on="click"
          placeholder="8~63 位"
          size="small"
        />
      </div>
      <div class="form-item">
        <n-switch v-model:value="wifiForm.broadcastHidden" />
        <span class="form-inline-label">隐藏 SSID（不广播）</span>
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
import { ref, reactive, computed, watch } from 'vue';
import type { WifiSettings } from '@/types';

const props = defineProps<{ show: boolean; wifiSettings: WifiSettings | null }>();
const emit = defineEmits<{ 'update:show': [boolean]; save: [payload: WifiSavePayload] }>();

const saving = ref(false);

const wifiForm = reactive({
  enabled: true,
  ssid: '',
  password: '',
  auth_mode: 'WPA2PSK',
  broadcastHidden: false,
});

interface WifiSavePayload {
  enabled: boolean;
  ssid: string;
  password: string;
  auth_mode: string;
  broadcastHidden: boolean;
  cur: WifiSettings | null;
}

// 设备回读的 auth_mode 写法可能不在这三个里（core 只对 OPEN 特判，其余原样透传给 goform
// AuthMode），动态并入当前值，避免 select 显示空白、保存时把现有加密方式改坏
const authModeOptions = computed(() => {
  const base = ['WPA2PSK', 'WPAPSK', 'OPEN'];
  const cur = props.wifiSettings?.auth_mode;
  const values = cur && !base.includes(cur) ? [cur, ...base] : base;
  return values.map((v) => ({ label: v === 'OPEN' ? '开放（无密码）' : v, value: v }));
});

// 打开时把当前只读数据填入表单
watch(
  () => props.show,
  (v) => {
    if (!v) return;
    const s = props.wifiSettings;
    wifiForm.enabled = s?.enabled ?? true;
    wifiForm.ssid = s?.ssid || '';
    wifiForm.password = s?.password || '';
    wifiForm.auth_mode = s?.auth_mode || 'WPA2PSK';
    wifiForm.broadcastHidden = s?.broadcast_disabled === 1;
  },
  { immediate: true }
);

function onSave() {
  saving.value = true;
  try {
    emit('save', { ...wifiForm, cur: props.wifiSettings });
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

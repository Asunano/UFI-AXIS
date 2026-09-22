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
      <!-- 开放网络没有密码可填：这一项隐藏，提交时也不带 passphrase（见 buildWifiConfigPayload） -->
      <div v-if="needsPassphrase" class="form-item">
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
        <label>最大连接数</label>
        <!-- 上限 10：用户对中兴 F50 的规格结论（2026-09-22），与 core 的 validateApConfig（1~10）一致。
             :max 只在用步进器时硬夹，手打 99 仍会落进 form —— 提交守门在 isWifiMaxStaNumAcceptable。 -->
        <n-input-number
          v-model:value="wifiForm.maxStaNum"
          :min="WifiMaxStaNumRange.min"
          :max="WifiMaxStaNumRange.max"
          :precision="0"
          placeholder="留空 = 不修改"
          size="small"
        />
      </div>
      <div class="form-item">
        <!-- 开=隐藏（broadcast_disabled=1），关=广播。别按"广播开关"理解，值是反的 -->
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
import { useMessage } from 'naive-ui';
import {
  WIFI_AUTH_MODE_DEFAULT,
  WifiMaxStaNumRange,
  isWifiMaxStaNumAcceptable,
  wifiSecurityNeedsPassphrase,
  wifiSecurityOptions,
} from '@/api/contract';
import type { WifiSettings } from '@/types';

const props = defineProps<{ show: boolean; wifiSettings: WifiSettings | null }>();
const emit = defineEmits<{ 'update:show': [boolean]; save: [payload: WifiSavePayload] }>();

const message = useMessage();
const saving = ref(false);

const wifiForm = reactive({
  enabled: true,
  ssid: '',
  password: '',
  auth_mode: WIFI_AUTH_MODE_DEFAULT,
  /** null = 留空，语义是「不修改」 */
  maxStaNum: null as number | null,
  broadcastHidden: false,
});

interface WifiSavePayload {
  enabled: boolean;
  ssid: string;
  password: string;
  auth_mode: string;
  maxStaNum: number | null;
  broadcastHidden: boolean;
  cur: WifiSettings | null;
}

// 加密方式的 4 个档位与 auth_mode/encryp_type 的配对关系是设备契约，统一放在 contract.ts；
// 设备回读的写法不在表里时由 wifiSecurityOptions 置顶并入，避免 select 空白 / 顺手改坏
const authModeOptions = computed(() => wifiSecurityOptions(props.wifiSettings?.auth_mode));
const needsPassphrase = computed(() => wifiSecurityNeedsPassphrase(wifiForm.auth_mode));

// 打开时把当前只读数据填入表单
watch(
  () => props.show,
  (v) => {
    if (!v) return;
    const s = props.wifiSettings;
    wifiForm.enabled = s?.enabled ?? true;
    wifiForm.ssid = s?.ssid || '';
    wifiForm.password = s?.password || '';
    wifiForm.auth_mode = s?.auth_mode || WIFI_AUTH_MODE_DEFAULT;
    // 0 / NaN 都当「读不到」处理：填 0 进去会在保存时下发一个不合法的最大连接数
    wifiForm.maxStaNum = Number(s?.max_sta_num) > 0 ? Number(s?.max_sta_num) : null;
    wifiForm.broadcastHidden = s?.broadcast_disabled === 1;
  },
  { immediate: true }
);

function onSave() {
  // 拦下来要说原因：静默丢掉这一项会变成「填了 0 点保存，提示成功，设备没变」
  if (!isWifiMaxStaNumAcceptable(wifiForm.maxStaNum)) {
    message.warning(
      `最大连接数必须是 ${WifiMaxStaNumRange.min}~${WifiMaxStaNumRange.max} 的整数（中兴 F50 最大支持 10 个），留空表示不修改`
    );
    return;
  }
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

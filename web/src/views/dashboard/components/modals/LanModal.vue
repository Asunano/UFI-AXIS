<template>
  <n-modal
    :show="show"
    preset="card"
    title="局域网 (LAN) 设置"
    style="width: 460px; max-width: calc(100vw - 32px)"
    @update:show="(v: boolean) => emit('update:show', v)"
  >
    <div class="modal-form-col">
      <div class="form-item">
        <n-switch v-model:value="lanForm.dhcp_enabled" />
        <span class="form-inline-label">启用 DHCP 服务器</span>
      </div>
      <div class="form-item">
        <label>LAN IP</label>
        <n-input v-model:value="lanForm.lan_ip" placeholder="192.168.0.1" size="small" />
      </div>
      <div class="form-item">
        <label>子网掩码</label>
        <n-input v-model:value="lanForm.lan_netmask" placeholder="255.255.255.0" size="small" />
      </div>
      <div class="form-item">
        <label>DHCP 起始地址</label>
        <n-input v-model:value="lanForm.dhcp_start" placeholder="192.168.0.100" size="small" />
      </div>
      <div class="form-item">
        <label>DHCP 结束地址</label>
        <n-input v-model:value="lanForm.dhcp_end" placeholder="192.168.0.200" size="small" />
      </div>
      <div class="form-item">
        <label>租约时间 (小时)</label>
        <n-input-number v-model:value="lanForm.dhcp_lease_hour" :min="1" size="small" style="width: 100%" />
      </div>
      <!-- MTU 只读：core 侧没有对应写入接口，放这里是为了不用再单独开一处 LAN 信息 -->
      <InfoRow label="MTU" :value="lanSettings.mtu ?? '--'" />
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
import InfoRow from '@/components/InfoRow.vue';

const props = defineProps<{ show: boolean; lanSettings: Record<string, any> }>();
const emit = defineEmits<{ 'update:show': [boolean]; save: [form: LanForm] }>();

const saving = ref(false);

const lanForm = reactive({
  lan_ip: '',
  lan_netmask: '',
  dhcp_enabled: false,
  dhcp_start: '',
  dhcp_end: '',
  dhcp_lease_hour: 24,
});

interface LanForm {
  lan_ip: string;
  lan_netmask: string;
  dhcp_enabled: boolean;
  dhcp_start: string;
  dhcp_end: string;
  dhcp_lease_hour: number;
}

// 打开时把当前只读数据填入表单（父组件持续轮询刷新 lanSettings，所以这里拿到的是最新值）
watch(
  () => props.show,
  (v) => {
    if (!v) return;
    const s = props.lanSettings || {};
    lanForm.lan_ip = s.lan_ip || '';
    lanForm.lan_netmask = s.lan_netmask || '';
    lanForm.dhcp_enabled = !!s.dhcp_enabled;
    lanForm.dhcp_start = s.dhcp_start || '';
    lanForm.dhcp_end = s.dhcp_end || '';
    lanForm.dhcp_lease_hour = Math.max(1, Math.round((s.dhcp_lease_sec || 86400) / 3600));
  },
  { immediate: true }
);

async function onSave() {
  saving.value = true;
  try {
    emit('save', { ...lanForm });
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
/* 原来这里自己定义了一份 .info-row/.info-label/.info-value，与公共 InfoRow.vue 的类名
   逐字撞车、样式又略有出入；已改用公共组件。 */
</style>

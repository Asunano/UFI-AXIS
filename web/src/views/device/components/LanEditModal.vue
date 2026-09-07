<template>
  <n-modal
    :show="show"
    preset="dialog"
    title="编辑 LAN / DHCP 设置"
    positive-text="保存"
    negative-text="取消"
    @update:show="(v: boolean) => emit('update:show', v)"
    @positive-click="onSave"
  >
    <div class="modal-form">
      <div class="form-item">
        <label>DHCP 服务</label>
        <n-switch v-model:value="lanForm.dhcp_enabled" />
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
        <n-input-number v-model:value="lanForm.dhcp_lease_hour" :min="1" size="small" />
      </div>
    </div>
  </n-modal>
</template>

<script setup lang="ts">
import { ref, reactive, watch } from 'vue';

const props = defineProps<{ show: boolean; lanSettings: Record<string, any> }>();
const emit = defineEmits<{ 'update:show': [boolean]; save: [form: Record<string, any>] }>();

const saving = ref(false);

const lanForm = reactive({
  lan_ip: '',
  lan_netmask: '',
  dhcp_enabled: false,
  dhcp_start: '',
  dhcp_end: '',
  dhcp_lease_hour: 24,
});

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

function onSave() {
  saving.value = true;
  try {
    // 真正的接口写入留在父组件（NetworkTab.saveLanSettings），与 4.1/4.2 受控范式一致
    emit('save', { ...lanForm });
    emit('update:show', false);
  } finally {
    saving.value = false;
  }
}
</script>

<style scoped>
.modal-form {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 12px;
  padding: 8px 0;
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
@media (max-width: 768px) {
  .modal-form {
    grid-template-columns: 1fr;
  }
}
</style>

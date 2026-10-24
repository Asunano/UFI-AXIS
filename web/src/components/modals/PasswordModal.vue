<template>
  <n-modal v-model:show="show" preset="card" title="设备后台密码" class="modal-fit" style="--modal-w: 480px">
    <div class="form-grid">
      <div class="form-item">
        <label>旧密码</label>
        <n-input
          v-model:value="form.old_password"
          type="password"
          show-password-on="mousedown"
          placeholder="输入当前密码"
          size="small"
        />
      </div>
      <div class="form-item">
        <label>新密码</label>
        <n-input
          v-model:value="form.new_password"
          type="password"
          show-password-on="mousedown"
          placeholder="输入新密码"
          size="small"
        />
      </div>
    </div>
    <template #footer>
      <div class="modal-footer">
        <n-button size="small" @click="show = false">关闭</n-button>
        <n-button size="small" type="primary" :loading="loading" @click="changePassword">修改密码</n-button>
      </div>
    </template>
  </n-modal>
</template>

<script setup lang="ts">
/**
 * 设备后台密码弹窗（2026-10-14 从 `AdvancedTab` 的「设备后台密码」整卡搬进弹窗，逻辑逐字保留）。
 *
 * 成功后清空两个输入框（原逻辑）：改完密码还把明文留在表单里没有意义。
 */
import { ref, reactive, computed, watch } from 'vue';
import { useMessage } from 'naive-ui';
import { useCancellableApi } from '@/composables/useCancellableApi';

const props = defineProps<{ show: boolean }>();
const emit = defineEmits<{ 'update:show': [boolean] }>();

const show = computed({
  get: () => props.show,
  set: (v) => emit('update:show', v),
});

const message = useMessage();
const api = useCancellableApi();

const loading = ref(false);
const form = reactive({ old_password: '', new_password: '' });

async function changePassword() {
  if (!form.old_password || !form.new_password) {
    message.warning('请填写旧密码和新密码');
    return;
  }
  loading.value = true;
  try {
    await api.post('/api/device/password', {
      old_password: form.old_password,
      new_password: form.new_password,
    });
    message.success('密码修改成功');
    form.old_password = '';
    form.new_password = '';
  } catch {
    message.error('密码修改失败');
  } finally {
    loading.value = false;
  }
}

// 每次打开都清空：上次留下的明文不该在下次打开时还在
watch(
  () => props.show,
  (v) => {
    if (v) {
      form.old_password = '';
      form.new_password = '';
    }
  }
);
</script>

<style scoped>
.form-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
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

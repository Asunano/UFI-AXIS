<template>
  <!--
    主人密码输入框（破坏性操作保护）。

    只在 core 的 `OwnerGate` 开关打开时才会出现 —— 关着时后端根本不会要求密码，
    这个框永远不会被调用。触发点是 `useApi` 收到 `401 MISSING_PASSWORD`。
  -->
  <n-modal
    :show="ownerPasswordVisible"
    preset="card"
    title="需要配对密码"
    class="owner-password-modal"
    :bordered="false"
    :mask-closable="false"
    :close-on-esc="true"
    :style="{ width: 'min(420px, calc(100vw - 32px))' }"
    @update:show="onCancel"
  >
    <p class="owner-password-hint">{{ ownerPasswordHint }}</p>
    <n-input
      v-model:value="password"
      type="password"
      show-password-on="click"
      placeholder="配对密码"
      :autofocus="true"
      @keyup.enter="onConfirm"
    />
    <template #footer>
      <div class="owner-password-actions">
        <n-button quaternary :disabled="submitting" @click="onCancel">取消</n-button>
        <n-button type="primary" :loading="submitting" @click="onConfirm">确认</n-button>
      </div>
    </template>
  </n-modal>
</template>

<script setup lang="ts">
import { ref, watch } from 'vue';
import { NButton, NInput, NModal } from 'naive-ui';
import { ownerPasswordHint, ownerPasswordVisible, submitOwnerPassword } from '@/composables/ownerPassword';

const password = ref('');
const submitting = ref(false);

// 每次重新弹出时清空上一次的输入：留着一个旧密码会让「这次输错了」变得看不出来。
watch(ownerPasswordVisible, (open) => {
  if (open) {
    password.value = '';
    submitting.value = false;
  }
});

function onConfirm() {
  if (submitting.value) return;
  submitting.value = true;
  submitOwnerPassword(password.value);
  submitting.value = false;
}

function onCancel() {
  if (submitting.value) return;
  submitOwnerPassword(null);
}
</script>

<style scoped>
.owner-password-hint {
  margin: 0 0 12px;
  font-size: 13px;
  line-height: 1.6;
  color: var(--ufi-text-secondary, var(--text-secondary, inherit));
  opacity: 0.85;
}

.owner-password-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}
</style>

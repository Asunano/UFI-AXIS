<template>
  <n-modal
    :show="show"
    preset="card"
    :title="isNew ? '新建 Cloudflare 隧道' : `编辑 ${nameInput}`"
    style="max-width: 560px"
    @update:show="emit('update:show', $event)"
  >
    <n-space vertical :size="10">
      <n-input v-if="isNew" v-model:value="nameInput" size="small" placeholder="隧道名（命名规则同 FRP）" />
      <n-input
        v-model:value="token"
        type="password"
        show-password-on="click"
        size="small"
        placeholder="cloudflared 隧道 token"
      />
      <span class="row-hint">
        core 的 PUT 要求 token 非空，所以编辑时会先回读现有 token 预填 —— 该端点本身就是明文返回 token 的。
      </span>
    </n-space>
    <template #footer>
      <n-space justify="end" :size="8">
        <n-button size="small" @click="emit('update:show', false)">取消</n-button>
        <n-button size="small" type="primary" :loading="saving" @click="save">保存</n-button>
      </n-space>
    </template>
  </n-modal>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { useMessage } from 'naive-ui';
import { useCancellableApi } from '@/composables/useCancellableApi';
import { errText } from '@/views/tunnel/tunnelShared';

/** `name` 为空串 = 新建 */
const props = defineProps<{ show: boolean; name: string }>();
const emit = defineEmits<{ 'update:show': [boolean]; saved: [] }>();

const message = useMessage();
const api = useCancellableApi();

const nameInput = ref('');
const token = ref('');
const saving = ref(false);
const isNew = computed(() => !props.name);

watch(
  () => props.show,
  async (v) => {
    if (!v) return;
    nameInput.value = props.name;
    token.value = '';
    saving.value = false;
    if (!props.name) return;
    try {
      // core 的 PUT 强制 token 非空，无法「留空表示不修改」，所以必须回读预填
      const { data } = await api.get(`/api/tunnel/cf/tunnel/${encodeURIComponent(props.name)}`);
      token.value = data?.token || '';
    } catch (e: any) {
      message.error(errText(e, '读取隧道失败'));
    }
  },
  { immediate: true }
);

async function save() {
  const name = nameInput.value.trim();
  if (!name) {
    message.error('请填写隧道名');
    return;
  }
  if (!token.value.trim()) {
    message.error('token 不能为空');
    return;
  }
  saving.value = true;
  try {
    await api.put(`/api/tunnel/cf/tunnel/${encodeURIComponent(name)}`, { token: token.value });
    message.success('隧道已保存');
    emit('update:show', false);
    emit('saved');
  } catch (e: any) {
    message.error(errText(e, '保存失败'));
  } finally {
    saving.value = false;
  }
}
</script>

<style scoped>
.row-hint {
  font-size: 12px;
  color: var(--text-muted);
}
</style>

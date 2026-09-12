<template>
  <n-modal
    :show="show"
    preset="card"
    :title="isNew ? '新建 FRP 通道' : `编辑 ${nameInput}`"
    style="max-width: 720px"
    @update:show="emit('update:show', $event)"
  >
    <n-space vertical :size="10">
      <n-input
        v-if="isNew"
        v-model:value="nameInput"
        size="small"
        placeholder='通道名（不能含 \\ / : * ? " &lt; &gt; | 逗号，最长 64）'
      />
      <n-input
        v-model:value="toml"
        type="textarea"
        :autosize="{ minRows: 12, maxRows: 24 }"
        placeholder="frpc TOML 配置"
      />
      <span class="row-hint">
        core 只做行级解析取 serverAddr / server_port / proxy 数；toml 为空会被拒绝（400），不会写成空文件。
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

/** `name` 为空串 = 新建（此时通道名由用户在弹窗里填） */
const props = defineProps<{ show: boolean; name: string }>();
const emit = defineEmits<{ 'update:show': [boolean]; saved: [] }>();

const message = useMessage();
const api = useCancellableApi();

const nameInput = ref('');
const toml = ref('');
const saving = ref(false);
const isNew = computed(() => !props.name);

watch(
  () => props.show,
  async (v) => {
    if (!v) return;
    nameInput.value = props.name;
    toml.value = '';
    saving.value = false;
    if (!props.name) return;
    try {
      const { data } = await api.get(`/api/tunnel/frp/config/${encodeURIComponent(props.name)}`);
      toml.value = data?.toml || '';
    } catch (e: any) {
      message.error(errText(e, '读取配置失败'));
    }
  },
  { immediate: true }
);

async function save() {
  const name = nameInput.value.trim();
  if (!name) {
    message.error('请填写通道名');
    return;
  }
  // core 对空 toml 会 400（T40-2 之前会静默写成空文件），这里先拦一道给出更直接的提示
  if (!toml.value.trim()) {
    message.error('TOML 配置不能为空');
    return;
  }
  saving.value = true;
  try {
    await api.put(`/api/tunnel/frp/config/${encodeURIComponent(name)}`, { toml: toml.value });
    message.success('配置已保存');
    emit('update:show', false);
    emit('saved');
  } catch (e: any) {
    // 400 常见原因：名字含非法字符（\ / : * ? " < > | 逗号 .. 或超 64 字符）
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

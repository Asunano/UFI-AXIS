<template>
  <n-modal
    :show="show"
    preset="card"
    title="文件校验和"
    style="width: 460px"
    @update:show="emit('update:show', $event)"
  >
    <div v-if="result" class="cs-grid">
      <InfoRow label="文件" :value="result.path" />
      <div v-for="(hash, algo) in result.algorithms" :key="algo" class="cs-row">
        <span class="cs-label">{{ algo.toUpperCase() }}</span>
        <code class="cs-hash">{{ hash }}</code>
        <n-button size="tiny" text type="primary" @click="copyHash(hash)">复制</n-button>
      </div>
    </div>
  </n-modal>
</template>

<script setup lang="ts">
/**
 * 校验和弹窗。数据来自 `/api/files/checksum`（由页面取好再打开）。
 * 每条算法一行，附「复制」按钮，便于比对下载文件的官方哈希。
 */
import InfoRow from '@/components/InfoRow.vue';

defineProps<{
  show: boolean;
  result: { path: string; algorithms: Record<string, string> } | null;
}>();

const emit = defineEmits<{ (e: 'update:show', v: boolean): void }>();

function copyHash(hash: string) {
  navigator.clipboard?.writeText(hash).catch(() => {});
}
</script>

<style scoped>
.cs-grid {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.cs-row {
  display: flex;
  align-items: center;
  gap: 8px;
}
.cs-label {
  width: 56px;
  flex-shrink: 0;
  font-size: 12px;
  color: var(--text-muted);
  font-variant-numeric: tabular-nums;
  text-transform: uppercase;
}
.cs-hash {
  flex: 1;
  min-width: 0;
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 12px;
  word-break: break-all;
  background: var(--surface-elevated);
  padding: 4px 6px;
  border-radius: 6px;
}
</style>

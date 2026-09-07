<template>
  <n-modal v-model:show="show" preset="card" title="已拉黑设备" style="width: 460px; max-width: calc(100vw - 32px)">
    <div v-if="blockedList.length" class="lan-list">
      <div v-for="e in blockedList" :key="e.mac" class="lan-row acl-row">
        <span class="lan-name">{{ e.name || '未知设备' }}</span>
        <span class="lan-mac">{{ e.mac }}</span>
        <span class="lan-act">
          <n-button
            size="tiny"
            :loading="aclPending === e.mac.toLowerCase()"
            :disabled="!!aclPending"
            @click="emit('unblock', e.mac)"
            >解除</n-button
          >
        </span>
      </div>
    </div>
    <div v-else class="hint-text">还没有拉黑任何设备。在「局域网设备」列表里点「拉黑」即可。</div>
    <template #footer>
      <div class="modal-footer">
        <n-button size="small" @click="show = false">关闭</n-button>
        <n-button
          size="small"
          type="error"
          :disabled="!blockedList.length || !!aclPending"
          :loading="aclPending === '*'"
          @click="emit('clear')"
          >全部解除</n-button
        >
      </div>
    </template>
  </n-modal>
</template>

<script setup lang="ts">
import { computed } from 'vue';

interface BlockedEntry {
  mac: string;
  name?: string;
}

const props = defineProps<{
  show: boolean;
  blockedList: BlockedEntry[];
  aclPending: string;
}>();

const emit = defineEmits<{
  'update:show': [boolean];
  unblock: [string];
  clear: [];
}>();

const show = computed({
  get: () => props.show,
  set: (v) => emit('update:show', v),
});
</script>

<style scoped>
.modal-footer {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}
.lan-list {
  display: flex;
  flex-direction: column;
}
.lan-row {
  display: grid;
  /* 第 4 列固定 56px 给拉黑/解除按钮；前三列仍按内容比例分 */
  grid-template-columns: minmax(0, 1.2fr) minmax(0, 1fr) minmax(0, 1.3fr) 56px;
  align-items: center;
  gap: 12px;
  padding: 8px 0;
  border-bottom: 1px solid var(--border-subtle);
}
/* 已拉黑弹窗里只有 名称 / MAC / 操作 三列 */
.acl-row {
  grid-template-columns: minmax(0, 1fr) minmax(0, 1.3fr) 56px;
}
.lan-row:last-child {
  border-bottom: none;
}
.lan-name {
  color: var(--text-primary);
  font-weight: 500;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.lan-mac {
  color: var(--text-muted);
  font-family: monospace;
  font-size: 12px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.lan-act {
  display: flex;
  justify-content: flex-end;
}
.hint-text {
  font-size: 13px;
  color: var(--text-muted);
}
</style>

<template>
  <n-modal :show="show" preset="card" title="文件信息" style="width: 400px" @update:show="emit('update:show', $event)">
    <div v-if="info" class="info-grid">
      <InfoRow label="名称" :value="info.name" />
      <InfoRow label="路径" :value="info.path" />
      <InfoRow label="类型" :value="info.isDirectory ? '文件夹' : '文件'" />
      <InfoRow label="大小" :value="info.isDirectory ? '--' : formatBytes(info.size ?? 0)" />
      <InfoRow label="修改时间" :value="formatDate(info.lastModified ?? 0)" />
      <InfoRow label="权限" :value="info.permissions || '--'" />
      <InfoRow v-if="info.owner" label="所有者" :value="info.owner" />
    </div>
  </n-modal>
</template>

<script setup lang="ts">
/**
 * 文件信息。数据来自 `/api/files/info`（比列表条目多 permissions / owner），
 * 由页面在点「信息」时取好再打开 —— 取失败只弹错误、不开弹窗。
 */
import InfoRow from '@/components/InfoRow.vue';
import { formatBytes } from '@/composables/utils';
import { formatDate, type FileEntry } from '../../filesShared';

defineProps<{
  show: boolean;
  info: FileEntry | null;
}>();

const emit = defineEmits<{ (e: 'update:show', v: boolean): void }>();
</script>

<style scoped>
.info-grid {
  display: flex;
  flex-direction: column;
}
</style>

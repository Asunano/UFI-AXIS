<template>
  <n-modal :show="show" preset="card" title="文件信息" style="width: 400px" @update:show="emit('update:show', $event)">
    <div v-if="info" class="info-grid">
      <InfoRow label="名称" :value="info.name" />
      <InfoRow label="路径" :value="info.path" />
      <InfoRow label="类型" :value="info.isDirectory ? '文件夹' : '文件'" />
      <InfoRow label="大小" :value="info.isDirectory ? '--' : formatBytes(info.size ?? 0)" />
      <InfoRow label="修改时间" :value="formatDate(info.lastModified ?? 0)" />
      <InfoRow label="权限" :value="info.permissions || '--'" />
      <InfoRow label="符号链接" :value="info.isSymlink ? '是' : '否'" />
      <InfoRow v-if="info.owner" label="所有者" :value="info.owner" />
    </div>

    <!-- 左关闭 / 右唯一主操作，与 app 的 FileInfoDialog 同形：
         压缩包与 apk 的双击都落到这个弹窗，动作只能从这里显式触发。
         没有成立的主操作时（rar/7z、pdf、未知扩展名）只留「关闭」——
         宁可少一个按钮，也不放一个点了只会重新挂起本弹窗的假按钮。 -->
    <template #action>
      <div class="info-actions">
        <n-button size="small" @click="emit('update:show', false)">关闭</n-button>
        <n-button v-if="primaryAction" size="small" type="primary" @click="onPrimary">
          {{ primaryAction.label }}
        </n-button>
      </div>
    </template>
  </n-modal>
</template>

<script setup lang="ts">
/**
 * 文件信息。数据来自 `/api/files/info`（比列表条目多 permissions / owner），
 * 由页面在点「信息」时取好再打开 —— 取失败只弹错误、不开弹窗。
 *
 * 主操作按钮只 emit key，具体做什么由 FilesView 的 `handleAction` 决定：
 * 那是长按菜单与本弹窗共用的**唯一**分发口，两处入口不会走出两套行为。
 */
import InfoRow from '@/components/InfoRow.vue';
import { formatBytes } from '@/composables/utils';
import { formatDate, type FileEntry, type PrimaryFileAction } from '../../filesShared';

const props = defineProps<{
  show: boolean;
  info: FileEntry | null;
  primaryAction: PrimaryFileAction | null;
}>();

const emit = defineEmits<{
  (e: 'update:show', v: boolean): void;
  (e: 'action', key: string): void;
}>();

/** 先关弹窗再发动作：apk 的安装确认框否则会叠在本弹窗上面（app 侧同样是这个顺序） */
function onPrimary() {
  const key = props.primaryAction?.action;
  emit('update:show', false);
  if (key) emit('action', key);
}
</script>

<style scoped>
.info-grid {
  display: flex;
  flex-direction: column;
}

.info-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-2);
  width: 100%;
}
</style>

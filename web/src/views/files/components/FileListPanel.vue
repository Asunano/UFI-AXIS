<template>
  <n-spin :show="loading">
    <div class="file-list">
      <div v-if="searchInfo" class="search-header">
        搜索 "{{ searchInfo.query }}" — {{ searchInfo.count }} 个结果
        <n-button size="tiny" text @click="emit('clear-search')">清除</n-button>
      </div>
      <div
        v-for="f in files"
        :key="f.path"
        class="file-item"
        :class="{ selected: selectedPath === f.path }"
        @click="emit('select', f)"
        @dblclick="emit('open', f)"
      >
        <FileKindIcon :file="f" />
        <div class="file-info">
          <span class="file-name">{{ f.name }}</span>
          <span class="file-meta">{{ fileMeta(f) }}</span>
        </div>
        <div class="file-actions" @click.stop>
          <n-button v-if="canPreview(f)" size="tiny" text @click="emit('preview', f)">预览</n-button>
          <n-button v-if="!f.isDirectory" size="tiny" text @click="emit('download', f)">下载</n-button>
          <n-dropdown :options="fileActions(f)" trigger="click" @select="(k: string) => emit('action', k, f)">
            <n-button size="tiny" text>
              <template #icon
                ><n-icon><EllipsisHorizontalOutline /></n-icon
              ></template>
            </n-button>
          </n-dropdown>
        </div>
      </div>
      <div v-if="!files.length && !loading" class="empty-state">
        {{ searchInfo ? '无匹配结果' : '空目录' }}
      </div>
    </div>
  </n-spin>
</template>

<script setup lang="ts">
/**
 * 文件列表。纯展示 + 意图上报：排序后的数组由页面给（搜索结果与目录列表共用同一个出口），
 * 每行的动作只 emit key，具体请求在页面里。
 *
 * `searchInfo` 非空即表示「当前展示的是搜索结果」——
 * 空态文案要据此区分「无匹配结果」和「空目录」，用 files.length 判不出来。
 */
import { canPreview, fileMeta, isTextEditable, type FileEntry } from '../filesShared';
import FileKindIcon from './FileKindIcon.vue';
import { EllipsisHorizontalOutline } from '@vicons/ionicons5';

defineProps<{
  files: FileEntry[];
  loading: boolean;
  selectedPath: string;
  /** null = 正常目录浏览；非 null = 搜索结果视图 */
  searchInfo: { query: string; count: number } | null;
}>();

const emit = defineEmits<{
  (e: 'select', f: FileEntry): void;
  (e: 'open', f: FileEntry): void;
  (e: 'preview', f: FileEntry): void;
  (e: 'download', f: FileEntry): void;
  (e: 'action', key: string, f: FileEntry): void;
  (e: 'clear-search'): void;
}>();

function fileActions(f: FileEntry) {
  const ext = f.name.split('.').pop()?.toLowerCase();
  const opts = [
    { label: '重命名', key: 'rename' },
    { label: '复制', key: 'copy' },
    { label: '剪切', key: 'cut' },
    { label: '信息', key: 'info' },
  ];
  if (!f.isDirectory) {
    opts.unshift({ label: '下载', key: 'download' });
    if (isTextEditable(f)) {
      opts.unshift({ label: '打开', key: 'open' });
    }
    if (canPreview(f)) {
      opts.unshift({ label: '预览', key: 'preview' });
    }
    if (ext === 'apk') {
      opts.unshift({ label: '安装 APK', key: 'install' });
    }
  }
  opts.push({ label: '删除', key: 'delete' });
  return opts;
}
</script>

<style scoped>
.file-list {
  border: 1px solid var(--border-subtle);
  border-radius: 8px;
  overflow: hidden;
}
.search-header {
  padding: 8px 12px;
  background: var(--surface-elevated);
  font-size: 13px;
  display: flex;
  align-items: center;
  gap: 8px;
}
.file-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 12px;
  cursor: pointer;
  border-bottom: 1px solid var(--border-subtle);
  transition: background 0.12s ease;
  user-select: none;
}
.file-item:last-child {
  border-bottom: none;
}
.file-item:hover {
  background: var(--surface-hover);
}
.file-item.selected {
  background: var(--accent-color-light);
}
.file-icon {
  flex-shrink: 0;
}
.file-info {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.file-name {
  font-size: 14px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.file-meta {
  font-size: 12px;
  color: var(--text-muted);
  font-variant-numeric: tabular-nums;
}
.file-actions {
  display: flex;
  align-items: center;
  gap: 2px;
  flex-shrink: 0;
  opacity: 0.55;
  transition: opacity 0.12s ease;
}
.file-item:hover .file-actions,
.file-item.selected .file-actions {
  opacity: 1;
}
.empty-state {
  padding: 48px 16px;
  text-align: center;
  color: var(--text-muted);
  font-size: 14px;
}
</style>

<!--
  上传弹窗（2026-11-07）。

  ## 为什么要有"选完不立刻传"这一步
  旧行为是选完文件立刻入队开传，于是：选错了没法收回、想一次传"几个文件 + 一个文件夹"
  要分好几次、传之前看不到总大小。这里先把文件暂存在列表里，点「开始上传」才真正入队。

  ## 文件夹上传是怎么做的
  两条来源：
    · `<input webkitdirectory>`：浏览器给每个 File 带 `webkitRelativePath`，直接能算出相对目录；
    · 拖拽：`DataTransferItem.webkitGetAsEntry()` 递归读目录树（`dataTransfer.files`
      里目录只是个 0 字节的假 File，不展开就会传上去一个空文件）。
  真正在设备端建目录树由页面负责（`onUploadSubmit`），本组件只负责"文件 + 相对目录"。
-->
<template>
  <n-modal
    :show="show"
    preset="card"
    title="上传文件"
    class="modal-fit"
    style="--modal-w: 620px"
    @update:show="emit('update:show', $event)"
  >
    <div class="up-bar">
      <n-dropdown trigger="click" :options="pickOptions" @select="onPick">
        <n-button size="small">
          {{ pickLabel }}
          <template #icon
            ><n-icon><ChevronDownOutline /></n-icon
          ></template>
        </n-button>
      </n-dropdown>
      <span class="up-total">{{ totalText }}</span>
      <span class="grow"></span>
      <n-button size="small" :disabled="!items.length || uploading" @click="clearAll">清空列表</n-button>
    </div>

    <input ref="fileInput" class="up-hidden" type="file" multiple @change="onFilePicked" />
    <!-- webkitdirectory 让浏览器把整个目录树展开成多个 File；TS 的 input 属性表里没有它，
         用属性名直接写（HTML 允许未知属性，浏览器按 webkit 前缀识别）。 -->
    <input ref="dirInput" class="up-hidden" type="file" webkitdirectory multiple @change="onDirPicked" />

    <div
      class="up-drop"
      :class="{ 'is-over': dragOver }"
      @dragenter.prevent="dragOver = true"
      @dragover.prevent="dragOver = true"
      @dragleave.prevent="dragOver = false"
      @drop.prevent="onDrop"
    >
      <template v-if="items.length === 0"> 将需要上传的文件 / 文件夹拖到此处 </template>
      <ul v-else class="up-list">
        <li v-for="(it, i) in items" :key="`${it.relDir}/${it.file.name}/${i}`" class="up-item">
          <n-icon :size="14" class="up-icon">
            <FolderOutline v-if="it.relDir" />
            <DocumentOutline v-else />
          </n-icon>
          <span class="up-name" :title="fullLabel(it)">{{
            it.relDir ? `${it.relDir}${it.file.name}` : it.file.name
          }}</span>
          <span class="up-size">{{ formatBytes(it.file.size) }}</span>
          <button type="button" class="up-remove" :disabled="uploading" title="移除" @click="items.splice(i, 1)">
            ✕
          </button>
        </li>
      </ul>
    </div>

    <div class="up-foot">
      <span class="up-hint">文件会传到当前目录；文件夹会在设备端建出同样的目录结构。</span>
      <n-button type="primary" size="small" :disabled="!items.length || uploading" @click="submit"> 开始上传 </n-button>
    </div>
  </n-modal>
</template>

<script setup lang="ts">
/**
 * 上传暂存弹窗：收集「文件 + 相对目录」，点开始上传才交给页面入队。
 * 目录树的创建、超限提示、断点续传都由 `useFileUpload` / 页面负责，这里不重复实现。
 */
import { computed, ref } from 'vue';
import { ChevronDownOutline, DocumentOutline, FolderOutline } from '@vicons/ionicons5';
import { formatBytes } from '@/composables/utils';

export interface StagedUpload {
  file: File;
  /** 相对目录（以 `/` 结尾，顶层文件为空串）。设备端会在它下面建目录。 */
  relDir: string;
}

defineProps<{ show: boolean; uploading?: boolean }>();
const emit = defineEmits<{
  (e: 'update:show', v: boolean): void;
  (e: 'submit', items: StagedUpload[]): void;
}>();

const fileInput = ref<HTMLInputElement | null>(null);
const dirInput = ref<HTMLInputElement | null>(null);
const dragOver = ref(false);
const items = ref<StagedUpload[]>([]);

const pickLabel = ref('上传文件');
const pickOptions = [
  { label: '上传文件', key: 'files' },
  { label: '上传文件夹', key: 'folder' },
];

const totalText = computed(() => {
  if (!items.value.length) return '';
  const bytes = items.value.reduce((sum, it) => sum + it.file.size, 0);
  return `${items.value.length} 个文件 · ${formatBytes(bytes)}`;
});

function onPick(key: string): void {
  pickLabel.value = key === 'folder' ? '上传文件夹' : '上传文件';
  if (key === 'folder') dirInput.value?.click();
  else fileInput.value?.click();
}

function appendFiles(files: File[], relDirOf: (f: File) => string): void {
  for (const f of files) items.value.push({ file: f, relDir: relDirOf(f) });
}

function onFilePicked(e: Event): void {
  const input = e.target as HTMLInputElement;
  appendFiles(Array.from(input.files ?? []), () => '');
  input.value = ''; // 允许再次选同一个文件
}

function onDirPicked(e: Event): void {
  const input = e.target as HTMLInputElement;
  appendFiles(Array.from(input.files ?? []), (f) => {
    // webkitRelativePath 形如 `相册/2024/a.jpg`；去掉文件名就是相对目录
    const rel = (f as File & { webkitRelativePath?: string }).webkitRelativePath ?? '';
    const cut = rel.lastIndexOf('/');
    return cut > 0 ? rel.slice(0, cut + 1) : '';
  });
  input.value = '';
}

function onDrop(e: DragEvent): void {
  dragOver.value = false;
  const dt = e.dataTransfer;
  if (!dt) return;
  const entries = Array.from(dt.items ?? [])
    .map((it) => (it as DataTransferItem & { webkitGetAsEntry?: () => FileSystemEntry | null }).webkitGetAsEntry?.())
    .filter((x): x is FileSystemEntry => !!x);

  if (entries.length === 0) {
    // 老浏览器不支持 entries：退化成只收文件（目录会以 0 字节假文件出现，这里过滤掉）
    appendFiles(
      Array.from(dt.files ?? []).filter((f) => !(f.size === 0 && !f.type)),
      () => ''
    );
    return;
  }

  for (const entry of entries) {
    if (entry.isDirectory) void walkDirectory(entry as FileSystemDirectoryEntry, `${entry.name}/`);
    else void readFile(entry as FileSystemFileEntry, '');
  }
}

function readFile(entry: FileSystemFileEntry, relDir: string): Promise<void> {
  return new Promise((resolve) => {
    entry.file(
      (f) => {
        items.value.push({ file: f, relDir });
        resolve();
      },
      () => resolve()
    );
  });
}

async function walkDirectory(entry: FileSystemDirectoryEntry, relDir: string): Promise<void> {
  const reader = entry.createReader();
  const children: FileSystemEntry[] = [];
  // readEntries 每次最多返回 100 条，必须一直读到空数组为止
  await new Promise<void>((resolve) => {
    const read = (): void => {
      reader.readEntries(
        (batch) => {
          if (!batch.length) return resolve();
          children.push(...batch);
          read();
        },
        () => resolve()
      );
    };
    read();
  });
  await Promise.all(
    children.map((c) =>
      c.isDirectory
        ? walkDirectory(c as FileSystemDirectoryEntry, `${relDir}${c.name}/`)
        : readFile(c as FileSystemFileEntry, relDir)
    )
  );
}

function clearAll(): void {
  items.value = [];
}

function fullLabel(it: StagedUpload): string {
  return `${it.relDir}${it.file.name}`;
}

function submit(): void {
  if (!items.value.length) return;
  emit('submit', items.value.slice());
}
</script>

<style scoped>
.up-bar {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 8px;
}
.up-total {
  font-size: 12px;
  color: var(--text-muted);
}
.grow {
  flex: 1;
}
.up-hidden {
  display: none;
}
.up-drop {
  border: 1.5px dashed var(--border-strong, var(--border-subtle));
  border-radius: var(--radius-md);
  min-height: 260px;
  max-height: 46vh;
  overflow: auto;
  padding: 16px;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  color: var(--text-secondary);
  font-size: 13px;
}
.up-drop.is-over {
  border-color: var(--accent-color);
  background: var(--accent-color-light);
}
.up-list {
  list-style: none;
  margin: 0;
  padding: 0;
  width: 100%;
  align-self: stretch;
}
.up-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 5px 4px;
  border-radius: var(--radius-sm);
}
.up-item:hover {
  background: var(--surface-hover);
}
.up-icon {
  color: var(--text-muted);
  flex: none;
}
.up-name {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--text-primary);
}
.up-size {
  flex: none;
  font-size: 11.5px;
  color: var(--text-muted);
  font-variant-numeric: tabular-nums;
}
.up-remove {
  flex: none;
  border: 0;
  background: transparent;
  color: var(--text-muted);
  cursor: pointer;
  padding: 0 4px;
  font-size: 12px;
}
.up-remove:hover {
  color: var(--error, #d03050);
}
.up-foot {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 10px;
}
.up-hint {
  flex: 1;
  font-size: 11.5px;
  color: var(--text-muted);
}
</style>

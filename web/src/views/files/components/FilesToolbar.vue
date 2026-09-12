<template>
  <div class="toolbar">
    <!-- 行 1：返回 + 路径 + 磁盘 + 刷新 -->
    <div class="bar bar-nav">
      <n-button size="small" class="nav-back" :disabled="!canGoUp" @click="emit('up')">
        <template #icon><n-icon><ArrowUpOutline /></n-icon></template>
        上级
      </n-button>
      <n-breadcrumb class="path-crumb">
        <n-breadcrumb-item
          v-for="(seg, i) in crumbs"
          :key="seg.path"
          :clickable="i < crumbs.length - 1"
          @click="onCrumb(i)"
        >
          {{ seg.label }}
        </n-breadcrumb-item>
      </n-breadcrumb>
      <div v-if="disks.length" class="disk-wrap" :title="diskTitle">
        <span class="disk-dot" :class="{ hot: primaryDiskHot }" />
        <span class="disk-text">{{ diskSummary }}</span>
      </div>
      <n-button size="small" quaternary class="icon-btn" title="刷新" @click="emit('refresh')">
        <template #icon><n-icon><RefreshOutline /></n-icon></template>
      </n-button>
    </div>

    <!-- 行 2：搜索 + 排序 + 新建 + 上传 -->
    <div class="bar bar-tools">
      <n-input
        :value="searchQuery"
        placeholder="搜索文件..."
        size="small"
        clearable
        class="search-input"
        @update:value="(v: string) => emit('update:searchQuery', v)"
        @keyup.enter="emit('search')"
        @clear="emit('clear-search')"
      >
        <template #prefix><n-icon><SearchOutline /></n-icon></template>
      </n-input>
      <n-select
        :value="sortBy"
        :options="SORT_OPTIONS"
        size="small"
        class="sort-select"
        @update:value="(v: string) => emit('update:sortBy', v)"
      />
      <n-dropdown :options="createOptions" trigger="click" @select="onCreate">
        <n-button size="small">新建</n-button>
      </n-dropdown>
      <n-upload
        :custom-request="customUpload"
        :show-file-list="false"
        multiple
        class="upload-btn"
      >
        <n-button size="small">上传</n-button>
      </n-upload>
    </div>
  </div>
</template>

<script setup lang="ts">
/**
 * 文件页顶栏：固定两行结构，避免 flex 换行把按钮挤成叠罗汉。
 * 行1 导航/路径/磁盘/刷新；行2 搜索/排序/新建/上传。
 * 由 FilesView 的 .files-header 固定，列表在 .files-body 内独立滚动。
 */
import { computed } from 'vue';
import type { UploadCustomRequestOptions } from 'naive-ui';
import { useAppStore } from '@/stores/app';
import { authHeaders, breadcrumbSegments, diskPercent, normalizePath } from '../filesShared';
import { ArrowUpOutline, SearchOutline, RefreshOutline } from '@vicons/ionicons5';

const props = defineProps<{
  currentPath: string;
  searchQuery: string;
  sortBy: string;
  disks?: any[];
}>();

const emit = defineEmits<{
  (e: 'update:searchQuery', v: string): void;
  (e: 'update:sortBy', v: string): void;
  (e: 'up'): void;
  (e: 'navigate', index: number): void;
  (e: 'search'): void;
  (e: 'clear-search'): void;
  (e: 'mkdir'): void;
  (e: 'touch'): void;
  (e: 'refresh'): void;
  (e: 'uploaded'): void;
  (e: 'upload-error'): void;
}>();

const SORT_OPTIONS = [
  { label: '名称', value: 'name' },
  { label: '大小', value: 'size' },
  { label: '日期', value: 'date' },
  { label: '类型', value: 'type' },
];

const createOptions = [
  { label: '新建文件夹', key: 'mkdir' },
  { label: '新建文件', key: 'touch' },
];

const appStore = useAppStore();
const crumbs = computed(() => breadcrumbSegments(props.currentPath));
const canGoUp = computed(() => crumbs.value.length > 1);
const disks = computed(() => props.disks || []);

const primaryDisk = computed(() => disks.value[0]);
const primaryDiskHot = computed(() =>
  primaryDisk.value ? diskPercent(primaryDisk.value) > 90 : false
);
const diskSummary = computed(() => {
  const d = primaryDisk.value;
  if (!d) return '';
  return `${d.available || '--'} / ${d.size || '--'}`;
});
const diskTitle = computed(() => {
  const d = primaryDisk.value;
  if (!d) return '';
  return `${d.label || d.mount}: ${d.available || ''} 可用 / ${d.size || ''}`;
});

function onCrumb(i: number) {
  if (i < 0 || i >= crumbs.value.length - 1) return;
  const crumb = crumbs.value[i];
  if (!crumb) return;
  const next = normalizePath(crumb.path);
  if (next !== normalizePath(props.currentPath)) emit('navigate', i);
}

function onCreate(key: string) {
  if (key === 'mkdir') emit('mkdir');
  else if (key === 'touch') emit('touch');
}

const uploadUrl = `${appStore.baseUrl || ''}/api/files/upload`;

/**
 * 自定义上传：走 fetch + 现算签名，而不是 n-upload 的原生 XHR。
 *
 * 为什么不能用 `:action` + `:headers`：那条路径由 naive-ui 自己发 XHR，**完全绕过 axios
 * 拦截器**，只能用静态请求头。而 core 的 AuthMiddleware 对 /api/files/upload 强制校验
 * `X-Timestamp` / `X-Nonce` / `X-Signature`，签名摘要是 `METHOD\nURI\nTS\nNONCE` ——
 * 时间戳与 nonce 每次都得变，静态 header 在结构上就无法满足，表现为**每次上传必然 444**。
 *
 * 这里用 filesShared 的 `authHeaders`（下载/预览同款）现算签名，方法传 POST。
 * 请求体用 FormData：浏览器会自动带上正确的 multipart boundary，core 用
 * `receiveMultipart()` 解析，字段名 `path` 对应目标目录，文件字段名不参与判定。
 */
// 类型注解写在**参数**上：naive-ui 的 `CustomRequest` 是 `(options) => void`，
// 把 options 类型标到函数变量上会得到「不可赋值给 CustomRequest」。返回 Promise 没问题
// （TS 允许把返回 Promise<void> 的函数赋给返回 void 的签名）。
const customUpload = async ({ file, onFinish, onError }: UploadCustomRequestOptions): Promise<void> => {
  const raw = file.file;
  if (!raw) {
    onError();
    emit('upload-error');
    return;
  }
  try {
    const form = new FormData();
    form.append('path', props.currentPath);
    form.append('file', raw, file.name);
    const res = await fetch(uploadUrl, {
      method: 'POST',
      headers: await authHeaders('/api/files/upload', 'POST'),
      body: form,
    });
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    onFinish();
    emit('uploaded');
  } catch {
    onError();
    emit('upload-error');
  }
};
</script>

<style scoped>
.toolbar {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 8px 10px;
  background: var(--bg-color, var(--surface-elevated, #fff));
}
.bar {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}
.bar-nav {
  min-height: 32px;
}
.bar-tools {
  min-height: 32px;
}
.nav-back {
  flex-shrink: 0;
}
.path-crumb {
  flex: 1 1 auto;
  min-width: 0;
  overflow: hidden;
  white-space: nowrap;
}
.path-crumb :deep(.n-breadcrumb-item) {
  font-size: 13px;
}
.path-crumb :deep(.n-breadcrumb-item:last-child) {
  font-weight: 600;
}
.disk-wrap {
  display: flex;
  align-items: center;
  gap: 6px;
  flex: 0 0 auto;
  padding: 0 8px;
  font-size: 11.5px;
  color: var(--text-muted);
  font-variant-numeric: tabular-nums;
  border-left: 1px solid var(--border-subtle);
}
.disk-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--accent-color, #4f8cff);
  flex-shrink: 0;
}
.disk-dot.hot {
  background: #e88080;
}
.disk-text {
  white-space: nowrap;
}
.icon-btn {
  width: 32px;
  padding: 0;
  flex-shrink: 0;
  margin-left: auto;
}
.search-input {
  flex: 1 1 auto;
  min-width: 0;
}
.sort-select {
  width: 88px;
  flex-shrink: 0;
}
.upload-btn {
  flex-shrink: 0;
}

/* 手机：磁盘文案收短，操作区仍单行 */
@media (max-width: 640px) {
  .toolbar {
    padding: 8px;
    gap: 8px;
  }
  .disk-wrap {
    border-left: none;
    padding: 0;
  }
  .disk-text {
    max-width: 7.5em;
    overflow: hidden;
    text-overflow: ellipsis;
  }
  .sort-select {
    width: 76px;
  }
}
</style>

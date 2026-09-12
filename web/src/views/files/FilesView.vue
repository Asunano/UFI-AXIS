<template>
  <div class="files-view">
    <!-- 固定顶栏：不参与列表滚动 -->
    <div class="files-header">
      <FilesToolbar
        v-model:search-query="searchQuery"
        v-model:sort-by="sortBy"
        :current-path="currentPath"
        :disks="disks"
        @up="goUp"
        @navigate="navigateTo"
        @search="doSearch"
        @clear-search="clearSearch"
        @mkdir="showMkdirModal = true"
        @touch="showTouchModal = true"
        @refresh="refreshAll"
        @uploaded="onUploadDone"
        @upload-error="onUploadError"
      />
    </div>

    <!-- 列表滚动区：顶栏下方独立滚动，内容不会再钻到顶栏底下 -->
    <div class="files-body">
      <n-alert v-if="storageDenied" type="warning" :show-icon="false" class="perm-alert">
        Core 未获得「所有文件访问权限」，文件列表可能为空或操作失败。请在设备端 UFI-AXIS 应用中授予该权限后点刷新。
      </n-alert>

      <div v-if="clipboard.path" class="clipboard-bar">
        <span>已{{ clipboard.mode === 'copy' ? '复制' : '剪切' }}: {{ clipboard.name }}</span>
        <n-button size="tiny" type="primary" @click="pasteHere">粘贴到此处</n-button>
        <n-button size="tiny" @click="clearClipboard">取消</n-button>
      </div>

      <FileListPanel
        :files="displayFiles"
        :loading="loading"
        :selected-path="selectedPath"
        :search-info="searchInfo"
        @select="selectFile"
        @open="openFile"
        @preview="openPreview"
        @download="downloadFile"
        @action="handleAction"
        @clear-search="clearSearch"
      />
    </div>

    <NameInputModal
      v-model:show="showMkdirModal"
      title="新建文件夹"
      placeholder="文件夹名称"
      confirm-text="创建"
      :loading="actionLoading"
      @confirm="doMkdir"
    />
    <NameInputModal
      v-model:show="showTouchModal"
      title="新建文件"
      placeholder="文件名（如 notes.txt）"
      confirm-text="创建"
      :loading="actionLoading"
      @confirm="doTouch"
    />
    <NameInputModal
      v-model:show="showRenameModal"
      title="重命名"
      :initial-value="renameInitial"
      :loading="actionLoading"
      @confirm="doRename"
    />

    <FileInfoModal v-model:show="showInfoModal" :info="fileInfo" />

    <!-- 懒加载弹窗一律走 useLazyModal，否则首帧 show 就是 true，进出场动画全丢 -->
    <component
      :is="previewComponent"
      v-if="previewComponent"
      :show="previewShow"
      :file="previewFile"
      @update:show="previewModal.setShow"
    />
    <component
      :is="editorComponent"
      v-if="editorComponent"
      :show="editorShow"
      :title="editorTitle"
      :content="editorContent"
      :read-only="editorReadOnly"
      :saving="actionLoading"
      @update:show="editorModal.setShow"
      @update:content="(v: string) => (editorContent = v)"
      @save="saveTextFile"
    />
  </div>
</template>

<script setup lang="ts">
/**
 * 文件管理页：状态、取数与写操作的编排；渲染拆到 components/ 下。
 *
 * 留在这里的两条时序，挪进弹窗就会变形，见各弹窗的文件头：
 *   · 预览的 100MB 劝退要在**打开之前**判（超限根本不开弹窗）
 *   · 文本编辑的读文件也在打开之前（读失败只弹错误、不开编辑器）
 */
import { ref, reactive, computed, onMounted } from 'vue';
import { useMessage, useDialog } from 'naive-ui';
import { getApiClient } from '@/composables/useApi';
import { useAppStore } from '@/stores/app';
import { formatBytes } from '@/composables/utils';
import { useLazyModal } from '@/composables/useLazyModal';
import FilesToolbar from './components/FilesToolbar.vue';
import FileListPanel from './components/FileListPanel.vue';
import NameInputModal from './components/modals/NameInputModal.vue';
import FileInfoModal from './components/modals/FileInfoModal.vue';
import {
  PREVIEW_MAX_BYTES,
  authHeaders,
  breadcrumbSegments,
  canPreview,
  isAllowedRoot,
  normalizePath,
  openActionOf,
  PRIMARY_STORAGE,
  type FileEntry,
} from './filesShared';

const message = useMessage();
const dialog = useDialog();
const api = getApiClient();
const appStore = useAppStore();

// ── 状态 ──
const currentPath = ref(PRIMARY_STORAGE);
const files = ref<FileEntry[]>([]);
const loading = ref(false);
const selectedPath = ref('');
const searchQuery = ref('');
const searchResults = ref<FileEntry[] | null>(null);
const lastQuery = ref('');
const sortBy = ref('name');
const disks = ref<any[]>([]);
const actionLoading = ref(false);
const fileInfo = ref<FileEntry | null>(null);
const clipboard = reactive({ path: '', name: '', mode: '' as '' | 'copy' | 'move' });
const storageDenied = ref(false);

// 弹窗状态
const showMkdirModal = ref(false);
const showTouchModal = ref(false);
const showRenameModal = ref(false);
const showInfoModal = ref(false);
const renameTarget = ref('');
const renameInitial = ref('');
const previewFile = ref<FileEntry | null>(null);
const editorContent = ref('');
const editorPath = ref('');
const editorTitle = ref('');
const editorReadOnly = ref(false);

const previewModal = useLazyModal(() => import('./components/modals/PreviewModal.vue'));
const previewComponent = previewModal.component;
const previewShow = previewModal.show;
const editorModal = useLazyModal(() => import('./components/modals/TextEditorModal.vue'));
const editorComponent = editorModal.component;
const editorShow = editorModal.show;

/** null = 正常目录浏览；非 null = 搜索结果视图（空态文案要据此区分） */
const searchInfo = computed(() =>
  searchResults.value === null ? null : { query: lastQuery.value, count: searchResults.value.length }
);

const displayFiles = computed(() => {
  const src = searchResults.value !== null ? searchResults.value : files.value;
  const sorted = [...src].sort((a, b) => {
    // 文件夹始终在前
    if (a.isDirectory !== b.isDirectory) return a.isDirectory ? -1 : 1;
    switch (sortBy.value) {
      case 'size':
        return (a.size || 0) - (b.size || 0);
      case 'date':
        return (b.lastModified || 0) - (a.lastModified || 0);
      case 'type': {
        const ea = a.name.split('.').pop() || '';
        const eb = b.name.split('.').pop() || '';
        return ea.localeCompare(eb);
      }
      default:
        return a.name.localeCompare(b.name);
    }
  });
  return sorted;
});

// ── 文件加载 ──
// core 的多数写操作返回 HTTP 200 + { success: false, error: "..." }，必须显式判断
function assertOk(data: any, fallback: string): boolean {
  if (data?.success === false) {
    message.error(data.error || fallback);
    return false;
  }
  return true;
}

async function loadFiles() {
  loading.value = true;
  try {
    const { data } = await api.get('/api/files/list', { params: { path: currentPath.value } });
    files.value = data.files || [];
    if (data.error) message.warning(data.error);
  } catch {
    message.error('加载文件列表失败');
  } finally {
    loading.value = false;
  }
}

async function loadStorageStatus() {
  try {
    const { data } = await api.get('/api/files/status');
    storageDenied.value = data?.isExternalStorageManager === false;
  } catch {
    /* 静默 */
  }
}

async function loadDiskUsage() {
  try {
    const { data } = await api.get('/api/files/disk-usage');
    disks.value = data.disks || [];
  } catch {
    /* 静默 */
  }
}

// ── 导航 ──
/** 走统一白名单：禁止 `/storage`、`/storage/emulated` 这类中间层。 */
function enterPath(nextRaw: string, { fromCrumb = false } = {}): boolean {
  const next = normalizePath(nextRaw);
  if (!isAllowedRoot(next)) {
    message.warning(fromCrumb ? '该级目录不可直接进入' : '该目录不在可访问范围内');
    return false;
  }
  if (next === currentPath.value) return true;
  currentPath.value = next;
  loadFiles();
  return true;
}

function navigateTo(index: number) {
  const crumbs = breadcrumbSegments(currentPath.value);
  const crumb = crumbs[index];
  if (!crumb) return;
  enterPath(crumb.path, { fromCrumb: true });
}

function goUp() {
  const crumbs = breadcrumbSegments(currentPath.value);
  if (crumbs.length <= 1) {
    message.warning('已到内部存储根目录');
    return;
  }
  const parent = crumbs[crumbs.length - 2];
  if (parent) enterPath(parent.path);
}

/**
 * 双击「打开」——**不再默认下载**。
 * 目录进入；媒体走预览弹窗；文本进编辑器；APK 弹安装确认；
 * 其余（压缩包/文档/未知类型）打开信息弹窗并提示用「下载」。
 */
function openFile(f: FileEntry) {
  if (f.isDirectory) {
    searchResults.value = null;
    searchQuery.value = '';
    enterPath(f.path);
    return;
  }
  switch (openActionOf(f)) {
    case 'preview':
      openPreview(f);
      break;
    case 'text':
      openTextEditor(f.path, f.name);
      break;
    case 'install':
      installApk(f);
      break;
    case 'info':
      message.info(`${f.name}：该类型暂不支持在线预览，请使用「下载」后本地打开`);
      showInfo(f);
      break;
    default:
      break;
  }
}

function selectFile(f: FileEntry) {
  selectedPath.value = selectedPath.value === f.path ? '' : f.path;
}

// ── 搜索 ──
async function doSearch() {
  if (!searchQuery.value.trim()) return;
  lastQuery.value = searchQuery.value;
  try {
    const { data } = await api.get('/api/files/search', {
      params: { path: currentPath.value, query: searchQuery.value, depth: 3 },
    });
    searchResults.value = data.files || [];
  } catch {
    message.error('搜索失败');
  }
}

function clearSearch() {
  searchQuery.value = '';
  searchResults.value = null;
  lastQuery.value = '';
}

// ── 每行动作 ──
function handleAction(key: string, f: FileEntry) {
  switch (key) {
    case 'install':
      installApk(f);
      break;
    case 'open':
      openFile(f);
      break;
    case 'preview':
      openPreview(f);
      break;
    case 'download':
      downloadFile(f);
      break;
    case 'rename':
      startRename(f);
      break;
    case 'copy':
      clipboard.path = f.path;
      clipboard.name = f.name;
      clipboard.mode = 'copy';
      break;
    case 'cut':
      clipboard.path = f.path;
      clipboard.name = f.name;
      clipboard.mode = 'move';
      break;
    case 'delete':
      confirmDelete(f);
      break;
    case 'info':
      showInfo(f);
      break;
  }
}

function downloadFile(f: FileEntry) {
  const uri = `/api/files/download?path=${encodeURIComponent(f.path)}`;
  const url = `${appStore.baseUrl || ''}${uri}`;
  // 需要带 auth header，用 fetch 下载；core 对 >50MB / 不存在的文件返回 JSON 错误，
  // 不检查 res.ok 会把错误 JSON 当文件内容下载下来
  authHeaders(uri)
    .then((headers) => fetch(url, { headers }))
    .then(async (res) => {
      if (!res.ok) {
        const detail = await res.json().catch(() => null);
        throw new Error(detail?.error || `HTTP ${res.status}`);
      }
      return res.blob();
    })
    .then((blob) => {
      const a = document.createElement('a');
      const objUrl = URL.createObjectURL(blob);
      a.href = objUrl;
      a.download = f.name;
      // Firefox 要求 <a> 在文档中才会触发下载；且同步 revoke 会让下载被取消，
      // 必须等本轮任务结束后再清理
      a.style.display = 'none';
      document.body.appendChild(a);
      a.click();
      setTimeout(() => {
        a.remove();
        URL.revokeObjectURL(objUrl);
      }, 0);
    })
    .catch((e) => message.error(e?.message ? `下载失败: ${e.message}` : '下载失败'));
}

function installApk(f: FileEntry) {
  dialog.info({
    title: '确认安装',
    content: `确定要安装 "${f.name}" 吗？`,
    positiveText: '安装',
    negativeText: '取消',
    onPositiveClick: async () => {
      const m = message.loading('正在发起安装...', { duration: 0 });
      try {
        const { data } = await api.post('/api/apps/install', { path: f.path });
        if (data.success) {
          message.success('已提交安装请求');
        } else {
          message.error(`安装失败: ${data.message}`);
        }
      } catch (e: any) {
        message.error(`安装出错: ${e.response?.data?.message || e.message}`);
      } finally {
        m.destroy();
      }
    },
  });
}

function startRename(f: FileEntry) {
  renameTarget.value = f.path;
  renameInitial.value = f.name;
  showRenameModal.value = true;
}

async function doRename(name: string) {
  if (!name.trim()) return;
  if (name.includes('/')) {
    message.error('名称不能包含 /');
    return;
  }
  actionLoading.value = true;
  try {
    const parent = renameTarget.value.substring(0, renameTarget.value.lastIndexOf('/'));
    const newPath = `${parent}/${name}`;
    const { data } = await api.post('/api/files/rename', { old_path: renameTarget.value, new_path: newPath });
    if (!assertOk(data, '重命名失败')) return;
    message.success('已重命名');
    showRenameModal.value = false;
    loadFiles();
  } catch {
    message.error('重命名失败');
  } finally {
    actionLoading.value = false;
  }
}

function confirmDelete(f: FileEntry) {
  dialog.warning({
    title: '确认删除',
    content: `确定要删除 "${f.name}" 吗？${f.isDirectory ? '文件夹及其所有内容将被删除。' : ''}`,
    positiveText: '删除',
    negativeText: '取消',
    onPositiveClick: async () => {
      try {
        const { data } = await api.post('/api/files/delete', { path: f.path });
        if (!assertOk(data, '删除失败')) return;
        message.success('已删除');
        loadFiles();
        loadDiskUsage();
      } catch (e: any) {
        message.error(e.response?.data?.error || '删除失败');
      }
    },
  });
}

async function showInfo(f: FileEntry) {
  try {
    const { data } = await api.get('/api/files/info', { params: { path: f.path } });
    fileInfo.value = data;
    showInfoModal.value = true;
  } catch {
    message.error('获取信息失败');
  }
}

async function doMkdir(name: string) {
  if (!name.trim()) return;
  if (name.includes('/')) {
    message.error('名称不能包含 /');
    return;
  }
  actionLoading.value = true;
  try {
    const { data } = await api.post('/api/files/mkdir', { path: `${currentPath.value}/${name}` });
    if (!assertOk(data, '创建失败')) return;
    message.success('文件夹已创建');
    showMkdirModal.value = false;
    loadFiles();
  } catch {
    message.error('创建失败');
  } finally {
    actionLoading.value = false;
  }
}

// core /api/files/touch 会 mkdirs 缺失的父目录；HTTP 200 也可能 success:false
async function doTouch(name: string) {
  if (!name.trim()) {
    message.warning('请输入文件名');
    return;
  }
  if (name.includes('/')) {
    message.error('名称不能包含 /');
    return;
  }
  actionLoading.value = true;
  try {
    const { data } = await api.post('/api/files/touch', { path: `${currentPath.value}/${name.trim()}` });
    if (!assertOk(data, '创建失败')) return;
    message.success('文件已创建');
    showTouchModal.value = false;
    loadFiles();
  } catch (e: any) {
    message.error(e.response?.data?.error || '创建失败');
  } finally {
    actionLoading.value = false;
  }
}

// ── 媒体预览 ──
// blob 与 object URL 的生命周期在 PreviewModal 里；这里只做「能不能预览」和大小劝退，
// 因为两者都必须在打开弹窗之前判定。
function openPreview(f: FileEntry) {
  if (!canPreview(f)) return;
  // 整文件读入内存，超过 100MB 直接劝退，避免浏览器内存爆掉
  if (typeof f.size === 'number' && f.size > PREVIEW_MAX_BYTES) {
    message.warning(`文件超过 100MB（${formatBytes(f.size)}），请下载后本地打开`);
    return;
  }
  // 先赋值再 open()：open() 会先挂载一帧（show 仍为 false），子组件的 show-watch
  // 下一帧才触发，那时 file 已经就位
  previewFile.value = f;
  previewModal.open();
}

// ── 剪贴板 ──
async function pasteHere() {
  if (!clipboard.path) return;
  const destination = `${currentPath.value}/${clipboard.name}`;
  if (destination === clipboard.path) {
    message.warning('源和目标相同');
    return;
  }
  try {
    if (clipboard.mode === 'copy') {
      const { data } = await api.post('/api/files/copy', { source: clipboard.path, destination });
      if (!assertOk(data, '复制失败')) return;
      message.success('已复制');
    } else {
      const { data } = await api.post('/api/files/move', { source: clipboard.path, destination });
      if (!assertOk(data, '移动失败')) return;
      message.success('已移动');
    }
    clearClipboard();
    loadFiles();
  } catch {
    message.error('操作失败');
  }
}

function clearClipboard() {
  clipboard.path = '';
  clipboard.name = '';
  clipboard.mode = '';
}

function onUploadDone() {
  message.success('上传完成');
  loadFiles();
  loadDiskUsage();
}

function onUploadError() {
  message.error('上传失败（目标路径不合法或写入失败）');
}

function refreshAll() {
  loadStorageStatus();
  loadFiles();
  loadDiskUsage();
}

// ── 文本编辑器 ──
async function openTextEditor(path: string, name: string) {
  try {
    const { data } = await api.post('/api/files/read', { path });
    const content = data.content || '';
    // core /read 对二进制/过大/非文件都返回 200 + content 为占位文本，
    // 直接编辑保存会把占位文本写回去从而损坏文件；服务端用 truncated 显式标记这种情况
    editorReadOnly.value = data.truncated === true;
    editorContent.value = content;
    editorPath.value = path;
    editorTitle.value = name;
    editorModal.open();
  } catch {
    message.error('读取文件失败');
  }
}

async function saveTextFile() {
  if (editorReadOnly.value) return;
  actionLoading.value = true;
  try {
    const { data } = await api.post('/api/files/write', { path: editorPath.value, content: editorContent.value });
    if (!assertOk(data, '保存失败')) return;
    message.success('已保存');
    editorModal.setShow(false);
    loadFiles();
  } catch {
    message.error('保存失败');
  } finally {
    actionLoading.value = false;
  }
}

onMounted(() => {
  loadStorageStatus();
  loadFiles();
  loadDiskUsage();
});
</script>

<style scoped>
/**
 * 布局：
 * - 父级 n-layout-scroll-container 仍可滚，但本页用 height:100% 撑满可视区，
 *   顶栏固定、列表在 .files-body 内滚，避免内容穿过顶栏。
 */
.files-view {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
  gap: 10px;
}
.files-header {
  flex: 0 0 auto;
  z-index: 20;
  border: 1px solid var(--border-subtle);
  border-radius: 10px;
  background: var(--bg-color, var(--surface-elevated, #fff));
  box-shadow: 0 1px 2px rgb(0 0 0 / 4%);
}
.files-body {
  flex: 1 1 auto;
  min-height: 0;
  overflow-y: auto;
  overscroll-behavior: contain;
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding-bottom: 4px;
}
.clipboard-bar {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 12px;
  background: var(--accent-color-light);
  border-radius: 8px;
  font-size: 13px;
  flex-shrink: 0;
}
.perm-alert {
  margin-bottom: 0;
  flex-shrink: 0;
}
</style>

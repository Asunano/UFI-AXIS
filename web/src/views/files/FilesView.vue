<template>
  <div class="files-view">
    <!-- 固定顶栏：不参与列表滚动 -->
    <div class="files-header">
      <FilesToolbar
        v-model:search-query="searchQuery"
        v-model:sort-by="sortBy"
        :current-path="currentPath"
        :disks="disks"
        :is-mobile="isMobile"
        :can-back="canBack"
        :can-forward="canForward"
        :sel-mode="selMode"
        :sel-count="selectedItems.length"
        :total-count="visibleFiles.length"
        :clipboard-count="clipboard.paths.length"
        :hidden-shown="showHidden"
        @nav="onNav"
        @navigate="navigateTo"
        @open-search="showSearchModal = true"
        @create="onCreate"
        @tool="onTool"
        @upload="onPickUpload"
      />
    </div>

    <!-- 列表滚动区：顶栏下方独立滚动，内容不会再钻到顶栏底下 -->
    <div class="files-body">
      <n-alert v-if="storageDenied" type="warning" :show-icon="false" class="perm-alert">
        Core 未获得「所有文件访问权限」，文件列表可能为空或操作失败。请在设备端 UFI-AXIS 应用中授予该权限后点刷新。
      </n-alert>

      <div v-if="clipboard.paths.length" class="clipboard-bar">
        <span class="cb-text">
          已{{ clipboard.mode === 'copy' ? '复制' : '剪切' }} {{ clipboard.paths.length }} 项：{{
            clipboard.names.slice(0, 2).join('、')
          }}{{ clipboard.paths.length > 2 ? ' 等' : '' }}
        </span>
        <n-button size="tiny" type="primary" :loading="pasting" @click="pasteHere">粘贴到此处</n-button>
        <n-button size="tiny" @click="clearClipboard">取消</n-button>
      </div>

      <FileListPanel
        :files="visibleFiles"
        :loading="loading"
        :selected-path="selectedPath"
        :sel-mode="selMode"
        :selected-paths="selectedPaths"
        @select="selectFile"
        @open="openFile"
        @toggle-select="toggleSelect"
        @preview="openPreview"
        @download="downloadFile"
        @action="handleAction"
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

    <FileInfoModal
      v-model:show="showInfoModal"
      :info="fileInfo"
      :primary-action="fileInfoPrimary"
      @action="onInfoAction"
    />

    <ChecksumModal v-model:show="showChecksumModal" :result="checksumResult" />

    <SearchModal
      v-model:show="showSearchModal"
      :current-path="currentPath"
      :initial-query="searchQuery"
      :is-mobile="isMobile"
      @pick="onSearchPick"
    />

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
    <!-- 上传进度：常驻右下角浮层，队列与 XHR 在 useFileUpload 里 -->
    <UploadProgressPanel
      :open="uploadPanelOpen"
      :tasks="uploadTasks"
      :uploading="uploadBusy"
      :overall-percent="uploadPercent"
      :done-count="uploadDone"
      :failed-count="uploadFailed"
      :percent-of="upload.percentOf"
      @cancel="upload.cancel"
      @cancel-all="upload.cancelAll"
      @clear="upload.clearFinished"
      @close="upload.closePanel"
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
 *
 * ── 关于「批量」必须知道的前提 ──
 * core 的 `/api/files/{copy,move,delete}` **只收单个 source**，没有批量端点，
 * 所以多选后的复制/移动/删除都是**前端串行循环**（见 `runBatch` / `pasteHere`）。
 * 唯一的例外是 `/compress`，它收 `paths: []`，多选压缩是**一次请求**。
 *
 * ── 关于「覆盖」必须知道的前提 ──
 * `/copy` 内部用 `REPLACE_EXISTING`（同名直接覆盖），`/move` 遇到目标已存在直接失败。
 * 两者都不做改名，所以**冲突规避必须在客户端完成**（见 `uniqueChildName`）——
 * 否则粘贴到已有同名文件的目录就是「要么丢数据、要么报错」。
 */
import { ref, reactive, computed, onMounted, onUnmounted } from 'vue';
import { useMessage, useDialog } from 'naive-ui';
import { getApiClient } from '@/composables/useApi';
import { useAppStore } from '@/stores/app';
import { formatBytes } from '@/composables/utils';
import { useLazyModal } from '@/composables/useLazyModal';
import { useIsMobile } from '@/composables/useIsMobile';
import FilesToolbar from './components/FilesToolbar.vue';
import FileListPanel from './components/FileListPanel.vue';
import UploadProgressPanel from './components/UploadProgressPanel.vue';
import SearchModal from './components/modals/SearchModal.vue';
import NameInputModal from './components/modals/NameInputModal.vue';
import FileInfoModal from './components/modals/FileInfoModal.vue';
import ChecksumModal from './components/modals/ChecksumModal.vue';
import { useFileUpload } from './useFileUpload';
import {
  PREVIEW_MAX_BYTES,
  authHeaders,
  breadcrumbSegments,
  canPreview,
  isAllowedRoot,
  isHiddenName,
  normalizePath,
  openActionOf,
  parentPathOf,
  PRIMARY_STORAGE,
  previewKindOf,
  primaryFileActionOf,
  uniqueChildName,
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
const sortBy = ref('name');
const disks = ref<any[]>([]);
const actionLoading = ref(false);
const fileInfo = ref<FileEntry | null>(null);
const showChecksumModal = ref(false);
const checksumResult = ref<{ path: string; algorithms: Record<string, string> } | null>(null);
const storageDenied = ref(false);
const showSearchModal = ref(false);
/** 视口判据统一走 composable（matchMedia，阈值与全站 768px 一致） */
const isMobile = useIsMobile();
const pasting = ref(false);

/** 隐藏文件开关。core `/list` 不过滤隐藏项，过滤只能做在客户端 */
const showHidden = ref(false);

/**
 * 剪贴板 —— **多选模型**：复制/剪切都往里写一组路径，粘贴时逐个落到目标目录。
 * 与单文件时代（只存一个 path/name）的差别只在粒度，落盘动作完全一样。
 */
const clipboard = reactive({ paths: [] as string[], names: [] as string[], mode: '' as '' | 'copy' | 'move' });

// ── 多选 ──
const selMode = ref(false);
const selectedPaths = ref<string[]>([]);

// ── 导航历史（core 没有历史接口，纯粹是客户端栈）──
const history = ref<string[]>([PRIMARY_STORAGE]);
const historyIndex = ref(0);
const canBack = computed(() => historyIndex.value > 0);
const canForward = computed(() => historyIndex.value < history.value.length - 1);

/** 搜索跳转后要落到的文件（等目录加载完再选中，否则列表里还没有它） */
const pendingSelectPath = ref('');

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

/** 展示用列表：过滤隐藏项 → 按 sortBy 排序（core `/list` 只保证「目录在前、名称升序」） */
const visibleFiles = computed(() => {
  const src = showHidden.value ? files.value : files.value.filter((f) => !isHiddenName(f.name));
  return [...src].sort((a, b) => {
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
});

/** 已勾选项（以「当前可见」为准：隐藏项被过滤后不该还能被批量操作命中） */
const selectedItems = computed(() => visibleFiles.value.filter((f) => selectedPaths.value.includes(f.path)));

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
    // 搜索跳转：目录到位后再选中目标文件
    if (pendingSelectPath.value) {
      const target = pendingSelectPath.value;
      pendingSelectPath.value = '';
      if (files.value.some((f) => f.path === target)) selectedPath.value = target;
    }
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
/** 换目录：清掉单选与多选（跨目录的选中集合没有意义），再拉列表 */
function applyPath(p: string) {
  currentPath.value = p;
  selectedPath.value = '';
  exitSelectMode();
  loadFiles();
}

/** 走统一白名单：禁止 `/storage`、`/storage/emulated` 这类中间层。 */
function enterPath(nextRaw: string, { push = true } = {}): boolean {
  const next = normalizePath(nextRaw);
  if (!isAllowedRoot(next)) {
    message.warning('该目录不在可访问范围内');
    return false;
  }
  if (next === currentPath.value) return true;
  if (push) {
    // 在当前位置之后重新开分支：history 被截断，前进按钮随之失效
    history.value = history.value.slice(0, historyIndex.value + 1);
    history.value.push(next);
    historyIndex.value = history.value.length - 1;
  }
  applyPath(next);
  return true;
}

function goBack() {
  if (!canBack.value) return;
  historyIndex.value -= 1;
  applyPath(history.value[historyIndex.value]!);
}

function goForward() {
  if (!canForward.value) return;
  historyIndex.value += 1;
  applyPath(history.value[historyIndex.value]!);
}

function navigateTo(index: number) {
  const crumb = breadcrumbSegments(currentPath.value)[index];
  if (crumb) enterPath(crumb.path);
}

function onNav(key: 'back' | 'forward' | 'refresh') {
  if (key === 'back') goBack();
  else if (key === 'forward') goForward();
  else refreshAll();
}

/**
 * 双击「打开」——**不下载、也不改文件系统**。
 * 目录进入；媒体走预览弹窗；文本进编辑器；APK 弹安装确认；
 * 其余（压缩包/文档/未知类型）打开详情弹窗，动作交给弹窗里的主操作按钮。
 *
 * 压缩包刻意不在这里解压：解压会改设备文件系统，而双击是一碰就触发、
 * 又没有确认的手势。与 app 的 `resolveOpenRoute` 同一条口径。
 */
function openFile(f: FileEntry) {
  if (f.isDirectory) {
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
      showInfo(f);
      break;
    default:
      break;
  }
}

function selectFile(f: FileEntry) {
  selectedPath.value = selectedPath.value === f.path ? '' : f.path;
}

// ── 多选 ──
function enterSelectMode() {
  selMode.value = true;
  selectedPath.value = '';
}
function exitSelectMode() {
  selMode.value = false;
  selectedPaths.value = [];
}
function toggleSelect(f: FileEntry) {
  const i = selectedPaths.value.indexOf(f.path);
  if (i < 0) selectedPaths.value = [...selectedPaths.value, f.path];
  else selectedPaths.value = selectedPaths.value.filter((p) => p !== f.path);
}

/** 排序方式循环切换（「更多」菜单里的那项；行2 的下拉是直接选） */
const SORT_CYCLE = ['name', 'size', 'date', 'type'];

function onCreate(key: 'mkdir' | 'touch') {
  if (key === 'mkdir') showMkdirModal.value = true;
  else showTouchModal.value = true;
}

function onTool(key: string) {
  switch (key) {
    case 'multi':
      enterSelectMode();
      break;
    case 'exit-select':
      exitSelectMode();
      break;
    case 'select-all':
      enterSelectMode();
      selectedPaths.value = visibleFiles.value.map((f) => f.path);
      break;
    case 'invert': {
      enterSelectMode();
      const cur = new Set(selectedPaths.value);
      selectedPaths.value = visibleFiles.value.map((f) => f.path).filter((p) => !cur.has(p));
      break;
    }
    case 'copy-selected':
      setClipboard('copy', selectedItems.value);
      break;
    case 'move-selected':
      setClipboard('move', selectedItems.value);
      break;
    case 'compress-selected':
      compressSelected();
      break;
    case 'delete-selected':
      confirmDeleteSelected();
      break;
    case 'paste':
      pasteHere();
      break;
    case 'clear-clipboard':
      clearClipboard();
      break;
    case 'cycle-sort': {
      const i = SORT_CYCLE.indexOf(sortBy.value);
      sortBy.value = SORT_CYCLE[(i + 1) % SORT_CYCLE.length]!;
      break;
    }
    case 'toggle-hidden':
      showHidden.value = !showHidden.value;
      message.info(showHidden.value ? '已显示隐藏文件' : '已隐藏隐藏文件');
      break;
    case 'refresh':
      refreshAll();
      break;
    default:
      break;
  }
}

// ── 搜索 ──
/**
 * 搜索结果落地。
 * 目录 → 直接进入；文件 → 进入其所在目录并选中它。
 * 后者才是多级目录下真正要解决的事：搜到了却过不去，等于没搜到。
 */
function onSearchPick(f: FileEntry) {
  const dir = f.isDirectory ? f.path : parentPathOf(f.path);
  if (!isAllowedRoot(dir)) {
    message.warning('该位置不在可访问范围内');
    return;
  }
  searchQuery.value = '';
  if (!f.isDirectory) pendingSelectPath.value = f.path;
  enterPath(dir);
}

// ── 批量操作 ──
function reportBatch(verb: string, ok: number, failed: string[]) {
  if (!failed.length) {
    message.success(`${verb}完成（${ok} 项）`);
    return;
  }
  const head = failed.slice(0, 3).join('、') + (failed.length > 3 ? ' 等' : '');
  if (ok === 0) message.error(`${verb}失败：${head}`);
  else message.warning(`${verb}完成 ${ok} 项，失败 ${failed.length} 项：${head}`);
}

/**
 * 串行执行批量动作。
 *
 * 为什么串行而不是 `Promise.all`：core 是跑在随身 WiFi 这类弱设备上的单进程，
 * 同时打 N 个文件操作只会让 IO 队列互相拖慢，且并发下的失败原因更难归因。
 * 串行 + 进度提示慢一点，但每一步的成败都能如实汇报。
 */
async function runBatch(targets: FileEntry[], verb: string, one: (f: FileEntry) => Promise<boolean>) {
  if (!targets.length) return;
  const m = message.loading(`${verb}中… 0/${targets.length}`, { duration: 0 });
  let ok = 0;
  const failed: string[] = [];
  for (let i = 0; i < targets.length; i++) {
    const f = targets[i]!;
    m.content = `${verb}中… ${i + 1}/${targets.length}`;
    try {
      if (await one(f)) ok += 1;
      else failed.push(f.name);
    } catch {
      failed.push(f.name);
    }
  }
  m.destroy();
  reportBatch(verb, ok, failed);
  exitSelectMode();
  await loadFiles();
  loadDiskUsage();
}

/** 多选压缩：core `/compress` 收 `paths: []`，**一次请求**，不必循环 */
async function compressSelected() {
  const items = selectedItems.value;
  if (!items.length) return;
  const m = message.loading('正在压缩…', { duration: 0 });
  try {
    const { data } = await api.post('/api/files/compress', { paths: items.map((i) => i.path) });
    m.destroy();
    if (!assertOk(data, '压缩失败')) {
      message.error(data?.error || '压缩失败（目标同名 zip 可能已存在）');
      return;
    }
    message.success(`已生成 ${data.path}`);
    exitSelectMode();
    await loadFiles();
    loadDiskUsage();
  } catch (e: any) {
    m.destroy();
    message.error(e.response?.data?.error || '压缩失败');
  }
}

function confirmDeleteSelected() {
  const items = selectedItems.value;
  if (!items.length) return;
  const hasDir = items.some((i) => i.isDirectory);
  dialog.warning({
    title: '确认删除',
    content: `确定删除选中的 ${items.length} 项吗？${hasDir ? '其中的文件夹及其所有内容都会被删除。' : ''}`,
    positiveText: '删除',
    negativeText: '取消',
    onPositiveClick: () =>
      runBatch(items, '删除', async (f) => {
        const { data } = await api.post('/api/files/delete', { path: f.path });
        return data?.success !== false;
      }),
  });
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
      setClipboard('copy', [f]);
      break;
    case 'cut':
      setClipboard('move', [f]);
      break;
    case 'delete':
      confirmDelete(f);
      break;
    case 'info':
      showInfo(f);
      break;
    case 'extract':
      extractArchive(f);
      break;
    case 'copy-path':
      copyPath(f);
      break;
    case 'checksum':
      openChecksum(f);
      break;
    case 'compress':
      compressItem(f);
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
    const parent = parentPathOf(renameTarget.value);
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

/** 详情弹窗右侧唯一主操作（apk→安装、zip→解压、可预览→打开，其余为 null） */
const fileInfoPrimary = computed(() => (fileInfo.value ? primaryFileActionOf(fileInfo.value) : null));

/**
 * 弹窗主操作复用 `handleAction` —— 它同时也是行内「⋯」菜单的分发口。
 * 两处入口共用一个分发，才不会出现「菜单里的解压」和「弹窗里的解压」行为不一致。
 */
function onInfoAction(key: string) {
  const f = fileInfo.value;
  if (f) handleAction(key, f);
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
  // 100MB 劝退只对「整份读进内存」的那几类（图片/文本）成立。
  // 2026-09-14：音视频已改成凭票流式（浏览器按 Range 边下边播，见 PreviewModal.requestStreamUrl），
  // 内存占用与文件大小无关 —— 再卡这条上限就等于"电影永远进不了预览"。
  const kind = previewKindOf(f.name || '');
  const streamed = kind === 'audio' || kind === 'video';
  if (!streamed && typeof f.size === 'number' && f.size > PREVIEW_MAX_BYTES) {
    message.warning(`文件超过 100MB（${formatBytes(f.size)}），请下载后本地打开`);
    return;
  }
  // 先赋值再 open()：open() 会先挂载一帧（show 仍为 false），子组件的 show-watch
  // 下一帧才触发，那时 file 已经就位
  previewFile.value = f;
  previewModal.open();
}

// ── 剪贴板 ──
function setClipboard(mode: 'copy' | 'move', items: FileEntry[]) {
  if (!items.length) return;
  clipboard.mode = mode;
  clipboard.paths = items.map((i) => i.path);
  clipboard.names = items.map((i) => i.name);
  // 剪贴板是「跨目录」的一步操作，选完就该退出多选去目标目录
  exitSelectMode();
  message.success(`已${mode === 'copy' ? '复制' : '剪切'} ${items.length} 项，请进入目标目录后粘贴`);
}

/**
 * 粘贴：把剪贴板里的每一项落到当前目录。
 *
 * 目标名一律走 `uniqueChildName` 做冲突规避 —— core 的 `/copy` 是 REPLACE_EXISTING
 * （同名会被静默覆盖），`/move` 遇到同名直接失败。两者都不改名，所以这里必须改名：
 * `a.txt` → `a (2).txt`。同一个粘贴批次里新占用的名字也要并进集合，否则多项重名会撞在一起。
 */
async function pasteHere() {
  const total = clipboard.paths.length;
  if (!total) return;
  const mode = clipboard.mode;
  if (mode === 'move' && clipboard.paths.every((p) => parentPathOf(p) === currentPath.value)) {
    message.warning('源与目标相同，未执行');
    return;
  }
  const taken = new Set(files.value.map((f) => f.name));
  const jobs = clipboard.paths.map((src, i) => {
    const destName = uniqueChildName(taken, clipboard.names[i]!);
    taken.add(destName);
    return { src, name: clipboard.names[i]!, dest: `${currentPath.value}/${destName}` };
  });

  const verb = mode === 'copy' ? '复制' : '移动';
  pasting.value = true;
  const m = message.loading(`${verb}中… 0/${total}`, { duration: 0 });
  let ok = 0;
  const failed: string[] = [];
  try {
    for (let i = 0; i < jobs.length; i++) {
      const j = jobs[i]!;
      m.content = `${verb}中… ${i + 1}/${total}`;
      try {
        const { data } = await api.post(mode === 'copy' ? '/api/files/copy' : '/api/files/move', {
          source: j.src,
          destination: j.dest,
        });
        if (data?.success === false) failed.push(j.name);
        else ok += 1;
      } catch {
        failed.push(j.name);
      }
    }
  } finally {
    m.destroy();
    pasting.value = false;
  }
  reportBatch(verb, ok, failed);
  // 只有全部成功才清空剪贴板：部分失败时保留，用户可重试
  if (!failed.length) clearClipboard();
  await loadFiles();
  loadDiskUsage();
}

function clearClipboard() {
  clipboard.paths = [];
  clipboard.names = [];
  clipboard.mode = '';
}

// ── 上传 ──
/**
 * 队列 + 进度 + 取消都在 `useFileUpload` 里（它必须用 XHR：fetch 拿不到上传进度）。
 * 这里只做两件事：给它「目标目录」，以及每个文件成功后刷新列表与磁盘读数。
 *
 * 逐个文件都刷一次列表，而不是整批结束再刷：一次传十个大文件时，
 * 用户希望传完一个就在列表里看到一个，而不是最后一起冒出来。
 */
const upload = useFileUpload({
  currentPath: () => currentPath.value,
  onFileDone: () => {
    loadFiles();
    loadDiskUsage();
  },
  onAllSettled: ({ done, failed, canceled }) => {
    if (failed > 0) {
      message.error(`上传结束：成功 ${done}，失败 ${failed}`);
    } else if (done > 0) {
      message.success(canceled > 0 ? `上传完成 ${done} 个，已取消 ${canceled} 个` : `上传完成 ${done} 个`);
    }
  },
});
const uploadTasks = upload.tasks;
const uploadPanelOpen = upload.panelOpen;
const uploadBusy = upload.uploading;
const uploadPercent = upload.overallPercent;
const uploadDone = upload.doneCount;
const uploadFailed = upload.failedCount;

function onPickUpload(files: File[]) {
  upload.enqueue(files);
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

// ── 归档操作：解压 / 压缩 / 校验和 / 复制路径 ──
function extractArchive(f: FileEntry) {
  const m = message.loading('正在解压...', { duration: 0 });
  api
    .post('/api/files/extract', { path: f.path })
    .then(({ data }: any) => {
      if (!data?.success) {
        message.error('解压失败');
        return;
      }
      message.success(`已解压到 ${data.destination}`);
      loadFiles();
      loadDiskUsage();
    })
    .catch((e: any) => message.error(e.response?.data?.error || '解压失败'))
    .finally(() => m.destroy());
}

function compressItem(f: FileEntry) {
  const m = message.loading('正在压缩...', { duration: 0 });
  api
    .post('/api/files/compress', { paths: [f.path] })
    .then(({ data }: any) => {
      if (!data?.success) {
        message.error('压缩失败');
        return;
      }
      message.success(`已生成 ${data.path}`);
      loadFiles();
      loadDiskUsage();
    })
    .catch((e: any) => message.error(e.response?.data?.error || '压缩失败'))
    .finally(() => m.destroy());
}

function copyPath(f: FileEntry) {
  if (navigator.clipboard?.writeText) {
    navigator.clipboard
      .writeText(f.path)
      .then(() => message.success('路径已复制'))
      .catch(() => message.error('复制失败'));
  } else {
    message.error('当前环境不支持复制');
  }
}

async function openChecksum(f: FileEntry) {
  try {
    const { data } = await api.post('/api/files/checksum', { path: f.path });
    if (!data?.success) {
      message.error('校验和计算失败');
      return;
    }
    checksumResult.value = { path: data.path, algorithms: data.algorithms };
    showChecksumModal.value = true;
  } catch (e: any) {
    message.error(e.response?.data?.error || '校验和失败');
  }
}

/** Esc 退出多选：多选是个「临时状态」，必须有不用鼠标也能退出的出口 */
function onKeydown(e: KeyboardEvent) {
  if (e.key !== 'Escape') return;
  if (showSearchModal.value) return;
  if (selMode.value) exitSelectMode();
}

onMounted(() => {
  window.addEventListener('keydown', onKeydown);

  loadStorageStatus();
  loadFiles();
  loadDiskUsage();
});

onUnmounted(() => {
  window.removeEventListener('keydown', onKeydown);
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
  /* 圆角卡片而不是「只有底边线的平条」：平条会让整块顶栏失去与列表的层次区分 */
  border-radius: var(--radius-md);
  /* --bg-color 从未定义过（兜底一路吃到纯白），所以暗色下这条顶栏其实是靠
     --surface-elevated 生效的 —— 直接接它，去掉那层假变量 */
  background: var(--surface-elevated);
  box-shadow: 0 1px 2px var(--shadow-color-soft);
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
  gap: var(--space-2);
  padding: var(--space-2) var(--space-3);
  background: var(--accent-color-light);
  border-radius: var(--radius-sm);
  font-size: var(--font-base);
  flex-shrink: 0;
}
.cb-text {
  flex: 1 1 auto;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.perm-alert {
  margin-bottom: 0;
  flex-shrink: 0;
}
</style>

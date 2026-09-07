<template>
  <div class="files-view">
    <!-- 工具栏 -->
    <div class="toolbar">
      <div class="toolbar-left">
        <n-button size="small" :disabled="!canGoUp" @click="goUp">
          <template #icon
            ><n-icon><ArrowUpOutline /></n-icon
          ></template>
          上级
        </n-button>
        <n-breadcrumb>
          <n-breadcrumb-item v-for="(seg, i) in pathSegments" :key="i" @click="navigateTo(i)">
            {{ seg || '根目录' }}
          </n-breadcrumb-item>
        </n-breadcrumb>
      </div>
      <div class="toolbar-right">
        <n-input
          v-model:value="searchQuery"
          placeholder="搜索文件..."
          size="small"
          clearable
          style="width: 180px"
          @keyup.enter="doSearch"
          @clear="clearSearch"
        >
          <template #prefix
            ><n-icon><SearchOutline /></n-icon
          ></template>
        </n-input>
        <n-select v-model:value="sortBy" :options="sortOptions" size="small" style="width: 100px" />
        <n-button size="small" @click="showMkdirModal = true">新建文件夹</n-button>
        <n-button size="small" @click="showTouchModal = true">新建文件</n-button>
        <n-upload
          :action="uploadUrl"
          :headers="uploadHeaders"
          :data="{ path: currentPath }"
          :show-file-list="false"
          multiple
          @finish="onUploadDone"
          @error="onUploadError"
        >
          <n-button size="small">上传文件</n-button>
        </n-upload>
        <n-button size="small" @click="refreshAll">
          <template #icon
            ><n-icon><RefreshOutline /></n-icon
          ></template>
        </n-button>
      </div>
    </div>

    <!-- 存储权限提示（core /api/files/status） -->
    <n-alert v-if="storageDenied" type="warning" :show-icon="false" class="perm-alert">
      Core 未获得「所有文件访问权限」，文件列表可能为空或操作失败。请在设备端 UFI-AXIS 应用中授予该权限后点刷新。
    </n-alert>

    <!-- 磁盘用量 -->
    <div v-if="disks.length" class="disk-bar">
      <div v-for="d in disks" :key="d.mount" class="disk-item">
        <span class="disk-label">{{ d.label || d.mount }}</span>
        <n-progress
          type="line"
          :percentage="diskPercent(d)"
          :height="6"
          :show-indicator="false"
          :color="diskPercent(d) > 90 ? '#e88080' : undefined"
          style="flex: 1"
        />
        <span class="disk-free">{{ d.available || '--' }} 可用 / {{ d.size || '--' }}</span>
      </div>
    </div>

    <!-- 剪贴板提示 -->
    <div v-if="clipboard.path" class="clipboard-bar">
      <span>已{{ clipboard.mode === 'copy' ? '复制' : '剪切' }}: {{ clipboard.name }}</span>
      <n-button size="tiny" type="primary" @click="pasteHere">粘贴到此处</n-button>
      <n-button size="tiny" @click="clearClipboard">取消</n-button>
    </div>

    <!-- 文件列表 -->
    <n-spin :show="loading">
      <div class="file-list">
        <div v-if="searchResults !== null" class="search-header">
          搜索 "{{ lastQuery }}" — {{ searchResults.length }} 个结果
          <n-button size="tiny" text @click="clearSearch">清除</n-button>
        </div>
        <div
          v-for="f in displayFiles"
          :key="f.path"
          class="file-item"
          :class="{ selected: selectedPath === f.path }"
          @click="selectFile(f)"
          @dblclick="openFile(f)"
        >
          <span class="file-icon" :class="fileIconClass(f)">{{ fileIcon(f) }}</span>
          <div class="file-info">
            <span class="file-name">{{ f.name }}</span>
            <span class="file-meta">{{ fileMeta(f) }}</span>
          </div>
          <div class="file-actions" @click.stop>
            <n-button v-if="canPreview(f)" size="tiny" text @click="openPreview(f)">预览</n-button>
            <n-button v-if="!f.isDirectory" size="tiny" text @click="downloadFile(f)">下载</n-button>
            <n-dropdown :options="fileActions(f)" trigger="click" @select="(k: string) => handleAction(k, f)">
              <n-button size="tiny" text>
                <template #icon
                  ><n-icon><EllipsisHorizontalOutline /></n-icon
                ></template>
              </n-button>
            </n-dropdown>
          </div>
        </div>
        <div v-if="!displayFiles.length && !loading" class="empty-state">
          {{ searchResults !== null ? '无匹配结果' : '空目录' }}
        </div>
      </div>
    </n-spin>

    <!-- 新建文件夹弹窗 -->
    <n-modal v-model:show="showMkdirModal" preset="dialog" title="新建文件夹" style="width: 360px">
      <n-input v-model:value="newFolderName" placeholder="文件夹名称" @keyup.enter="doMkdir" />
      <template #action>
        <n-button @click="showMkdirModal = false">取消</n-button>
        <n-button type="primary" :loading="actionLoading" @click="doMkdir">创建</n-button>
      </template>
    </n-modal>

    <!-- 新建文件弹窗 -->
    <n-modal v-model:show="showTouchModal" preset="dialog" title="新建文件" style="width: 360px">
      <n-input v-model:value="newFileName" placeholder="文件名（如 notes.txt）" @keyup.enter="doTouch" />
      <template #action>
        <n-button @click="showTouchModal = false">取消</n-button>
        <n-button type="primary" :loading="actionLoading" @click="doTouch">创建</n-button>
      </template>
    </n-modal>

    <!-- 媒体预览弹窗 -->
    <n-modal
      v-model:show="showPreviewModal"
      preset="card"
      :title="previewName || '预览'"
      style="width: 760px; max-width: 92vw"
      @after-leave="releasePreviewUrl"
    >
      <n-spin :show="previewLoading">
        <div class="preview-body">
          <img
            v-if="previewUrl && previewKind === 'image'"
            :src="previewUrl"
            :alt="previewName"
            class="preview-image"
          />
          <video v-else-if="previewUrl && previewKind === 'video'" :src="previewUrl" controls class="preview-video" />
          <audio v-else-if="previewUrl && previewKind === 'audio'" :src="previewUrl" controls class="preview-audio" />
          <div v-else-if="!previewLoading" class="preview-placeholder">无可预览内容</div>
        </div>
      </n-spin>
    </n-modal>

    <!-- 重命名弹窗 -->
    <n-modal v-model:show="showRenameModal" preset="dialog" title="重命名" style="width: 360px">
      <n-input v-model:value="renameValue" @keyup.enter="doRename" />
      <template #action>
        <n-button @click="showRenameModal = false">取消</n-button>
        <n-button type="primary" :loading="actionLoading" @click="doRename">确定</n-button>
      </template>
    </n-modal>

    <!-- 文件信息弹窗 -->
    <n-modal v-model:show="showInfoModal" preset="card" title="文件信息" style="width: 400px">
      <div v-if="fileInfo" class="info-grid">
        <InfoRow label="名称" :value="fileInfo.name" />
        <InfoRow label="路径" :value="fileInfo.path" />
        <InfoRow label="类型" :value="fileInfo.isDirectory ? '文件夹' : '文件'" />
        <InfoRow label="大小" :value="fileInfo.isDirectory ? '--' : formatBytes(fileInfo.size)" />
        <InfoRow label="修改时间" :value="formatDate(fileInfo.lastModified)" />
        <InfoRow label="权限" :value="fileInfo.permissions || '--'" />
        <InfoRow v-if="fileInfo.owner" label="所有者" :value="fileInfo.owner" />
      </div>
    </n-modal>

    <!-- 文本编辑弹窗 -->
    <n-modal v-model:show="showEditorModal" preset="card" :title="editorTitle" style="width: 700px; max-width: 90vw">
      <n-alert v-if="editorReadOnly" type="warning" :show-icon="false" style="margin-bottom: 8px">
        {{ editorContent }}<br />该文件无法在线编辑，保存已禁用。
      </n-alert>
      <n-input
        v-else
        v-model:value="editorContent"
        type="textarea"
        :autosize="{ minRows: 10, maxRows: 30 }"
        style="font-family: monospace"
      />
      <template #action>
        <n-button @click="showEditorModal = false">关闭</n-button>
        <n-button v-if="!editorReadOnly" type="primary" :loading="actionLoading" @click="saveTextFile">保存</n-button>
      </template>
    </n-modal>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted, onUnmounted } from 'vue';
import { useMessage, useDialog } from 'naive-ui';
import { getApiClient } from '@/composables/useApi';
import { useAppStore } from '@/stores/app';
import { formatBytes } from '@/composables/utils';
import { loadDeviceIdentity } from '@/composables/deviceIdentityLazy';
import InfoRow from '@/components/InfoRow.vue';
import { ArrowUpOutline, SearchOutline, RefreshOutline, EllipsisHorizontalOutline } from '@vicons/ionicons5';

const message = useMessage();
const dialog = useDialog();
const api = getApiClient();
const appStore = useAppStore();

// ── 状态 ──
const currentPath = ref('/storage/emulated/0');
const files = ref<any[]>([]);
const loading = ref(false);
const selectedPath = ref('');
const searchQuery = ref('');
const searchResults = ref<any[] | null>(null);
const lastQuery = ref('');
const sortBy = ref('name');
const disks = ref<any[]>([]);
const actionLoading = ref(false);
const fileInfo = ref<any>(null);
const clipboard = reactive({ path: '', name: '', mode: '' as '' | 'copy' | 'move' });

const sortOptions = [
  { label: '名称', value: 'name' },
  { label: '大小', value: 'size' },
  { label: '日期', value: 'date' },
  { label: '类型', value: 'type' },
];

// 弹窗状态
const showMkdirModal = ref(false);
const newFolderName = ref('');
const showTouchModal = ref(false);
const newFileName = ref('');
const showPreviewModal = ref(false);
const previewLoading = ref(false);
const previewUrl = ref('');
const previewKind = ref<'image' | 'video' | 'audio' | ''>('');
const previewName = ref('');
const showRenameModal = ref(false);
const renameValue = ref('');
const renameTarget = ref('');
const showInfoModal = ref(false);
const showEditorModal = ref(false);
const editorContent = ref('');
const editorPath = ref('');
const editorTitle = ref('');
const editorReadOnly = ref(false);
const storageDenied = ref(false);

// core diskMap 返回的全是字符串（size/used/available 已格式化，usePercent 形如 "57%"）
function diskPercent(d: any): number {
  const raw = String(d?.usePercent ?? '0').replace('%', '');
  const n = Number(raw);
  return isNaN(n) ? 0 : Math.min(100, Math.max(0, Math.round(n)));
}

// /api/files/search 只返回 name/path/isDirectory，没有 size/lastModified
function fileMeta(f: any): string {
  if (f.isDirectory) return '文件夹';
  if (f.size == null || f.lastModified == null) return '文件';
  return `${formatBytes(f.size)} · ${formatDate(f.lastModified)}`;
}

const pathSegments = computed(() => {
  const p = currentPath.value || '';
  return p.split('/').filter(Boolean);
});

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

const uploadUrl = computed(() => `${appStore.baseUrl || ''}/api/files/upload`);
const uploadHeaders = computed<Record<string, string>>(() => {
  const headers: Record<string, string> = {};
  if (appStore.token) headers.Authorization = `Bearer ${appStore.token}`;
  return headers;
});

const canGoUp = computed(() => {
  const parts = (currentPath.value || '').split('/').filter(Boolean);
  parts.pop();
  return parts.length > 0 && isAllowedRoot('/' + parts.join('/'));
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
function navigateTo(index: number) {
  const segs = pathSegments.value.slice(0, index + 1);
  const next = '/' + segs.join('/');
  if (!isAllowedRoot(next)) {
    message.warning('该目录不在可访问范围内');
    return;
  }
  currentPath.value = next;
  loadFiles();
}

function goUp() {
  const parts = currentPath.value.split('/').filter(Boolean);
  parts.pop();
  const next = parts.length ? '/' + parts.join('/') : '/';
  // core 只允许 /storage/ 、/sdcard 、/mnt/media_rw/ 前缀，再往上会被判为 Invalid path
  if (!isAllowedRoot(next)) {
    message.warning('已到可访问范围的顶层');
    return;
  }
  currentPath.value = next;
  loadFiles();
}

// 与 core FileRoutes.isUserStoragePath 保持一致
function isAllowedRoot(p: string): boolean {
  return p === '/sdcard' || p.startsWith('/sdcard/') || p.startsWith('/storage/') || p.startsWith('/mnt/media_rw/');
}

function openFile(f: any) {
  if (f.isDirectory) {
    currentPath.value = f.path;
    searchResults.value = null;
    searchQuery.value = '';
    loadFiles();
  } else {
    // 尝试打开文本文件
    const ext = f.name.split('.').pop()?.toLowerCase();
    if (
      [
        'txt',
        'log',
        'conf',
        'cfg',
        'ini',
        'json',
        'xml',
        'yaml',
        'yml',
        'md',
        'sh',
        'py',
        'js',
        'ts',
        'html',
        'css',
        'csv',
      ].includes(ext || '')
    ) {
      openTextEditor(f.path, f.name);
    } else {
      downloadFile(f);
    }
  }
}

function selectFile(f: any) {
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

// ── 文件操作 ──
function fileIcon(f: any): string {
  if (f.isDirectory) return '📁';
  const ext = f.name.split('.').pop()?.toLowerCase();
  const map: Record<string, string> = {
    jpg: '🖼',
    jpeg: '🖼',
    png: '🖼',
    gif: '🖼',
    svg: '🖼',
    webp: '🖼',
    mp4: '🎬',
    mkv: '🎬',
    avi: '🎬',
    mov: '🎬',
    webm: '🎬',
    mp3: '🎵',
    wav: '🎵',
    flac: '🎵',
    ogg: '🎵',
    aac: '🎵',
    pdf: '📕',
    doc: '📘',
    docx: '📘',
    xls: '📗',
    xlsx: '📗',
    zip: '📦',
    tar: '📦',
    gz: '📦',
    rar: '📦',
    '7z': '📦',
    apk: '📱',
    txt: '📝',
    log: '📝',
    json: '📝',
  };
  return map[ext || ''] || '📄';
}

function fileIconClass(f: any) {
  if (f.isDirectory) return 'icon-folder';
  return 'icon-file';
}

function fileActions(f: any) {
  const ext = f.name.split('.').pop()?.toLowerCase();
  const opts = [
    { label: '重命名', key: 'rename' },
    { label: '复制', key: 'copy' },
    { label: '剪切', key: 'cut' },
    { label: '信息', key: 'info' },
  ];
  if (!f.isDirectory) {
    opts.unshift({ label: '下载', key: 'download' });
    if (ext === 'apk') {
      opts.unshift({ label: '安装 APK', key: 'install' });
    }
  }
  opts.push({ label: '删除', key: 'delete' });
  return opts;
}

function handleAction(key: string, f: any) {
  switch (key) {
    case 'install':
      installApk(f);
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

/**
 * 裸 fetch 的鉴权头：Bearer + 设备签名。
 *
 * 这两处（下载 / 预览）绕过了 axios，所以拿不到 useApi 里的签名拦截器；core 的
 * AuthMiddleware 对**所有** /api 请求强制验签，少这三个头就是 444。
 * 签名的 URI 必须是服务端看到的 path+query，因此这里传相对路径而不是拼好的绝对 URL。
 */
async function authHeaders(uri: string): Promise<Record<string, string>> {
  const signed = await (await loadDeviceIdentity()).signRequest('GET', uri);
  return appStore.token ? { Authorization: `Bearer ${appStore.token}`, ...signed } : { ...signed };
}

function downloadFile(f: any) {
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

function installApk(f: any) {
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

function startRename(f: any) {
  renameTarget.value = f.path;
  renameValue.value = f.name;
  showRenameModal.value = true;
}

async function doRename() {
  if (!renameValue.value.trim()) return;
  if (renameValue.value.includes('/')) {
    message.error('名称不能包含 /');
    return;
  }
  actionLoading.value = true;
  try {
    const parent = renameTarget.value.substring(0, renameTarget.value.lastIndexOf('/'));
    const newPath = `${parent}/${renameValue.value}`;
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

function confirmDelete(f: any) {
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

async function showInfo(f: any) {
  try {
    const { data } = await api.get('/api/files/info', { params: { path: f.path } });
    fileInfo.value = data;
    showInfoModal.value = true;
  } catch {
    message.error('获取信息失败');
  }
}

async function doMkdir() {
  if (!newFolderName.value.trim()) return;
  if (newFolderName.value.includes('/')) {
    message.error('名称不能包含 /');
    return;
  }
  actionLoading.value = true;
  try {
    const { data } = await api.post('/api/files/mkdir', { path: `${currentPath.value}/${newFolderName.value}` });
    if (!assertOk(data, '创建失败')) return;
    message.success('文件夹已创建');
    showMkdirModal.value = false;
    newFolderName.value = '';
    loadFiles();
  } catch {
    message.error('创建失败');
  } finally {
    actionLoading.value = false;
  }
}

// core /api/files/touch 会 mkdirs 缺失的父目录；HTTP 200 也可能 success:false
async function doTouch() {
  if (!newFileName.value.trim()) {
    message.warning('请输入文件名');
    return;
  }
  if (newFileName.value.includes('/')) {
    message.error('名称不能包含 /');
    return;
  }
  actionLoading.value = true;
  try {
    const { data } = await api.post('/api/files/touch', { path: `${currentPath.value}/${newFileName.value.trim()}` });
    if (!assertOk(data, '创建失败')) return;
    message.success('文件已创建');
    showTouchModal.value = false;
    newFileName.value = '';
    loadFiles();
  } catch (e: any) {
    message.error(e.response?.data?.error || '创建失败');
  } finally {
    actionLoading.value = false;
  }
}

// ── 媒体预览（/api/files/stream 需要 Bearer 头，不能直接给 <img>/<video> src，
// 必须 fetch 成 blob 后转 object URL，并在关闭/卸载时 revoke 防止泄漏） ──
const PREVIEW_MAX_BYTES = 100 * 1024 * 1024;
const PREVIEW_IMAGE_EXTS = ['jpg', 'jpeg', 'png', 'gif', 'webp', 'bmp', 'svg'];
const PREVIEW_VIDEO_EXTS = ['mp4', 'webm', 'mkv', 'mov'];
const PREVIEW_AUDIO_EXTS = ['mp3', 'wav', 'ogg', 'flac', 'm4a'];

function previewKindOf(name: string): 'image' | 'video' | 'audio' | '' {
  const ext = name.split('.').pop()?.toLowerCase() || '';
  if (PREVIEW_IMAGE_EXTS.includes(ext)) return 'image';
  if (PREVIEW_VIDEO_EXTS.includes(ext)) return 'video';
  if (PREVIEW_AUDIO_EXTS.includes(ext)) return 'audio';
  return '';
}

function canPreview(f: any): boolean {
  return !f.isDirectory && previewKindOf(f.name || '') !== '';
}

function releasePreviewUrl() {
  if (previewUrl.value) URL.revokeObjectURL(previewUrl.value);
  previewUrl.value = '';
  previewKind.value = '';
}

async function openPreview(f: any) {
  const kind = previewKindOf(f.name || '');
  if (!kind) return;
  // 整文件读入内存，超过 100MB 直接劝退，避免浏览器内存爆掉
  if (typeof f.size === 'number' && f.size > PREVIEW_MAX_BYTES) {
    message.warning(`文件超过 100MB（${formatBytes(f.size)}），请下载后本地打开`);
    return;
  }
  releasePreviewUrl();
  previewKind.value = kind;
  previewName.value = f.name;
  previewLoading.value = true;
  showPreviewModal.value = true;
  const uri = `/api/files/stream?path=${encodeURIComponent(f.path)}`;
  const url = `${appStore.baseUrl || ''}${uri}`;
  try {
    const res = await fetch(url, { headers: await authHeaders(uri) });
    // 失败时 core 返回 JSON（Invalid path / 文件不存在 / range not satisfiable）
    if (!res.ok) {
      const detail = await res.json().catch(() => null);
      throw new Error(detail?.error || `HTTP ${res.status}`);
    }
    const blob = await res.blob();
    // 请求期间弹窗可能已被关闭，此时不要留下无人 revoke 的 object URL
    if (!showPreviewModal.value) return;
    previewUrl.value = URL.createObjectURL(blob);
  } catch (e: any) {
    showPreviewModal.value = false;
    releasePreviewUrl();
    message.error(e?.message ? `预览失败: ${e.message}` : '预览失败');
  } finally {
    previewLoading.value = false;
  }
}

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
// core /read 对二进制/过大/非文件都返回 200 + content 为占位文本，
// 直接编辑保存会把占位文本写回去从而损坏文件，必须识别后转只读。
const READ_PLACEHOLDER = /^\[(不是文件或不存在|文件过大|二进制文件)/;

async function openTextEditor(path: string, name: string) {
  try {
    const { data } = await api.post('/api/files/read', { path });
    const content = data.content || '';
    editorReadOnly.value = READ_PLACEHOLDER.test(content);
    editorContent.value = content;
    editorPath.value = path;
    editorTitle.value = name;
    showEditorModal.value = true;
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
    showEditorModal.value = false;
    loadFiles();
  } catch {
    message.error('保存失败');
  } finally {
    actionLoading.value = false;
  }
}

function formatDate(ts: number) {
  if (!ts) return '--';
  return new Date(ts).toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' });
}

onMounted(() => {
  loadStorageStatus();
  loadFiles();
  loadDiskUsage();
});

onUnmounted(() => {
  // 组件销毁时兜底释放（弹窗未走 after-leave 的场景，如路由直接切走）
  releasePreviewUrl();
});
</script>

<style scoped>
.files-view {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
}
.toolbar-left {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
  flex: 1;
}
.toolbar-right {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}
.disk-bar {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.disk-item {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
}
.disk-label {
  min-width: 60px;
  color: var(--text-secondary);
}
.disk-free {
  min-width: 80px;
  text-align: right;
  color: var(--text-muted);
}
.clipboard-bar {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 12px;
  background: var(--accent-color-light, rgba(32, 128, 240, 0.06));
  border-radius: 8px;
  font-size: 13px;
}
.file-list {
  border: 1px solid var(--border-subtle);
  border-radius: 8px;
  overflow: hidden;
}
.search-header {
  padding: 8px 12px;
  background: var(--hover-color);
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
  transition: background 0.1s;
}
.file-item:last-child {
  border-bottom: none;
}
.file-item:hover {
  background: var(--hover-color);
}
.file-item.selected {
  background: var(--accent-color-light, rgba(32, 128, 240, 0.06));
}
.file-icon {
  font-size: 20px;
  width: 28px;
  text-align: center;
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
}
.file-actions {
  display: flex;
  align-items: center;
  gap: 2px;
  flex-shrink: 0;
}
.empty-state {
  padding: 40px;
  text-align: center;
  color: var(--text-muted);
  font-size: 14px;
}
.info-grid {
  display: flex;
  flex-direction: column;
}
.preview-body {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 160px;
}
.preview-image {
  max-width: 100%;
  max-height: 70vh;
  object-fit: contain;
}
.preview-video {
  max-width: 100%;
  max-height: 70vh;
}
.preview-audio {
  width: 100%;
}
.preview-placeholder {
  color: var(--text-muted);
  font-size: 13px;
}
</style>

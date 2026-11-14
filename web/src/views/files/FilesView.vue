<template>
  <div class="files-view">
    <!-- 固定顶栏：不参与列表滚动 -->
    <div class="files-header">
      <FilesToolbar
        v-model:search-query="searchQuery"
        v-model:sort-by="sortBy"
        :current-path="currentPath"
        :disks="disks"
        :push-active="pushSummary?.active ?? 0"
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
        @open-upload="uploadOpen = true"
        @open-storage-devices="showStorageDevicesModal = true"
      />
    </div>

    <!-- 列表滚动区：在顶栏下方独立滚动，内容不会进入顶栏区域。

         该区域同时是拖拽上传的投放区，三项约束必须同时满足：
         · `dragover` 必须调用 `preventDefault()`，否则浏览器不将其视为投放区（drop 不会触发）；
         · 拖入 / 拖出状态使用**计数器**而非布尔量 —— 拖过子元素时 `dragleave` 与 `dragenter`
           会成对冒泡，布尔量会导致高亮反复闪烁；
         · 仅在确实拖拽文件时高亮（`dataTransfer.types` 含 `Files`），
           否则在页内拖动选中的文本也会触发高亮。 -->
    <div
      class="files-body"
      :class="{ 'is-drop-target': dropActive }"
      @dragenter="onDragEnter"
      @dragover.prevent
      @dragleave="onDragLeave"
      @drop.prevent="onDrop"
    >
      <!-- 提示层：pointer-events:none，不能挡住 drop 事件本身 -->
      <div v-if="dropActive" class="drop-hint">松开即上传到当前目录</div>

      <!-- 批量复制 / 移动 / 删除的常驻进度条：队列与重试逻辑在 useBatchOperation 中。
           状态为模块级，关闭弹窗、切换目录、切换页面后仍然保留（仅刷新页面时丢失）。 -->
      <BatchProgressPanel
        :jobs="batchJobs"
        :collapsed="batchCollapsed"
        @cancel-all="batch.cancelAll"
        @cancel-item="batch.cancelItem"
        @retry-item="batch.retryItem"
        @retry-failed="batch.retryFailed"
        @clear="batch.clearFinished"
        @toggle="batch.toggleCollapsed"
      />

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

      <!-- 远端推送摘要：上传到远端为两段式，第二段（core → 远端）的进度若仅在作业弹窗
           打开时可见，关闭弹窗后就既无进度也无失败重试入口。此处常驻显示摘要，
           并提供直接打开作业面板的入口（失败项的「重试」位于面板内）。 -->
      <RemotePushBanner :summary="pushSummary" @open="showRemotePushModal = true" />

      <FileListPanel
        :files="visibleFiles"
        :loading="loading"
        :failed="loadFailed"
        :selected-path="selectedPath"
        :sel-mode="selMode"
        :selected-paths="selectedPaths"
        @select="selectFile"
        @open="openFile"
        @toggle-select="toggleSelect"
        @preview="openPreview"
        @download="downloadFile"
        @action="handleAction"
        @retry="loadFiles"
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

    <!-- 上传暂存弹窗：先收集（含整个文件夹），点「开始上传」才建目录并入队 -->
    <UploadDialog v-model:show="uploadOpen" :uploading="uploadBusy" @submit="onUploadSubmit" />

    <!-- 存储与外接设备：入口为工具栏的存储读数与「更多 → 存储」。
         使用弹窗而非 popover：内容长度不可预知时 popover 会被顶出视口。
         数据仍由本页持有（打开时刷新一次），弹窗本身不发起请求。 -->
    <StorageDevicesModal
      v-model:show="showStorageDevicesModal"
      :disks="disks"
      :loading="diskLoading"
      :storage-sources="storageSources"
      :current-path="currentPath"
      :push-active="pushSummary?.active ?? 0"
      @navigate-to-volume="onNavigateToVolume"
      @navigate-to-remote="onNavigateToRemote"
      @manage-sources="onManageSourcesFromDevices"
      @open-push="onOpenPushFromDevices"
    />

    <!-- 远端存储源管理：入口在「存储」弹窗底部。
         关闭后重新拉取列表，使新增或停用的源立即反映到弹窗内的可浏览入口。 -->
    <StorageSourcesModal v-model:show="showStorageSourcesModal" @update:show="onStorageSourcesClosed" />

    <!-- 远端推送作业面板：入口在「存储」弹层内。
         轮询由该面板自行管理（仅打开期间，间隔 2s），关闭即停止；关闭后的可见性由上方
         RemotePushBanner 接管（15s 摘要轮询，见 useRemotePush.ts）。 -->
    <RemotePushModal v-model:show="showRemotePushModal" />

    <SearchModal
      v-model:show="showSearchModal"
      :current-path="currentPath"
      :initial-query="searchQuery"
      @pick="onSearchPick"
    />

    <!-- 懒加载弹窗必须经由 useLazyModal：否则首帧的 show 即为 true，进出场动画全部丢失 -->
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
    <!-- 上传进度：常驻右下角浮层；队列与 XHR 逻辑在 useFileUpload 中 -->
    <UploadProgressPanel
      :open="uploadPanelOpen"
      :tasks="uploadTasks"
      :uploading="uploadBusy"
      :overall-percent="uploadPercent"
      :done-count="uploadDone"
      :failed-count="uploadFailed"
      :percent-of="upload.percentOf"
      :limit-label="uploadLimitText"
      @cancel="upload.cancel"
      @cancel-all="upload.cancelAll"
      @retry="upload.retry"
      @clear="upload.clearFinished"
      @close="upload.closePanel"
    />
  </div>
</template>

<script setup lang="ts">
/**
 * 文件管理页：状态、取数与写操作的编排；渲染拆到 components/ 下。
 *
 * 以下两条时序必须保留在本组件，移入弹窗会改变行为（见各弹窗的文件头）：
 *   · 预览的 100MB 限制必须在**打开之前**判定（超限时不打开弹窗）
 *   · 文本编辑的读文件同样在打开之前（读取失败只提示错误，不打开编辑器）
 *
 * ── 批量操作的前提 ──
 * core 的 `/api/files/{copy,move,delete}` **只接受单个 source**，没有批量端点，
 * 因此多选后的复制 / 移动 / 删除均为**前端串行循环**（见 `pasteHere` /
 * `confirmDeleteSelected`；队列本体在 `useBatchOperation.ts`，面板在
 * `BatchProgressPanel.vue`）。
 * 唯一的例外是 `/compress`：它接受 `paths: []`，多选压缩为**一次请求**。
 *
 * ── 覆盖语义的前提 ──
 * `/copy` 内部使用 `REPLACE_EXISTING`（同名直接覆盖），`/move` 遇到已存在的目标直接失败。
 * 两者都不做改名，因此**冲突规避必须在客户端完成**（见 `uniqueChildName`）——
 * 否则向已有同名文件的目录粘贴时只能二选一：丢失数据或请求失败。
 */
import { ref, reactive, computed, watch, onMounted, onUnmounted } from 'vue';
import { useMessage, useDialog } from 'naive-ui';
import { getApiClient } from '@/composables/useApi';
import { useAppStore } from '@/stores/app';
import { formatBytes } from '@/composables/utils';
import { useLazyModal } from '@/composables/useLazyModal';
import { useIsMobile } from '@/composables/useIsMobile';
import FilesToolbar from './components/FilesToolbar.vue';
import FileListPanel from './components/FileListPanel.vue';
import UploadProgressPanel from './components/UploadProgressPanel.vue';
// 批量操作的常驻进度条（队列在 useBatchOperation 中）
import BatchProgressPanel from './components/BatchProgressPanel.vue';
// 远端推送摘要条（第二段进度的常驻可见性）
import RemotePushBanner from './components/RemotePushBanner.vue';
import SearchModal from './components/modals/SearchModal.vue';
import NameInputModal from './components/modals/NameInputModal.vue';
import FileInfoModal from './components/modals/FileInfoModal.vue';
import ChecksumModal from './components/modals/ChecksumModal.vue';
import UploadDialog, { type StagedUpload } from './components/modals/UploadDialog.vue';
// 存储与外接设备（原工具栏 popover 改为自适应弹窗）
import StorageDevicesModal from './components/modals/StorageDevicesModal.vue';
// 远端存储源管理
import StorageSourcesModal from './components/modals/StorageSourcesModal.vue';
// 远端推送作业面板（上传到远端的第二段进度）
import RemotePushModal from './components/modals/RemotePushModal.vue';
import { useFileUpload } from './useFileUpload';
import { useBatchOperation } from './useBatchOperation';
import { useRemotePushSummary } from './useRemotePush';
import {
  PREVIEW_MAX_BYTES,
  assertOk as assertOkShared,
  authHeaders,
  effectiveUploadLimit,
  errorOf,
  uploadLimitLabel,
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
  REMOTE_PREFIX,
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
/** 列表**读取失败**（与「空目录」是两种状态，见 loadFiles 的注释） */
const loadFailed = ref(false);
/** 本机卷读数是否在读取中：存储弹窗据此区分「尚未读到」与「确实没有卷」 */
const diskLoading = ref(false);
const selectedPath = ref('');
const searchQuery = ref('');
const sortBy = ref('name');
const disks = ref<any[]>([]);
/** 远端存储源（GET /api/storage/sources）。文件 API 均接受 `remote:<id>/…`，因此与本地卷共用同一套浏览 UI。 */
const storageSources = ref<any[]>([]);
const showStorageSourcesModal = ref(false);
/** 存储与外接设备弹窗（原工具栏 popover 的明细已迁至此处） */
const showStorageDevicesModal = ref(false);
const actionLoading = ref(false);
const fileInfo = ref<FileEntry | null>(null);
const showChecksumModal = ref(false);
const checksumResult = ref<{ path: string; algorithms: Record<string, string> } | null>(null);
const storageDenied = ref(false);
const showSearchModal = ref(false);
/** 视口判据统一走 composable（matchMedia，阈值与全站 768px 一致） */
const isMobile = useIsMobile();
// `pasting` 由模块级批量队列派生为 computed，而非组件局部 ref：
// 切换页面后返回时状态仍然正确（见批量操作一节）。

/** 隐藏文件开关。core `/list` 不过滤隐藏项，过滤只能做在客户端 */
const showHidden = ref(false);

/**
 * 剪贴板采用**多选模型**：复制与剪切都写入一组路径，粘贴时逐项落到目标目录。
 * 与单文件模型（只存一个 path/name）的差别仅在粒度，落盘动作完全一致。
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

/** 搜索跳转后要定位的文件（在目录加载完成后再选中，否则列表中尚无该条目） */
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

/** 已勾选项（以「当前可见」为准：隐藏项被过滤后不应再被批量操作命中） */
const selectedItems = computed(() => visibleFiles.value.filter((f) => selectedPaths.value.includes(f.path)));

// ── 文件加载 ──
// core 的多数写操作返回 HTTP 200 + { success: false, error: "..." }，必须显式判断。
// 判定实现只有一份（`filesShared.assertOk`），此处仅注入提示出口：
// composable 不直接依赖 naive-ui 的 message 实例。
function assertOk(data: any, fallback: string): boolean {
  return assertOkShared(data, fallback, (msg) => message.error(msg));
}

async function loadFiles() {
  loading.value = true;
  loadFailed.value = false;
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
  } catch (e: any) {
    // 失败必须与「空目录」区分开：仅弹一条 toast 时列表区仍会渲染「空目录」，
    // 用户会理解为「该目录没有文件」，这种误导比直接报错更严重。
    files.value = [];
    loadFailed.value = true;
    message.error(e?.response?.data?.error || '加载文件列表失败');
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
  diskLoading.value = true;
  try {
    const { data } = await api.get('/api/files/disk-usage');
    disks.value = data.disks || [];
  } catch {
    /* 静默：读数拿不到时存储弹窗显示「还没有读到」，不弹错 */
  } finally {
    diskLoading.value = false;
  }
}

/**
 * 远端存储源列表。
 *
 * 仅在「存储」弹层中用于提供可浏览入口与试连 / 管理，**不影响本地卷链路**；
 * 获取失败时按"未配置远端源"处理（旧固件没有 /api/storage/*，静默处理，不提示错误）。
 */
async function loadStorageSources() {
  try {
    const { data } = await api.get('/api/storage/sources');
    storageSources.value = data?.sources || [];
  } catch {
    /* 静默 */
  } finally {
    // 推送摘要的启用判据是"是否存在启用中的远端源"；onMounted 中
    // loadStorageSources() 未被 await，紧随其后查询会得到**空列表**。
    // 因此在该请求完成后（finally）再查询一次。
    void push.refresh();
  }
}

/** 点击远端源入口：拼接为 core 的客户端路径形态 `remote:<id>/`，再走统一导航（白名单已放行 `remote:`）。 */
function onNavigateToRemote(id: string) {
  enterPath(`${REMOTE_PREFIX}${id}/`);
}

/**
 * 切换到另一块本机卷（SD 卡 / U 盘）。
 *
 * 弹窗侧先 emit 再关闭；此处只负责经由统一白名单与历史栈导航 ——
 * `enterPath` 会校验 `isAllowedRoot`，并清除跨目录无意义的选中态。
 */
function onNavigateToVolume(mount: string) {
  enterPath(mount);
}

/** 弹窗内的「管理远端存储源…」：先关闭本弹窗再打开配置弹窗，避免两层遮罩叠加。 */
function onManageSourcesFromDevices() {
  showStorageDevicesModal.value = false;
  showStorageSourcesModal.value = true;
}

/** 弹窗内的「远端推送作业…」：同上；作业面板自行管理 2s 轮询。 */
function onOpenPushFromDevices() {
  showStorageDevicesModal.value = false;
  showRemotePushModal.value = true;
}

/**
 * 打开存储弹窗时刷新一次读数与远端源列表。
 *
 * 磁盘读数仅在上传 / 删除后被刷新，间隔一段时间再打开弹窗可能显示旧数值；
 * 远端源列表也可能刚在设备端被修改。两个请求均**不 await**：弹窗先用现有数据渲染，
 * 响应返回后就地更新，避免打开时出现空白。
 */
watch(showStorageDevicesModal, (v) => {
  if (!v) return;
  loadDiskUsage();
  loadStorageSources();
});

/** 存储源弹窗关闭后重新拉取列表：新增 / 停用 / 删除的源需立即反映到「存储」弹层内的可浏览入口。 */
function onStorageSourcesClosed(v: boolean) {
  if (!v) {
    loadStorageSources();
  }
}

/**
 * 远端推送作业摘要。
 *
 * 仅统计数量时，上传完成后用户只会看到"上传成功"，而远端实际尚未收到文件；
 * 且 `RemotePushModal` 的轮询由 `watch(() => props.show)` 起停，关闭弹窗后
 * **既无进度也无失败重试入口**。因此同一份数据以摘要形式由列表上方的常驻横幅
 * （`RemotePushBanner`）呈现，并提供打开作业面板的入口。
 *
 * **不新增第二条轮询**：仍为 15s 间隔、同一端点，仅移入 `useRemotePushSummary`
 * （该 composable 同时保留"没有启用中的远端源则不请求端点"的判断）。
 */
const push = useRemotePushSummary(() => storageSources.value.some((s) => s.enabled !== false));
const pushSummary = push.summary;
const showRemotePushModal = ref(false);

/**
 * 作业面板关闭时立即刷新一次摘要：用户在面板内执行「清理已结束」或「重试」后，
 * 横幅不应等待下一轮 15s 轮询才更新。
 */
watch(showRemotePushModal, (v) => {
  if (!v) void push.refresh();
});

// ── 导航 ──
/** 切换目录：清除单选与多选状态（跨目录的选中集合没有意义），再拉取列表 */
function applyPath(p: string) {
  currentPath.value = p;
  selectedPath.value = '';
  exitSelectMode();
  loadFiles();
}

/** 经由统一白名单：拒绝 `/storage`、`/storage/emulated` 这类中间层路径。 */
function enterPath(nextRaw: string, { push = true } = {}): boolean {
  const next = normalizePath(nextRaw);
  if (!isAllowedRoot(next)) {
    message.warning('该目录不在可访问范围内');
    return false;
  }
  if (next === currentPath.value) return true;
  if (push) {
    // 在当前位置之后重开分支：history 被截断，前进按钮随之失效
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
 * 双击「打开」——**不下载、也不修改文件系统**。
 * 目录进入；媒体打开预览弹窗；文本进入编辑器；APK 弹出安装确认；
 * 其余（压缩包 / 文档 / 未知类型）打开详情弹窗，由弹窗内的主操作按钮执行动作。
 *
 * 压缩包不在此处解压：解压会修改设备文件系统，而双击是无确认的即时手势。
 * 与 app 的 `resolveOpenRoute` 采用同一口径。
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

/** 排序方式循环切换（「更多」菜单项；工具栏第二行的下拉为直接选择） */
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
 * 应用搜索结果。
 * 目录 → 直接进入；文件 → 进入其所在目录并选中该项。
 * 后者是多级目录下的关键路径：能搜到但无法定位，等同于搜索失败。
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
/**
 * 批量复制 / 移动 / 删除统一走 `useBatchOperation`：逐项状态、取消剩余、失败项单独重试，
 * 面板常驻在列表上方（`BatchProgressPanel`）。
 *
 * 进度若只存在于一条 `message.loading` 中（`ok` / `failed` 为函数内局部变量），
 * 关闭该 toast 后无法确认失败项，也无法中止（中止只能刷新页面并丢失已完成进度），
 * 失败后只能整批重来。详见 useBatchOperation.ts 的文件头。
 *
 * **进入批量面板的判据**：操作具备「多项 + 逐项成败 + 取消剩余 + 单项重试」四项，
 * 即**前端串行循环**的 `/copy`、`/move`、`/delete`（core 只接受单个 source，没有批量端点）。
 * **保留 toast 的操作**为**单请求**：`/compress` 一次接受 `paths[]`，`/extract` 与两处安装
 * 都只有一个请求，不存在"项"这一维度 —— 取消 HTTP 无法停止 core 已开始的压缩 / 解压 /
 * 安装，重试也无法只重试其中一项，放入面板只会得到一个仅有一行且按钮均不适用的面板。
 *
 * 队列串行而非 `Promise.all`：core 是运行在随身 WiFi 等资源受限设备上的单进程，
 * 同时发起 N 个文件操作会使 IO 队列互相拖慢，且并发下的失败原因更难归因。
 */
const batch = useBatchOperation({
  notify: notifyBatch,
  onBatchSettled: (job) => {
    // 整批结束后才刷新列表：`FileListPanel` 的 loading 是整块遮罩（`n-spin :show`），
    // 逐项刷新会使遮罩在整批期间反复闪烁；逐项进度由常驻面板负责，此处只反映最终结果。
    loadFiles();
    loadDiskUsage();
    exitSelectMode();
    // 粘贴：仅在全部成功时清空剪贴板，部分失败时保留。面板内每个失败项均提供「重试」，
    // 无需回到列表中逐个确认失败项。
    if ((job.verb === '复制' || job.verb === '移动') && !job.items.some((i) => i.status === 'error')) {
      clearClipboard();
    }
  },
});
const batchJobs = batch.jobs;
const batchCollapsed = batch.collapsed;

/** 队列的提示出口：composable 不直接依赖 naive-ui 的 message 实例（需在组件之外继续运行） */
function notifyBatch(kind: 'info' | 'success' | 'warning' | 'error', text: string) {
  if (kind === 'success') message.success(text);
  else if (kind === 'warning') message.warning(text);
  else if (kind === 'error') message.error(text);
  else message.info(text);
}

/** 粘贴按钮的加载态：状态由模块级队列派生，切换页面后返回仍然正确 */
const pasting = computed(() => batch.activeVerb.value === '复制' || batch.activeVerb.value === '移动');

/**
 * 多选压缩：core `/compress` 接受 `paths: []`，**一次请求**，无需循环。
 *
 * 保留 `message.loading`，不进入批量面板 —— 判据见上文（单请求即不存在"项"）。
 * 已知限制：该请求受全局 axios 的 15s 超时约束（`composables/useApi.ts`），
 * 压缩大目录时可能出现客户端报失败、而设备端实际成功的情况。
 */
async function compressSelected() {
  const items = selectedItems.value;
  if (!items.length) return;
  const m = message.loading('正在压缩…', { duration: 0 });
  try {
    const { data } = await api.post('/api/files/compress', { paths: items.map((i) => i.path) });
    m.destroy();
    // 仅提示一次：assertOk 已提示过错误，此处不再追加 data?.error，避免同一原因重复提示
    if (!assertOk(data, '压缩失败（目标同名 zip 可能已存在）')) return;
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
      batch.start(
        '删除',
        currentPath.value,
        items.map((f) => ({
          path: f.path,
          name: f.name,
          run: async () => {
            const { data } = await api.post('/api/files/delete', { path: f.path });
            // 判定统一走 filesShared.assertOk；失败原因抛给队列，由逐项面板显示 core 原文，
            // 而非将所有失败归一为「操作失败」
            if (!assertOkShared(data, '删除失败')) throw new Error(errorOf(data, '删除失败'));
            return true;
          },
        }))
      ),
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
  // 下载需携带 auth header，故使用 fetch；core 对 >50MB 或不存在的文件返回 JSON 错误，
  // 不检查 res.ok 会把错误 JSON 当作文件内容保存
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
      // Firefox 要求 <a> 位于文档中才会触发下载；同步 revoke 对象 URL 会取消下载，
      // 必须在本轮任务结束后再清理
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

/**
 * 安装 APK（文件行内与详情弹窗的入口）。
 *
 * 保留 `message.loading`，不进入批量面板 —— 判据同上：这是**单请求**操作，
 * 不存在"项"（取消 HTTP 无法停止 core 的安装，重试也无法只重试其中一项）。
 * 由于是单请求，该 toast 只能表示「core 已接收请求」：core 内部仍需等待
 * `adb install -r` / PackageInstaller 回调（见 core `AppManager.installApk`）。
 * 因此文案如实说明"已提交"并指向可查看结果的位置；**回读 `/api/apps` 确认安装结果**
 * 由应用页负责（该页可比对版本号），不在此处重复实现轮询。
 */
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
        if (!assertOk(data, '安装失败')) return;
        message.success('已提交安装请求，可在「应用」页查看安装结果');
      } catch (e: any) {
        // core 的失败原因位于 `error` 信封中；只读取 `.message` 会始终显示 axios 的
        // `Request failed with status code 500`，丢失 core 返回的具体原因
        message.error(`安装出错: ${e.response?.data?.error || e.response?.data?.message || e.message}`);
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
 * 弹窗主操作复用 `handleAction` —— 它同时是行内「⋯」菜单的分发入口。
 * 两处入口共用同一分发，避免「菜单内解压」与「弹窗内解压」行为不一致。
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

// core /api/files/touch 会自动创建缺失的父目录；HTTP 200 也可能返回 success:false
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
// blob 与 object URL 的生命周期由 PreviewModal 管理；此处只判定「能否预览」与大小限制，
// 因为两者都必须在打开弹窗之前完成。
function openPreview(f: FileEntry) {
  if (!canPreview(f)) return;
  // 100MB 限制仅适用于「整份读入内存」的类型（图片 / 文本）。
  // 音视频使用票据流式播放（浏览器按 Range 边下载边播放，见 PreviewModal.requestStreamUrl），
  // 内存占用与文件大小无关；对该类施加同一上限会使大文件永远无法预览。
  const kind = previewKindOf(f.name || '');
  const streamed = kind === 'audio' || kind === 'video';
  if (!streamed && typeof f.size === 'number' && f.size > PREVIEW_MAX_BYTES) {
    message.warning(`文件超过 100MB（${formatBytes(f.size)}），请下载后本地打开`);
    return;
  }
  // 先赋值再调用 open()：open() 会先挂载一帧（此时 show 仍为 false），
  // 子组件的 show-watch 到下一帧才触发，届时 file 已就位
  previewFile.value = f;
  previewModal.open();
}

// ── 剪贴板 ──
function setClipboard(mode: 'copy' | 'move', items: FileEntry[]) {
  if (!items.length) return;
  clipboard.mode = mode;
  clipboard.paths = items.map((i) => i.path);
  clipboard.names = items.map((i) => i.name);
  // 剪贴板是跨目录的一步操作，选择后即退出多选，前往目标目录
  exitSelectMode();
  message.success(`已${mode === 'copy' ? '复制' : '剪切'} ${items.length} 项，请进入目标目录后粘贴`);
}

/**
 * 粘贴：将剪贴板中的每一项落到当前目录。
 *
 * 目标名一律经 `uniqueChildName` 做冲突规避 —— core 的 `/copy` 使用 REPLACE_EXISTING
 * （同名会被静默覆盖），`/move` 遇到同名直接失败。两者都不改名，因此必须在此改名：
 * `a.txt` → `a (2).txt`。同一粘贴批次内新占用的名称也需并入集合，否则多项重名会互相冲突。
 *
 * 整批交由 `useBatchOperation` 执行。剪贴板语义不变：仅全部成功时清空，部分失败时保留；
 * 面板内每个失败项均提供「重试」，无需回到列表中逐个确认失败项。
 */
function pasteHere() {
  const total = clipboard.paths.length;
  if (!total) return;
  const mode = clipboard.mode;
  if (mode === 'move' && clipboard.paths.every((p) => parentPathOf(p) === currentPath.value)) {
    message.warning('源与目标相同，未执行');
    return;
  }
  const dir = currentPath.value;
  const uri = mode === 'copy' ? '/api/files/copy' : '/api/files/move';
  const failText = mode === 'copy' ? '复制失败' : '移动失败';
  const taken = new Set(files.value.map((f) => f.name));
  // 目标名在**入队时**计算并快照进 run：重试必须命中同一目标名，
  // 否则重试会再次调用 uniqueChildName（此时 `a (2).txt` 已存在），结果又多复制一份 `a (3).txt`
  const targets = clipboard.paths.map((src, i) => {
    const name = clipboard.names[i]!;
    const destName = uniqueChildName(taken, name);
    taken.add(destName);
    const dest = `${dir}/${destName}`;
    return {
      path: src,
      name,
      run: async () => {
        const { data } = await api.post(uri, { source: src, destination: dest });
        // 同 confirmDeleteSelected：判定只实现一份，失败原因带入队列
        if (!assertOkShared(data, failText)) throw new Error(errorOf(data, failText));
        return true;
      },
    };
  });

  batch.start(mode === 'copy' ? '复制' : '移动', dir, targets);
}

function clearClipboard() {
  clipboard.paths = [];
  clipboard.names = [];
  clipboard.mode = '';
}

// ── 上传 ──
/**
 * 队列、进度与取消均由 `useFileUpload` 管理（必须使用 XHR：fetch 无法获取上传进度）。
 * 此处只做两件事：提供「目标目录」，以及在每个文件成功后刷新列表与磁盘读数。
 *
 * 按文件逐个刷新列表而非整批结束后刷新：批量上传多个大文件时，
 * 用户需要每完成一个即在列表中看到结果，而非全部结束后一次性出现。
 */
const upload = useFileUpload({
  currentPath: () => currentPath.value,
  onFileDone: () => {
    loadFiles();
    loadDiskUsage();
  },
  // 超限文件在入队前即被拦截（不发送任何字节），此处提供即时提示：
  // 仅依赖面板内的红色提示时，用户可能仍在留意文件选择器关闭的位置
  onRejected: (rejected) => {
    const limit = uploadLimitLabel(effectiveUploadLimit(upload.caps.value));
    const names = rejected.map((t) => t.name).join('、');
    message.warning(
      rejected.length === 1
        ? `${names} 超过单文件上限 ${limit}，未上传`
        : `${rejected.length} 个文件超过单文件上限 ${limit}，未上传：${names}`
    );
  },
  onRenamed: (task) => {
    if (task.finalName && task.finalName !== task.name) {
      message.info(`目标已存在，自动保存为 ${task.finalName}`);
    }
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

const uploadLimitText = computed(() => uploadLimitLabel(effectiveUploadLimit(upload.caps.value)));

/** 上传弹窗是否打开（点击「上传」先暂存，不立即发起请求） */
const uploadOpen = ref(false);

/**
 * 提交暂存列表：先按相对目录在设备端建立目录树，再逐条入队。
 *
 * 目录在此处创建的原因：上传接口只负责写入单个文件，父目录不存在时的行为依赖服务端实现
 * （可能失败，也可能被并入文件名）。显式建树使「文件夹上传」的语义确定。
 * 建目录失败不阻断后续文件 —— 最常见的原因是同名目录已存在，静默跳过即可。
 */
async function onUploadSubmit(list: StagedUpload[]): Promise<void> {
  const base = currentPath.value;
  const relDirs = [...new Set(list.map((i) => i.relDir).filter(Boolean))].sort((a, b) => a.length - b.length);
  for (const rel of relDirs) {
    let acc = base;
    for (const part of rel.split('/').filter(Boolean)) {
      acc = `${acc}/${part}`;
      try {
        await api.post('/api/files/mkdir', { path: acc });
      } catch {
        /* 目录已存在 / 无权限：交给后续上传自己报错，这里不做二次判断 */
      }
    }
  }
  upload.enqueueItems(
    list.map((i) => ({
      file: i.file,
      dir: i.relDir ? `${base}/${i.relDir}`.replace(/\/+$/, '') : base,
    }))
  );
  uploadOpen.value = false;
}

// ── 拖拽上传 ──

/**
 * 拖入层级计数，而非布尔量。
 *
 * 拖拽文件在列表内移动会不断穿过子元素，每次都成对冒泡 `dragleave` 与 `dragenter`；
 * 使用布尔量会在离开子元素的瞬间关闭高亮，表现为反复闪烁。
 * 记录层级、归零才视为真正离开。
 */
const dragDepth = ref(0);
const dropActive = computed(() => dragDepth.value > 0);

/** 仅识别"拖拽的是文件"。缺少该判断时，拖动页内选中的文本也会触发高亮。 */
function isFileDrag(e: DragEvent): boolean {
  const types = e.dataTransfer?.types;
  return !!types && Array.from(types).includes('Files');
}

function onDragEnter(e: DragEvent) {
  if (!isFileDrag(e)) return;
  e.preventDefault();
  dragDepth.value++;
}

function onDragLeave(e: DragEvent) {
  if (!isFileDrag(e)) return;
  dragDepth.value = Math.max(0, dragDepth.value - 1);
}

/**
 * 投放：取出文件并入队。
 *
 * **必须过滤目录**。拖入文件夹时 `dataTransfer.files` 中会出现一项 size 为 0、
 * type 为空的 File，直接上传会在设备端写入一个 0 字节的同名文件。
 * 判定使用 `webkitGetAsEntry().isDirectory`（Chrome / Safari / Firefox 均支持），
 * 无法取得 entry 时以「size 为 0 且 type 为空」兜底。
 *
 * 目录上传需要递归展开并在设备端建立目录树，属于独立功能；此处仅如实告知用户已跳过。
 */
function onDrop(e: DragEvent) {
  dragDepth.value = 0;
  const dt = e.dataTransfer;
  if (!dt) return;

  const items = Array.from(dt.items || []);
  const files = Array.from(dt.files || []);
  const accepted: File[] = [];
  let skippedDirs = 0;

  files.forEach((file, i) => {
    // 按规范 items 与 files 同序，据此取得对应的 entry
    const entry = items[i]?.webkitGetAsEntry?.();
    const isDir = entry ? entry.isDirectory : file.size === 0 && !file.type;
    if (isDir) skippedDirs++;
    else accepted.push(file);
  });

  if (skippedDirs > 0) {
    message.warning(`已跳过 ${skippedDirs} 个文件夹：暂不支持上传整个目录`);
  }
  if (accepted.length > 0) upload.enqueue(accepted);
}

/** 页面卸载时使设备端删除在途的 .ufipart。 */
let removeUnloadGuard: (() => void) | null = null;

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
    // core /read 对二进制文件、超大文件与非文件均返回 200 且 content 为占位文本，
    // 直接编辑保存会把占位文本写回并损坏文件；服务端以 truncated 显式标记该情况
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

// ── 归档操作：解压 / 压缩 / 校验（哈希）/ 复制路径 ──
/**
 * 解压保留 `message.loading`，不进入批量面板 —— 判据同批量一节：
 * `/extract` 是**单请求**（core 内部完成后才返回），不存在"项"，取消 HTTP 也无法停止。
 * 单文件解压通常在数秒内结束，为其提供仅有一行且按钮均不适用的面板并无必要。
 * 已知限制：该请求受全局 axios 的 15s 超时约束（`composables/useApi.ts`），
 * 解压大压缩包时可能出现客户端报失败、而设备端实际成功的情况。
 */
function extractArchive(f: FileEntry) {
  const m = message.loading('正在解压...', { duration: 0 });
  api
    .post('/api/files/extract', { path: f.path })
    .then(({ data }: any) => {
      if (!assertOk(data, '解压失败')) return;
      message.success(`已解压到 ${data.destination}`);
      loadFiles();
      loadDiskUsage();
    })
    .catch((e: any) => message.error(e.response?.data?.error || '解压失败'))
    .finally(() => m.destroy());
}

/** 同 `extractArchive`：单请求，不存在"项"，保留 toast（判据见上） */
function compressItem(f: FileEntry) {
  const m = message.loading('正在压缩...', { duration: 0 });
  api
    .post('/api/files/compress', { paths: [f.path] })
    .then(({ data }: any) => {
      if (!assertOk(data, '压缩失败')) return;
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
    if (!assertOk(data, '校验计算失败')) return;
    checksumResult.value = { path: data.path, algorithms: data.algorithms };
    showChecksumModal.value = true;
  } catch (e: any) {
    message.error(e.response?.data?.error || '校验失败');
  }
}

/** Esc 退出多选：多选为临时状态，必须提供不使用鼠标即可退出的出口 */
function onKeydown(e: KeyboardEvent) {
  if (e.key !== 'Escape') return;
  if (showSearchModal.value) return;
  if (selMode.value) exitSelectMode();
}

onMounted(() => {
  window.addEventListener('keydown', onKeydown);
  removeUnloadGuard = upload.installUnloadGuard();
  // 能力探测 + 加载 L2 续传线索
  upload.ensureCaps().then((c) => upload.loadResumeHints(c.sessionTtlSeconds));
  loadStorageStatus();
  // 推送摘要的首次拉取位于 loadStorageSources() 的 finally 中：其启用判据依赖
  // 该列表，而此处未 await，若紧随其后查询会得到空列表。
  // 15s 轮询由 useRemotePushSummary 自行起停，随组件作用域一起销毁。
  loadStorageSources();
  loadFiles();
  loadDiskUsage();
});

onUnmounted(() => {
  window.removeEventListener('keydown', onKeydown);
  removeUnloadGuard?.();
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
  gap: var(--space-2);
}
.files-header {
  flex: 0 0 auto;
  z-index: 20;
  border: 1px solid var(--border-subtle);
  /* 使用圆角卡片而非仅底边线的平条：平条会使整块顶栏与列表失去层次区分 */
  border-radius: var(--radius-md);
  /* 底色使用 --surface-elevated。`--bg-color` 从未定义（取值一路兜底为纯白），
     暗色主题下该顶栏实际一直由 --surface-elevated 生效，故直接使用后者。 */
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
  gap: var(--space-2);
  padding-bottom: var(--space-1);
  /* 拖拽投放区的定位基准（提示层是 absolute） */
  position: relative;
}
/* 拖拽文件悬停时：虚线描边与轻微底色。不使用实线，以免与列表卡片的边框混淆 */
.files-body.is-drop-target {
  outline: 2px dashed var(--accent-color);
  outline-offset: -4px;
  border-radius: var(--radius-md);
  background: var(--accent-color-light);
}
.drop-hint {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 10;
  font-size: var(--font-md);
  font-weight: 600;
  color: var(--accent-color);
  /* 必须不拦截 drop 事件：否则松手时事件落在提示层上，而该层没有 drop 处理 */
  pointer-events: none;
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

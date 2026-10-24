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
        @upload="onPickUpload"
        @open-storage-devices="showStorageDevicesModal = true"
      />
    </div>

    <!-- 列表滚动区：顶栏下方独立滚动，内容不会再钻到顶栏底下

         同时是拖拽上传的投放区。三件事必须一起做对：
         · `dragover` 必须 `preventDefault()`，否则浏览器不认这是投放区（drop 根本不触发）；
         · 用**计数器**而不是布尔量跟踪拖入/拖出 —— 拖过子元素时 dragleave/dragenter
           会成对冒泡，用布尔量会让高亮疯狂闪烁；
         · 只在真的拖着文件时才高亮（`dataTransfer.types` 含 `Files`），
           否则页内选中文本随便一拖就亮一片。 -->
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

      <!-- 批量复制/移动/删除的常驻进度条（2026-10-14）：队列与重试在 useBatchOperation 里，
           状态是模块级的 —— 关弹窗、换目录、切页面再回来都还在（刷新页面才会丢） -->
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

      <!-- 远端推送摘要（2026-10-14）：上传到远端是两段式，第二段（core → 远端）原来只在
           作业弹窗打开时可见 —— 关掉弹窗就没进度、也没失败重试入口。这里常驻一条，
           并给一个直接打开作业面板的入口（失败项的「重试」在里面）。 -->
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

    <!-- 存储与外接设备（2026-10-14）：入口是工具栏的存储读数与「更多 → 存储」。
         原来这里是一个 popover，内容长度不可预知时会被顶出视口；现在改成弹窗，
         数据仍由本页持有（打开时刷新一次），弹窗本身不发请求。 -->
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

    <!-- 远端存储源管理（2026-10-14）：入口在「存储」弹窗底部。
         关掉后重拉一次列表 —— 刚新增/停用的源要立刻反映到弹窗里的可浏览入口。 -->
    <StorageSourcesModal v-model:show="showStorageSourcesModal" @update:show="onStorageSourcesClosed" />

    <!-- 远端推送作业面板（2026-10-14）：入口在「存储」弹层里。
         它自己管轮询（只在打开期间，2s），关掉即停 —— 关掉后的可见性由上面的
         RemotePushBanner（15s 摘要轮询，见 useRemotePush.ts）接管，所以不打开它也看得见在跑什么。 -->
    <RemotePushModal v-model:show="showRemotePushModal" />

    <SearchModal
      v-model:show="showSearchModal"
      :current-path="currentPath"
      :initial-query="searchQuery"
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
 * 留在这里的两条时序，挪进弹窗就会变形，见各弹窗的文件头：
 *   · 预览的 100MB 劝退要在**打开之前**判（超限根本不开弹窗）
 *   · 文本编辑的读文件也在打开之前（读失败只弹错误、不开编辑器）
 *
 * ── 关于「批量」必须知道的前提 ──
 * core 的 `/api/files/{copy,move,delete}` **只收单个 source**，没有批量端点，
 * 所以多选后的复制/移动/删除都是**前端串行循环**（见 `pasteHere` / `confirmDeleteSelected`，
 * 队列本体在 `useBatchOperation.ts`，面板在 `BatchProgressPanel.vue`）。
 * 唯一的例外是 `/compress`，它收 `paths: []`，多选压缩是**一次请求**。
 * 2026-10-14：串行循环的进度原来只活在一条 `message.loading` 里（关掉即失忆、不能取消、
 * 失败只能整批重来），现在改成常驻的逐项面板 + 单项重试，判据写在下面「批量操作」那一节。
 *
 * ── 关于「覆盖」必须知道的前提 ──
 * `/copy` 内部用 `REPLACE_EXISTING`（同名直接覆盖），`/move` 遇到目标已存在直接失败。
 * 两者都不做改名，所以**冲突规避必须在客户端完成**（见 `uniqueChildName`）——
 * 否则粘贴到已有同名文件的目录就是「要么丢数据、要么报错」。
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
// 2026-10-14：批量操作的常驻进度条（队列在 useBatchOperation 里）
import BatchProgressPanel from './components/BatchProgressPanel.vue';
// 2026-10-14：远端推送摘要条（第二段进度的常驻可见性）
import RemotePushBanner from './components/RemotePushBanner.vue';
import SearchModal from './components/modals/SearchModal.vue';
import NameInputModal from './components/modals/NameInputModal.vue';
import FileInfoModal from './components/modals/FileInfoModal.vue';
import ChecksumModal from './components/modals/ChecksumModal.vue';
// 2026-10-14：存储与外接设备（原来的工具栏 popover，改成自适应弹窗）
import StorageDevicesModal from './components/modals/StorageDevicesModal.vue';
// 2026-10-14：远端存储源管理（web 侧此前完全没有这块支持）
import StorageSourcesModal from './components/modals/StorageSourcesModal.vue';
// 2026-10-14：远端推送作业面板（上传到远端的第二段，此前完全不可见）
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
/** 列表**读取失败**（与「空目录」是两件事，见 loadFiles 的注释） */
const loadFailed = ref(false);
/** 本机卷读数是否在读取中：存储弹窗用它区分「还没读到」与「真的没有卷」 */
const diskLoading = ref(false);
const selectedPath = ref('');
const searchQuery = ref('');
const sortBy = ref('name');
const disks = ref<any[]>([]);
/** 远端存储源（GET /api/storage/sources）。文件 API 全线接受 `remote:<id>/…`，所以它们与本地卷共用同一套浏览 UI。 */
const storageSources = ref<any[]>([]);
const showStorageSourcesModal = ref(false);
/** 存储与外接设备弹窗（2026-10-14：原工具栏 popover 的明细搬到这里） */
const showStorageDevicesModal = ref(false);
const actionLoading = ref(false);
const fileInfo = ref<FileEntry | null>(null);
const showChecksumModal = ref(false);
const checksumResult = ref<{ path: string; algorithms: Record<string, string> } | null>(null);
const storageDenied = ref(false);
const showSearchModal = ref(false);
/** 视口判据统一走 composable（matchMedia，阈值与全站 768px 一致） */
const isMobile = useIsMobile();
// 2026-10-14：原来的 `pasting` 是个组件局部 ref（粘贴的 loading 状态）。
// 现在它是从模块级批量队列派生出来的 computed —— 切页回来仍然是对的（见批量操作那一节）。

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
// core 的多数写操作返回 HTTP 200 + { success: false, error: "..." }，必须显式判断。
// 判定实现只有一份（`filesShared.assertOk`），这里只注入提示出口 ——
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
    // 失败必须与「空目录」区分开：原来只弹一条 toast，列表区照样渲染「空目录」，
    // 用户看到的是「这个目录没有文件」——比起报错，这是更糟的误导。
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
 * 远端存储源列表（2026-10-14 补）。
 *
 * 只在「存储」弹层里用来给可浏览入口 + 试连/管理，**不影响本地卷那条链路**；
 * 拿不到就当作"没配远端源"（老固件没有 /api/storage/*，静默即可，不要弹错）。
 */
async function loadStorageSources() {
  try {
    const { data } = await api.get('/api/storage/sources');
    storageSources.value = data?.sources || [];
  } catch {
    /* 静默 */
  } finally {
    // 2026-10-14：推送摘要的启用判据是"有没有启用中的远端源"，而 onMounted 里
    // loadStorageSources() 没有 await，紧跟其后问一次的话看到的是**空列表**
    // （原来那个角标就是这么在前 15s 恒为 0 的）。改成源拉完再问一次。
    void push.refresh();
  }
}

/** 点远端源入口：拼成 core 的客户端路径形态 `remote:<id>/`，再走统一导航（白名单已放行 remote:）。 */
function onNavigateToRemote(id: string) {
  enterPath(`${REMOTE_PREFIX}${id}/`);
}

/**
 * 切到另一块本机卷（SD 卡 / U 盘）。
 *
 * 弹窗那边已经先 emit 再关窗；这里只负责走统一白名单与历史栈 ——
 * `enterPath` 会校验 `isAllowedRoot`，并清掉跨目录没有意义的选中态。
 */
function onNavigateToVolume(mount: string) {
  enterPath(mount);
}

/** 弹窗里的「管理远端存储源…」：先关本弹窗再开配置弹窗，避免两层 mask 叠着。 */
function onManageSourcesFromDevices() {
  showStorageDevicesModal.value = false;
  showStorageSourcesModal.value = true;
}

/** 弹窗里的「远端推送作业…」：同上，作业面板自己管 2s 轮询。 */
function onOpenPushFromDevices() {
  showStorageDevicesModal.value = false;
  showRemotePushModal.value = true;
}

/**
 * 打开存储弹窗时刷新一次读数与远端源列表。
 *
 * 磁盘读数只在上传/删除后被动刷新，用户隔一阵点开看到的可能是旧数字；
 * 远端源列表也可能刚在设备端改过。两个请求都**不 await**：弹窗先用现有数据渲染，
 * 回来再就地更新 —— 不会出现"打开弹窗先空一下"。
 */
watch(showStorageDevicesModal, (v) => {
  if (!v) return;
  loadDiskUsage();
  loadStorageSources();
});

/** 存储源弹窗关闭后重拉列表：刚新增 / 停用 / 删除的源要立刻反映到「存储」弹层里的可浏览入口。 */
function onStorageSourcesClosed(v: boolean) {
  if (!v) {
    loadStorageSources();
  }
}

/**
 * 远端推送作业摘要（2026-10-14 升级）。
 *
 * 2026-10-14：这里原来只有一个 `pushActive`（数字），用途仅仅是给「存储」弹层里的
 * 「远端推送作业…」入口挂个角标 —— 于是用户传完文件只看到"上传成功"、远端其实还没有文件，
 * 而**关掉作业弹窗后既没有进度、也没有失败重试入口**（`RemotePushModal` 的轮询是
 * `watch(() => props.show)` 起停的）。现在同一份数据升级成摘要，由列表上方的常驻横幅
 * （`RemotePushBanner`）呈现，并直接给一个打开作业面板的入口。
 *
 * **轮询没有新增第二条**：仍是原来那一条 15s / 同一个端点，只是搬进 `useRemotePushSummary`
 * （它同时保留了"没有启用中的远端源就不打端点"的判断）。
 */
const push = useRemotePushSummary(() => storageSources.value.some((s) => s.enabled !== false));
const pushSummary = push.summary;
const showRemotePushModal = ref(false);

/**
 * 作业面板关掉时立刻问一次摘要：用户在面板里点了「清理已结束」/「重试」之后，
 * 横幅不该等到下一轮 15s 轮询才跟着变。
 */
watch(showRemotePushModal, (v) => {
  if (!v) void push.refresh();
});

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
/**
 * 批量复制 / 移动 / 删除统一走 `useBatchOperation`：逐项状态、取消剩余、失败项单独重试，
 * 面板常驻在列表上方（`BatchProgressPanel`）。
 *
 * 2026-10-14：原来是 `runBatch` + `pasteHere` 各自 `message.loading` 转圈，`ok`/`failed` 是
 * 函数内的局部变量 —— 关掉那条 toast 就再也查不到失败的是哪几项，想中止只能刷新页面
 * （连已经完成的进度一起丢），失败后也只能整批重来。详见 useBatchOperation.ts 的文件头。
 *
 * **这次把 6 处 `message.loading` 逐个过了一遍，判据如下**：
 *   进面板 = 具备「多项 + 逐项成败 + 取消剩余 + 单项重试」四件事的操作，也就是**前端串行循环**
 *     （core 的 `/copy` `/move` `/delete` 都只收单个 source，没有批量端点）。
 *   留 toast = **单请求**操作：core 的 `/compress` 一次收 `paths[]`，`/extract` 与两个安装
 *     都只有一个请求，没有"项"这一维度 —— 中途取消 HTTP 停不下 core 已经开始的压缩/解压/安装，
 *     重试也无法只重试其中一项，硬塞进面板只会做出一个永远只有一行、按钮全都不适用的假面板。
 *
 * 队列为什么串行而不是 `Promise.all`：core 是跑在随身 WiFi 这类弱设备上的单进程，
 * 同时打 N 个文件操作只会让 IO 队列互相拖慢，且并发下的失败原因更难归因。
 */
const batch = useBatchOperation({
  notify: notifyBatch,
  onBatchSettled: (job) => {
    // 整批结束才刷列表：`FileListPanel` 的 loading 是整块遮罩（`n-spin :show`），
    // 逐项刷新会让整批期间遮罩反复闪；逐项进度由常驻面板负责，这里只交代最终结果。
    loadFiles();
    loadDiskUsage();
    exitSelectMode();
    // 粘贴：只有全部成功才清空剪贴板。部分失败时保留 —— 现在面板里每个失败项都有「重试」，
    // 不必再自己回列表里翻出是哪几个失败了（这是 2026-10-14 这次改动的直接动机之一）。
    if ((job.verb === '复制' || job.verb === '移动') && !job.items.some((i) => i.status === 'error')) {
      clearClipboard();
    }
  },
});
const batchJobs = batch.jobs;
const batchCollapsed = batch.collapsed;

/** 队列的提示出口：composable 不直接依赖 naive-ui 的 message 实例（它要能在组件之外继续跑） */
function notifyBatch(kind: 'info' | 'success' | 'warning' | 'error', text: string) {
  if (kind === 'success') message.success(text);
  else if (kind === 'warning') message.warning(text);
  else if (kind === 'error') message.error(text);
  else message.info(text);
}

/** 粘贴按钮的转圈：状态从模块级队列派生，切页回来仍然是对的（原来是个组件局部 ref） */
const pasting = computed(() => batch.activeVerb.value === '复制' || batch.activeVerb.value === '移动');

/**
 * 多选压缩：core `/compress` 收 `paths: []`，**一次请求**，不必循环。
 *
 * 2026-10-14：保留 `message.loading`，不进批量面板 —— 判据见上面那一段（单请求 = 没有"项"）。
 * 已知遗留（不在本次改动范围内）：它走全局 axios 的 15s 超时（`composables/useApi.ts`），
 * 压缩大目录可能"客户端报失败、设备端其实成功"。
 */
async function compressSelected() {
  const items = selectedItems.value;
  if (!items.length) return;
  const m = message.loading('正在压缩…', { duration: 0 });
  try {
    const { data } = await api.post('/api/files/compress', { paths: items.map((i) => i.path) });
    m.destroy();
    // 只提示一次：原来 assertOk 已经弹过一条，这里又补一条 data?.error，同一件事说两遍
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
            // 判定统一走 filesShared.assertOk；失败原因抛给队列，逐项面板上显示 core 原文，
            // 而不是所有失败都压成同一句「操作失败」
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

/**
 * 安装 APK（文件行内 / 详情弹窗里的入口）。
 *
 * 2026-10-14：保留 `message.loading`，不进批量面板 —— 判据同上面批量那一节：这是**单请求**操作，
 * 没有"项"可分（取消 HTTP 停不下 core 的安装，重试也没法只重试其中一项）。
 * 但也正因为它是单请求，这条 toast 只能代表「core 收下了请求」：core 内部还要等
 * `adb install -r` / PackageInstaller 回调（见 core `AppManager.installApk`）。
 * 所以文案改成如实说"已提交"并指向能看结果的地方；**回读 `/api/apps` 确认真的装上了**
 * 放在应用页（那边有版本号可比），不在这里再复制一套轮询。
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
        // core 的失败原因在 `error` 信封里；原先只读 `.message`，于是恒显示 axios 的
        // `Request failed with status code 500`，把 core 说的话丢了
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
 *
 * 2026-10-14：整批交给 `useBatchOperation`（原来是本函数自己循环 + `message.loading`，
 * `ok`/`failed` 都是局部变量，关掉 toast 就查不到失败的是哪几项）。
 * 剪贴板口径不变：只有全部成功才清空；部分失败时保留 —— 但**现在面板里每个失败项都有
 * 「重试」**，不必再自己回列表里翻出是哪几个失败了。
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
  // 目标名在**入队时**算好并快照进 run：重试必须打到同一个目标名，
  // 否则"重试"会再算一次 uniqueChildName（这次 `a (2).txt` 已存在）而变成又复制一份 `a (3).txt`
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
        // 同 confirmDeleteSelected：判定一份、原因带进队列
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
  // 超限文件在入队前就被拦下（一个字节都没发），这里给一条即时提示 ——
  // 只靠面板里那行红字的话，用户可能正盯着文件选择器刚关闭的位置
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

function onPickUpload(files: File[]) {
  upload.enqueue(files);
}

// ── 拖拽上传 ──

/**
 * 拖入层级计数，不是布尔量。
 *
 * 拖着文件在列表里移动时会不断穿过子元素，每次都成对冒泡 `dragleave`+`dragenter`；
 * 用布尔量就会在"离开子元素"的那一刻把高亮关掉，表现为疯狂闪烁。
 * 记层级、归零才算真的离开。
 */
const dragDepth = ref(0);
const dropActive = computed(() => dragDepth.value > 0);

/** 只认"拖的是文件"。不判的话，页内选中一段文字随手一拖也会亮一片。 */
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
 * **必须过滤目录**。拖一个文件夹进来时 `dataTransfer.files` 里会出现一个
 * size 为 0、type 为空的 File，直接传上去会在设备端落一个 0 字节的同名文件 ——
 * 比报错更糟。用 `webkitGetAsEntry().isDirectory` 判（Chrome/Safari/Firefox 都支持），
 * 拿不到 entry 时用「size 0 且无 type」兜底。
 *
 * 目录上传要递归展开 + 在设备端建目录树，是另一个功能，这里只如实告诉用户跳过了。
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
    // items 与 files 同序（规范如此），据此拿到对应的 entry
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

/** 页面卸载时让设备端删在途的 .ufipart。*/
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
/**
 * 2026-10-14：解压保留 `message.loading`，不进批量面板 —— 判据同批量那一节：
 * `/extract` 是**单请求**（core 内部解完才回），没有"项"可分，取消 HTTP 也停不下它。
 * 单个文件的解压确实常常一两秒就结束，为它撑一个只有一行、按钮全不适用的面板不划算。
 * 已知遗留（不在本次改动范围内）：它走全局 axios 的 15s 超时（`composables/useApi.ts`），
 * 解压大压缩包可能"客户端报失败、设备端其实成功"。
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

/** 2026-10-14：同 `extractArchive` —— 单请求、无"项"可分，保留 toast（判据见上） */
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
    if (!assertOk(data, '校验和计算失败')) return;
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
  removeUnloadGuard = upload.installUnloadGuard();
  // 能力探测 + 加载 L2 续传线索
  upload.ensureCaps().then((c) => upload.loadResumeHints(c.sessionTtlSeconds));
  loadStorageStatus();
  // 2026-10-14：推送摘要的首次拉取放在 loadStorageSources() 的 finally 里（它的启用判据
  // 依赖那份列表，而这里没有 await —— 原来就是这么让角标在前 15s 恒为 0 的）。
  // 那一条 15s 轮询现在由 useRemotePushSummary 自己起停，随组件作用域一起销毁。
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
  /* 圆角卡片而不是「只有底边线的平条」：平条会让整块顶栏失去与列表的层次区分 */
  border-radius: var(--radius-md);
  /* 底色接 --surface-elevated。历史上的 `--bg-color` 从未定义过（兜底一路吃到纯白），
     暗色下这条顶栏其实一直是靠 --surface-elevated 生效的 —— 现在直接接它。 */
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
/* 拖着文件悬停时：虚线描边 + 轻微底色。不用实线，避免和列表卡片的边框混淆 */
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
  /* 关键：不能吃掉 drop 事件，否则松手时事件落在提示层上、而它没有 drop 处理 */
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

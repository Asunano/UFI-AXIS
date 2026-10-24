<template>
  <n-spin :show="loading">
    <div class="file-list" :class="{ selmode: selMode }">
      <div
        v-for="f in files"
        :key="f.path"
        class="file-item"
        :class="{ selected: isRowSelected(f) }"
        @click="onRowClick(f)"
        @dblclick="onRowDblClick(f)"
      >
        <!-- 多选模式的勾选态。刻意不用 n-checkbox：整行就是热区，
             再套一个可独立点击的复选框只会带来「点复选框算选中、点行算打开」的歧义 -->
        <span v-if="selMode" class="sel-box" :class="{ on: isSelected(f) }">
          <n-icon><CheckmarkOutline /></n-icon>
        </span>

        <FileKindIcon :file="f" />
        <div class="file-info">
          <span class="file-name" :title="f.name">{{ f.name }}</span>
          <span class="file-meta">{{ fileMeta(f) }}</span>
        </div>

        <!-- 多选态隐藏行内操作：批量动作已上提到顶栏的选择工具条 -->
        <div v-if="!selMode" class="file-actions" @click.stop>
          <!-- 窄屏（≤768）只留「更多」：预览/下载在 ⋮ 菜单里都有，
               把 156px 的文件名区让回来，行内也不会挤成三颗小按钮 -->
          <n-button v-if="canPreview(f)" class="quick" size="tiny" text @click="emit('preview', f)">预览</n-button>
          <n-button v-if="!f.isDirectory" class="quick" size="tiny" text @click="emit('download', f)">下载</n-button>
          <n-dropdown :options="fileActions(f)" trigger="click" @select="(k: string) => emit('action', k, f)">
            <n-button size="tiny" text title="更多操作">
              <template #icon
                ><n-icon><EllipsisHorizontalOutline /></n-icon
              ></template>
            </n-button>
          </n-dropdown>
        </div>
      </div>
      <div v-if="!files.length && !loading" class="empty-state" :class="{ 'is-failed': failed }">
        <template v-if="failed">
          <span>读取失败，没拿到这一层的文件列表。</span>
          <n-button size="tiny" text type="primary" @click="emit('retry')">重试</n-button>
        </template>
        <template v-else>空目录</template>
      </div>
    </div>
  </n-spin>
</template>

<script setup lang="ts">
/**
 * 文件列表。纯展示 + 意图上报：排序/过滤后的数组由页面给，每行的动作只 emit key，
 * 具体请求在页面里。
 *
 * 有两种「选中」语义，别混：
 *  · `selectedPath` —— 普通浏览时的**单选**高亮（点一下选中，供后续操作参考）；
 *  · `selMode + selectedPaths` —— **多选**模式下的勾选集合（批量复制/移动/删除的输入）。
 * 多选态下单选高亮不再参与渲染，否则同一行会出现两种高亮叠加。
 *
 * 空态与失败态**必须分开**：只说「空目录」会把「没读到」误导成「没有文件」，
 * 用户会以为文件被删了。失败态给一个重试入口（`retry` 由页面接回 loadFiles）。
 *
 * 搜索结果不再走本组件：`/search` 的结果必须显示「在哪一层目录」，
 * 而列表行只有文件名 + 元信息（`/search` 连 size 都不返回），同名文件无法区分。
 * 现在统一在 SearchModal 里呈现。
 */
import { canExtract, canPreview, fileMeta, isTextEditable, type FileEntry } from '../filesShared';
import FileKindIcon from './FileKindIcon.vue';
import { CheckmarkOutline, EllipsisHorizontalOutline } from '@vicons/ionicons5';

const props = withDefaults(
  defineProps<{
    files: FileEntry[];
    loading: boolean;
    /** 上一次加载失败（与「空目录」区分，见文件头） */
    failed?: boolean;
    selectedPath: string;
    /** 多选模式：整行点击 = 切换勾选，行内操作隐藏 */
    selMode?: boolean;
    /** 多选模式下已勾选的路径集合 */
    selectedPaths?: string[];
  }>(),
  { failed: false, selMode: false, selectedPaths: () => [] }
);

const emit = defineEmits<{
  (e: 'select', f: FileEntry): void;
  (e: 'open', f: FileEntry): void;
  (e: 'toggle-select', f: FileEntry): void;
  (e: 'preview', f: FileEntry): void;
  (e: 'download', f: FileEntry): void;
  (e: 'action', key: string, f: FileEntry): void;
  (e: 'retry'): void;
}>();

function isSelected(f: FileEntry): boolean {
  return props.selectedPaths.includes(f.path);
}

function isRowSelected(f: FileEntry): boolean {
  return props.selMode ? isSelected(f) : props.selectedPath === f.path;
}

function onRowClick(f: FileEntry) {
  if (props.selMode) emit('toggle-select', f);
  else emit('select', f);
}

/** 多选态下双击不打开：连点两下会把勾选切两次再弹预览，行为不可预期 */
function onRowDblClick(f: FileEntry) {
  if (props.selMode) return;
  emit('open', f);
}

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
    if (canExtract(f)) {
      opts.unshift({ label: '解压', key: 'extract' });
    }
    // 文件专属：校验和 + 压缩成本地 zip
    opts.push({ label: '校验和', key: 'checksum' });
    opts.push({ label: '压缩', key: 'compress' });
  }
  // 文件夹也可整体压缩
  if (f.isDirectory) {
    opts.push({ label: '压缩', key: 'compress' });
  }
  opts.push({ label: '复制路径', key: 'copy-path' });
  opts.push({ label: '删除', key: 'delete' });
  return opts;
}
</script>

<style scoped>
.file-list {
  border: 1px solid var(--border-subtle);
  border-radius: var(--radius-md);
  overflow: hidden;
}
.file-item {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  padding: var(--space-2) var(--space-3);
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
  /* 左侧强调条：不只靠底色表达选中（底色在暗色下对比度低） */
  box-shadow: inset 3px 0 0 var(--accent-color);
}
.sel-box {
  flex-shrink: 0;
  width: 20px;
  height: 20px;
  border-radius: var(--radius-pill);
  border: 1.5px solid var(--border-subtle);
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-size: var(--font-base);
  color: transparent;
  transition:
    background 0.12s ease,
    border-color 0.12s ease;
}
.sel-box.on {
  background: var(--accent-color);
  border-color: var(--accent-color);
  color: var(--card-bg);
}
.file-info {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.file-name {
  font-size: var(--font-md);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.file-meta {
  font-size: var(--font-sm);
  color: var(--text-muted);
  font-variant-numeric: tabular-nums;
  /* 元信息是「大小 · 时间」这类短文本，但窄屏列宽可能只有 150px：
     允许在任意字符处断行，宁可两行也不横向撑破行 */
  overflow-wrap: anywhere;
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
/* 触屏没有 hover：行内操作必须常驻可见，否则在手机上等于不存在 */
@media (hover: none) {
  .file-actions {
    opacity: 1;
  }
}

/**
 * 窄屏的退化策略（2026-10-14）：
 *   · 行内「预览 / 下载」隐藏 —— 它们在 ⋮ 菜单里是同一套动作（见 fileActions），
 *     不是丢功能，只是把位置让给文件名；
 *   · 行距收紧一档，让一屏多显示一行；
 *   · 文件名仍用 ellipsis（长名不换行撑高行），悬停/长按有 title 看全名。
 *
 * 断点 2026-10-21 由 480 并回 768（全站只留 768/1024 两档）。
 */
@media (max-width: 768px) {
  .file-actions .quick {
    display: none;
  }
  .file-item {
    padding: var(--space-2);
  }
}
.empty-state {
  padding: 48px 16px;
  text-align: center;
  color: var(--text-muted);
  font-size: var(--font-md);
}
/* 失败态：一句话 + 一个重试入口，竖排居中 */
.empty-state.is-failed {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--space-2);
  color: var(--text-secondary);
}
</style>

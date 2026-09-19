<template>
  <div class="toolbar" :class="{ 'is-mobile': isMobile }">
    <!-- ────────── 行1：导航 · 地址栏 · 搜索 ────────── -->
    <div class="tb-row tb-row1">
      <div class="tb-nav">
        <n-button quaternary :size="btnSize" title="后退" :disabled="!canBack" @click="emit('nav', 'back')">
          <template #icon
            ><n-icon><ArrowBackOutline /></n-icon
          ></template>
        </n-button>
        <n-button quaternary :size="btnSize" title="前进" :disabled="!canForward" @click="emit('nav', 'forward')">
          <template #icon
            ><n-icon><ArrowForwardOutline /></n-icon
          ></template>
        </n-button>
        <n-button quaternary :size="btnSize" title="刷新" @click="emit('nav', 'refresh')">
          <template #icon
            ><n-icon><RefreshOutline /></n-icon
          ></template>
        </n-button>
      </div>

      <!-- 地址栏。刻意不用 n-breadcrumb：窄屏要能横向滚动并默认滚到最右（见下方 CSS） -->
      <div ref="addrRef" class="tb-address" :title="currentPath">
        <template v-for="(seg, i) in crumbs" :key="seg.path">
          <span v-if="i" class="sep">/</span>
          <span class="seg" :class="{ current: i === crumbs.length - 1 }" @click="onCrumb(i)">{{ seg.label }}</span>
        </template>
      </div>

      <!-- 桌面：输入框（回车转交搜索弹窗）；移动端：图标按钮 -->
      <n-input
        v-if="!isMobile"
        :value="searchQuery"
        class="tb-search"
        :size="btnSize"
        placeholder="搜索文件…"
        clearable
        @update:value="(v: string) => emit('update:searchQuery', v)"
        @keyup.enter="emit('open-search')"
      >
        <template #prefix
          ><n-icon><SearchOutline /></n-icon
        ></template>
      </n-input>
      <n-button v-else quaternary :size="btnSize" title="搜索" @click="emit('open-search')">
        <template #icon
          ><n-icon><SearchOutline /></n-icon
        ></template>
      </n-button>
    </div>

    <!-- ────────── 行2：功能栏 / 选择工具条（互斥，高度恒定） ────────── -->
    <div class="tb-row tb-row2">
      <div v-if="!selMode" class="tb-funcs">
        <!-- 创建：唯一强调色主操作，置首 -->
        <n-dropdown :options="createOptions" trigger="click" @select="onCreate">
          <n-button type="primary" :size="btnSize">
            <template #icon
              ><n-icon><AddOutline /></n-icon
            ></template>
            创建
          </n-button>
        </n-dropdown>

        <!-- 原生 input 而不是 n-upload：n-upload 自己发 XHR、只能带静态请求头，
             而 core 对 /api/files/upload 强校验每次都变的时间戳签名（必然 444）；
             它内建的进度条又依赖它自己那条请求。所以只借它选文件的能力已无意义，
             直接用隐藏 input，请求交给 FilesView 的上传队列。 -->
        <input ref="fileInputRef" class="tb-file-input" type="file" multiple @change="onFilePicked" />
        <n-button quaternary :size="btnSize" title="上传文件到当前目录" @click="fileInputRef?.click()">
          <template #icon
            ><n-icon><CloudUploadOutline /></n-icon
          ></template>
          上传
        </n-button>

        <n-button
          quaternary
          :size="btnSize"
          title="多选（进入后可复制 / 移动 / 压缩 / 删除）"
          @click="emit('tool', 'multi')"
        >
          <template #icon
            ><n-icon><CheckboxOutline /></n-icon
          ></template>
          多选
        </n-button>

        <n-dropdown :options="sortOptions" trigger="click" @select="onSort">
          <n-button quaternary :size="btnSize" title="排序方式">
            <template #icon
              ><n-icon><SwapVerticalOutline /></n-icon
            ></template>
            排序
          </n-button>
        </n-dropdown>

        <n-dropdown :options="moreOptions" trigger="click" @select="onMore">
          <n-button quaternary :size="btnSize" title="更多">
            <template #icon
              ><n-icon><EllipsisHorizontalOutline /></n-icon
            ></template>
            更多
          </n-button>
        </n-dropdown>
      </div>

      <!-- 选择模式工具条 -->
      <div v-else class="tb-selbar">
        <n-button quaternary :size="btnSize" @click="emit('tool', 'exit-select')">
          <template #icon
            ><n-icon><CloseOutline /></n-icon
          ></template>
          取消
        </n-button>
        <span class="sel-count"
          >已选 <b>{{ selCount }}</b> / {{ totalCount }} 项</span
        >
        <n-button quaternary :size="btnSize" :disabled="!totalCount" @click="emit('tool', 'select-all')">全选</n-button>
        <n-button quaternary :size="btnSize" :disabled="!selCount" @click="emit('tool', 'invert')">反选</n-button>
        <span class="tb-divider" />
        <n-button quaternary :size="btnSize" :disabled="!selCount" @click="emit('tool', 'copy-selected')">
          <template #icon
            ><n-icon><CopyOutline /></n-icon
          ></template>
          复制
        </n-button>
        <n-button quaternary :size="btnSize" :disabled="!selCount" @click="emit('tool', 'move-selected')">
          <template #icon
            ><n-icon><MoveOutline /></n-icon
          ></template>
          移动
        </n-button>
        <n-button quaternary :size="btnSize" :disabled="!selCount" @click="emit('tool', 'compress-selected')">
          <template #icon
            ><n-icon><ArchiveOutline /></n-icon
          ></template>
          压缩
        </n-button>
        <n-button
          quaternary
          type="error"
          :size="btnSize"
          :disabled="!selCount"
          @click="emit('tool', 'delete-selected')"
        >
          <template #icon
            ><n-icon><TrashOutline /></n-icon
          ></template>
          删除
        </n-button>
      </div>

      <!-- 存储读数（点击展开明细）。数据全部来自 core /api/files/disk-usage -->
      <n-popover v-if="primaryDisk && !selMode" trigger="click" placement="bottom-end" :show-arrow="false">
        <template #trigger>
          <div class="tb-storage" :class="{ hot: primaryDiskHot }" title="点击查看存储明细">
            <span class="tb-bar">
              <i
                :style="{
                  width: primaryDisk.usedPct + '%',
                  background: primaryDiskHot ? 'var(--error)' : 'var(--accent-color)',
                }"
              />
            </span>
            <span class="tb-storage-txt">{{ primaryDisk.available }} 可用 / {{ primaryDisk.size }}</span>
          </div>
        </template>

        <div class="disk-pop">
          <template v-for="(d, i) in diskRows" :key="d.mount">
            <div v-if="i" class="disk-pop-divider" />
            <div class="disk-pop-head">{{ d.label }}</div>
            <div class="disk-pop-num">{{ d.usedPct }}<small>% 已用</small></div>
            <div class="disk-pop-bar">
              <i
                :style="{ width: d.usedPct + '%', background: d.usedPct > 90 ? 'var(--error)' : 'var(--accent-color)' }"
              />
            </div>
            <div class="disk-pop-rows">
              <div class="disk-pop-row">
                <span>可用空间</span><b>{{ d.available }}</b>
              </div>
              <div class="disk-pop-row">
                <span>已用空间</span><b>{{ d.used }}</b>
              </div>
              <div class="disk-pop-row">
                <span>总容量</span><b>{{ d.size }}</b>
              </div>
              <div class="disk-pop-row">
                <span>挂载点</span><b class="mono">{{ d.mount }}</b>
              </div>
            </div>
          </template>
        </div>
      </n-popover>
    </div>
  </div>
</template>

<script setup lang="ts">
/**
 * 文件页顶栏 —— 固定双行：
 *   行1  后退 / 前进 / 刷新 · 地址栏 · 搜索
 *   行2  创建 / 上传 / 多选 / 排序 / 更多 · 存储读数
 *
 * 为什么是双行：单行要放下 5 个功能按钮 + 地址栏 + 搜索，窄屏只能靠 flex 换行兜底，
 * 结果就是「元素挤在一起、行序随机」（前几轮实测踩过）。双行让每行的职责固定，
 * 窄屏只需重排而不是让它自己决定。
 *
 * 行2 与选择工具条**互斥**（不是叠一行）：多选态下高度不变，列表不会跳动。
 *
 * 本组件只做「显示 + 上报」：数据与写操作全在 FilesView，上传也一样 ——
 * 选完文件只 emit File[]，队列/进度/取消在 `useFileUpload`（见那份文件头）。
 */
import { computed, nextTick, ref, watch } from 'vue';
import type { DropdownOption } from 'naive-ui';
import { breadcrumbSegments, diskPercent, normalizePath } from '../filesShared';
import {
  AddOutline,
  ArchiveOutline,
  ArrowBackOutline,
  ArrowForwardOutline,
  CheckboxOutline,
  CloseOutline,
  CloudUploadOutline,
  CopyOutline,
  EllipsisHorizontalOutline,
  MoveOutline,
  RefreshOutline,
  SearchOutline,
  SwapVerticalOutline,
  TrashOutline,
} from '@vicons/ionicons5';

const props = withDefaults(
  defineProps<{
    currentPath: string;
    searchQuery: string;
    sortBy: string;
    disks?: any[];
    isMobile?: boolean;
    canBack?: boolean;
    canForward?: boolean;
    selMode?: boolean;
    selCount?: number;
    /** 当前可见条目数（隐藏文件过滤后），用于「全选 / 已选 N / 总数」 */
    totalCount?: number;
    clipboardCount?: number;
    hiddenShown?: boolean;
  }>(),
  {
    disks: () => [],
    isMobile: false,
    canBack: false,
    canForward: false,
    selMode: false,
    selCount: 0,
    totalCount: 0,
    clipboardCount: 0,
    hiddenShown: false,
  }
);

const emit = defineEmits<{
  (e: 'update:searchQuery', v: string): void;
  (e: 'update:sortBy', v: string): void;
  (e: 'nav', key: 'back' | 'forward' | 'refresh'): void;
  (e: 'navigate', index: number): void;
  (e: 'open-search'): void;
  (e: 'create', key: 'mkdir' | 'touch'): void;
  (e: 'tool', key: string): void;
  (e: 'upload', files: File[]): void;
}>();

const SORT_LABEL: Record<string, string> = { name: '名称', size: '大小', date: '日期', type: '类型' };

const crumbs = computed(() => breadcrumbSegments(props.currentPath));

/** naive 的 medium = 34px、large = 40px；移动端放大到 40px 以满足触控目标 */
const btnSize = computed<'medium' | 'large'>(() => (props.isMobile ? 'large' : 'medium'));

const createOptions: DropdownOption[] = [
  { label: '新建文件夹', key: 'mkdir' },
  { label: '新建文件', key: 'touch' },
];

const sortOptions = computed<DropdownOption[]>(() =>
  Object.entries(SORT_LABEL).map(([key, label]) => ({
    label,
    key,
    // 当前排序项打勾（naive 用 icon 槽表达选中，这里借 props 上色避免额外样式）
    props: { style: key === props.sortBy ? 'color: var(--accent-color); font-weight: 600' : '' },
  }))
);

/**
 * 「更多」菜单。分组而不是平铺：剪贴板 / 选择 / 视图 是三件不同的事。
 *
 * 刻意**不放**「新建文件夹 / 上传」——它们已经是行2 上的常驻按钮，
 * 再进菜单就是同一个动作两处入口（前几轮的教训：菜单里塞太多常用项 = 没人用菜单）。
 *
 * 括号里的是实时状态：粘贴项显示剪贴板项数、排序项显示当前依据、隐藏项显示当前开关。
 * 全部用 label 文本表达，不走自定义渲染 —— 下拉内容会被 teleport 到 body，
 * scoped 样式够不着，自绘行反而要往 main.css 里加全局类。
 */
const moreOptions = computed<DropdownOption[]>(() => {
  const cb = props.clipboardCount;
  return [
    {
      type: 'group',
      label: '剪贴板',
      key: 'g-clip',
      children: [
        {
          label: cb ? `粘贴到当前目录（${cb} 项）` : '粘贴到当前目录（剪贴板为空）',
          key: 'paste',
          disabled: !cb,
        },
        { label: '清空剪贴板', key: 'clear-clipboard', disabled: !cb },
      ],
    },
    {
      type: 'group',
      label: '选择',
      key: 'g-sel',
      children: [
        { label: '全选并进入多选', key: 'select-all', disabled: !props.totalCount },
        { label: '反选', key: 'invert', disabled: !props.totalCount },
        { label: '进入多选模式', key: 'multi' },
      ],
    },
    {
      type: 'group',
      label: '视图',
      key: 'g-view',
      children: [
        { label: `排序方式：${SORT_LABEL[props.sortBy] || '名称'}`, key: 'cycle-sort' },
        { label: props.hiddenShown ? '隐藏隐藏文件' : '显示隐藏文件', key: 'toggle-hidden' },
        { label: '刷新列表', key: 'refresh' },
      ],
    },
  ];
});

// ── 地址栏 ──
const addrRef = ref<HTMLElement | null>(null);

/** 窄屏地址栏可横向滚动：默认滚到最右，保证「当前目录」始终可见 */
function scrollAddrToEnd() {
  nextTick(() => {
    const el = addrRef.value;
    if (el) el.scrollLeft = el.scrollWidth;
  });
}

watch(() => props.currentPath, scrollAddrToEnd, { immediate: true });

function onCrumb(i: number) {
  // 最后一段就是当前目录，点了没有意义也不该有反馈
  if (i < 0 || i >= crumbs.value.length - 1) return;
  const crumb = crumbs.value[i];
  if (!crumb) return;
  if (normalizePath(crumb.path) === normalizePath(props.currentPath)) return;
  emit('navigate', i);
}

function onCreate(key: string) {
  emit('create', key as 'mkdir' | 'touch');
}
function onSort(key: string) {
  emit('update:sortBy', key);
}
function onMore(key: string) {
  emit('tool', key);
}

// ── 存储读数 ──
const diskRows = computed(() =>
  props.disks.map((d) => ({
    label: d.label || d.mount || '存储',
    mount: String(d.mount || ''),
    size: d.size || '--',
    used: d.used || '--',
    available: d.available || '--',
    usedPct: diskPercent(d),
  }))
);

const primaryDisk = computed(() => diskRows.value[0] || null);
const primaryDiskHot = computed(() => (primaryDisk.value ? primaryDisk.value.usedPct > 90 : false));

// ── 上传 ──
const fileInputRef = ref<HTMLInputElement | null>(null);

/**
 * 选完文件只上报，不发请求。
 * 选完后必须清空 `input.value`：否则再选同一个文件不会触发 change（浏览器认为值没变），
 * 表现为「第二次点上传没反应」。
 */
function onFilePicked(e: Event) {
  const input = e.target as HTMLInputElement;
  const files = Array.from(input.files || []);
  input.value = '';
  if (files.length) emit('upload', files);
}
</script>

<style scoped>
/**
 * 配色/圆角/间距/字号一律走令牌（main.css :root），不写死取值 ——
 * 写死值不跟暗色与换肤走，且会顶破 `scripts/check-ui-baseline.mjs` 的增量门禁。
 * 按钮沿用 n-button（quaternary / primary）：语义色由 naiveTheme.ts 从同一批 CSS 变量派生，
 * 暗色下文字色自动正确，自绘按钮反而要自己处理这套。
 */
/* 隐藏的文件选择器：只借它弹系统选择框，视觉入口是旁边那颗按钮 */
.tb-file-input {
  display: none;
}

.toolbar {
  display: flex;
  flex-direction: column;
}

.tb-row {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  padding: var(--space-2) var(--space-3);
  min-width: 0;
}
.tb-row1 {
  border-bottom: 1px solid var(--border-subtle);
}
.tb-row2 {
  gap: var(--space-1);
}

/* ── 行1 左：导航 ── */
.tb-nav {
  display: flex;
  align-items: center;
  gap: 2px;
  flex: 0 0 auto;
}

/* ── 行1 中：地址栏 ── */
.tb-address {
  /* flex-basis 必须是 0 而不是 auto：取 auto 时基准宽度=内容宽，
     窄屏（≤375）会因「导航 + 内容宽」超行宽而被挤到下一行（实测顶栏变 3 行）。
     basis:0 让它纯粹按剩余空间分配，必然与导航同排。 */
  flex: 1 1 0;
  min-width: 0;
  display: flex;
  align-items: center;
  gap: 2px;
  height: 34px;
  padding: 0 var(--space-3);
  border: 1px solid var(--border-subtle);
  border-radius: var(--radius-sm);
  background: var(--surface-elevated);
  overflow-x: auto;
  overflow-y: hidden;
  /* 可横向滚动但不出滚动条 */
  scrollbar-width: none;
}
.tb-address::-webkit-scrollbar {
  display: none;
}
.tb-address .seg {
  font-size: var(--font-base);
  color: var(--text-secondary);
  padding: 2px var(--space-1);
  border-radius: var(--radius-sm);
  cursor: pointer;
  white-space: nowrap;
}
.tb-address .seg:hover {
  background: var(--surface-hover);
  color: var(--text-primary);
}
.tb-address .seg.current {
  color: var(--text-primary);
  font-weight: 600;
  cursor: default;
}
.tb-address .seg.current:hover {
  background: transparent;
}
.tb-address .sep {
  color: var(--text-muted);
  flex: 0 0 auto;
  user-select: none;
  font-size: var(--font-base);
}
.is-mobile .tb-address {
  height: 40px;
}

/* ── 行1 右：搜索 ── */
.tb-search {
  flex: 0 0 240px;
}

/* ── 行2：功能栏 ── */
.tb-funcs {
  display: flex;
  align-items: center;
  gap: var(--space-1);
  flex: 1 1 auto;
  min-width: 0;
}
.tb-selbar {
  display: flex;
  align-items: center;
  gap: var(--space-1);
  flex: 1 1 auto;
  min-width: 0;
}
.sel-count {
  font-size: var(--font-base);
  color: var(--text-secondary);
  white-space: nowrap;
  margin: 0 var(--space-1);
}
.sel-count b {
  color: var(--accent-color);
}
.tb-divider {
  width: 1px;
  height: 20px;
  background: var(--border-subtle);
  margin: 0 var(--space-1);
  flex-shrink: 0;
}

/* ── 行2 右：存储读数 ── */
.tb-storage {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  padding: 0 var(--space-2);
  height: 34px;
  border-radius: var(--radius-sm);
  margin-left: auto;
  flex: 0 0 auto;
  cursor: pointer;
  transition: background 0.12s ease;
}
.tb-storage:hover {
  background: var(--surface-hover);
}
.tb-bar {
  width: 68px;
  height: 6px;
  border-radius: var(--radius-pill);
  background: var(--border-subtle);
  overflow: hidden;
  display: flex;
  flex-shrink: 0;
}
.tb-bar > i {
  display: block;
  height: 100%;
  transition: width 0.4s ease;
}
.tb-storage-txt {
  font-size: var(--font-xs);
  color: var(--text-muted);
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
}

/* ── 存储明细浮层 ── */
.disk-pop {
  width: 264px;
}
.disk-pop-head {
  font-size: var(--font-sm);
  color: var(--text-muted);
}
.disk-pop-num {
  font-size: var(--font-2xl);
  font-weight: 600;
  line-height: 1.1;
  color: var(--text-primary);
  font-variant-numeric: tabular-nums;
  margin-top: var(--space-1);
}
.disk-pop-num small {
  font-size: var(--font-sm);
  font-weight: 400;
  color: var(--text-muted);
  margin-left: var(--space-1);
}
.disk-pop-bar {
  height: 9px;
  border-radius: var(--radius-pill);
  background: var(--border-subtle);
  overflow: hidden;
  margin: var(--space-3) 0;
}
.disk-pop-bar > i {
  display: block;
  height: 100%;
  transition: width 0.4s ease;
}
.disk-pop-rows {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
}
.disk-pop-row {
  display: flex;
  align-items: center;
  font-size: var(--font-sm);
  color: var(--text-secondary);
}
.disk-pop-row b {
  margin-left: auto;
  color: var(--text-primary);
  font-weight: 500;
  font-variant-numeric: tabular-nums;
}
.disk-pop-row b.mono {
  font-size: var(--font-xs);
  color: var(--text-muted);
}
.disk-pop-divider {
  height: 1px;
  background: var(--border-subtle);
  margin: var(--space-3) 0;
}

/* ── 断点：全站只用 1024 / 768 两档（main.css 的断点约定）── */
@media (max-width: 1024px) {
  .tb-search {
    flex-basis: 180px;
  }
}

@media (max-width: 768px) {
  .tb-row {
    padding: var(--space-2);
    gap: var(--space-2);
  }
  /* 行2 在手机上换行（而不是把 5 个按钮压成极小或撑出横向滚动）：
     功能栏独占一行，存储读数随后换到第二行 */
  .tb-row2 {
    flex-wrap: wrap;
  }
  .tb-funcs,
  .tb-selbar {
    flex: 1 1 100%;
  }
  .tb-storage {
    margin-left: 0;
  }
}

@media (max-width: 480px) {
  /* 极窄屏：功能栏/选择条横向滚动兜底，保证每个按钮都是完整可点尺寸 */
  .tb-funcs,
  .tb-selbar {
    overflow-x: auto;
    flex-wrap: nowrap;
    scrollbar-width: none;
  }
  .tb-funcs::-webkit-scrollbar,
  .tb-selbar::-webkit-scrollbar {
    display: none;
  }
  .tb-storage {
    display: none;
  }
}
</style>

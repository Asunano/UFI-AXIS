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
        <!-- 创建：唯一强调色主操作，置首。

             行2 上的三个下拉共用一套约束（2026-10-22，完整理由见 script 里的 menuProps
             与文件末尾那段非 scoped 样式）：
             · to="body"      —— 显式钉在 body 下。naive 默认就是 body，但嵌在弹窗/抽屉里
                                时它会改投到那一层的 body（常带 overflow 裁剪），写死省心；
             · placement      —— 决定菜单往触发器的哪一侧展开。行2 在窄屏是可横向滚动的
                                一排按钮，越靠右的按钮越要用 end 对齐，否则菜单右侧会
                                顶出 360px 的视口；
             · menu-props     —— 给菜单根节点挂「高度上限 + 内部滚动」，「更多」默认
                                约 450px 高，矮视口里 naive 会把它翻到触发器上方。 -->
        <n-dropdown
          :options="createOptions"
          trigger="click"
          placement="bottom-start"
          to="body"
          :menu-props="menuProps"
          @select="onCreate"
        >
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

        <n-dropdown
          :options="sortOptions"
          trigger="click"
          placement="bottom-start"
          to="body"
          :menu-props="menuProps"
          @select="onSort"
        >
          <n-button quaternary :size="btnSize" title="排序方式">
            <template #icon
              ><n-icon><SwapVerticalOutline /></n-icon
            ></template>
            排序
          </n-button>
        </n-dropdown>

        <!-- 「更多」用 bottom-end：它是行2 最右的按钮，居中对齐会让菜单右半边出屏 -->
        <n-dropdown
          :options="moreOptions"
          trigger="click"
          placement="bottom-end"
          to="body"
          :menu-props="menuProps"
          @select="onMore"
        >
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

      <!--
        存储读数（点击打开「存储与外接设备」弹窗）。

        2026-10-14：这里原来是一个 n-popover，点开就地展开所有磁盘读数 + 远端源。
        内容长度不可预知（两块盘就 ~600px 高），popover 没有 max-height，窄屏/矮屏
        直接顶出视口；出屏后既滚不到也关不掉。现在只保留读数，明细交给
        StorageDevicesModal（有 mask + 自身滚动区，360px 也完整可看）。

        触发按钮用原生 button 而不是 div：键盘可聚焦、回车可开，
        外观由下方 .tb-storage 清零（appearance / border / font 全继承）。
      -->
      <button
        v-if="primaryDisk && !selMode"
        type="button"
        class="tb-storage"
        :class="{ hot: primaryDiskHot }"
        title="存储与外接设备（本机卷 / SD 卡 / U 盘 / 远端源）"
        @click="emit('open-storage-devices')"
      >
        <span class="tb-bar">
          <i
            :style="{
              width: primaryDisk.usedPct + '%',
              background: primaryDiskHot ? 'var(--error)' : 'var(--accent-color)',
            }"
          />
        </span>
        <span class="tb-storage-txt">{{ primaryDisk.available }} 可用 / {{ primaryDisk.size }}</span>
      </button>
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
    /** 进行中的远端推送作业数（`GET /api/files/remote-push` 的 active_count），0 = 没有。 */
    pushActive?: number;
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
    pushActive: 0,
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
  /** 2026-10-14：打开「存储与外接设备」弹窗（明细从 popover 搬进弹窗）。 */
  (e: 'open-storage-devices'): void;
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

/**
 * 行2 三个下拉共用的菜单根节点 props —— naive 会把这个返回值 merge 到
 * `.n-dropdown-menu` 上（`Dropdown.mjs` 的 `renderPopoverBody`），也是给 teleport
 * 到 body 的菜单挂样式的唯一入口（scoped 选择器够不着）。
 *
 * ── 2026-10-22：为什么要给菜单加高度上限 ──
 *
 * 先排除误判：「更多」的菜单**不是**被容器裁剪。n-dropdown 默认就把菜单 teleport 到
 * body（这里再显式写一次 `to="body"`），行2 的 `overflow-x: auto`、`.files-header`
 * 的圆角卡片都裁不到它。真正的问题是**菜单本身高过视口**：
 *
 *   「更多」= 4 个分组标题 + 9 个条目，naive 默认行高 34px ⇒ 约 450px 高，
 *   而菜单根节点没有 max-height/overflow。它从行2 按钮底边（距页面顶部约 174px）
 *   向下展开：视口高 ≤ 约 620px 时下方就放不下它（手机浏览器带地址栏时更早触发）。
 *   这时 naive 的 flip（vueuc Follower）会比较「下方剩余」与「上方剩余」，下方不够
 *   就翻到触发器**上方** —— top 变成负值，顶部几项直接出屏；翻不动时则被视口底边切掉。
 *   两种情况都滚不到，因为滚动的是菜单自己（没有滚动区）而不是页面（页面本来就不该
 *   为下拉滚动）。
 *
 * 上限取 `100vh - 200px`：200px 是「页面顶部 → 行2 按钮底边」的上界
 * （应用顶栏 56 + 页面内距 14~24 + 工具栏两行约 112），因此这个上限恒小于触发器
 * 下方的可用空间 ⇒ flip 不会触发，菜单底边也留在视口内。具体声明在文件末尾的
 * 非 scoped 样式里（那边才能用 `dvh` 兜底手机地址栏，见那里的注释）。
 */
const menuProps = () => ({ class: 'files-toolbar-dropdown-menu' });

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
    {
      // 2026-10-14：存储明细从 popover 搬进弹窗后，这里补一个**文本入口**。
      // 手机（≤480px）上存储读数只占窄窄一条，菜单里这一项是更明确的发现路径。
      type: 'group',
      label: '存储',
      key: 'g-storage',
      children: [
        {
          label: props.pushActive ? `存储与外接设备（推送 ${props.pushActive} 个进行中）…` : '存储与外接设备…',
          key: 'open-storage-devices',
        },
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
  // 存储弹窗不是「页面工具」，不走 tool 那条分发；在这里直接开窗
  if (key === 'open-storage-devices') {
    emit('open-storage-devices');
    return;
  }
  emit('tool', key);
}

// ── 存储读数 ──
/**
 * 顶栏只显示第一块盘（内部存储）的读数；完整明细在 StorageDevicesModal 里。
 * 2026-10-14：这里原来还负责把 core 的 disks 整理成弹层行、并承接卷/远端源切换；
 * 那些逻辑连同 n-popover 一起搬进弹窗后，本组件只留「显示 + 一个打开动作」。
 */
const primaryDisk = computed(() => {
  const d = props.disks[0];
  if (!d) return null;
  return {
    label: d.label || d.mount || '存储',
    mount: String(d.mount || ''),
    size: d.size || '--',
    available: d.available || '--',
    usedPct: diskPercent(d),
  };
});
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

/* ── 行2 右：存储读数 ──
   2026-10-14：由 div 改成原生 button（键盘可聚焦、回车可开），所以这里要清掉
   浏览器默认外观；配色仍全部走令牌，hover/focus 只借用 surface/ring 两组既有变量。 */
.tb-storage {
  appearance: none;
  display: flex;
  align-items: center;
  gap: var(--space-2);
  padding: 0 var(--space-2);
  height: 34px;
  border: none;
  border-radius: var(--radius-sm);
  background: transparent;
  color: inherit;
  font: inherit;
  text-align: left;
  margin-left: auto;
  flex: 0 0 auto;
  cursor: pointer;
  transition: background 0.12s ease;
}
.tb-storage:hover {
  background: var(--surface-hover);
}
.tb-storage:focus-visible {
  outline: 2px solid var(--accent-ring);
  outline-offset: 2px;
}
.is-mobile .tb-storage {
  height: 40px;
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
  max-width: 180px;
  overflow: hidden;
  text-overflow: ellipsis;
  font-size: var(--font-xs);
  color: var(--text-muted);
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
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
    /* 2026-10-14：选择工具条有 8 个控件，481~768px 之间（例如 600px 宽）
       整行约 760px，原来既不换行也不滚动，直接顶破页面产生横向滚动条。
       这里改成「行高不变 + 容器内横向滚动」，与 ≤480 同一套兜底。 */
    flex-wrap: nowrap;
    overflow-x: auto;
    scrollbar-width: none;
  }
  .tb-funcs::-webkit-scrollbar,
  .tb-selbar::-webkit-scrollbar {
    display: none;
  }
  .tb-storage {
    margin-left: 0;
  }
  /* 窄屏不再隐藏存储入口（原来 display:none，手机上根本进不去外接设备）。
     它此时独占一行，进度条 + 一行读数放得下；菜单里另有一项同名入口。
     断点 2026-10-21 由 480 并回 768（全站只留 768/1024 两档）。 */
  .tb-storage-txt {
    max-width: 150px;
  }
  .tb-bar {
    width: 52px;
  }
}
</style>

<!--
  非 scoped：n-dropdown 的菜单被 teleport 到 body，scoped 选择器落不到它身上，
  只能靠 `menu-props` 挂一个类名（见 script 里的 menuProps）。前缀唯一，只服务本组件。
-->
<style>
/**
 * 行2 三个下拉的菜单根节点（2026-10-22）。
 *
 * `max-height` 是这次修复的核心：出屏的条目由菜单自己滚，而不是靠页面滚
 * （页面本来就不为下拉留滚动）。上限为什么取 `100vh - 200px`、为什么不能只靠
 * naive 的 flip，见 script 里 `menuProps` 上方那段。
 *
 * 两行 max-height 是有意的：手机浏览器里 `100vh` 取的是地址栏收起时的高度，
 * 拿它当上限仍可能高过可见区；支持 `dvh` 的浏览器用第二行，不支持的由第一行兜底
 * （与 main.css 的 `.modal-fit` 同一套写法）。
 */
.files-toolbar-dropdown-menu {
  /* ui-baseline-allow: 200px 是「应用顶栏 56 + 页面内距 14~24 + 工具栏两行约 112」的布局上界，令牌表里没有"页面顶部到工具按钮底边"这个量；它约束的是视口余量，不是版式间距 */
  max-height: calc(100vh - 200px);
  max-height: calc(100dvh - 200px);
  /* 菜单内容比上限高时出现滚动条，全部条目都滚得到 */
  overflow-y: auto;
  /* 滚到菜单尽头不把滚动接力给页面（否则手指一滑整页跟着动） */
  overscroll-behavior: contain;
}
</style>

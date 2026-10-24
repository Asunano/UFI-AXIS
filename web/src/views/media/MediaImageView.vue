<!--
  图片页。与 app 的 `MediaImageScreen` / `MediaImageViewerScreen` 对应。

  方格网格（1:1 裁切）+ 全屏查看器。查看器里可左右翻页，键盘 ←/→/Esc 同样可用 ——
  看图是连续动作，每张都要退回列表再点下一张是最让人烦的交互。

  ──── 为什么查看器显示的是缩略图而不是原图 ────
  **不是**。查看器换票据取原始字节（`fetchStreamUrl`），缩略图只做网格。
  原图可能是几十 MB 的 RAW/PNG，所以在原图到达前先把已有缩略图放大占位，
  避免大图加载期间一片空白。

  2026-10-14 本轮补齐（对齐 app，逐条见各自的注释）：
  · **时间轴按天分段**（今天 / 昨天 / 9月18日 / 2025年…，口径见 `components/mediaTimeline.ts`，
     只在按时间排序时画段头）；
  · **文件夹视图**（页头「时间轴 / 文件夹」切换，共用 `components/MediaFolderBrowser.vue`）；
  · **排序**下推到 core（`MediaSortControl` + `setSort`）；
  · **重新扫描**接上并延迟补拉（`@rescanned` → `mediaRescanRefresh`）；
  · **查看器缩放**：双指缩放 + 单指拖动 + 双击 1×↔2×，上限 6×，回到 1× 复位平移。

  2026-10-22 查看器改成**灯箱**：整块深色底、无卡片头/边框，文件名与翻页位置进灯箱顶栏，
  详情表从主区挪到可收起的底部条（默认收起，见 `.detail-panel` 的说明）。
-->
<template>
  <div class="media-list-page">
    <div class="page-head">
      <div class="head-text">
        <h2 class="head-title">图片</h2>
        <span class="head-sub">{{ headSub }}</span>
      </div>
      <div class="head-actions">
        <!-- 2026-10-14：两栏（时间轴 / 文件夹）。与 app 的 `MediaImageScreen.kt:31-37` 同构
             （那两栏的字面量就叫「时间轴」「文件夹」），默认仍是时间轴 ——
             文件夹是**并列的第二个入口**，不是"文件夹视图替换掉时间轴"。 -->
        <n-radio-group v-model:value="view" size="small" class="view-switch">
          <n-radio-button value="grid">时间轴</n-radio-button>
          <n-radio-button value="folder">文件夹</n-radio-button>
        </n-radio-group>
        <!-- 搜索只作用于时间轴（文件夹视图有自己的逐层导航），切到文件夹时收起 -->
        <n-input
          v-if="view === 'grid'"
          v-model:value="query"
          size="small"
          placeholder="搜索文件名"
          clearable
          class="head-search"
        />
        <!-- 排序：**刻意不写 `v-model="sort"`** —— v-model 会直接给那个 ref 赋值，
             绕过 `useMediaLibrary.setSort` 里那次 reload（换了排序列表不动）。
             它必须下推到 core，见 `mediaShared.fetchMediaPage` 的说明。 -->
        <MediaSortControl :model-value="sort" @update:model-value="setSort" />
        <n-button size="small" quaternary :loading="loading" @click="refreshActive">刷新</n-button>
        <!-- 2026-10-14：图片域的设置回图片页。与视频页是**同一个组件、同一种触发方式**，
             只有 kind 与按钮文案不同（视频页叫「扫描范围」，因为那边还有一个「封面设置」）。 -->
        <MediaScanDirsSettings kind="image" label="设置" @saved="onScanDirsSaved" @rescanned="onRescanned" />
      </div>
    </div>

    <!-- 两个视图都在 DOM 里，用 v-show 切：时间轴留着（已取到的缩略图不必重取），
         文件夹视图首次打开才挂载（见 folderEverOpened） -->
    <MediaEmptyState
      v-if="showEmpty"
      v-show="view === 'grid'"
      :permission-denied="permissionDenied"
      :failed="failed"
      kind-label="图片"
      @retry="reload"
    />

    <n-spin v-else v-show="view === 'grid'" :show="loading && items.length === 0">
      <!-- 按天分段（2026-10-14）。**只在按时间排序时画段头**：按名称/大小排之后相邻两项的
           日期是乱的，插段头会得到一串只含一项的段（app 侧同一条判据，见
           `MediaImageScreen.kt:39-43`），那时退化成原来那个平铺网格。 -->
      <div v-if="timelineOn" class="timeline">
        <section v-for="sec in sections" :key="sec.key" class="day-section">
          <h3 class="day-head">{{ sec.label }}</h3>
          <div class="tile-grid">
            <button
              v-for="entry in sec.entries"
              :key="entry.item.id"
              type="button"
              class="tile"
              @click="openAt(entry.index)"
            >
              <img
                v-if="thumbs[entry.item.id]"
                :src="thumbs[entry.item.id]"
                :alt="entry.item.name"
                class="tile-img"
                loading="lazy"
              />
              <span v-else class="tile-fallback">
                <n-icon :size="22"><ImageOutline /></n-icon>
              </span>
            </button>
          </div>
        </section>
      </div>
      <div v-else class="tile-grid">
        <button v-for="(p, i) in filtered" :key="p.id" type="button" class="tile" @click="openAt(i)">
          <img v-if="thumbs[p.id]" :src="thumbs[p.id]" :alt="p.name" class="tile-img" loading="lazy" />
          <span v-else class="tile-fallback">
            <n-icon :size="22"><ImageOutline /></n-icon>
          </span>
        </button>
      </div>
      <div v-if="hasMore()" class="load-more">
        <n-button size="small" quaternary :loading="loadingMore" @click="loadMore">
          加载更多（{{ items.length }} / {{ total }}）
        </n-button>
      </div>
    </n-spin>

    <!-- 文件夹视图：与时间轴并列的第二种视图，共用组件（见 components/MediaFolderBrowser.vue） -->
    <MediaFolderBrowser
      v-if="folderEverOpened"
      v-show="view === 'folder'"
      kind="image"
      :sort="sort"
      :refresh-key="refreshKey"
      @open="openFromFolder"
    />

    <!-- 灯箱查看器（2026-10-22）。
         改前是 `preset="card"` 弹窗：卡片头（文件名）、边框、圆角把图和控件一起框住。
         看图要的是一块干净的深色底，所以现在**没有 preset** —— 卡片头/边框随之消失，
         文件名与翻页位置改由灯箱自己的 `.lb-top` 承担。
         **无 preset 的 n-modal 要求 default slot 有且只有一个根节点**（子节点数 ≠ 1 时
         naive 直接不渲染，连遮罩一起 —— 见 MediaVideoView 同一段说明）。
         所以往里加东西请加在 `.lightbox` **里面**，不要再并列第二个兄弟节点。
         宽高仍走 `.modal-fit` + `--modal-w` / `--modal-h`：这里传的是视口值，
         实际宽度被收口到 `100vw - 32px`（≤768px 时 -24px）；四周那一圈就是遮罩，
         点它关闭（`mask-closable` 默认 true），Esc 也由 naive 处理（`close-on-esc` 默认 true）。
         高度不在模板里算：`.lightbox` 的 `height: 100dvh` 会作为 flex 子项被
         `.modal-fit` 的 max-height 压回视口内（见 `.lightbox` 的 CSS）。 -->
    <n-modal
      :show="viewerIndex >= 0"
      class="modal-fit"
      style="--modal-w: 100vw; --modal-h: 100dvh"
      @update:show="close"
    >
      <div class="lightbox">
        <div class="lb-top">
          <span class="lb-title" :title="viewerItem?.name">{{ viewerItem?.name || '' }}</span>
          <span class="lb-pos">{{ positionText }}</span>
          <button type="button" class="lb-btn lb-close" title="关闭（Esc）" aria-label="关闭" @click="close">
            <n-icon :size="18"><CloseOutline /></n-icon>
          </button>
        </div>

        <div class="lb-main">
          <button class="nav-btn nav-prev" :disabled="viewerIndex <= 0" title="上一张" @click="step(-1)">
            <n-icon :size="22"><ChevronBackOutline /></n-icon>
          </button>
          <!-- 手势面：**只有它**接 pointer 事件，翻页钮是它的兄弟节点（绝对定位浮在两侧），
               所以"拖动/缩放"永远抢不走翻页点击 —— app 那边要在手势里专门判 `scale > 1`
               才不吃水平拖动（`MediaImageViewerScreen.kt:116-136`），这里靠 DOM 结构就不必判。
               另：`touch-action: none` 让浏览器别把这套手势当成滚动/双指缩放页面。
               它同时是"点空白关闭"的落点：1× 时点在图片以外的留白上 = 点遮罩。 -->
          <div
            ref="stageEl"
            class="viewer-stage"
            :class="{ 'is-zoomed': scale > 1 }"
            @pointerdown="onPointerDown"
            @pointermove="onPointerMove"
            @pointerup="onPointerUp"
            @pointercancel="onPointerUp"
            @dblclick="onDoubleClick"
            @wheel.prevent="onWheel"
            @click="onStageClick"
          >
            <!-- 原图未到达时先把缩略图放大顶住，别留一片空白 -->
            <img
              v-if="fullUrl"
              :src="fullUrl"
              :alt="viewerItem?.name"
              class="viewer-img"
              :style="imgStyle"
              draggable="false"
            />
            <img
              v-else-if="viewerThumb"
              :src="viewerThumb"
              :alt="viewerItem?.name"
              class="viewer-img is-placeholder"
              :style="imgStyle"
              draggable="false"
            />
            <div v-else class="viewer-loading"><n-spin size="small" /></div>
          </div>
          <button
            class="nav-btn nav-next"
            :disabled="viewerIndex < 0 || viewerIndex >= viewerList.length - 1"
            title="下一张"
            @click="step(1)"
          >
            <n-icon :size="22"><ChevronForwardOutline /></n-icon>
          </button>
        </div>

        <!-- 缩放手势没有可见入口：给一行说明 + 当前倍数（鼠标用户尤其需要知道"双击"）。
             控件用自绘按钮而不是 n-button：它们压在常暗底上，naive 按钮的字色跟主题走，
             亮色主题下会是深字压深底（见 `.lb-btn` 的豁免说明）。 -->
        <div class="lb-bottom">
          <span class="lb-hint">
            {{ scale > 1 ? `${scaleText} · 双击复位` : '双击放大 · 双指缩放 · 拖动平移' }}
          </span>
          <span class="lb-actions">
            <button v-if="scale > 1" type="button" class="lb-btn" @click="resetZoom">复位</button>
            <button
              type="button"
              class="lb-btn"
              :class="{ 'is-on': detailOpen }"
              :aria-expanded="detailOpen"
              @click="detailOpen = !detailOpen"
            >
              {{ detailOpen ? '收起详情' : '详情' }}
            </button>
          </span>
        </div>

        <!-- 详情表**不在**主区：它与照片抢的正是纵向空间（矮屏上图的可用高度会被压掉一半）。
             收起时灯箱只有图；展开后是底部一条信息条，自己滚。 -->
        <div v-if="detailOpen && viewerItem" class="detail-panel">
          <div class="detail-grid">
            <InfoRow v-for="row in detailRows" :key="row.label" :label="row.label" :value="row.value" />
          </div>
        </div>
      </div>
    </n-modal>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue';
import { useMessage } from 'naive-ui';
import { ImageOutline, ChevronBackOutline, ChevronForwardOutline, CloseOutline } from '@vicons/ionicons5';
import { fetchStreamUrl, formatMediaDate, formatMediaSize, type MediaItem } from './mediaShared';
import { useMediaLibrary } from './useMediaLibrary';
import { groupMediaByDay } from './components/mediaTimeline';
import { useRescanRefresh } from './components/mediaRescanRefresh';
import MediaEmptyState from './components/MediaEmptyState.vue';
import MediaFolderBrowser from './components/MediaFolderBrowser.vue';
import MediaScanDirsSettings from './components/MediaScanDirsSettings.vue';
import MediaSortControl from './components/MediaSortControl.vue';
import InfoRow from '@/components/InfoRow.vue';

const message = useMessage();
const {
  api,
  items,
  total,
  loading,
  loadingMore,
  permissionDenied,
  failed,
  thumbs,
  sort,
  hasMore,
  reload,
  loadMore,
  setSort,
} = useMediaLibrary('image');

/**
 * 视图：`grid` = 时间轴（原有网格 + 按天分段），`folder` = 文件夹（2026-10-14 新增）。
 * 与 app 的两栏同名同序（`MediaImageScreen.kt:131`：`listOf("时间轴", "文件夹")`）。
 */
const view = ref<'grid' | 'folder'>('grid');
/**
 * 文件夹视图是否被打开过。
 *
 * 模板上是 `v-if="folderEverOpened" v-show="view === 'folder'"`：没打开过就不挂载
 * （省掉一次用户根本没要的 `/browse` 请求），打开过之后只隐藏不卸载 ——
 * 否则每次切回时间轴再切回来，用户都要从根目录重新走一遍
 * （app 侧对应 `MediaImageScreen.kt:50-52` 那条"两栏滚动位置提到切栏之上"的修法）。
 */
const folderEverOpened = ref(false);
watch(view, (v) => {
  if (v === 'folder') folderEverOpened.value = true;
});

/** 重扫之后自增，用来让文件夹视图也补拉一次（见 onRescanned）。 */
const refreshKey = ref(0);

const { refreshAfterRescan } = useRescanRefresh();
/**
 * 「重新扫描」之后补拉列表。core 侧扫描是**异步**的（立刻返回、后台慢慢扫），
 * 立刻重拉只会拿到旧列表；三次补拉的节奏与理由见 `components/mediaRescanRefresh.ts`。
 */
function onRescanned() {
  refreshAfterRescan(() => {
    reload();
    refreshKey.value += 1;
  });
}

/**
 * 扫描范围保存成功之后重拉。
 *
 * 比原来多刷新了文件夹视图：扫描目录就是 `/browse` 的 roots（`browseRootsOf`），
 * 改完不重拉的话，文件夹视图里还会列着刚被移除的那个根、或者少一个刚加的。
 */
function onScanDirsSaved() {
  reload();
  refreshKey.value += 1;
}

/**
 * 页头的「刷新」按**当前视图**刷新。
 *
 * 时间轴（`/list`）与文件夹视图（`/browse`）是两份数据、各有自己的刷新入口，
 * 但页头这颗按钮在两种视图下都看得见 —— 在文件夹视图里点它没反应，
 * 是最容易被当成"坏了"的那种。所以它按当前视图分流。
 */
function refreshActive() {
  if (view.value === 'folder') refreshKey.value += 1;
  else reload();
}

const query = ref('');
/** -1 = 查看器关闭。存**下标**而不是条目：左右翻页要的就是下标。 */
const viewerIndex = ref(-1);
/**
 * 查看器翻页的范围。
 *
 * 打开时**定下来**（`slice()` 一份）：时间轴点开 = 当前筛选结果，文件夹点开 = 当前那一层。
 * 不长期持有 `filtered` —— 用户看到一半改了搜索词，翻页范围会整个变掉。
 */
const viewerList = ref<MediaItem[]>([]);
const fullUrl = ref('');

const filtered = computed(() => {
  const q = query.value.trim().toLowerCase();
  return q ? items.value.filter((p) => p.name.toLowerCase().includes(q)) : items.value;
});

/** 按天分段。**不重排**（顺序是 core 给的），见 `mediaTimeline.ts`。 */
const sections = computed(() => groupMediaByDay(filtered.value));
/**
 * 是否画段头。
 *
 * **只在按时间排序时画**：按名称/大小排之后相邻两项的日期是乱的，插段头会得到
 * 一串只含一项的段（app 侧同一条判据，见 `MediaImageScreen.kt:39-43`）。
 * 那时退化成平铺网格 —— 与本次改动之前完全一样。
 */
const timelineOn = computed(() => sort.value.key === 'date');

const viewerItem = computed(() => (viewerIndex.value >= 0 ? viewerList.value[viewerIndex.value] : undefined));
const viewerThumb = computed(() => (viewerItem.value ? thumbs.value[viewerItem.value.id] || '' : ''));
/** 顶栏右上角的「当前 / 共几张」。翻页范围是打开那一刻定下来的 `viewerList`。 */
const positionText = computed(() =>
  viewerIndex.value >= 0 ? `${viewerIndex.value + 1} / ${viewerList.value.length}` : ''
);
/**
 * 详情条是否展开（2026-10-22）。
 *
 * 默认**收起**：灯箱的第一职责是把图看大，详情是"我再看一眼这是哪张、多大"的补充。
 * 与视频页的 `detailOpen` 同一口径（那边也是默认收起、按次点开），
 * 但它不随翻页/关闭复位 —— 用户既然要了详情，翻到下一张通常还想看。
 */
const detailOpen = ref(false);

const showEmpty = computed(
  () => !loading.value && (permissionDenied.value || failed.value || items.value.length === 0)
);

const headSub = computed(() => {
  if (permissionDenied.value) return '未授权';
  return total.value ? `共 ${total.value} 张` : '';
});

const detailRows = computed(() => {
  const p = viewerItem.value;
  if (!p) return [];
  const rows = [
    { label: '路径', value: p.path },
    { label: '大小', value: formatMediaSize(p.size) },
  ];
  if (p.width && p.height) rows.push({ label: '尺寸', value: `${p.width}×${p.height}` });
  rows.push({ label: '修改时间', value: formatMediaDate(p.date_modified) });
  return rows;
});

// ──────────────────────── 缩放 / 平移（2026-10-14） ────────────────────────

/**
 * 手势用 **pointer 事件**实现（而不是 touch 事件）：同一套代码同时覆盖触摸与鼠标 ——
 * 触摸屏上 pointerType 是 `touch`（多指时每个手指一个 pointerId），桌面上是 `mouse`。
 * 与 app 的 `detectTransformGestures` + 双击两态是同一组能力：
 * 双指缩放、单指拖动平移、双击 1×↔2×，**上限 6×**，回到 1× 时复位平移。
 *
 * 变换只写在这两个变量上，最终落到 `<img>` 的 `transform`：
 * 浏览器在合成层做缩放，不触发重排（对应 app 侧"缩放/位移只写在 graphicsLayer 里"）。
 */
const scale = ref(1);
const tx = ref(0);
const ty = ref(0);
/** 手势面（图片本身 + 它的留白）。拖动与缩放的坐标系都相对它的中心。 */
const stageEl = ref<HTMLElement | null>(null);
/** 同时在按下的指针。两指进入捏合，一指是拖动。 */
const pointers = new Map<number, { x: number; y: number }>();
/** 单指拖动的起点快照。 */
let dragStart: { x: number; y: number; tx: number; ty: number } | null = null;
/** 双指捏合的起点快照（两指距离、倍数、位移、两指中点）。 */
let pinchStart: { dist: number; scale: number; tx: number; ty: number; mx: number; my: number } | null = null;
/**
 * 本次按下期间是否真的做过手势（捏合或放大态拖动）。
 *
 * 只给「点空白关闭」用：手势结束后浏览器仍可能补一个 click，
 * 不认这个标记的话，捏合回到 1× 松手就会顺手把灯箱关掉。
 * 每次 pointerdown 复位，见 onPointerDown。
 */
let gestureMoved = false;

/** 双击的放大倍数。与 app 同一个值（双击的语义是"看细节 / 看全貌"两态，不是无级放大）。 */
const DOUBLE_TAP_SCALE = 2;
/** 缩放上限。与 app 的 `coerceIn(1f, 6f)` 一致。 */
const MAX_SCALE = 6;

const imgStyle = computed(() => ({
  transform: `translate(${tx.value}px, ${ty.value}px) scale(${scale.value})`,
}));
const scaleText = computed(() => `${scale.value.toFixed(1)}×`);

/** 手势面的中心与尺寸（相对视口）。所有"以某点为锚"的换算都以它为准。 */
function stageRect() {
  const r = stageEl.value?.getBoundingClientRect();
  return { cx: r ? r.left + r.width / 2 : 0, cy: r ? r.top + r.height / 2 : 0, w: r?.width ?? 0, h: r?.height ?? 0 };
}

/**
 * 把平移夹在合理范围内。
 *
 * 与 app 同一条算式：放大 s 倍后，图片每边多出 `size*(s-1)/2`，位移超过这个量
 * 就会把图拖出屏幕外找不回来。**按手势面的尺寸算而不是图片的实际显示尺寸**：
 * `object-fit: contain` 时图片比容器小，这个上界比"刚好不露黑边"更宽松 ——
 * 宁可多给一点余量，也不要让用户在 2× 时感觉图被"焊死"。
 */
function clampPan() {
  const { w, h } = stageRect();
  const lx = (w * (scale.value - 1)) / 2;
  const ly = (h * (scale.value - 1)) / 2;
  tx.value = Math.min(lx, Math.max(-lx, tx.value));
  ty.value = Math.min(ly, Math.max(-ly, ty.value));
}

function resetZoom() {
  scale.value = 1;
  tx.value = 0;
  ty.value = 0;
}

/**
 * 缩放到 `next` 并让**锚点**（屏幕坐标）下的那一像素留在原地。
 *
 * 变换是 `translate(t) scale(s)`，于是屏幕点 `q`（相对中心）与图片坐标 `p` 的关系是
 * `q = s*p + t`。要让它不动：`t' = q - (s'/s) * (q - t)`。
 * 双击用点击位置、滚轮用光标位置、捏合用两指中点 —— 三处共用这一条。
 */
function zoomAt(next: number, qx: number, qy: number) {
  const clamped = Math.min(MAX_SCALE, Math.max(1, next));
  const ratio = clamped / scale.value;
  tx.value = qx - ratio * (qx - tx.value);
  ty.value = qy - ratio * (qy - ty.value);
  scale.value = clamped;
  if (clamped === 1) {
    // 回到原尺寸就归位，否则图片会歪在一边（与 app 的 `offsetX = 0f` 同一条）
    tx.value = 0;
    ty.value = 0;
    return;
  }
  clampPan();
}

function onPointerDown(e: PointerEvent) {
  // 鼠标只认左键：右键是浏览器菜单、中键是滚动，都不该动图
  if (e.pointerType === 'mouse' && e.button !== 0) return;
  const el = e.currentTarget as HTMLElement;
  // 指针捕获：手指/鼠标滑出手势面之后仍能收到 move/up，否则拖动会在边缘断掉
  el.setPointerCapture(e.pointerId);
  gestureMoved = false;
  pointers.set(e.pointerId, { x: e.clientX, y: e.clientY });
  if (pointers.size === 1) {
    dragStart = { x: e.clientX, y: e.clientY, tx: tx.value, ty: ty.value };
    pinchStart = null;
  } else if (pointers.size === 2) {
    const [a, b] = [...pointers.values()];
    if (!a || !b) return;
    dragStart = null;
    pinchStart = {
      dist: Math.hypot(a.x - b.x, a.y - b.y),
      scale: scale.value,
      tx: tx.value,
      ty: ty.value,
      mx: (a.x + b.x) / 2,
      my: (a.y + b.y) / 2,
    };
  }
}

function onPointerMove(e: PointerEvent) {
  if (!pointers.has(e.pointerId)) return;
  pointers.set(e.pointerId, { x: e.clientX, y: e.clientY });

  if (pointers.size >= 2) {
    const [a, b] = [...pointers.values()];
    if (!a || !b || !pinchStart || pinchStart.dist <= 0) return;
    gestureMoved = true;
    const dist = Math.hypot(a.x - b.x, a.y - b.y);
    const next = Math.min(MAX_SCALE, Math.max(1, (pinchStart.scale * dist) / pinchStart.dist));
    const ratio = next / pinchStart.scale;
    const { cx, cy } = stageRect();
    // 锚点是**按下那一刻**的两指中点，落点是**当前**的两指中点：
    // 前者保证"指下的那一像素不跑"，后者让两指一起移动时图片跟着平移
    // （若把锚点也取成当前中点，两指同向平移会被算成"位移没变"，图就动不了）。
    const q0x = pinchStart.mx - cx;
    const q0y = pinchStart.my - cy;
    const qx = (a.x + b.x) / 2 - cx;
    const qy = (a.y + b.y) / 2 - cy;
    tx.value = qx - ratio * (q0x - pinchStart.tx);
    ty.value = qy - ratio * (q0y - pinchStart.ty);
    scale.value = next;
    if (next === 1) {
      tx.value = 0;
      ty.value = 0;
    } else {
      clampPan();
    }
    return;
  }

  // 单指拖动：**只在放大之后平移**。1× 时图片本来就完整可见，拖动没有意义，
  // 而且会和"轻扫翻页"这类手势打架（与 app 的 `if (scale > 1f)` 同一条）。
  if (dragStart && scale.value > 1) {
    gestureMoved = true;
    tx.value = dragStart.tx + (e.clientX - dragStart.x);
    ty.value = dragStart.ty + (e.clientY - dragStart.y);
    clampPan();
  }
}

function onPointerUp(e: PointerEvent) {
  pointers.delete(e.pointerId);
  if (pointers.size < 2) pinchStart = null;
  if (pointers.size === 1) {
    // 两指抬起一根：用剩下那根重新起一次拖动，否则画面会按"第一根手指的起点"跳一下
    const rest = [...pointers.values()][0];
    dragStart = rest ? { x: rest.x, y: rest.y, tx: tx.value, ty: ty.value } : null;
  } else if (pointers.size === 0) {
    dragStart = null;
  }
}

function onDoubleClick(e: MouseEvent) {
  const { cx, cy } = stageRect();
  if (scale.value > 1) {
    // 放大态下双击 = 复位（两态切换，不是无级放大）
    resetZoom();
    return;
  }
  zoomAt(DOUBLE_TAP_SCALE, e.clientX - cx, e.clientY - cy);
}

function onWheel(e: WheelEvent) {
  const { cx, cy } = stageRect();
  // 1.15 一档：再小要滚很多下才有感觉，再大一档就跳过中间的倍数了
  const factor = e.deltaY < 0 ? 1.15 : 1 / 1.15;
  zoomAt(scale.value * factor, e.clientX - cx, e.clientY - cy);
}

/**
 * 点空白处关闭灯箱（遮罩的点击由 n-modal 自己处理，这里补的是**图片四周的留白** ——
 * 灯箱几乎铺满视口，只剩十几像素的遮罩边，单靠它关闭太难点）。
 *
 * 三个不关的条件：
 *  · 放大态（`scale > 1`）：那时点空白是在"看细节"，而且空白可能就是图片本身被拖走后露出的；
 *  · 本次按下做过手势：捏合/拖动的收尾 click 不是"点空白"（见 gestureMoved）；
 *  · 点在图片上：灯箱里的图片是内容，不是关闭按钮。
 */
function onStageClick(e: MouseEvent) {
  if (scale.value > 1 || gestureMoved) return;
  if ((e.target as HTMLElement | null)?.closest('.viewer-img')) return;
  close();
}

// ──────────────────────── 打开 / 翻页 ────────────────────────

/** 打开查看器。`list` 是翻页范围（时间轴的筛选结果 / 文件夹视图的当前一层）。 */
function openViewer(list: MediaItem[], i: number) {
  if (i < 0 || i >= list.length) return;
  viewerList.value = list.slice();
  viewerIndex.value = i;
}

function openAt(i: number) {
  openViewer(filtered.value, i);
}

/** 从文件夹视图点进来：翻页范围是**那一层**，不是时间轴里那一份（两边内容不同）。 */
function openFromFolder(item: MediaItem, list: MediaItem[]) {
  openViewer(
    list,
    list.findIndex((x) => x.id === item.id)
  );
}

function close() {
  viewerIndex.value = -1;
  fullUrl.value = '';
  // 关掉时把缩放复位：下次打开另一张不该还带着上一次的倍数
  resetZoom();
}

function step(delta: number) {
  const next = viewerIndex.value + delta;
  if (next < 0 || next >= viewerList.value.length) return;
  viewerIndex.value = next;
}

/**
 * 下标一变就换票据取原图，并把缩放复位。
 *
 * 取回来时再比一次下标：连续按方向键会让多个请求在飞，先发的可能后到 ——
 * 不比对就会把上一张的图盖到当前这张上。
 *
 * 缩放**必须跟着复位**（app 侧靠 `key(page)` 重建整棵子树达到同样效果，
 * 见 `MediaImageViewerScreen.kt:37-40`）：放大第 3 张再翻到第 4 张，
 * 保留倍数会让新图一上来就歪在一边。
 */
watch(viewerIndex, async (i) => {
  fullUrl.value = '';
  resetZoom();
  const item = i >= 0 ? viewerList.value[i] : undefined;
  if (!item) return;
  try {
    const url = await fetchStreamUrl(api, item.path);
    if (viewerIndex.value === i) fullUrl.value = url;
  } catch (e: any) {
    message.error(e?.message || '获取图片失败');
  }
});

/** 键盘导航。看图是连续动作，鼠标点小箭头翻几十张太累。 */
function onKey(e: KeyboardEvent) {
  if (viewerIndex.value < 0) return;
  if (e.key === 'ArrowLeft') step(-1);
  else if (e.key === 'ArrowRight') step(1);
}

onMounted(() => {
  reload();
  window.addEventListener('keydown', onKey);
});
onUnmounted(() => window.removeEventListener('keydown', onKey));
</script>

<style scoped>
.media-list-page {
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.page-head {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 16px;
  flex-wrap: wrap;
}
.head-text {
  display: flex;
  align-items: baseline;
  gap: 10px;
  min-width: 0;
}
.head-title {
  margin: 0;
  font-size: 20px;
  font-weight: 700;
  color: var(--text-primary);
}
.head-sub {
  font-size: 12px;
  color: var(--text-muted);
}
.head-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  /* 2026-10-14：页头多了「视图切换」与「排序」两个控件，中等宽度下这一排放不下。
     允许它们在 actions **内部**换行（而不是把标题挤到下一行），
     搜索框在窄一档的媒体查询里再让出宽度（见下面的 @media 1024px）。 */
  flex-wrap: wrap;
  justify-content: flex-end;
}
/* 视图切换：固定宽度，排序档位文案长短不一时页头不跟着抖 */
.view-switch {
  flex: 0 0 auto;
}
.head-search {
  width: 200px;
}
/* 2026-10-14：1024px 以下把搜索框收窄一档 —— 页头现在是
   视图切换 + 搜索 + 排序 + 刷新 + 设置，200px 的搜索框会把它们顶成三行。
   放在 768px 那条之前：那条的 flex:1 需要压过这里的固定宽度。 */
@media (max-width: 1024px) {
  .head-search {
    width: 140px;
  }
}

/* ── 时间轴（2026-10-14：按天分段） ── */
.timeline {
  display: flex;
  flex-direction: column;
}
.day-section {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin-bottom: 12px;
}
/* 段头跟着滚：粘在滚动容器（`.main-content` 里那层 n-layout-scroll-container）顶部。
   背景必须铺满（页面底色），否则下面的缩略图会从字底下透出来。
   段头文案的口径见 components/mediaTimeline.ts（今天 / 昨天 / 9月18日 / 2025年…）。 */
.day-head {
  position: sticky;
  top: 0;
  z-index: 1;
  margin: 0;
  padding: 6px 0;
  background: var(--page-bg);
  font-size: 13px;
  font-weight: 600;
  color: var(--text-secondary);
}

/* 方格墙：图片页不显示文件名（名字对看图没有帮助，缩略图本身就是标识），
   所以格子可以排得更密 */
.tile-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(140px, 1fr));
  gap: 8px;
}
.tile {
  position: relative;
  aspect-ratio: 1;
  padding: 0;
  border: none;
  border-radius: var(--radius-sm);
  overflow: hidden;
  background: var(--surface-elevated);
  cursor: pointer;
  transition:
    transform 0.15s ease,
    box-shadow 0.15s ease;
}
.tile:hover {
  transform: scale(1.02);
  box-shadow: 0 4px 14px var(--shadow-color-soft);
}
.tile-img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}
.tile-fallback {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 100%;
  height: 100%;
  color: var(--text-muted);
}

.load-more {
  display: flex;
  justify-content: center;
  padding: 16px 0;
}

/* ── 灯箱（2026-10-22） ── */
/* 外壳**常暗**：灯箱是"放映"语义 —— 照片本身明暗都有，四周与控件不能跟主题翻转，
   否则亮色主题下白底会从照片边上漏出来、白字控件压在浅色照片上直接读不出。
   这是全站极少数不跟暗色模式走的表面，口径与视频页缩略图上的预热角标一致。
   ui-baseline-allow: 灯箱是放映语义的固定深底，接主题令牌会在亮色主题下漏出白底、压不住照片 */
.lightbox {
  display: flex;
  flex-direction: column;
  /* 定高，让 `.lb-main` 用 flex:1 把图片区撑满。
     **不要**在这里写 calc(100dvh - 48px) 这类与 main.css 重复的魔法数：
     这个元素是 `.modal-fit` 的 flex 子项，会被它的 max-height 压回视口内
     （≤768px 是 -24px 那档）；`min-height: 0` 是能被 flex-shrink 压回去的前提。 */
  height: 100dvh;
  min-height: 0;
  overflow: hidden;
  border-radius: var(--radius-lg);
  background: rgba(10, 10, 12, 0.96);
  color: #f5f5f5;
}
/* 顶栏：文件名 + 翻页位置 + 关闭。原先这三样分别是卡片头、无、卡片头右上角。 */
.lb-top {
  flex: 0 0 auto;
  display: flex;
  align-items: center;
  gap: var(--space-3);
  padding: var(--space-3) var(--space-4);
  min-width: 0;
}
.lb-title {
  flex: 1;
  min-width: 0;
  font-size: var(--font-md);
  font-weight: 600;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
/* 位置计数用等宽数字：翻页时不会因为 9→10 变宽而让整行抖一下 */
.lb-pos {
  flex: 0 0 auto;
  font-size: var(--font-sm);
  font-variant-numeric: tabular-nums;
  opacity: 0.65;
}
/* 图片与翻页钮的定位容器（原 `.viewer`）。翻页钮绝对定位在它两侧。 */
.lb-main {
  position: relative;
  display: flex;
  flex: 1 1 auto;
  min-width: 0;
  min-height: 0;
}
/* 工具条旁的灯箱按钮。自绘而不是 n-button：底色/字色必须与主题无关
   （见 `.lightbox` 那条豁免），n-button 的字色跟主题走。
   ui-baseline-allow: 灯箱控件压在照片上，两种主题下都必须自带深底白字 */
.lb-btn {
  flex: 0 0 auto;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: var(--space-2);
  height: 32px;
  padding: 0 var(--space-3);
  border: none;
  border-radius: var(--radius-pill);
  background: rgba(255, 255, 255, 0.12);
  color: #fff;
  font-size: var(--font-sm);
  cursor: pointer;
  transition: background 0.15s;
}
/* ui-baseline-allow: 同上，悬停只是把同一块半透明白底加深一档 */
.lb-btn:hover {
  background: rgba(255, 255, 255, 0.22);
}
/* 「详情」展开态：同一块底再亮一档，表示这条底部条现在是开着的 */
/* ui-baseline-allow: 同上，开关态靠底色的明暗区分，不引入主题色 */
.lb-btn.is-on {
  background: rgba(255, 255, 255, 0.28);
}
/* 关闭键做成正圆：它在顶栏最右侧，矩形按钮会显得像普通文字按钮 */
.lb-close {
  width: 32px;
  padding: 0;
  border-radius: 50%;
}
/* 工具条：左边是手势说明，右边是「复位 / 详情」。单独成条而不是浮在图上 —— 浮层会挡图，
   而这行字是**给不知道有手势的人看的**，压在图上看不见就失去意义。 */
.lb-bottom {
  flex: 0 0 auto;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-3);
  padding: var(--space-2) var(--space-4) var(--space-3);
}
.lb-hint {
  font-size: var(--font-xs);
  opacity: 0.7;
}
.lb-actions {
  flex: 0 0 auto;
  display: flex;
  align-items: center;
  gap: var(--space-2);
}
/* 详情条：从主区挪出来的信息表（原来塞在图下面）。可收起，展开后自己滚，
   高度上限 40% —— 保证任何时候图片区都还占大半屏。
   底色回到**主题表面**：`InfoRow` 的标签/值用的是 `--text-secondary` / `--text-primary`，
   只有落在 `--card-bg` 上才在明暗两种主题下都成立（塞进灯箱的常暗底会变成深字压深底）。 */
.detail-panel {
  flex: 0 1 auto;
  max-height: 40%;
  overflow: auto;
  padding: var(--space-2) var(--space-4) var(--space-3);
  background: var(--card-bg);
  border-top: 1px solid var(--border-subtle);
}
/* 手势面：图片 + 它的留白。翻页钮是它的**兄弟**（绝对定位浮在两侧），
   所以拖动/缩放不会抢走翻页点击（见模板里的说明）。 */
.viewer-stage {
  position: relative;
  display: flex;
  align-items: center;
  justify-content: center;
  flex: 1 1 auto;
  min-width: 0;
  min-height: 0;
  /* 放大后必须裁掉溢出：不然图会盖到翻页钮与工具条上 */
  overflow: hidden;
  /* 这套手势由本组件自己处理，别让浏览器把它当成页面滚动 / 双指缩放 */
  touch-action: none;
}
.viewer-stage.is-zoomed {
  cursor: grab;
}
.viewer-stage.is-zoomed:active {
  cursor: grabbing;
}
/* 图片吃满手势面（灯箱要的是"能看多大看多大"）：`contain` 保比例，
   上限只由 `.lb-main` 的可用高度（已扣掉顶栏 / 工具条 / 展开的详情条）决定。 */
.viewer-img {
  max-width: 100%;
  max-height: 100%;
  object-fit: contain;
  border-radius: var(--radius-md);
  /* 拖动时不要选中/拖出浏览器原生的图片拖拽影子 */
  user-select: none;
  -webkit-user-drag: none;
}
/* 缩略图顶位期间稍微压暗，让"这还不是原图"看得出来 */
.viewer-img.is-placeholder {
  filter: blur(1px) brightness(0.96);
}
.viewer-loading {
  display: flex;
  align-items: center;
  justify-content: center;
}
/* 翻页钮浮在图两侧。自带深色底：它压在任意画面上，跟着主题走会在浅色图上消失 */
/* 灯箱的左右翻页键压在图片本身上，不是压在主题背景上 ——
   底与字必须在**明暗两种模式下完全一样**，否则暗色下白字落在浅色照片上会糊掉。
   ui-baseline-allow: 浮在图片上的控件，必须自带深底白字，接主题令牌会在亮图上读不出来 */
.nav-btn {
  position: absolute;
  top: 50%;
  transform: translateY(-50%);
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 36px;
  height: 36px;
  border: none;
  border-radius: 50%;
  background: rgba(0, 0, 0, 0.45);
  color: #fff;
  cursor: pointer;
  transition:
    background 0.15s,
    opacity 0.15s;
  z-index: 1;
}
.nav-btn:hover:not(:disabled) {
  /* 同 .nav-btn：悬停只是把同一块深底加深一档
     ui-baseline-allow: 浮在图片上的控件，必须自带深底白字，接主题令牌会在亮图上读不出来 */
  background: rgba(0, 0, 0, 0.66);
}
.nav-btn:disabled {
  opacity: 0.25;
  cursor: not-allowed;
}
.nav-prev {
  left: 8px;
}
.nav-next {
  right: 8px;
}

/* 详情块的容器（行本身的排版由 `InfoRow` 负责，与视频页共用同一份）。
   上下留白由 `.detail-panel` 的内距给，这里不再自带 margin。 */
.detail-grid {
  display: flex;
  flex-direction: column;
}

/* 矮屏（≤720px 高）：顶栏与工具条各收一档留白，把高度让给图片 ——
   只按宽度分档时，720px 高的窗口里两条 chrome 会吃掉近 100px。 */
@media (max-height: 720px) {
  .lb-top {
    padding: var(--space-2) var(--space-4);
  }
  .lb-bottom {
    padding: var(--space-2) var(--space-4);
  }
}

@media (max-width: 768px) {
  /* 页头竖排：360px 视口放不下「标题 + 200px 搜索框 + 刷新」一行 */
  .page-head {
    flex-direction: column;
    align-items: stretch;
    gap: 10px;
  }
  .head-actions {
    justify-content: space-between;
  }
  .head-search {
    /* flex:1 把 flex-basis 置为 0，从而盖掉上面那条 width:200px */
    flex: 1;
    width: auto;
  }
  .tile-grid {
    grid-template-columns: repeat(auto-fill, minmax(100px, 1fr));
    gap: 6px;
  }
  /* 翻页钮在手机上要更大才点得到（44px 是可点区域的常见下限） */
  .nav-btn {
    width: 44px;
    height: 44px;
  }
  /* 灯箱 chrome 收一档内距：左右 12px 与 `.modal-fit` 在窄屏下的边距同档 */
  .lb-top,
  .lb-bottom {
    padding-left: var(--space-3);
    padding-right: var(--space-3);
  }
  /* 触屏上按钮的主要输入是拇指：关闭键与工具条按钮抬到 36px */
  .lb-btn {
    height: 36px;
  }
  .lb-close {
    width: 36px;
  }
  /* 详情条占屏一半：手机上「路径」这类长值一行放不下，行数比桌面多 */
  .detail-panel {
    max-height: 50%;
  }
}
</style>

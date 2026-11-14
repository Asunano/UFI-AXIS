<!--
  图片页。与 app 的 `MediaImageScreen` / `MediaImageViewerScreen` 对应。

  方格网格（1:1 裁切）+ 全屏查看器。查看器支持左右翻页，键盘 ←/→/Esc 同样可用 ——
  看图是连续动作，逐一返回列表再打开下一张的交互成本过高。

  ──── 查看器显示的内容 ────
  查看器显示的是原图，不是缩略图：它通过换票据获取原始字节（`fetchStreamUrl`），
  缩略图只用于网格。原图可能是数十 MB 的 RAW/PNG，因此在原图到达前先用已有缩略图放大占位，
  避免大图加载期间出现空白。

  主要能力（对齐 app，逐条见各自的注释）：
  · **时间轴按天分段**（今天 / 昨天 / 9月18日 / 2025年…，口径见 `components/mediaTimeline.ts`，
     只在按时间排序时绘制段头）；
  · **文件夹视图**（页头「时间轴 / 文件夹」切换，共用 `components/MediaFolderBrowser.vue`）；
  · **排序**下推到 core（`MediaSortControl` + `setSort`）；
  · **重新扫描**并延迟补拉（`@rescanned` → `mediaRescanRefresh`）；
  · **查看器缩放**：双指缩放 + 单指拖动 + 双击 1×↔2×，上限 6×，回到 1× 时复位平移。

  查看器为**灯箱**形态：整块深色底、无卡片头与边框，文件名与翻页位置位于灯箱顶栏，
  详情表从主区移至可收起的底部条（默认收起，见 `.detail-panel` 的说明）。
-->
<template>
  <div class="media-list-page">
    <div class="page-head">
      <div class="head-text">
        <h2 class="head-title">图片</h2>
        <span class="head-sub">{{ headSub }}</span>
      </div>
      <div class="head-actions">
        <!-- 两栏视图（时间轴 / 文件夹）。与 app 的 `MediaImageScreen.kt:31-37` 同构
              （两栏的字面量即「时间轴」「文件夹」），默认仍为时间轴 ——
             文件夹是**并列的第二个入口**，不是「用文件夹视图替换时间轴」。 -->
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
        <!-- 图片域的设置回到图片页。与视频页使用**同一个组件、同一种触发方式**，
             仅 kind 与按钮文案不同（视频页为「扫描范围」，因为那边还有一个「封面设置」）。 -->
        <MediaScanDirsSettings kind="image" label="设置" @saved="onScanDirsSaved" @rescanned="onRescanned" />
      </div>
    </div>

    <!-- 两个视图都存在于 DOM 中，通过 v-show 切换：时间轴保留（已取得的缩略图无需重新请求），
         文件夹视图首次打开时才挂载（见 folderEverOpened） -->
    <MediaEmptyState
      v-if="showEmpty"
      v-show="view === 'grid'"
      :permission-denied="permissionDenied"
      :failed="failed"
      kind-label="图片"
      @retry="reload"
    />

    <n-spin v-else v-show="view === 'grid'" :show="loading && items.length === 0">
      <!-- 按天分段。**只在按时间排序时绘制段头**：按名称/大小排序后相邻两项的日期无序，
           插入段头会得到一串只含一项的分段（app 侧同一判据，见
           `MediaImageScreen.kt:39-43`），此时退化为平铺网格。 -->
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

    <!-- 打开前的原图预载：动画必须等字节解码完再开始，否则第一帧只能是缩略图/空白 -->
    <div v-if="showOpeningIndicator" class="open-loading" role="status" aria-live="polite">
      <n-spin size="small" />
      <span>正在加载原图…</span>
    </div>

    <!-- 灯箱查看器。
         不使用 `preset="card"`：卡片头（文件名）、边框与圆角会把图片和控件一并框住，
         而看图需要一块干净的深色底。因此**不设置 preset**，文件名与翻页位置改由
         灯箱自身的 `.lb-top` 承担。
         **无 preset 的 n-modal 要求 default slot 有且只有一个根节点**（子节点数 ≠ 1 时
         naive 不渲染任何内容，包括遮罩 —— 见 MediaVideoView 中的同一说明）。
         因此新增内容必须放在 `.lightbox` **内部**，不得并列第二个兄弟节点。
         宽高仍由 `.modal-fit` + `--modal-w` / `--modal-h` 控制：此处传入视口值，
         实际宽度收口到 `100vw - 32px`（≤768px 时为 -24px）；四周区域即遮罩，
         点击关闭（`mask-closable` 默认 true），Esc 由 naive 处理（`close-on-esc` 默认 true）。
         高度不在模板中计算：`.lightbox` 的 `height: 100dvh` 作为 flex 子项会被
         `.modal-fit` 的 max-height 压回视口内（见 `.lightbox` 的 CSS）。 -->
    <n-modal
      :show="viewerIndex >= 0"
      class="modal-fit"
      style="--modal-w: 100vw; --modal-h: 100dvh"
      @update:show="close"
    >
      <!-- 开合动画由本地状态（closing + CSS animation）驱动，**不使用** display-directive="show"：
            该指令会让弹窗常驻 DOM，遮罩层在关闭后仍参与命中测试，表现为「关闭后无法再次打开图片」。 -->
      <div v-if="viewerIndex >= 0" class="lightbox" :class="{ 'is-closing': closing }" :style="boxStyle">
        <div class="lb-top">
          <span class="lb-title" :title="viewerItem?.name">{{ viewerItem?.name || '' }}</span>
          <span class="lb-pos">{{ positionText }}</span>
          <button type="button" class="lb-btn lb-close" title="关闭（Esc）" aria-label="关闭" @click="close">
            <n-icon :size="18"><CloseOutline /></n-icon>
          </button>
        </div>

        <div class="lb-main">
          <button class="lb-btn nav-btn nav-prev" :disabled="viewerIndex <= 0" title="上一张" @click="step(-1)">
            <n-icon :size="22"><ChevronBackOutline /></n-icon>
          </button>
          <!-- 手势面：**只有它**接收 pointer 事件，翻页钮是它的兄弟节点（绝对定位浮在两侧），
                 因此拖动/缩放不会抢占翻页点击 —— app 需在手势中判断 `scale > 1`
                 才能避免响应水平拖动（`MediaImageViewerScreen.kt:116-136`），此处由 DOM 结构保证。
                 另外 `touch-action: none` 阻止浏览器将该套手势识别为页面滚动/双指缩放。
                 它同时是「点击空白关闭」的落点：1× 时点击图片以外的留白等同于点击遮罩。 -->
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
            <!--
              缩略图始终垫在底层，原图作为同格覆盖层淡入。
              若以 `v-if=fullUrl` 替换掉缩略图：原图 URL 已到达但字节尚未解码时，
              中间会出现一帧空白，表现为灯箱打开时闪一下。当前两层同时存在：
                · 缩略图已在网格中加载过，灯箱第一帧即有内容；
                · 原图 @load 成功后才将 opacity 从 0 提升到 1，不改变布局，也不替换节点。
            -->
            <img
              v-if="viewerThumb"
              :src="viewerThumb"
              :alt="viewerItem?.name"
              class="viewer-img is-placeholder"
              :style="imgStyle"
              draggable="false"
            />
            <img
              v-if="fullUrl"
              :src="fullUrl"
              :alt="viewerItem?.name"
              class="viewer-img viewer-full"
              :class="{ 'is-loaded': fullLoaded }"
              :style="imgStyle"
              draggable="false"
              @load="onFullLoad"
              @error="onFullError"
            />
            <div v-if="!viewerThumb && !fullLoaded" class="viewer-loading"><n-spin size="small" /></div>
          </div>
          <button
            class="lb-btn nav-btn nav-next"
            :disabled="viewerIndex < 0 || viewerIndex >= viewerList.length - 1"
            title="下一张"
            @click="step(1)"
          >
            <n-icon :size="22"><ChevronForwardOutline /></n-icon>
          </button>

          <!-- 底部工具条压在图片下沿（电影感样式）：渐变遮罩 + 手势提示 + 复位/详情。
                 控件使用自绘按钮而非 n-button：它们位于常暗底上，naive 按钮的字色随主题变化，
                 亮色主题下会形成深色文字压深色底（见 `.lb-btn` 的豁免说明）。 -->
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
        </div>

        <!-- 详情表**不在**主区：它与图片竞争纵向空间（矮屏上图片的可用高度会被压缩一半）。
               收起时灯箱只显示图片；展开后为底部信息条，独立滚动。 -->
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
 * 视图：`grid` = 时间轴（网格 + 按天分段），`folder` = 文件夹。
 * 与 app 的两栏同名同序（`MediaImageScreen.kt:131`：`listOf("时间轴", "文件夹")`）。
 */
const view = ref<'grid' | 'folder'>('grid');
/**
 * 文件夹视图是否被打开过。
 *
 * 模板上是 `v-if="folderEverOpened" v-show="view === 'folder'"`：没打开过就不挂载
 * （省掉一次用户根本没要的 `/browse` 请求），打开过之后只隐藏不卸载 ——
 * 否则每次切回时间轴再切回来，用户都要从根目录重新走一遍
 * （app 侧对应 `MediaImageScreen.kt:50-52` 中「两栏滚动位置提升到切换层之上」的处理）。
 */
const folderEverOpened = ref(false);
watch(view, (v) => {
  if (v === 'folder') folderEverOpened.value = true;
});

/** 重扫之后自增，用来让文件夹视图也补拉一次（见 onRescanned）。 */
const refreshKey = ref(0);

const { refreshAfterRescan } = useRescanRefresh();
/**
 * 「重新扫描」之后补拉列表。core 侧扫描是**异步**的（立即返回、后台执行），
 * 立即重新请求只会得到旧列表；三次补拉的节奏与理由见 `components/mediaRescanRefresh.ts`。
 */
function onRescanned() {
  refreshAfterRescan(() => {
    reload();
    refreshKey.value += 1;
  });
}

/**
 * 扫描范围保存成功之后重新请求。
 *
 * 同时刷新文件夹视图：扫描目录即 `/browse` 的 roots（`browseRootsOf`），
 * 若不刷新，文件夹视图仍会列出已被移除的根目录，或缺少新增的根目录。
 */
function onScanDirsSaved() {
  reload();
  refreshKey.value += 1;
}

/**
 * 页头的「刷新」按**当前视图**刷新。
 *
 * 时间轴（`/list`）与文件夹视图（`/browse`）是两份数据、各有独立的刷新入口，
 * 而页头按钮在两种视图下均可见 —— 若在文件夹视图中点击无响应，容易被判定为故障。
 * 因此按当前视图分流。
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
/** 首次打开前预载好的原图 URL；watch 会直接消费它，跳过第二次 fetch。 */
const pendingUrl = ref('');
/** 预载期间的状态。慢图要有反馈，不然点击像没反应。 */
const opening = ref(false);
const showOpeningIndicator = ref(false);
let openingIndicatorTimer: number | undefined;

const filtered = computed(() => {
  const q = query.value.trim().toLowerCase();
  return q ? items.value.filter((p) => p.name.toLowerCase().includes(q)) : items.value;
});

/** 按天分段。**不重排**（顺序是 core 给的），见 `mediaTimeline.ts`。 */
const sections = computed(() => groupMediaByDay(filtered.value));
/**
 * 是否绘制段头。
 *
 * **只在按时间排序时绘制**：按名称/大小排序后相邻两项的日期无序，插入段头会得到
 * 一串只含一项的分段（app 侧同一判据，见 `MediaImageScreen.kt:39-43`）。
 * 此时退化为平铺网格。
 */
const timelineOn = computed(() => sort.value.key === 'date');

const viewerItem = computed(() => (viewerIndex.value >= 0 ? viewerList.value[viewerIndex.value] : undefined));
const viewerThumb = computed(() => (viewerItem.value ? thumbs.value[viewerItem.value.id] || '' : ''));
/** 顶栏右上角的「当前 / 共几张」。翻页范围是打开那一刻定下来的 `viewerList`。 */
const positionText = computed(() =>
  viewerIndex.value >= 0 ? `${viewerIndex.value + 1} / ${viewerList.value.length}` : ''
);
/**
 * 详情条是否展开。
 *
 * 默认**收起**：灯箱的首要职责是显示尽可能大的图片，详情是对「这是哪张、多大」的补充。
 * 与视频页的 `detailOpen` 同一口径（同样默认收起、按次展开），
 * 但它不随翻页或关闭复位 —— 用户展开详情后，翻到下一张通常仍需要查看。
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

// ──────────────────────── 缩放 / 平移 ────────────────────────

/**
 * 手势使用 **pointer 事件**实现（而不是 touch 事件）：同一套代码同时覆盖触摸与鼠标 ——
 * 触摸屏上 pointerType 为 `touch`（多指时每个手指一个 pointerId），桌面上为 `mouse`。
 * 与 app 的 `detectTransformGestures` + 双击两态是同一组能力：
 * 双指缩放、单指拖动平移、双击 1×↔2×，**上限 6×**，回到 1× 时复位平移。
 *
 * 变换只写在这两个变量上，最终落到 `<img>` 的 `transform`：
 * 浏览器在合成层完成缩放，不触发重排（对应 app 侧「缩放/位移只写入 graphicsLayer」）。
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
 * 不识别该标记时，捏合回到 1× 松手会一并关闭灯箱。
 * 每次 pointerdown 复位，见 onPointerDown。
 */
let gestureMoved = false;
/**
 * 按下时是否落在图片上。
 *
 * `onPointerDown` 会对手势面 `setPointerCapture`，之后浏览器把 click 的 `target`
 * 改写成手势面（而不是图片）—— 只判 `e.target.closest('.viewer-img')` 会失准，
 * 结果就是"点图片 = 点空白 = 关闭灯箱"，双击还没来得及触发 dblclick 灯箱就没了。
 * 这里记按下那一刻的真实落点，供 `onStageClick` 用。
 */
let pressStartedOnImage = false;

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
 * 将平移限制在合理范围内。
 *
 * 与 app 同一算式：放大 s 倍后，图片每边多出 `size*(s-1)/2`，位移超过该值
 * 会把图片拖出屏幕外而无法恢复。**按手势面的尺寸计算，而不是图片的实际显示尺寸**：
 * `object-fit: contain` 时图片小于容器，该上界比「恰好不露出黑边」更宽松 ——
 * 留出一定余量，避免 2× 时平移完全无法移动。
 */
function panLimit(s: number) {
  const { w, h } = stageRect();
  return { lx: (w * (s - 1)) / 2, ly: (h * (s - 1)) / 2 };
}

function clampPanAt(s: number, x: number, y: number): [number, number] {
  const { lx, ly } = panLimit(s);
  return [Math.min(lx, Math.max(-lx, x)), Math.min(ly, Math.max(-ly, y))];
}

function clampPan() {
  [tx.value, ty.value] = clampPanAt(scale.value, tx.value, ty.value);
}

/**
 * 程序化缩放的目标值（双击 / 滚轮），与当前渲染值分开记。
 *
 * 滚轮在 100ms 内会连发很多次；如果每次都拿"正在动画中的 scale"再乘 1.15，
 * 实际倍数会被动画拖慢，滚十格也到不了预期。目标值只由用户输入推进，
 * rAF 只负责把画面追上去。
 */
let zoomGoal = { scale: 1, x: 0, y: 0 };
let zoomFrame = 0;

function syncZoomGoal() {
  zoomGoal = { scale: scale.value, x: tx.value, y: ty.value };
}

function stopZoomAnimation() {
  if (zoomFrame) {
    cancelAnimationFrame(zoomFrame);
    zoomFrame = 0;
  }
  syncZoomGoal();
}

function applyZoomGoal() {
  scale.value = zoomGoal.scale;
  tx.value = zoomGoal.x;
  ty.value = zoomGoal.y;
}

/**
 * 从当前画面缓动到目标。双击 220ms、滚轮 160ms；连续滚轮会在当前帧重新起步，
 * 不会排队播完一长串 1.15 倍。`prefers-reduced-motion` 时直接落位。
 */
function animateZoom(duration = 180) {
  if (zoomFrame) cancelAnimationFrame(zoomFrame);
  zoomFrame = 0;
  const reduce = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
  const from = { scale: scale.value, x: tx.value, y: ty.value };
  if (
    reduce ||
    (Math.abs(from.scale - zoomGoal.scale) < 0.001 &&
      Math.abs(from.x - zoomGoal.x) < 0.5 &&
      Math.abs(from.y - zoomGoal.y) < 0.5)
  ) {
    applyZoomGoal();
    return;
  }
  const t0 = performance.now();
  const tick = (now: number) => {
    // rAF 的时间戳取的是**帧开始**时刻，可能比 requestAnimationFrame 之前记的 t0 还早
    // 一两毫秒；不夹住下界的话 p 会是负数，easeOutCubic 把 scale 推到 1 以下，
    // 双击的第一帧就会反向抖一下（实测 t=2ms 时 0.91×）。
    const p = Math.min(1, Math.max(0, (now - t0) / duration));
    // easeOutCubic：起步快、收尾稳，比线性更符合"放大看细节"的手感
    const k = 1 - Math.pow(1 - p, 3);
    scale.value = from.scale + (zoomGoal.scale - from.scale) * k;
    tx.value = from.x + (zoomGoal.x - from.x) * k;
    ty.value = from.y + (zoomGoal.y - from.y) * k;
    if (p < 1) {
      zoomFrame = requestAnimationFrame(tick);
    } else {
      zoomFrame = 0;
      applyZoomGoal();
    }
  };
  zoomFrame = requestAnimationFrame(tick);
}

function resetZoom() {
  stopZoomAnimation();
  scale.value = 1;
  tx.value = 0;
  ty.value = 0;
  zoomGoal = { scale: 1, x: 0, y: 0 };
}

/**
 * 缩放到 `next` 并让**锚点**（屏幕坐标）下的那一像素留在原地。
 *
 * 变换是 `translate(t) scale(s)`，于是屏幕点 `q`（相对中心）与图片坐标 `p` 的关系是
 * `q = s*p + t`。要让它不动：`t' = q - (s'/s) * (q - t)`。
 * 双击用点击位置、滚轮用光标位置、捏合用两指中点 —— 三处共用这一条。
 *
 * 此处只更新**目标**值，画面由 animateZoom 逐帧跟随；
 * 捏合/拖动仍直接写入 scale/tx/ty（手指位于屏幕上时不能受动画延迟影响）。
 */
function zoomAt(next: number, qx: number, qy: number, duration = 180) {
  // 只取消当前帧，**不得**将 zoomGoal 同步为视觉值：连续滚轮需要在上一次的目标上继续乘，
  // 否则每一格都被"动画走到一半的 scale"拖慢，滚十格也到不了预期倍数。
  if (zoomFrame) {
    cancelAnimationFrame(zoomFrame);
    zoomFrame = 0;
  }
  const clamped = Math.min(MAX_SCALE, Math.max(1, next));
  const ratio = clamped / zoomGoal.scale;
  let nx = qx - ratio * (qx - zoomGoal.x);
  let ny = qy - ratio * (qy - zoomGoal.y);
  if (clamped === 1) {
    // 回到原尺寸就归位，否则图片会歪在一边（与 app 的 `offsetX = 0f` 同一条）
    nx = 0;
    ny = 0;
  } else {
    [nx, ny] = clampPanAt(clamped, nx, ny);
  }
  zoomGoal = { scale: clamped, x: nx, y: ny };
  animateZoom(duration);
}

function onPointerDown(e: PointerEvent) {
  // 鼠标只认左键：右键是浏览器菜单、中键是滚动，都不该动图
  if (e.pointerType === 'mouse' && e.button !== 0) return;
  const el = e.currentTarget as HTMLElement;
  // 指针捕获：手指/鼠标滑出手势面之后仍能收到 move/up，否则拖动会在边缘断掉
  el.setPointerCapture(e.pointerId);
  // 手指/鼠标一落下就接管画面：正在跑的双击/滚轮缓动必须停在当前帧，
  // 否则拖动会和 rAF 抢同一组 scale/tx/ty，表现为"拖一下又弹回去"。
  stopZoomAnimation();
  gestureMoved = false;
  pressStartedOnImage = !!(e.target as HTMLElement | null)?.closest('.viewer-img');
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

  // 单指拖动：**仅在放大后平移**。1× 时图片完整可见，拖动没有意义，
  // 且会与「轻扫翻页」这类手势冲突（与 app 的 `if (scale > 1f)` 同一条判据）。
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
    syncZoomGoal();
  }
}

function onDoubleClick(e: MouseEvent) {
  const { cx, cy } = stageRect();
  if (zoomGoal.scale > 1) {
    // 放大态下双击 = 复位（两态切换，不是无级放大）；复位同样走缓动
    zoomAt(1, 0, 0, 220);
    return;
  }
  zoomAt(DOUBLE_TAP_SCALE, e.clientX - cx, e.clientY - cy, 220);
}

function onWheel(e: WheelEvent) {
  const { cx, cy } = stageRect();
  // 按 delta 连续换算而不是固定 1.15：鼠标滚轮一格 ≈ 0.86×，触控板的小 delta
  // 则连续变化；再配合 animateZoom 的 160ms 追帧，不会一格一跳。
  const factor = Math.exp(-e.deltaY * 0.0015);
  zoomAt(zoomGoal.scale * factor, e.clientX - cx, e.clientY - cy, 160);
}

/**
 * 点空白处关闭灯箱（遮罩的点击由 n-modal 自己处理，这里补的是**图片四周的留白** ——
 * 灯箱几乎铺满视口，仅剩十几像素的遮罩边，仅依赖它关闭难以点击）。
 *
 * 三个不关的条件：
 *  · 放大态（`scale > 1`）：那时点空白是在"看细节"，而且空白可能就是图片本身被拖走后露出的；
 *  · 本次按下做过手势：捏合/拖动的收尾 click 不是"点空白"（见 gestureMoved）；
 *  · 点在图片上：灯箱里的图片是内容，不是关闭按钮。
 */
function onStageClick(e: MouseEvent) {
  if (scale.value > 1 || gestureMoved) return;
  // 指针捕获会把 click.target 改成手势面，必须看按下时的落点（见 pressStartedOnImage）
  if (pressStartedOnImage) return;
  if ((e.target as HTMLElement | null)?.closest('.viewer-img')) return;
  close();
}

// ──────────────────────── 打开 / 翻页 ────────────────────────

/** 打开查看器。`list` 是翻页范围（时间轴的筛选结果 / 文件夹视图的当前一层）。 */
function openViewer(list: MediaItem[], i: number) {
  if (i < 0 || i >= list.length) return;
  // 连点关闭/打开时，上一轮的关闭定时器不能把新开的灯箱关掉
  if (closeTimer !== undefined) {
    window.clearTimeout(closeTimer);
    closeTimer = undefined;
  }
  closing.value = false;
  viewerList.value = list.slice();
  viewerIndex.value = i;
}

/**
 * 等原图真正可绘制后再打开灯箱。
 *
 * 不能只等待 `fetchStreamUrl`：URL 到达后浏览器尚未解码，`<img>` 仍会先空一帧；
 * 也不能只依靠缩略图占位：缩略图通常只有 144px，放大会出现由模糊到清晰的过程。
 * 因此首次打开须等待 `decode()` 完成后再设置 viewerIndex —— 打开动画第一帧即为最终画质。
 */
function preloadImage(url: string): Promise<void> {
  return new Promise((resolve, reject) => {
    const img = new Image();
    img.decoding = 'async';
    img.onload = () => {
      // decode 失败不阻塞打开：图片已经 onload，浏览器至少能画出已解码的部分
      if (typeof img.decode === 'function')
        img.decode().then(
          () => resolve(),
          () => resolve()
        );
      else resolve();
    };
    img.onerror = () => reject(new Error('图片解码失败'));
    img.src = url;
  });
}

async function prepareAndOpen(list: MediaItem[], i: number) {
  if (opening.value || i < 0 || i >= list.length) return;
  const item = list[i];
  if (!item) return;
  opening.value = true;
  openingIndicatorTimer = window.setTimeout(() => {
    showOpeningIndicator.value = true;
  }, 150);
  try {
    const url = await fetchStreamUrl(api, item.path);
    await preloadImage(url);
    pendingUrl.value = url;
    openViewer(list, i);
  } catch (e: any) {
    message.error(e?.message || '原图加载失败');
  } finally {
    opening.value = false;
    if (openingIndicatorTimer !== undefined) {
      window.clearTimeout(openingIndicatorTimer);
      openingIndicatorTimer = undefined;
    }
    showOpeningIndicator.value = false;
  }
}

function openAt(i: number) {
  void prepareAndOpen(filtered.value, i);
}

/** 从文件夹视图点进来：翻页范围是**那一层**，不是时间轴里那一份（两边内容不同）。 */
function openFromFolder(item: MediaItem, list: MediaItem[]) {
  void prepareAndOpen(
    list,
    list.findIndex((x) => x.id === item.id)
  );
}

/**
 * 灯箱盒子尺寸：由条目的 width/height 预先确定宽高比。
 *
 * 若盒子为 fit-content，打开瞬间尚无图片（只有 spinner），盒子会按最小尺寸 320×240 出现，
 * 待原图解码完成后再扩展到真实尺寸，视觉上表现为动画播放中途尺寸突变。
 * 当前在打开时即按真实宽高比确定盒子，占位与成图尺寸一致，动画不会跳变。
 * 无法取得宽高（旧数据 / 异常条目）时回退为 3:2。
 */
const boxStyle = computed<Record<string, string>>(() => {
  const it = viewerItem.value;
  const w = Number(it?.width);
  const h = Number(it?.height);
  const ar = Number.isFinite(w) && Number.isFinite(h) && w > 0 && h > 0 ? w / h : 1.5;
  return { '--lb-ar': String(ar) };
});

/** 原图字节是否已经解码到可绘制。只有 true 才把覆盖层提亮，避免"节点换了但还没画出来"的一帧空白。 */
const fullLoaded = ref(false);

const closing = ref(false);
let closeTimer: number | undefined;

/**
 * 关闭灯箱：先为 .lightbox 添加 is-closing 播放 180ms 淡出动画，再卸载。
 *
 * 不使用 <Transition> 的 leave：n-modal 在 show 变为 false 的同一 tick 即卸载内容，
 * 不存在离场窗口（除非使用 display-directive="show" 常驻，而那会让遮罩继续拦截点击）。
 */
function close() {
  if (viewerIndex.value < 0 || closing.value) return;
  closing.value = true;
  closeTimer = window.setTimeout(() => {
    viewerIndex.value = -1;
    fullUrl.value = '';
    // 关掉时把缩放复位：下次打开另一张不该还带着上一次的倍数
    resetZoom();
    closing.value = false;
    closeTimer = undefined;
  }, 190);
}

function step(delta: number) {
  const next = viewerIndex.value + delta;
  if (next < 0 || next >= viewerList.value.length) return;
  viewerIndex.value = next;
}

/**
 * 下标变化时换票据获取原图，并复位缩放。
 *
 * 结果返回时需再次比较下标：连续按方向键会有多个请求在途，先发出的请求可能后返回 ——
 * 不比较会把上一张的图片覆盖到当前这张上。
 *
 * 缩放**必须一并复位**（app 侧通过 `key(page)` 重建整棵子树达到同样效果，
 * 见 `MediaImageViewerScreen.kt:37-40`）：在第 3 张放大后翻到第 4 张，
 * 保留倍数会使新图片初始即处于偏移状态。
 */
watch(viewerIndex, async (i) => {
  // 透明遮罩只在灯箱存在期间生效：用 body class 精确限定，避免影响其它 n-modal
  document.body.classList.toggle('lb-open', i >= 0);
  fullUrl.value = '';
  fullLoaded.value = false;
  resetZoom();
  const item = i >= 0 ? viewerList.value[i] : undefined;
  if (!item) return;
  // 首次打开：prepareAndOpen 已完成 fetch 与 decode，直接使用，不再发起请求。
  if (pendingUrl.value) {
    fullUrl.value = pendingUrl.value;
    pendingUrl.value = '';
    fullLoaded.value = true;
    return;
  }
  try {
    const url = await fetchStreamUrl(api, item.path);
    if (viewerIndex.value === i) fullUrl.value = url;
  } catch (e: any) {
    message.error(e?.message || '获取图片失败');
  }
});

function onFullLoad() {
  if (viewerIndex.value >= 0) fullLoaded.value = true;
}

function onFullError() {
  // 原图失败时保留缩略图/占位，不把灯箱变成一片空白；错误仍提示一次。
  fullLoaded.value = false;
  message.error('原图加载失败，已保留缩略图');
}

/** 键盘导航。看图是连续动作，用鼠标逐个点击箭头翻看数十张的操作成本过高。 */
function onKey(e: KeyboardEvent) {
  if (viewerIndex.value < 0) return;
  if (e.key === 'ArrowLeft') step(-1);
  else if (e.key === 'ArrowRight') step(1);
  // 兜底：即使 focus trap 没接管，Esc 也关闭灯箱
  else if (e.key === 'Escape') close();
}

onMounted(() => {
  reload();
  window.addEventListener('keydown', onKey);
});
onUnmounted(() => {
  window.removeEventListener('keydown', onKey);
  document.body.classList.remove('lb-open');
  if (openingIndicatorTimer !== undefined) window.clearTimeout(openingIndicatorTimer);
});
</script>

<style scoped>
.media-list-page {
  display: flex;
  flex-direction: column;
  gap: 16px;
}
/* 预载反馈：只有慢图（>150ms）才出现，避免快图时闪一下提示 */
.open-loading {
  position: fixed;
  left: 50%;
  top: calc(env(safe-area-inset-top, 0px) + 72px);
  transform: translateX(-50%);
  z-index: 3000;
  display: inline-flex;
  align-items: center;
  gap: var(--space-2);
  padding: var(--space-2) var(--space-3);
  border: 1px solid var(--border-subtle);
  border-radius: var(--radius-pill);
  background: var(--card-bg);
  color: var(--text-secondary);
  box-shadow: 0 6px 20px var(--shadow-color-soft);
  font-size: var(--font-sm);
  pointer-events: none;
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
  font-size: var(--font-xl);
  font-weight: 700;
  color: var(--text-primary);
}
.head-sub {
  font-size: var(--font-base);
  color: var(--text-muted);
}
.head-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  /* 页头包含「视图切换」与「排序」两个控件，中等宽度下该行放不下。
     允许它们在 actions **内部**换行（而不是把标题挤到下一行），
     搜索框在更窄的媒体查询中再让出宽度（见下面的 @media 1024px）。 */
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
/* 1024px 以下将搜索框收窄一档 —— 页头包含
   视图切换 + 搜索 + 排序 + 刷新 + 设置，200px 的搜索框会使其折成三行。
   此规则置于 768px 那条之前：那条的 flex:1 需要覆盖这里的固定宽度。 */
@media (max-width: 1024px) {
  .head-search {
    width: 140px;
  }
}

/* ── 时间轴（按天分段） ── */
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
  font-size: var(--font-base);
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

/* ── 灯箱 ── */
/* 灯箱的遮罩：保留变暗效果，但**不使用 250ms 淡入淡出**。
   问题不在于存在遮罩，而在于遮罩比面板晚出现且带渐变，与面板缩放叠加时产生灰色闪烁。
   当前遮罩在灯箱挂载的同一帧即为稳定深色，没有 transition 与 opacity 动画；
   面板自身执行透明度过渡。以 body class 限定，其它弹窗的遮罩不受影响。 */
:global(body.lb-open .n-modal-mask) {
  /* ui-baseline-allow: 灯箱遮罩压在图片上，两档主题都必须是不透明深底 */
  background-color: rgba(0, 0, 0, 0.72) !important;
  opacity: 1 !important;
  transition: none !important;
}

/* 外壳**常暗**：灯箱为「放映」语义 —— 照片本身明暗不一，四周与控件不能随主题翻转，
   否则亮色主题下会出现白色边缘、白字控件压在浅色照片上无法辨认。
   这是全站少数不随暗色模式变化的表面，口径与视频页缩略图上的预热角标一致。
   ui-baseline-allow: 灯箱是放映语义的固定深底，接主题令牌会在亮色主题下漏出白底、压不住照片 */
.lightbox {
  position: relative;
  display: flex;
  flex-direction: column;
  /* 定高，让 `.lb-main` 用 flex:1 把图片区撑满。
     **不得**在此写 calc(100dvh - 48px) 这类与 main.css 重复的固定数值：
     该元素是 `.modal-fit` 的 flex 子项，会被其 max-height 压回视口内
     （≤768px 为 -24px 那档）；`min-height: 0` 是能被 flex-shrink 压缩回去的前提。 */
  /* 灯箱不铺满视口，而是**贴合图片尺寸**（见 .viewer-img 的 max-*）。
     铺满时竖图两侧会留下大片纯黑；当前灯箱盒子只比图片大一圈，
     四周交给半透明遮罩（可见背后的页面），黑色区域不会超出图片边界。 */
  /* 盒子尺寸 = 宽高比 × 可用视口（--lb-ar 来自条目的 width/height）：
         width  = min(可用宽, 可用高 × ar)
         height = width / ar（aspect-ratio 推导）
     因此「占位/加载中/成图」三个阶段盒子尺寸一致，开合动画不会先小后大。 */
  width: min(calc(100vw - 32px), calc((100dvh - 48px) * var(--lb-ar, 1.5)));
  height: auto;
  min-width: min(320px, calc(100vw - 32px));
  min-height: min(240px, calc(100dvh - 48px));
  max-width: calc(100vw - 32px);
  max-height: calc(100dvh - 48px);
  overflow: hidden;
  border-radius: var(--radius-lg);
  /* 灯箱的整套场景色集中在这里：控件不跟主题翻转，但字面量只出现在这一处，
     下面所有规则都引用变量（改色只改这里，审计也不会再逐个报）。 */
  /* ui-baseline-allow: 灯箱压在图片上，两种主题下都必须是深底浅字 */
  --lb-bg: rgba(10, 10, 12, 0.96);
  --lb-ink: #f5f5f5;
  --lb-ink-solid: #fff;
  --lb-glow: rgba(255, 255, 255, 0.12);
  --lb-glow-hover: rgba(255, 255, 255, 0.22);
  --lb-glow-on: rgba(255, 255, 255, 0.28);
  --lb-scrim: rgba(0, 0, 0, 0.45);
  --lb-scrim-hover: rgba(0, 0, 0, 0.66);
  --lb-scrim-fade: rgba(0, 0, 0, 0.55);
  background: var(--lb-bg);
  color: var(--lb-ink);
  /* naive 的 .n-modal 带一圈灰色 box-shadow；灯箱的边界由半透明遮罩表达，
     这圈阴影会在开合缩放时先露出来再被面板压住，看起来就是"闪一下灰影"。
     用双类名提高特异性压过全局 .n-modal（同为一个类时胜负依赖打包顺序）。 */
  box-shadow: none;
}
.lightbox.lightbox {
  box-shadow: none;
}
/* 开合动画：渐入渐出。
   背景遮罩是同一帧出现的稳定深色（无渐变、无阴影），因此面板执行透明度过渡时
   不会出现灰色闪烁；原图也在打开前完成预载与解码，不会边淡入边换图。
   面板只改变 opacity，不缩放、不位移、不投影。 */
.lightbox {
  /* .n-modal 的全局样式带 transition: all；动画的 opacity 必须只由 keyframes 控制。 */
  transition: none;
  animation: lb-in 0.2s ease-out both;
}
/* naive 的 fade-in-scale-up 还会给面板加 opacity/transform 过渡，必须关掉；
   否则它会和上面的 keyframes 叠加，第一帧又出现透明度跳变。 */
.lightbox.fade-in-scale-up-transition-enter-active,
.lightbox.fade-in-scale-up-transition-leave-active {
  transition: none !important;
}
/* 不拦 naive 的 opacity：本版就是要渐入渐出；只关掉它的 transition，
   避免它和 keyframes 同时插值。
   但它的 enter-from 还带 scale(0.5) —— 第一帧会先缩成一半再跳回原尺寸，
   和"闪一下"是同一类问题。这里把 transform 钉死为 none，动画只保留 opacity。 */
.lightbox.fade-in-scale-up-transition-enter-from,
.lightbox.fade-in-scale-up-transition-enter-to,
.lightbox.fade-in-scale-up-transition-leave-from,
.lightbox.fade-in-scale-up-transition-leave-to {
  transform: none !important;
}
.lightbox.is-closing {
  animation: lb-out 0.18s ease-in both;
}
@keyframes lb-in {
  from {
    opacity: 0;
  }
  to {
    opacity: 1;
  }
}
@keyframes lb-out {
  from {
    opacity: 1;
  }
  to {
    opacity: 0;
  }
}

/* 顶栏（电影感样式）：压在图片上沿，渐变遮罩延伸到底部，不占用图片高度 */
.lb-top {
  position: absolute;
  top: 0;
  left: 0;
  right: 0;
  z-index: 3;
  display: flex;
  align-items: center;
  gap: var(--space-3);
  padding: var(--space-3) var(--space-4);
  min-width: 0;
  background: linear-gradient(180deg, var(--lb-scrim-fade), transparent);
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
  /* 贴合图片：不再 flex:1 抢满高度 */
  flex: 0 0 auto;
  width: 100%;
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
  background: var(--lb-glow);
  color: var(--lb-ink-solid);
  font-size: var(--font-sm);
  cursor: pointer;
  transition: background 0.15s;
}
/* ui-baseline-allow: 同上，悬停只是把同一块半透明白底加深一档 */
.lb-btn:hover {
  background: var(--lb-glow-hover);
}
/* 「详情」展开态：同一块底再亮一档，表示这条底部条现在是开着的 */
/* ui-baseline-allow: 同上，开关态靠底色的明暗区分，不引入主题色 */
.lb-btn.is-on {
  background: var(--lb-glow-on);
}
/* 关闭键做成正圆：它在顶栏最右侧，矩形按钮会显得像普通文字按钮 */
.lb-close {
  width: 32px;
  padding: 0;
  border-radius: 50%;
}
/* 底部工具条：同样压在图片下沿；保留手势说明 —— 该说明面向尚未了解手势的用户，
   借助渐变遮罩压在图片上仍可辨认。 */
.lb-bottom {
  position: absolute;
  left: 0;
  right: 0;
  bottom: 0;
  z-index: 3;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-3);
  padding: var(--space-3) var(--space-4);
  background: linear-gradient(0deg, var(--lb-scrim-fade), transparent);
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
/* 详情条：从主区移出的信息表。可收起，展开后独立滚动，
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
  /* 占位阶段就按真实宽高比占好位置：spinner/缩略图/成图三种状态同尺寸，
     开合动画不会"先小后大"（详情条展开时盒子仍有剩余空间，不会被裁掉）。 */
  aspect-ratio: var(--lb-ar, 1.5);
  width: 100%;
  max-height: calc(100dvh - 48px);
  /* 缩略图与原图叠在同一格：换图不换节点、不改布局，原图 load 后再淡入。
     用 grid 而不是两个 flex 子项，避免两张图并排把宽度撑成两倍。 */
  display: grid;
  place-items: center;
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
  /* 父级盒子已有确定尺寸（width + aspect-ratio），百分比重新有意义 */
  grid-area: 1 / 1;
  /* 缩略图与原图必须吃满同一个舞台：只给 max-* 时缩略图按 144px 原始尺寸居中，
     原图到达后再变成 360px，开灯箱就会看到"先小后大"的跳变。 */
  width: 100%;
  height: 100%;
  max-width: 100%;
  /* 全局 img 规则带了 `transition: all`，会把 rAF 逐帧写入的 transform 再插值一次：
     双击/滚轮表现为先反向抖一下再追上目标（实测 t=2ms 时 scale 0.94），
     拖拽时则永远慢半拍。缩放动画由 animateZoom 独占，这里必须关掉 CSS 过渡。 */
  transition: none;
  max-height: 100%;
  object-fit: contain;
  border-radius: var(--radius-md);
  /* 拖动时不得选中或拖出浏览器原生的图片拖拽影子 */
  user-select: none;
  -webkit-user-drag: none;
}
/* 缩略图顶位期间稍微压暗，让"这还不是原图"看得出来 */
.viewer-img.is-placeholder {
  filter: blur(1px) brightness(0.96);
}
/* 原图覆盖在缩略图上：URL 到达不等于可绘制，必须等 @load 才提亮。
   固定 opacity 过渡而不是 v-if 换节点，避免"原图到了但还没解码"的一帧空白。 */
.viewer-full {
  opacity: 0;
  transition: opacity 0.18s ease;
}
.viewer-full.is-loaded {
  opacity: 1;
}
.viewer-loading {
  display: flex;
  align-items: center;
  justify-content: center;
}
/* 翻页钮浮在图片两侧。自带深色底：它压在任意画面上，随主题变化会在浅色图片上不可见 */
/* 灯箱的左右翻页键压在图片本身而非主题背景上 ——
   底色与文字必须在**明暗两种模式下完全一致**，否则暗色下白字落在浅色照片上难以辨认。
   ui-baseline-allow: 浮在图片上的控件，必须自带深底白字，接主题令牌会在亮图上读不出来 */
/* 翻页钮 = `.lb-btn` + 本类：底色/字色/过渡/圆角都来自 .lb-btn，
   这里只补「绝对定位在图片两侧、正圆、略大一点」的差异，
   不再重复一份 height/padding/display。 */
.nav-btn {
  position: absolute;
  top: 50%;
  transform: translateY(-50%);
  width: 36px;
  height: 36px;
  padding: 0;
  border-radius: 50%;
  background: var(--lb-scrim);
  transition:
    background 0.15s,
    opacity 0.15s;
  z-index: 1;
}
.nav-btn:hover:not(:disabled) {
  /* 同 .nav-btn：悬停只是把同一块深底加深一档
     ui-baseline-allow: 浮在图片上的控件，必须自带深底白字，接主题令牌会在亮图上读不出来 */
  background: var(--lb-scrim-hover);
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

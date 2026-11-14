<!--
  歌词面板。主区右列与「沉浸播放视图」共用同一个实例形状（两处都渲染这一个组件）。

  ──── 从 AudioPlayerPanel 拆出来的原因 ────
  改成"底部播放条 + 上方两列"之后，歌词不再是播放面板的一部分：它在主区是一列，
  在沉浸视图里是右半屏。留在原组件里就得把封面/走带一起拖过去。

  ──── 三件踩过坑的实现 ────
  1. 当前行居中用 `offsetTop` 自己算 `scrollTop`，**不用 `scrollIntoView({block:'center'})`**
     —— 后者对所有祖先滚动容器生效，页面主滚动区会跟着被拉动；
  2. 首尾行靠上下**半高垫片**才能滚到面板正中（纯文本歌词没有"当前行"，不渲染垫片，
     否则只会把首行推到中间）；
  3. 只过渡 `opacity/color/font-size`，不用 `scale` —— 那会让每行都建合成层，切歌时掉帧。
-->
<template>
  <div ref="panelEl" class="lyrics-panel" :class="{ scrolling, large }" aria-live="polite" @scroll.passive="onScroll">
    <template v-if="lyricLines.length">
      <div v-if="synced" class="lyrics-pad" aria-hidden="true" />
      <div
        v-for="(line, i) in lyricLines"
        :key="i"
        :ref="(el) => setLineRef(el, i)"
        class="lyrics-line"
        :class="{ current: i === currentIndex, plain: !synced }"
        @click="synced && emit('seek', line[0] / 1000)"
      >
        {{ line[1] }}
      </div>
      <div v-if="synced" class="lyrics-pad" aria-hidden="true" />
    </template>
    <div v-else class="lyrics-empty">
      <p class="empty-title">暂无歌词</p>
      <p class="empty-hint">把同名 .lrc 放在音频旁边，或使用带内嵌歌词的文件</p>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue';
import { LYRIC_TIME_UNSYNCED } from '@/views/files/id3Lyrics';

const props = defineProps<{
  /** `parseLyrics` 的结果：[毫秒, 文本][]。无时间轴时毫秒是 LYRIC_TIME_UNSYNCED */
  lyricLines: Array<[number, string]>;
  /** 当前播放位置（秒） */
  currentSec: number;
  /** 沉浸视图里字号升一档 */
  large?: boolean;
}>();

const emit = defineEmits<{ seek: [seconds: number] }>();

/**
 * 歌词是否带时间轴。纯文本歌词不高亮、不自动滚动、不可点击跳转，
 * 只当作可手动滚动的静态文本 —— 否则会假装"当前行"骗人。与文件预览、app 端同一口径。
 */
const synced = computed(() => props.lyricLines.some((l) => l[0] !== LYRIC_TIME_UNSYNCED));

/** 当前行 = 最后一个时间戳 ≤ 当前位置的行。`parseLrc` 已按时间排序，遇到更大的就能停。 */
const currentIndex = computed(() => {
  if (!props.lyricLines.length || !synced.value) return -1;
  const t = props.currentSec * 1000;
  let idx = -1;
  for (let i = 0; i < props.lyricLines.length; i++) {
    const line = props.lyricLines[i];
    if (line && line[0] <= t) idx = i;
    else break;
  }
  return idx;
});

const panelEl = ref<HTMLElement | null>(null);
const scrolling = ref(false);
const lineEls = new Map<number, HTMLElement>();
let scrollTimer: number | null = null;
let raf = 0;

function onScroll() {
  scrolling.value = true;
  if (scrollTimer != null) window.clearTimeout(scrollTimer);
  scrollTimer = window.setTimeout(() => {
    scrolling.value = false;
  }, 600);
}

function setLineRef(el: unknown, i: number) {
  if (el instanceof HTMLElement) lineEls.set(i, el);
  else lineEls.delete(i);
}

/** 缓动滚到目标 scrollTop；连续切换时打断上一段，避免动画叠罗汉掉帧。 */
function animateScrollTo(panel: HTMLElement, to: number, duration = 420) {
  if (raf) cancelAnimationFrame(raf);
  const from = panel.scrollTop;
  const target = Math.max(0, to);
  if (Math.abs(target - from) < 1) return;
  const start = performance.now();
  const step = (now: number) => {
    const t = Math.min(1, (now - start) / duration);
    const e = 1 - Math.pow(1 - t, 3);
    panel.scrollTop = from + (target - from) * e;
    raf = t < 1 ? requestAnimationFrame(step) : 0;
  };
  raf = requestAnimationFrame(step);
}

function center(i: number) {
  const panel = panelEl.value;
  const line = lineEls.get(i);
  if (!panel || !line) return;
  animateScrollTo(panel, line.offsetTop - panel.clientHeight / 2 + line.offsetHeight / 2);
}

watch(currentIndex, (i) => {
  if (i >= 0) center(i);
});

// 换歌 / 歌词首次到达后也居中。同时清行元素表：切歌时 Vue 会复用 DOM，
// 旧索引的条目不一定被 ref 回调删掉，不清的话这个 Map 在长会话里只增不减。
watch(
  () => props.lyricLines,
  async () => {
    lineEls.clear();
    if (!props.lyricLines.length) return;
    await nextTick();
    if (currentIndex.value >= 0) center(currentIndex.value);
  }
);

// 从主区列切到沉浸视图（或反之）时组件是重新挂载的，scrollTop 归零，要补一次居中
watch(
  () => props.large,
  async () => {
    await nextTick();
    if (currentIndex.value >= 0) center(currentIndex.value);
  }
);

onBeforeUnmount(() => {
  if (raf) cancelAnimationFrame(raf);
  if (scrollTimer != null) window.clearTimeout(scrollTimer);
});
</script>

<style scoped>
.lyrics-panel {
  position: relative; /* line.offsetTop 相对面板，居中计算不用 getBoundingClientRect */
  display: flex;
  flex-direction: column;
  min-width: 0;
  height: 100%;
  min-height: 0;
  overflow-y: auto;
  overflow-x: hidden;
  padding: 0 6px;
  /* 滚到底不该把外层页面接着往下带 */
  overscroll-behavior: contain;
  /* 上下渐隐让"当前行在中间"看起来是自然的；滚动时改成不遮，减少每帧重绘 */
  mask-image: linear-gradient(180deg, transparent, #000 12%, #000 88%, transparent);
  -webkit-mask-image: linear-gradient(180deg, transparent, #000 12%, #000 88%, transparent);
  scrollbar-width: none;
  -ms-overflow-style: none;
}
.lyrics-panel.scrolling {
  mask-image: none;
  -webkit-mask-image: none;
}
.lyrics-panel::-webkit-scrollbar {
  width: 6px;
}
.lyrics-panel::-webkit-scrollbar-track {
  background: transparent;
}
.lyrics-panel::-webkit-scrollbar-thumb {
  background: var(--scrollbar-thumb);
  border-radius: 999px;
  border: 1px solid transparent;
  background-clip: content-box;
}
.lyrics-panel:not(:hover):not(.scrolling)::-webkit-scrollbar {
  width: 0;
  display: none;
}
.lyrics-pad {
  flex: 0 0 auto;
  height: calc(50% - 1.6em);
  min-height: 80px;
  pointer-events: none;
}
.lyrics-empty {
  height: 100%;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 6px;
  text-align: center;
}
.empty-title {
  margin: 0;
  font-size: var(--font-md);
  color: var(--text-secondary);
}
.empty-hint {
  margin: 0;
  max-width: 260px;
  font-size: var(--font-xs);
  line-height: 1.6;
  color: var(--text-muted);
}
.lyrics-line {
  flex-shrink: 0;
  padding: 8px 12px;
  font-size: var(--font-lg);
  color: var(--text-muted);
  border-radius: var(--radius-sm);
  cursor: pointer;
  line-height: 1.5;
  text-align: center;
  opacity: 0.45;
  transition:
    opacity 0.22s ease,
    color 0.22s ease,
    font-size 0.22s ease;
  contain: layout style;
  will-change: opacity;
}
.lyrics-line:hover {
  color: var(--text-secondary);
  opacity: 0.8;
}
/* 当前行走 --text-primary 而不是主色：这一列里"当前"已经靠字号+字重+不透明度
   区分得很清楚了，主色留给播放条上的走带键，免得同一屏出现三处主色抢注意力。 */
.lyrics-line.current {
  color: var(--text-primary);
  font-size: 21px;
  font-weight: 700;
  opacity: 1;
  will-change: auto;
}
/* 无时间轴的纯文本歌词：没有"当前行"概念，所有行等亮度常显、不给点击手型（点了也无处可跳）。
   字号沿用未激活行，避免整段都是大号字撑爆面板。 */
.lyrics-line.plain {
  cursor: default;
  opacity: 0.85;
  color: var(--text-secondary);
}
.lyrics-line.plain:hover {
  opacity: 0.85;
  color: var(--text-secondary);
}

/* 沉浸视图：整体升一档，当前行明显更大 —— 那一屏只有封面和歌词两件事 */
.lyrics-panel.large .lyrics-line {
  font-size: 19px;
  padding: 10px 16px;
}
.lyrics-panel.large .lyrics-line.current {
  font-size: 26px;
}

@media (max-width: 720px) {
  .lyrics-line {
    font-size: var(--font-md);
    padding: 6px 10px;
  }
  .lyrics-line.current {
    font-size: var(--font-xl);
  }
  .lyrics-panel.large .lyrics-line {
    font-size: var(--font-lg);
  }
  .lyrics-panel.large .lyrics-line.current {
    font-size: 22px;
  }
}
</style>

<!--
  底部播放条。Spotify / Apple Music 的通用版式：**走带不占列**，单独横在主区下方。

  ──── 为什么改成这个形状 ────
  旧版是"播放面板（封面列 + 歌词列）"与"队列列"并排 ⇒ 一屏三列。1440px 视口下每列
  只剩三四百像素：封面不敢放大、歌词一行断两截、队列窗口只能显示十来行。
  把走带抽成一条横排之后，上方就只剩两列（队列 | 歌词），两者都能吃满高度。

  ──── 三段式 grid ────
    [封面 + 曲名/艺人]   [走带 / 进度（两行居中）]   [音量 + 歌词 + 沉浸]
  中段用 `2fr` 吃掉多余宽度：进度条是这条里唯一"越长越好用"的元素。
  左右两段给 `minmax(0, 1fr)` 而不是固定宽，才能保证进度条真正居中
  （固定宽时左右文字长度不同，中段会偏）。

  ──── 移动端 ────
  两行：上行 `封面 + 信息 + 播放键 + 歌词/沉浸钮`，下行整宽进度条。
  音量整段隐藏 —— 手机上调音量用物理键，而滑条会把上行挤到放不下。
-->
<template>
  <div class="player-bar">
    <!-- 左：点封面或文字进/出沉浸视图（和 Spotify 点封面展开是同一个手势） -->
    <button
      type="button"
      class="pb-now"
      :title="stageOpen ? '收起大封面' : '展开大封面'"
      :aria-pressed="stageOpen"
      @click="emit('update:stageOpen', !stageOpen)"
    >
      <span class="pb-cover" :class="{ 'is-empty': !coverUrl }">
        <img v-if="coverUrl" :src="coverUrl" :alt="title" />
        <svg v-else viewBox="0 0 24 24" aria-hidden="true">
          <path fill="currentColor" d="M12 3v10.55A4 4 0 1 0 14 17V7h4V3h-6z" />
        </svg>
      </span>
      <span class="pb-text">
        <span class="pb-title" :title="title">{{ title }}</span>
        <span class="pb-sub" :title="subtitle">{{ subtitle }}</span>
      </span>
    </button>

    <!-- 中：走带 + 进度 -->
    <div class="pb-center">
      <div class="ctrl-row">
        <button type="button" class="ctrl-btn side" :disabled="!hasPrev" title="上一首" @click="emit('prev')">
          <svg viewBox="0 0 24 24" class="ctrl-icon small" aria-hidden="true">
            <path fill="currentColor" d="M6 6h2v12H6V6zm3.5 6L18 6v12l-8.5-6z" />
          </svg>
        </button>
        <button type="button" class="ctrl-btn skip" title="后退 10 秒" aria-label="后退 10 秒" @click="skip(-10)">
          <svg viewBox="0 0 24 24" class="ctrl-icon" aria-hidden="true">
            <path fill="currentColor" d="M12 5V2L7 6.5 12 11V8a5 5 0 1 1-5 5H5a7 7 0 1 0 7-8z" />
            <!-- 环形箭头的内孔是以 (12,13) 为心、半径 5 的圆：字号必须让 "10" 的外接框
                 落在这个圆内（7.2px 粗体两位数约 8px 宽 / 5px 高，四角距圆心 ≈4.7 < 5） -->
            <text x="12" y="15.6" text-anchor="middle" font-size="7.2" font-weight="700" fill="currentColor">10</text>
          </svg>
        </button>
        <button type="button" class="ctrl-btn primary" :title="playing ? '暂停' : '播放'" @click="emit('toggle')">
          <svg v-if="!playing" viewBox="0 0 24 24" class="ctrl-icon play" aria-hidden="true">
            <path fill="currentColor" d="M8 5v14l11-7L8 5z" />
          </svg>
          <svg v-else viewBox="0 0 24 24" class="ctrl-icon play" aria-hidden="true">
            <path fill="currentColor" d="M6 19h4V5H6v14zm8-14v14h4V5h-4z" />
          </svg>
        </button>
        <button type="button" class="ctrl-btn skip" title="前进 10 秒" aria-label="前进 10 秒" @click="skip(10)">
          <!-- 同一条弧线镜像即可，避免再维护一份镜像路径；数字不能跟着翻，所以放在组外 -->
          <svg viewBox="0 0 24 24" class="ctrl-icon" aria-hidden="true">
            <g transform="translate(24 0) scale(-1 1)">
              <path fill="currentColor" d="M12 5V2L7 6.5 12 11V8a5 5 0 1 1-5 5H5a7 7 0 1 0 7-8z" />
            </g>
            <text x="12" y="15.6" text-anchor="middle" font-size="7.2" font-weight="700" fill="currentColor">10</text>
          </svg>
        </button>
        <button type="button" class="ctrl-btn side" :disabled="!hasNext" title="下一首" @click="emit('next')">
          <svg viewBox="0 0 24 24" class="ctrl-icon small" aria-hidden="true">
            <path fill="currentColor" d="M16 6h2v12h-2V6zM6 6l8.5 6L6 18V6z" />
          </svg>
        </button>
      </div>
      <!-- 已播 / 剩余分列两端：剩余带负号，和 Apple Music 一致，读数比总时长更有用 -->
      <div class="seek-row">
        <span class="seek-time">{{ formatTime(displaySec) }}</span>
        <input
          class="seek"
          :class="{ dragging: scrubbing }"
          :style="{ '--pct': seekPercent + '%' }"
          type="range"
          min="0"
          :max="durationSec || 0"
          step="0.01"
          :value="displaySec"
          :disabled="!durationSec"
          aria-label="播放进度"
          @input="onSeekInput"
          @change="commitSeek"
        />
        <span class="seek-time right">-{{ formatTime(remainSec) }}</span>
      </div>
    </div>

    <!-- 右：次要设置 -->
    <div class="pb-right">
      <!-- 音量：常态只是一个按钮，悬浮（或触屏常驻）才展开滑条。
           收起靠 volumeActive = hover | 拖动 | 键盘聚焦，拖动中一律不收，
           否则鼠标拖到容器外滑条会中途消失、音量停在半路。 -->
      <div
        class="volume-wrap"
        :class="{ open: volumeActive }"
        @mouseenter="volumeHover = true"
        @mouseleave="volumeHover = false"
        @focusin="onVolumeFocusIn"
        @focusout="volumeFocused = false"
      >
        <div class="volume-slot">
          <input
            class="volume"
            :style="{ '--pct': volumePercent + '%' }"
            type="range"
            min="0"
            max="1"
            step="0.01"
            :value="volume"
            title="音量"
            aria-label="音量"
            @input="onVolume"
            @pointerdown="onVolumeDragStart"
          />
        </div>
        <button
          type="button"
          class="ctrl-btn vol-btn"
          :title="`音量 ${volumePercent}%`"
          aria-label="音量"
          :aria-expanded="volumeActive"
          @click="volumeHover = true"
        >
          <svg class="vol-icon" viewBox="0 0 24 24" aria-hidden="true">
            <!-- 图标随档位换：静音 / 小 / 大。三条 d 是同一只喇叭加不同数量的声波弧 -->
            <path v-if="volume <= 0" fill="currentColor" :d="VOL_ICON_MUTE" />
            <path v-else-if="volume < 0.5" fill="currentColor" :d="VOL_ICON_LOW" />
            <path v-else fill="currentColor" :d="VOL_ICON_HIGH" />
          </svg>
        </button>
      </div>

      <button
        type="button"
        class="pill"
        :class="{ on: lyricsOpen }"
        :aria-pressed="lyricsOpen"
        :title="lyricsOpen ? '收起歌词' : '展开歌词'"
        @click="emit('update:lyricsOpen', !lyricsOpen)"
      >
        <svg viewBox="0 0 24 24" aria-hidden="true">
          <path fill="currentColor" d="M4 5h16v2H4V5zm0 4h10v2H4V9zm0 4h16v2H4v-2zm0 4h10v2H4v-2z" />
        </svg>
        <span class="pill-text">歌词</span>
      </button>

      <button
        type="button"
        class="pill icon-only"
        :class="{ on: stageOpen }"
        :aria-pressed="stageOpen"
        :title="stageOpen ? '回到列表' : '大封面视图'"
        @click="emit('update:stageOpen', !stageOpen)"
      >
        <svg v-if="!stageOpen" viewBox="0 0 24 24" aria-hidden="true">
          <path fill="currentColor" d="M4 14h2v4h4v2H4v-6zm10-10h6v6h-2V6h-4V4z" />
        </svg>
        <svg v-else viewBox="0 0 24 24" aria-hidden="true">
          <path fill="currentColor" d="M10 4h2v6H6V8h4V4zm2 10h6v2h-4v4h-2v-6z" />
        </svg>
      </button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, ref } from 'vue';

const props = defineProps<{
  title: string;
  artist: string;
  album?: string;
  coverUrl: string;
  playing: boolean;
  /** 当前播放位置（秒），由 <audio> 的 timeupdate 推上来 */
  currentSec: number;
  durationSec: number;
  volume: number;
  hasPrev: boolean;
  hasNext: boolean;
  lyricsOpen: boolean;
  stageOpen: boolean;
}>();

const emit = defineEmits<{
  toggle: [];
  prev: [];
  next: [];
  /** 拖动结束后才发：跟着 input 发会让 <audio> 在拖动过程中反复 seek、卡顿 */
  seek: [seconds: number];
  volume: [value: number];
  'update:lyricsOpen': [value: boolean];
  'update:stageOpen': [value: boolean];
}>();

/** 播放条的第二行文字：艺人 —— 专辑。条高有限，两者挤一行比各占一行更稳。 */
const subtitle = computed(() => (props.album ? `${props.artist} — ${props.album}` : props.artist));

// ── 进度 ──

/**
 * 拖动中的本地值。
 *
 * 拖动时不能直接显示 `props.currentSec`：那个值仍由播放进度驱动，会把滑块拽回去。
 * 所以按住期间用本地值接管显示，松手（change）时才把目标位置交出去。
 */
const scrubbing = ref(false);
const scrubPos = ref(0);
const displaySec = computed(() => (scrubbing.value ? scrubPos.value : props.currentSec));

const remainSec = computed(() => {
  const d = props.durationSec;
  if (!Number.isFinite(d) || d <= 0) return 0;
  return Math.max(0, d - displaySec.value);
});

/**
 * 已播比例（0–100）。
 *
 * 进度/音量条都是 `appearance: none` 自绘轨道（见 style 里的说明），
 * 原生 `accent-color` 填充随之失效，已播那一段必须由 CSS 自己用渐变画 ——
 * 这两个值就是喂给 `--pct` 的唯一数据源。
 */
const seekPercent = computed(() => {
  const d = props.durationSec;
  if (!Number.isFinite(d) || d <= 0) return 0;
  return Math.min(100, Math.max(0, (displaySec.value / d) * 100));
});

function onSeekInput(e: Event) {
  scrubbing.value = true;
  scrubPos.value = Number((e.target as HTMLInputElement).value);
}

function commitSeek(e: Event) {
  const v = Number((e.target as HTMLInputElement).value);
  scrubbing.value = false;
  emit('seek', v);
}

/** ±10 秒。在这里算好目标位置再发，父组件只管 seek 到哪。 */
function skip(delta: number) {
  const d = props.durationSec;
  if (!Number.isFinite(d) || d <= 0) return;
  emit('seek', Math.min(d, Math.max(0, props.currentSec + delta)));
}

function formatTime(sec: number): string {
  if (!Number.isFinite(sec) || sec < 0) return '0:00';
  const s = Math.floor(sec % 60);
  const m = Math.floor((sec / 60) % 60);
  const h = Math.floor(sec / 3600);
  const mm = h > 0 ? String(m).padStart(2, '0') : String(m);
  return h > 0 ? `${h}:${mm}:${String(s).padStart(2, '0')}` : `${mm}:${String(s).padStart(2, '0')}`;
}

// ── 音量 ──

const volumePercent = computed(() => Math.round(props.volume * 100));

/**
 * 音量条的展开态。
 *
 * hover / 键盘聚焦 / **正在拖动** 三者任一为真才展开。拖动必须单独算一条：
 * 原生 range 没有 dragstart，鼠标按住后拖出容器会触发 mouseleave，
 * 若只看 hover，滑条会在手指还按着的时候收起来。
 */
const volumeHover = ref(false);
const volumeFocused = ref(false);
const volumeDragging = ref(false);
const volumeActive = computed(() => volumeHover.value || volumeFocused.value || volumeDragging.value);

/**
 * 音量图标的三档 path（静音 / 小声 / 大声）。
 *
 * 提到 script 里而不是写在 template 上：这三条 `d` 单行都超 120 字符，会卡
 * prettier 的 printWidth；而且喇叭主体那段路径三档完全相同，写在 template 里是三份拷贝。
 */
const VOL_ICON_MUTE =
  'M3 9v6h4l5 5V4L7 9H3zM21.8 9.6 21 8.8 17.6 12.2 14.3 8.8 13.4 9.6 16.8 13 13.4 16.4 14.3 17.2 17.6 13.8 21 17.2 21.8 16.4 18.4 13z';
const VOL_ICON_LOW = 'M3 9v6h4l5 5V4L7 9H3zm13.5 3A4.5 4.5 0 0 0 14 8.1v7.8a4.48 4.48 0 0 0 2.5-2.9z';
const VOL_ICON_HIGH =
  'M3 9v6h4l5 5V4L7 9H3zm13.5 3A4.5 4.5 0 0 0 14 8.1v7.8a4.48 4.48 0 0 0 2.5-2.9zM14 3.2v2.1a7 7 0 0 1 0 13.4v2.1a9 9 0 0 0 0-17.6z';

/**
 * 只认**键盘**带来的聚焦。
 *
 * `:focus-visible` 正是"这次聚焦该不该给视觉反馈"的浏览器判据：键盘 Tab 命中，
 * 程序化聚焦与鼠标点击不命中。鼠标场景由 hover 负责，不需要 focus 再兜一次。
 */
function onVolumeFocusIn(e: FocusEvent) {
  const el = e.target as HTMLElement | null;
  volumeFocused.value = el?.matches?.(':focus-visible') ?? false;
}

function endVolumeDrag() {
  volumeDragging.value = false;
  window.removeEventListener('pointerup', endVolumeDrag);
  window.removeEventListener('pointercancel', endVolumeDrag);
}

function onVolumeDragStart() {
  volumeDragging.value = true;
  // 挂到 window：松手可能发生在容器外，元素上的 pointerup 收不到
  window.addEventListener('pointerup', endVolumeDrag);
  window.addEventListener('pointercancel', endVolumeDrag);
}

function onVolume(e: Event) {
  emit('volume', Number((e.target as HTMLInputElement).value));
}

onBeforeUnmount(endVolumeDrag);
</script>

<style scoped>
.player-bar {
  display: grid;
  /* 左右等宽（1fr）+ 中段吃剩余（2fr）：只有左右同宽，中段的进度条才真正居中 */
  grid-template-columns: minmax(0, 1fr) minmax(320px, 2fr) minmax(0, 1fr);
  align-items: center;
  gap: var(--space-4);
  padding: 10px 16px;
  border: 1px solid var(--border-subtle);
  border-radius: var(--radius-lg);
  background: var(--surface-card);
}

/* ── 左：当前曲目 ── */
.pb-now {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
  padding: 4px;
  margin: -4px;
  border: none;
  border-radius: var(--radius-md);
  background: transparent;
  cursor: pointer;
  text-align: left;
  font: inherit;
  color: inherit;
  transition: background 0.15s ease;
}
.pb-now:hover {
  background: var(--surface-hover);
}
.pb-cover {
  display: flex;
  align-items: center;
  justify-content: center;
  flex: 0 0 auto;
  width: 48px;
  height: 48px;
  overflow: hidden;
  border-radius: var(--radius-md);
}
/* 只有"没封面"才画底板：有图时底色会从 cover 的缝隙里透出来，看着像图被遮了一层 */
.pb-cover.is-empty {
  background: var(--surface-hover);
  color: var(--accent-color);
}
.pb-cover img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}
.pb-cover svg {
  width: 40%;
  height: 40%;
}
.pb-text {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}
.pb-title {
  font-size: var(--font-md);
  font-weight: 600;
  color: var(--text-primary);
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}
.pb-sub {
  font-size: var(--font-xs);
  color: var(--text-muted);
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}

/* ── 中：走带 + 进度 ── */
.pb-center {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 4px;
  min-width: 0;
}
/* 尺寸递增 上下曲 < ±10s < 播放，尺寸差是唯一的层次手段（都不带边框） */
.ctrl-row {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: var(--space-2);
}
.ctrl-btn {
  appearance: none;
  border: none;
  background: transparent;
  color: var(--text-secondary);
  display: inline-flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  width: 34px;
  height: 34px;
  padding: 0;
  border-radius: 50%;
  transition:
    background 0.15s ease,
    color 0.15s ease;
}
.ctrl-btn:hover:not(:disabled) {
  background: var(--surface-hover);
  color: var(--text-primary);
}
.ctrl-btn:disabled {
  color: var(--text-muted);
  cursor: not-allowed;
  opacity: 0.5;
}
.ctrl-btn.side {
  width: 30px;
  height: 30px;
  color: var(--text-muted);
}
/* 主键用「主色淡底 + 主色图标」而不是「主色实底 + 白字」：淡底在明暗两态下都不抢戏 */
.ctrl-btn.primary {
  width: 42px;
  height: 42px;
  color: var(--accent-color);
  background: var(--accent-color-light);
}
.ctrl-btn.primary:hover {
  background: var(--accent-color-light);
  box-shadow: 0 0 0 3px var(--accent-color-light);
}
.ctrl-icon {
  width: 22px;
  height: 22px;
  color: currentColor;
}
.ctrl-icon.play {
  width: 24px;
  height: 24px;
}
.ctrl-icon.small {
  width: 16px;
  height: 16px;
}

.seek-row {
  display: grid;
  grid-template-columns: 38px minmax(0, 1fr) 38px;
  align-items: center;
  gap: var(--space-2);
  width: 100%;
}
.seek-time {
  font-size: var(--font-xs);
  color: var(--text-muted);
  font-variant-numeric: tabular-nums;
}
.seek-time.right {
  text-align: right;
}
/**
 * 进度 / 音量滑条：`appearance: none` 之后 track 与 thumb 全部自绘。
 *
 * 注意：不要改成「transform: scaleY() 把轨道撑粗」。
 * 在 WebKit/Blink 里 thumb（`::-webkit-slider-thumb`）是 `::-webkit-slider-runnable-track`
 * 的**子节点**，父级的 transform 会连带作用在它身上：轨道竖向放大，圆点就被同比拉成竖椭圆；
 * Firefox 的 `::-moz-range-track` / `::-moz-range-thumb` 同理。所以定死三条：
 *   1. 轨道加粗只允许改 track 自己的 height；
 *   2. thumb 变大只允许改 thumb 自己的 width/height；
 *   3. 这两个伪元素里都不许出现 transform。
 *
 * thumb **不用 opacity:0 藏起来只在 hover 显形** —— 触屏没有 hover，
 * 手指拖进度时完全看不到滑块位置。改成常显、hover/拖动时变大一档。
 */
.seek,
.volume {
  --track-h: 4px;
  --thumb-size: 10px;
  /* 轨道底色走令牌：--border-subtle 是浅/深两态都看得清的中性线色 */
  --track-bg: var(--border-subtle);
  appearance: none;
  -webkit-appearance: none;
  width: 100%;
  min-width: 0;
  /* 元素高度只决定命中区（细轨很难点中），轨道视觉厚度由 --track-h 给 */
  height: 16px;
  margin: 0;
  padding: 0;
  background: transparent;
  cursor: pointer;
}
.seek:hover:not(:disabled),
.seek.dragging,
.volume:hover {
  --track-h: 6px;
  --thumb-size: 12px;
}
.seek::-webkit-slider-runnable-track,
.volume::-webkit-slider-runnable-track {
  height: var(--track-h);
  border-radius: var(--radius-pill);
  background: linear-gradient(to right, var(--accent-color) 0 var(--pct, 0%), var(--track-bg) var(--pct, 0%) 100%);
  transition: height 0.15s ease;
}
.seek::-webkit-slider-thumb,
.volume::-webkit-slider-thumb {
  appearance: none;
  -webkit-appearance: none;
  width: var(--thumb-size);
  height: var(--thumb-size);
  border: none;
  border-radius: 50%;
  background: var(--accent-color);
  /* WebKit 不会替你居中 thumb：靠负 margin 把它压回轨道中线 */
  margin-top: calc((var(--track-h) - var(--thumb-size)) / 2);
  transition:
    width 0.15s ease,
    height 0.15s ease,
    margin-top 0.15s ease;
}
.seek::-moz-range-track,
.volume::-moz-range-track {
  height: var(--track-h);
  border-radius: var(--radius-pill);
  background: linear-gradient(to right, var(--accent-color) 0 var(--pct, 0%), var(--track-bg) var(--pct, 0%) 100%);
  transition: height 0.15s ease;
}
/* Firefox 自己会把 thumb 对齐轨道中线，不需要 margin 修正 */
.seek::-moz-range-thumb,
.volume::-moz-range-thumb {
  width: var(--thumb-size);
  height: var(--thumb-size);
  border: none;
  border-radius: 50%;
  background: var(--accent-color);
  transition:
    width 0.15s ease,
    height 0.15s ease;
}
.seek:disabled {
  cursor: default;
  opacity: 0.5;
}

/* ── 右：次要设置，靠右对齐 ── */
.pb-right {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: var(--space-2);
  min-width: 0;
}
/**
 * 音量：常态只露一个按钮，滑条悬浮才展开。
 *
 * 展开用 width + opacity 过渡，而不是 display:none —— 后者根本没有过渡这回事。
 * 收起态再补一条 pointer-events:none：0 宽但仍在文档里的滑条不该抢指针。
 * 滑条在按钮**左侧**：这一段整体靠右，朝右展开会顶到容器边。
 */
.volume-wrap {
  display: flex;
  align-items: center;
  gap: var(--space-1);
  flex: 0 1 auto;
  min-width: 0;
}
.volume-slot {
  display: flex;
  align-items: center;
  width: 0;
  opacity: 0;
  overflow: hidden;
  pointer-events: none;
  transition:
    width 0.18s ease,
    opacity 0.18s ease;
}
.volume-wrap.open .volume-slot {
  width: 90px;
  opacity: 1;
  pointer-events: auto;
}
/* 触屏没有 hover，展开态永远不会到来 ⇒ 滑条常驻，否则音量彻底调不了 */
@media (hover: none) {
  .volume-slot {
    width: 90px;
    opacity: 1;
    pointer-events: auto;
  }
}
.ctrl-btn.vol-btn {
  width: 30px;
  height: 30px;
  color: var(--text-muted);
}
.vol-icon {
  width: 17px;
  height: 17px;
  flex-shrink: 0;
}

.pill {
  display: inline-flex;
  align-items: center;
  gap: var(--space-1);
  flex: 0 0 auto;
  padding: 4px 10px;
  font-size: var(--font-xs);
  color: var(--text-muted);
  background: transparent;
  border: 1px solid var(--border-subtle);
  border-radius: var(--radius-pill);
  cursor: pointer;
  transition:
    color 0.15s ease,
    background 0.15s ease,
    border-color 0.15s ease;
}
.pill:hover {
  color: var(--text-secondary);
  background: var(--surface-hover);
}
.pill.on {
  color: var(--accent-color);
  border-color: var(--accent-color);
  background: var(--accent-color-light);
}
.pill svg {
  width: 13px;
  height: 13px;
}
.pill.icon-only {
  padding: 4px 7px;
}

/* 窄屏：两行。上行「封面+信息 / 播放键 / 开关」，下行整宽进度条。
   音量整段隐藏 —— 手机调音量用物理键，滑条会把上行挤到放不下。 */
@media (max-width: 720px) {
  .player-bar {
    grid-template-columns: minmax(0, 1fr) auto;
    grid-template-areas:
      'now right'
      'center center';
    gap: var(--space-2) var(--space-3);
    padding: 10px 12px;
  }
  .pb-now {
    grid-area: now;
  }
  .pb-right {
    grid-area: right;
  }
  .pb-center {
    grid-area: center;
  }
  .volume-wrap {
    display: none;
  }
  /* 走带与进度在同一格里上下排，走带的 ±10s 保留（手机上比拖进度好按） */
  .ctrl-row {
    gap: var(--space-3);
  }
  .ctrl-btn {
    width: 40px;
    height: 40px;
  }
  .ctrl-btn.side {
    width: 36px;
    height: 36px;
  }
  .ctrl-btn.primary {
    width: 46px;
    height: 46px;
  }
  .pill-text {
    display: none;
  }
  .pill {
    padding: 4px 7px;
  }
}
</style>

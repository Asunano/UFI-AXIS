<!--
  音乐播放面板 —— Apple Music 风格。与 app 的 `MediaAudioPlayerScreen` 是同一个界面的两端镜像。

  ──── 为什么是这个版式 ────
  Apple Music 的播放页只有三件事，按视觉重量从大到小：**封面 → 歌名/艺人 → 进度与走带**。
  歌词是可切换的右侧栏（桌面）/ 下方区（窄屏），不抢封面的位置。
  这里照这个层级排，而不是把封面缩成缩略图再塞满按钮：

    · 封面：大尺寸、22px 圆角、柔和投影，播放时略微放大（`.is-playing`）——
      「在放」这件事由封面自己表达，不需要额外的动图；
    · 进度条：细轨道 + 悬停才出现的滑块，两端是已播/剩余（**剩余带负号**，同 Apple Music）；
    · 走带：上一首 / 大号播放圆钮 / 下一首，播放钮直径明显大于两侧 —— 那是唯一的主操作。

  ──── 歌词 ────
  取数在 core（`/api/media/lyrics` 读旁挂 .lrc/.txt），**时间轴解析复用文件页那份**
  （`views/files/id3Lyrics.ts` 的 `parseLyrics`）：那里已经处理了 `[offset:]`、逐行 BOM、
  以及"纯文本歌词降级为不滚动静态文本"。再写一份必然与文件预览、与 app 端解出不同的时间轴。

  当前行居中靠 `scrollIntoView({ block: 'center' })`，只在**行号变化**时滚一次 ——
  跟着 timeupdate（约 4 次/秒）滚会让整栏一直在抖。
-->
<template>
  <div class="player">
    <!-- ── 左：封面 + 曲目信息 + 进度 + 走带 ── -->
    <div class="player-main">
      <div class="cover-box" :class="{ 'is-playing': playing }">
        <img v-if="coverUrl" :src="coverUrl" :alt="title" class="cover-img" />
        <div v-else class="cover-fallback">
          <n-icon :size="72"><MusicalNotesOutline /></n-icon>
        </div>
      </div>

      <div class="track-meta">
        <div class="track-title" :title="title">{{ title }}</div>
        <div class="track-artist">
          {{ artist }}<span v-if="album"> — {{ album }}</span>
        </div>
      </div>

      <!--
        进度条用原生 range：拖动、键盘方向键、触屏都免费得到，且不依赖任何组件库的
        内部结构。外观全部由下面的 CSS 接管（轨道/滑块都自绘），所以跨浏览器一致。
      -->
      <div class="progress-row">
        <input
          class="progress"
          type="range"
          min="0"
          :max="durationSec || 0"
          step="0.1"
          :value="displaySec"
          :disabled="!durationSec"
          :style="{ '--played': `${playedPercent}%` }"
          aria-label="播放进度"
          @input="onSeekInput"
          @change="onSeekCommit"
        />
        <div class="time-row">
          <span class="time">{{ formatDuration(displaySec * 1000) }}</span>
          <!-- 剩余时间带负号，与 Apple Music 一致 -->
          <span class="time">-{{ formatDuration(Math.max(0, (durationSec - displaySec) * 1000)) }}</span>
        </div>
      </div>

      <div class="transport">
        <button class="tp-btn" :disabled="!hasPrev" title="上一首" @click="emit('prev')">
          <n-icon :size="22"><PlaySkipBackOutline /></n-icon>
        </button>
        <button class="tp-btn tp-play" :title="playing ? '暂停' : '播放'" @click="emit('toggle')">
          <n-icon :size="26">
            <PauseOutline v-if="playing" />
            <PlayOutline v-else />
          </n-icon>
        </button>
        <button class="tp-btn" :disabled="!hasNext" title="下一首" @click="emit('next')">
          <n-icon :size="22"><PlaySkipForwardOutline /></n-icon>
        </button>
      </div>

      <div class="volume-row">
        <n-icon :size="16" class="vol-icon"><VolumeLowOutline /></n-icon>
        <input
          class="volume"
          type="range"
          min="0"
          max="1"
          step="0.01"
          :value="volume"
          :style="{ '--played': `${volume * 100}%` }"
          aria-label="音量"
          @input="onVolumeInput"
        />
        <n-icon :size="16" class="vol-icon"><VolumeHighOutline /></n-icon>
      </div>
    </div>

    <!-- ── 右：歌词 ── -->
    <div class="player-lyrics">
      <div class="lyrics-head">
        <span class="lyrics-title">歌词</span>
        <span v-if="!lyricLines.length" class="lyrics-note">未找到歌词文件</span>
        <span v-else-if="!synced" class="lyrics-note">无时间轴，静态显示</span>
      </div>
      <div ref="lyricsBox" class="lyrics-box">
        <p
          v-for="(line, i) in lyricLines"
          :key="i"
          :ref="(el) => setLineRef(el, i)"
          class="lyric-line"
          :class="{ 'is-active': synced && i === activeLine }"
        >
          {{ line[1] }}
        </p>
        <div v-if="!lyricLines.length" class="lyrics-placeholder">把同名的 .lrc 或 .txt 放在音频旁边即可显示歌词。</div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import {
  MusicalNotesOutline,
  PlayOutline,
  PauseOutline,
  PlaySkipBackOutline,
  PlaySkipForwardOutline,
  VolumeLowOutline,
  VolumeHighOutline,
} from '@vicons/ionicons5';
import { formatDuration } from '../mediaShared';
import { LYRIC_TIME_UNSYNCED } from '@/views/files/id3Lyrics';

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
  /** `parseLyrics` 的结果：[毫秒, 文本][]。无时间轴时毫秒是 LYRIC_TIME_UNSYNCED */
  lyricLines: Array<[number, string]>;
}>();

const emit = defineEmits<{
  toggle: [];
  prev: [];
  next: [];
  /** 拖动结束后才发：跟着 input 发会让 <audio> 在拖动过程中反复 seek、卡顿 */
  seek: [seconds: number];
  volume: [value: number];
}>();

/**
 * 拖动中的本地值。
 *
 * 拖动时不能直接显示 `props.currentSec`：那个值仍由播放进度驱动，会把滑块拽回去。
 * 所以按住期间用本地值接管显示，松手（change）时才把目标位置交出去。
 */
const dragSec = ref<number | null>(null);
const displaySec = computed(() => dragSec.value ?? props.currentSec);

const playedPercent = computed(() => {
  if (!props.durationSec) return 0;
  return Math.min(100, Math.max(0, (displaySec.value / props.durationSec) * 100));
});

function onSeekInput(e: Event) {
  dragSec.value = Number((e.target as HTMLInputElement).value);
}

function onSeekCommit(e: Event) {
  const v = Number((e.target as HTMLInputElement).value);
  dragSec.value = null;
  emit('seek', v);
}

function onVolumeInput(e: Event) {
  emit('volume', Number((e.target as HTMLInputElement).value));
}

/** 有任意一行带真实时间戳就算「有时间轴」，此时才滚动与高亮。 */
const synced = computed(() => props.lyricLines.some(([t]) => t !== LYRIC_TIME_UNSYNCED));

/**
 * 当前行 = 最后一个时间戳 ≤ 当前播放位置的行。
 *
 * 用「从后往前找第一个满足的」而不是二分：歌词行数量级在几十到几百，
 * 线性扫的开销远小于维护一个随 seek 失效的游标。
 */
const activeLine = computed(() => {
  if (!synced.value) return -1;
  const ms = props.currentSec * 1000;
  for (let i = props.lyricLines.length - 1; i >= 0; i--) {
    const t = props.lyricLines[i]![0];
    if (t !== LYRIC_TIME_UNSYNCED && t <= ms) return i;
  }
  return -1;
});

const lyricsBox = ref<HTMLElement | null>(null);
const lineEls = new Map<number, HTMLElement>();

function setLineRef(el: unknown, i: number) {
  if (el instanceof HTMLElement) lineEls.set(i, el);
  else lineEls.delete(i);
}

/**
 * 只在**行号变化**时滚一次。
 *
 * 跟着 `currentSec` 滚等于每秒滚 4 次，整栏一直在抖；而行与行之间往往有几秒间隔，
 * 按行号滚才是"歌词跟着唱到哪一句走"。
 */
watch(activeLine, (i) => {
  if (i < 0) return;
  lineEls.get(i)?.scrollIntoView({ block: 'center', behavior: 'smooth' });
});
</script>

<style scoped>
.player {
  display: grid;
  grid-template-columns: minmax(0, 320px) minmax(0, 1fr);
  gap: 28px;
  align-items: start;
}

/* ── 左栏 ── */
.player-main {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 16px;
  min-width: 0;
}

/* 封面：Apple Music 的大圆角 + 柔和投影；播放时略放大，"在放"由它自己表达 */
.cover-box {
  width: 100%;
  aspect-ratio: 1;
  border-radius: 22px;
  overflow: hidden;
  background: var(--surface-elevated);
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--text-muted);
  box-shadow: 0 10px 30px rgba(0, 0, 0, 0.18);
  transform: scale(0.94);
  transition:
    transform 0.35s cubic-bezier(0.22, 0.61, 0.36, 1),
    box-shadow 0.35s ease;
}
.cover-box.is-playing {
  transform: scale(1);
  box-shadow: 0 16px 40px rgba(0, 0, 0, 0.26);
}
.cover-img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}
.cover-fallback {
  display: flex;
  align-items: center;
  justify-content: center;
}

.track-meta {
  width: 100%;
  text-align: center;
  min-width: 0;
}
.track-title {
  font-size: 17px;
  font-weight: 600;
  color: var(--text-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.track-artist {
  margin-top: 2px;
  font-size: 13px;
  color: var(--text-muted);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* ── 进度条：原生 range 自绘。轨道 4px，滑块默认隐形、悬停/拖动才出现 ── */
.progress-row {
  width: 100%;
}
.progress,
.volume {
  -webkit-appearance: none;
  appearance: none;
  width: 100%;
  height: 4px;
  border-radius: 999px;
  outline: none;
  cursor: pointer;
  /* 已播部分用强调色，剩余部分用细描边色；--played 由内联 style 给出 */
  background: linear-gradient(
    to right,
    var(--accent-color) 0%,
    var(--accent-color) var(--played, 0%),
    var(--border-subtle) var(--played, 0%),
    var(--border-subtle) 100%
  );
}
.progress:disabled {
  cursor: default;
  opacity: 0.6;
}
.progress::-webkit-slider-thumb,
.volume::-webkit-slider-thumb {
  -webkit-appearance: none;
  appearance: none;
  width: 12px;
  height: 12px;
  border-radius: 50%;
  background: var(--accent-color);
  /* 不用 display:none：那会让拖动区域一起消失 */
  opacity: 0;
  transition: opacity 0.15s ease;
}
.progress:hover::-webkit-slider-thumb,
.progress:active::-webkit-slider-thumb,
.volume:hover::-webkit-slider-thumb,
.volume:active::-webkit-slider-thumb {
  opacity: 1;
}
.progress::-moz-range-thumb,
.volume::-moz-range-thumb {
  width: 12px;
  height: 12px;
  border: none;
  border-radius: 50%;
  background: var(--accent-color);
  opacity: 0;
  transition: opacity 0.15s ease;
}
.progress:hover::-moz-range-thumb,
.volume:hover::-moz-range-thumb {
  opacity: 1;
}

.time-row {
  display: flex;
  justify-content: space-between;
  margin-top: 6px;
}
.time {
  font-size: 11px;
  color: var(--text-muted);
  font-variant-numeric: tabular-nums;
}

/* ── 走带：播放钮明显更大，它是唯一的主操作 ── */
.transport {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 22px;
}
.tp-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 38px;
  height: 38px;
  border: none;
  border-radius: 50%;
  background: transparent;
  color: var(--text-primary);
  cursor: pointer;
  transition:
    background 0.15s,
    transform 0.12s;
}
.tp-btn:hover:not(:disabled) {
  background: var(--surface-hover);
}
.tp-btn:active:not(:disabled) {
  transform: scale(0.92);
}
.tp-btn:disabled {
  color: var(--text-muted);
  cursor: not-allowed;
  opacity: 0.5;
}
.tp-play {
  width: 54px;
  height: 54px;
  background: var(--accent-color);
  color: #fff;
}
.tp-play:hover {
  /* 主钮 hover 不改背景（改了会和次级按钮的 hover 语义混淆），只轻微放大 */
  background: var(--accent-color);
  transform: scale(1.04);
}

.volume-row {
  display: flex;
  align-items: center;
  gap: 10px;
  width: 78%;
}
.vol-icon {
  color: var(--text-muted);
  flex-shrink: 0;
}

/* ── 右栏：歌词 ── */
.player-lyrics {
  display: flex;
  flex-direction: column;
  min-width: 0;
  /* 与左栏封面顶部对齐后，高度跟着左栏走 */
  height: 100%;
}
.lyrics-head {
  display: flex;
  align-items: baseline;
  gap: 10px;
  margin-bottom: 10px;
}
.lyrics-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--text-primary);
}
.lyrics-note {
  font-size: 12px;
  color: var(--text-muted);
}
.lyrics-box {
  flex: 1;
  min-height: 280px;
  max-height: 56vh;
  overflow-y: auto;
  padding: 8px 4px;
  /* 上下渐隐：让"当前行在中间"这件事看起来是自然的，而不是被裁断 */
  mask-image: linear-gradient(to bottom, transparent, #000 12%, #000 88%, transparent);
  -webkit-mask-image: linear-gradient(to bottom, transparent, #000 12%, #000 88%, transparent);
}
.lyric-line {
  margin: 0;
  padding: 7px 0;
  font-size: 15px;
  line-height: 1.6;
  color: var(--text-muted);
  transition:
    color 0.25s ease,
    font-size 0.25s ease;
  word-break: break-word;
}
/* 当前行：加粗、放大、主色。Apple Music 靠这三样同时变化来"抓眼" */
.lyric-line.is-active {
  font-size: 18px;
  font-weight: 700;
  color: var(--text-primary);
}
.lyrics-placeholder {
  padding: 24px 4px;
  font-size: 12px;
  line-height: 1.6;
  color: var(--text-muted);
}

/* 窄屏：歌词落到封面下方，封面不再占满整宽（否则一屏只剩封面） */
@media (max-width: 900px) {
  .player {
    grid-template-columns: minmax(0, 1fr);
    gap: 20px;
  }
  .cover-box {
    max-width: 260px;
    margin: 0 auto;
  }
  .lyrics-box {
    min-height: 200px;
    max-height: 40vh;
  }
}
</style>

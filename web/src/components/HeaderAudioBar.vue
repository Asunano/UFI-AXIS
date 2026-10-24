<!--
  顶栏全局播放器（2026-10-24 改版）。

  · 只保留「封面 + 曲名/歌手 + 走带 + 随机/循环/倍速 + 进度」；
  · 不再在顶栏里展开完整播放器：悬停或点击封面，浮层只显示歌词；
  · 歌词浮层与播放器同宽（窄屏取最小可读宽度），不显示时间轴、不显示滚动条；
  · 当前行只加粗，不加背景色；
  · 播放状态仍然只有 `stores/player.ts` 一份真源，本组件只读 + 派发动作。
-->
<template>
  <div
    v-if="player.currentTrack"
    ref="rootEl"
    class="header-audio"
    @mouseenter="scheduleOpen"
    @mouseleave="scheduleClose"
  >
    <button
      type="button"
      class="ha-now"
      :title="titleTip"
      :aria-label="`查看歌词：${player.displayTitle}`"
      @click.stop="openLyrics"
    >
      <span class="ha-cover" :class="{ 'is-empty': !player.coverUrl || coverBroken }">
        <img
          v-if="player.coverUrl && !coverBroken"
          :src="player.coverUrl"
          :alt="player.displayTitle"
          @error="coverBroken = true"
        />
        <svg v-else viewBox="0 0 24 24" aria-hidden="true">
          <path fill="currentColor" d="M12 3v10.55A4 4 0 1 0 14 17V7h4V3h-6z" />
        </svg>
      </span>
      <span class="ha-text">
        <span class="ha-title" :class="{ 'is-error': !!player.errorText }" :title="titleTip">
          {{ player.displayTitle }}
        </span>
        <span class="ha-sub" :title="player.displayArtist">{{ player.displayArtist }}</span>
      </span>
    </button>

    <div class="ha-controls">
      <button
        type="button"
        class="ha-btn side"
        :disabled="!player.hasPrev"
        title="上一首"
        aria-label="上一首"
        @click="player.prev()"
      >
        <svg viewBox="0 0 24 24" class="ha-icon small" aria-hidden="true">
          <path fill="currentColor" d="M6 6h2v12H6V6zm3.5 6L18 6v12l-8.5-6z" />
        </svg>
      </button>
      <button type="button" class="ha-btn primary" :title="playTitle" :aria-label="playTitle" @click="player.toggle()">
        <svg v-if="player.buffering" viewBox="0 0 24 24" class="ha-icon play spin" aria-hidden="true">
          <circle
            cx="12"
            cy="12"
            r="8"
            fill="none"
            stroke="currentColor"
            stroke-width="2.5"
            stroke-linecap="round"
            stroke-dasharray="13 38"
          />
        </svg>
        <svg v-else-if="player.playing" viewBox="0 0 24 24" class="ha-icon play" aria-hidden="true">
          <path fill="currentColor" d="M6 19h4V5H6v14zm8-14v14h4V5h-4z" />
        </svg>
        <svg v-else viewBox="0 0 24 24" class="ha-icon play" aria-hidden="true">
          <path fill="currentColor" d="M8 5v14l11-7L8 5z" />
        </svg>
      </button>
      <button
        type="button"
        class="ha-btn side"
        :disabled="!player.hasNext"
        title="下一首"
        aria-label="下一首"
        @click="player.next()"
      >
        <svg viewBox="0 0 24 24" class="ha-icon small" aria-hidden="true">
          <path fill="currentColor" d="M16 6h2v12h-2V6zM6 6l8.5 6L6 18V6z" />
        </svg>
      </button>
    </div>

    <div class="ha-toggles">
      <button
        type="button"
        class="ha-chip"
        :class="{ on: player.shuffle }"
        :aria-pressed="player.shuffle"
        title="随机播放"
        aria-label="随机播放"
        @click="player.toggleShuffle()"
      >
        <svg viewBox="0 0 24 24" aria-hidden="true">
          <path
            fill="currentColor"
            d="M10.59 9.17 5.41 4 4 5.41l5.17 5.17 1.42-1.41zM14.5 4l2.04 2.04L4 18.59 5.41 20 17.96 7.46 20 9.5V4h-5.5zm.33 9.41-1.41 1.41 3.13 3.13L14.5 20H20v-5.5l-2.04 2.04-3.13-3.13z"
          />
        </svg>
      </button>
      <button
        type="button"
        class="ha-chip"
        :class="{ on: player.repeatMode !== 'off' }"
        :aria-pressed="player.repeatMode !== 'off'"
        :title="repeatTitle"
        :aria-label="repeatTitle"
        @click="player.cycleRepeat()"
      >
        <svg v-if="player.repeatMode === 'one'" viewBox="0 0 24 24" aria-hidden="true">
          <path
            fill="currentColor"
            d="M7 7h10v3l4-4-4-4v3H5v6h2V7zm10 10H7v-3l-4 4 4 4v-3h12v-6h-2v4zm-4-2V9h-1l-2 1v1h1.5v4H13z"
          />
        </svg>
        <svg v-else viewBox="0 0 24 24" aria-hidden="true">
          <path fill="currentColor" d="M7 7h10v3l4-4-4-4v3H5v6h2V7zm10 10H7v-3l-4 4 4 4v-3h12v-6h-2v4z" />
        </svg>
      </button>
      <button
        type="button"
        class="ha-chip rate"
        :title="`播放速度 ${rateText}`"
        :aria-label="`播放速度 ${rateText}`"
        @click="player.cyclePlaybackRate()"
      >
        {{ rateText }}
      </button>
    </div>

    <div v-if="player.durationSec > 0" class="ha-progress">
      <div class="ha-track"><div class="ha-fill" :style="{ width: `${progressPercent}%` }" /></div>
      <input
        class="ha-range"
        type="range"
        min="0"
        :max="player.durationSec"
        step="0.01"
        :value="displaySec"
        aria-label="播放进度"
        @input="onScrubInput"
        @change="commitSeek"
      />
    </div>

    <Teleport to="body">
      <Transition name="ha-lyrics">
        <div
          v-if="lyricsOpen"
          ref="panelEl"
          class="ha-lyrics-pop"
          :style="panelStyle"
          @mouseenter="cancelClose"
          @mouseleave="scheduleClose"
          @click.stop
        >
          <div ref="lyricsBodyEl" class="ha-lyrics-body">
            <template v-if="lyricLines.length">
              <p
                v-for="(line, i) in lyricLines"
                :key="i"
                :ref="(el) => setLineRef(el, i)"
                class="ha-lyric"
                :class="{ current: i === currentLyricIndex }"
              >
                {{ line[1] }}
              </p>
            </template>
            <p v-else class="ha-lyrics-empty">暂无歌词</p>
          </div>
        </div>
      </Transition>
    </Teleport>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { usePlayerStore } from '@/stores/player';

const player = usePlayerStore();

/** 封面 URL 失效时退回音符图标，避免顶栏出现浏览器破图。 */
const coverBroken = ref(false);
watch(
  () => player.coverUrl,
  () => {
    coverBroken.value = false;
  }
);

const playTitle = computed(() => (player.buffering ? '缓冲中' : player.playing ? '暂停' : '播放'));
const repeatTitle = computed(() => {
  if (player.repeatMode === 'one') return '单曲循环';
  if (player.repeatMode === 'all') return '列表循环';
  return '循环关闭';
});
const rateText = computed(() => `${player.playbackRate}×`);
const titleTip = computed(() => player.errorText || player.displayTitle);

// ── 进度（唯一的本地状态） ──
const scrubbing = ref(false);
const scrubPos = ref(0);
const displaySec = computed(() => (scrubbing.value ? scrubPos.value : player.positionSec));
const progressPercent = computed(() => {
  const d = player.durationSec;
  if (!Number.isFinite(d) || d <= 0) return 0;
  return Math.min(100, Math.max(0, (displaySec.value / d) * 100));
});
function onScrubInput(e: Event) {
  scrubbing.value = true;
  scrubPos.value = Number((e.target as HTMLInputElement).value);
}
function commitSeek(e: Event) {
  const sec = Number((e.target as HTMLInputElement).value);
  scrubbing.value = false;
  player.seek(sec);
}

// ── 歌词浮层 ──
const lyricLines = computed(() => player.lyricLines);
const lyricsSynced = computed(() => lyricLines.value.some(([t]) => t >= 0));
const currentLyricIndex = computed(() => {
  if (!lyricsSynced.value) return -1;
  const now = player.positionMs;
  let idx = -1;
  for (let i = 0; i < lyricLines.value.length; i++) {
    const line = lyricLines.value[i];
    if (line && line[0] <= now) idx = i;
    else break;
  }
  return idx;
});

const rootEl = ref<HTMLElement | null>(null);
const panelEl = ref<HTMLElement | null>(null);
const lyricsBodyEl = ref<HTMLElement | null>(null);
const lyricsOpen = ref(false);
const panelPos = ref({ top: 0, left: 0, width: 360 });
const panelStyle = computed(() => ({
  top: `${panelPos.value.top}px`,
  left: `${panelPos.value.left}px`,
  width: `${panelPos.value.width}px`,
}));

let openTimer: ReturnType<typeof setTimeout> | null = null;
let closeTimer: ReturnType<typeof setTimeout> | null = null;

function updatePanelPosition() {
  const rect = rootEl.value?.getBoundingClientRect();
  if (!rect) return;
  // 桌面/平板与媒体条同宽；窄屏媒体条只剩图标时，歌词保底 320px 可读宽度
  const width = Math.min(rect.width >= 280 ? rect.width : 320, Math.max(200, window.innerWidth - 16));
  const maxLeft = Math.max(8, window.innerWidth - width - 8);
  panelPos.value = {
    top: Math.round(rect.bottom + 8),
    left: Math.round(Math.min(Math.max(8, rect.left), maxLeft)),
    width: Math.round(width),
  };
}

function cancelClose() {
  if (closeTimer) {
    clearTimeout(closeTimer);
    closeTimer = null;
  }
}

function cancelOpen() {
  if (openTimer) {
    clearTimeout(openTimer);
    openTimer = null;
  }
}

function scheduleOpen() {
  cancelClose();
  cancelOpen();
  openTimer = setTimeout(() => {
    openTimer = null;
    openLyrics();
  }, 140);
}

function scheduleClose() {
  cancelOpen();
  cancelClose();
  closeTimer = setTimeout(() => {
    closeTimer = null;
    lyricsOpen.value = false;
  }, 180);
}

function openLyrics() {
  lyricsOpen.value = true;
  updatePanelPosition();
  nextTick(() => scrollToCurrent(true));
}

const lineEls = new Map<number, HTMLElement>();
function setLineRef(el: unknown, i: number) {
  if (el instanceof HTMLElement) lineEls.set(i, el);
  else lineEls.delete(i);
}

function scrollToCurrent(instant = false) {
  if (!lyricsOpen.value) return;
  requestAnimationFrame(() => {
    const body = lyricsBodyEl.value;
    const idx = currentLyricIndex.value;
    if (!body || idx < 0) return;
    const line = lineEls.get(idx);
    if (!line) return;
    // 不能用 line.offsetTop：它的 offsetParent 不一定是滚动容器（面板是 fixed）。
    // 用两个 rect 的差值换算成滚动容器内的相对位置，再减去可视高度的一半。
    const bodyRect = body.getBoundingClientRect();
    const lineRect = line.getBoundingClientRect();
    const lineTop = body.scrollTop + lineRect.top - bodyRect.top;
    const top = Math.max(0, lineTop - (body.clientHeight - lineRect.height) / 2);
    body.scrollTo({ top, behavior: instant ? 'auto' : 'smooth' });
  });
}

watch(currentLyricIndex, () => {
  if (lyricsOpen.value) nextTick(() => scrollToCurrent());
});
watch(lyricsOpen, (open) => {
  if (open) nextTick(() => scrollToCurrent(true));
});

function onPointerDown(event: PointerEvent) {
  if (!lyricsOpen.value) return;
  const target = event.target as Node;
  if (rootEl.value?.contains(target) || panelEl.value?.contains(target)) return;
  lyricsOpen.value = false;
}
function onKeydown(event: KeyboardEvent) {
  if (event.key === 'Escape') lyricsOpen.value = false;
}
function onResize() {
  if (lyricsOpen.value) updatePanelPosition();
}

onMounted(() => {
  document.addEventListener('pointerdown', onPointerDown);
  document.addEventListener('keydown', onKeydown);
  window.addEventListener('resize', onResize);
});
onBeforeUnmount(() => {
  document.removeEventListener('pointerdown', onPointerDown);
  document.removeEventListener('keydown', onKeydown);
  window.removeEventListener('resize', onResize);
  cancelOpen();
  cancelClose();
});
</script>

<style scoped>
.header-audio {
  position: relative;
  box-sizing: border-box;
  width: 360px;
  max-width: 360px;
  flex: 0 1 360px;
  height: 40px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 6px;
  padding: 0 10px;
  border: 0;
  border-radius: 10px;
  background: var(--card-bg);
  box-shadow: 0 3px 12px -9px rgba(15, 30, 22, 0.35);
  overflow: hidden;
  transition: background 0.16s ease;
}
.header-audio:hover {
  background: var(--surface-hover);
}

.ha-now {
  display: flex;
  align-items: center;
  gap: 8px;
  flex: 0 1 150px;
  max-width: 150px;
  min-width: 0;
  padding: 0;
  border: none;
  background: transparent;
  color: inherit;
  font: inherit;
  text-align: left;
  cursor: pointer;
}
.ha-cover {
  display: flex;
  align-items: center;
  justify-content: center;
  flex: 0 0 auto;
  width: 28px;
  height: 28px;
  overflow: hidden;
  border-radius: 7px;
  color: var(--accent-color);
  background: var(--surface-hover);
  box-shadow: inset 0 0 0 1px rgba(255, 255, 255, 0.1);
}
.ha-cover img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}
.ha-cover svg {
  width: 58%;
  height: 58%;
}
.ha-text {
  display: flex;
  flex-direction: column;
  gap: 1px;
  min-width: 0;
}
.ha-title {
  max-width: 112px;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
  font-size: 12px;
  font-weight: 600;
  line-height: 1.2;
  color: var(--text-primary);
}
.ha-title.is-error {
  color: var(--warning);
}
.ha-sub {
  max-width: 112px;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
  font-size: 10px;
  line-height: 1.2;
  color: var(--text-muted);
}
.ha-now:hover .ha-title {
  color: var(--accent-color);
}
.ha-now:hover .ha-title.is-error {
  color: var(--warning);
}

.ha-controls {
  display: flex;
  align-items: center;
  gap: 1px;
  flex: none;
}
.ha-btn {
  appearance: none;
  display: grid;
  place-items: center;
  width: 24px;
  height: 26px;
  border: 0;
  border-radius: 6px;
  background: transparent;
  color: var(--text-secondary);
  cursor: pointer;
}
.ha-btn:hover:not(:disabled) {
  background: var(--surface-hover);
  color: var(--text-primary);
}
.ha-btn:disabled {
  opacity: 0.35;
  cursor: default;
}
.ha-btn.primary {
  width: 27px;
  height: 27px;
  margin: 0 2px;
  border-radius: 50%;
  color: var(--card-bg);
  background: var(--text-primary);
}
.ha-btn.primary:hover:not(:disabled) {
  opacity: 0.88;
  background: var(--text-primary);
  color: var(--card-bg);
}
.ha-icon {
  width: 16px;
  height: 16px;
  display: block;
}
.ha-icon.small {
  width: 13px;
  height: 13px;
}
.ha-icon.play.spin {
  animation: ha-spin 0.9s linear infinite;
}
@keyframes ha-spin {
  to {
    transform: rotate(360deg);
  }
}

.ha-toggles {
  display: flex;
  align-items: center;
  gap: 1px;
  flex: none;
}
.ha-chip {
  appearance: none;
  height: 22px;
  padding: 0 5px;
  border: 0;
  border-radius: 5px;
  background: transparent;
  color: var(--text-muted);
  font-size: 10px;
  display: inline-flex;
  align-items: center;
  cursor: pointer;
}
.ha-chip svg {
  width: 12px;
  height: 12px;
}
.ha-chip:hover {
  background: var(--surface-hover);
  color: var(--text-secondary);
}
.ha-chip.on {
  color: var(--text-primary);
  background: var(--surface-active, var(--surface-hover));
}

.ha-progress {
  position: absolute;
  left: 10px;
  right: 10px;
  bottom: 0;
  height: 8px;
}
.ha-track {
  position: absolute;
  left: 0;
  right: 0;
  bottom: 0;
  height: 2px;
  background: var(--border-subtle);
  overflow: hidden;
}
.ha-fill {
  height: 100%;
  background: var(--accent-color);
}
.ha-range {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  margin: 0;
  opacity: 0;
  cursor: pointer;
}

/* ── 歌词浮层：与播放器同宽，浅/深主题跟随 ── */
.ha-lyrics-pop {
  position: fixed;
  z-index: 4000;
  padding: 0;
  border: 1px solid var(--border-subtle);
  border-radius: 16px;
  background: var(--card-bg);
  box-shadow: 0 22px 56px -26px rgba(15, 30, 22, 0.35);
  overflow: hidden;
  color: var(--text-primary);
}
.ha-lyrics-enter-active,
.ha-lyrics-leave-active {
  transition:
    opacity 0.16s ease,
    transform 0.16s ease;
}
.ha-lyrics-enter-from,
.ha-lyrics-leave-to {
  opacity: 0;
  transform: translateY(-5px) scale(0.985);
}
.ha-lyrics-body {
  max-height: 170px;
  overflow-y: auto;
  padding: 12px 18px;
  text-align: center;
  scrollbar-width: none;
  -ms-overflow-style: none;
  mask-image: linear-gradient(to bottom, transparent 0, #000 14px, #000 calc(100% - 16px), transparent 100%);
  -webkit-mask-image: linear-gradient(to bottom, transparent 0, #000 14px, #000 calc(100% - 16px), transparent 100%);
}
.ha-lyrics-body::-webkit-scrollbar {
  display: none;
  width: 0;
  height: 0;
}
.ha-lyric {
  margin: 0;
  padding: 3px 6px;
  border-radius: 7px;
  font-size: 11.5px;
  line-height: 1.6;
  color: var(--text-secondary);
  transition:
    color 0.2s ease,
    font-size 0.18s ease;
}
.ha-lyric.current {
  padding: 6px;
  font-size: 17px;
  font-weight: 650;
  line-height: 1.45;
  color: var(--text-primary);
}
.ha-lyrics-empty {
  margin: 0;
  padding: 22px 0;
  font-size: 12px;
  color: var(--text-muted);
}

@media (max-width: 1180px) {
  .header-audio {
    width: 340px;
    max-width: 340px;
    flex-basis: 340px;
  }
  .ha-chip.rate {
    display: none;
  }
}
@media (max-width: 1024px) {
  .header-audio {
    width: 320px;
    max-width: 320px;
    flex-basis: 320px;
  }
  .ha-toggles {
    display: none;
  }
  .ha-title {
    max-width: 120px;
  }
}
@media (max-width: 720px) {
  .header-audio {
    width: 320px;
    max-width: 320px;
    flex-basis: 320px;
    padding: 0 10px;
  }
  .ha-now {
    flex: 1 1 auto;
    max-width: 150px;
  }
  .ha-title,
  .ha-sub {
    max-width: 120px;
  }
  .ha-controls {
    flex: none;
  }
  .ha-btn.side {
    display: none;
  }
}
@media (max-width: 660px) {
  .header-audio {
    width: 180px;
    max-width: 180px;
    flex-basis: 180px;
    padding: 0 8px;
  }
  .ha-now {
    max-width: 110px;
  }
  .ha-title,
  .ha-sub {
    max-width: 80px;
  }
}
@media (max-width: 480px) {
  .header-audio {
    width: 38px;
    max-width: 38px;
    flex-basis: 38px;
    padding: 0;
    justify-content: center;
  }
  .ha-now {
    flex: none;
  }
  .ha-text,
  .ha-controls {
    display: none;
  }
}
</style>

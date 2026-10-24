<!--
  播放控制条（2026-10-23 重做）。

  ## 为什么单独一个组件
  桌面播放条与沉浸播放页需要**同一套走带/进度/次要动作**，只是排布不同：
    · `variant="bar"`：左侧当前曲目（点开出沉浸）+ 中间控制 + 右侧次要动作；
    · `variant="immersive"`：没有左侧曲目（沉浸页自己有大封面），整宽进度 + 左/中/右三段。
  两处各写一份就会漂移（一边加了睡眠、另一边忘了），所以只做这一份，靠 variant 换布局。

  ## 状态从哪来
  全部读写 `stores/player.ts`（播放状态必须比页面活得久，见那个文件头）。
  本组件**不持有任何播放状态**，只派发 store 动作；页面只通过三个事件决定"弹哪一层"：
  `open-queue`（队列下拉）、`toggle-lyrics`（歌词显隐）、`open-stage`（进入沉浸）。

  ## 按钮层级
  主走带（随机 / 上一首 / 播放 / 下一首 / 循环）永远居中；音量、倍速在左，
  歌词、队列、睡眠、关闭在右。旧版把它们和时间、进度塞在同一行，中文一多就互相挤。
-->
<template>
  <div class="acb" :class="[`is-${variant}`]">
    <!-- 左：当前曲目（仅普通播放条） -->
    <div
      v-if="variant === 'bar'"
      class="acb-now"
      role="button"
      tabindex="0"
      @click="emit('open-stage')"
      @keyup.enter="emit('open-stage')"
    >
      <img
        v-if="player.coverUrl && !coverBroken"
        :src="player.coverUrl"
        alt=""
        class="acb-cover"
        @error="coverBroken = true"
      />
      <span v-else class="acb-cover acb-cover--empty">
        <n-icon :size="18"><MusicalNotesOutline /></n-icon>
      </span>
      <span class="acb-meta">
        <b :title="player.displayTitle">{{ player.displayTitle }}</b>
        <span :title="player.displayArtist">{{ player.displayArtist }}</span>
      </span>
    </div>

    <!-- 中：进度 + 走带（bar 时在中间列，immersive 时进度独占一行、走带在左下） -->
    <div class="acb-center">
      <div class="acb-progress" @pointerenter="showTimeTip = true" @pointerleave="showTimeTip = false">
        <div class="acb-slider-wrap">
          <n-slider
            class="acb-slider"
            :value="sliderValue"
            :max="sliderMax"
            :step="1"
            :tooltip="false"
            @update:value="onSeek"
          />
          <Transition name="time-tip">
            <span
              v-if="showTimeTip && player.durationSec > 0"
              class="acb-time-tip"
              :style="{ left: `${timeTipPercent}%` }"
            >
              {{ fmt(player.positionSec) }} <em>/ {{ fmt(player.durationSec) }}</em>
            </span>
          </Transition>
        </div>
      </div>

      <div class="acb-transport">
        <button
          type="button"
          class="acb-icon"
          :class="{ on: player.shuffle }"
          :aria-pressed="player.shuffle"
          :title="player.shuffle ? '随机播放：已开（只改播放顺序，不动队列）' : '随机播放：已关'"
          @click="player.toggleShuffle()"
        >
          <n-icon :size="17"><ShuffleOutline /></n-icon>
        </button>
        <button type="button" class="acb-icon" :disabled="!player.hasPrev" title="上一首" @click="player.prev()">
          <n-icon :size="18"><PlaySkipBackOutline /></n-icon>
        </button>
        <button type="button" class="acb-play" :title="player.playing ? '暂停' : '播放'" @click="player.toggle()">
          <n-icon :size="20">
            <PauseOutline v-if="player.playing" />
            <PlayOutline v-else />
          </n-icon>
        </button>
        <button type="button" class="acb-icon" :disabled="!player.hasNext" title="下一首" @click="player.next()">
          <n-icon :size="18"><PlaySkipForwardOutline /></n-icon>
        </button>
        <button
          type="button"
          class="acb-icon"
          :class="{ on: player.repeatMode !== 'off' }"
          :title="repeatTitle"
          @click="player.cycleRepeat()"
        >
          <n-icon :size="17"><RepeatOutline /></n-icon>
        </button>
      </div>
    </div>

    <!-- 右：次要动作 + 队列 / 歌词。
         沉浸层里左组（音量/倍速）落在左栏、右组（歌词/队列/睡眠/关闭）落在右栏，
         与 mockup 的「左 - 走带 - 右」三段式一致。 -->
    <div class="acb-actions">
      <span class="acb-side acb-side--left">
        <n-popover trigger="click" placement="top-start" :width="180">
          <template #trigger>
            <button type="button" class="acb-icon vol" :title="`音量 ${volumePercent}%`">
              <n-icon :size="16"><VolumeHighOutline /></n-icon>
            </button>
          </template>
          <div class="acb-vol">
            <n-slider :value="volumePercent" :step="1" :tooltip="false" @update:value="onVolume" />
          </div>
        </n-popover>
        <n-dropdown :options="rateOptions" trigger="click" @select="onRate">
          <button type="button" class="acb-rate" :class="{ on: player.playbackRate !== 1 }" title="播放速度">
            {{ rateLabel }}
          </button>
        </n-dropdown>
      </span>

      <span class="acb-side acb-side--right">
        <button
          type="button"
          class="acb-text"
          :class="{ on: lyricsOpen }"
          :aria-pressed="lyricsOpen"
          title="显示 / 隐藏歌词"
          @click="emit('toggle-lyrics')"
        >
          歌词
        </button>
        <button type="button" class="acb-text" title="队列（下拉）" @click="emit('open-queue')">队列</button>
        <n-popover v-model:show="sleepOpen" trigger="click" placement="top-end" :width="236">
          <template #trigger>
            <button type="button" class="acb-icon" :class="{ on: player.sleepMode !== 'off' }" :title="sleepTitle">
              <n-icon :size="16"><MoonOutline /></n-icon>
            </button>
          </template>
          <div class="acb-sleep">
            <div class="acb-sleep-title">睡眠定时</div>
            <ToggleRow
              class="acb-sleep-switch"
              label="播完当前曲目"
              description="当前曲目结束后暂停，不再自动切下一首"
              :model-value="player.sleepMode === 'end-of-track'"
              :disabled="!player.currentTrack"
              @update:model-value="player.toggleSleepAfterCurrentTrack()"
            />
            <div class="acb-sleep-grid">
              <button v-for="m in SLEEP_PRESETS" :key="m" type="button" @click="setSleep(m)">{{ m }} 分钟</button>
              <button v-if="player.sleepMode === 'countdown'" type="button" class="cancel" @click="cancelSleep">
                取消倒计时
              </button>
            </div>
          </div>
        </n-popover>
        <button
          v-if="variant === 'immersive'"
          type="button"
          class="acb-icon"
          title="关闭正在播放"
          aria-label="关闭正在播放"
          @click="emit('close')"
        >
          <n-icon :size="16"><CloseOutline /></n-icon>
        </button>
        <button v-if="variant === 'bar'" type="button" class="acb-icon" title="沉浸播放" @click="emit('open-stage')">
          <n-icon :size="16"><ExpandOutline /></n-icon>
        </button>
      </span>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import {
  CloseOutline,
  ExpandOutline,
  MoonOutline,
  MusicalNotesOutline,
  PauseOutline,
  PlayOutline,
  PlaySkipBackOutline,
  PlaySkipForwardOutline,
  RepeatOutline,
  ShuffleOutline,
  VolumeHighOutline,
} from '@vicons/ionicons5';
import { SLEEP_PRESET_MINUTES, usePlayerStore } from '@/stores/player';
import ToggleRow from '@/components/ToggleRow.vue';

withDefaults(
  defineProps<{
    /** bar = 页面底部播放条；immersive = 沉浸播放页底部 */
    variant?: 'bar' | 'immersive';
    /** 歌词当前是否展开（只用于按钮状态） */
    lyricsOpen?: boolean;
  }>(),
  { variant: 'bar', lyricsOpen: true }
);

const emit = defineEmits<{
  (e: 'open-queue'): void;
  (e: 'toggle-lyrics'): void;
  (e: 'open-stage'): void;
  (e: 'close'): void;
}>();

const player = usePlayerStore();
const SLEEP_PRESETS = SLEEP_PRESET_MINUTES;

const sleepOpen = ref(false);
/** 鼠标/触摸进入进度条时显示「播放时间 / 总时长」，位置跟随当前进度圆点。 */
const showTimeTip = ref(false);
const timeTipPercent = computed(() => Math.min(96, Math.max(4, (sliderValue.value / sliderMax.value) * 100)));
/** 封面 URL 失效时退回音符图标，避免播放条出现浏览器破图。 */
const coverBroken = ref(false);
watch(
  () => player.coverUrl,
  () => {
    coverBroken.value = false;
  }
);

const sliderMax = computed(() => Math.max(player.durationSec || 0, 1));
const sliderValue = computed(() => Math.min(player.positionSec || 0, sliderMax.value));

const volumePercent = computed(() => Math.round((player.volume || 0) * 100));
const rateLabel = computed(() => `${player.playbackRate}×`);
const rateOptions = [0.75, 1, 1.25, 1.5, 1.75, 2].map((r) => ({ label: `${r}×`, key: String(r) }));

const repeatTitle = computed(() => {
  if (player.repeatMode === 'one') return '单曲循环：已开';
  if (player.repeatMode === 'all') return '列表循环：已开';
  return '循环：关';
});

const sleepTitle = computed(() => {
  if (player.sleepMode === 'off') return '睡眠定时';
  if (player.sleepMode === 'end-of-track') return '播完当前曲目后停止';
  const m = Math.max(1, Math.ceil(player.sleepRemainingSec / 60));
  return `睡眠定时：还剩约 ${m} 分钟`;
});

/** 秒 → m:ss / h:mm:ss；非法值才显示 `--`，0 秒必须显示 0:00 */
function fmt(sec: number): string {
  if (!Number.isFinite(sec) || sec < 0) return '--';
  const s = Math.floor(sec);
  const h = Math.floor(s / 3600);
  const m = Math.floor((s % 3600) / 60);
  const r = s % 60;
  return h ? `${h}:${String(m).padStart(2, '0')}:${String(r).padStart(2, '0')}` : `${m}:${String(r).padStart(2, '0')}`;
}

function onSeek(v: number | [number, number]) {
  const sec = Array.isArray(v) ? v[0] : v;
  player.seek(sec);
}
function onVolume(v: number | [number, number]) {
  const pct = Array.isArray(v) ? v[0] : v;
  player.setVolume(pct / 100);
}
function onRate(key: string | number) {
  player.setPlaybackRate(Number(key));
}
function setSleep(minutes: number) {
  player.startSleepTimer(minutes);
  sleepOpen.value = false;
}
function cancelSleep() {
  player.cancelSleepTimer();
  sleepOpen.value = false;
}
</script>

<style scoped>
.acb {
  display: grid;
  /* B 方案：第一行通栏进度，第二行「当前曲目 / 走带 / 动作」三段。
     不再让中列固定吃 420px；任何宽度都不换行。 */
  grid-template-columns: minmax(0, 1fr) auto minmax(0, 1fr);
  grid-template-rows: auto auto;
  gap: 4px 14px;
  align-items: center;
  padding: 5px 16px 7px;
  border-top: 1px solid var(--border-subtle);
  background: var(--card-bg);
}
.acb-center {
  display: contents;
}
.acb-progress {
  grid-column: 1 / -1;
  grid-row: 1;
}
.acb-now {
  grid-column: 1;
  grid-row: 2;
}
.acb-transport {
  grid-column: 2;
  grid-row: 2;
}
.acb-actions {
  grid-column: 3;
  grid-row: 2;
  justify-self: end;
  flex-wrap: nowrap;
}
.acb.is-immersive {
  /* 与资料库播放条同一外框：透明 1px 顶边框 + 同一行高，
     这样进度条、走带、动作在两条播放条上的 y 坐标一致。 */
  border-top: 1px solid transparent;
  grid-template-rows: auto minmax(48px, auto);
  background: transparent;
  padding: 5px 16px 7px;
}
.acb.is-immersive .acb-center {
  display: contents;
}
.acb.is-immersive .acb-progress {
  grid-column: 1 / -1;
  grid-row: 1;
}
.acb.is-immersive .acb-transport {
  grid-column: 2;
  grid-row: 2;
  justify-self: center;
}
.acb.is-immersive .acb-actions {
  display: contents;
}
/* 沉浸层：左组（音量 / 倍速）在左栏，右组（歌词 / 队列 / 睡眠 / 关闭）在右栏，
   中间留给走带。bar 变体里两组仍在右端作为一组次要动作。 */
.acb.is-immersive .acb-side--left {
  grid-column: 1;
  grid-row: 2;
  justify-self: start;
}
.acb.is-immersive .acb-side--right {
  grid-column: 3;
  grid-row: 2;
  justify-self: end;
}

/* ── 当前曲目 ── */
.acb-now {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
  cursor: pointer;
  border-radius: var(--radius-md);
  padding: 4px;
}
.acb-now:hover {
  background: var(--surface-hover);
}
.acb-now:focus {
  outline: none;
}
.acb-now:focus-visible {
  outline: 2px solid var(--accent-color);
  outline-offset: -2px;
}
.acb-cover {
  width: 40px;
  height: 40px;
  border-radius: 8px;
  object-fit: cover;
  flex: none;
}
.acb-cover--empty {
  display: grid;
  place-items: center;
  background: var(--surface-elevated);
  color: var(--text-muted);
}
.acb-meta {
  min-width: 0;
  flex: 1;
}
.acb-meta b {
  display: block;
  font-size: 12.5px;
  font-weight: 600;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.acb-meta span {
  display: block;
  font-size: 11px;
  color: var(--text-muted);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

/* ── 进度 ── */
.acb-center {
  min-width: 0;
}
.acb-progress {
  position: relative;
  display: block;
  width: 100%;
  min-width: 0;
}
.acb-slider-wrap {
  position: relative;
  width: 100%;
  min-width: 0;
}
/* 常态不占字：鼠标（或触摸拖动）进入进度条才浮出「已播放 / 剩余」 */
.acb-time-tip {
  position: absolute;
  bottom: calc(100% + 6px);
  z-index: 3;
  transform: translateX(-50%);
  padding: 4px 7px;
  border-radius: 6px;
  background: var(--text-primary);
  color: var(--card-bg);
  font-size: 10.5px;
  line-height: 1.2;
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
  pointer-events: none;
  box-shadow: var(--shadow-color-soft);
}
.acb-time-tip em {
  font-style: normal;
  opacity: 0.68;
}
.time-tip-enter-active,
.time-tip-leave-active {
  transition:
    opacity 0.12s ease,
    transform 0.12s ease;
}
.time-tip-enter-from,
.time-tip-leave-to {
  opacity: 0;
  transform: translateX(-50%) translateY(3px);
}
.acb-slider {
  width: 100%;
  min-width: 0;
}
/* 让进度条细一点：naive 默认 6px 轨道在播放条里太重 */
.acb-slider :deep(.n-slider .n-slider-rail) {
  height: 4px;
  border-radius: 99px;
}
.acb-slider :deep(.n-slider .n-slider-rail__fill) {
  background: var(--accent-color);
}
/* 进度圆点：始终可见，绿点 + 白描边；沉浸层描边换成舞台底色。
   注意 `.acb-slider` 本身就挂在 `.n-slider` 根上，不能写成 `.acb-slider .n-slider`。 */
.acb-slider :deep(.n-slider-rail .n-slider-handles .n-slider-handle-wrapper .n-slider-handle) {
  width: 12px;
  height: 12px;
  border: 2px solid var(--card-bg);
  border-radius: 50%;
  background: var(--accent-color);
  box-shadow:
    0 0 0 1px var(--accent-color),
    0 1px 3px var(--shadow-color-soft);
}
.acb.is-immersive .acb-slider :deep(.n-slider-rail .n-slider-handles .n-slider-handle-wrapper .n-slider-handle) {
  border-color: var(--stage-bg, #0b0d11);
}

.acb-transport {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  margin-top: 1px;
}
.acb-actions {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 2px;
  flex-wrap: wrap;
  min-width: 0;
}
.acb-side {
  display: flex;
  align-items: center;
  gap: 2px;
  min-width: 0;
  flex: none;
}
.acb-icon {
  width: 30px;
  height: 30px;
  border: 0;
  background: none;
  border-radius: 50%;
  display: grid;
  place-items: center;
  color: var(--text-secondary);
  cursor: pointer;
  flex: none;
}
.acb-icon:hover:not(:disabled) {
  background: var(--surface-hover);
  color: var(--text-primary);
}
.acb-icon:disabled {
  opacity: 0.35;
  cursor: default;
}
.acb-icon.on {
  color: var(--accent-color);
}
.acb-rate,
.acb-text {
  border: 0;
  background: none;
  border-radius: var(--radius-sm);
  padding: 5px 8px;
  font-size: 12px;
  color: var(--text-secondary);
  cursor: pointer;
  font-variant-numeric: tabular-nums;
}
.acb-rate:hover,
.acb-text:hover {
  background: var(--surface-hover);
  color: var(--text-primary);
}
.acb-rate.on,
.acb-text.on {
  color: var(--accent-color);
  background: var(--accent-color-light);
}
.acb-play {
  width: 40px;
  height: 40px;
  border: 0;
  border-radius: 50%;
  background: var(--text-primary);
  color: var(--card-bg);
  display: grid;
  place-items: center;
  cursor: pointer;
}
.acb-play:hover {
  opacity: 0.9;
}

/* ── 音量 / 睡眠浮层 ── */
.acb-vol {
  padding: 8px 4px;
}
.acb-sleep-title {
  font-size: 12px;
  font-weight: 600;
  color: var(--text-primary);
  margin-bottom: 8px;
}
.acb-sleep-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 6px;
}
.acb-sleep-switch {
  padding: 8px 0 10px;
  border-bottom: 1px solid var(--border-subtle);
}
.acb-sleep-switch :deep(.toggle-label) {
  font-size: 12px;
}
.acb-sleep-switch :deep(.toggle-desc) {
  font-size: 10.5px;
}
.acb-sleep-grid button {
  border: 1px solid var(--border-subtle);
  background: var(--card-bg);
  border-radius: var(--radius-sm);
  padding: 6px 8px;
  font-size: 12px;
  color: var(--text-secondary);
  cursor: pointer;
}
.acb-sleep-grid button:hover {
  border-color: var(--accent-color);
  color: var(--accent-color);
}
.acb-sleep-grid button.cancel {
  grid-column: 1 / -1;
  color: var(--error);
  border-color: var(--error);
}

/* ── 沉浸变体：无左侧曲目、两行；颜色由舞台的浅/深变量提供 ── */
.acb.is-immersive {
  --acb-ink: var(--brand-ink);
  --acb-dim: var(--text-muted);
  --acb-line: var(--brand-glow-soft);
  color: var(--acb-ink);
}
.acb.is-immersive .acb-time {
  color: var(--acb-dim);
  font-size: 11px;
}
.acb.is-immersive .acb-icon {
  color: var(--brand-ink);
}
.acb.is-immersive .acb-icon:hover:not(:disabled) {
  background: var(--brand-glow-soft);
  color: var(--brand-ink);
}
.acb.is-immersive .acb-play {
  width: 44px;
  height: 44px;
  background: var(--brand-logo-bg);
  color: var(--brand-panel-1);
}
.acb.is-immersive .acb-text,
.acb.is-immersive .acb-rate {
  color: var(--brand-ink);
}
.acb.is-immersive .acb-text:hover,
.acb.is-immersive .acb-rate:hover {
  background: var(--brand-glow-soft);
  color: var(--brand-ink);
}
.acb.is-immersive .acb-text.on,
.acb.is-immersive .acb-rate.on {
  color: var(--accent-color);
  background: var(--accent-color-light);
}
.acb.is-immersive .acb-slider :deep(.n-slider-rail) {
  background: var(--border-subtle);
}
/* ── 手机（≤768）沉浸控制条：三行 ──
   旧版把动作塞进三列 grid 的第三列，390px 下那列只有 43px 宽，
   六个动作竖排成 5 行（152px 高），和走带完全不在一条线上。
   改成「进度 / 走带 / 动作」三行，动作一行居中；全部按钮保留。 */
@media (max-width: 768px) {
  .acb.is-immersive {
    grid-template-columns: 1fr;
    grid-template-rows: auto auto auto;
    gap: 8px;
    border-top: 0;
    padding: 4px 16px calc(8px + env(safe-area-inset-bottom));
  }
  .acb.is-immersive .acb-progress {
    grid-column: 1;
    grid-row: 1;
  }
  .acb.is-immersive .acb-transport {
    grid-column: 1;
    grid-row: 2;
  }
  .acb.is-immersive .acb-actions {
    display: flex;
    grid-column: 1;
    grid-row: 3;
    justify-self: center;
    justify-content: center;
    flex-wrap: nowrap;
    width: 100%;
  }
  .acb.is-immersive .acb-side--left,
  .acb.is-immersive .acb-side--right {
    grid-column: auto;
    grid-row: auto;
    justify-self: auto;
  }
}
</style>

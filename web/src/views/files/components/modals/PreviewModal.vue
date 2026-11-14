<template>
  <n-modal
    :show="show"
    preset="card"
    :title="file?.name || '预览'"
    class="modal-fit preview-modal"
    style="--modal-w: 960px; --modal-h: 860px"
    @update:show="emit('update:show', $event)"
  >
    <n-spin :show="loading" class="preview-spin">
      <!-- ── 图片：缩放与拖拽（变换直接写入 DOM，避免 Vue 每帧重渲染） ── -->
      <div v-if="kind === 'image'" class="preview-stage image-stage">
        <div class="stage-toolbar">
          <n-button size="tiny" quaternary @click="zoomBy(1.25)">放大</n-button>
          <n-button size="tiny" quaternary @click="zoomBy(0.8)">缩小</n-button>
          <n-button size="tiny" quaternary @click="resetZoom">1:1</n-button>
          <n-button size="tiny" quaternary @click="fitZoom">适应</n-button>
          <span class="zoom-label">{{ zoomPct }}%</span>
        </div>
        <div ref="imgViewportEl" class="image-viewport" @wheel.prevent="onWheel">
          <img
            v-if="url"
            ref="imgEl"
            class="preview-image"
            :src="url"
            :alt="file?.name"
            draggable="false"
            @pointerdown="onPanStart"
            @pointermove="onPanMove"
            @pointerup="onPanEnd"
            @pointercancel="onPanEnd"
          />
        </div>
      </div>

      <!-- ── 视频：统一使用 VideoPlayer（格式分档 / customType / 字幕 / 错误兜底均在其中）──
           不使用原生 `<video controls>`：无法解码的容器只会显示黑框。
           不在其下重复显示文件名：弹窗标题已包含文件名，重复显示会压缩播放区。 -->
      <div v-else-if="kind === 'video'" class="preview-stage video-stage">
        <VideoPlayer
          :url="url"
          :name="file?.name || ''"
          :path="file?.path || ''"
          can-download
          @download="downloadCurrent"
        />
      </div>

      <!-- ── 音频：左列封面 / 信息 / 控制，右列歌词 ──
           左列自上而下为：封面 → 标题层次 → 进度 → 控制 → 音量，视觉重心位于封面与
           播放键；歌词独占右列，滚动仅发生在歌词面板内部。 -->
      <div v-else-if="kind === 'audio'" class="preview-stage audio-stage">
        <div class="audio-body" :class="{ 'lyrics-off': !lyricsOpen }">
          <div class="audio-left">
            <div class="cover-box" :class="{ 'is-empty': !coverUrl }">
              <img v-if="coverUrl" class="cover-img" :src="coverUrl" :alt="songTitle" />
              <svg v-else class="cover-icon" viewBox="0 0 24 24" aria-hidden="true">
                <path fill="currentColor" d="M12 3v10.55A4 4 0 1 0 14 17V7h4V3h-6z" />
              </svg>
            </div>
            <div class="song-meta">
              <div class="song-title" :title="songTitle">{{ songTitle }}</div>
              <div class="song-artist" :title="songArtist">{{ songArtist }}</div>
              <div v-if="songAlbum" class="song-album" :title="songAlbum">{{ songAlbum }}</div>
            </div>
            <!-- 已播与剩余分列两端：剩余时间带负号；播放过程中剩余时间比总时长更具参考价值 -->
            <div class="seek-row">
              <span class="seek-time">{{ formatTime(scrubbing ? scrubPos : currentTime) }}</span>
              <input
                class="seek"
                :class="{ dragging: scrubbing }"
                :style="{ '--pct': seekPercent + '%' }"
                type="range"
                min="0"
                :max="duration || 0"
                step="0.01"
                :value="scrubbing ? scrubPos : currentTime"
                :disabled="!url || !duration"
                aria-label="播放进度"
                @input="onSeekInput"
                @change="commitSeek"
              />
              <span class="seek-time right">-{{ formatTime(remainSeconds) }}</span>
            </div>
            <div class="ctrl-row">
              <button type="button" class="ctrl-btn" title="后退 10 秒" aria-label="后退 10 秒" @click="skip(-10)">
                <svg viewBox="0 0 24 24" class="ctrl-icon" aria-hidden="true">
                  <path fill="currentColor" d="M12 5V2L7 6.5 12 11V8a5 5 0 1 1-5 5H5a7 7 0 1 0 7-8z" />
                  <!-- 环形箭头内孔为以 (12,13) 为圆心、半径 5 的圆：字号须使 "10" 的外接框
                       落在该圆内（7.2px 粗体两位数约 8px 宽、5px 高，四角距圆心约 4.7 < 5）；
                       8.5px 会使数字两端压到弧线上。 -->
                  <text x="12" y="15.6" text-anchor="middle" font-size="7.2" font-weight="700" fill="currentColor">
                    10
                  </text>
                </svg>
              </button>
              <button type="button" class="ctrl-btn primary" :title="playing ? '暂停' : '播放'" @click="togglePlay">
                <svg v-if="!playing" viewBox="0 0 24 24" class="ctrl-icon play" aria-hidden="true">
                  <path fill="currentColor" d="M8 5v14l11-7L8 5z" />
                </svg>
                <svg v-else viewBox="0 0 24 24" class="ctrl-icon play" aria-hidden="true">
                  <path fill="currentColor" d="M6 19h4V5H6v14zm8-14v14h4V5h-4z" />
                </svg>
              </button>
              <button type="button" class="ctrl-btn" title="前进 10 秒" aria-label="前进 10 秒" @click="skip(10)">
                <!-- 通过镜像复用同一条弧线路径，避免维护第二份路径；数字不能随之镜像，因此置于组外 -->
                <svg viewBox="0 0 24 24" class="ctrl-icon" aria-hidden="true">
                  <g transform="translate(24 0) scale(-1 1)">
                    <path fill="currentColor" d="M12 5V2L7 6.5 12 11V8a5 5 0 1 1-5 5H5a7 7 0 1 0 7-8z" />
                  </g>
                  <text x="12" y="15.6" text-anchor="middle" font-size="7.2" font-weight="700" fill="currentColor">
                    10
                  </text>
                </svg>
              </button>
            </div>
            <div class="bottom-row">
              <!-- 音量：常态仅显示按钮，悬浮（触屏下常驻）时展开滑条。
                   展开条件为 volumeActive = hover | 拖动 | 键盘聚焦；拖动期间一律不收起，
                   否则指针移出容器时滑条中途消失，音量停在中间值。 -->
              <div
                class="volume-wrap"
                :class="{ open: volumeActive }"
                @mouseenter="volumeHover = true"
                @mouseleave="volumeHover = false"
                @focusin="onVolumeFocusIn"
                @focusout="volumeFocused = false"
              >
                <button
                  type="button"
                  class="ctrl-btn vol-btn"
                  :title="`音量 ${volumePercent}%`"
                  aria-label="音量"
                  :aria-expanded="volumeActive"
                  @click="volumeHover = true"
                >
                  <svg class="vol-icon" viewBox="0 0 24 24" aria-hidden="true">
                    <!-- 图标随音量档位切换：静音 / 小 / 大。三条 d 为同一喇叭主体加不同数量的声波弧 -->
                    <path v-if="volume <= 0" fill="currentColor" :d="VOL_ICON_MUTE" />
                    <path v-else-if="volume < 0.5" fill="currentColor" :d="VOL_ICON_LOW" />
                    <path v-else fill="currentColor" :d="VOL_ICON_HIGH" />
                  </svg>
                </button>
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
                  <span class="vol-pct">{{ volumePercent }}%</span>
                </div>
              </div>
              <!-- 收起歌词后左列独占宽度，封面随之放大；矮屏下亦可用于释放纵向空间 -->
              <button
                type="button"
                class="lyrics-toggle"
                :class="{ on: lyricsOpen }"
                :aria-pressed="lyricsOpen"
                :title="lyricsOpen ? '收起歌词' : '展开歌词'"
                @click="lyricsOpen = !lyricsOpen"
              >
                <svg viewBox="0 0 24 24" aria-hidden="true">
                  <path fill="currentColor" d="M4 5h16v2H4V5zm0 4h10v2H4V9zm0 4h16v2H4v-2zm0 4h10v2H4v-2z" />
                </svg>
                <span>歌词</span>
              </button>
            </div>
          </div>

          <div
            v-if="lyricsOpen"
            ref="lyricsPanelEl"
            class="lyrics-panel"
            :class="{ scrolling: lyricsScrolling }"
            @scroll.passive="onLyricsScroll"
          >
            <template v-if="lyrics.length">
              <!-- 上下半高垫片：使首尾行也能滚动到面板中央（高度基于面板高度，非宽度百分比）。
                   纯文本歌词不存在「当前行居中」，垫片只会把首行推到面板中部，因此不渲染。 -->
              <div v-if="lyricsSynced" class="lyrics-pad" aria-hidden="true" />
              <div
                v-for="(line, i) in lyrics"
                :key="i"
                :ref="(el) => setLyricLineRef(el, i)"
                class="lyrics-line"
                :class="{ current: i === currentLyricIndex, plain: !lyricsSynced }"
                @click="lyricsSynced && seekTo(line[0] / 1000)"
              >
                {{ line[1] }}
              </div>
              <div v-if="lyricsSynced" class="lyrics-pad" aria-hidden="true" />
            </template>
            <div v-else class="lyrics-empty">暂无歌词</div>
          </div>
        </div>
        <audio
          ref="audioEl"
          :src="url"
          preload="metadata"
          @timeupdate="syncAudioTime"
          @durationchange="syncAudioTime"
          @play="playing = true"
          @pause="playing = false"
          @ended="playing = false"
          @error="onMediaError"
        />
      </div>

      <div v-else-if="!loading" class="preview-placeholder">无可预览内容</div>
    </n-spin>
  </n-modal>
</template>

<script setup lang="ts">
/**
 * 媒体预览。
 *
 * 视频：统一使用 `VideoPlayer`（格式分档 / customType / 字幕 / 错误兜底均在其中），
 *       本组件不自绘 `<video>`，也不额外显示文件名。
 * 音频：左列封面 + 标题层次 + 细轨进度 + 主播放键，右列歌词。
 *       进度与音量使用原生 `<input type="range">`，填充色由渐变绘制，不引入第三方 slider。
 * 图片：缩放与平移在手势期间直接修改 `img.style.transform`，不经过 Vue 响应式重渲染，
 *       以避免卡顿。
 * 图标一律使用内联 SVG；全站禁用 emoji。
 */
import { computed, nextTick, onUnmounted, ref, watch } from 'vue';
import VideoPlayer from '@/components/VideoPlayer.vue';
import { useMessage } from 'naive-ui';
import { useAppStore } from '@/stores/app';
import { authHeaders, previewKindOf, type FileEntry, type PreviewKind } from '../../filesShared';
import { LYRIC_TIME_UNSYNCED } from '../../id3Lyrics';
import { probeAudioMeta } from '../../audioMetaProbe';

const props = defineProps<{
  show: boolean;
  file: FileEntry | null;
}>();

const emit = defineEmits<{ (e: 'update:show', v: boolean): void }>();

/**
 * 「下载此文件」：VideoPlayer 在 C 档（浏览器无法解码的容器）中提供的下载入口。
 *
 * 直接使用已有的票据 URL 与 `<a download>`：票据地址位于 `/api` 之外的免鉴权区，
 * 可通过裸 GET 获取；`/api/files/download` 需要签名头，`<a>` 无法携带。
 * 该实现无需新增 emit，也无需将下载逻辑透传回 FilesView。
 */
function downloadCurrent() {
  if (!url.value || !props.file) return;
  const a = document.createElement('a');
  a.href = url.value;
  a.download = props.file.name;
  a.click();
}

const message = useMessage();
const appStore = useAppStore();

const url = ref('');
const kind = ref<PreviewKind>('');
const loading = ref(false);

const audioEl = ref<HTMLAudioElement | null>(null);
const playing = ref(false);
const currentTime = ref(0);
const duration = ref(0);
const volume = ref(0.85);
let autoPlayAudio = false;
const scrubbing = ref(false);
const scrubPos = ref(0);

const imgEl = ref<HTMLImageElement | null>(null);
const imgViewportEl = ref<HTMLElement | null>(null);

/** 手势热路径仅修改这些普通数值，通过 rAF 写入 DOM，结束后再同步到 zoomPct。 */
let zoom = 1;
let panX = 0;
let panY = 0;
const zoomPct = ref(100);

const songTitle = computed(() => {
  if (id3Title.value) return id3Title.value;
  const name = props.file?.name || '';
  return name.includes('.') ? name.replace(/\.[^.]+$/, '') : name;
});
const songArtist = computed(() => id3Artist.value || '未知艺人');
const songAlbum = computed(() => id3Album.value || '');

const id3Title = ref('');
const id3Artist = ref('');
const id3Album = ref('');
const coverUrl = ref('');

/**
 * 剩余时长（秒）。
 *
 * 进度条右端显示「-剩余」而非总时长：总时长在一次播放中为常量，剩余时长才是
 * 播放过程中会被反复查看的信息。
 */
const remainSeconds = computed(() => {
  const d = duration.value;
  if (!Number.isFinite(d) || d <= 0) return 0;
  const t = scrubbing.value ? scrubPos.value : currentTime.value;
  return Math.max(0, d - t);
});

/**
 * 已播比例（0–100）。
 *
 * 进度条与音量条均为 `appearance: none` 的自绘轨道（见样式注释），
 * 原生 `accent-color` 填充随之失效，已播部分必须由 CSS 渐变绘制。
 * 本值与 `volumePercent` 是 `--pct` 的唯一数据源。
 */
const seekPercent = computed(() => {
  const d = duration.value;
  if (!Number.isFinite(d) || d <= 0) return 0;
  const t = scrubbing.value ? scrubPos.value : currentTime.value;
  return Math.min(100, Math.max(0, (t / d) * 100));
});
const volumePercent = computed(() => Math.round(volume.value * 100));

/**
 * 音量条的展开条件。
 *
 * 常态仅显示按钮，hover、键盘聚焦、正在拖动三者任一为真时才展开。
 * 拖动必须单独计入：原生 range 没有 `dragstart` 事件，按住后拖出容器会触发 `mouseleave`；
 * 若仅依据 hover，滑条会在指针仍按下时收起。
 */
const volumeHover = ref(false);

/**
 * 音量图标的三档 path（静音 / 小声 / 大声）。
 *
 * 定义在 script 中而非 template 内：三条 `d` 单行均超过 120 字符，会超出 prettier 的
 * printWidth；且喇叭主体路径三档完全相同，写在 template 中会重复三次。
 */
const VOL_ICON_MUTE =
  'M3 9v6h4l5 5V4L7 9H3zM21.8 9.6 21 8.8 17.6 12.2 14.3 8.8 13.4 9.6 16.8 13 13.4 16.4 14.3 17.2 17.6 13.8 21 17.2 21.8 16.4 18.4 13z';
const VOL_ICON_LOW = 'M3 9v6h4l5 5V4L7 9H3zm13.5 3A4.5 4.5 0 0 0 14 8.1v7.8a4.48 4.48 0 0 0 2.5-2.9z';
const VOL_ICON_HIGH =
  'M3 9v6h4l5 5V4L7 9H3zm13.5 3A4.5 4.5 0 0 0 14 8.1v7.8a4.48 4.48 0 0 0 2.5-2.9zM14 3.2v2.1a7 7 0 0 1 0 13.4v2.1a9 9 0 0 0 0-17.6z';
const volumeFocused = ref(false);
const volumeDragging = ref(false);
const volumeActive = computed(() => volumeHover.value || volumeFocused.value || volumeDragging.value);

/**
 * 仅处理键盘产生的聚焦。
 *
 * 弹窗打开时，浏览器（及 Naive UI 的 modal）会将焦点移至内部第一个可聚焦元素，
 * 音量滑条恰好在较前位置。若响应全部 `focusin` 事件，用户未作任何操作时音量面板
 * 即自行展开，且光标停留在滑条上（方向键会直接修改音量）。
 *
 * `:focus-visible` 是浏览器判断「本次聚焦是否需要视觉反馈」的判据：键盘 Tab 命中，
 * 程序化聚焦与鼠标点击不命中。鼠标场景由 hover 处理，无需再由 focus 兜底。
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
  // 监听挂在 window 上：指针释放可能发生在容器之外，元素上的 pointerup 不会触发
  window.addEventListener('pointerup', endVolumeDrag);
  window.addEventListener('pointercancel', endVolumeDrag);
}

const lyrics = ref<Array<[number, string]>>([]);
/** 纯 UI 开关：收起歌词后左列独占整宽（封面放大），矮屏下亦可释放纵向空间。 */
const lyricsOpen = ref(true);
const lyricsPanelEl = ref<HTMLElement | null>(null);
const lyricsScrolling = ref(false);
const lyricLineEls = new Map<number, HTMLElement>();
let lyricsScrollTimer: number | null = null;

function onLyricsScroll() {
  lyricsScrolling.value = true;
  if (lyricsScrollTimer != null) window.clearTimeout(lyricsScrollTimer);
  lyricsScrollTimer = window.setTimeout(() => {
    lyricsScrolling.value = false;
  }, 600);
}

function setLyricLineRef(el: unknown, i: number) {
  if (el instanceof HTMLElement) lyricLineEls.set(i, el);
  else lyricLineEls.delete(i);
}

/**
 * 歌词是否带时间轴。纯文本歌词（[LYRIC_TIME_UNSYNCED]）不高亮、不自动滚动、不可点击跳转，
 * 仅作为可手动滚动的静态文本，否则会呈现虚假的「当前行」。与 app 端 `LyricsPanel` 口径一致。
 */
const lyricsSynced = computed(() => lyrics.value.some((l) => l[0] !== LYRIC_TIME_UNSYNCED));

const currentLyricIndex = computed(() => {
  if (!lyrics.value.length || !lyricsSynced.value) return -1;

  const t = (scrubbing.value ? scrubPos.value : currentTime.value) * 1000;
  let idx = -1;
  for (let i = 0; i < lyrics.value.length; i++) {
    const line = lyrics.value[i];
    if (line && line[0] <= t) idx = i;
    else break;
  }
  return idx;
});

let lyricsScrollRaf = 0;

/** 以缓动滚动到目标 scrollTop；连续切换时中断上一段动画，避免动画叠加导致掉帧。 */
function animateScrollTo(panel: HTMLElement, to: number, duration = 420) {
  if (lyricsScrollRaf) cancelAnimationFrame(lyricsScrollRaf);
  const from = panel.scrollTop;
  const target = Math.max(0, to);
  if (Math.abs(target - from) < 1) return;
  const start = performance.now();
  const step = (now: number) => {
    const t = Math.min(1, (now - start) / duration);
    const e = 1 - Math.pow(1 - t, 3);
    panel.scrollTop = from + (target - from) * e;
    if (t < 1) {
      lyricsScrollRaf = requestAnimationFrame(step);
    } else {
      lyricsScrollRaf = 0;
    }
  };
  lyricsScrollRaf = requestAnimationFrame(step);
}

/** 将当前行滚动到面板垂直居中位置。使用 offsetTop 计算，避免每帧调用 getBoundingClientRect 触发强制布局。 */
function scrollLyricCenter(i: number) {
  const panel = lyricsPanelEl.value;
  const line = lyricLineEls.get(i);
  if (!panel || !line) return;
  const target = line.offsetTop - panel.clientHeight / 2 + line.offsetHeight / 2;
  animateScrollTo(panel, target);
}

watch(currentLyricIndex, (i) => {
  if (i >= 0) scrollLyricCenter(i);
});

// 歌词首次加载后将当前行居中（需等待布局完成）
watch(lyrics, async () => {
  if (!lyrics.value.length) return;
  await nextTick();
  const i = currentLyricIndex.value;
  if (i >= 0) scrollLyricCenter(i);
});

// 收起后再展开时面板重新挂载（scrollTop 归零），需补充一次居中，
// 否则须等到下一行变化才回到视野
watch(lyricsOpen, async (open) => {
  if (!open) return;
  await nextTick();
  const i = currentLyricIndex.value;
  if (i >= 0) scrollLyricCenter(i);
});

function formatTime(sec: number): string {
  if (!Number.isFinite(sec) || sec < 0) return '0:00';
  const s = Math.floor(sec % 60);
  const m = Math.floor((sec / 60) % 60);
  const h = Math.floor(sec / 3600);
  const mm = h > 0 ? String(m).padStart(2, '0') : String(m);
  const ss = String(s).padStart(2, '0');
  return h > 0 ? `${h}:${mm}:${ss}` : `${mm}:${ss}`;
}

function releaseMedia() {
  playing.value = false;
  currentTime.value = 0;
  duration.value = 0;
  scrubbing.value = false;
  scrubPos.value = 0;
  audioEl.value?.pause();
}

function resetTransformState() {
  zoom = 1;
  panX = 0;
  panY = 0;
  zoomPct.value = 100;
  applyTransform();
}

function applyTransform() {
  const el = imgEl.value;
  if (!el) return;
  el.style.transform = `translate3d(${panX}px, ${panY}px, 0) scale(${zoom})`;
}

/**
 * [url] 当前是否为 `blob:` objectURL。
 *
 * 图片与文本仍采用「整份读取为 blob」的方式，音视频使用票据流式 URL（普通 HTTP URL）。
 * 两者清理方式不同：objectURL 必须调用 `revokeObjectURL`，否则内存不会释放；
 * 对普通 URL 调用它虽然无害，但会掩盖两者的区别。票据无需主动作废：
 * core 侧滑动过期 10 分钟后自动回收。
 */
let urlIsObjectUrl = false;

function release() {
  releaseMedia();
  autoPlayAudio = false;
  lyrics.value = [];
  lyricLineEls.clear();
  id3Title.value = '';
  id3Artist.value = '';
  id3Album.value = '';
  if (coverUrl.value) URL.revokeObjectURL(coverUrl.value);
  coverUrl.value = '';
  if (url.value && urlIsObjectUrl) URL.revokeObjectURL(url.value);
  url.value = '';
  urlIsObjectUrl = false;
  kind.value = '';
  resetTransformState();
}

async function load() {
  const f = props.file;
  if (!f) return;
  const k = previewKindOf(f.name || '');
  if (!k) return;
  release();
  kind.value = k;
  loading.value = true;
  try {
    if (k === 'audio' || k === 'video') {
      // 媒体使用「票据 + 浏览器原生流式」：`<audio src>` / `<video src>` 由渲染引擎发起请求，
      // 无法附加鉴权头；若将整个文件 fetch 为 blob 再传入，会受 100MB 上限限制，
      // 且必须下载完成后才能播放。因此先换取短时票据
      // （POST /api/files/stream-ticket，使用正常头部鉴权），随后 Range 请求与 seek
      // 全部交由浏览器处理，实现边下边播。详见 core 的 MediaTicketStore。
      url.value = await requestStreamUrl(f.path);
      urlIsObjectUrl = false;
    } else {
      const uri = `/api/files/stream?path=${encodeURIComponent(f.path)}`;
      const res = await fetch(`${appStore.baseUrl || ''}${uri}`, { headers: await authHeaders(uri) });
      if (!res.ok) {
        const detail = await res.json().catch(() => null);
        throw new Error(detail?.error || `HTTP ${res.status}`);
      }
      const blob = await res.blob();
      if (!props.show) return;
      url.value = URL.createObjectURL(blob);
      urlIsObjectUrl = true;
    }
    if (!props.show) return;
    await nextTick();
    if (kind.value === 'image') {
      resetTransformState();
      if (imgEl.value) imgEl.value.style.transform = 'translate3d(0px, 0px, 0) scale(1)';
    }
    if (kind.value === 'audio') {
      autoPlayAudio = true;
      if (audioEl.value) audioEl.value.volume = volume.value;
      tryAutoplayAudio();
      void loadAudioMetaAndLyrics(f.path, f.size);
    }
  } catch (e: any) {
    release();
    emit('update:show', false);
    message.error(e?.message ? `预览失败: ${e.message}` : '预览失败');
  } finally {
    loading.value = false;
  }
}

/**
 * 换取可直接用于 `<audio src>` / `<video src>` 的流式 URL。
 *
 * 票据由 core 侧 `POST /api/files/stream-ticket` 签发：可重复使用（否则浏览器的第二个
 * Range 请求会被拒绝，播放中断）、滑动过期 10 分钟、仅授权单个文件。
 */
async function requestStreamUrl(path: string): Promise<string> {
  const uri = '/api/files/stream-ticket';
  const res = await fetch(`${appStore.baseUrl || ''}${uri}`, {
    method: 'POST',
    headers: { ...(await authHeaders(uri, 'POST')), 'Content-Type': 'application/json' },
    body: JSON.stringify({ path }),
  });
  if (!res.ok) {
    const detail = await res.json().catch(() => null);
    throw new Error(detail?.error || `HTTP ${res.status}`);
  }
  const data = await res.json();
  const relative = typeof data?.url === 'string' && data.url ? data.url : '';
  if (!relative) throw new Error('未拿到播放票据');
  return `${appStore.baseUrl || ''}${relative}`;
}

/**
 * 音频元数据、封面与歌词。
 *
 * 字节读取与回退顺序位于 `../../audioMetaProbe`（音乐页复用同一实现，见该处说明），
 * 本函数仅负责将结果写入组件状态，并丢弃过期结果。
 */
async function loadAudioMetaAndLyrics(path: string, fileSize?: number) {
  audioLoadPath = path;
  lyrics.value = [];
  lyricLineEls.clear();
  id3Title.value = '';
  id3Artist.value = '';
  id3Album.value = '';
  if (coverUrl.value) URL.revokeObjectURL(coverUrl.value);
  coverUrl.value = '';
  await probeAudioMeta(path, fileSize, {
    isStale: () => audioLoadStale(path),
    hasCover: () => !!coverUrl.value,
    onTags: (tags) => {
      if (tags.title) id3Title.value = tags.title;
      if (tags.artist) id3Artist.value = tags.artist;
      if (tags.album) id3Album.value = tags.album;
    },
    // 探测器仅负责生成 objectURL；释放在 release() 或下一次加载开始时执行
    onCover: (u) => {
      coverUrl.value = u;
    },
    onLyrics: (lines) => {
      lyrics.value = lines;
    },
  });
}

/** 当前这条音频元数据加载链对应的文件路径，用于识别过期结果（见 [audioLoadStale]）。 */
let audioLoadPath = '';

/**
 * 判断该异步链是否已过期；过期时丢弃结果，不写入状态。
 *
 * 仅判断 `props.show` 与 `kind` 不足：切换到另一首音频时 `kind` 仍为 `'audio'`，
 * 上一首尚未返回的封面 / 歌词请求会把结果写入当前曲目，造成封面与歌词错配。
 * 因此必须同时比较 path。
 */
function audioLoadStale(path: string): boolean {
  return !props.show || kind.value !== 'audio' || audioLoadPath !== path;
}

// ── 播放控制 ──
function togglePlay() {
  const el = audioEl.value;
  if (!el || !url.value) return;
  if (el.paused) void el.play().catch(() => undefined);
  else el.pause();
}

function onVolume(ev: Event) {
  const v = Number((ev.target as HTMLInputElement).value);
  volume.value = v;
  if (audioEl.value) audioEl.value.volume = v;
}

/** 打开预览时尝试自动播放；被浏览器拦截时静默失败，用户仍可手动播放。 */
function tryAutoplayAudio() {
  const el = audioEl.value;
  if (!el || !url.value || !autoPlayAudio) return;
  autoPlayAudio = false;
  el.volume = volume.value;
  void el.play().catch(() => undefined);
}

function skip(delta: number) {
  const el = audioEl.value;
  if (!el) return;
  el.currentTime = Math.max(0, Math.min(el.duration || 0, el.currentTime + delta));
  currentTime.value = el.currentTime;
}

function seekTo(sec: number) {
  const el = audioEl.value;
  if (!el) return;
  el.currentTime = sec;
  currentTime.value = sec;
}

function onSeekInput(ev: Event) {
  scrubbing.value = true;
  scrubPos.value = Number((ev.target as HTMLInputElement).value);
}

function commitSeek(ev: Event) {
  const v = Number((ev.target as HTMLInputElement).value);
  scrubPos.value = v;
  if (audioEl.value) audioEl.value.currentTime = v;
  currentTime.value = v;
  scrubbing.value = false;
}

function syncAudioTime() {
  const el = audioEl.value;
  if (!el || scrubbing.value) return;
  currentTime.value = el.currentTime;
  duration.value = el.duration || 0;
}

/**
 * 仅在「确实无法解码且尚未开始播放」时提示。
 * 空 src、关闭时清空 src、Range 探测期间的瞬时 error 都会触发 error 事件；
 * 这些情况必须一律忽略，避免出现「可播放却提示不支持」。
 */
function onMediaError(ev: Event) {
  if (!url.value) return;
  const el = ev.target as HTMLMediaElement | null;
  if (!el?.error) return;
  if (playing.value || el.readyState >= 1) return;
  if (el.error.code !== MediaError.MEDIA_ERR_SRC_NOT_SUPPORTED) return;
  message.warning('当前浏览器可能不支持该媒体编码，请下载后用本地播放器打开');
}

// ── 图片：热路径直接写入 style，不使用 Vue 的 :style 绑定 ──
function resetZoom() {
  zoom = 1;
  panX = 0;
  panY = 0;
  zoomPct.value = 100;
  applyTransform();
}

function fitZoom() {
  resetZoom();
}

function zoomBy(factor: number) {
  zoom = Math.min(8, Math.max(0.1, zoom * factor));
  zoomPct.value = Math.round(zoom * 100);
  applyTransform();
}

let wheelTimer: number | null = null;
function onWheel(ev: WheelEvent) {
  const factor = ev.deltaY < 0 ? 1.08 : 0.93;
  zoom = Math.min(8, Math.max(0.1, zoom * factor));
  applyTransform();
  if (wheelTimer != null) window.clearTimeout(wheelTimer);
  wheelTimer = window.setTimeout(() => {
    zoomPct.value = Math.round(zoom * 100);
  }, 80);
}

let panning = false;
let lastX = 0;
let lastY = 0;
let panRaf = 0;
let pendingDx = 0;
let pendingDy = 0;

function onPanStart(ev: PointerEvent) {
  if (zoom <= 1) return;
  panning = true;
  lastX = ev.clientX;
  lastY = ev.clientY;
  (ev.target as HTMLElement).setPointerCapture?.(ev.pointerId);
}

function onPanMove(ev: PointerEvent) {
  if (!panning) return;
  pendingDx += ev.clientX - lastX;
  pendingDy += ev.clientY - lastY;
  lastX = ev.clientX;
  lastY = ev.clientY;
  if (!panRaf) {
    panRaf = requestAnimationFrame(() => {
      panRaf = 0;
      panX += pendingDx;
      panY += pendingDy;
      pendingDx = 0;
      pendingDy = 0;
      applyTransform();
    });
  }
}

function onPanEnd() {
  panning = false;
  if (panRaf) {
    cancelAnimationFrame(panRaf);
    panRaf = 0;
  }
}

watch(
  () => props.show,
  (v) => (v ? load() : release()),
  { immediate: true }
);

onUnmounted(() => {
  if (wheelTimer != null) window.clearTimeout(wheelTimer);
  if (lyricsScrollTimer != null) window.clearTimeout(lyricsScrollTimer);
  if (lyricsScrollRaf) cancelAnimationFrame(lyricsScrollRaf);
  if (panRaf) cancelAnimationFrame(panRaf);
  // 拖动音量期间组件被卸载（关闭弹窗）：window 上的监听必须一并移除
  endVolumeDrag();
  release();
});
</script>

<style scoped>
.preview-spin {
  min-height: 180px;
  display: flex;
  flex-direction: column;
}
.preview-stage {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

/* 图片 */
.stage-toolbar {
  display: flex;
  align-items: center;
  gap: 4px;
}
.zoom-label {
  margin-left: auto;
  font-size: 12px;
  color: var(--text-muted);
  font-variant-numeric: tabular-nums;
}
.image-viewport {
  position: relative;
  display: flex;
  align-items: center;
  justify-content: center;
  height: min(62vh, 560px);
  overflow: hidden;
  border: 1px solid var(--border-subtle);
  border-radius: 8px;
  background: var(--surface-elevated);
  cursor: default;
  touch-action: none;
  /* 减少合成层抖动 */
  contain: strict;
}
.preview-image {
  max-width: 100%;
  max-height: 100%;
  object-fit: contain;
  transform-origin: center center;
  user-select: none;
  pointer-events: auto;
  /* 手势期间由 JS 写入 transform；初始值用于清除绑定引起的重排 */
  transform: translate3d(0, 0, 0) scale(1);
  will-change: transform;
  backface-visibility: hidden;
}

/* ── 音频版式 ──
   左列为竖向阅读线（封面 → 标题层次 → 进度 → 控制 → 音量），右列仅放置歌词。
   控制区置于封面正下方而非容器底部：容器高度是弹性的，贴底会使控制区随视口上下漂移，
   跟随封面可保持播放器整体的视觉连贯性。 */
.audio-stage {
  padding: 0;
  min-height: 0;
}
/**
 * 高度策略。
 *
 * 不得同时设置 `height`、`min-height: 340px` 与 `max-height: min(64vh, 460px)`：
 * 视口高度不足约 530px 时 min/max 相互矛盾，而左列居中且不裁剪，封面上下缘会溢出容器。
 *
 * 当前仅保留一个确定高度：确定高度向下传递给左列，左列中封面是唯一可收缩项，
 * 空间不足时优先压缩封面而非溢出；歌词面板始终是唯一的滚动容器，
 * 因此不会出现外层与内层两条滚动条。
 */
.audio-body {
  display: grid;
  grid-template-columns: minmax(0, 1.05fr) minmax(0, 0.95fr);
  gap: var(--space-5);
  height: min(68vh, 520px);
  align-items: stretch;
}
/* 收起歌词：左列独占整宽，封面随之放大 */
.audio-body.lyrics-off {
  grid-template-columns: minmax(0, 1fr);
}
.audio-left {
  /* 封面基准尺寸集中在该变量中；媒体查询只修改它，无需重写盒模型 */
  --cover-size: min(272px, 32vh);
  /* 封面圆角大于常规卡片一级，但仍由设计令牌推导 */
  --cover-radius: calc(var(--radius-lg) + 4px);
  display: flex;
  flex-direction: column;
  align-items: center;
  /* 使用 auto margin 实现安全居中，而不使用 justify-content: center：
     空间富余时两端 auto 均分，形成视觉居中；空间不足时 auto 归零，
     内容自顶部排列，不会像居中那样向上下两个方向同时溢出。 */
  justify-content: flex-start;
  gap: var(--space-4);
  min-width: 0;
  min-height: 0;
  height: 100%;
  padding: var(--space-2) var(--space-2) 0;
  /* 极端矮视口下的兜底裁剪（封面已压缩至下限仍无法容纳），避免内容溢出弹窗 */
  overflow: hidden;
}
.audio-body.lyrics-off .audio-left {
  --cover-size: min(340px, 42vh);
}
/**
 * 封面。
 *
 * 仅约束高度，宽度由图片比例决定，因此横版与竖版封面均按原始比例完整显示。
 * 不得改用固定 168×168 配合 `object-fit: cover`：非方形封面会被裁切。
 * 该元素是左列中唯一 `flex-shrink` 不为 0 的元素，承担矮屏下的高度压缩。
 */
.cover-box {
  display: flex;
  align-items: center;
  justify-content: center;
  flex: 0 1 auto;
  height: var(--cover-size);
  min-height: 72px;
  max-width: 100%;
  min-width: 0;
  margin-top: auto;
}
/* 仅无封面时显示方形占位底板。有封面时容器不绘制底色与圆角，
   否则 contain 产生的留白会露出底板颜色，表现为图片被遮罩覆盖。 */
.cover-box.is-empty {
  aspect-ratio: 1 / 1;
  background: var(--surface-hover);
  border-radius: var(--cover-radius);
  color: var(--accent-color);
}
.cover-icon {
  width: 36%;
  height: 36%;
}
.cover-img {
  height: 100%;
  width: auto;
  max-width: 100%;
  object-fit: contain;
  border-radius: var(--cover-radius);
  display: block;
  /* 阴影表达「浮起」的层级语义，而非配色：令牌仅提供颜色（含暗色档），几何参数保留在此处 */
  box-shadow: 0 14px 34px var(--shadow-color-strong);
}
.song-meta {
  width: 100%;
  max-width: 360px;
  min-width: 0;
  text-align: center;
  flex: 0 0 auto;
}
/* 三级文字层次：标题最重，艺人使用主色（作为可点击强调色），专辑最轻。
   层次由字号、字重、颜色三者共同区分；仅调整字号在中文排版中区分度不足。 */
.song-title {
  font-size: 25px;
  font-weight: 700;
  letter-spacing: -0.3px;
  line-height: 1.24;
  color: var(--text-primary);
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}
.song-artist {
  margin-top: 8px;
  font-size: var(--font-lg);
  font-weight: 500;
  color: var(--accent-color);
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}
.song-album {
  margin-top: 4px;
  font-size: var(--font-sm);
  color: var(--text-muted);
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}
/* 音量与歌词开关同排：二者均为次要设置，各占一行会削弱控制区的视觉重心。
   采用两端对齐而非居中：音量展开时向左列中部扩展，右侧歌词按钮位置不变。 */
.bottom-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-3);
  width: 100%;
  max-width: 360px;
  flex: 0 0 auto;
  margin-bottom: auto;
  padding-bottom: var(--space-2);
}
/**
 * 音量：常态仅显示按钮，滑条在悬浮时展开。
 *
 * 展开使用 width 与 opacity 过渡，不使用 `display: none`（该属性无过渡）。
 * 收起态须同时设置 `pointer-events: none`：宽度为 0 但仍在文档流中的滑条不应接收指针事件。
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
  gap: var(--space-2);
  width: 0;
  opacity: 0;
  overflow: hidden;
  pointer-events: none;
  transition:
    width 0.18s ease,
    opacity 0.18s ease;
}
.volume-wrap.open .volume-slot {
  width: 136px;
  opacity: 1;
  pointer-events: auto;
}
/* 触屏设备没有 hover，展开态不会触发，因此滑条常驻显示；否则无法调节音量 */
@media (hover: none) {
  .volume-slot {
    width: 136px;
    opacity: 1;
    pointer-events: auto;
  }
}
/* 音量键沿用 .ctrl-btn 的圆形外观，尺寸降低一档：其次要控件，不应与 ±10s 按钮争夺
   注意力。选择器写作 .ctrl-btn.vol-btn 以覆盖矮屏媒体查询中的 .ctrl-btn 尺寸
   （同特异度，后写者优先）。 */
.ctrl-btn.vol-btn {
  width: 34px;
  height: 34px;
  color: var(--text-muted);
}
.vol-icon {
  width: 18px;
  height: 18px;
  flex-shrink: 0;
}
.volume {
  flex: 1 1 auto;
}
.vol-pct {
  width: 34px;
  flex: 0 0 auto;
  text-align: right;
  font-size: var(--font-xs);
  color: var(--text-muted);
  font-variant-numeric: tabular-nums;
}
.lyrics-toggle {
  display: inline-flex;
  align-items: center;
  gap: var(--space-1);
  flex: 0 0 auto;
  padding: 3px 10px;
  font-size: var(--font-xs);
  color: var(--text-muted);
  background: transparent;
  border: 1px solid var(--border-subtle);
  border-radius: var(--radius-pill);
  cursor: pointer;
}
.lyrics-toggle:hover {
  color: var(--text-secondary);
  background: var(--surface-hover);
}
.lyrics-toggle.on {
  color: var(--accent-color);
  border-color: var(--accent-color);
  background: var(--accent-color-light);
}
.lyrics-toggle svg {
  width: 13px;
  height: 13px;
}
.lyrics-panel {
  position: relative; /* line.offsetTop 相对面板计算，居中时无需 getBoundingClientRect */
  display: flex;
  flex-direction: column;
  min-width: 0;
  height: 100%;
  min-height: 0;
  overflow-y: auto;
  overflow-x: hidden;
  padding: 0 6px;
  overscroll-behavior: contain;
  /* 滚动期间移除以减少每帧重绘 */
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
  height: 6px;
}
.lyrics-panel::-webkit-scrollbar-track {
  background: transparent;
}
.lyrics-panel::-webkit-scrollbar-thumb {
  background: var(--text-muted);
  border-radius: 999px;
  border: 1px solid transparent;
  background-clip: content-box;
}
.lyrics-panel:not(:hover):not(.scrolling)::-webkit-scrollbar {
  width: 0;
  height: 0;
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
  align-items: center;
  justify-content: center;
  color: var(--text-muted);
  font-size: 14px;
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
  /* 仅改变 opacity 与 color：使用 scale 会为每行创建合成层，切换歌曲时易掉帧 */
  opacity: 0.45;
  transition:
    opacity 0.22s ease,
    color 0.22s ease,
    font-size 0.22s ease;
  /* 降低滚动时的文字重排开销 */
  contain: layout style;
  will-change: opacity;
}
.lyrics-line:hover {
  color: var(--text-secondary);
  opacity: 0.8;
  background: transparent;
}
/* 当前行使用 --text-primary 而非主色：该列中「当前」已由字号、字重与不透明度区分，
   主色保留给左列的艺人名与播放键，避免同一屏出现多处主色。 */
.lyrics-line.current {
  color: var(--text-primary);
  font-size: 21px;
  font-weight: 700;
  opacity: 1;
  background: transparent;
  will-change: auto;
}
/* 无时间轴的纯文本歌词：不存在「当前行」，所有行等亮度显示，且不显示点击手型
   （点击无跳转目标）。字号沿用未激活行，避免整段 21px 导致面板内容过长。 */
.lyrics-line.plain {
  cursor: default;
  opacity: 0.85;
  color: var(--text-secondary);
}
.lyrics-line.plain:hover {
  opacity: 0.85;
  color: var(--text-secondary);
}
/* 进度：细轨 + 两端时间 */
.seek-row {
  display: grid;
  grid-template-columns: 38px minmax(0, 1fr) 38px;
  align-items: center;
  gap: var(--space-2);
  width: 100%;
  max-width: 360px;
  flex: 0 0 auto;
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
 * 进度与音量滑条：`appearance: none` 后 track 与 thumb 全部自绘。
 *
 * 不得使用 `transform: scaleY()` 加粗轨道。在 WebKit/Blink 中，thumb
 * （`::-webkit-slider-thumb`）是 `::-webkit-slider-runnable-track` 的子节点，
 * 父级 transform 会一并作用于它：轨道纵向放大 1.9 倍时，圆点被同比拉伸为竖椭圆，
 * 即悬浮态圆点变形的原因。Firefox 的 `::-moz-range-track` / `::-moz-range-thumb` 同理。
 * 因此约定三条：
 *   1. 轨道加粗只能修改 track 自身的 height；
 *   2. thumb 放大只能修改 thumb 自身的 width / height；
 *   3. 这两个伪元素中不得出现 transform。
 *
 * 此外 `appearance: none` 会使原生 `accent-color` 填充失效，已播部分必须由
 * `linear-gradient` 绘制，比例来自 template 上的 `--pct`（seekPercent / volumePercent）。
 */
.seek,
.volume {
  --track-h: 4px;
  --thumb-size: 10px;
  /* 轨道底色使用令牌：--border-subtle 在浅色与深色两态下均清晰，不硬编码颜色值 */
  --track-bg: var(--border-subtle);
  appearance: none;
  -webkit-appearance: none;
  width: 100%;
  min-width: 0;
  /* 元素高度仅决定命中区域（细轨难以点中）；轨道视觉厚度由 --track-h 决定 */
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
  /* WebKit 不会自动居中 thumb：通过负 margin 对齐轨道中线 */
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
/* Firefox 会自动将 thumb 对齐轨道中线，无需 margin 修正 */
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
/* 控制区：中央播放键最大，两侧 ±10s 按钮次之；尺寸差是唯一的层次手段（均无边框）。
   整体尺寸提高一档（40→48 / 60→64）：±10s 图标内嵌 "10" 两位数字，
   40px 按钮下环形箭头与数字会相互挤压。 */
.ctrl-row {
  display: flex;
  justify-content: center;
  align-items: center;
  gap: var(--space-5);
  flex: 0 0 auto;
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
  width: 48px;
  height: 48px;
  padding: 0;
  border-radius: 50%;
  transition:
    background 0.15s ease,
    color 0.15s ease;
}
.ctrl-btn:hover {
  background: var(--surface-hover);
  color: var(--text-primary);
}
/* 主按钮使用「主色淡底 + 主色图标」而非「主色实底 + 白字」：淡底在明暗两态下均不突显，
   实底会使该按钮在深色播放面板中过亮。虽已提供 --on-accent 令牌，此处为设计取舍。 */
.ctrl-btn.primary {
  color: var(--accent-color);
  width: 64px;
  height: 64px;
  background: var(--accent-color-light);
}
.ctrl-btn.primary:hover {
  background: var(--accent-color-light);
  box-shadow: 0 0 0 4px var(--accent-color-light);
}
.ctrl-icon {
  width: 28px;
  height: 28px;
  color: currentColor;
}
.ctrl-icon.play {
  width: 32px;
  height: 32px;
}

.preview-placeholder {
  padding: 48px 0;
  text-align: center;
  color: var(--text-muted);
  font-size: 13px;
}

/* 矮屏：将左列中固定高度的块整体缩小一档。封面可自行收缩，但若标题、控制、音量维持
   原尺寸，收缩额度会被它们占用，封面降至 72px 下限仍然紧凑；因此主动降低一档，
   为封面保留比例。
   断点使用全站统一的 720 高度档（允许的断点仅为 768 / 1024 / 720 高度三档）。
   这些声明在统一的 720 块中没有等价项，不得删除；断点提升至 720 后，
   560–720 区间内播放器仅缩小一档，不会溢出（左列 overflow: hidden 兜底）。 */
@media (max-height: 720px) {
  .audio-body {
    gap: var(--space-3);
  }
  .audio-left {
    --cover-size: min(150px, 24vh);
    gap: var(--space-2);
  }
  .audio-body.lyrics-off .audio-left {
    --cover-size: min(200px, 32vh);
  }
  .song-title {
    font-size: var(--font-lg);
  }
  .song-artist {
    margin-top: 2px;
    font-size: var(--font-base);
  }
  .song-album {
    margin-top: 2px;
  }
  /* 控制键同步缩小一档，但仍保留使 ±10s 图标中 "10" 清晰可辨的余量（40 / 24 为下限） */
  .ctrl-row {
    gap: var(--space-4);
  }
  .ctrl-btn {
    width: 40px;
    height: 40px;
  }
  .ctrl-btn.primary {
    width: 52px;
    height: 52px;
  }
  .ctrl-btn.vol-btn {
    width: 30px;
    height: 30px;
  }
  .ctrl-icon {
    width: 24px;
    height: 24px;
  }
  .ctrl-icon.play {
    width: 26px;
    height: 26px;
  }
  .vol-icon {
    width: 16px;
    height: 16px;
  }
  .lyrics-line {
    padding: 6px 10px;
    font-size: var(--font-md);
  }
  .lyrics-line.current {
    font-size: var(--font-lg);
  }
}

/* 窄屏（断点 768）：单列布局。歌词行使用 minmax(0, 1fr) 而非固定最小高度：
   容器高度确定，行高之和超过容器即溢出，因此由歌词占用剩余空间；
   面板自身可滚动，行数增加不会把播放器挤出视口。 */
@media (max-width: 768px) {
  .audio-body {
    grid-template-columns: minmax(0, 1fr);
    grid-template-rows: auto minmax(0, 1fr);
    height: min(74vh, 560px);
    gap: var(--space-3);
  }
  .audio-body.lyrics-off {
    grid-template-rows: minmax(0, 1fr);
  }
  .audio-left {
    --cover-size: min(170px, 26vh);
    height: auto;
    gap: var(--space-2);
    padding: 0;
  }
  .audio-body.lyrics-off .audio-left {
    --cover-size: min(240px, 34vh);
    height: 100%;
  }
  /* 单列下左列高度由内容自适应，auto margin 无可分配空间，因此在此覆盖为 0 */
  .cover-box,
  .bottom-row {
    margin-top: 0;
    margin-bottom: 0;
  }
  .song-title {
    font-size: var(--font-xl);
  }
  .lyrics-panel {
    padding-top: var(--space-2);
    border-top: 1px solid var(--border-subtle);
  }
}
</style>

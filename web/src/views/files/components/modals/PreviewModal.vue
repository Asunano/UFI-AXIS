<template>
  <n-modal
    :show="show"
    preset="card"
    :title="file?.name || '预览'"
    class="preview-modal"
    style="width: min(960px, 94vw)"
    :content-style="{ maxHeight: '86vh', display: 'flex', flexDirection: 'column' }"
    @update:show="emit('update:show', $event)"
  >
    <n-spin :show="loading" class="preview-spin">
      <!-- ── 图片：可缩放 / 拖拽（变换直接写 DOM，避免 Vue 每帧重排） ── -->
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

      <!-- ── 视频：只用原生 controls（进度 / 音量 / 全屏 / 播放齐全），底部不再叠重复按钮 ── -->
      <div v-else-if="kind === 'video'" class="preview-stage video-stage">
        <video
          ref="videoEl"
          class="preview-video"
          controls
          playsinline
          preload="metadata"
          :src="url"
          @error="onMediaError"
        />
        <div class="video-meta">
          <span>{{ file?.name }}</span>
        </div>
      </div>

      <!-- ── 音频：Apple Music 式「左封面/信息/控制 + 右歌词」 ──
           左列自上而下是一条阅读线：大封面 → 标题层次 → 进度 → 控制 → 音量，
           视觉重心压在封面与播放键上；歌词单独占右列，滚动只发生在它内部。 -->
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
            <!-- 已播 / 剩余分列两端：剩余带负号，和 Apple Music 一致，读数比总时长更有用 -->
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
                  <!-- 环形箭头的内孔是以 (12,13) 为心、半径 5 的圆：字号必须让 "10" 的外接框
                       落在这个圆内（7.2px 粗体两位数约 8px 宽 / 5px 高，四角距圆心 ≈4.7 < 5），
                       原来的 8.5 会让数字两端压到弧线上。 -->
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
                <!-- 同一条弧线镜像即可，避免再维护一份镜像路径；数字不能跟着翻，所以放在组外 -->
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
                    />
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
              <!-- 收起歌词后左列独占宽度、封面随之变大；矮屏也多一条"腾高度"的退路 -->
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
              <!-- 上下半高垫片：让首尾行也能滚到面板中央（高度按面板，不是按宽度%）。
                   纯文本歌词没有"居中当前行"这回事，垫片只会把首行推到面板中间，故不渲染。 -->
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
 * 视频：只用原生 <video controls>（自带播放/进度/音量/全屏），底部不再叠重复按钮。
 * 音频：Apple Music 式版式 —— 左列大封面 + 标题层次 + 细轨进度 + 大播放键，右列歌词。
 *       进度/音量坚持用原生 <input type="range">（填充色靠 accent-color），不引第三方 slider。
 * 图片：缩放/平移在手势期间直接改 img.style.transform（不走 Vue 响应式重渲染），避免卡顿。
 * 图标一律用 inline SVG，全站禁止 emoji。
 */
import { computed, nextTick, onUnmounted, ref, watch } from 'vue';
import { useMessage } from 'naive-ui';
import { useAppStore } from '@/stores/app';
import { authHeaders, previewKindOf, type FileEntry, type PreviewKind } from '../../filesShared';
import {
  LYRIC_TIME_UNSYNCED,
  decodeTextBytes,
  extractAudioArtwork,
  extractAudioLyrics,
  extractAudioTags,
  flacArtworkRequiredBytes,
  id3TagBodySize,
  parseLyrics,
} from '../../id3Lyrics';

const props = defineProps<{
  show: boolean;
  file: FileEntry | null;
}>();

const emit = defineEmits<{ (e: 'update:show', v: boolean): void }>();

const message = useMessage();
const appStore = useAppStore();

const url = ref('');
const kind = ref<PreviewKind>('');
const loading = ref(false);

const videoEl = ref<HTMLVideoElement | null>(null);
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

/** 手势热路径只改这些普通数字，用 rAF 写 DOM；结束后再同步到 zoomPct */
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
 * 进度条右端显示 `-剩余` 而不是总时长：总时长在同一屏里是常量、看一次就够，
 * 剩余量才是播放中真正会被反复扫视的信息（与 Apple Music 一致）。
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
 * 进度/音量条都改成了 `appearance: none` 自绘轨道（见 style 里的说明），
 * 原生的 `accent-color` 填充随之失效，已播那一段必须由 CSS 自己用渐变画 ——
 * 这两个值就是喂给 `--pct` 的唯一数据源。
 */
const seekPercent = computed(() => {
  const d = duration.value;
  if (!Number.isFinite(d) || d <= 0) return 0;
  const t = scrubbing.value ? scrubPos.value : currentTime.value;
  return Math.min(100, Math.max(0, (t / d) * 100));
});
const volumePercent = computed(() => Math.round(volume.value * 100));

/**
 * 音量条的展开态。
 *
 * 平时只露一个按钮，hover / 键盘聚焦 / **正在拖动** 三者任一为真才展开。
 * 拖动必须单独算一条：原生 range 没有 dragstart，鼠标按住后拖出容器会触发 mouseleave，
 * 若只看 hover，滑条会在手指还按着的时候收起来。
 */
const volumeHover = ref(false);

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
const volumeFocused = ref(false);
const volumeDragging = ref(false);
const volumeActive = computed(() => volumeHover.value || volumeFocused.value || volumeDragging.value);

/**
 * 只认**键盘**带来的聚焦。
 *
 * 弹窗打开时浏览器（以及 Naive UI 的 modal）会把焦点丢给内部第一个可聚焦元素，
 * 而音量滑条正好排在前面 —— 照单全收 `focusin` 的话，用户什么都没做，
 * 音量面板就自己展开了、光标也停在滑条上（按方向键会直接改音量）。
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

const lyrics = ref<Array<[number, string]>>([]);
/** 纯 UI 开关：收起歌词后左列独占整宽（封面变大），矮屏也能靠它腾出竖向空间 */
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
 * 只当作可手动滚动的静态文本 —— 否则会假装"当前行"骗人。与 app 端 `LyricsPanel` 同一口径。
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

/** 缓动滚到目标 scrollTop；连续切换时打断上一段，避免动画叠罗汉掉帧。 */
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

/** 当前行滚到面板垂直居中（用 offsetTop，避免每帧 getBoundingClientRect 强制布局）。 */
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

// 歌词首次加载后也把当前行居中（布局完成后）
watch(lyrics, async () => {
  if (!lyrics.value.length) return;
  await nextTick();
  const i = currentLyricIndex.value;
  if (i >= 0) scrollLyricCenter(i);
});

// 收起再展开后面板是重新挂载的（scrollTop 归零），要补一次居中，否则要等下一行才回到视野
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
  videoEl.value?.pause();
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
 * [url] 当前是不是 `blob:` objectURL。
 *
 * 图片/文本仍走「整份读成 blob」，音视频改成了票据流式 URL（普通 http URL）。
 * 两者的清理方式不同：objectURL 必须 `revokeObjectURL` 否则内存不释放，
 * 而对普通 URL 调它虽然无害，却会掩盖"这里到底该不该释放"这个区别。
 * 票据本身不需要主动作废 —— core 侧滑动过期 10 分钟自动回收。
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
      // 媒体走「票据 + 浏览器原生流式」：`<audio src>` / `<video src>` 由渲染引擎发起请求，
      // **无法附加鉴权头**，所以此前只能 fetch 整个文件成 blob 再喂进去 ——
      // 代价是 100MB 上限、必须下载完才能播、电影根本进不了预览。
      // 现在换一张短时票据（POST /api/files/stream-ticket，走正常头部鉴权），
      // 之后 Range 与 seek 全交给浏览器，边下边播。详见 core 的 MediaTicketStore。
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
 * 换取可直接塞进 `<audio src>` / `<video src>` 的流式 URL。
 *
 * 票据由 core 侧 `POST /api/files/stream-ticket` 签发：可重复使用（否则浏览器的第二个
 * Range 请求就会被拒、播放当场断）、滑动过期 10 分钟、只授权这一个文件。
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
 * 音频元数据 + 封面 + 歌词。
 *
 * 三条链路的回退顺序（与 app 端 `FilePreviewOverlay` 对齐）：
 * - 标题/艺人/专辑：内嵌标签（ID3 或 FLAC VORBIS_COMMENT，按魔数分流）→ 文件名兜底（UI 层）
 * - 封面：内嵌图（ID3 APIC / FLAC PICTURE）→ 同目录 sidecar 图 → 占位符
 * - 歌词：同目录 `.lrc` → 内嵌（ID3 USLT/SYLT 或 FLAC LYRICS）→「暂无歌词」
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
  await loadAudioId3(path, fileSize);
  // 内嵌封面没有才去同目录找：大量 FLAC / 整轨都是外挂封面
  await trySidecarCover(path);
  if (await trySiblingLrc(path)) return;
  await tryEmbeddedLyrics(path, fileSize);
}

/** 当前这条音频元数据加载链对应的文件路径，用于识别过期结果（见 [audioLoadStale]）。 */
let audioLoadPath = '';

/**
 * 这条异步链是否已经过期，过期就丢弃结果、不要写进 state。
 *
 * 只判 `props.show` 和 `kind` 不够：切到**另一首音频**时 `kind` 仍然是 `'audio'`，
 * 上一首在飞的封面 / 歌词请求返回后会把结果盖到当前歌上（封面串台、歌词对不上）。
 * 所以必须再比对 path。
 */
function audioLoadStale(path: string): boolean {
  return !props.show || kind.value !== 'audio' || audioLoadPath !== path;
}

/** 头部探测长度：文本标签几乎总在这段里，够小以免每首歌都白拉几百 KB。 */
const HEAD_PROBE_BYTES = 64 * 1024;

/**
 * 为拿完整封面而追加读取的**硬上限**。
 *
 * 注意这不是「猜要读多少」——需要读多少由 `flacArtworkRequiredBytes`（FLAC）或
 * `10 + tagSize`（ID3）**精确算出**。这个常量只用来否决"大到不值得为预览拉"的情况
 * （无损专辑的内嵌封面能到好几 MB）。超过就干脆不取，交给 sidecar 图或占位符 ——
 * **取一半比不取更糟**：截断的 JPEG 会渲染成上半张图 + 下半露底色。
 */
const COVER_FETCH_HARD_LIMIT_BYTES = 4 * 1024 * 1024;

/**
 * 补取内嵌歌词时的读取上限。
 *
 * 歌词是文本，即便截断也只是少几行、不会产生"半张图"那种视觉错觉，
 * 所以这里保留固定上限即可。
 */
const LYRICS_PROBE_MAX_BYTES = 1024 * 1024;

async function trySiblingLrc(path: string): Promise<boolean> {
  if (!path.includes('.')) return false;
  const lrcPath = path.replace(/\.[^.]+$/, '') + '.lrc';
  if (lrcPath === path) return false;
  const uri = `/api/files/stream?path=${encodeURIComponent(lrcPath)}`;
  try {
    const res = await fetch(`${appStore.baseUrl || ''}${uri}`, { headers: await authHeaders(uri) });
    if (!res.ok) return false;
    // 取字节而非 res.text()：后者按 Content-Type 的 charset（octet-stream → UTF-8）硬解，
    // GBK 编码的 .lrc 会整篇变乱码。与 app 端 decodeTextBytes 同一口径。
    const text = decodeTextBytes(new Uint8Array(await res.arrayBuffer()));
    if (audioLoadStale(path)) return false;
    const parsed = parseLyrics(text);
    if (parsed.length) {
      lyrics.value = parsed;
      return true;
    }
    return false;
  } catch {
    return false;
  }
}

async function fetchRange(path: string, start: number, end: number): Promise<Uint8Array | null> {
  const uri = `/api/files/stream?path=${encodeURIComponent(path)}`;
  try {
    const res = await fetch(`${appStore.baseUrl || ''}${uri}`, {
      headers: { ...(await authHeaders(uri)), Range: `bytes=${start}-${end}` },
    });
    if (!res.ok && res.status !== 206) return null;
    return new Uint8Array(await res.arrayBuffer());
  } catch {
    return null;
  }
}

/**
 * 读音频元数据（标题/艺人/专辑 + 内嵌封面）。
 *
 * 2026-09-14：
 * - 去掉 `.mp3`/`.flac` 扩展名白名单 —— 容器由**魔数**判（`extractAudioTags` 内部分流
 *   ID3 / FLAC），扩展名白名单只会让 `.m4a`/`.ogg` 这类连尝试的机会都没有；
 * - 补取上限对 FLAC 也生效：FLAC 的 PICTURE block 不在 ID3 tag 里，`id3TagBodySize` 恒为 null，
 *   原来那条补取分支对 FLAC 完全不触发 ⇒ 大封面永远读不到。
 */
async function loadAudioId3(path: string, fileSize?: number): Promise<void> {
  const head = await fetchRange(path, 0, HEAD_PROBE_BYTES - 1);
  if (!head || audioLoadStale(path)) return;
  applyId3(head);

  if (coverUrl.value) return;
  // 还没拿到封面：按容器算出"到底还需要读多少字节"，而不是猜一个上限。
  // 猜小了会拿到**被截断的图片数据** —— 浏览器对半截 JPEG 会只渲染上半部分、下半留空，
  // 空白处露出 .cover-box 的 var(--surface-hover) 底色，看起来就是
  // 「封面下半被一层随深浅模式变色的遮罩挡住」（2026-09-14 修复的正是这个）。
  // - FLAC：PICTURE block 头里有精确的数据长度，直接算出绝对终点；
  // - ID3：APIC 在 tag 内，读满 `10 + tagSize` 就一定完整。
  const flacNeed = flacArtworkRequiredBytes(head);
  const tagSize = id3TagBodySize(head);
  const wanted = flacNeed ?? (tagSize != null ? 10 + tagSize : null);
  if (wanted == null || wanted <= head.length) return;
  if (wanted > COVER_FETCH_HARD_LIMIT_BYTES) {
    // 封面大到不值得为预览拉下来（多为无损专辑的超大内嵌图）。
    // 不取胜过取一半 —— 交给 sidecar 图或占位符。
    return;
  }
  const end = Math.min(wanted - 1, (fileSize ?? wanted) - 1);
  if (end < head.length) return;
  const more = await fetchRange(path, 0, end);
  if (more && props.show && kind.value === 'audio') applyId3(more);
}

function applyId3(bytes: Uint8Array) {
  // 统一入口按魔数分流 ID3 / FLAC —— 与 app 端 ExoPlayer 一视同仁给出 MediaMetadata 对齐
  const tags = extractAudioTags(bytes);
  if (tags.title) id3Title.value = tags.title;
  if (tags.artist) id3Artist.value = tags.artist;
  if (tags.album) id3Album.value = tags.album;

  if (!coverUrl.value) {
    const art = extractAudioArtwork(bytes);
    if (art && art.data.length > 0) {
      const blob = new Blob([art.data], { type: art.mime });
      coverUrl.value = URL.createObjectURL(blob);
    }
  }
}

/**
 * 同目录 sidecar 封面探测（2026-09-14 新增，双端同口径）。
 *
 * 无内嵌封面的文件（大量 FLAC、外挂封面的整轨）在同目录会躺着一张图。
 * 候选顺序照常见播放器：同名图优先（`歌名.jpg`），再看整张专辑共用的 `cover`/`folder`/`front`。
 *
 * 实现上先用 `Range: bytes=0-0` 花 1 个字节探存在、命中后才整取 —— 盲目整取会在
 * 每次打开一首没封面的歌时白拉 5 次几百 KB。
 */
async function trySidecarCover(path: string): Promise<void> {
  if (coverUrl.value) return;
  const slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
  const dir = slash >= 0 ? path.slice(0, slash) : '';
  const base = path.replace(/\.[^.\\/]+$/, '');
  const candidates: string[] = [];
  for (const ext of ['jpg', 'jpeg', 'png', 'webp']) candidates.push(`${base}.${ext}`);
  for (const name of ['cover.jpg', 'cover.png', 'folder.jpg', 'front.jpg']) {
    candidates.push(dir ? `${dir}/${name}` : name);
  }
  for (const candidate of candidates) {
    if (audioLoadStale(path) || coverUrl.value) return;
    const probe = await fetchRange(candidate, 0, 0);
    if (!probe || probe.length === 0) continue;
    const uri = `/api/files/stream?path=${encodeURIComponent(candidate)}`;
    try {
      const res = await fetch(`${appStore.baseUrl || ''}${uri}`, { headers: await authHeaders(uri) });
      if (!res.ok) continue;
      const blob = await res.blob();
      if (audioLoadStale(path) || coverUrl.value) return;
      coverUrl.value = URL.createObjectURL(blob);
      return;
    } catch {
      // 单个候选失败就试下一个：这条链路是"锦上添花"，任何失败都不该冒泡成预览失败
    }
  }
}

async function tryEmbeddedLyrics(path: string, fileSize?: number): Promise<boolean> {
  // 容器由魔数判（extractAudioLyrics 内部分流 ID3 USLT/SYLT 与 FLAC LYRICS），不再看扩展名
  const head = await fetchRange(path, 0, HEAD_PROBE_BYTES - 1);
  if (!head) return false;
  if (!props.show || kind.value !== 'audio') return false;
  applyId3(head);

  const fromHead = extractAudioLyrics(head);
  if (fromHead) {
    const parsed = parseLyrics(fromHead);
    if (parsed.length) {
      lyrics.value = parsed;
      return true;
    }
  }

  // 头部没有：ID3 有显式 tag 长度可用，FLAC 没有（VORBIS_COMMENT 是独立 block）→ 用固定上限兜
  const tagSize = id3TagBodySize(head);
  const wanted = tagSize != null ? tagSize : LYRICS_PROBE_MAX_BYTES;
  if (wanted <= HEAD_PROBE_BYTES) return false;
  const end = Math.min(wanted - 1, (fileSize ?? wanted) - 1, LYRICS_PROBE_MAX_BYTES - 1);
  const full = await fetchRange(path, 0, end);
  if (!full || audioLoadStale(path)) return false;
  const fromFull = extractAudioLyrics(full);
  if (!fromFull) return false;
  const parsed = parseLyrics(fromFull);
  if (!parsed.length) return false;
  lyrics.value = parsed;
  return true;
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

/** 打开预览即尝试自动播；浏览器拦截时静默失败，用户仍可点播放。 */
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
 * 只有在「确实无法解码且尚未开始播放」时才提示。
 * 空 src、关闭时清 src、Range 探测中的瞬时 error 都会触发 error 事件；
 * 用户反馈「能播但弹不支持」——这些一律吞掉。
 */
function onMediaError(ev: Event) {
  if (!url.value) return;
  const el = ev.target as HTMLMediaElement | null;
  if (!el?.error) return;
  if (playing.value || el.readyState >= 1) return;
  if (el.error.code !== MediaError.MEDIA_ERR_SRC_NOT_SUPPORTED) return;
  message.warning('当前浏览器可能不支持该媒体编码，请下载后用本地播放器打开');
}

// ── 图片：热路径直接写 style，不用 Vue :style 绑定 ──
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
  // 拖音量时组件被卸载（关弹窗）：window 上那对监听必须跟着走
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
  /* 手势期由 JS 写 transform；初始清掉绑定带来的重排 */
  transform: translate3d(0, 0, 0) scale(1);
  will-change: transform;
  backface-visibility: hidden;
}

/* 视频：只有原生控件 */
.preview-video {
  width: 100%;
  max-height: min(58vh, 640px);
  border-radius: 8px;
  background: #000;
  display: block;
}
.video-meta {
  font-size: 12px;
  color: var(--text-muted);
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}

/* ── 音频：Apple Music 式版式 ──
   左列一条竖向阅读线（封面 → 标题层次 → 进度 → 控制 → 音量），右列只有歌词。
   之所以把控制区放在封面正下方而不是贴容器底部：容器高度是弹性的，贴底会让
   控制区随视口上下漂移，跟着封面走才有"一整块播放器"的整体感。 */
.audio-stage {
  padding: 0;
  min-height: 0;
}
/**
 * 高度策略（2026-09-14 重做）。
 *
 * 原来是 height + min-height:340px + max-height:min(64vh,460px) 三条一起写：
 * 视口高不足约 530px 时 min/max 直接矛盾，左列又是居中且不裁剪，
 * 结果封面的上下缘会溢出到容器外面。
 *
 * 现在只留**一个确定高度**：确定高度会往下传给左列，左列里封面是唯一可收缩项，
 * 空间不够时先压封面而不是溢出；歌词面板始终是全局唯一的滚动容器，
 * 所以不会出现"外层 + 内层"两条滚动条。
 */
.audio-body {
  display: grid;
  grid-template-columns: minmax(0, 1.05fr) minmax(0, 0.95fr);
  gap: var(--space-5);
  height: min(68vh, 520px);
  align-items: stretch;
}
/* 收起歌词：左列独占整宽，封面顺势放大 */
.audio-body.lyrics-off {
  grid-template-columns: minmax(0, 1fr);
}
.audio-left {
  /* 封面基准尺寸集中在这一个变量里，媒体查询只改它，不用重写盒模型 */
  --cover-size: min(272px, 32vh);
  /* 封面圆角比常规卡片再圆一档（Apple Music 是大圆角 + 明显投影），但仍从令牌推导 */
  --cover-radius: calc(var(--radius-lg) + 4px);
  display: flex;
  flex-direction: column;
  align-items: center;
  /* 用 auto margin 做"安全居中"而不是 justify-content:center：
     空间富余时两端 auto 均分 ⇒ 视觉居中；空间不足时 auto 归零 ⇒
     内容从顶部排起，不会像居中那样朝上下两个方向同时溢出。 */
  justify-content: flex-start;
  gap: var(--space-4);
  min-width: 0;
  min-height: 0;
  height: 100%;
  padding: var(--space-2) var(--space-2) 0;
  /* 极端矮视口（封面已压到下限仍放不下）时兜底裁剪，避免内容压到弹窗外面 */
  overflow: hidden;
}
.audio-body.lyrics-off .audio-left {
  --cover-size: min(340px, 42vh);
}
/**
 * 封面。
 *
 * 只给高度基准、宽度交给图片比例，所以横版/竖版封面都按原始比例完整显示 ——
 * 之前是固定 168×168 配 object-fit:cover，非方形封面会被裁掉两头。
 * 同时它是左列里唯一 flex-shrink 不为 0 的元素，承担矮屏的高度让位。
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
/* 只有"没封面"才需要一块方形占位底板。有封面时容器不画底色也不画圆角，
   否则 contain 留出的空档会露出底板颜色，看着像图片被一层遮罩挡住。 */
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
  /* 阴影是"浮起"这个层级语义，不是配色 —— 令牌只给颜色（含暗色档），几何留在这里 */
  box-shadow: 0 14px 34px var(--shadow-color-strong);
}
.song-meta {
  width: 100%;
  max-width: 360px;
  min-width: 0;
  text-align: center;
  flex: 0 0 auto;
}
/* 三级文字层次：标题最重、艺人用主色（Apple Music 里艺人/专辑是可点的强调色）、
   专辑最轻。层次靠字号+字重+颜色三者一起拉开，只改字号在中文里区分度不够。 */
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
/* 音量与歌词开关同排：两个都是"次要设置"，各占一行会把控制区的重心冲淡。
   两端对齐而不是居中：音量展开时只朝中间长，右边的歌词 pill 不会跟着左右跳。 */
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
 * 音量：常态只露一个按钮，滑条悬浮才展开。
 *
 * 展开用 width + opacity 过渡，而不是 display:none —— 后者根本没有过渡这回事。
 * 收起态再补一条 pointer-events:none：0 宽但仍在文档里的滑条不该抢指针。
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
/* 触屏没有 hover，展开态永远不会到来 ⇒ 滑条常驻，否则音量彻底调不了 */
@media (hover: none) {
  .volume-slot {
    width: 136px;
    opacity: 1;
    pointer-events: auto;
  }
}
/* 音量键沿用 .ctrl-btn 的圆形视觉，只降一档尺寸：它是次要控件，不该和 ±10s 抢注意力。
   写成 .ctrl-btn.vol-btn 是为了压过矮屏媒体查询里的 .ctrl-btn 尺寸（同特异度、后写者胜）。 */
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
  position: relative; /* line.offsetTop 相对面板，居中计算不用 getBoundingClientRect */
  display: flex;
  flex-direction: column;
  min-width: 0;
  height: 100%;
  min-height: 0;
  overflow-y: auto;
  overflow-x: hidden;
  padding: 0 6px;
  overscroll-behavior: contain;
  /* 遮罩在滚动时改成不遮，减少每帧重绘 */
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
  /* 只动 opacity/color：scale 会让每行都建合成层，切歌时容易掉帧 */
  opacity: 0.45;
  transition:
    opacity 0.22s ease,
    color 0.22s ease,
    font-size 0.22s ease;
  /* 减少滚动时的文字重排开销 */
  contain: layout style;
  will-change: opacity;
}
.lyrics-line:hover {
  color: var(--text-secondary);
  opacity: 0.8;
  background: transparent;
}
/* 当前行走 --text-primary 而不是主色：这一列里"当前"已经靠字号+字重+不透明度
   区分得很清楚了，主色留给左列的艺人名与播放键，免得同一屏出现三处主色抢注意力。 */
.lyrics-line.current {
  color: var(--text-primary);
  font-size: 21px;
  font-weight: 700;
  opacity: 1;
  background: transparent;
  will-change: auto;
}
/* 无时间轴的纯文本歌词：没有"当前行"概念，所有行等亮度常显、不给点击手型
   （点了也无处可跳）。字号沿用未激活行，避免整段都是 21px 撑爆面板。 */
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
 * 进度 / 音量滑条：`appearance: none` 之后 track 与 thumb 全部自绘。
 *
 * 注意：不要改回「transform: scaleY() 把轨道撑粗」那一版。
 * 在 WebKit/Blink 里 thumb（`::-webkit-slider-thumb`）是 `::-webkit-slider-runnable-track`
 * 的**子节点**，父级的 transform 会连带作用在它身上：轨道竖向放大 1.9 倍，
 * 圆点就被同比拉成竖椭圆 —— 这正是"悬浮时圆点被压扁"的根因；Firefox 的
 * `::-moz-range-track` / `::-moz-range-thumb` 同理。所以定死三条：
 *   1. 轨道加粗只允许改 track 自己的 height；
 *   2. thumb 变大只允许改 thumb 自己的 width/height；
 *   3. 这两个伪元素里都不许出现 transform。
 *
 * 另外 `appearance: none` 会让原生 `accent-color` 填充一起失效，已播的那一段必须自己
 * 用 linear-gradient 画，比例由 template 上的 `--pct`（seekPercent / volumePercent）给。
 */
.seek,
.volume {
  --track-h: 4px;
  --thumb-size: 10px;
  /* 轨道底色走令牌：--border-subtle 是浅/深两态都看得清的中性线色，不硬编码颜色 */
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
/* 控制区：中央播放键最大、两侧 ±10s 次之，尺寸差是唯一的层次手段（都不带边框）。
   整体升一档（40→48 / 60→64）：±10s 的图标里嵌着 "10" 两位数，
   40px 按钮下环形箭头和数字会挤成一团。 */
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
/* 主键用「主色淡底 + 主色图标」而不是「主色实底 + 白字」：淡底在明暗两态下都不抢戏，
   实底会让这一颗按钮在深色播放面板里过亮（现在已有 --on-accent，但这里是取舍不是缺令牌）。 */
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

/* 矮屏（视口高不足约 560px）：把左列那些"固定高度"的块整体压小一档。
   封面能自己收缩，但如果标题/控制/音量维持原尺寸，收缩额度会全被它们吃掉、
   封面直接掉到 72px 下限还是紧绷 —— 所以这里主动降一档，给封面留出比例。 */
@media (max-height: 560px) {
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
  /* 控制键跟着降一档，但仍留出让 ±10s 图标里的 "10" 看得清的余量（40/24 是下限） */
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

/* 窄屏：单列。歌词行用 minmax(0,1fr) 而不是给最小高度 ——
   容器高度是确定的，行高之和只要可能超过它就会溢出，让歌词吃"剩下的"最稳；
   面板自己能滚，所以行数再多也不会把播放器顶出去。 */
@media (max-width: 720px) {
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
  /* 单列下左列高度是内容自适应的，auto margin 没有可分配的空间，写着也无意义 */
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

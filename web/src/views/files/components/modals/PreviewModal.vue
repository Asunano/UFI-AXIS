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

      <!-- ── 音频：左大封面+信息+控制，右歌词 ── -->
      <div v-else-if="kind === 'audio'" class="preview-stage audio-stage">
        <div class="audio-body">
          <div class="audio-left">
            <div class="cover-box">
              <img v-if="coverUrl" class="cover-img" :src="coverUrl" :alt="songTitle" />
              <svg v-else class="cover-icon" viewBox="0 0 24 24" aria-hidden="true">
                <path fill="currentColor" d="M12 3v10.55A4 4 0 1 0 14 17V7h4V3h-6z" />
              </svg>
            </div>
            <div class="song-meta">
              <div class="song-title" :title="songTitle">{{ songTitle }}</div>
              <div class="song-artist">{{ songArtist }}</div>
              <div v-if="songAlbum" class="song-album">{{ songAlbum }}</div>
            </div>
            <input
              class="seek"
              type="range"
              min="0"
              :max="duration || 0"
              step="0.01"
              :value="scrubbing ? scrubPos : currentTime"
              :disabled="!url || !duration"
              @input="onSeekInput"
              @change="commitSeek"
            />
            <div class="time-row">
              <span>{{ formatTime(scrubbing ? scrubPos : currentTime) }}</span>
              <span class="meta-dim">{{ formatTime(duration) }}</span>
            </div>
            <div class="ctrl-row">
              <button type="button" class="ctrl-btn" title="后退 10 秒" @click="skip(-10)">
                <svg viewBox="0 0 24 24" class="ctrl-icon" aria-hidden="true">
                  <path fill="currentColor" d="M11 18V6l-8.5 6 8.5 6zm.5-6l8.5 6V6l-8.5 6z" />
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
              <button type="button" class="ctrl-btn" title="前进 10 秒" @click="skip(10)">
                <svg viewBox="0 0 24 24" class="ctrl-icon" aria-hidden="true">
                  <path fill="currentColor" d="M13 6v12l8.5-6L13 6zM4 18l8.5-6L4 6v12z" />
                </svg>
              </button>
            </div>
            <div class="volume-row">
              <svg class="vol-icon" viewBox="0 0 24 24" aria-hidden="true">
                <path
                  fill="currentColor"
                  d="M3 9v6h4l5 5V4L7 9H3zm13.5 3A4.5 4.5 0 0 0 14 8.1v7.8a4.48 4.48 0 0 0 2.5-2.9zM14 3.2v2.1a7 7 0 0 1 0 13.4v2.1a9 9 0 0 0 0-17.6z"
                />
              </svg>
              <input
                class="volume"
                type="range"
                min="0"
                max="1"
                step="0.01"
                :value="volume"
                title="音量"
                @input="onVolume"
              />
              <span class="vol-pct">{{ Math.round(volume * 100) }}%</span>
            </div>
          </div>

          <div
            ref="lyricsPanelEl"
            class="lyrics-panel"
            :class="{ scrolling: lyricsScrolling }"
            @scroll.passive="onLyricsScroll"
          >
            <template v-if="lyrics.length">
              <!-- 上下半高垫片：让首尾行也能滚到面板中央（高度按面板，不是按宽度%） -->
              <div class="lyrics-pad" aria-hidden="true" />
              <div
                v-for="(line, i) in lyrics"
                :key="i"
                :ref="(el) => setLyricLineRef(el, i)"
                class="lyrics-line"
                :class="{ current: i === currentLyricIndex }"
                @click="seekTo(line[0] / 1000)"
              >
                {{ line[1] }}
              </div>
              <div class="lyrics-pad" aria-hidden="true" />
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
 * 音频：布局对齐 App FilePreviewOverlay —— 上区左封面+歌名 / 右歌词，下区进度+后退|播放|前进。
 * 图片：缩放/平移在手势期间直接改 img.style.transform（不走 Vue 响应式重渲染），避免卡顿。
 * 图标一律用 inline SVG，全站禁止 emoji。
 */
import { computed, nextTick, onUnmounted, ref, watch } from 'vue';
import { useMessage } from 'naive-ui';
import { useAppStore } from '@/stores/app';
import { authHeaders, previewKindOf, type FileEntry, type PreviewKind } from '../../filesShared';
import { extractEmbeddedLyrics, extractId3Artwork, extractId3TextTags, id3TagBodySize, parseLrc } from '../../id3Lyrics';

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

const lyrics = ref<Array<[number, string]>>([]);
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

const currentLyricIndex = computed(() => {
  if (!lyrics.value.length) return -1;
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
  if (url.value) URL.revokeObjectURL(url.value);
  url.value = '';
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
  const uri = `/api/files/stream?path=${encodeURIComponent(f.path)}`;
  try {
    const res = await fetch(`${appStore.baseUrl || ''}${uri}`, { headers: await authHeaders(uri) });
    if (!res.ok) {
      const detail = await res.json().catch(() => null);
      throw new Error(detail?.error || `HTTP ${res.status}`);
    }
    const blob = await res.blob();
    if (!props.show) return;
    url.value = URL.createObjectURL(blob);
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
 * 音频元数据 + 歌词：
 * - 标题/艺人/专辑：ID3 TIT2/TPE1/TALB（对齐 App ExoPlayer MediaMetadata）
 * - 歌词：同目录 .lrc → 内嵌 USLT/SYLT
 * 头部 Range 与 App 一致：64KB 起步，标签更大再补取（封顶 4MB）。
 */
async function loadAudioMetaAndLyrics(path: string, fileSize?: number) {
  lyrics.value = [];
  lyricLineEls.clear();
  id3Title.value = '';
  id3Artist.value = '';
  id3Album.value = '';
  if (coverUrl.value) URL.revokeObjectURL(coverUrl.value);
  coverUrl.value = '';
  await loadAudioId3(path, fileSize);
  if (await trySiblingLrc(path)) return;
  await tryEmbeddedLyrics(path, fileSize);
}

async function trySiblingLrc(path: string): Promise<boolean> {
  if (!path.includes('.')) return false;
  const lrcPath = path.replace(/\.[^.]+$/, '') + '.lrc';
  if (lrcPath === path) return false;
  const uri = `/api/files/stream?path=${encodeURIComponent(lrcPath)}`;
  try {
    const res = await fetch(`${appStore.baseUrl || ''}${uri}`, { headers: await authHeaders(uri) });
    if (!res.ok) return false;
    const text = await res.text();
    if (!props.show || kind.value !== 'audio') return false;
    const parsed = parseLrc(text);
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
 * 读 ID3 元数据（TIT2/TPE1/TALB + APIC 封面）。
 * 与 App 相同：文本帧常在 APIC 前；64KB 可能截断大封面，故标签更大时补取整段标签。
 */
async function loadAudioId3(path: string, fileSize?: number): Promise<void> {
  const lower = path.toLowerCase();
  if (!lower.endsWith('.mp3') && !lower.endsWith('.flac')) return;
  const head = await fetchRange(path, 0, 65535);
  if (!head || !props.show || kind.value !== 'audio') return;
  applyId3(head);

  const tagSize = id3TagBodySize(head);
  if (tagSize == null) return;
  // APIC 常在标签中后部且体积大；64KB 内没有封面且标签更大时，补取（封顶 4MB）
  if (!coverUrl.value && 10 + tagSize > head.length) {
    const end = Math.min(10 + tagSize - 1, (fileSize ?? 4 * 1024 * 1024) - 1, 4 * 1024 * 1024 - 1);
    if (end >= head.length) {
      const more = await fetchRange(path, 0, end);
      if (more && props.show && kind.value === 'audio') applyId3(more);
    }
  }
}

function applyId3(bytes: Uint8Array) {
  const tags = extractId3TextTags(bytes);
  if (tags.title) id3Title.value = tags.title;
  if (tags.artist) id3Artist.value = tags.artist;
  if (tags.album) id3Album.value = tags.album;

  if (!coverUrl.value) {
    const art = extractId3Artwork(bytes);
    if (art && art.data.length > 0) {
      const blob = new Blob([art.data], { type: art.mime });
      if (coverUrl.value) URL.revokeObjectURL(coverUrl.value);
      coverUrl.value = URL.createObjectURL(blob);
    }
  }
}

async function tryEmbeddedLyrics(path: string, fileSize?: number): Promise<boolean> {
  const lower = path.toLowerCase();
  if (!lower.endsWith('.mp3') && !lower.endsWith('.flac')) return false;
  const head = await fetchRange(path, 0, 65535);
  if (!head) return false;
  if (!props.show || kind.value !== 'audio') return false;
  applyId3(head);

  const fromHead = extractEmbeddedLyrics(head);
  if (fromHead) {
    const parsed = parseLrc(fromHead);
    if (parsed.length) {
      lyrics.value = parsed;
      return true;
    }
  }

  const tagSize = id3TagBodySize(head);
  if (tagSize == null || tagSize <= 65536) return false;
  const end = Math.min(tagSize - 1, (fileSize ?? tagSize) - 1, 4 * 1024 * 1024 - 1);
  const full = await fetchRange(path, 0, end);
  if (!full || !props.show || kind.value !== 'audio') return false;
  const fromFull = extractEmbeddedLyrics(full);
  if (!fromFull) return false;
  const parsed = parseLrc(fromFull);
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

/* 音频：左大封面+信息，右歌词 */
.audio-stage {
  padding: 0;
  min-height: 0;
}
/* 左略宽于右，封面/信息更醒目 */
.audio-body {
  display: grid;
  grid-template-columns: minmax(0, 1.08fr) minmax(0, 0.92fr);
  gap: 20px;
  height: min(64vh, 460px);
  min-height: 340px;
  max-height: min(64vh, 460px);
  align-items: stretch;
}
.audio-left {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 12px;
  min-width: 0;
  min-height: 0;
  padding: 12px 8px;
}
.cover-box {
  width: 168px;
  height: 168px;
  border-radius: 16px;
  overflow: hidden;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  background: var(--surface-hover);
  color: var(--accent-color, #4f8cff);
  box-shadow: 0 8px 24px rgb(0 0 0 / 8%);
}
.cover-icon {
  width: 56px;
  height: 56px;
}
.cover-img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  border-radius: inherit;
  display: block;
}
.song-meta {
  width: 100%;
  max-width: 240px;
  min-width: 0;
  text-align: center;
  flex-shrink: 0;
}
.song-title {
  font-size: 18px;
  font-weight: 600;
  line-height: 1.3;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}
.song-artist {
  margin-top: 4px;
  font-size: 14px;
  color: var(--text-secondary, var(--text-muted));
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}
.song-album {
  margin-top: 3px;
  font-size: 12px;
  color: var(--text-muted);
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}
.volume-row {
  display: flex;
  align-items: center;
  gap: 8px;
  width: min(220px, 100%);
  margin-top: 2px;
}
.vol-icon {
  width: 16px;
  height: 16px;
  color: var(--text-muted);
  flex-shrink: 0;
}
.volume {
  flex: 1;
  min-width: 0;
  height: 4px;
  accent-color: var(--accent-color, #4f8cff);
  cursor: pointer;
}
.vol-pct {
  width: 36px;
  text-align: right;
  font-size: 11px;
  color: var(--text-muted);
  font-variant-numeric: tabular-nums;
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
  padding: 9px 12px;
  font-size: 15px;
  color: var(--text-muted);
  border-radius: 8px;
  cursor: pointer;
  line-height: 1.55;
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
  color: var(--text-secondary, inherit);
  opacity: 0.8;
  background: transparent;
}
.lyrics-line.current {
  color: var(--accent-color, #4f8cff);
  font-size: 17px;
  font-weight: 600;
  opacity: 1;
  background: transparent;
  will-change: auto;
}
.seek {
  width: 100%;
  min-width: 0;
  height: 4px;
  margin: 2px 0;
  accent-color: var(--accent-color, #4f8cff);
  cursor: pointer;
}
.time-row {
  width: 100%;
  display: flex;
  justify-content: space-between;
  font-size: 11px;
  font-variant-numeric: tabular-nums;
}
.meta-dim {
  color: var(--text-muted);
}
.ctrl-row {
  display: flex;
  justify-content: center;
  align-items: center;
  gap: 6px;
}
.ctrl-btn {
  appearance: none;
  border: none;
  background: transparent;
  color: var(--text-secondary, var(--text-muted));
  display: inline-flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  width: 32px;
  height: 32px;
  padding: 0;
  border-radius: 50%;
}
.ctrl-btn:hover {
  background: var(--surface-hover);
}
.ctrl-btn.primary {
  color: var(--accent-color, #4f8cff);
  width: 36px;
  height: 36px;
  background: var(--accent-color-light, rgba(79, 140, 255, 0.12));
}
.ctrl-icon {
  width: 18px;
  height: 18px;
  color: currentColor;
}
.ctrl-icon.play {
  width: 22px;
  height: 22px;
}

.preview-placeholder {
  padding: 48px 0;
  text-align: center;
  color: var(--text-muted);
  font-size: 13px;
}

@media (max-width: 720px) {
  .audio-body {
    grid-template-columns: 1fr;
    grid-template-rows: auto minmax(180px, 1fr);
    height: auto;
    max-height: none;
    min-height: 420px;
    gap: 12px;
  }
  .audio-left {
    padding: 4px 0 8px;
    gap: 10px;
  }
  .cover-box {
    width: 140px;
    height: 140px;
  }
  .song-title {
    font-size: 16px;
  }
  .lyrics-panel {
    height: auto;
    min-height: 200px;
    max-height: 240px;
    border-top: 1px solid var(--border-subtle);
  }
}
</style>

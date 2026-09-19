<!--
  音乐页。与 app 的 `MediaAudioScreen` + `MediaAudioPlayerScreen` 对应
  （web 把"列表"与"播放页"并排放在一屏 —— 桌面视口够宽，不必像手机那样跳两层）。

  左：Apple Music 风格播放面板（`components/AudioPlayerPanel.vue`）
  右：播放队列 = 整个音乐库，当前曲目高亮

  ──── 播放链路 ────
  `<audio>` 的 src 走票据（见 `mediaShared.fetchStreamUrl`）。**一个 `<audio>` 实例复用**，
  换曲只换 src：每首新建一个元素会让上一首的缓冲与解码器留在内存里。

  ──── 封面与歌词的取数顺序 ────
  切歌时先把上一首的封面/歌词清掉再取新的，否则在新封面到达之前会短暂显示上一首的图
  （那比显示占位图更容易误读成"切歌没成功"）。
-->
<template>
  <div class="audio-page">
    <div class="page-head">
      <div class="head-text">
        <h2 class="head-title">音乐</h2>
        <span class="head-sub">{{ headSub }}</span>
      </div>
      <div class="head-actions">
        <n-input v-model:value="query" size="small" placeholder="搜索歌名 / 艺人" clearable class="head-search" />
        <n-button size="small" quaternary :loading="loading" @click="reload">刷新</n-button>
      </div>
    </div>

    <MediaEmptyState v-if="showEmpty" :permission-denied="permissionDenied" :failed="failed" kind-label="音频" />

    <n-spin v-else :show="loading && items.length === 0">
      <div class="audio-body">
        <!-- 播放面板。没选曲目时给一个引导，而不是渲染一个空播放器 -->
        <section class="panel-wrap sub-panel">
          <AudioPlayerPanel
            v-if="current"
            :title="trackTitleOf(current)"
            :artist="trackArtistOf(current)"
            :album="current.album"
            :cover-url="coverUrl"
            :playing="playing"
            :current-sec="currentSec"
            :duration-sec="durationSec"
            :volume="volume"
            :has-prev="currentIndex > 0"
            :has-next="currentIndex >= 0 && currentIndex < items.length - 1"
            :lyric-lines="lyricLines"
            @toggle="togglePlay"
            @prev="playPrev"
            @next="playNext"
            @seek="seekTo"
            @volume="setVolume"
          />
          <div v-else class="panel-idle">
            <n-icon :size="56" color="var(--text-muted)"><MusicalNotesOutline /></n-icon>
            <span class="idle-text">从右侧列表选一首开始播放</span>
          </div>
        </section>

        <!-- 队列 -->
        <section class="queue-wrap">
          <div class="queue-head">
            <span class="queue-title">播放队列</span>
            <span class="queue-count">{{ filtered.length }} / {{ total }}</span>
          </div>
          <n-scrollbar class="queue-scroll">
            <div class="queue-list">
              <button
                v-for="(t, i) in filtered"
                :key="t.id"
                type="button"
                class="queue-item"
                :class="{ 'is-current': t.id === current?.id }"
                @click="playTrack(t)"
              >
                <!-- 序号位在当前曲目上换成播放状态图标：Apple Music 就是这个处理 -->
                <span class="q-index">
                  <n-icon v-if="t.id === current?.id" :size="14">
                    <VolumeHighOutline v-if="playing" />
                    <PauseOutline v-else />
                  </n-icon>
                  <template v-else>{{ i + 1 }}</template>
                </span>
                <img v-if="thumbs[t.id]" :src="thumbs[t.id]" alt="" class="q-thumb" />
                <span v-else class="q-thumb q-thumb-fallback">
                  <n-icon :size="14"><MusicalNotesOutline /></n-icon>
                </span>
                <span class="q-text">
                  <span class="q-title">{{ trackTitleOf(t) }}</span>
                  <span class="q-artist">{{ trackArtistOf(t) }}</span>
                </span>
                <span class="q-dur">{{ formatDuration(t.duration_ms) }}</span>
              </button>
            </div>
            <div v-if="hasMore()" class="queue-more">
              <n-button size="tiny" quaternary :loading="loadingMore" @click="loadMore">加载更多</n-button>
            </div>
          </n-scrollbar>
        </section>
      </div>
    </n-spin>

    <!--
      唯一的 <audio> 实例。不加 controls：走带完全由播放面板接管，
      两套控件同时存在会让"暂停"有两个真源。
    -->
    <audio
      ref="audioEl"
      class="audio-el"
      @timeupdate="onTimeUpdate"
      @loadedmetadata="onLoadedMetadata"
      @play="playing = true"
      @pause="playing = false"
      @ended="playNext"
      @error="onAudioError"
    />
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue';
import { useMessage } from 'naive-ui';
import { MusicalNotesOutline, PauseOutline, VolumeHighOutline } from '@vicons/ionicons5';
import {
  createBlobUrlPool,
  fetchCoverUrl,
  fetchLyricsText,
  fetchStreamUrl,
  formatDuration,
  trackArtistOf,
  trackTitleOf,
  type MediaItem,
} from './mediaShared';
import { useMediaLibrary } from './useMediaLibrary';
import { parseLyrics } from '@/views/files/id3Lyrics';
import AudioPlayerPanel from './components/AudioPlayerPanel.vue';
import MediaEmptyState from './components/MediaEmptyState.vue';

const message = useMessage();
const { api, items, total, loading, loadingMore, permissionDenied, failed, thumbs, hasMore, reload, loadMore } =
  useMediaLibrary('audio');

/** 封面用独立的 pool：它随切歌频繁换，和列表缩略图的生命周期不同。 */
const coverPool = createBlobUrlPool();

const query = ref('');
const audioEl = ref<HTMLAudioElement | null>(null);

const current = ref<MediaItem | null>(null);
const coverUrl = ref('');
const lyricLines = ref<Array<[number, string]>>([]);
const playing = ref(false);
const currentSec = ref(0);
const durationSec = ref(0);
const volume = ref(1);

const filtered = computed(() => {
  const q = query.value.trim().toLowerCase();
  if (!q) return items.value;
  return items.value.filter(
    (t) => trackTitleOf(t).toLowerCase().includes(q) || trackArtistOf(t).toLowerCase().includes(q)
  );
});

/** 上一首/下一首在**完整库**里算，不在筛选结果里 —— 筛选只是查找手段，不该改变播放顺序。 */
const currentIndex = computed(() => (current.value ? items.value.findIndex((t) => t.id === current.value!.id) : -1));

const showEmpty = computed(
  () => !loading.value && (permissionDenied.value || failed.value || items.value.length === 0)
);

const headSub = computed(() => {
  if (permissionDenied.value) return '未授权';
  return total.value ? `共 ${total.value} 首` : '';
});

// ── 播放控制 ──

async function playTrack(track: MediaItem) {
  const el = audioEl.value;
  if (!el) return;
  current.value = track;
  // 先清上一首的封面与歌词：新封面到达前显示旧图会被误读成"切歌没成功"
  coverPool.release(coverUrl.value);
  coverUrl.value = '';
  lyricLines.value = [];
  currentSec.value = 0;
  durationSec.value = 0;
  try {
    el.src = await fetchStreamUrl(api, track.path);
    await el.play();
  } catch (e: any) {
    // 用户手势之外的 play() 会被浏览器拒（NotAllowedError）——那不是取数失败，不必报错
    if (e?.name !== 'NotAllowedError') message.error(e?.message || '播放失败');
  }
  loadCover(track.id);
  loadLyrics(track.id);
}

async function loadCover(id: number) {
  const url = await fetchCoverUrl(api, id);
  // 取回来时用户可能已经切歌了，对不上就丢掉（并回收，否则泄漏）
  if (current.value?.id !== id) {
    if (url) URL.revokeObjectURL(url);
    return;
  }
  coverUrl.value = coverPool.keep(url);
}

async function loadLyrics(id: number) {
  const text = await fetchLyricsText(api, id);
  if (current.value?.id !== id) return;
  // 解析复用文件页那份（含 [offset:] 与"纯文本降级"），不在这里再写一遍
  lyricLines.value = text ? parseLyrics(text) : [];
}

function togglePlay() {
  const el = audioEl.value;
  if (!el || !current.value) return;
  if (el.paused) el.play().catch(() => undefined);
  else el.pause();
}

function playPrev() {
  const i = currentIndex.value;
  if (i > 0) playTrack(items.value[i - 1]!);
}

function playNext() {
  const i = currentIndex.value;
  if (i >= 0 && i < items.value.length - 1) playTrack(items.value[i + 1]!);
  else playing.value = false;
}

function seekTo(sec: number) {
  const el = audioEl.value;
  if (!el || !Number.isFinite(sec)) return;
  el.currentTime = sec;
  currentSec.value = sec;
}

function setVolume(v: number) {
  volume.value = v;
  if (audioEl.value) audioEl.value.volume = v;
}

function onTimeUpdate() {
  currentSec.value = audioEl.value?.currentTime ?? 0;
}

function onLoadedMetadata() {
  const d = audioEl.value?.duration;
  // 流式音频的 duration 可能是 Infinity（未知），此时回落到 MediaStore 给的时长
  durationSec.value = Number.isFinite(d) ? (d as number) : (current.value?.duration_ms ?? 0) / 1000;
}

function onAudioError() {
  // src 为空串时浏览器也会触发一次 error，那不是真故障
  if (audioEl.value?.src) message.error('音频无法播放，可能是浏览器不支持该编码');
}

onMounted(reload);
onUnmounted(() => {
  audioEl.value?.pause();
  coverPool.releaseAll();
});
</script>

<style scoped>
.audio-page {
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
}
.head-search {
  width: 200px;
}

/* 播放面板与队列并排：面板固定较宽（封面要够大），队列吃掉剩余宽度 */
.audio-body {
  display: grid;
  grid-template-columns: minmax(0, 1.35fr) minmax(0, 1fr);
  gap: 20px;
  align-items: start;
}
/* .sub-panel 是全局类（描边 + 内距 + 圆角 + 底色） */
.panel-wrap {
  min-width: 0;
}
.panel-idle {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 12px;
  min-height: 320px;
}
.idle-text {
  font-size: 13px;
  color: var(--text-muted);
}

/* ── 队列 ── */
.queue-wrap {
  display: flex;
  flex-direction: column;
  min-width: 0;
}
.queue-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 10px;
  margin-bottom: 8px;
}
.queue-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--text-primary);
}
.queue-count {
  font-size: 12px;
  color: var(--text-muted);
}
.queue-scroll {
  max-height: 64vh;
}
.queue-list {
  display: flex;
  flex-direction: column;
}
.queue-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 10px;
  border: none;
  border-radius: var(--radius-sm);
  background: transparent;
  cursor: pointer;
  text-align: left;
  font: inherit;
  color: inherit;
  transition: background 0.15s;
}
.queue-item:hover {
  background: var(--surface-hover);
}
.queue-item.is-current {
  background: var(--accent-color-light);
}
.queue-item.is-current .q-title {
  color: var(--accent-color);
  font-weight: 600;
}
.q-index {
  width: 20px;
  flex-shrink: 0;
  text-align: center;
  font-size: 12px;
  color: var(--text-muted);
  font-variant-numeric: tabular-nums;
}
.queue-item.is-current .q-index {
  color: var(--accent-color);
}
.q-thumb {
  width: 36px;
  height: 36px;
  flex-shrink: 0;
  border-radius: var(--radius-sm);
  object-fit: cover;
  background: var(--surface-elevated);
}
.q-thumb-fallback {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  color: var(--text-muted);
}
.q-text {
  display: flex;
  flex-direction: column;
  gap: 1px;
  flex: 1;
  min-width: 0;
}
.q-title {
  font-size: 13px;
  color: var(--text-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.q-artist {
  font-size: 11px;
  color: var(--text-muted);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.q-dur {
  flex-shrink: 0;
  font-size: 11px;
  color: var(--text-muted);
  font-variant-numeric: tabular-nums;
}
.queue-more {
  display: flex;
  justify-content: center;
  padding: 10px 0;
}

/* <audio> 只做解码与播放，不出现在布局里 */
.audio-el {
  display: none;
}

@media (max-width: 1100px) {
  .audio-body {
    grid-template-columns: minmax(0, 1fr);
  }
  .queue-scroll {
    max-height: 42vh;
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
  /* 队列行加高到 44px 级别：手机上 8px 内距的行太窄，点歌容易点错相邻一首 */
  .queue-item {
    padding: 10px;
  }
  .q-thumb {
    width: 40px;
    height: 40px;
  }
}
</style>

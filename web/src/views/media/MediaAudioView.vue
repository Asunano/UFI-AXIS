<!--
  音乐页。与 app 的 `MediaAudioScreen` + `MediaAudioPlayerScreen` 对应。

  ──── 版式：底部播放条 + 上方两列 ────
  之前是「播放面板（封面列 + 歌词列）」与「队列列」并排 ⇒ **一屏三列**。
  1440px 视口下每列只剩三四百像素：封面不敢放大、歌词一行断成两截、
  队列窗口固定 520px 只能显示十来行，而队列恰恰是这一页最主要的浏览对象。

  现在拆成三块，各自吃满该吃的方向（Spotify / Apple Music 的通用骨架）：

    ┌─ 页头（标题 + 搜索 + 刷新）────────────────┐
    │  队列（吃满高度）        │  歌词（可收起）  │
    │                          │                  │
    ├─ 播放条（封面 + 走带 + 进度 + 音量 + 开关）─┤
    └───────────────────────────────────────────┘

  · 高度不再靠 `vh` 猜：`DefaultLayout` 的内容区是 `height:100%` 的独立滚动容器
    （见那里 `.main-content > .n-layout-scroll-container`），所以这里 `height:100%`
    拿到的就是"顶栏以下的全部空间"，主区 `flex:1` 自然吃满、播放条贴在底部。
    只在视口比 `min-height` 还矮时才退回整页滚动。
  · **沉浸视图**：点播放条左侧的封面（或右侧的展开钮），主列从队列换成大封面 + 曲目信息，
    歌词列不动 —— 于是"看封面听歌"和"翻队列选歌"是同一屏的两种模式，不是两个页面。

  ──── 播放链路 ────
  `<audio>` 的 src 走票据（见 `mediaShared.fetchStreamUrl`）。**一个 `<audio>` 实例复用**，
  换曲只换 src：每首新建一个元素会让上一首的缓冲与解码器留在内存里。
  票据滑动过期 10 分钟，长时间暂停后再播会失败 —— 那种情况自动换一张票据接着播
  （见 [onAudioError]），不是报"浏览器不支持该编码"。

  ──── 元数据的三层来源 ────
  1. `/api/media/list` 的 `title/artist/album`（MediaStore 扫出来的，绝大多数够用）；
  2. 缺了才问 `/api/media/tags` —— core 侧用自己的 `AudioTagReader` 解文件字节、
     再用 `MediaMetadataRetriever` 兜底，比前端那份解析器更全（支持 m4a）。
     **不在前端重解一遍标签**，理由写在 `mediaShared.fetchAudioTags` 的注释里；
  3. 封面 `/api/media/cover`（内嵌图原字节）取不到 → 退到同目录 sidecar 图；
     歌词 `/api/media/lyrics`（旁挂 .lrc/.txt）没有 → 退到内嵌 USLT / FLAC LYRICS。
     后两条兜底复用文件管理器那套 Range 探测（`views/files/audioMetaProbe.ts`）——
     core 的这两条端点明确不做这部分（`/lyrics` 只读旁挂、`/cover` 只看内嵌）。

  ──── 切歌时的取数顺序 ────
  先把上一首的封面/歌词清掉再取新的，否则在新封面到达之前会短暂显示上一首的图
  （那比显示占位图更容易误读成"切歌没成功"）。所有异步结果都用 [playSeq] 校验代数。
-->
<template>
  <div class="audio-page">
    <div class="page-head">
      <div class="head-text">
        <h2 class="head-title">音乐</h2>
        <span class="head-sub">{{ headSub }}</span>
      </div>
      <div class="head-actions">
        <n-button size="small" :quaternary="!playlistsOpen" :type="playlistsOpen ? 'primary' : 'default'" @click="playlistsOpen = !playlistsOpen">
          歌单
        </n-button>
        <n-input v-model:value="query" size="small" placeholder="搜索歌名 / 艺人" clearable class="head-search" />
        <n-button size="small" quaternary :loading="loading" @click="reload">刷新</n-button>
      </div>
    </div>

    <MediaEmptyState v-if="showEmpty" :permission-denied="permissionDenied" :failed="failed" kind-label="音频" />

    <template v-else>
      <div class="main-area" :class="{ 'lyrics-off': !lyricsOpen, 'playlists-off': !playlistsOpen }">
        <!-- 歌单列：选一个歌单就把队列换成它的曲目，不打断正在播的那一首 -->
        <PlaylistPanel
          v-if="playlistsOpen"
          :playlists="playlists"
          :active-id="activePlaylistId"
          :loading="playlistsLoading"
          @select="selectPlaylist"
          @create="openCreatePlaylist"
          @rename="openRenamePlaylist"
          @remove="confirmRemovePlaylist"
        />

        <!-- 主列：沉浸视图下是大封面，其余时候是队列 -->
        <section v-if="stageOpen && current" class="stage sub-panel">
          <div class="stage-cover" :class="{ 'is-empty': !coverUrl, 'is-playing': playing }">
            <img v-if="coverUrl" :src="coverUrl" :alt="displayTitle" />
            <svg v-else viewBox="0 0 24 24" aria-hidden="true">
              <path fill="currentColor" d="M12 3v10.55A4 4 0 1 0 14 17V7h4V3h-6z" />
            </svg>
          </div>
          <div class="stage-meta">
            <div class="stage-title" :title="displayTitle">{{ displayTitle }}</div>
            <div class="stage-artist" :title="displayArtist">{{ displayArtist }}</div>
            <div v-if="displayAlbum" class="stage-album" :title="displayAlbum">{{ displayAlbum }}</div>
          </div>
        </section>

        <section v-else class="queue sub-panel">
          <div class="queue-head">
            <span class="queue-title">{{ queueTitle }}</span>
            <span class="queue-count">{{ queueCountText }}</span>
          </div>
          <n-spin :show="queueLoading && queueTracks.length === 0" class="queue-spin">
            <n-scrollbar class="queue-scroll">
              <div class="queue-list">
                <!--
                  一行 = 可点的曲目按钮 + 右侧「⋯」。两者必须是**兄弟**而不是嵌套：
                  按钮里套按钮是非法 HTML，浏览器会把内层提出来，点「⋯」会连带触发播放。
                  key 用 path 而不是 id —— 歌单里已失效的条目 id 全是 0，按 id 做 key 会撞。
                -->
                <div
                  v-for="(t, i) in filtered"
                  :key="t.path"
                  class="queue-row"
                  :class="{ 'is-current': t.path === current?.path, 'is-missing': t.missing }"
                >
                  <button
                    type="button"
                    class="queue-item"
                    :disabled="t.missing"
                    :aria-current="t.path === current?.path ? 'true' : undefined"
                    @click="playTrack(t)"
                  >
                    <!-- 序号位在当前曲目上换成播放状态图标：Apple Music 就是这个处理 -->
                    <span class="q-index">
                      <n-icon v-if="t.path === current?.path" :size="14">
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
                      <span class="q-artist">{{ t.missing ? MISSING_NOTE : trackArtistOf(t) }}</span>
                    </span>
                    <span class="q-dur">{{ formatDuration(t.duration_ms) }}</span>
                  </button>
                  <n-dropdown
                    trigger="click"
                    :options="rowMenuOptions"
                    @select="(k: string) => onRowMenu(k, t)"
                  >
                    <n-button size="tiny" quaternary class="q-more" aria-label="更多操作">⋯</n-button>
                  </n-dropdown>
                </div>
              </div>
              <!-- 歌单是一次给全量的，没有"加载更多"这件事 -->
              <div v-if="!activePlaylistId && hasMore()" class="queue-more">
                <n-button size="tiny" quaternary :loading="loadingMore" @click="loadMore">加载更多</n-button>
              </div>
            </n-scrollbar>
          </n-spin>
        </section>

        <!-- 歌词列。没选曲目时保留这一列的位置，但显示引导而不是空面板 -->
        <section v-if="lyricsOpen" class="lyrics-col sub-panel">
          <LyricsPanel
            v-if="current"
            :lyric-lines="lyricLines"
            :current-sec="currentSec"
            :large="stageOpen"
            @seek="seekTo"
          />
          <div v-else class="lyrics-idle">
            <n-icon :size="40" color="var(--text-muted)"><MusicalNotesOutline /></n-icon>
            <span>从左侧队列选一首开始播放</span>
          </div>
        </section>
      </div>

      <!-- 播放条只在选了曲目之后出现：没有当前曲目时它上面每个控件都是死的 -->
      <AudioPlayerBar
        v-if="current"
        v-model:lyrics-open="lyricsOpen"
        v-model:stage-open="stageOpen"
        :title="displayTitle"
        :artist="displayArtist"
        :album="displayAlbum"
        :cover-url="coverUrl"
        :playing="playing"
        :current-sec="currentSec"
        :duration-sec="durationSec"
        :volume="volume"
        :has-prev="currentIndex > 0"
        :has-next="currentIndex >= 0 && currentIndex < queueTracks.length - 1"
        @toggle="togglePlay"
        @prev="playPrev"
        @next="playNext"
        @seek="seekTo"
        @volume="setVolume"
      />
    </template>

    <!--
      歌单名输入（新建 / 重命名共用一个弹窗）。
      新建与重命名只差一个标题和"提交给谁"，两个弹窗会得到两份一样的校验与两处要同步的文案。

      `width` 必须显式给（2026-09-21 修"左右边距不对"）：`preset="card"` 的 naive 样式里
      **没有默认宽度**，不写就会被 `main.css` 那条 `.n-modal { max-width: calc(100vw - 32px) }`
      顶到接近整屏宽 —— 一个只放一行输入框的弹窗铺满桌面屏，左右边距看着就是坏的。
      420px + 视口兜底是本仓小弹窗的既有取值（见 SleepModal / FileInfoModal）。
    -->
    <n-modal
      v-model:show="nameModalOpen"
      preset="card"
      :title="nameModalTitle"
      style="width: 420px; max-width: calc(100vw - 32px)"
      @after-leave="onNameModalClosed"
    >
      <n-input
        v-model:value="nameInput"
        placeholder="歌单名"
        :maxlength="64"
        show-count
        @keyup.enter="submitName"
      />
      <template #footer>
        <div class="pl-name-actions">
          <n-button size="small" quaternary @click="nameModalOpen = false">取消</n-button>
          <n-button size="small" type="primary" :disabled="!nameInput.trim()" @click="submitName">确定</n-button>
        </div>
      </template>
    </n-modal>

    <!--
      唯一的 <audio> 实例。不加 controls：走带完全由播放条接管，
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
import { useDialog, useMessage } from 'naive-ui';
import { MusicalNotesOutline, PauseOutline, VolumeHighOutline } from '@vicons/ionicons5';
import { WsDataTopic } from '@/api/contract';
import { useWebSocketStore } from '@/stores/websocket';
import {
  createBlobUrlPool,
  fetchAudioTags,
  fetchCoverUrl,
  fetchLyricsText,
  fetchStreamUrl,
  formatDuration,
  readAudioVolume,
  trackArtistOf,
  trackTitleOf,
  writeAudioVolume,
  type MediaItem,
} from './mediaShared';
import {
  addPlaylistItems,
  addResultText,
  createPlaylist,
  deletePlaylist,
  fetchPlaylistItems,
  fetchPlaylists,
  playlistErrorText,
  removePlaylistItems,
  renamePlaylist,
  type PlaylistEntry,
  type PlaylistTrack,
} from './playlistShared';
import { useMediaLibrary } from './useMediaLibrary';
import { parseLyrics } from '@/views/files/id3Lyrics';
import { probeEmbeddedLyrics, probeSidecarCover } from '@/views/files/audioMetaProbe';
import { mediaErrorTextOf } from '@/composables/videoFormat';
import AudioPlayerBar from './components/AudioPlayerBar.vue';
import LyricsPanel from './components/LyricsPanel.vue';
import MediaEmptyState from './components/MediaEmptyState.vue';
import PlaylistPanel from './components/PlaylistPanel.vue';

const message = useMessage();
const dialog = useDialog();
const wsStore = useWebSocketStore();
const { api, items, total, loading, loadingMore, permissionDenied, failed, thumbs, hasMore, reload, loadMore, loadThumbs } =
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
const volume = ref(readAudioVolume());

/** 歌词列是否展开。收起后队列/大封面独占整宽。 */
const lyricsOpen = ref(true);
/** 沉浸视图：主列从队列换成大封面。 */
const stageOpen = ref(false);
/**
 * 歌单列是否展开。
 *
 * 默认**收起**：这一页的主业是"翻队列听歌"，歌单是按需用的。而且在窄屏上歌单列会独占
 * 主列（三块并排谁都看不清），默认展开会让手机用户一进来看到的是歌单而不是歌。
 */
const playlistsOpen = ref(false);

// ── 歌单（详见 playlistShared） ──

const playlists = ref<PlaylistEntry[]>([]);
const playlistsLoading = ref(false);
/** 当前选中的歌单 id；null = 全部音乐（队列 = 媒体库列表）。 */
const activePlaylistId = ref<string | null>(null);
const activePlaylistName = ref('');
const playlistTracks = ref<PlaylistTrack[]>([]);
const playlistTracksLoading = ref(false);
const playlistMissingCount = ref(0);
/** 序号守卫：loadPlaylistTracks 的两个入口（selectPlaylist / ws data_changed）可能并发 */
let plTrackSeq = 0;

/** 已失效条目在队列里那一行的副标题。 */
const MISSING_NOTE = '文件已不在媒体库';

/** `/api/media/tags` 补回来的标签，只在 `/list` 那份为空时才用得上。 */
const tagTitle = ref('');
const tagArtist = ref('');
const tagAlbum = ref('');

/**
 * 切歌代数。
 *
 * `playTrack` 里有 `await 换票`，之后还并发着标签/封面/歌词三条异步链。
 * 快速连点时这些回调会交错，必须靠代数判断"我还是当前那一首吗"——
 * 只比对 `current.value?.id` 不够：同一首被连点两次时 id 相同但票据已经换过，
 * 慢的那一次会把新的 `el.src` 覆盖掉。
 */
let playSeq = 0;

/**
 * 当前队列的来源：选了歌单就是那个歌单的曲目，否则是媒体库列表。
 *
 * 歌单的顺序**是用户排定的**，core 原样给出 —— 这里不再按时间/名称排一次。
 */
const queueTracks = computed<PlaylistTrack[]>(() =>
  activePlaylistId.value ? playlistTracks.value : items.value
);
const queueLoading = computed(() => (activePlaylistId.value ? playlistTracksLoading.value : loading.value));
const queueTitle = computed(() => (activePlaylistId.value ? activePlaylistName.value || '歌单' : '播放队列'));
const queueCountText = computed(() => {
  if (!activePlaylistId.value) return `${filtered.value.length} / ${total.value}`;
  const base = `${filtered.value.length} / ${playlistTracks.value.length}`;
  return playlistMissingCount.value > 0 ? `${base} · ${playlistMissingCount.value} 首已失效` : base;
});

const filtered = computed(() => {
  const q = query.value.trim().toLowerCase();
  if (!q) return queueTracks.value;
  return queueTracks.value.filter(
    (t) => trackTitleOf(t).toLowerCase().includes(q) || trackArtistOf(t).toLowerCase().includes(q)
  );
});

/**
 * 上一首/下一首在**当前队列来源的全集**里算，不在筛选结果里 ——
 * 筛选只是查找手段，不该改变播放顺序。
 *
 * 按 path 比对而不是 id：歌单里已失效的条目 id 全是 0，用 id 会撞到一起。
 */
const currentIndex = computed(() =>
  current.value ? queueTracks.value.findIndex((t) => t.path === current.value!.path) : -1
);

/** 显示用标签：`/list` 优先，空了才用 `/tags` 补回来的那份，最后退到文件名。 */
const displayTitle = computed(() =>
  current.value ? current.value.title || tagTitle.value || trackTitleOf(current.value) : ''
);
const displayArtist = computed(() => current.value?.artist || tagArtist.value || '未知艺人');
const displayAlbum = computed(() => current.value?.album || tagAlbum.value || '');

const showEmpty = computed(
  () => !loading.value && (permissionDenied.value || failed.value || items.value.length === 0)
);

const headSub = computed(() => {
  if (permissionDenied.value) return '未授权';
  return total.value ? `共 ${total.value} 首` : '';
});

// ── 播放控制 ──

async function playTrack(track: PlaylistTrack) {
  const el = audioEl.value;
  if (!el) return;
  // 已失效的条目没有可取流的文件：点了只会换来一句播放失败，不如什么都不做
  if (track.missing) return;
  const seq = ++playSeq;
  current.value = track;
  ticketRetried = false;
  // 先清上一首的封面/歌词/标签：新的到达前显示旧的会被误读成"切歌没成功"
  coverPool.release(coverUrl.value);
  coverUrl.value = '';
  lyricLines.value = [];
  tagTitle.value = '';
  tagArtist.value = '';
  tagAlbum.value = '';
  currentSec.value = 0;
  durationSec.value = 0;
  try {
    const src = await fetchStreamUrl(api, track.path);
    // 期间用户已经点了别的歌：这张票据连 src 都不要赋，否则会把新的覆盖掉
    if (seq !== playSeq) return;
    el.src = src;
    el.volume = volume.value;
    await el.play();
  } catch (e: any) {
    if (seq !== playSeq) return;
    // 用户手势之外的 play() 会被浏览器拒（NotAllowedError）——那不是取数失败，不必报错。
    // useCancellableApi 在取消在途请求时会让 fetchStreamUrl 抛"无法获取播放地址"，
    // 而"取消"几乎总是因为用户自己切了歌/离开了页面，弹 toast 是噪音。
    const cancelled = e?.code === 'ERR_CANCELED' || e?.message === '无法获取播放地址';
    if (e?.name !== 'NotAllowedError' && !cancelled) message.error(e?.message || '播放失败');
  }
  if (seq !== playSeq) return;
  void loadTags(track, seq);
  void loadCover(track, seq);
  void loadLyrics(track, seq);
}

/** `/list` 的标签齐了就不必多问一次 core。 */
async function loadTags(track: MediaItem, seq: number) {
  if (track.title && track.artist && track.album) return;
  const tags = await fetchAudioTags(api, track.id);
  if (!tags || seq !== playSeq) return;
  tagTitle.value = tags.title || '';
  tagArtist.value = tags.artist || '';
  tagAlbum.value = tags.album || '';
}

async function loadCover(track: MediaItem, seq: number) {
  const url = await fetchCoverUrl(api, track.id);
  // 取回来时用户可能已经切歌了，对不上就丢掉（并回收，否则泄漏）
  if (seq !== playSeq) {
    if (url) URL.revokeObjectURL(url);
    return;
  }
  if (url) {
    coverUrl.value = coverPool.keep(url);
    return;
  }
  // core 的 /cover 只看内嵌图（取不到才退缩略图），不会去扫同目录。
  // 大量 FLAC / 整轨是外挂封面（cover.jpg / folder.jpg），这里补上这条。
  await probeSidecarCover(track.path, metaSink(seq));
}

async function loadLyrics(track: MediaItem, seq: number) {
  const text = await fetchLyricsText(api, track.id);
  if (seq !== playSeq) return;
  // 解析复用文件页那份（含 [offset:] 与"纯文本降级"），不在这里再写一遍
  const parsed = text ? parseLyrics(text) : [];
  if (parsed.length) {
    lyricLines.value = parsed;
    return;
  }
  // core 的 /lyrics 明确只读旁挂 .lrc/.txt（注释里写了"不在 core 里塞 ID3 解析器"），
  // 内嵌歌词只能前端解 —— 复用文件管理器那条 Range 探测
  await probeEmbeddedLyrics(track.path, track.size, metaSink(seq));
}

/**
 * 给 `audioMetaProbe` 的结果接收端。
 *
 * 只接封面与歌词：标签走 core 的 `/tags`（见文件头说明），这里刻意忽略 `onTags`。
 */
function metaSink(seq: number) {
  return {
    isStale: () => seq !== playSeq,
    hasCover: () => !!coverUrl.value,
    onTags: () => undefined,
    onCover: (u: string) => {
      if (seq !== playSeq) {
        URL.revokeObjectURL(u);
        return;
      }
      coverUrl.value = coverPool.keep(u);
    },
    onLyrics: (lines: Array<[number, string]>) => {
      if (seq === playSeq) lyricLines.value = lines;
    },
  };
}

function togglePlay() {
  const el = audioEl.value;
  if (!el || !current.value) return;
  if (el.paused) el.play().catch(() => undefined);
  else el.pause();
}

function playPrev() {
  const i = currentIndex.value;
  // 跳过已失效的：它们在队列里只是占位，不能播
  for (let k = i - 1; k >= 0; k--) {
    const t = queueTracks.value[k];
    if (t && !t.missing) {
      void playTrack(t);
      return;
    }
  }
}

function playNext() {
  const i = currentIndex.value;
  if (i >= 0) {
    for (let k = i + 1; k < queueTracks.value.length; k++) {
      const t = queueTracks.value[k];
      if (t && !t.missing) {
        void playTrack(t);
        return;
      }
    }
  }
  // 到尾就停，不循环
  playing.value = false;
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

/**
 * 播放出错。
 *
 * 两类要分开，不能一句"浏览器不支持该编码"了事：
 *  · **票据过期**（滑动过期 10 分钟）—— 长时间暂停后按播放/拖进度就会撞上。
 *    表现是网络类错误，而这时"重新换一张票、seek 回原位继续"是完全可自动完成的，
 *    不该让用户看到一句误导的报错然后自己重新点一遍；
 *  · 真的解不了 —— 交给 `mediaErrorTextOf` 按 MediaError 的 code 给准确文案
 *    （与视频播放器同一份映射，两处对同一个 code 必须说同一句话）。
 */
async function onAudioError() {
  const el = audioEl.value;
  // src 为空串时浏览器也会触发一次 error，那不是真故障
  if (!el?.src || !current.value) return;
  const code = el.error?.code;
  const played = currentSec.value > 0;
  if (played && code === MediaError.MEDIA_ERR_NETWORK && !ticketRetried) {
    ticketRetried = true;
    await resumeWithFreshTicket();
    return;
  }
  message.error(mediaErrorTextOf(el.error, current.value.name));
}

/** 一首歌只自动换票一次：换完还错说明不是票据的问题，再循环下去就是死循环。 */
let ticketRetried = false;

async function resumeWithFreshTicket() {
  const el = audioEl.value;
  const track = current.value;
  if (!el || !track) return;
  const at = currentSec.value;
  const seq = playSeq;
  try {
    const src = await fetchStreamUrl(api, track.path);
    if (seq !== playSeq) return;
    el.src = src;
    el.currentTime = at;
    await el.play();
  } catch {
    // 换票也失败：可能是 core 重启或文件已删，这时如实报错
    message.error('播放中断，且重新获取播放地址失败');
  }
}

// ── 歌单：取数、选择与写操作 ──
//
// 歌单本体存在 core，这里只是镜像：写完一律重拉，不做本地乐观更新 ——
// 加歌的真实结果（跳过了几条、有没有撞上限）只有 core 知道。

async function reloadPlaylists() {
  playlistsLoading.value = true;
  try {
    playlists.value = await fetchPlaylists(api);
  } catch (e: any) {
    message.error(playlistErrorText('加载歌单', e));
  } finally {
    playlistsLoading.value = false;
  }
}

async function loadPlaylistTracks(id: string) {
  const seq = ++plTrackSeq;
  playlistTracksLoading.value = true;
  try {
    const resp = await fetchPlaylistItems(api, id);
    // useCancellableApi 取消在途请求时为 null，按"没结果"处理，不要清空已有列表
    if (!resp) return;
    if (seq !== plTrackSeq) return; // 先发后到，丢弃过期结果
    activePlaylistName.value = resp.name;
    playlistTracks.value = resp.items ?? [];
    playlistMissingCount.value = resp.missing_count ?? 0;
    // 缩略图走与列表同一个并发闸门和同一份缓存（见 useMediaLibrary.loadThumbs）
    void loadThumbs(playlistTracks.value);
  } catch (e: any) {
    if (seq !== plTrackSeq) return;
    message.error(playlistErrorText('加载歌单曲目', e));
  } finally {
    if (seq === plTrackSeq) playlistTracksLoading.value = false;
  }
}

/**
 * 切换队列来源。
 *
 * **不碰播放器**：切歌单只是换"接下来从哪挑歌"，正在听的那一首继续放。
 * 代价是当前曲目可能不在新来源里（`currentIndex = -1`），此时上/下一首会置灰 ——
 * 那是如实的：新来源里确实没有"这首的下一首"。
 */
function selectPlaylist(id: string | null) {
  activePlaylistId.value = id;
  if (!id) {
    playlistTracks.value = [];
    playlistMissingCount.value = 0;
    activePlaylistName.value = '';
    return;
  }
  void loadPlaylistTracks(id);
}

const ADD_PREFIX = 'add:';
const NEW_PLAYLIST_KEY = '__new__';

/** 队列行「⋯」菜单：加入歌单（二级菜单列出全部歌单）+ 从本歌单移出。 */
const rowMenuOptions = computed(() => {
  const children: Array<{ label: string; key: string }> = playlists.value.map((p) => ({
    label: `${p.name}（${p.count}）`,
    key: `${ADD_PREFIX}${p.id}`,
  }));
  // 一个歌单都没有时这一条就是唯一入口 —— 否则用户得先去建一个再回来
  children.push({ label: '＋ 新建歌单…', key: `${ADD_PREFIX}${NEW_PLAYLIST_KEY}` });
  const opts: Array<Record<string, unknown>> = [{ label: '加入歌单', key: 'add', children }];
  if (activePlaylistId.value) opts.push({ label: '从本歌单移出', key: 'remove' });
  return opts;
});

function onRowMenu(key: string, track: PlaylistTrack) {
  if (key === 'remove') {
    void doRemoveFromPlaylist(track);
    return;
  }
  if (!key.startsWith(ADD_PREFIX)) return;
  const target = key.slice(ADD_PREFIX.length);
  if (target === NEW_PLAYLIST_KEY) {
    openNameModal('create-and-add', '', '');
    pendingAddPath.value = track.path;
    return;
  }
  void doAddToPlaylist(target, track.path);
}

async function doAddToPlaylist(playlistId: string, path: string) {
  try {
    const r = await addPlaylistItems(api, playlistId, [path]);
    const name = playlists.value.find((p) => p.id === playlistId)?.name ?? '歌单';
    message.success(addResultText(name, r));
    await reloadPlaylists();
    if (activePlaylistId.value === playlistId) await loadPlaylistTracks(playlistId);
  } catch (e: any) {
    message.error(playlistErrorText('加入歌单', e));
  }
}

async function doRemoveFromPlaylist(track: PlaylistTrack) {
  const id = activePlaylistId.value;
  if (!id) return;
  try {
    const removed = await removePlaylistItems(api, id, [track.path]);
    message.success(`已移出 ${removed} 首`);
    await loadPlaylistTracks(id);
    await reloadPlaylists();
  } catch (e: any) {
    message.error(playlistErrorText('移出歌单', e));
  }
}

// ── 歌单名弹窗（新建 / 新建并加入 / 重命名 共用） ──

type NameMode = 'create' | 'create-and-add' | 'rename';

const nameModalOpen = ref(false);
const nameInput = ref('');
const nameMode = ref<NameMode>('create');
/** rename 的目标歌单 id。 */
const nameTargetId = ref('');
/** create-and-add 时要顺带加进去的曲目路径。 */
const pendingAddPath = ref('');

const nameModalTitle = computed(() => (nameMode.value === 'rename' ? '重命名歌单' : '新建歌单'));

function openNameModal(mode: NameMode, targetId: string, initial: string) {
  nameMode.value = mode;
  nameTargetId.value = targetId;
  nameInput.value = initial;
  nameModalOpen.value = true;
}

function openCreatePlaylist() {
  pendingAddPath.value = '';
  openNameModal('create', '', '');
}

function openRenamePlaylist(entry: PlaylistEntry) {
  pendingAddPath.value = '';
  openNameModal('rename', entry.id, entry.name);
}

/** 弹窗完全关闭后再清状态：关闭动画期间清掉会让标题/输入框闪一下。 */
function onNameModalClosed() {
  nameInput.value = '';
  nameTargetId.value = '';
  pendingAddPath.value = '';
}

async function submitName() {
  const name = nameInput.value.trim();
  if (!name) return;
  const mode = nameMode.value;
  const targetId = nameTargetId.value;
  const addPath = pendingAddPath.value;
  nameModalOpen.value = false;
  try {
    if (mode === 'rename') {
      await renamePlaylist(api, targetId, name);
      message.success(`已重命名为「${name}」`);
      await reloadPlaylists();
      if (activePlaylistId.value === targetId) activePlaylistName.value = name;
      return;
    }
    const id = await createPlaylist(api, name);
    if (mode === 'create-and-add' && id && addPath) {
      const r = await addPlaylistItems(api, id, [addPath]);
      message.success(addResultText(name, r));
    } else {
      message.success(`已创建歌单「${name}」`);
    }
    await reloadPlaylists();
  } catch (e: any) {
    message.error(playlistErrorText(mode === 'rename' ? '重命名' : '创建歌单', e));
  }
}

function confirmRemovePlaylist(entry: PlaylistEntry) {
  dialog.warning({
    title: '删除歌单',
    // 说清"不删文件"：歌单里存的是路径，用户很容易以为删歌单会连歌一起删
    content: `删除歌单「${entry.name}」？里面的 ${entry.count} 首歌不会被删除。`,
    positiveText: '删除',
    negativeText: '取消',
    onPositiveClick: async () => {
      try {
        await deletePlaylist(api, entry.id);
        message.success('歌单已删除');
        // 正在看的就是它：退回"全部音乐"，但**不停止播放**
        if (activePlaylistId.value === entry.id) selectPlaylist(null);
        await reloadPlaylists();
      } catch (e: any) {
        message.error(playlistErrorText('删除歌单', e));
      }
    },
  });
}

onMounted(() => {
  reload();
  void reloadPlaylists();
});
onUnmounted(() => {
  audioEl.value?.pause();
  // 音量在这里落盘而不是每次 setVolume：拖音量条会连发几十个事件
  writeAudioVolume(volume.value);
  coverPool.releaseAll();
});

/**
 * 实时：另一端（app）改了歌单集合就立即对齐。
 *
 * 自己的写操作也会收到这一帧，于是会多重拉一次 —— 与 `views/tasks` 同一取舍：
 * 多一次 GET 换"两端永远一致"，比为自己的写操作去重更省心。
 */
const unsubPlaylists = wsStore.on('data_changed', (payload: any) => {
  if (String(payload?.changed ?? '') !== WsDataTopic.MEDIA_PLAYLISTS) return;
  void reloadPlaylists();
  const id = activePlaylistId.value;
  if (id) void loadPlaylistTracks(id);
});
onUnmounted(() => unsubPlaylists());
</script>

<style scoped>
/**
 * 三行纵向：页头（自然高）/ 主区（吃满）/ 播放条（自然高）。
 *
 * `height: 100%` 能成立是因为 `DefaultLayout` 的内容区是一个 `height:100%` 的独立滚动容器
 * —— 不是随文档流长的普通 div。于是这里拿到的就是"顶栏以下的全部空间"，
 * 不必像旧版那样用 `max-height: min(68vh, 520px)` 猜一个值。
 *
 * `min-height` 是唯一的退路：视口比它还矮时放弃"贴底"，交回外层容器滚动，
 * 否则队列会被压成两三行。
 */
.audio-page {
  display: flex;
  flex-direction: column;
  gap: 14px;
  height: 100%;
  min-height: 560px;
}

.page-head {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 16px;
  flex-wrap: wrap;
  flex: 0 0 auto;
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

/* 主区：歌单 | 队列（或大封面）| 歌词。
   `min-height: 0` 是让内层滚动容器生效的关键 —— 缺了它 flex 子项会以内容高度为下限，
   队列会一路长下去把播放条顶出视口。
   歌单列给 0.5fr：它只放名字与条数，比队列窄一档就够，再宽是浪费横向空间。 */
.main-area {
  flex: 1;
  min-height: 0;
  display: grid;
  grid-template-columns: minmax(0, 0.5fr) minmax(0, 1.15fr) minmax(0, 0.85fr);
  gap: 14px;
}
.main-area.lyrics-off {
  grid-template-columns: minmax(0, 0.5fr) minmax(0, 1fr);
}
.main-area.playlists-off {
  grid-template-columns: minmax(0, 1.15fr) minmax(0, 0.85fr);
}
.main-area.playlists-off.lyrics-off {
  grid-template-columns: minmax(0, 1fr);
}

/* ── 队列 ── */
/* .sub-panel 是全局类（描边 + 内距 + 圆角 + 底色） */
.queue {
  display: flex;
  flex-direction: column;
  min-width: 0;
  min-height: 0;
}
.queue-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 10px;
  margin-bottom: 8px;
  flex: 0 0 auto;
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
/* n-spin 会在内容外再包一层 div，不显式撑满的话里面的 n-scrollbar 拿不到高度 */
.queue-spin {
  flex: 1;
  min-height: 0;
}
.queue-spin :deep(.n-spin-content) {
  height: 100%;
}
.queue-scroll {
  height: 100%;
}
.queue-list {
  display: flex;
  flex-direction: column;
}
/* 一行 = 曲目按钮 + 右侧「⋯」。选中/失效态染到整行，
   只染按钮会在行尾「⋯」那一格留下没上色的缺口。 */
.queue-row {
  display: flex;
  align-items: center;
  border-radius: var(--radius-sm);
}
.queue-row:hover {
  background: var(--surface-hover);
}
.queue-row.is-current {
  background: var(--accent-color-light);
}
.queue-row.is-current .q-title {
  color: var(--accent-color);
  font-weight: 600;
}
/* 已失效：整行压暗，但**不隐藏** —— 隐藏等于"歌自己没了"，用户无从判断原因 */
.queue-row.is-missing .q-title,
.queue-row.is-missing .q-index,
.queue-row.is-missing .q-dur {
  color: var(--text-muted);
}
.queue-row.is-missing .queue-item {
  cursor: default;
}
/* 「⋯」常驻而不是 hover 才出现：触屏上没有 hover，藏起来等于这台设备没有这些操作 */
.q-more {
  flex-shrink: 0;
  margin-right: 4px;
}
.queue-item {
  display: flex;
  align-items: center;
  gap: 10px;
  flex: 1;
  min-width: 0;
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

/* ── 沉浸视图：大封面 + 曲目信息 ── */
.stage {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: var(--space-4);
  min-width: 0;
  min-height: 0;
  overflow: hidden;
}
/**
 * 封面只给高度基准、宽度交给图片比例，所以横版/竖版封面都按原始比例完整显示。
 * 它是这一列里唯一可收缩的元素，矮屏时先压它而不是让文字溢出。
 */
.stage-cover {
  display: flex;
  align-items: center;
  justify-content: center;
  flex: 0 1 auto;
  height: min(380px, 46vh);
  min-height: 96px;
  max-width: 100%;
  min-width: 0;
  /* 播放时略微放大 —— "在放"这件事由封面自己表达，不需要额外的动图 */
  transform: scale(0.96);
  transition: transform 0.35s cubic-bezier(0.22, 0.61, 0.36, 1);
}
.stage-cover.is-playing {
  transform: scale(1);
}
/* 只有"没封面"才画占位底板：有图时 contain 留出的空档会露出底色，看着像被遮了一层 */
.stage-cover.is-empty {
  aspect-ratio: 1 / 1;
  background: var(--surface-hover);
  border-radius: calc(var(--radius-lg) + 4px);
  color: var(--accent-color);
}
.stage-cover img {
  height: 100%;
  width: auto;
  max-width: 100%;
  object-fit: contain;
  display: block;
  border-radius: calc(var(--radius-lg) + 4px);
  /* 阴影是"浮起"这个层级语义，不是配色 —— 令牌只给颜色（含暗色档），几何留在这里 */
  box-shadow: 0 16px 40px var(--shadow-color-strong);
}
.stage-cover svg {
  width: 34%;
  height: 34%;
}
/* 三级文字层次：标题最重、艺人用主色、专辑最轻。
   层次靠字号+字重+颜色三者一起拉开，只改字号在中文里区分度不够。 */
.stage-meta {
  width: 100%;
  max-width: 420px;
  min-width: 0;
  text-align: center;
  flex: 0 0 auto;
}
.stage-title {
  font-size: 26px;
  font-weight: 700;
  letter-spacing: -0.3px;
  line-height: 1.24;
  color: var(--text-primary);
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}
.stage-artist {
  margin-top: 8px;
  font-size: var(--font-lg);
  font-weight: 500;
  color: var(--accent-color);
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}
.stage-album {
  margin-top: 4px;
  font-size: var(--font-sm);
  color: var(--text-muted);
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}

/* ── 歌词列 ── */
.lyrics-col {
  display: flex;
  flex-direction: column;
  min-width: 0;
  min-height: 0;
}
.lyrics-idle {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 12px;
  font-size: 13px;
  color: var(--text-muted);
  text-align: center;
}

/* <audio> 只做解码与播放，不出现在布局里 */
.audio-el {
  display: none;
}

.pl-name-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}

/* 窄屏：主区单列。歌词与队列不再并排 —— 360px 分两列谁都看不清，
   改由播放条上的「歌词」开关在两者之间切换（收起歌词 = 看队列）。 */
@media (max-width: 720px) {
  .audio-page {
    gap: 10px;
    /* 手机上不强求"贴底"：地址栏伸缩会让 100% 频繁变化，交回外层滚动更稳 */
    height: auto;
    min-height: 0;
  }
  /* 三列在窄屏一律退成单列（含歌单列）：360px 摆两列谁都看不清 */
  .main-area,
  .main-area.lyrics-off,
  .main-area.playlists-off,
  .main-area.playlists-off.lyrics-off {
    grid-template-columns: minmax(0, 1fr);
    /* 失去"吃满剩余高度"之后必须自己给一个高度，否则队列会一路长下去 */
    height: 60vh;
  }
  /* 歌单展开时它独占这一列：手机上三块并排谁都看不清。
     于是「歌单」开关在窄屏是真开关 —— 开 = 看歌单，关 = 回到队列 / 歌词。 */
  .main-area:not(.playlists-off) .queue,
  .main-area:not(.playlists-off) .stage,
  .main-area:not(.playlists-off) .lyrics-col {
    display: none;
  }
  /* 歌单收起后，歌词展开时再把主列藏掉：两块各占 30vh 都不好用 */
  .main-area.playlists-off:not(.lyrics-off) .queue,
  .main-area.playlists-off:not(.lyrics-off) .stage {
    display: none;
  }
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
  .stage-cover {
    height: min(220px, 32vh);
  }
  .stage-title {
    font-size: var(--font-xl);
  }
}
</style>

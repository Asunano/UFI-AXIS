<!--
  视频页。与 app 的 `MediaVideoScreen` / `MediaVideoHome`（海报墙）对应。

  海报墙用 16:9 缩略图 + 两行文件名 + 时长角标 —— 与 app 的 `PosterCell` 同一组信息。
  点开在弹窗里播放（`<video controls>`，Range/seek 全交给浏览器，见票据说明）。
-->
<template>
  <div class="media-list-page">
    <div class="page-head">
      <div class="head-text">
        <h2 class="head-title">视频</h2>
        <span class="head-sub">{{ headSub }}</span>
      </div>
      <div class="head-actions">
        <n-input v-model:value="query" size="small" placeholder="搜索文件名" clearable class="head-search" />
        <n-button size="small" quaternary :loading="loading" @click="reload">刷新</n-button>
      </div>
    </div>

    <MediaEmptyState v-if="showEmpty" :permission-denied="permissionDenied" :failed="failed" kind-label="视频" />

    <n-spin v-else :show="loading && items.length === 0">
      <div class="poster-grid">
        <button v-for="v in filtered" :key="v.id" type="button" class="poster-cell" @click="open(v)">
          <span class="poster-thumb">
            <img v-if="thumbs[v.id]" :src="thumbs[v.id]" :alt="v.name" class="thumb-img" loading="lazy" />
            <span v-else class="thumb-fallback">
              <n-icon :size="26"><VideocamOutline /></n-icon>
            </span>
            <span v-if="v.duration_ms" class="thumb-badge">{{ formatDuration(v.duration_ms) }}</span>
          </span>
          <span class="poster-name" :title="v.name">{{ v.name }}</span>
          <span class="poster-meta">{{ formatMediaSize(v.size) }}</span>
        </button>
      </div>
      <div v-if="hasMore()" class="load-more">
        <n-button size="small" quaternary :loading="loadingMore" @click="loadMore">
          加载更多（{{ items.length }} / {{ total }}）
        </n-button>
      </div>
    </n-spin>

    <!-- 播放弹窗只装播放器本身：不要卡片外壳、标题栏与文件详情表。
         播放时那些信息没人看，反而把播放器挤小、在手机上更明显。
         文件名/路径/分辨率这些在列表页与文件管理器里都查得到。 -->
    <n-modal :show="!!playingItem" :style="{ width: '960px', maxWidth: 'calc(100vw - 32px)' }" @update:show="close">
      <!-- 播放器统一走 VideoPlayer：格式分档、customType、字幕、错误兜底都在它里面。
           url 为空（还在换票据）时它自己不建实例，所以这里不用再判 -->
      <VideoPlayer
        v-if="playingItem"
        :url="streamUrl"
        :name="playingItem.name"
        :path="playingItem.path"
        :poster="thumbs[playingItem.id] || ''"
        can-download
        @download="downloadPlaying"
      />
      <div v-else class="player-loading"><n-spin size="small" /></div>
    </n-modal>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { useMessage } from 'naive-ui';
import { VideocamOutline } from '@vicons/ionicons5';
import { fetchStreamUrl, formatDuration, formatMediaSize, type MediaItem } from './mediaShared';
import { useMediaLibrary } from './useMediaLibrary';
import MediaEmptyState from './components/MediaEmptyState.vue';
import VideoPlayer from '@/components/VideoPlayer.vue';

const message = useMessage();
const { api, items, total, loading, loadingMore, permissionDenied, failed, thumbs, hasMore, reload, loadMore } =
  useMediaLibrary('video');

const query = ref('');
const playingItem = ref<MediaItem | null>(null);
const streamUrl = ref('');

const filtered = computed(() => {
  const q = query.value.trim().toLowerCase();
  return q ? items.value.filter((v) => v.name.toLowerCase().includes(q)) : items.value;
});

const showEmpty = computed(
  () => !loading.value && (permissionDenied.value || failed.value || items.value.length === 0)
);

const headSub = computed(() => {
  if (permissionDenied.value) return '未授权';
  return total.value ? `共 ${total.value} 个` : '';
});

async function open(v: MediaItem) {
  playingItem.value = v;
  streamUrl.value = '';
  try {
    streamUrl.value = await fetchStreamUrl(api, v.path);
  } catch (e: any) {
    message.error(e?.message || '获取播放地址失败');
    playingItem.value = null;
  }
}

function close() {
  playingItem.value = null;
  // 清空 src 让浏览器立刻停止拉流：只关弹窗的话 <video> 可能还在后台缓冲
  // （VideoPlayer 自己也会在 unmount 时 destroy，这里是双保险）
  streamUrl.value = '';
}

/**
 * 放不了的格式给一条下载出路。
 *
 * 用票据 URL + `download` 属性触发浏览器下载：票据地址在 `/api` 之外的免鉴权区，
 * `<a download>` 这种裸 GET 正好能用（`/api/files/download` 要签名头，`<a>` 带不上）。
 */
function downloadPlaying() {
  const item = playingItem.value;
  if (!item || !streamUrl.value) return;
  const a = document.createElement('a');
  a.href = streamUrl.value;
  a.download = item.name;
  a.click();
}

onMounted(reload);
</script>

<style scoped>
/* 页头 / 网格 / 加载更多 / 详情表这几块与图片页同构，但两页各自 scoped：
   共用一份要么抽成组件（两页的网格比例不同，抽了还要加参数），
   要么提到全局（会污染其它页的 .poster-* 命名）。当前规模下各留一份更清楚。 */
.media-list-page {
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

.poster-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(220px, 1fr));
  gap: 16px;
}
.poster-cell {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 0;
  border: none;
  background: transparent;
  cursor: pointer;
  text-align: left;
  font: inherit;
  color: inherit;
  min-width: 0;
}
.poster-thumb {
  position: relative;
  display: block;
  width: 100%;
  aspect-ratio: 16 / 9;
  border-radius: var(--radius-md);
  overflow: hidden;
  background: var(--surface-elevated);
  transition:
    transform 0.18s ease,
    box-shadow 0.18s ease;
}
.poster-cell:hover .poster-thumb {
  transform: translateY(-2px);
  box-shadow: 0 6px 18px rgba(0, 0, 0, 0.16);
}
.thumb-img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}
.thumb-fallback {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 100%;
  height: 100%;
  color: var(--text-muted);
}
/* 角标压在缩略图上，必须自带深色底才在任何画面上都读得出来 —— 这里刻意不用主题令牌 */
.thumb-badge {
  position: absolute;
  right: 6px;
  bottom: 6px;
  padding: 1px 5px;
  border-radius: 3px;
  background: rgba(0, 0, 0, 0.72);
  color: #fff;
  font-size: 11px;
  line-height: 1.5;
  font-variant-numeric: tabular-nums;
}
.poster-name {
  font-size: 13px;
  font-weight: 500;
  color: var(--text-primary);
  line-height: 1.4;
  /* 两行截断：文件名常常很长，一行放不下又不该把整格撑高 */
  display: -webkit-box;
  -webkit-line-clamp: 2;
  line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.poster-meta {
  font-size: 11px;
  color: var(--text-muted);
}

.load-more {
  display: flex;
  justify-content: center;
  padding: 16px 0;
}

.player-video {
  width: 100%;
  max-height: 68vh;
  border-radius: var(--radius-md);
  background: #000;
}
.player-loading {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 200px;
}

.detail-grid {
  margin-top: 12px;
  display: flex;
  flex-direction: column;
}
.detail-row {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 12px;
  padding: 5px 0;
  border-bottom: 1px solid var(--border-subtle);
}
.detail-row:last-child {
  border-bottom: none;
}
.detail-label {
  flex-shrink: 0;
  font-size: 12px;
  color: var(--text-muted);
}
.detail-value {
  font-size: 12px;
  color: var(--text-primary);
  text-align: right;
  word-break: break-all;
}

@media (max-width: 768px) {
  /* 页头竖排：标题与「搜索 + 刷新」各占一行，搜索框吃满剩余宽度。
     横排时那个 200px 的搜索框会把标题挤成两行（360px 视口放不下 标题+200+刷新）。 */
  .page-head {
    flex-direction: column;
    align-items: stretch;
    gap: 10px;
  }
  .head-actions {
    justify-content: space-between;
  }
  .head-search {
    /* flex:1 会把 flex-basis 置为 0，从而盖掉上面那条 width:200px */
    flex: 1;
    width: auto;
  }
  .poster-grid {
    grid-template-columns: repeat(auto-fill, minmax(150px, 1fr));
    gap: 10px;
  }
  /* 弹窗里的详情行：路径在手机上一行放不下，标签与值改成上下排 */
  .detail-row {
    flex-direction: column;
    gap: 2px;
  }
  .detail-value {
    text-align: left;
  }
}
</style>

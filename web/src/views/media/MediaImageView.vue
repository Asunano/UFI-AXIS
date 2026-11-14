<!--
  图片页。与 app 的 `MediaImageScreen` / `MediaImageViewerScreen` 对应。

  方格网格（1:1 裁切）+ 全屏查看器。查看器里可左右翻页，键盘 ←/→/Esc 同样可用 ——
  看图是连续动作，每张都要退回列表再点下一张是最让人烦的交互。

  ──── 为什么查看器显示的是缩略图而不是原图 ────
  **不是**。查看器换票据取原始字节（`fetchStreamUrl`），缩略图只做网格。
  原图可能是几十 MB 的 RAW/PNG，所以在原图到达前先把已有缩略图放大占位，
  避免大图加载期间一片空白。
-->
<template>
  <div class="media-list-page">
    <div class="page-head">
      <div class="head-text">
        <h2 class="head-title">图片</h2>
        <span class="head-sub">{{ headSub }}</span>
      </div>
      <div class="head-actions">
        <n-input v-model:value="query" size="small" placeholder="搜索文件名" clearable class="head-search" />
        <n-button size="small" quaternary :loading="loading" @click="reload">刷新</n-button>
      </div>
    </div>

    <MediaEmptyState v-if="showEmpty" :permission-denied="permissionDenied" :failed="failed" kind-label="图片" />

    <n-spin v-else :show="loading && items.length === 0">
      <div class="tile-grid">
        <button v-for="(p, i) in filtered" :key="p.id" type="button" class="tile" @click="openAt(i)">
          <img v-if="thumbs[p.id]" :src="thumbs[p.id]" :alt="p.name" class="tile-img" loading="lazy" />
          <span v-else class="tile-fallback">
            <n-icon :size="22"><ImageOutline /></n-icon>
          </span>
        </button>
      </div>
      <div v-if="hasMore()" class="load-more">
        <n-button size="small" quaternary :loading="loadingMore" @click="loadMore">
          加载更多（{{ items.length }} / {{ total }}）
        </n-button>
      </div>
    </n-spin>

    <n-modal
      :show="viewerIndex >= 0"
      preset="card"
      :title="viewerItem?.name || ''"
      style="width: 1000px; max-width: calc(100vw - 32px)"
      @update:show="close"
    >
      <div class="viewer">
        <button class="nav-btn nav-prev" :disabled="viewerIndex <= 0" title="上一张" @click="step(-1)">
          <n-icon :size="22"><ChevronBackOutline /></n-icon>
        </button>
        <!-- 原图未到达时先把缩略图放大顶住，别留一片空白 -->
        <img v-if="fullUrl" :src="fullUrl" :alt="viewerItem?.name" class="viewer-img" />
        <img v-else-if="viewerThumb" :src="viewerThumb" :alt="viewerItem?.name" class="viewer-img is-placeholder" />
        <div v-else class="viewer-loading"><n-spin size="small" /></div>
        <button
          class="nav-btn nav-next"
          :disabled="viewerIndex < 0 || viewerIndex >= filtered.length - 1"
          title="下一张"
          @click="step(1)"
        >
          <n-icon :size="22"><ChevronForwardOutline /></n-icon>
        </button>
      </div>
      <div v-if="viewerItem" class="detail-grid">
        <div v-for="row in detailRows" :key="row.label" class="detail-row">
          <span class="detail-label">{{ row.label }}</span>
          <span class="detail-value">{{ row.value }}</span>
        </div>
      </div>
    </n-modal>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue';
import { useMessage } from 'naive-ui';
import { ImageOutline, ChevronBackOutline, ChevronForwardOutline } from '@vicons/ionicons5';
import { fetchStreamUrl, formatMediaDate, formatMediaSize } from './mediaShared';
import { useMediaLibrary } from './useMediaLibrary';
import MediaEmptyState from './components/MediaEmptyState.vue';

const message = useMessage();
const { api, items, total, loading, loadingMore, permissionDenied, failed, thumbs, hasMore, reload, loadMore } =
  useMediaLibrary('image');

const query = ref('');
/** -1 = 查看器关闭。存**下标**而不是条目：左右翻页要的就是下标。 */
const viewerIndex = ref(-1);
const fullUrl = ref('');

const filtered = computed(() => {
  const q = query.value.trim().toLowerCase();
  return q ? items.value.filter((p) => p.name.toLowerCase().includes(q)) : items.value;
});

const viewerItem = computed(() => (viewerIndex.value >= 0 ? filtered.value[viewerIndex.value] : undefined));
const viewerThumb = computed(() => (viewerItem.value ? thumbs.value[viewerItem.value.id] || '' : ''));

const showEmpty = computed(
  () => !loading.value && (permissionDenied.value || failed.value || items.value.length === 0)
);

const headSub = computed(() => {
  if (permissionDenied.value) return '未授权';
  return total.value ? `共 ${total.value} 张` : '';
});

const detailRows = computed(() => {
  const p = viewerItem.value;
  if (!p) return [];
  const rows = [
    { label: '路径', value: p.path },
    { label: '大小', value: formatMediaSize(p.size) },
  ];
  if (p.width && p.height) rows.push({ label: '尺寸', value: `${p.width}×${p.height}` });
  rows.push({ label: '修改时间', value: formatMediaDate(p.date_modified) });
  return rows;
});

function openAt(i: number) {
  viewerIndex.value = i;
}

function close() {
  viewerIndex.value = -1;
  fullUrl.value = '';
}

function step(delta: number) {
  const next = viewerIndex.value + delta;
  if (next < 0 || next >= filtered.value.length) return;
  viewerIndex.value = next;
}

/**
 * 下标一变就换票据取原图。
 *
 * 取回来时再比一次下标：连续按方向键会让多个请求在飞，先发的可能后到 ——
 * 不比对就会把上一张的图盖到当前这张上。
 */
watch(viewerIndex, async (i) => {
  fullUrl.value = '';
  const item = i >= 0 ? filtered.value[i] : undefined;
  if (!item) return;
  try {
    const url = await fetchStreamUrl(api, item.path);
    if (viewerIndex.value === i) fullUrl.value = url;
  } catch (e: any) {
    message.error(e?.message || '获取图片失败');
  }
});

/** 键盘导航。看图是连续动作，鼠标点小箭头翻几十张太累。 */
function onKey(e: KeyboardEvent) {
  if (viewerIndex.value < 0) return;
  if (e.key === 'ArrowLeft') step(-1);
  else if (e.key === 'ArrowRight') step(1);
}

onMounted(() => {
  reload();
  window.addEventListener('keydown', onKey);
});
onUnmounted(() => window.removeEventListener('keydown', onKey));
</script>

<style scoped>
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

/* 方格墙：图片页不显示文件名（名字对看图没有帮助，缩略图本身就是标识），
   所以格子可以排得更密 */
.tile-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(140px, 1fr));
  gap: 8px;
}
.tile {
  position: relative;
  aspect-ratio: 1;
  padding: 0;
  border: none;
  border-radius: var(--radius-sm);
  overflow: hidden;
  background: var(--surface-elevated);
  cursor: pointer;
  transition:
    transform 0.15s ease,
    box-shadow 0.15s ease;
}
.tile:hover {
  transform: scale(1.02);
  box-shadow: 0 4px 14px rgba(0, 0, 0, 0.16);
}
.tile-img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}
.tile-fallback {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 100%;
  height: 100%;
  color: var(--text-muted);
}

.load-more {
  display: flex;
  justify-content: center;
  padding: 16px 0;
}

/* ── 查看器 ── */
.viewer {
  position: relative;
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 300px;
}
.viewer-img {
  max-width: 100%;
  max-height: 68vh;
  object-fit: contain;
  border-radius: var(--radius-md);
}
/* 缩略图顶位期间稍微压暗，让"这还不是原图"看得出来 */
.viewer-img.is-placeholder {
  filter: blur(1px) brightness(0.96);
}
.viewer-loading {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 300px;
}
/* 翻页钮浮在图两侧。自带深色底：它压在任意画面上，跟着主题走会在浅色图上消失 */
.nav-btn {
  position: absolute;
  top: 50%;
  transform: translateY(-50%);
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 36px;
  height: 36px;
  border: none;
  border-radius: 50%;
  background: rgba(0, 0, 0, 0.45);
  color: #fff;
  cursor: pointer;
  transition:
    background 0.15s,
    opacity 0.15s;
  z-index: 1;
}
.nav-btn:hover:not(:disabled) {
  background: rgba(0, 0, 0, 0.66);
}
.nav-btn:disabled {
  opacity: 0.25;
  cursor: not-allowed;
}
.nav-prev {
  left: 8px;
}
.nav-next {
  right: 8px;
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
  .tile-grid {
    grid-template-columns: repeat(auto-fill, minmax(100px, 1fr));
    gap: 6px;
  }
  /* 翻页钮在手机上要更大才点得到（44px 是可点区域的常见下限） */
  .nav-btn {
    width: 44px;
    height: 44px;
  }
  /* 路径一行放不下，标签与值改上下排 */
  .detail-row {
    flex-direction: column;
    gap: 2px;
  }
  .detail-value {
    text-align: left;
  }
}
</style>

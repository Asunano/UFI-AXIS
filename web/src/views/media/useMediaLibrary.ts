/**
 * 媒体列表的分页取数。视频 / 音乐 / 图片三页共用。
 *
 * 三页的差别只有 `kind` 与"怎么渲染一条"，而"翻页、去重、缩略图按需取、卸载时回收
 * blob URL"这四件事逐字相同 —— 各写一份的结果是其中一页忘了 revoke 就漏内存。
 *
 * 缩略图**按条目懒取**而不是随列表一次性全取：一页 60 条各发一个二进制请求，
 * 全部并发会把 core 的 QoS 许可打满。这里用一个固定宽度的并发闸门
 * （[THUMB_CONCURRENCY]）—— 之前是 `for` 循环里逐个 `await`，等于并发 1，
 * 一页封面要一条条慢慢冒出来。
 */
import { onUnmounted, ref } from 'vue';
import { useCancellableApi } from '@/composables/useCancellableApi';
import {
  createBlobUrlPool,
  fetchMediaPage,
  fetchThumbUrl,
  MEDIA_PAGE_SIZE,
  type MediaItem,
  type MediaKind,
} from './mediaShared';

/**
 * 同时在飞的缩略图请求数。
 *
 * 4 是"别把 core 的 QoS 打满"与"别一条条挤牙膏"之间的折中：浏览器对同源
 * HTTP/1.1 本身也只给 6 条连接，再开大也排不上。
 */
const THUMB_CONCURRENCY = 4;

export function useMediaLibrary(kind: MediaKind) {
  const api = useCancellableApi();
  const pool = createBlobUrlPool();

  const items = ref<MediaItem[]>([]);
  const total = ref(0);
  const loading = ref(false);
  const loadingMore = ref(false);
  /** 403：设备未授予该类型的媒体读取权限。与普通网络错误分开，界面文案完全不同。 */
  const permissionDenied = ref(false);
  const failed = ref(false);
  /** id → 缩略图 blob URL。没有该键 = 还没取到或取不到，渲染占位图标。 */
  const thumbs = ref<Record<number, string>>({});

  const hasMore = () => items.value.length < total.value;

  async function loadThumbs(list: MediaItem[]) {
    // id ≤ 0 = 媒体库里没有这一行（歌单里已失效的条目就是 id 0）。
    // 不过滤掉的话每一条都会发一次注定 404 的请求，还会把并发闸门占满。
    const pending = list.filter((item) => item.id > 0 && !thumbs.value[item.id]);
    let cursor = 0;
    // N 个 worker 共享一个游标：谁空了谁取下一条，天然保持"同时最多 N 条在飞"
    const worker = async () => {
      while (cursor < pending.length) {
        const item = pending[cursor++];
        if (!item) break;
        const url = await fetchThumbUrl(api, kind, item.id);
        if (url) thumbs.value[item.id] = pool.keep(url);
      }
    };
    await Promise.all(Array.from({ length: Math.min(THUMB_CONCURRENCY, pending.length) }, worker));
  }

  async function load(offset = 0) {
    if (offset === 0) loading.value = true;
    else loadingMore.value = true;
    try {
      const data = await fetchMediaPage(api, kind, offset, MEDIA_PAGE_SIZE);
      // useCancellableApi 取消在途请求时 data 为 null，按"无结果"处理（不算失败）
      if (!data) return;
      permissionDenied.value = false;
      failed.value = false;
      const page = data.items ?? [];
      if (offset === 0) {
        items.value = page;
      } else {
        // 去重：设备侧新增文件会让同一 offset 的窗口整体后移，翻页可能重复给出同一条
        const seen = new Set(items.value.map((i) => i.id));
        items.value = [...items.value, ...page.filter((i) => !seen.has(i.id))];
      }
      total.value = data.total ?? items.value.length;
      void loadThumbs(page);
    } catch (e: any) {
      if (e?.response?.status === 403) permissionDenied.value = true;
      else failed.value = true;
    } finally {
      loading.value = false;
      loadingMore.value = false;
    }
  }

  function reload() {
    // 换页重拉前先回收旧缩略图：不清的话切走再回来会把上一批 URL 一直挂着
    pool.releaseAll();
    thumbs.value = {};
    items.value = [];
    total.value = 0;
    load(0);
  }

  function loadMore() {
    if (loadingMore.value || !hasMore()) return;
    load(items.value.length);
  }

  onUnmounted(() => pool.releaseAll());

  // loadThumbs 一并暴露：歌单曲目不是这个 composable 拉的（走 /api/playlists/:id/items），
  // 但缩略图仍要用同一个并发闸门与同一份 thumbs 缓存 —— 另写一份会得到第二个 blob 池，
  // 卸载时漏回收其中一个。
  return {
    api,
    items,
    total,
    loading,
    loadingMore,
    permissionDenied,
    failed,
    thumbs,
    hasMore,
    load,
    reload,
    loadMore,
    loadThumbs,
  };
}

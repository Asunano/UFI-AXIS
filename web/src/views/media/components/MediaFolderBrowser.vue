<!--
  媒体库「文件夹视图」：照文件管理器那样按目录一层层走（视频页与图片页共用，2026-10-14）。

  ## 为什么是一个吃 kind 的共用组件
  视频页与图片页的文件夹视图**除了"一条怎么画"之外逐字相同**：同样的 `/browse` 取数、
  同样的 roots / parent / folders / items 四段语义、同样的面包屑与「上一级」、同样的
  "封面要另取 blob 再回收"。各写一份的结果是其中一页漏了 `pool.releaseAll()`，
  或者对"多个根"那条分支处理得不一样。两页的差别全部收在 `kind` 上：
  视频渲染 16:9 海报（带时长角标与「字幕 N」），图片渲染 1:1 方格。

  ## 与网格（海报墙 / 图片墙）是**两种视图**，不是"文件夹视图吃掉网格"
  与 app 的取舍一致：`MediaVideoScreen.kt:68-81` 是"一页两栏（首页 / 媒体库）"，
  首页是海报墙、媒体库是文件夹。web 侧把它落成**页头的一个视图切换**：
  默认仍然是原来那个网格，文件夹是并列的第二个入口（挂载在页面上，见各页的 `view`）。
  刻意不做成"文件夹视图替换掉网格"——那会让用户找不到原来的入口；
  也不做左右两栏并存——窄屏下两栏各只剩两三百像素，而且网格的数据源是
  `useMediaLibrary`（整库分页），没法跟着左边选中的目录过滤（那要改它的取数参数，
  不在本次改动范围内）。

  ## 文件夹图标 & 文件缩略图
  文件夹一行固定画 `FolderOutline`，不再用子目录里某张媒体图当封面 ——
  与音乐页的文件夹视图一致，避免“看起来像专辑/海报”的误导，也省掉每层目录的封面请求。
  只有本层的**文件**才走 `fetchThumbUrl(api, kind, id)` 取缩略图，共用同一个 blob 池。

  ## 为什么连这一层的 items 一起 emit 出去
  页面拿到的是"播放列表 / 翻页范围"。那个范围在文件夹视图里就是**当前这一层的文件**，
  不是页面网格里那一份：从文件夹点进去的第一个文件，在网格列表里可能压根不存在
  （它还在第 3 页没加载）。所以 `open` 带两个参数。

  ## 否决过的替代方案
  · **左右两栏（目录树 + 内容）**：见上。
  · **把 items 交给页面的 `useMediaLibrary`**：`/browse` 的 items 与 `/list` 的分页列表
    是两份数据（"这一层有什么" vs "整库第 N 页"），塞进同一个 items 会出现两个真源。
  · **把"选根"做成一个下拉**：`path` 为空时 core 回的就是"请先选一个根"
    （`path:''` + 非空 roots + 空 folders/items），那是一个**页面状态**而不是一个筛选器，
    列成可点的行比藏在下拉里更容易发现。
  · **自己缓存一层目录（返回时还原位置）**：父页面用 `v-if` 挂载本组件，切走即销毁，
    缓存得活在父页面里；而"切回来还在原目录"这件事已经由父页面"首次打开后改用 v-show"
    解决了（见各页的 `folderEverOpened`），组件自己再存一份就是第二个真源。
-->
<template>
  <section class="folder-browser">
    <div class="fb-bar">
      <n-button size="tiny" quaternary :disabled="!parent" :title="parent || '已经在根目录'" @click="goParent">
        上一级
      </n-button>
      <div class="fb-crumbs">
        <template v-for="(c, i) in crumbs" :key="c.path || '/root'">
          <span v-if="i > 0" class="fb-sep">/</span>
          <button
            type="button"
            class="fb-crumb"
            :class="{ 'is-current': i === crumbs.length - 1 }"
            :title="c.title"
            @click="go(c.path)"
          >
            {{ c.label }}
          </button>
        </template>
      </div>
      <n-button size="tiny" quaternary :loading="loading" @click="reload">刷新</n-button>
    </div>

    <div v-if="permissionDenied" class="fb-note">媒体权限未授权，无法浏览{{ kindLabel }}</div>
    <div v-else-if="failed" class="fb-note">
      <span class="fb-note-text">{{ errorText }}</span>
      <n-button size="tiny" quaternary @click="reload">重试</n-button>
    </div>

    <template v-else>
      <!-- 多根：core 的语义是"先让客户端选一个"，不是"这个目录是空的" -->
      <div v-if="showRoots" class="fb-list">
        <button v-for="r in roots" :key="r" type="button" class="fb-folder" :title="r" @click="go(r)">
          <span class="fb-cover fb-cover-fallback">
            <n-icon :size="18"><FolderOutline /></n-icon>
          </span>
          <span class="fb-folder-name">{{ r }}</span>
        </button>
      </div>

      <template v-else>
        <div v-if="folders.length" class="fb-list">
          <button
            v-for="f in folders"
            :key="f.path"
            type="button"
            class="fb-folder"
            :title="f.path"
            @click="go(f.path)"
          >
            <span class="fb-cover fb-cover-fallback">
              <n-icon :size="18"><FolderOutline /></n-icon>
            </span>
            <span class="fb-folder-name">{{ f.name }}</span>
            <span class="fb-folder-count">{{ f.count }} 个</span>
          </button>
        </div>

        <div v-if="items.length" class="fb-items" :class="kind === 'video' ? 'is-video' : 'is-image'">
          <button v-for="it in items" :key="it.id" type="button" class="fb-item" :title="it.name" @click="open(it)">
            <span class="fb-thumb">
              <img v-if="thumbs[it.id]" :src="thumbs[it.id]" :alt="it.name" loading="lazy" class="fb-thumb-img" />
              <span v-else class="fb-thumb-fallback">
                <n-icon v-if="kind === 'video'" :size="22"><VideocamOutline /></n-icon>
                <n-icon v-else :size="22"><ImageOutline /></n-icon>
              </span>
              <span v-if="kind === 'video' && it.duration_ms" class="fb-thumb-badge">
                {{ formatDuration(it.duration_ms) }}
              </span>
              <!-- 字幕角标与时长角标同处一角会叠在一起：字幕挪到左上 -->
              <span v-if="kind === 'video' && it.subtitle_count" class="fb-thumb-cc">
                字幕 {{ it.subtitle_count }} 条
              </span>
            </span>
            <span v-if="kind === 'video'" class="fb-item-name">{{ it.name }}</span>
          </button>
        </div>

        <div v-if="loading && !folders.length && !items.length" class="fb-loading"><n-spin size="small" /></div>
        <div v-else-if="!folders.length && !items.length" class="fb-note">这个文件夹里没有{{ kindLabel }}</div>
      </template>
    </template>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue';
import { FolderOutline, ImageOutline, VideocamOutline } from '@vicons/ionicons5';
import { useCancellableApi } from '@/composables/useCancellableApi';
import {
  createBlobUrlPool,
  fetchThumbUrl,
  formatDuration,
  type MediaItem,
  type MediaKind,
  type MediaSort,
} from '../mediaShared';
import { MEDIA_KIND_LABELS } from '../mediaConfigShared';
import {
  fetchMediaBrowse,
  isMediaPermissionDenied,
  mediaBrowseErrorText,
  type MediaBrowseFolder,
} from './mediaBrowseShared';

const props = defineProps<{
  /** 浏览哪一类。两页各传一个字面量，组件内不变。 */
  kind: MediaKind;
  /** 页面的当前排序。`/browse` 与 `/list` 认同一组 `sort`/`order`，直接透传。 */
  sort: MediaSort;
  /** 页面重新扫描后自增；用来让浏览视图也补拉一次（core 是异步扫描）。 */
  refreshKey?: number;
}>();

/** 唯一出口。第二个参数是**当前这一层的 items**，用途见文件头。 */
const emit = defineEmits<{ (e: 'open', item: MediaItem, list: MediaItem[]): void }>();

/**
 * 同时在飞的封面请求数。与 `useMediaLibrary.THUMB_CONCURRENCY` /
 * `GroupBrowserPanel` 取同一个值（4）：浏览器对同源 HTTP/1.1 只给 6 条连接，
 * 开再大也排不上；并发 1 会让封面一条条挤牙膏。
 */
const THUMB_CONCURRENCY = 4;

const api = useCancellableApi();
const pool = createBlobUrlPool();

/** 请求中的目录。面包屑画的是它，失败时会被回滚成 [loadedPath]。 */
const path = ref('');
/** 当前**已经渲染出来**的那一层。与 [path] 分开：请求失败时两者会短暂不一致。 */
const loadedPath = ref('');
const roots = ref<string[]>([]);
const parent = ref<string | null>(null);
const folders = ref<MediaBrowseFolder[]>([]);
const items = ref<MediaItem[]>([]);
const loading = ref(false);
/** 403 且带 `permission`：媒体权限未授权。与"目录不在扫描范围内"分开，文案完全不同。 */
const permissionDenied = ref(false);
const failed = ref(false);
const errorText = ref('');
/** MediaStore id → 封面 blob URL。子目录的 `cover_id` 与文件的 `id` 是同一个 id 空间，共用一个 map。 */
const thumbs = ref<Record<number, string>>({});

/**
 * 取数代数。连点面包屑 / 换目录会有两轮请求在飞，先发后到时靠它丢弃过期结果。
 * 它同时是"组件已卸载"的守卫：卸载后 `useCancellableApi` 让在途请求 resolve 成
 * undefined，但**已经回来**的那一条仍可能走到 `pool.keep()` —— 往一个已经
 * `releaseAll()` 的池子里塞 URL 就是泄漏（照 `GroupBrowserPanel` 的做法）。
 */
let seq = 0;

const kindLabel = computed(() => MEDIA_KIND_LABELS[props.kind]);
/**
 * 是否处于"请先选一个根"状态。
 *
 * 判据是 `path` 为空且 roots 不止一个：core 侧 `target = requested ?: roots.singleOrNull()`，
 * 只有一个根时它直接进那一个（回的是那个根的 listing），所以"path 为空 + 多根"
 * 是唯一会走到"选根"分支的组合。
 */
const showRoots = computed(() => !path.value && roots.value.length > 1);

/**
 * 面包屑。**从根开始画**，不画 `/storage/emulated/0` 这些前缀 ——
 * 那些层级对用户没有意义，而且根之上 core 本来就不让浏览（403）。
 */
const crumbs = computed<Array<{ label: string; title: string; path: string }>>(() => {
  const cur = path.value;
  if (!cur) return [{ label: '根目录', title: '选择要浏览的根目录', path: '' }];
  // 命中的根取**最长**的那个：根之间可能是包含关系（配了 /a 又配了 /a/b）
  const root =
    roots.value.filter((r) => cur === r || cur.startsWith(`${r}/`)).sort((a, b) => b.length - a.length)[0] ?? '';
  const out: Array<{ label: string; title: string; path: string }> = [
    { label: root ? root.split('/').filter(Boolean).pop() || root : '根目录', title: root || '根目录', path: root },
  ];
  if (root) {
    let acc = root;
    for (const seg of cur.slice(root.length).split('/').filter(Boolean)) {
      acc = `${acc}/${seg}`;
      out.push({ label: seg, title: acc, path: acc });
    }
  }
  return out;
});

async function loadThumbs(s: number) {
  // 文件夹固定用文件夹图标，不再取子目录封面；只为本层文件取缩略图
  const ids: number[] = [];
  for (const it of items.value) {
    if (it.id > 0 && !thumbs.value[it.id]) ids.push(it.id);
  }
  let cursor = 0;
  // N 个 worker 共享一个游标：谁空了谁取下一条，天然保持"同时最多 N 条在飞"
  const worker = async () => {
    while (cursor < ids.length) {
      const id = ids[cursor++];
      if (id === undefined) break;
      const url = await fetchThumbUrl(api, props.kind, id);
      if (s !== seq) {
        // 期间换了目录 / 组件已卸载：这个 URL 不能进池子（池子可能已经清空过），自己回收
        if (url) URL.revokeObjectURL(url);
        return;
      }
      if (url) thumbs.value[id] = pool.keep(url);
    }
  };
  await Promise.all(Array.from({ length: Math.min(THUMB_CONCURRENCY, ids.length) }, worker));
}

async function load() {
  const s = ++seq;
  loading.value = true;
  // 换目录前先回收上一层的封面：不回收的话在目录树里走十几层会把每层的封面都挂着
  pool.releaseAll();
  thumbs.value = {};
  permissionDenied.value = false;
  failed.value = false;
  errorText.value = '';
  try {
    const resp = await fetchMediaBrowse(api, props.kind, path.value, props.sort);
    // 取消（卸载 / 已换目录）时 data 为 undefined：按"没有结果"处理，**不算失败**
    if (!resp || s !== seq) return;
    // 一律采用**回读**的 path：core 回的是 canonicalPath，与请求值可能不同（去尾斜杠/解符号链接）
    path.value = resp.path ?? '';
    loadedPath.value = path.value;
    roots.value = resp.roots ?? [];
    parent.value = resp.parent ?? null;
    folders.value = resp.folders ?? [];
    items.value = resp.items ?? [];
    void loadThumbs(s);
  } catch (e: any) {
    if (s !== seq) return;
    // 目录没进去，面包屑也要退回去 —— 否则会出现"面包屑指着 B、列表还是 A"的错位
    path.value = loadedPath.value;
    if (isMediaPermissionDenied(e)) permissionDenied.value = true;
    else {
      failed.value = true;
      errorText.value = mediaBrowseErrorText(e);
    }
  } finally {
    if (s === seq) loading.value = false;
  }
}

/** 换排序 = 换一次重拉（core 侧 `/browse` 的 sortListItems 与 `/list` 同一个）。 */
watch(
  () => `${props.sort.key}-${props.sort.order}`,
  () => void load()
);
/** 页面重新扫描后补拉（core 是异步扫描，页面那边已经延迟过了）。 */
watch(
  () => props.refreshKey,
  () => void load()
);

function reload() {
  void load();
}

function go(target: string) {
  if (target === path.value) return;
  path.value = target;
  void load();
}

function goParent() {
  const p = parent.value;
  if (!p) return;
  go(p);
}

function open(item: MediaItem) {
  emit('open', item, items.value);
}

onMounted(() => void load());
onUnmounted(() => pool.releaseAll());
</script>

<style scoped>
.folder-browser {
  display: flex;
  flex-direction: column;
  gap: 12px;
  min-width: 0;
}

/* 工具条：上一级 / 面包屑 / 刷新。面包屑吃满中间，路径长时自己横向滚 */
.fb-bar {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}
.fb-crumbs {
  display: flex;
  align-items: center;
  gap: 2px;
  flex: 1;
  min-width: 0;
  overflow-x: auto;
  white-space: nowrap;
}
.fb-crumb {
  padding: 2px 4px;
  border: none;
  border-radius: var(--radius-sm);
  background: transparent;
  color: var(--text-secondary);
  font: inherit;
  font-size: var(--font-sm);
  cursor: pointer;
  max-width: 220px;
  overflow: hidden;
  text-overflow: ellipsis;
}
.fb-crumb:hover {
  background: var(--surface-hover);
}
.fb-crumb.is-current {
  color: var(--text-primary);
  font-weight: 600;
  cursor: default;
}
.fb-sep {
  color: var(--text-muted);
  font-size: var(--font-sm);
}

/* 子目录：**卡片网格**而不是整行长条（2026-10-22 独立适配）。
   行条在桌面上要横跨整个内容区、鼠标移动距离很长，也看不出"这是一个可进入的目录"；
   卡片网格与下面的文件缩略图同为网格，视觉上是同一层级的两种对象。
   宽度用 auto-fill：内容区宽（桌面侧栏展开 220）自动 3~4 列，平板 2 列，手机 1 列。 */
.fb-list {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(240px, 1fr));
  gap: 8px;
}
.fb-folder {
  display: flex;
  align-items: center;
  gap: 10px;
  width: 100%;
  min-width: 0;
  padding: 8px 10px;
  border: 1px solid var(--border-subtle);
  border-radius: var(--radius-md);
  background: var(--card-bg);
  color: inherit;
  font: inherit;
  text-align: left;
  cursor: pointer;
  transition:
    border-color 0.15s,
    background 0.15s;
}
.fb-folder:hover {
  border-color: var(--accent-color);
  background: var(--surface-hover);
}
.fb-folder:focus-visible {
  outline: 2px solid var(--accent-ring);
  outline-offset: 1px;
}
.fb-cover {
  width: 40px;
  height: 40px;
  flex-shrink: 0;
  border-radius: var(--radius-sm);
  object-fit: cover;
  background: var(--surface-elevated);
}
.fb-cover-fallback {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  color: var(--text-muted);
}
.fb-folder-name {
  flex: 1;
  min-width: 0;
  font-size: var(--font-base);
  color: var(--text-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.fb-folder-count {
  flex-shrink: 0;
  font-size: var(--font-xs);
  color: var(--text-muted);
  font-variant-numeric: tabular-nums;
}

/* 本层的媒体文件。视频按 16:9 铺（与海报墙同一个比例），图片按 1:1 铺 */
.fb-items {
  display: grid;
  gap: 12px;
}
.fb-items.is-video {
  grid-template-columns: repeat(auto-fill, minmax(180px, 1fr));
}
.fb-items.is-image {
  grid-template-columns: repeat(auto-fill, minmax(120px, 1fr));
  gap: 8px;
}
.fb-item {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 0;
  border: none;
  background: transparent;
  color: inherit;
  font: inherit;
  text-align: left;
  cursor: pointer;
  min-width: 0;
}
.fb-thumb {
  position: relative;
  display: block;
  width: 100%;
  border-radius: var(--radius-sm);
  overflow: hidden;
  background: var(--surface-elevated);
}
.fb-items.is-video .fb-thumb {
  aspect-ratio: 16 / 9;
}
.fb-items.is-image .fb-thumb {
  aspect-ratio: 1;
}
.fb-thumb-img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}
.fb-thumb-fallback {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 100%;
  height: 100%;
  color: var(--text-muted);
}
/* 角标压在缩略图上，必须自带深色底才在任何画面上都读得出来。
   ui-baseline-allow: 浮在缩略图上的角标，必须自带深底白字，两种主题下一致 */
.fb-thumb-badge {
  position: absolute;
  right: 4px;
  bottom: 4px;
  padding: 0 4px;
  border-radius: 3px;
  background: rgba(0, 0, 0, 0.72);
  color: #fff;
  font-size: var(--font-xs);
  line-height: 1.5;
  font-variant-numeric: tabular-nums;
}
/* 字幕角标用主色（它是"有额外内容"的正面标记，不是浮在图上的黑底读数），
   压在左上角避开时长角标 */
.fb-thumb-cc {
  position: absolute;
  left: 4px;
  top: 4px;
  padding: 0 4px;
  border-radius: 3px;
  background: var(--accent-color);
  color: var(--on-accent);
  font-size: var(--font-xs);
  line-height: 1.5;
}
.fb-item-name {
  font-size: var(--font-sm);
  color: var(--text-primary);
  line-height: 1.4;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.fb-loading {
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px 0;
}
/* 空目录 / 无权限 / 失败：三种都走这里，不占额外高度 */
.fb-note {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
  padding: 20px 10px;
  font-size: var(--font-sm);
  line-height: 1.6;
  color: var(--text-muted);
  text-align: center;
}
.fb-note-text {
  word-break: break-word;
}

@media (max-width: 768px) {
  /* 手机：上一级 / 刷新 留在第一行，面包屑独占第二行 —— 三者挤一行时
     「上一级」和面包屑会互相压到只剩几个字。 */
  .fb-bar {
    flex-wrap: wrap;
    row-gap: 6px;
  }
  .fb-crumbs {
    order: 3;
    flex-basis: 100%;
    padding-bottom: 2px;
  }
  .fb-list {
    grid-template-columns: 1fr;
    gap: 6px;
  }
  .fb-folder {
    padding: 8px;
  }
  .fb-items.is-video {
    grid-template-columns: repeat(auto-fill, minmax(140px, 1fr));
    gap: 8px;
  }
  .fb-items.is-image {
    grid-template-columns: repeat(auto-fill, minmax(96px, 1fr));
    gap: 6px;
  }
  .fb-crumb {
    max-width: 140px;
  }
}
</style>

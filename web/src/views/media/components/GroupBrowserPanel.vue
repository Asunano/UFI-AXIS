<!--
  音乐页「分组浏览」面板：把整个音乐库按 专辑 / 歌手 / 文件夹 列成可滚动的卡片列表。

  ## 为什么这是一个自取数的面板，而不是像 PlaylistPanel 那样纯受控
  歌单是 core 里的一份**可变数据**（新建 / 重命名 / 加歌），页面为了别的原因已经持有它，
  面板再取一次就会出现两个真源。分组不是：它是 `/groups` 的一次聚合快照，
  除了这个面板没有任何人需要它，交给页面持有只会多一份"什么时候该重拉"的负担。
  所以这里自己取数，只把"用户点开了哪一组"emit 出去 —— 面板的边界是"分组集合 + 加载态
  全在内部，页面只需要知道用户点了什么"。

  ## 为什么只有一个 emit
  一开始按"单击 = 选中高亮 / 双击或右侧小按钮 = 进入"设计过，但那要求用户先学会
  "选中"和"进入"是两件事 —— 而在这个面板里点一张专辑的唯一意图就是"看这张专辑里的歌"。
  于是单击即 `open`，`activeKey` / `activeBy` 退化成纯粹的**染色依据**
  （"当前队列来源是哪个分组"），不再参与任何交互语义。

  ## 为什么缩略图闸门在这里又写了一份（没有复用 useMediaLibrary.loadThumbs）
  `useMediaLibrary` 是给"平铺列表 + 分页"用的：它持有 items / total / hasMore，
  缩略图按条目 id 缓存。分组是另一套取数（一次全量、封面键是 `cover_id`、没有分页），
  把分组硬塞进 `useMediaLibrary('audio')` 会得到第二个 items 真源和第二个 blob 池 ——
  卸载时漏回收其中一个就是内存泄漏。所以这里只**借它的做法**（4 个 worker 共享游标），
  自己持有 `createBlobUrlPool()` 并在 `onUnmounted` 里 `releaseAll()`。

  ## 为什么用普通 div 滚动而不是 n-scrollbar
  列表要 `flex:1; min-height:0; overflow-y:auto` 吃满外层 flex 容器给的高度。
  套 `n-scrollbar` 时它内部还有一层需要撑高的包裹，得靠 `:deep()` 打进去
  （PlaylistPanel 就是这么干的），凭空多一层高度耦合；这里只要一条原生 overflow 就够，
  不需要自定义滚动条外观。

  ## 否决过的替代方案
  · **把分组做成独立路由**：这一页只有一个 `<audio>` 实例，换路由会把它卸掉，
    正在听的歌会断 —— 与 PlaylistPanel 同一个理由，所以是面板不是页面。
  · **分组列与队列列并排**：页面已经有三列（歌单 / 队列 / 歌词），再加一列会让每列
    只剩两三百像素。挂载方式交给父组件（它可以复用歌单列那一格做切换）。
  · **一次性把每个分组的封面都取回来**：几百个分组就是几百个二进制请求，
    会把 core 的 QoS 许可打满，而且绝大多数分组在视口外根本不会被看到。
-->
<template>
  <section class="group-panel sub-panel" :class="{ 'is-inline': inline }">
    <div class="gp-head">
      <!-- 页签只有三个且互斥，用一排 n-button 而不是 n-tabs：tabs 会再带一层
           下划线 + 内容区包裹，而这里下面就是列表，多一层包裹就多一处高度耦合 -->
      <div class="gp-tabs">
        <n-button
          v-for="opt in BY_OPTIONS"
          :key="opt"
          size="tiny"
          :type="by === opt ? 'primary' : 'default'"
          :quaternary="by !== opt"
          @click="switchBy(opt)"
        >
          {{ GROUP_BY_LABELS[opt] }}
        </n-button>
      </div>
      <span v-if="total > 0" class="gp-total">{{ totalText }}</span>
    </div>

    <div class="gp-list">
      <div v-if="loading && groups.length === 0" class="gp-loading">
        <n-spin size="small" />
      </div>

      <template v-else>
        <!-- 一行 = 一张封面 + 标题/副标题 + 条数。整行都是按钮：
             只让标题可点会让右边一大片看着能点却点不动。 -->
        <button
          v-for="g in visibleGroups"
          :key="g.key"
          type="button"
          class="gp-item"
          :class="{ 'is-active': isActive(g) }"
          :disabled="!g.key"
          :title="g.key ? g.title : noKeyNote"
          @click="emit('open', g, by)"
        >
          <img v-if="by !== 'folder' && thumbs[g.cover_id]" :src="thumbs[g.cover_id]" alt="" class="gp-cover" />
          <span v-else class="gp-cover gp-cover-fallback" :class="{ 'is-folder': by === 'folder' }">
            <n-icon :size="16">
              <FolderOutline v-if="by === 'folder'" />
              <MusicalNotesOutline v-else />
            </n-icon>
          </span>
          <span class="gp-text">
            <span class="gp-title">{{ g.title }}</span>
            <span v-if="g.subtitle" class="gp-sub" :title="g.subtitle">{{ g.subtitle }}</span>
          </span>
          <!-- 量词不能省：裸数字「37」在这一行里与序号、时长长得一样，读不出它是什么 -->
          <span class="gp-count">{{ g.count }} 项</span>
        </button>
      </template>

      <div v-if="permissionDenied" class="gp-note">媒体权限未授权，无法读取音乐库</div>
      <div v-else-if="failed" class="gp-note">
        <span class="gp-note-text">{{ errorText }}</span>
        <n-button size="tiny" quaternary @click="reload">重试</n-button>
      </div>
      <div v-else-if="!loading && visibleGroups.length === 0" class="gp-note">
        {{ query ? `没有匹配「${query}」的${GROUP_BY_LABELS[by]}` : emptyText }}
      </div>
    </div>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue';
import { FolderOutline, MusicalNotesOutline } from '@vicons/ionicons5';
import { useCancellableApi } from '@/composables/useCancellableApi';
import { createBlobUrlPool, fetchThumbUrl } from '../mediaShared';
import { fetchAudioGroups, GROUP_BY_LABELS, groupErrorText, type AudioGroup, type MediaGroupBy } from '../mediaGroups';

const props = defineProps<{
  /** 当前队列来源的分组 key。**只用于高亮**，不参与取数（维度由面板自己的页签决定）。 */
  activeKey: string;
  /** 当前队列来源的分组维度，与 `activeKey` 一起决定染哪一行。 */
  activeBy: MediaGroupBy;
  /** 从外部 chip 打开时指定初始维度（专辑/艺人/文件夹） */
  initialBy?: MediaGroupBy;
  /** 直接铺在音乐页列表区时隐藏面板头的页签（页签已由页面顶部的分类承担）。 */
  inline?: boolean;
  /** 音乐页搜索框的关键词；按组名/副标题过滤。 */
  query?: string;
}>();

/** 唯一的出口：进入某个分组的曲目列表。见文件头"为什么只有一个 emit"。 */
const emit = defineEmits<{
  (e: 'open', group: AudioGroup, by: MediaGroupBy): void;
}>();

/** 页签顺序写死：维度只有三个，且「专辑 → 歌手 → 文件夹」是从常用到不常用。 */
const BY_OPTIONS: MediaGroupBy[] = ['album', 'artist', 'folder'];

/** 总数文案的量词：「共 37 张专辑」比「共 37 个专辑」像人话。 */
const TOTAL_UNITS: Record<MediaGroupBy, string> = { album: '张专辑', artist: '位歌手', folder: '个文件夹' };

/**
 * 同时在飞的封面请求数。
 *
 * 与 `useMediaLibrary.THUMB_CONCURRENCY` 取同一个值（4）：浏览器对同源 HTTP/1.1
 * 本身只给 6 条连接，再开大也排不上，而并发 1 会让封面一条条挤牙膏。
 */
const THUMB_CONCURRENCY = 4;

const api = useCancellableApi();
const pool = createBlobUrlPool();

/**
 * 面板自己持有的维度。
 *
 * 初值取 `props.activeBy`：父组件已经打开过某个分组时，面板一进来就停在同一个维度上。
 * 之后**刻意不跟随 props 变化** —— 用户正在翻歌手，父组件因为别的原因改了 activeBy
 * 就把页签拽走，会让人正在看的那一列凭空消失。
 */
const by = ref<MediaGroupBy>(props.initialBy ?? props.activeBy);

const groups = ref<AudioGroup[]>([]);
const total = ref(0);
const loading = ref(false);
/** 403：设备未授予音频读取权限。与普通网络错误分开，界面文案完全不同。 */
const permissionDenied = ref(false);
const failed = ref(false);
const errorText = ref('');
/** `cover_id` → 封面 blob URL。没有该键 = 还没取到或取不到，渲染占位图标。 */
const thumbs = ref<Record<number, string>>({});

/**
 * 取数代数。
 *
 * 切页签 / 点重试会并发着两轮请求，先发后到时靠它丢弃过期结果
 * （照 `MediaAudioView.loadPlaylistTracks`）。它同时是"组件已卸载"的守卫：
 * 卸载后 `useCancellableApi` 会让在途请求 resolve 成 undefined，但**已经回来**的那一条
 * 仍可能走到 `pool.keep()` —— 往一个已经 `releaseAll()` 的池子里塞 URL 就是泄漏。
 */
let seq = 0;

const totalText = computed(() => (total.value > 0 ? `共 ${total.value} ${TOTAL_UNITS[by.value]}` : ''));
const emptyText = computed(() => `没有扫到任何${GROUP_BY_LABELS[by.value]}`);
/** 搜索过滤只在展示层做：分组集合本身是一次全量快照，过滤不需要回 core。 */
const visibleGroups = computed(() => {
  const q = (props.query ?? '').trim().toLowerCase();
  if (!q) return groups.value;
  return groups.value.filter((g) => g.title.toLowerCase().includes(q) || (g.subtitle ?? '').toLowerCase().includes(q));
});
/** 空 key 分组的禁用说明。folder 维度的 key 不会为空（core 直接跳过），只对另两个维度有意义。 */
const noKeyNote = computed(() => `这一组没有${GROUP_BY_LABELS[by.value]}标签，不能作为筛选条件`);

/**
 * 该不该给这一行染色。
 *
 * `key` 为空串的组不参与高亮：父组件传空 `activeKey` 的语义是"当前队列来源不是任何分组"，
 * 与"来源是那个空 key 的未知专辑组"区分不开 —— 宁可不染，也不要染错一行。
 */
function isActive(g: AudioGroup): boolean {
  return !!g.key && props.activeBy === by.value && props.activeKey === g.key;
}

async function loadThumbs(list: AudioGroup[], s: number) {
  // cover_id ≤ 0 = 组内没有可用条目（core 用 0 表示"没有"，_ID 从 1 开始）。
  // 不过滤掉的话每条都会发一次注定 404 的请求，还会把并发闸门占满
  // —— 与 useMediaLibrary.loadThumbs 里 item.id > 0 那条守卫同一理由。
  const pending = list.filter((g) => by.value !== 'folder' && g.cover_id > 0 && !thumbs.value[g.cover_id]);
  let cursor = 0;
  // N 个 worker 共享一个游标：谁空了谁取下一条，天然保持"同时最多 N 条在飞"
  const worker = async () => {
    while (cursor < pending.length) {
      const g = pending[cursor++];
      if (!g) break;
      const url = await fetchThumbUrl(api, 'audio', g.cover_id);
      // 期间切了页签 / 组件已卸载：这个 URL 不能进池子（池子可能已经清空过），自己回收
      if (s !== seq) {
        if (url) URL.revokeObjectURL(url);
        return;
      }
      if (url) thumbs.value[g.cover_id] = pool.keep(url);
    }
  };
  await Promise.all(Array.from({ length: Math.min(THUMB_CONCURRENCY, pending.length) }, worker));
}

async function reload() {
  const s = ++seq;
  loading.value = true;
  // 换维度 / 重试前先回收旧封面：不回收的话来回切页签会把三份列表的封面一直挂着
  pool.releaseAll();
  thumbs.value = {};
  groups.value = [];
  total.value = 0;
  permissionDenied.value = false;
  failed.value = false;
  errorText.value = '';
  try {
    const resp = await fetchAudioGroups(api, by.value);
    if (s !== seq) return; // 先发后到，丢弃过期结果
    // useCancellableApi 取消在途请求时为 null，按"无结果"处理（不算失败，也不清空已有列表）
    if (!resp) return;
    groups.value = resp.groups ?? [];
    total.value = resp.total ?? groups.value.length;
    void loadThumbs(groups.value, s);
  } catch (e: any) {
    if (s !== seq) return;
    if (e?.response?.status === 403) permissionDenied.value = true;
    else {
      failed.value = true;
      errorText.value = groupErrorText('加载音乐分组', e);
    }
  } finally {
    if (s === seq) loading.value = false;
  }
}

// 外部的「专辑 / 艺人 / 文件夹」chip 通过 initialBy 指定维度
watch(
  () => props.initialBy,
  (v) => {
    if (v && v !== by.value) switchBy(v);
  }
);

function switchBy(next: MediaGroupBy) {
  if (next === by.value) return;
  by.value = next;
  void reload();
}

onMounted(() => {
  void reload();
});
onUnmounted(() => pool.releaseAll());
</script>

<style scoped>
/* 面板自己吃满外层给的高度（父组件是 flex / grid 容器），`max-height` 一律交给外层。
   `min-height: 0` 缺了的话 flex 子项会以内容高度为下限，几百个分组会把布局一路撑长。 */
.group-panel {
  display: flex;
  flex-direction: column;
  min-height: 0;
  min-width: 0;
  height: 100%;
}
/* 直接铺在音乐页列表区：去掉抽屉/卡片外观和内部页签，只留可滚动的分组行。
   页签与总数由页面顶部的分类栏和列表头承担，避免同一屏出现两套切换。 */
.group-panel.is-inline {
  background: transparent;
  border: 0;
  border-radius: 0;
  box-shadow: none;
  padding: 0;
}
.group-panel.is-inline .gp-head {
  display: none;
}

.gp-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 8px;
  flex: 0 0 auto;
}
.gp-tabs {
  display: flex;
  gap: 4px;
  min-width: 0;
}
.gp-total {
  flex-shrink: 0;
  font-size: 11px;
  color: var(--text-muted);
  font-variant-numeric: tabular-nums;
}

/* 列表：这一列里唯一可滚动的东西 */
.gp-list {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
}

.gp-loading {
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px 0;
}

/* 一行 = 可点区域。hover / 选中态染整行 —— 只染文字会在两侧留下没上色的缺口 */
.gp-item {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  padding: 6px 8px;
  border: none;
  border-radius: var(--radius-sm);
  background: transparent;
  cursor: pointer;
  text-align: left;
  font: inherit;
  color: inherit;
  min-width: 0;
}
.gp-item:hover:not(:disabled) {
  background: var(--surface-hover);
}
.gp-item.is-active {
  background: var(--accent-color-light);
}
.gp-item.is-active .gp-title {
  color: var(--accent-color);
  font-weight: 600;
}
/* 空 key 的分组（未知专辑 / 未知歌手）不可点：它的 key 送回 /list 会被当成
   "没传筛选"而返回整库，点进去看到的会是全部歌曲 —— 那比不给点更误导。
   压暗 + 默认光标，与"文件已失效"那一行的处理同一种语言。 */
.gp-item:disabled {
  cursor: default;
}
.gp-item:disabled .gp-title,
.gp-item:disabled .gp-sub,
.gp-item:disabled .gp-count {
  color: var(--text-muted);
}

.gp-cover {
  width: 36px;
  height: 36px;
  flex-shrink: 0;
  border-radius: var(--radius-sm);
  object-fit: cover;
  background: var(--surface-elevated);
}
.gp-cover-fallback {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  color: var(--text-muted);
}
.gp-text {
  display: flex;
  flex-direction: column;
  gap: 1px;
  flex: 1;
  min-width: 0;
}
.gp-title {
  font-size: 13px;
  color: var(--text-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.gp-sub {
  font-size: 11px;
  color: var(--text-muted);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.gp-count {
  flex-shrink: 0;
  font-size: 11px;
  color: var(--text-muted);
  font-variant-numeric: tabular-nums;
}

/* 空列表 / 无权限 / 失败：三种都在列表内部，不占额外高度 */
.gp-note {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
  padding: 16px 10px;
  font-size: 12px;
  line-height: 1.6;
  color: var(--text-muted);
  text-align: center;
}
.gp-note-text {
  word-break: break-word;
}
</style>

<!--
  歌单面板（音乐页主区最左一列）。

  ## 为什么是面板而不是独立路由
  这一页只有一个 `<audio>` 实例，换路由会把它卸掉 —— 正在听的歌会断。做成同屏的一列之后
  「选歌单」和「听歌」是同一个页面的两种视角，切来切去不打断播放。

  ## 纯受控
  自己不取数、不写操作，全部 emit 上抛（与 `views/tasks` 的 `TaskCard` 同一边界）。
  歌单集合是 core 的数据，取数与重拉的时机由页面统一管，拆到组件里会出现两个真源。
-->
<template>
  <section class="pl-panel sub-panel">
    <div class="pl-head">
      <span class="pl-title">歌单</span>
      <n-button size="tiny" quaternary type="primary" @click="emit('create')">新建</n-button>
    </div>

    <n-spin :show="loading && playlists.length === 0" class="pl-spin">
      <n-scrollbar class="pl-scroll">
        <!-- 「全部音乐」是一个固定项而不是歌单：它代表"不筛歌单"，所以没有重命名/删除 -->
        <button
          type="button"
          class="pl-item"
          :class="{ 'is-active': activeId === null }"
          @click="emit('select', null)"
        >
          <span class="pl-item-name">全部音乐</span>
        </button>

        <div v-for="p in playlists" :key="p.id" class="pl-row" :class="{ 'is-active': activeId === p.id }">
          <button type="button" class="pl-item pl-item-grow" @click="emit('select', p.id)">
            <span class="pl-item-name" :title="p.name">{{ p.name }}</span>
            <span class="pl-item-count">{{ p.count }}</span>
          </button>
          <n-dropdown
            trigger="click"
            :options="rowOptions"
            @select="(k: string) => onRowAction(k, p)"
          >
            <n-button size="tiny" quaternary class="pl-more" aria-label="更多操作">⋯</n-button>
          </n-dropdown>
        </div>

        <div v-if="!loading && playlists.length === 0" class="pl-empty">
          还没有歌单，点右上角「新建」
        </div>
      </n-scrollbar>
    </n-spin>
  </section>
</template>

<script setup lang="ts">
import type { PlaylistEntry } from '../playlistShared';

const props = defineProps<{
  playlists: PlaylistEntry[];
  /** 当前选中的歌单 id；null = 全部音乐。 */
  activeId: string | null;
  loading: boolean;
}>();
// 模板里直接用解构出来的 props 名，这里显式引一次以免 noUnusedLocals 报错
void props;

const emit = defineEmits<{
  (e: 'select', id: string | null): void;
  (e: 'create'): void;
  (e: 'rename', entry: PlaylistEntry): void;
  (e: 'remove', entry: PlaylistEntry): void;
}>();

const rowOptions = [
  { label: '重命名', key: 'rename' },
  { label: '删除歌单', key: 'remove' },
];

function onRowAction(key: string, entry: PlaylistEntry) {
  if (key === 'rename') emit('rename', entry);
  else if (key === 'remove') emit('remove', entry);
}
</script>

<style scoped>
.pl-panel {
  display: flex;
  flex-direction: column;
  min-width: 0;
  min-height: 0;
}
.pl-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 8px;
  flex: 0 0 auto;
}
.pl-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--text-primary);
}
/* 同 .queue-spin：n-spin 会包一层 div，不显式撑满里面的 scrollbar 拿不到高度 */
.pl-spin {
  flex: 1;
  min-height: 0;
}
.pl-spin :deep(.n-spin-content) {
  height: 100%;
}
.pl-scroll {
  height: 100%;
}

/* 一行 = 可点区域 + 右侧「⋯」。选中态染到整行（含「⋯」那一格），
   只染左边按钮会在行尾留一块没上色的缺口。 */
.pl-row {
  display: flex;
  align-items: center;
  border-radius: var(--radius-sm);
}
.pl-row:hover,
.pl-item:hover {
  background: var(--surface-hover);
}
.pl-row.is-active,
.pl-item.is-active {
  background: var(--accent-color-light);
}
.pl-row.is-active .pl-item-name,
.pl-item.is-active .pl-item-name {
  color: var(--accent-color);
  font-weight: 600;
}
.pl-item {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  padding: 8px 10px;
  border: none;
  border-radius: var(--radius-sm);
  background: transparent;
  cursor: pointer;
  text-align: left;
  font: inherit;
  color: inherit;
  min-width: 0;
}
.pl-item-grow {
  flex: 1;
  min-width: 0;
}
.pl-item-name {
  flex: 1;
  min-width: 0;
  font-size: 13px;
  color: var(--text-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.pl-item-count {
  flex-shrink: 0;
  font-size: 11px;
  color: var(--text-muted);
  font-variant-numeric: tabular-nums;
}
/* 「⋯」常驻而不是 hover 才出现：触屏上没有 hover，藏起来等于这台设备没有这两个操作 */
.pl-more {
  flex-shrink: 0;
  margin-right: 4px;
}
.pl-empty {
  padding: 16px 10px;
  font-size: 12px;
  line-height: 1.6;
  color: var(--text-muted);
  text-align: center;
}
</style>

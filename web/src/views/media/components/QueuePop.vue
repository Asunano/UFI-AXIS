<!--
  播放队列浮层（2026-10-23 从 MediaAudioView 抽出）。

  ## 为什么单独一个组件
  资料库的播放条与沉浸播放层都要弹同一份队列，但两者的定位父级不同：
    · 资料库：浮层挂在 `.player-dock` 上，从播放条上沿弹出；
    · 沉浸层：浮层挂在 `.immersive-dock` 上，从沉浸控制条上沿弹出。
  各写一份会在“当前行高亮 / 失效条目标注 / 加载更多”上立刻分叉。定位交给父级，
  本组件只管内容，所以根元素不带 `position` —— 父级用 `bottom: calc(100% + 8px)` 锚定。

  ## 深色主题为什么用 prop 而不是父级选择器
  `<style scoped>` 的父级选择器打不进子组件内部（除根元素外），而沉浸层需要一整套
  深色令牌覆盖。`dark` prop 在根元素上加 `is-dark`，样式和结构留在这一个文件里，
  不依赖“父页面正好也有 stage-open 类”这种隐形约定。
-->
<template>
  <aside class="queue-pop" :class="{ 'is-dark': dark }" aria-label="播放队列">
    <div class="qp-head">
      <b>{{ title }}</b>
      <span class="qp-count">{{ countText }}</span>
      <span class="grow"></span>
      <button type="button" class="qp-close" title="关闭" @click="emit('close')">✕</button>
    </div>
    <div class="qp-actions">
      <button v-if="!isLibraryQueue" type="button" :disabled="playableCount === 0" @click="emit('play-all', false)">
        播放全部
      </button>
      <button v-if="!isLibraryQueue" type="button" :disabled="playableCount === 0" @click="emit('play-all', true)">
        随机播放
      </button>
      <button v-if="queueLength" type="button" @click="emit('clear')">清空</button>
    </div>
    <div class="qp-list">
      <button
        v-for="(t, i) in tracks"
        :key="t.path"
        type="button"
        class="qp-row"
        :class="{ 'is-current': t.path === currentPath, 'is-missing': t.missing }"
        :disabled="t.missing"
        @click="emit('play', t)"
      >
        <span class="qp-index">
          <n-icon v-if="t.path === currentPath" :size="13">
            <VolumeHighOutline v-if="playing" />
            <PauseOutline v-else />
          </n-icon>
          <template v-else>{{ i + 1 }}</template>
        </span>
        <span class="qp-text">
          <b>{{ trackTitleOf(t) }}</b>
          <small>{{ t.missing ? MISSING_NOTE : trackArtistOf(t) }}</small>
        </span>
        <span class="qp-dur">{{ formatDuration(t.duration_ms) }}</span>
      </button>
      <div v-if="isLibraryQueue && hasMore" class="qp-more">
        <n-button size="tiny" quaternary :loading="loadingMore" @click="emit('load-more')">加载更多</n-button>
      </div>
    </div>
  </aside>
</template>

<script setup lang="ts">
import { PauseOutline, VolumeHighOutline } from '@vicons/ionicons5';
import type { PlaylistTrack } from '../playlistShared';
import { formatDuration, trackArtistOf, trackTitleOf } from '../mediaShared';

defineProps<{
  tracks: PlaylistTrack[];
  title: string;
  countText: string;
  /** true = 队列就是媒体库平铺列表；只有它才显示「加载更多」。 */
  isLibraryQueue: boolean;
  playableCount: number;
  /** 队列总条数（`tracks` 是搜索过滤后的展示集）；清空按钮按整份队列判断。 */
  queueLength: number;
  hasMore: boolean;
  loadingMore: boolean;
  currentPath?: string;
  playing: boolean;
  /** 沉浸层里的深色版本。 */
  dark?: boolean;
}>();

const emit = defineEmits<{
  (e: 'close'): void;
  (e: 'play', track: PlaylistTrack): void;
  (e: 'play-all', random: boolean): void;
  (e: 'clear'): void;
  (e: 'load-more'): void;
}>();

/** 与 MediaAudioView 同一句文案；抽出组件后不再从页面传一个常量进来。 */
const MISSING_NOTE = '文件已不在媒体库';
</script>

<style scoped>
.queue-pop {
  z-index: 56;
  width: 340px;
  max-height: min(60vh, 460px);
  display: flex;
  flex-direction: column;
  background: var(--card-bg);
  border: 1px solid var(--border-subtle);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-color-strong);
  overflow: hidden;
}
.qp-head {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 11px 12px 6px;
}
.qp-head b {
  font-size: 12.5px;
  color: var(--text-primary);
}
.qp-count {
  font-size: 11px;
  color: var(--text-muted);
  font-variant-numeric: tabular-nums;
}
.qp-head .grow {
  flex: 1;
}
.qp-close {
  border: 0;
  background: none;
  color: var(--text-muted);
  cursor: pointer;
  padding: 3px 6px;
  border-radius: var(--radius-sm);
}
.qp-actions {
  display: flex;
  gap: 6px;
  padding: 0 12px 8px;
  border-bottom: 1px solid var(--border-subtle);
}
.qp-actions button {
  border: 1px solid var(--border-subtle);
  background: var(--card-bg);
  border-radius: var(--radius-sm);
  padding: 4px 9px;
  font-size: 11.5px;
  color: var(--text-secondary);
  cursor: pointer;
}
.qp-actions button:hover:not(:disabled) {
  border-color: var(--accent-color);
  color: var(--accent-color);
}
.qp-actions button:disabled {
  opacity: 0.45;
  cursor: default;
}
.qp-list {
  flex: 1;
  min-height: 0;
  overflow: auto;
  padding: 6px;
}
.qp-row {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  border: 0;
  background: none;
  border-radius: var(--radius-sm);
  padding: 7px 8px;
  text-align: left;
  cursor: pointer;
  color: inherit;
  font: inherit;
  /* 队列可能几百条，只布局浮层可视区内这几行 */
}
.qp-row:hover {
  background: var(--surface-hover);
}
.qp-row.is-current {
  background: var(--accent-color-light);
}
.qp-row.is-missing {
  opacity: 0.55;
}
.qp-index {
  width: 18px;
  flex: none;
  text-align: right;
  font-size: 11px;
  color: var(--text-muted);
  font-variant-numeric: tabular-nums;
}
.qp-text {
  flex: 1;
  min-width: 0;
}
.qp-text b {
  display: block;
  font-size: 12px;
  font-weight: 500;
  color: var(--text-primary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.qp-text small {
  display: block;
  font-size: 11px;
  color: var(--text-muted);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.qp-dur {
  font-size: 11px;
  color: var(--text-muted);
  font-variant-numeric: tabular-nums;
}
.qp-more {
  display: flex;
  justify-content: center;
  padding: 6px 0;
}

/* 沉浸层深色版：固定使用中性深灰，不接品牌绿令牌。 */
.queue-pop.is-dark {
  /* ui-baseline-allow: 沉浸层固定深色场景，队列必须中性深底浅灰字 */
  --qp-bg: #14171d;
  --qp-line: #2a3038;
  --qp-line-strong: #3a424d;
  --qp-ink: #e9edf3;
  --qp-button-ink: #cbd5e1;
  --qp-dim: #8d98a8;
  --qp-hover: rgba(255, 255, 255, 0.06);
  --qp-current: rgba(255, 255, 255, 0.1);
  background: var(--qp-bg);
  border-color: var(--qp-line);
  color: var(--qp-ink);
}
.queue-pop.is-dark .qp-head b,
.queue-pop.is-dark .qp-text b {
  color: var(--qp-ink);
}
.queue-pop.is-dark .qp-count,
.queue-pop.is-dark .qp-text small,
.queue-pop.is-dark .qp-dur,
.queue-pop.is-dark .qp-index,
.queue-pop.is-dark .qp-close {
  color: var(--qp-dim);
  opacity: 1;
}
.queue-pop.is-dark .qp-actions {
  border-bottom-color: var(--qp-line);
}
.queue-pop.is-dark .qp-actions button {
  background: transparent;
  border-color: var(--qp-line);
  color: var(--qp-button-ink);
}
.queue-pop.is-dark .qp-actions button:hover:not(:disabled) {
  border-color: var(--qp-line-strong);
  color: var(--qp-ink);
}
.queue-pop.is-dark .qp-row:hover {
  background: var(--qp-hover);
}
.queue-pop.is-dark .qp-row.is-current {
  background: var(--qp-current);
}

@media (max-width: 768px) {
  .queue-pop {
    left: 8px;
    right: 8px;
    width: auto;
    max-height: 60vh;
  }
}
</style>

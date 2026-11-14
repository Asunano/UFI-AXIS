<!--
  顶栏每日诗词挂件。与手机端 `UfiHeaderPoem` 是同一个功能的两端镜像。

  两条刻意的口径：
  1. 挂件只显示**一句**（`content`），完整篇目/作者/译文收进浮层。顶栏是"顺带看一眼"的位置，
     不是阅读界面；那一句本身也由上游选好，web 不截断也不改写。
  2. 点击会带 `refresh=1` 重取，但**完全可能拿回同一首** —— core 本地缓存 10 分钟、
     上游自己也有约 10 分钟缓存。所以按钮文案是"换一句"而提示写明"可能仍是同一首"，
     不承诺一定会变。
-->
<template>
  <n-popover v-if="visible" trigger="click" placement="bottom" :width="320">
    <template #trigger>
      <span class="poem-line" :title="p?.content">{{ p?.content }}</span>
    </template>

    <div class="poem-detail">
      <div class="poem-head">
        <span class="poem-title">{{ p?.title }}</span>
        <span class="poem-meta">{{ authorLine }}</span>
      </div>

      <!-- 全文：`show_origin` 关掉时只留顶栏那一句，不展开整篇 -->
      <div v-if="showOrigin && fullLines.length" class="poem-full">
        <p v-for="(line, i) in fullLines" :key="i" class="poem-full-line">{{ line }}</p>
      </div>

      <div v-if="p?.translate?.length" class="poem-translate">
        <span class="poem-section-label">译文</span>
        <p v-for="(line, i) in p.translate" :key="i" class="poem-translate-line">{{ line }}</p>
      </div>

      <!-- 命中标签由上游按设备出口 IP 的地理位置、当地天气、时辰与节气算出，只读展示 -->
      <div v-if="p?.match_tags?.length" class="poem-tags">
        <n-tag v-for="t in p.match_tags" :key="t" size="tiny" :bordered="false">{{ t }}</n-tag>
      </div>

      <div class="poem-actions">
        <span class="poem-hint">上游有约 10 分钟缓存，可能仍是同一首</span>
        <n-button size="tiny" quaternary :loading="store.poetryLoading" @click="store.fetchPoetry(true)">
          换一句
        </n-button>
      </div>
    </div>
  </n-popover>
</template>

<script setup lang="ts">
import { computed } from 'vue';
import { useUiExtrasStore } from '@/stores/uiExtras';

const store = useUiExtrasStore();

const p = computed(() => store.poetry);

const visible = computed(() => store.poetryConfig.enabled && !!p.value?.content);

const showOrigin = computed(() => store.poetryConfig.show_origin);

const fullLines = computed(() => p.value?.full_content ?? []);

/** 朝代可能是空串（上游不总给），此时不显示那对括号。 */
const authorLine = computed(() => {
  const c = p.value;
  if (!c) return '';
  return c.dynasty ? `${c.author} · ${c.dynasty}` : c.author;
});
</script>

<style scoped>
.poem-line {
  font-size: 12px;
  color: var(--text-muted);
  line-height: 1.2;
  cursor: pointer;
  user-select: none;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  /* 顶栏里它和设备条抢宽度，给一个上限而不是任其撑开 */
  max-width: 220px;
}
.poem-line:hover {
  color: var(--text-secondary);
}

/* ── 详情浮层 ── */
.poem-detail {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.poem-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 10px;
}
.poem-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--text-primary);
}
.poem-meta {
  font-size: 12px;
  color: var(--text-muted);
  white-space: nowrap;
}
.poem-full {
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.poem-full-line {
  margin: 0;
  font-size: 13px;
  line-height: 1.7;
  color: var(--text-primary);
}
.poem-translate {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding-top: 8px;
  border-top: 1px solid var(--border-subtle);
}
.poem-section-label {
  font-size: 12px;
  color: var(--text-muted);
}
.poem-translate-line {
  margin: 0;
  font-size: 12px;
  line-height: 1.6;
  color: var(--text-secondary);
}
.poem-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
}
.poem-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding-top: 8px;
  border-top: 1px solid var(--border-subtle);
}
.poem-hint {
  font-size: 11px;
  color: var(--text-muted);
}

/* 手机上顶栏放不下这一句 —— 设备条也是同样的处理 */
@media (max-width: 768px) {
  .poem-line {
    display: none;
  }
}
</style>

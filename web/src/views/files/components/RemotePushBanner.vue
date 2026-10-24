<template>
  <!--
    远端推送横幅（2026-10-14 新建）。
    它把"第二段（core → 远端）"从"只有打开弹窗才看得见"变成页面上常驻的一条：
    有活跃作业、或有失败待处理时出现，并给一个直接打开作业面板的入口（失败项的「重试」在里面）。
  -->
  <div v-if="summary" class="rp-banner" :class="{ 'is-failed': summary.failed > 0 }">
    <div class="rp-main">
      <span class="rp-text">{{ text }}</span>
      <!-- 进度条只在真的在跑时给：没有活跃作业却画一条 0% 的槽会让人以为卡住了 -->
      <n-progress
        v-if="summary.active > 0"
        class="rp-progress"
        type="line"
        :percentage="summary.percent"
        :height="3"
        :show-indicator="false"
        processing
      />
    </div>
    <n-button size="tiny" type="primary" @click="emit('open')">查看</n-button>
  </div>
</template>

<script setup lang="ts">
/**
 * 远端推送摘要条。
 *
 * 纯展示 + 意图上抛：轮询与摘要计算在 `useRemotePushSummary` 里（那是原来那条 15s 轮询的升级，
 * 不是第二条轮询），作业列表与重试仍在 `RemotePushModal` 里 —— 这里只负责"让人知道它在跑、
 * 并在失败时把人送到能重试的地方"。
 */
import { computed } from 'vue';
import type { RemotePushSummary } from '../useRemotePush';

const props = defineProps<{ summary: RemotePushSummary | null }>();
const emit = defineEmits<{ (e: 'open'): void }>();

const text = computed(() => {
  const s = props.summary;
  if (!s) return '';
  const parts: string[] = [];
  if (s.active > 0) {
    const name = s.currentName ? `：${s.currentName}` : '';
    const more = s.active > 1 ? `（共 ${s.active} 项）` : '';
    parts.push(`远端推送中${name} ${s.percent}%${more}`);
  } else {
    parts.push('远端推送');
  }
  if (s.failed > 0) {
    parts.push(s.retryable > 0 ? `${s.failed} 项失败（${s.retryable} 项可重试）` : `${s.failed} 项失败`);
  }
  return parts.join(' · ');
});
</script>

<style scoped>
/* 配色/圆角/间距一律走 main.css 的令牌，不写死取值（check-ui-baseline.mjs 卡增量） */
.rp-banner {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  flex-shrink: 0;
  padding: var(--space-2) var(--space-3);
  background: var(--accent-color-light);
  border-radius: var(--radius-sm);
  font-size: var(--font-base);
}
/* 有失败时整条换成告警底色：这条横幅此刻要的是"有人得处理一下"，不是"正在跑" */
.rp-banner.is-failed {
  background: var(--warning-light);
}

.rp-main {
  flex: 1;
  min-width: 0;
}

.rp-text {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--text-primary);
  font-variant-numeric: tabular-nums;
}

.rp-progress {
  margin-top: var(--space-1);
}
</style>

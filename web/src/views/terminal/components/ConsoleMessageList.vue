<template>
  <n-scrollbar ref="sbRef" class="msg-scroll">
    <div v-if="messages.length === 0 && !loading" class="empty">暂无记录，发送命令开始对话</div>
    <template v-else>
      <ConsoleBubble v-for="m in messages" :key="m.id" :message="m" />
      <div v-if="loading" class="typing-wrap"><ConsoleTypingIndicator /></div>
    </template>
  </n-scrollbar>
</template>

<script setup lang="ts">
import { ref, watch, nextTick } from 'vue';
import { NScrollbar } from 'naive-ui';
import ConsoleBubble from './ConsoleBubble.vue';
import ConsoleTypingIndicator from './ConsoleTypingIndicator.vue';
import type { ConsoleMessage } from './types';

const props = defineProps<{ messages: ConsoleMessage[]; loading?: boolean }>();

const sbRef = ref<any>(null);

function scrollBottom() {
  nextTick(() => sbRef.value?.scrollTo?.({ position: 'bottom' }));
}

watch(() => props.messages.length, scrollBottom);
watch(() => props.loading, scrollBottom);
</script>

<style scoped>
/* flex:1 + min-height:0：拿父级 .pane-list(flex 列) 给出的确定高度，列表独立滚动，
   不再用 height:100%(在 flex 链里会变成循环依赖、随内容撑高) */
.msg-scroll {
  flex: 1 1 auto;
  min-height: 0;
}
.empty {
  text-align: center;
  color: var(--text-muted);
  padding: 40px 0;
  font-size: 14px;
}
.typing-wrap {
  padding: 6px 2px;
}
</style>

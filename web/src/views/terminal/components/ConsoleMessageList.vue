<template>
  <n-scrollbar ref="sbRef" class="msg-scroll">
    <!-- 只渲染最近一段消息，避免切 Tab 时为几百个气泡付布局成本；更早的历史按需追加 -->
    <div v-if="earlierCount" class="earlier-row">
      <n-button size="tiny" quaternary @click="emit('load-earlier')">加载更早的 {{ earlierCount }} 条</n-button>
    </div>
    <!-- 空态走全局 `.client-empty`（main.css：居中 / 13px / --text-muted），不再自造一份 -->
    <div v-if="messages.length === 0 && !loading" class="client-empty">暂无记录，发送命令开始对话</div>
    <template v-else>
      <ConsoleBubble v-for="m in messages" :key="m.id" :message="m" />
      <div v-if="loading" class="typing-wrap"><ConsoleTypingIndicator /></div>
    </template>
  </n-scrollbar>
</template>

<script setup lang="ts">
import { ref, watch, nextTick, onMounted } from 'vue';
import { NScrollbar } from 'naive-ui';
import ConsoleBubble from './ConsoleBubble.vue';
import ConsoleTypingIndicator from './ConsoleTypingIndicator.vue';
import type { ConsoleMessage } from './types';

const props = defineProps<{
  messages: ConsoleMessage[];
  loading?: boolean;
  visible?: boolean;
  earlierCount?: number;
}>();
const emit = defineEmits<{ 'load-earlier': [] }>();

const sbRef = ref<any>(null);

function scrollBottom() {
  nextTick(() => sbRef.value?.scrollTo?.({ position: 'bottom' }));
}

/**
 * 滚到底。watch 的是**最后一条消息的 id**，不是 `messages.length`：
 * 长度不变但内容被整份替换（`loadHistory` 拉到 core 的新历史、或切 AT/Shell 两个 tab
 * 恰好长度相同）时，只盯长度不会触发，用户会停在旧位置看到一段错位的历史。
 * 用最后一条的 id 则覆盖「追加」（id 变）与「等长替换」（末条 id 变）两种。
 */
watch(() => props.messages[props.messages.length - 1]?.id, scrollBottom);
watch(() => props.loading, scrollBottom);
/**
 * 2026-10-24：父组件用两份列表 + v-show 切 Tab，列表在隐藏期间可能收到新消息；
 * 重新显示时补一次滚底（隐藏时 scrollTo 拿不到正确高度）。首次挂载也走 onMounted。
 */
watch(
  () => props.visible,
  (v) => v && scrollBottom()
);
onMounted(scrollBottom);
</script>

<style scoped>
/* flex:1 + min-height:0：拿父级 .pane-list(flex 列) 给出的确定高度，列表独立滚动，
   不再用 height:100%(在 flex 链里会变成循环依赖、随内容撑高) */
.msg-scroll {
  flex: 1 1 auto;
  min-height: 0;
}
.earlier-row {
  display: flex;
  justify-content: center;
  padding: 2px 0 8px;
}
.typing-wrap {
  padding: 6px 2px;
}
</style>

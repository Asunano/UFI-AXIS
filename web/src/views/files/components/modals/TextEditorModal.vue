<template>
  <n-modal
    :show="show"
    preset="card"
    :title="title"
    class="text-editor-modal"
    style="width: min(920px, 94vw)"
    :content-style="contentStyle"
    @update:show="emit('update:show', $event)"
  >
    <template #default>
      <n-alert v-if="readOnly" type="warning" :show-icon="false" class="readonly-alert">
        {{ content }}<br />该文件无法在线编辑，保存已禁用。
      </n-alert>

      <div class="editor-meta">
        <span class="meta-chip">{{ lineCount }} 行 · {{ charCount }} 字符</span>
        <span v-if="!readOnly" class="meta-chip" :class="{ dirty }">
          {{ dirty ? '● 未保存' : '已同步' }}
        </span>
        <span class="meta-hint">Ctrl/Cmd+S 保存</span>
      </div>

      <!-- 固定高度容器：textarea 用 absolute 填满，避免 flex + height:100% 在部分浏览器塌成几行 -->
      <div v-if="!readOnly" class="editor-shell">
        <div class="gutter" aria-hidden="true">
          <div class="gutter-inner" :style="{ transform: `translateY(${-scrollTop}px)` }">
            <div v-for="n in gutterLines" :key="n" class="gutter-line">{{ n }}</div>
          </div>
        </div>
        <textarea
          ref="taEl"
          class="editor-area"
          :value="content"
          spellcheck="false"
          wrap="off"
          @input="onInput"
          @scroll="onEditorScroll"
          @keydown.ctrl.s.prevent="emit('save')"
          @keydown.meta.s.prevent="emit('save')"
        />
      </div>
    </template>

    <template #action>
      <div class="editor-actions">
        <n-button size="small" quaternary :disabled="readOnly" @click="copyAll">复制全文</n-button>
        <div class="actions-right">
          <n-button @click="emit('update:show', false)">关闭</n-button>
          <n-button v-if="!readOnly" type="primary" :loading="saving" @click="emit('save')">
            保存
          </n-button>
        </div>
      </div>
    </template>
  </n-modal>
</template>

<script setup lang="ts">
/**
 * 文本编辑弹窗。
 *
 * 布局坑：n-modal 的 content 区 + flex max-height 在不同浏览器会把
 * `height: 100%` 的 textarea 塌成默认 2 行，而 gutter 因整列内容撑满看起来正常，
 * 正文就像被“遮掉”了。改法：
 *  - .editor-shell 显式 height（不是 min/max 混用）
 *  - textarea absolute 铺满 shell
 *  - content-style 尽量简单，少插一层奇怪的 flex
 */
import { computed, nextTick, ref, watch } from 'vue';
import { useMessage } from 'naive-ui';

const props = defineProps<{
  show: boolean;
  title: string;
  content: string;
  readOnly: boolean;
  saving: boolean;
}>();

const emit = defineEmits<{
  (e: 'update:show', v: boolean): void;
  (e: 'update:content', v: string): void;
  (e: 'save'): void;
}>();

const message = useMessage();
const taEl = ref<HTMLTextAreaElement | null>(null);
const scrollTop = ref(0);
const cleanSnapshot = ref('');
const dirty = ref(false);

const contentStyle = {
  padding: '12px 16px 12px',
};

const lineCount = computed(() => {
  if (!props.content) return 1;
  let n = 1;
  for (let i = 0; i < props.content.length; i++) {
    if (props.content.charCodeAt(i) === 10) n++;
  }
  return n;
});

const charCount = computed(() => props.content.length);

const MAX_GUTTER_LINES = 8000;
const gutterLines = computed(() => {
  const n = Math.min(lineCount.value, MAX_GUTTER_LINES);
  return Array.from({ length: n }, (_, i) => i + 1);
});

function onInput(ev: Event) {
  const v = (ev.target as HTMLTextAreaElement).value;
  emit('update:content', v);
  dirty.value = v !== cleanSnapshot.value;
}

function onEditorScroll() {
  scrollTop.value = taEl.value?.scrollTop ?? 0;
}

async function copyAll() {
  try {
    await navigator.clipboard.writeText(props.content);
    message.success('已复制全文');
  } catch {
    message.error('复制失败');
  }
}

watch(
  () => props.show,
  async (v) => {
    if (v) {
      cleanSnapshot.value = props.content;
      dirty.value = false;
      scrollTop.value = 0;
      await nextTick();
      if (taEl.value) {
        taEl.value.scrollTop = 0;
        taEl.value.focus();
      }
    }
  },
  { immediate: true }
);

watch(
  () => props.content,
  (v) => {
    if (!props.show || props.readOnly) return;
    dirty.value = v !== cleanSnapshot.value;
  }
);

watch(
  () => props.title,
  () => {
    cleanSnapshot.value = props.content;
    dirty.value = false;
  }
);
</script>

<style scoped>
.readonly-alert {
  margin-bottom: 10px;
}
.editor-meta {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 10px;
  flex-shrink: 0;
}
.meta-chip {
  font-size: 12px;
  color: var(--text-muted);
  padding: 2px 8px;
  border-radius: 999px;
  background: var(--surface-hover);
  font-variant-numeric: tabular-nums;
}
.meta-chip.dirty {
  color: var(--warning-color, #d4a017);
}
.meta-hint {
  margin-left: auto;
  font-size: 11px;
  color: var(--text-muted);
}

/* 关键：显式高度，不用 min/max 夹；children 用 absolute 填满 */
.editor-shell {
  position: relative;
  display: flex;
  width: 100%;
  height: 52vh;
  min-height: 280px;
  max-height: 640px;
  border: 1px solid var(--border-subtle);
  border-radius: 8px;
  overflow: hidden;
  background: var(--bg-color, #fff);
}

.gutter {
  position: relative;
  z-index: 1;
  flex: 0 0 52px;
  width: 52px;
  height: 100%;
  overflow: hidden;
  background: var(--surface-hover);
  border-right: 1px solid var(--border-subtle);
}
.gutter-inner {
  padding: 10px 8px 40px 0;
  text-align: right;
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 12px;
  line-height: 1.55;
  color: var(--text-muted);
  user-select: none;
  will-change: transform;
}
.gutter-line {
  height: 1.55em;
}

.editor-area {
  position: absolute;
  inset: 0 0 0 52px; /* 让出 gutter */
  width: calc(100% - 52px);
  height: 100%;
  padding: 10px 12px 40px;
  border: none;
  outline: none;
  resize: none;
  overflow: auto;
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 13px;
  line-height: 1.55;
  tab-size: 2;
  background: transparent;
  color: inherit;
  white-space: pre;
  box-sizing: border-box;
  /* 确保长内容可滚，且自身成为滚动容器 */
  overscroll-behavior: contain;
}

.editor-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  width: 100%;
  gap: 8px;
  margin-top: 4px;
}
.actions-right {
  display: flex;
  align-items: center;
  gap: 8px;
}

/* 去掉 modal 对 body 的奇怪挤压 */
:deep(.n-modal) {
  overflow: visible;
}
</style>

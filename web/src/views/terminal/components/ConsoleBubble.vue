<template>
  <div class="bubble-row" :class="`is-${message.role}`">
    <div class="bubble" :class="{ 'bubble-error': message.role === 'error' }">
      <div class="bubble-head">
        <span class="bubble-role">{{ roleLabel }}</span>
        <n-button size="tiny" secondary class="copy-btn" @click="copy">复制</n-button>
      </div>
      <pre
        class="bubble-text"
      ><span v-if="promptPrefix" class="bubble-prompt" aria-hidden="true">{{ promptPrefix }}</span>{{ bodyText }}</pre>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue';
import { NButton } from 'naive-ui';
import type { ConsoleMessage } from './types';

const props = defineProps<{ message: ConsoleMessage }>();

/**
 * 命令气泡带终端提示符（`# ` / `$ `）：
 *   · 提示符单独包一层，`user-select: none` —— 双击选命令时不会把 `#` 一起选中；
 *   · 复制只复制命令本身（去掉提示符），否则粘到别处还要手动删。
 */
const promptMatch = computed(() =>
  props.message.role === 'user' ? /^([#$] )([\s\S]*)$/.exec(props.message.text) : null
);
const promptPrefix = computed(() => promptMatch.value?.[1] ?? '');
const bodyText = computed(() => promptMatch.value?.[2] ?? props.message.text);

const roleLabel = computed(() =>
  props.message.role === 'user' ? '命令' : props.message.role === 'error' ? '错误' : '响应'
);

async function copy() {
  try {
    await navigator.clipboard.writeText(bodyText.value);
  } catch {
    /* 剪贴板不可用时静默 */
  }
}
</script>

<style scoped>
.bubble-row {
  display: flex;
  margin-bottom: 10px;
  /* 切 AT / Shell 时列表可能有几百条历史；只绘制视口附近的气泡，
     避免 display 切换时把全部气泡重新布局/绘制一遍。 */
  content-visibility: auto;
  contain-intrinsic-size: auto 72px;
}
.bubble-row.is-user {
  justify-content: flex-end;
}
.bubble-row.is-assistant,
.bubble-row.is-error {
  justify-content: flex-start;
}
/* ── 终端气泡配色（2026-11-08 重做）──
   原先是写死的 One Dark 深色（#1a1a2e / #2a3a5e）+ 浅灰细字，浅色模式下像一块"嵌入式黑窗"，
   正文字重又轻，长命令/长输出读起来很吃力。现在改成接主题令牌：
     · 响应：--surface-elevated 浅底 + 主文字
     · 命令：--accent-color-light 浅绿底（区分"我发的"与"机器回的"）
     · 错误：--error-light 浅红底 + --error 文字/描边
   仍然保留等宽字体（终端内容需要对齐），但字重提到 500、字号 13.5，正文用 --text-primary。 */
.bubble {
  max-width: 75%;
  background: var(--surface-elevated);
  border: 1px solid var(--border-subtle);
  border-radius: 10px;
  padding: 9px 12px;
}
.bubble-row.is-user .bubble {
  background: var(--accent-color-light);
  border-color: color-mix(in srgb, var(--accent-color) 28%, transparent);
}
.bubble-error {
  background: var(--error-light);
  border-color: color-mix(in srgb, var(--error) 35%, transparent);
}
.bubble-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 4px;
}
.bubble-role {
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 0.02em;
  color: var(--text-secondary);
}
.bubble-error .bubble-role {
  color: var(--error);
}
/* 复制按钮：原来用 tertiary（透明底 + 低对比文字），在浅色气泡上几乎看不见。
   改成 secondary（浅底描边）+ 明确字色，hover 走主色，保证"看得见有这个功能"。 */
.copy-btn {
  flex: none;
  font-size: 11px;
  opacity: 1;
}
.copy-btn :deep(.n-button__content) {
  color: var(--text-secondary);
  font-weight: 500;
}
.copy-btn:hover :deep(.n-button__content) {
  color: var(--accent-color);
}

/* 提示符不可选中、不可复制；颜色弱化，避免和正文抢注意力 */
.bubble-prompt {
  user-select: none;
  -webkit-user-select: none;
  color: var(--text-muted);
}
.bubble-text {
  margin: 0;
  /* 等宽保留：终端输出靠列对齐才能读；但字重/字号按正文标准给足 */
  font-family: 'JetBrains Mono', 'Fira Code', ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 13.5px;
  font-weight: 500;
  line-height: 1.65;
  white-space: pre-wrap;
  word-break: break-all;
  color: var(--text-primary);
}
.bubble-row.is-user .bubble-text {
  color: var(--text-primary);
}
.bubble-error .bubble-text {
  color: var(--error);
}
@media (max-width: 768px) {
  .bubble {
    max-width: 85%;
  }
}
</style>

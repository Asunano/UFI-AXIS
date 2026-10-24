<template>
  <div class="bubble-row" :class="`is-${message.role}`">
    <div class="bubble" :class="{ 'bubble-error': message.role === 'error' }">
      <div class="bubble-head">
        <span class="bubble-role">{{ roleLabel }}</span>
        <n-button size="tiny" tertiary class="copy-btn" @click="copy">复制</n-button>
      </div>
      <pre class="bubble-text">{{ message.text }}</pre>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue';
import { NButton } from 'naive-ui';
import type { ConsoleMessage } from './types';

const props = defineProps<{ message: ConsoleMessage }>();

const roleLabel = computed(() =>
  props.message.role === 'user' ? '命令' : props.message.role === 'error' ? '错误' : '响应'
);

async function copy() {
  try {
    await navigator.clipboard.writeText(props.message.text);
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
/* ── 终端气泡配色：刻意不接主题令牌（域色豁免）──
   下面 9 个写死值是一套 One Dark 风格的**终端配色**（深蓝底 #1a1a2e / 用户气泡 #2a3a5e /
   错误 #e06c75 / 正文 #c8c8d8），语义是「这是一个终端」，不是「这是本站的强调色/错误色」。
   接 --accent-color / --error 会让它跟着换肤走，反而丢掉终端该有的辨识度；
   而且它在浅色模式下也应当保持深底（终端就是深的），所以也不需要 .dark 档。
   与 Android 侧「域色是否随皮肤」是同一性质的豁免，不要在 W4 的清理里顺手换掉。 */
/* ui-baseline-allow: One Dark 终端配色，语义是「这是一个终端」，不跟本站主题换肤 */
.bubble {
  max-width: 75%;
  background: #1a1a2e;
  border: 1px solid rgba(255, 255, 255, 0.06);
  border-radius: 10px;
  padding: 8px 10px;
}
/* ui-baseline-allow: 终端配色（同 .bubble 的完整口径），用户气泡的深蓝底与描边 */
.bubble-row.is-user .bubble {
  background: #2a3a5e;
  border-color: rgba(126, 200, 227, 0.3);
}
/* ui-baseline-allow: 终端配色（同 .bubble 的完整口径），错误气泡的描边红 */
.bubble-error {
  border-color: rgba(224, 108, 117, 0.5);
}
.bubble-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 4px;
}
/* ui-baseline-allow: 终端配色（同 .bubble 的完整口径），角色标签的暗灰 */
.bubble-role {
  font-size: 11px;
  color: #6c6c8a;
}
/* ui-baseline-allow: 终端配色（同 .bubble 的完整口径），错误角色标签的红 */
.bubble-error .bubble-role {
  color: #e06c75;
}
.copy-btn {
  font-size: 11px;
}
/* ui-baseline-allow: 终端配色（同 .bubble 的完整口径），终端正文字色 */
.bubble-text {
  margin: 0;
  font-family: 'JetBrains Mono', 'Fira Code', monospace;
  font-size: 13px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-all;
  color: #c8c8d8;
}
/* ui-baseline-allow: 终端配色（同 .bubble 的完整口径），错误正文的红 */
.bubble-error .bubble-text {
  color: #e06c75;
}
/* ui-baseline-allow: 终端配色（同 .bubble 的完整口径），用户正文的浅蓝白 */
.bubble-row.is-user .bubble-text {
  color: #e8f0ff;
}
@media (max-width: 768px) {
  .bubble {
    max-width: 85%;
  }
}
</style>

<template>
  <!-- 宽高交给全局 `.modal-fit`（360px 视口下不再出屏） -->
  <n-modal
    :show="show"
    preset="card"
    title="校验"
    class="modal-fit"
    style="--modal-w: 460px"
    @update:show="emit('update:show', $event)"
  >
    <div v-if="result" class="cs-grid">
      <!-- 路径单独一行、铺满宽度：InfoRow 的值区是 text-align:right + max-width:65%，
           长路径会从中间开始显示，看着像"标签和内容断开了"（用户反馈的"异常大间隔"）。 -->
      <div class="cs-row cs-path-row">
        <span class="cs-label">文件</span>
        <code class="cs-path" :title="result.path">{{ result.path }}</code>
        <n-button size="tiny" text type="primary" @click="copyPath(result.path)">复制</n-button>
      </div>
      <p class="cs-hint">校验用于确认文件与来源一致（例如和下载页给出的哈希比对），不涉及上传或修改。</p>
      <div v-for="(hash, algo) in result.algorithms" :key="algo" class="cs-row">
        <span class="cs-label">{{ algo.toUpperCase() }}</span>
        <code class="cs-hash">{{ hash }}</code>
        <n-button size="tiny" text type="primary" @click="copyHash(hash)">复制</n-button>
      </div>
    </div>
  </n-modal>
</template>

<script setup lang="ts">
/**
 * 「校验」弹窗（MD5 / SHA1 / SHA256）。数据来自 `/api/files/checksum`（由页面取好再打开）。
 * 每条算法一行，附「复制」按钮，便于比对下载文件的官方哈希。
 */
defineProps<{
  show: boolean;
  result: { path: string; algorithms: Record<string, string> } | null;
}>();

const emit = defineEmits<{ (e: 'update:show', v: boolean): void }>();

function copyHash(hash: string) {
  navigator.clipboard?.writeText(hash).catch(() => {});
}

function copyPath(path: string) {
  navigator.clipboard?.writeText(path).catch(() => {});
}
</script>

<style scoped>
.cs-grid {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.cs-row {
  display: flex;
  align-items: center;
  gap: 8px;
}
.cs-label {
  width: 56px;
  flex-shrink: 0;
  font-size: 12px;
  color: var(--text-muted);
  font-variant-numeric: tabular-nums;
  text-transform: uppercase;
}
.cs-path {
  flex: 1;
  min-width: 0;
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 12px;
  color: var(--text-primary);
  /* 单行尾部省略 + 悬停看全文：路径可能很长，换行会把整个弹窗撑高 */
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  direction: ltr;
}
.cs-hint {
  margin: 2px 0 0;
  font-size: 11.5px;
  color: var(--text-muted);
  line-height: 1.5;
}
.cs-hash {
  flex: 1;
  min-width: 0;
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 12px;
  word-break: break-all;
  background: var(--surface-elevated);
  padding: 4px 6px;
  border-radius: 6px;
}
</style>

<template>
  <!--
    致命错误浮层（2026-10-30）。由 `main.ts` 的 errorHandler / window.error 触发，
    状态见 `composables/globalError.ts`。

    刻意**不用任何 naive-ui 组件、不用 router、不用 store**：触发它的往往正是某个组件树崩掉，
    此时再走一遍 `n-modal` 的 teleport / `n-button` 的注入链路，等于把「出错时依赖最少」这条
    唯一的要求丢掉。所以这里是纯 HTML + 令牌样式，明暗模式靠 `main.css` 的 `.dark` 自动跟随。
  -->
  <div v-if="fatalError" class="fatal-mask" role="alert" aria-live="assertive">
    <div class="fatal-card">
      <div class="fatal-title">
        <svg
          width="20"
          height="20"
          viewBox="0 0 24 24"
          fill="none"
          stroke="currentColor"
          stroke-width="2"
          stroke-linecap="round"
          stroke-linejoin="round"
          aria-hidden="true"
        >
          <circle cx="12" cy="12" r="9" />
          <path d="M12 8v4M12 16h.01" />
        </svg>
        <span>{{ fatalError.reloadHint ? '页面已过期，需要刷新' : '页面出错了' }}</span>
      </div>

      <p class="fatal-message">
        {{
          fatalError.reloadHint
            ? '设备上的 Web 面板已经更新，当前打开的页面引用的是旧版本文件。刷新一次即可恢复，不影响设备本身。'
            : fatalError.message
        }}
      </p>

      <p class="fatal-hint">界面可能已经停止响应。可以先点「重新加载」；如果反复出现，把这段详情贴到反馈里。</p>

      <pre class="fatal-detail">{{ fatalError.detail }}</pre>

      <div class="fatal-actions">
        <button type="button" class="fatal-btn" @click="copyDetail">复制详情</button>
        <button type="button" class="fatal-btn" @click="dismissFatal">继续使用</button>
        <button type="button" class="fatal-btn fatal-btn--primary" @click="reload">重新加载</button>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { dismissFatal, fatalError } from '@/composables/globalError';

function reload() {
  location.reload();
}

/**
 * 复制详情，给「反馈问题」用。
 *
 * 用 `navigator.clipboard` 而不是自造 textarea + execCommand：后者已废弃，
 * 而这里失败也无所谓（剪贴板权限被拒时静默），用户仍能手动选中 pre 里的文本。
 */
async function copyDetail() {
  const info = fatalError.value;
  if (!info) return;
  try {
    await navigator.clipboard.writeText(`${info.message}\n\n${info.detail}`);
  } catch {
    /* 静默：没有剪贴板权限时用户仍可手动选中 */
  }
}
</script>

<style scoped>
.fatal-mask {
  position: fixed;
  inset: 0;
  /* 必须压在 naive 的弹窗（~2000）与消息（~6000 区间视版本）之上：
     这条信息比任何一层浮层都重要 —— 页面已经不能用了。 */
  z-index: 9000;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: var(--space-4);
  background: var(--page-bg);
}
.fatal-card {
  width: min(640px, 100%);
  max-height: min(80vh, 640px);
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
  padding: var(--space-5);
  border: 1px solid var(--border-subtle);
  border-radius: var(--radius-lg);
  background: var(--card-bg);
  box-shadow: 0 12px 32px var(--shadow-color-strong);
}
.fatal-title {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  font-size: var(--font-lg);
  font-weight: 600;
  color: var(--error);
}
.fatal-message {
  font-size: var(--font-md);
  color: var(--text-primary);
  line-height: 1.5;
  word-break: break-word;
}
.fatal-hint {
  font-size: var(--font-sm);
  color: var(--text-secondary);
  line-height: 1.5;
}
.fatal-detail {
  flex: 1;
  min-height: 80px;
  overflow: auto;
  margin: 0;
  padding: var(--space-3);
  border-radius: var(--radius-sm);
  background: var(--code-bg);
  color: var(--text-secondary);
  font-family: ui-monospace, Menlo, monospace;
  font-size: var(--font-sm);
  line-height: 1.5;
  white-space: pre-wrap;
  word-break: break-word;
  overscroll-behavior: contain;
}
.fatal-actions {
  display: flex;
  justify-content: flex-end;
  flex-wrap: wrap;
  gap: var(--space-2);
}
/* 按钮样式手写而不用 n-button：见模板顶部注释。尺寸对齐 naive 的 size="small"（28px 高、
   12px 内距），保证它看起来仍属于这个产品，而不是浏览器原生按钮。 */
.fatal-btn {
  padding: 0 var(--space-3);
  height: 28px;
  font-size: var(--font-base);
  font-family: inherit;
  color: var(--text-primary);
  background: transparent;
  border: 1px solid var(--border-subtle);
  border-radius: var(--radius-sm);
  cursor: pointer;
  transition:
    border-color 0.15s,
    background 0.15s;
}
.fatal-btn:hover {
  border-color: var(--accent-color);
  background: var(--surface-hover);
}
.fatal-btn--primary {
  color: var(--on-accent);
  background: var(--accent-color);
  border-color: var(--accent-color);
}
.fatal-btn--primary:hover {
  background: var(--accent-color);
  opacity: 0.88;
}
</style>

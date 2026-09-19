/**
 * 全站统一的移动端断点判据（2026-09-19）。
 *
 * ──── 为什么要统一 ────
 * 之前三处各写一遍且临界值不同：
 *   · DefaultLayout  → `window.innerWidth < 768`（不含 768）
 *   · SmsView        → `window.innerWidth <= 768`（含 768）
 *   · FilesView      → `window.matchMedia('(max-width: 768px)')`（含 768）
 * 在 768px 这个点上三者行为不同：DefaultLayout 认为是桌面（展示侧栏 + 设备信息条），
 * 而 SmsView/FilesView 认为是移动端（单面板 / 移动工具栏）。
 * 统一后全站在 768px 这个临界值上行为一致。
 *
 * ──── 阈值约定 ────
 * `(max-width: 768px)` 含 768 自身 —— 这与 CSS 里 44 处 `@media (max-width: 768px)`
 * 以及 `main.css` 顶部的断点说明一致（「768 → 单列/移动形态」）。
 *
 * ──── 实现选择 ────
 * `matchMedia` 而不是 `resize` + `innerWidth`：
 *   · 不丢初始值（matchMedia 创建时就知道当前匹配态）
 *   · 不跟着每一帧 resize 跑（只在跨越断点时触发）
 *   · 与 CSS `@media` 的判据**逐字相同**，不会再出现
 *     "CSS 认为是移动端但 JS 认为不是"的分裂
 *
 * 返回的 `isMobile` 是响应式 `Ref<boolean>`，组件卸载时自动清理监听。
 */
import { onScopeDispose, ref, type Ref } from 'vue';

const MOBILE_QUERY = '(max-width: 768px)';

/**
 * 返回一个响应式布尔值：当前视口 ≤ 768px 时为 `true`。
 *
 * **组件内调用**（在 setup 或 composable 上下文中）：自动在 scope 销毁时清理监听。
 * **全局调用**（store 的 action 等无 scope 的地方）：调用方需自行 `stop()` 或忽略
 * （全局场景通常是常驻的，不清理也不会泄漏）。
 */
export function useIsMobile(): Ref<boolean> {
  const mq = window.matchMedia(MOBILE_QUERY);
  const isMobile = ref(mq.matches);

  function onChange(e: MediaQueryListEvent) {
    isMobile.value = e.matches;
  }

  mq.addEventListener('change', onChange);

  // 在有 effect scope 的上下文中自动清理（组件卸载 / scope stop）
  try {
    onScopeDispose(() => mq.removeEventListener('change', onChange));
  } catch {
    // 无 scope（全局调用）时 onScopeDispose 会抛，忽略
  }

  return isMobile;
}

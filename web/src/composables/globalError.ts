/**
 * 全局致命错误的状态容器（2026-10-30）。
 *
 * ## 为什么需要
 * 改前全仓没有任何 `app.config.errorHandler` / `window.onerror`：任何一个组件在
 * setup 或 render 里抛异常，Vue 只会 `console.error` 一句，DOM 停在上一次成功渲染的状态，
 * 用户看到的是**半旧的界面**或整页空白，而页面上没有任何提示、没有出口，只能靠手动刷新。
 *
 * ## 为什么状态放在模块级而不是 Pinia store
 * 触发它的是一条已经出错的调用栈。此刻最不该做的事就是依赖那些可能正是错误来源的东西
 * （store 初始化、路由、naive provider）。模块级 `ref` 没有任何依赖，`App.vue` 直接读它渲染浮层，
 * 即使整个 Pinia 挂了也能显示。
 *
 * ## 分级：只有「页面已经不可用」才弹全屏浮层
 *   · `app.config.errorHandler`（setup / render / 生命周期里的异常）→ 浮层。
 *     Vue 已经把该组件树废掉了，继续留在页面上没有意义。
 *   · `window.error`（未被任何 try/catch 接住的同步异常）→ 浮层。
 *   · `unhandledrejection`（未处理的 Promise 拒绝）→ **只记日志，不弹浮层**。
 *     轮询/懒加载这类后台请求漏了 catch 太常见，为一次网络失败糊住整个界面，
 *     比现在静默更糟。真正的网络错误由各自的 `useApi` 拦截器与 toast 负责。
 *
 * ## 刷新提示
 * 动态 import 失败（`Failed to fetch dynamically imported module`）在本项目有特定含义：
 * Web 面板是整包 OTA 的，新版本上线后旧页面的 hash 文件名已不存在 —— 此时**刷新即可恢复**，
 * 而不是"程序有 bug"。这类错误在文案上单独给一句，免得用户以为设备坏了。
 */
import { readonly, ref } from 'vue';

export interface FatalErrorInfo {
  /** 一句话结论（展示在标题下方） */
  message: string;
  /** 原始错误堆栈或组件信息，放在可滚动的详情区 */
  detail: string;
  /** 是否是「刷新就能好」的那类（动态 import 404） */
  reloadHint: boolean;
}

const state = ref<FatalErrorInfo | null>(null);

/** 只读快照，供 `App.vue` 渲染浮层 */
export const fatalError = readonly(state);

/** 详情区最多留多少字符：堆栈可能几千行，全塞进 DOM 没意义，也拖慢渲染 */
const MAX_DETAIL = 4000;

/**
 * 浏览器自己就会派发、但**页面完全没事**的告警。
 *
 * 目前只有一种：`ResizeObserver loop completed with undelivered notifications.`
 * 它是浏览器在同帧内连续收到 resize 通知时的提示（规范允许、无法完全避免，
 * 任何用了 ResizeObserver 的组件库都可能触发），`window.onerror` 会把它当普通错误派发。
 * 如果不过滤，用户看到的是一整屏"界面可能已经停止响应"——而实际上什么都还能点。
 *
 * 处理方式：**只打 warn，不进致命态**。真正导致循环的代码仍然按 bug 去修（见 ScrollingLineChart），
 * 但这类告警不该成为"页面崩了"的判据。
 */
const BENIGN_PATTERNS = [
  'ResizeObserver loop completed with undelivered notifications',
  'ResizeObserver loop limit exceeded',
];

export function isBenignBrowserWarning(message: string): boolean {
  return BENIGN_PATTERNS.some((p) => message.includes(p));
}

/** 这些错误是「整包 OTA 后旧 chunk 已下线」，刷新即可恢复，不是代码 bug */
const RELOAD_PATTERNS = [
  'Failed to fetch dynamically imported module',
  'Importing a module script failed',
  'error loading dynamically imported module',
];

function stringify(err: unknown): string {
  if (err instanceof Error) return err.stack || `${err.name}: ${err.message}`;
  if (typeof err === 'string') return err;
  try {
    return JSON.stringify(err, null, 2);
  } catch {
    return String(err);
  }
}

/**
 * 报告一个致命错误。可以重复调用：后一条覆盖前一条（浮层只显示最新的那次，
 * 同一页面连续出错时用户需要的也是最后一次的信息）。
 */
export function reportFatal(error: unknown, context = ''): void {
  // 浏览器自身的良性告警不改变页面状态：记一条 warn 就够，别糊用户一屏。
  const raw = error instanceof Error ? error.message : typeof error === 'string' ? error : '';
  if (isBenignBrowserWarning(raw)) {
    console.warn(`[ignored]${context ? ` (${context})` : ''}`, raw);
    return;
  }

  // 先无条件打日志：浮层可能因为别的原因没渲染出来，控制台里的这一条才是排查入口。
  console.error(`[fatal]${context ? ` (${context})` : ''}`, error);

  const detail = stringify(error);
  // 标题只用「像人话」的那两种：Error.message / 字符串本身。
  // 其它抛出物（`throw {code:500}`、`Promise.reject(Symbol())`）走通用文案 ——
  // 标题区显示 `[object Object]` 只会让人以为是你写错了，而不是设备出了什么事。
  const message = error instanceof Error ? error.message : typeof error === 'string' ? error : '';
  state.value = {
    message: message || '页面渲染出错',
    detail: (context ? `${context}\n\n` : '') + detail.slice(0, MAX_DETAIL),
    reloadHint: RELOAD_PATTERNS.some((p) => detail.includes(p)),
  };
}

/** 关掉浮层（用户点「继续使用」）。页面可能仍是坏的，但至少能自己决定刷新时机。 */
export function dismissFatal(): void {
  state.value = null;
}

/**
 * 「同一设备、同一时间只在一个标签页里活跃」的轻量互斥（2026-10-21 决策 D7-3）。
 *
 * ## 为什么需要它
 * core 的凭据模型是**一指纹一 token**：同一浏览器再次 `confirm` 会轮换 token，
 * 先登录的那个标签页随即吃到 444。这是"重配对即吊销"的干净语义（决策 D7 选①），
 * 不该为了多标签去改凭据模型；但**静默互踢**必须变成"知情选择"（选③）：
 * 第二个标签页在登录前会明确告知"另一个标签页已登录，继续会让它退出"，
 * 已在后台被接管的标签页也会收到提示，而不是下次请求突然被踢回登录页。
 *
 * ## 为什么用 localStorage + storage 事件
 * `storage` 事件只在**同源其它标签页**之间触发 —— 正好是我们要的传播范围；
 * 而 localStorage 本身同源共享，两个标签看到的是同一把锁。
 * 标签自身 id 放 sessionStorage：每个标签各自一份，刷新不变、关掉即消失。
 */

const LOCK_KEY = 'ufi-axis-active-tab';
const TAB_KEY = 'ufi-axis-tab-id';

export interface TabLock {
  id: string;
  at: number;
}

/** 当前标签页的 id（没有就生成一个，写进本标签的 sessionStorage）。 */
export function getTabId(): string {
  try {
    let id = sessionStorage.getItem(TAB_KEY);
    if (!id) {
      const bytes = new Uint8Array(8);
      crypto.getRandomValues(bytes);
      id = Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('');
      sessionStorage.setItem(TAB_KEY, id);
    }
    return id;
  } catch {
    // 隐私模式下 sessionStorage 可能不可用：退化成固定值，互斥能力降级但不报错
    return 'unknown';
  }
}

/** 记下「本标签页现在是活跃的那个」。登录成功后调用。 */
export function markActiveTab(): void {
  try {
    const lock: TabLock = { id: getTabId(), at: Date.now() };
    localStorage.setItem(LOCK_KEY, JSON.stringify(lock));
  } catch {
    /* 忽略：拿不到 localStorage 时互斥不可用，但不该影响登录 */
  }
}

/** 读当前活跃标签页；没有或内容损坏返回 null。 */
export function readActiveTab(): TabLock | null {
  try {
    const raw = localStorage.getItem(LOCK_KEY);
    if (!raw) return null;
    const parsed = JSON.parse(raw) as TabLock;
    return parsed && typeof parsed.id === 'string' ? parsed : null;
  } catch {
    return null;
  }
}

/** 是否有**别的**标签页正活跃。 */
export function isAnotherTabActive(): boolean {
  const lock = readActiveTab();
  return !!lock && lock.id !== getTabId();
}

/**
 * 监听活跃标签页变化。
 *
 * @param cb 回调参数：当前活跃锁（可能为 null）与**本标签**的 id
 * @returns 取消监听的函数（组件卸载时调用）
 */
export function onActiveTabChanged(cb: (active: TabLock | null, self: string) => void): () => void {
  const handler = (e: StorageEvent) => {
    if (e.key !== LOCK_KEY) return;
    cb(readActiveTab(), getTabId());
  };
  window.addEventListener('storage', handler);
  return () => window.removeEventListener('storage', handler);
}

/** 释放锁（退出登录/掉线时调用；只有自己持有才清，避免误删别人的）。 */
export function releaseActiveTab(): void {
  try {
    const lock = readActiveTab();
    if (lock && lock.id === getTabId()) localStorage.removeItem(LOCK_KEY);
  } catch {
    /* 忽略 */
  }
}

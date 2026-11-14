/**
 * 浏览器系统通知（Web Notification API）。
 *
 * 2026-10-06：验证码卡片与告警此前只有页面内 toast —— 用户切到别的标签页就看不见。
 * 这里补一层系统级通知：页面不在前台（document.hidden）时才发，避免「页面里已经
 * 有卡片、系统通知又叠一份」的双重打扰。
 *
 * 权限：Web Notification API，浏览器弹窗授权（非 Android 权限，与 core 无关）。
 * 未授权 / 被拒绝时静默降级为「只显示页内 toast」，绝不反复弹授权框骚扰用户。
 */

const LS_ENABLED = 'ufi.browserNotify.enabled';

/** 用户开关（localStorage 持久化）。默认关：首次使用由用户在设置里主动打开。 */
export function isBrowserNotifyEnabled(): boolean {
  return localStorage.getItem(LS_ENABLED) === 'true';
}

/**
 * 打开开关：必须由用户手势触发（设置面板的点击事件里调）——
 * 浏览器规定 Notification.requestPermission() 在无手势上下文会被直接拒绝。
 * @return 授权结果文案，供设置面板展示
 */
export async function enableBrowserNotify(): Promise<string> {
  if (typeof Notification === 'undefined') {
    return '当前浏览器不支持系统通知';
  }
  let perm = Notification.permission;
  if (perm === 'default') {
    perm = await Notification.requestPermission();
  }
  if (perm === 'granted') {
    localStorage.setItem(LS_ENABLED, 'true');
    // 发一条测试通知，用户能立刻确认生效
    new Notification('UFI-AXIS', { body: '系统通知已开启：验证码与告警将在后台标签页提醒你', tag: 'ufi-test' });
    return '已开启';
  }
  if (perm === 'denied') {
    return '授权被拒绝：请在浏览器站点设置里允许「通知」后重试';
  }
  return '未完成授权';
}

export function disableBrowserNotify(): void {
  localStorage.setItem(LS_ENABLED, 'false');
}

/**
 * 发一条系统通知。只有「开关开 + 已授权 + 页面不在前台」时才真正发出。
 * @param tag 同 tag 的通知会互相替换（同一验证码重复推送不堆叠）
 */
export function notifySystem(title: string, body: string, tag?: string): void {
  if (!isBrowserNotifyEnabled() || typeof Notification === 'undefined') return;
  if (Notification.permission !== 'granted') return;
  // 页面在前台时不发：页内 toast/卡片已经足够，双份只会造成打扰
  if (!document.hidden) return;
  try {
    const n = new Notification(title, { body, tag: tag ?? 'ufi-notify', icon: '/favicon.svg' });
    // 点击聚焦回页面
    n.onclick = () => {
      window.focus();
      n.close();
    };
  } catch {
    /* SW 环境或构造失败：静默降级 */
  }
}

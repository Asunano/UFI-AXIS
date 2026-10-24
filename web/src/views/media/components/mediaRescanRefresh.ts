/**
 * 「重新扫描之后隔多久补拉列表」这件小事的共用实现（2026-10-14）。
 *
 * ## 为什么需要它
 * `MediaScanDirsSettings` 的「重新扫描」是 `POST /api/media/rescan`，core 侧
 * **异步触发**（立刻回 200，扫描在后台跑，见 `mediaConfigShared.rescanMedia` 的说明）。
 * 所以 `@rescanned` 之后**不能立刻 reload** —— 那时扫描还没完，拉到的还是旧列表，
 * 用户会以为"重扫没用"。正确做法是隔一会儿再拉，而且拉一次不够（大库要几十秒）。
 *
 * ## 为什么节奏写在这里、而不是各页自己定
 * 视频页 / 图片页（以及别人在做的音乐页）如果各写一份，就会出现三种节奏，
 * 而"多久之后刷新"这件事没有任何页间差异 —— 有差异的只是"刷新之后还要不要做别的"
 * （视频页要顺带刷一次预热快照），那由调用方在回调里做。
 *
 * ## 三档的取法
 * 3s：小库（几十个文件）通常已经扫完，这是"立刻见效"的那一档；
 * 8s / 15s：兜住几千个文件的大库。三次都不中就交给用户点「刷新」，
 * 或者等 WS 的 `media:*` 推送（core 扫完会推）。
 * **刻意不做无限轮询**：媒体页可能一直开着，无限轮询等于给设备加一份常驻负载。
 *
 * ## 为什么放在 components/ 下
 * 本次的写权限只到 `views/media/components/**`。它本身与"组件"无关（没有模板），
 * 但它是这两个页面共用的东西，放这里比复制两份好。
 *
 * ## 否决过的替代方案
 * · **各页写一份 `setTimeout`**：见上。
 * · **用 `setInterval` 轮询直到列表变化**：判断"变化了没有"要比较整份列表
 *   （还要处理"本来就该是这些"的情况），而且列表没变时它永远不会停。
 * · **`await` 一个固定的 sleep 再 reload**：把用户的操作卡在一个不可见的等待里，
 *   而且和 `rescanMedia` 的语义（立刻返回）不符。
 */
import { onUnmounted } from 'vue';

/**
 * 补拉的三个时间点（毫秒，从触发重扫那一刻起算）。
 * 导出是为了让页面能在注释/文案里引用同一份数字，不要各写一套。
 */
export const MEDIA_RESCAN_REFRESH_DELAYS: readonly number[] = [3000, 8000, 15000];

/**
 * 拿到一个 `refreshAfterRescan(run)`：连排三次补拉，重复调用会**重置**这一串
 * （用户连点两次「重新扫描」不该得到六次补拉）。
 *
 * 组件卸载时自动清掉未触发的定时器（`onUnmounted` 只能在 setup 里注册，
 * 所以本函数必须在 setup 里调用）。
 */
export function useRescanRefresh() {
  const timers: number[] = [];

  function cancel() {
    while (timers.length > 0) {
      const t = timers.pop();
      if (t !== undefined) window.clearTimeout(t);
    }
  }

  function refreshAfterRescan(run: () => void) {
    cancel();
    for (const delay of MEDIA_RESCAN_REFRESH_DELAYS) {
      timers.push(window.setTimeout(run, delay));
    }
  }

  onUnmounted(cancel);

  return { refreshAfterRescan };
}

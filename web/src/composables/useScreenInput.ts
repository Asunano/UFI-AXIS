import { onBeforeUnmount, ref, watch, type Ref } from 'vue';
import {
  AndroidKeyCode,
  AndroidKeyEventAction,
  AndroidKeyEventMeta,
  AndroidMotionEventAction,
  ScrcpyPointerId as PointerId,
} from '@yume-chan/scrcpy';
import type { ScrcpyControlMessageWriter } from '@yume-chan/scrcpy';

/**
 * 触摸 / 按键输入（2026-10-31，S6）。
 *
 * ## 为什么必须是"逐事件触摸流"
 * 设备的 fling（惯性）由 InputManager 根据真实 `ACTION_MOVE` 序列的速度曲线算出来。
 * 把一次拖动压成一个 `swipe` 手势（或只发 down/up 两个点）时，速度曲线是直的，
 * 滑出来的距离和惯性都不对 —— 这是"能点但不能滑"的根源。
 * 所以这里逐事件转发，让设备自己判定。
 *
 * ## 三条必须守住的规则（都有真实故障对应）
 * 1. **中断必补 `ACTION_CANCEL`**：漏发会让设备侧留下一根"按着不放"的手指，
 *    此后整个触摸屏失效直到进程被杀。切标签页、断网、旋转、组件卸载都算中断。
 * 2. **`videoWidth/videoHeight` 用流的 metadata**，不是画布的 CSS 尺寸。
 *    传错会让所有点击整体偏移（比例正确但基准错）。
 * 3. **MOVE 按 rAF 合并**到 ≤60Hz：120Hz 触控板会打满上行带宽，且设备也不会更快。
 */

type Meta = { width: number; height: number };

export function useScreenInput(
  canvas: Ref<HTMLCanvasElement | null>,
  writer: Ref<ScrcpyControlMessageWriter | null>,
  meta: Ref<Meta | null>
) {
  /** 当前是否有一根手指按下 */
  const active = ref(false);

  /**
   * 诊断计数（2026-10-31）。三个数字分别对应三个环节，页面直接显示：
   * `down` = 指针事件到没到画布；`sent` = 控制消息有没有发出去；`err` = 发送时抛了什么。
   * 有它才能一眼看出"没反应"卡在哪一段，而不是再猜一轮。
   */
  const downCount = ref(0);
  const sentCount = ref(0);
  const lastError = ref('');

  let pointerId: number | null = null;
  let pending: { x: number; y: number } | null = null;
  let raf = 0;

  const clamp = (v: number, lo: number, hi: number) => Math.min(hi, Math.max(lo, v));

  /**
   * 屏幕坐标 → 设备坐标。
   * 画布被 CSS 缩放过（max-width/height），所以按 rect 与视频尺寸的比例换算。
   */
  function toDevice(ev: PointerEvent, m: Meta): { x: number; y: number } {
    const el = canvas.value;
    if (!el) return { x: 0, y: 0 };
    const r = el.getBoundingClientRect();
    if (r.width <= 0 || r.height <= 0) return { x: 0, y: 0 };
    return {
      x: clamp(((ev.clientX - r.left) * m.width) / r.width, 0, m.width - 1),
      y: clamp(((ev.clientY - r.top) * m.height) / r.height, 0, m.height - 1),
    };
  }

  function send(action: AndroidMotionEventAction, x: number, y: number) {
    const w = writer.value;
    const m = meta.value;
    if (!w || !m) {
      lastError.value = !w ? '控制通道未就绪（writer=null）' : '视频尺寸未知（meta=null）';
      return;
    }
    sentCount.value += 1;
    void w
      .injectTouch({
        action,
        pointerId: PointerId.Finger,
        pointerX: x,
        pointerY: y,
        // ★ 必须是流的 metadata 尺寸，不是画布尺寸
        videoWidth: m.width,
        videoHeight: m.height,
        pressure: 1,
        actionButton: 0,
        buttons: 0,
      })
      .catch((e: unknown) => {
        lastError.value = e instanceof Error ? e.message : String(e);
      });
  }

  function flushMove() {
    raf = 0;
    if (!pending) return;
    const p = pending;
    pending = null;
    send(AndroidMotionEventAction.Move, p.x, p.y);
  }

  function onDown(ev: PointerEvent) {
    // 计数放在所有 early-return 之前：这样"事件根本没到画布"和"到了但发不出去"
    // 是两种可区分的现象。
    downCount.value += 1;
    const m = meta.value;
    if (!m || !writer.value) {
      lastError.value = !writer.value ? '控制通道未就绪（writer=null）' : '视频尺寸未知（meta=null）';
      return;
    }
    // 只认第一根手指；第二根按下时补一次 CANCEL 并**真正结束这次拖动**。
    //
    // 原先只发 CANCEL 就 return：`active`/`pointerId` 仍留在原位，第二根手指抬起前
    // 画布不再接受任何按下事件，第一根的拖动也被掐在半路（设备侧收到 DOWN…CANCEL）。
    if (active.value) {
      send(AndroidMotionEventAction.Cancel, 0, 0);
      endPointer();
      return;
    }
    ev.preventDefault();
    pointerId = ev.pointerId;
    canvas.value?.setPointerCapture?.(ev.pointerId);
    active.value = true;
    const p = toDevice(ev, m);
    send(AndroidMotionEventAction.Down, p.x, p.y);
  }

  function onMove(ev: PointerEvent) {
    const m = meta.value;
    if (!m || !active.value || ev.pointerId !== pointerId) return;
    ev.preventDefault();
    pending = toDevice(ev, m);
    if (!raf) raf = requestAnimationFrame(flushMove);
  }

  function onUp(ev: PointerEvent) {
    const m = meta.value;
    if (!m || !active.value || ev.pointerId !== pointerId) return;
    ev.preventDefault();
    // 先把手上的最后一个位置送出去，再抬起来：否则短促拖动会丢掉终点
    if (raf) {
      cancelAnimationFrame(raf);
      flushMove();
    }
    const p = toDevice(ev, m);
    send(AndroidMotionEventAction.Up, p.x, p.y);
    endPointer();
  }

  /** 任何非正常结束都走这里：补 CANCEL，把设备侧的手指收回来 */
  function onCancel(ev?: PointerEvent) {
    if (!active.value) return;
    if (ev) ev.preventDefault();
    send(AndroidMotionEventAction.Cancel, 0, 0);
    endPointer();
  }

  function endPointer() {
    if (raf) {
      cancelAnimationFrame(raf);
      raf = 0;
    }
    pending = null;
    if (pointerId !== null) {
      try {
        canvas.value?.releasePointerCapture?.(pointerId);
      } catch {
        // 指针可能已经释放
      }
    }
    pointerId = null;
    active.value = false;
  }

  // ── 绑定与解绑 ──
  let bound: HTMLCanvasElement | null = null;

  function unbind() {
    if (!bound) return;
    bound.removeEventListener('pointerdown', onDown);
    bound.removeEventListener('pointermove', onMove);
    bound.removeEventListener('pointerup', onUp);
    bound.removeEventListener('pointercancel', onCancel as EventListener);
    bound.removeEventListener('lostpointercapture', onCancel as EventListener);
    bound = null;
  }

  function bind(el: HTMLCanvasElement | null) {
    unbind();
    if (!el) return;
    el.addEventListener('pointerdown', onDown);
    el.addEventListener('pointermove', onMove);
    el.addEventListener('pointerup', onUp);
    el.addEventListener('pointercancel', onCancel as EventListener);
    el.addEventListener('lostpointercapture', onCancel as EventListener);
    bound = el;
  }

  watch(canvas, (el) => bind(el), { immediate: true });

  // 连接断开（writer 没了）或页面隐藏时，把手指收回来
  watch(writer, (w) => {
    if (!w) onCancel();
  });

  function onVisibility() {
    if (document.visibilityState === 'hidden') onCancel();
  }
  document.addEventListener('visibilitychange', onVisibility);

  onBeforeUnmount(() => {
    document.removeEventListener('visibilitychange', onVisibility);
    onCancel();
    unbind();
  });

  // ── 按键 ──
  async function key(code: AndroidKeyCode) {
    const w = writer.value;
    if (!w) return;
    // BACK 走 backOrScreenOn：scrcpy 对它有特殊处理，直接 injectKeyCode 在部分 ROM 上无效
    if (code === AndroidKeyCode.AndroidBack) {
      await w.backOrScreenOn(AndroidKeyEventAction.Down).catch(() => {});
      await w.backOrScreenOn(AndroidKeyEventAction.Up).catch(() => {});
      return;
    }
    const msg = { action: AndroidKeyEventAction.Down, keyCode: code, repeat: 0, metaState: AndroidKeyEventMeta.None };
    await w.injectKeyCode(msg).catch(() => {});
    await w.injectKeyCode({ ...msg, action: AndroidKeyEventAction.Up }).catch(() => {});
  }

  /** 滚轮：交给库的 scroll 控制器做累加与钳制，不要手写字节 */
  function scroll(deltaY: number) {
    const w = writer.value;
    const m = meta.value;
    if (!w || !m) return;
    const v = Math.max(-1, Math.min(1, -deltaY / 240));
    void w
      .injectScroll({
        pointerX: m.width >> 1,
        pointerY: m.height >> 1,
        videoWidth: m.width,
        videoHeight: m.height,
        scrollX: 0,
        scrollY: v,
        buttons: 0,
      })
      .catch(() => {});
  }

  return { active, downCount, sentCount, lastError, bind, unbind, key, scroll, cancel: () => onCancel() };
}

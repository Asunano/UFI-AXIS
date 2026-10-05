/**
 * 「执行 → 等待结果 → 获取结果 → 更新」通用确认器（web 端）。
 *
 * 只服务 A 级操作（耗时/需等待设备生效）。B 级操作继续走 useNetworkControls.runWrite。
 * 轮询节奏复用 contract 的 NetworkModeSwitchProbe（§1.1），不另造数字。
 */
import { ref, onScopeDispose } from 'vue';
import { useMessage } from 'naive-ui';
import {
  NetworkModeSwitchProbe,
  modeProbeIntervalMs,
  shouldKeepProbingMode,
} from '@/api/contract';

export type OperationDialogState =
  | { kind: 'idle' }
  | { kind: 'running' }      // 下发 + 轮询中，弹窗显示圆环
  | { kind: 'hidden' }       // 用户选了"后台进行"：弹窗消失，轮询继续
  | { kind: 'success' }      // 回读命中，短暂停留后自动关闭
  | { kind: 'failed' };      // 受理被拒或预算超时

export interface ApiOperationOptions {
  /** 弹窗标题 */
  title: string;
  /** running 态主文案：必须用"正在…"，下发成功只代表受理 */
  running: string;
  /** 成功 toast：只在回读命中后弹 */
  success: string;
  /** running 态补充说明（如"网络栈会重启，期间信号短暂中断"） */
  hint?: string;
  /** 下发请求。受理判定：data?.success !== false（core 信封失败口径，见 §1.2） */
  submit: () => Promise<{ data: any }>;
  /**
   * 回读并判定设备是否已生效。返回 true = 命中目标值，停止轮询。
   * 抛错按"当次未命中"处理（设备可能正在重启模块），预算内继续问。
   */
  verify: () => Promise<boolean>;
  /** 生效后的额外部动作（刷新本地状态源等） */
  onApplied?: () => void;
  /** 失败态重试动作；缺省不显示重试按钮 */
  retry?: () => void;
}

export function useApiOperation() {
  const message = useMessage();

  const state = ref<OperationDialogState>({ kind: 'idle' });
  const failReason = ref('');

  let disposed = false;
  /**
   * 卸载/路由切走后：停止轮询、不再弹 toast。
   * 极端情况防护：没有它，后台轮询会在用户已离开的页面上弹一条误导提示；
   * useCancellableApi 让卸载后请求 resolve 成 { __canceled: true }，
   * 所以 submit 与 verify 里都要显式识别取消。
   */
  onScopeDispose(() => { disposed = true; });

  function close() { state.value = { kind: 'idle' }; }

  /** 「后台进行」：只藏弹窗，轮询与最终 toast 继续 */
  function toBackground() {
    if (state.value.kind === 'running') state.value = { kind: 'hidden' };
  }

  async function run(opts: ApiOperationOptions): Promise<boolean> {
    // 已有操作正在进行：直接拒绝第二个操作（E-2，web 侧取最小正确解——不复用也不并发）
    if (state.value.kind === 'running' || state.value.kind === 'hidden') {
      message.warning('已有操作正在进行');
      return false;
    }

    failReason.value = '';
    state.value = { kind: 'running' };

    // ── 1. 下发 ──
    let accepted = false;
    let reason = opts.title + '失败';
    try {
      const res = await opts.submit();
      if ((res as any)?.__canceled === true) {
        if (!disposed) close();          // 取消 = 无结果，静默复位不弹提示
        return false;
      }
      accepted = (res as any)?.data?.success !== false;
      if (!accepted) reason = (res as any)?.data?.error || reason;
    } catch (e: any) {
      reason = e?.response?.data?.error || e?.message || reason;
    }
    if (!accepted) {
      if (disposed) return false;
      state.value = { kind: 'failed' };
      failReason.value = reason;
      message.error(reason);
      return false;
    }

    // ── 2. 等待结果：复用 SwitchProbe 节奏（firstDelay 600ms + 13 次有上限轮询）──
    await new Promise((r) => setTimeout(r, NetworkModeSwitchProbe.firstDelayMs));
    let attempt = 0;
    let reached = false;
    for (;;) {
      if (disposed) return false;        // 卸载后不再轮询、不再 toast
      attempt += 1;
      try {
        reached = await opts.verify();
      } catch { /* 当次回读失败不算失败，预算内继续 */ }
      if (!shouldKeepProbingMode(attempt, reached)) break;
      await new Promise((r) => setTimeout(r, modeProbeIntervalMs(attempt)));
    }

    if (disposed) return reached;

    // ── 3. 获取结果 → 更新 ──
    // 重新读一次 kind：await 之后状态可能已被 toBackground 改掉，不能沿用旧窄化
    const kindAfterProbe = (): OperationDialogState['kind'] => state.value.kind;
    if (reached) {
      opts.onApplied?.();
      // 注意：onApplied 可能把 state 置成别的值，读之前重新取，别依赖上面的窄化
      if (kindAfterProbe() !== 'hidden') {
        state.value = { kind: 'success' };
        await new Promise((r) => setTimeout(r, 300));   // 对勾停留，让用户看见
      }
      if (disposed) return true;
      message.success(opts.success);
      close();
    } else {
      failReason.value = `${opts.title}未在预期时间内完成，设备可能仍在后台继续，可稍后刷新查看`;
      if (kindAfterProbe() !== 'hidden') state.value = { kind: 'failed' };
      // 超时用 warning 不是 error：设备往往稍后就生效了，这不是失败是"尚未确认"
      message.warning(failReason.value);
    }
    return reached;
  }

  return { state, failReason, run, close, toBackground };
}

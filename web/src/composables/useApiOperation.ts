/**
 * 「执行 → 等待结果 → 获取结果 → 更新」通用确认器（web 端）。
 *
 * 仅用于 A 级操作，即耗时且需要等待设备生效的操作；B 级操作仍由
 * `useNetworkControls.runWrite` 处理。
 *
 * 轮询节奏取自 contract 的 `NetworkModeSwitchProbe`：首次等待
 * `firstDelayMs`（600 ms），随后按 `modeProbeIntervalMs` 递增间隔回读，
 * 回读次数由 `maxAttempts` 限定，不在本模块另行定义数值。
 */
import { ref, onScopeDispose, type Ref } from 'vue';
import { useMessage } from 'naive-ui';
import {
  NetworkModeSwitchBudgetMs,
  NetworkModeSwitchProbe,
  modeProbeIntervalMs,
  shouldKeepProbingMode,
} from '@/api/contract';

export type OperationDialogState =
  | { kind: 'idle' }
  | { kind: 'running' } // 已下发并在轮询中，弹窗显示进度圆环
  | { kind: 'hidden' } // 已选择「后台进行」：弹窗隐藏，轮询继续
  | { kind: 'success' } // 回读命中目标值，短暂停留后自动关闭
  | { kind: 'failed' }; // 受理被拒或超出时间预算

export interface ApiOperationOptions {
  /** 弹窗标题 */
  title: string;
  /** running 态主文案，须以「正在…」表述：下发成功仅表示请求已被受理 */
  running: string;
  /** 成功提示：仅在回读命中目标值后弹出 */
  success: string;
  /** running 态的补充说明，例如「网络栈会重启，期间信号短暂中断」 */
  hint?: string;
  /** 下发请求。受理判定为 `data?.success !== false`（core 信封的失败约定） */
  submit: () => Promise<{ data: any }>;
  /**
   * 回读设备状态并判定是否已生效。返回 `true` 表示命中目标值，停止轮询。
   *
   * 抛出异常按「本次未命中」处理（设备可能正在重启模块），在时间预算内继续回读。
   */
  verify: () => Promise<boolean>;
  /** 生效后的附加动作，例如刷新本地状态源 */
  onApplied?: () => void;
  /** 失败态的重试动作；缺省时不显示重试按钮 */
  retry?: () => void;
}

/**
 * 状态外置入口：可选的宿主。
 *
 * 不传时，状态位是本 composable 的实例级 ref，随调用方组件一同创建与销毁。
 *
 * 传入宿主（如 `stores/networkOps`）时，状态位归宿主所有：
 *
 *   · 页面切换不再丢失「进行中」状态：状态的生命周期长于页面，任意渲染方读到同一份；
 *   · 执行部分（轮询循环与 toast）仍运行在调用 `useApiOperation` 的作用域内，因此宿主
 *     必须把调用放在与状态同寿命的组件中（见 `DefaultLayout`），`onScopeDispose` 的边界
 *     也随之落在该组件，而非某个页面。
 *
 * 该参数只改变状态的归属；其余语义（并发闸门、hidden 状态收敛、toast 触发时机）不变。
 */
export interface ApiOperationStateHost {
  /** 状态位本身，而非 Pinia 解包后的值 */
  state: Ref<OperationDialogState>;
  /** 失败原因本身，而非 Pinia 解包后的值 */
  failReason: Ref<string>;
}

/** `useApiOperation` 的返回值形状。独立导出，使宿主（store）可仅依赖该类型持有执行器。 */
export interface ApiOperationRunner {
  state: Ref<OperationDialogState>;
  failReason: Ref<string>;
  run: (opts: ApiOperationOptions) => Promise<boolean>;
  close: () => void;
  toBackground: () => void;
}

export function useApiOperation(host?: ApiOperationStateHost): ApiOperationRunner {
  const message = useMessage();

  const state = host?.state ?? ref<OperationDialogState>({ kind: 'idle' });
  const failReason = host?.failReason ?? ref('');

  let disposed = false;
  /**
   * 作用域销毁（组件卸载或路由离开）后：停止轮询，且不再弹出 toast。
   *
   * 该防护不可省略：否则后台轮询会在用户已经离开的页面上弹出误导性提示。
   * `useCancellableApi` 使销毁后的请求 resolve 为 `{ __canceled: true }`，
   * 因此 `submit` 与 `verify` 都必须显式识别取消。
   *
   * 状态外置（传入 host）时该边界不变，但其保护对象不再是页面切换：宿主
   * （`DefaultLayout`）在登录会话内只挂载一次，轮询可跨页继续；登出或卸载应用外壳时
   * 才在此终止。这是「状态生命周期长于页面」的预期结果。
   */
  onScopeDispose(() => {
    disposed = true;
  });

  function close() {
    state.value = { kind: 'idle' };
  }

  /** 「后台进行」：只藏弹窗，轮询与最终 toast 继续 */
  function toBackground() {
    if (state.value.kind === 'running') state.value = { kind: 'hidden' };
  }

  async function run(opts: ApiOperationOptions): Promise<boolean> {
    // 已有操作正在进行：直接拒绝第二个操作。web 侧采用最小正确实现，既不复用也不并发
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
        if (!disposed) close(); // 取消表示无结果：静默复位，不弹提示
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

    // ── 2. 等待结果：复用 SwitchProbe 的节奏（首次等待 600 ms，随后 13 次有上限回读）──
    await new Promise((r) => setTimeout(r, NetworkModeSwitchProbe.firstDelayMs));
    let attempt = 0;
    let reached = false;
    for (;;) {
      if (disposed) return false; // 已销毁：不再轮询，也不再弹出 toast
      attempt += 1;
      try {
        reached = await opts.verify();
      } catch {
        /* 单次回读失败不视为失败，在预算内继续 */
      }
      if (!shouldKeepProbingMode(attempt, reached)) break;
      await new Promise((r) => setTimeout(r, modeProbeIntervalMs(attempt)));
    }

    if (disposed) return reached;

    // ── 3. 获取结果 → 更新 ──
    // 重新读取 kind：await 期间状态可能已被 toBackground 修改，不得沿用上方的类型收窄
    const kindAfterProbe = (): OperationDialogState['kind'] => state.value.kind;
    if (reached) {
      opts.onApplied?.();
      // onApplied 可能把 state 置为其他值：须在读取前重新获取，不得依赖上面的类型收窄
      if (kindAfterProbe() !== 'hidden') {
        state.value = { kind: 'success' };
        await new Promise((r) => setTimeout(r, 300)); // 成功图标停留 300ms，确保用户可见
      }
      if (disposed) return true;
      message.success(opts.success);
      close();
    } else {
      /*
       * 超时文案必须给出实际等待时长，使用户能区分秒级与分钟级等待。
       *
       * 秒数取自 `NetworkModeSwitchBudgetMs`，该常量与 Kotlin `SwitchProbe.TOTAL_BUDGET_MS`
       * 使用同一算式：600 ms + 1.5 s × 4 + 3 s × 8 = 30.6 s。
       * 文案中不得写死数字：预算调整后，文案应随之自动一致。
       */
      failReason.value =
        `${opts.title}未在预期时间内完成（已等待约 ${Math.round(NetworkModeSwitchBudgetMs / 1000)} 秒）` +
        '，设备可能仍在后台继续，可稍后刷新查看';
      if (kindAfterProbe() !== 'hidden') state.value = { kind: 'failed' };
      // 超时使用 warning 而非 error：设备通常稍后即生效，此状态为「尚未确认」而非失败
      message.warning(failReason.value);
    }

    /*
     * 「后台进行」与超时同时发生时，必须将 hidden 状态收敛回 idle。
     *
     * 上面两个分支都带有 `!== 'hidden'` 守卫，用于避免用户选择「后台进行」后弹窗再次
     * 出现；其副作用是 hidden 状态没有任何分支负责收敛。而 `run()` 开头的并发闸门把
     * hidden 也视为「有操作正在进行」，因此超时或回读失败之后，任何 A 级操作都会被
     * 静默拒绝，直到刷新页面。该路径出现概率较高：网络状况差，用户选择「后台进行」
     * 离开，随后回读超时。
     *
     * 收敛原则：操作已经结束（无论成功或超时），hidden 状态即失去意义。成功分支由
     * `close()` 置为 idle，此处主要处理超时分支。
     *
     * 读取前显式断言一次：经过上面的类型收窄，TS 认为此处不可能是 'hidden'，
     * 但 `toBackground` 是在 `await` 期间异步修改状态的，运行时取值范围宽于类型收窄。
     */
    if ((state.value as OperationDialogState).kind === 'hidden') state.value = { kind: 'idle' };
    return reached;
  }

  return { state, failReason, run, close, toBackground };
}

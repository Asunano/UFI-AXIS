/**
 * 「执行 → 等待结果 → 获取结果 → 更新」通用确认器（web 端）。
 *
 * 只服务 A 级操作（耗时/需等待设备生效）。B 级操作继续走 useNetworkControls.runWrite。
 * 轮询节奏复用 contract 的 NetworkModeSwitchProbe（§1.1），不另造数字。
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
  | { kind: 'running' } // 下发 + 轮询中，弹窗显示圆环
  | { kind: 'hidden' } // 用户选了"后台进行"：弹窗消失，轮询继续
  | { kind: 'success' } // 回读命中，短暂停留后自动关闭
  | { kind: 'failed' }; // 受理被拒或预算超时

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

/**
 * 状态外置入口（2026-10-14）。
 *
 * 不传 = 老行为：状态位是本 composable 的实例级 ref，随调用方组件一起生灭。
 * 传了 = 状态位归**宿主**（如 `stores/networkOps`）所有，于是：
 *
 *   · 切页不再让"进行中"消失 —— 状态活在比页面久的地方，谁渲染它都读到同一份；
 *   · 但**执行**（轮询循环 + toast）仍然跑在调用 `useApiOperation` 的那个作用域里，
 *     所以宿主必须把这次调用放在一个和状态同寿命的组件里（见 DefaultLayout 的说明），
 *     `onScopeDispose` 的边界也就落在那里，而不是某个页面上。
 *
 * 只是把"状态放哪"变成可注入的，其余语义（并发闸门、hidden 收敛、toast 时机）一字未改。
 */
export interface ApiOperationStateHost {
  /** 状态位本体（不是解包后的值） */
  state: Ref<OperationDialogState>;
  /** 失败原因本体（不是解包后的值） */
  failReason: Ref<string>;
}

/** `useApiOperation` 的返回值形状。抽出来是为了让宿主（store）能只依赖类型持有它。 */
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
   * 卸载/路由切走后：停止轮询、不再弹 toast。
   * 极端情况防护：没有它，后台轮询会在用户已离开的页面上弹一条误导提示；
   * useCancellableApi 让卸载后请求 resolve 成 { __canceled: true }，
   * 所以 submit 与 verify 里都要显式识别取消。
   *
   * 2026-10-14：外置状态（传了 host）时这条边界**不动**，但它守的不再是"切页"——
   * 宿主（DefaultLayout）在整个登录会话里只挂载一次，所以轮询能跨页继续；
   * 登出、卸载应用壳时才在这里收摊。这正是"状态比页面活得久"要的效果。
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
        if (!disposed) close(); // 取消 = 无结果，静默复位不弹提示
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
      if (disposed) return false; // 卸载后不再轮询、不再 toast
      attempt += 1;
      try {
        reached = await opts.verify();
      } catch {
        /* 当次回读失败不算失败，预算内继续 */
      }
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
        await new Promise((r) => setTimeout(r, 300)); // 对勾停留，让用户看见
      }
      if (disposed) return true;
      message.success(opts.success);
      close();
    } else {
      /*
       * 2026-10-14：超时文案补上「等了多久」。
       *
       * 改前是一句「未在预期时间内完成」——用户不知道这个"预期时间"是 3 秒还是 3 分钟，
       * 只能干等或反复点重试。秒数取 [NetworkModeSwitchBudgetMs]（contract 里与 Kotlin
       * `SwitchProbe.TOTAL_BUDGET_MS` 同一算式的那个常量，600ms + 1.5s×4 + 3s×8 = 30.6s），
       * **不要在文案里再写死一个数字**：预算一改，文案会跟着自动对。
       */
      failReason.value =
        `${opts.title}未在预期时间内完成（已等待约 ${Math.round(NetworkModeSwitchBudgetMs / 1000)} 秒）` +
        '，设备可能仍在后台继续，可稍后刷新查看';
      if (kindAfterProbe() !== 'hidden') state.value = { kind: 'failed' };
      // 超时用 warning 不是 error：设备往往稍后就生效了，这不是失败是"尚未确认"
      message.warning(failReason.value);
    }

    /*
     * 2026-10-14 修（后台进行 + 超时会把这一页的操作永久锁死）：
     *
     * 上面两个分支都带 `!== 'hidden'` 守卫 —— 那是刻意的，为的是用户点了「后台进行」
     * 之后不要又把弹窗弹回来。但守卫的副作用是：**hidden 态没有任何人负责收敛回 idle**。
     * 而 [run] 开头的并发闸门把 hidden 也算作"有操作正在进行"，于是超时（或回读失败）
     * 之后再点任何 A 级操作都会被静默拒绝，直到刷新页面 —— 而这条路径恰恰是最容易走到的
     * 那种：网络不好 → 用户点「后台进行」去干别的 → 回读超时。
     *
     * 收口原则：**这次操作已经结束了**（无论成功还是超时），hidden 就没有存在意义了。
     * 成功那一支 `close()` 本来就会置 idle，这里主要救的是超时那一支。
     *
     * 读的时候刻意断言一次：上面几处窄化之后 TS 认为这里不可能是 'hidden'，
     * 但 `toBackground` 是在 `await` 期间异步改的，运行时的可能比类型收窄更宽。
     */
    if ((state.value as OperationDialogState).kind === 'hidden') state.value = { kind: 'idle' };
    return reached;
  }

  return { state, failReason, run, close, toBackground };
}

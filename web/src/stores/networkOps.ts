import { defineStore } from 'pinia';
import { ref } from 'vue';
import type { ApiOperationRunner, OperationDialogState } from '@/composables/useApiOperation';

/**
 * A 级网络操作（WiFi 总开关 / 移动数据 / 飞行模式 / 制式切换）的状态宿主。
 *
 * ## 该 store 的职责
 *
 * 本 store 持有这批操作的状态位与制式切换的中间态。
 *
 * 若将状态位放在 `useNetworkControls()` 的实例级 ref 上（`wifiOp = useApiOperation()`、
 * `modeOpState`），而该 composable 每页各调用一次，则会出现以下行为：在仪表盘切换 WiFi
 * 开关后进入网络页，进度弹窗消失、操作不可见（反向同理），因为新页面的
 * `useApiOperation` 是一份全新的 `{ kind: 'idle' }`；旧页面卸载时 `onScopeDispose` 还会
 * 把 `disposed` 置真，轮询随之停止，设备是否完成切换不再有观察者。
 *
 * 结论与 `stores/update.ts` 一致：进行中的状态必须存放在生命周期长于组件的位置。
 * 两者的差异仅在于：更新进度在服务端有状态可查（`/api/update/status`），该 store 自行
 * 启动定时器，并可在页面刷新后通过 `resume()` 恢复；而本 store 的操作是「下发一次、
 * 等待设备生效」，服务端没有该次操作的状态（`/api/network/status` 只返回设备当前事实，
 * 无法回答「刚才是哪一次操作」），因此状态只能由前端持有：状态位放在本 store，
 * 执行（下发 → 轮询回读 → 终态）交由与 store 同寿命的执行器完成，见 [attach]。
 *
 * ## 并发闸门
 *
 * 闸门仍在 `useApiOperation.run` 中（running / hidden 均视为「有操作在跑」），
 * 判据为全站一份，而非每页各一份实例：同一时刻只允许一个 A 级操作，第二个请求被拒绝
 * （「已有操作正在进行」）。否则仪表盘与网络页可同时发起 A 级操作，两个设备写操作并发
 * 下发、各自显示进度，互不阻塞。
 *
 * 该约束是收紧而非放宽：这批操作对应「网络栈重启 / 重新拨号」级别的动作，并发下发不存在
 * 正确的收敛结果。闸门只覆盖本 store 的这一批操作；`BandLockModal` / `AppsView` 中的
 * `useApiOperation` 仍是各自独立的实例，因为它们不写同一批设备状态。
 */

/** 一次 A 级操作的完整描述。与 `ApiOperationOptions` 同形，额外提供两个发起方收敛钩子。 */
export interface NetworkOpRequest {
  /** 弹窗标题，同时作为失败文案前缀（`${title}失败` / `${title}未在预期时间内完成`） */
  title: string;
  /** running 态主文案，须以「正在…」表述：下发成功仅表示请求已被受理 */
  running: string;
  /** 成功提示与成功态文案 */
  success: string;
  /** running 态的补充说明 */
  hint?: string;
  /** 下发请求。受理判定沿用 core 信封约定（`data?.success !== false`） */
  submit: () => Promise<{ data: any }>;
  /**
   * 回读并判定设备是否已生效。
   * 必须使用不随页面卸载而取消的 client，见 `useNetworkControls`。
   */
  verify: () => Promise<boolean>;
  /**
   * 每次实际开始下发前调用，重试同样会调用。
   * 供发起方设置「切换中」等中间态；中间态需跨页可见，因此也存放在本 store。
   */
  onStart?: () => void;
  /**
   * 终态（成功 / 失败 / 超时）后调用。失败分支同样必须收敛中间态，
   * 否则 `switchingMode` 会一直停留在「切换中」，制式标签无法更新。
   */
  onSettled?: (reached: boolean) => void;
}

export const useNetworkOpsStore = defineStore('networkOps', () => {
  // ── 弹窗状态（全仓唯一数据源）──
  /**
   * 进行中 / 成功 / 失败；`hidden` 表示已选择「后台进行」：
   * 轮询继续，终态仅通过 toast 通知。
   */
  const state = ref<OperationDialogState>({ kind: 'idle' });
  /** 失败原因。下发被拒时为 core 返回的 error，超时时为「未在预期时间内完成…」 */
  const failReason = ref('');
  /** 弹窗文案，取自最近一次操作，因此弹窗无需知道发起方 */
  const title = ref('');
  const runningText = ref('');
  const successText = ref('');
  const hint = ref<string | undefined>(undefined);

  // ── 制式切换的中间态（同样从 useNetworkControls 的实例级 ref 迁移而来）──
  /**
   * 正在切换的目标制式；`null` 表示没有进行中的切换。
   *
   * 它同时是 `loadDeviceSettings` 的闸门：切换期间设备仍上报旧档位，回读值不得覆盖
   * 待选项。状态提升到 store 后，该闸门为全站生效：在某页发起的切换，其待选档位不会
   * 被其他页面的回读结果改回切换前的值。
   */
  const switchingMode = ref<string | null>(null);
  /** 最近一次切换在时间预算内未读到目标档位（仅当回读已执行时才成立，下发被拒不计入） */
  const modeSwitchTimedOut = ref(false);
  /**
   * 最近一次切换的目标档位。
   *
   * 用于 [modeSwitchTimedOut] 的自动复位：超时表示「预算内未读到」，而非永久失败。
   * 设备常在 30.6 s 之后才上报新档位（弱信号、重新注册较慢）。回读一旦读到该目标，
   * 超时结论即失效，标记必须自动清除，否则入口瓦片会持续显示「· 尚未完成」。
   * 与 [switchingMode] 一样为全站唯一：发起页与观察页必须看到同一目标。
   */
  const modeSwitchTarget = ref<string | null>(null);

  /**
   * 终态计数器：每次 A 级操作收敛（成功 / 失败 / 超时）递增 1。
   *
   * 消费方（`useNetworkControls`）据此回读自身状态源。页面可能已不是发起操作的那一个，
   * 但仍需刷新，这是状态外置后唯一需要补齐的耦合。使用计数器而非回调：回调会捕获
   * 已卸载的页面，而计数器由当前页面订阅。
   */
  const finishedSeq = ref(0);

  /** 执行器（轮询与 toast 逻辑）。由应用外壳在 setup 中创建，见 [attach]。 */
  let runner: ApiOperationRunner | null = null;
  /** 最近一次请求。失败态的「重试」按原样重新执行该请求。 */
  let lastRequest: NetworkOpRequest | null = null;

  /**
   * 状态位本体，供应用外壳创建执行器时使用。
   *
   * 不在 store 内直接调用 `useApiOperation()`：该 composable 依赖 `useMessage()`，
   * 而 `useMessage` 基于 `inject`，仅在 `n-message-provider` 之内的组件 setup 中可用。
   * store 的 setup 不保证运行于该上下文（取决于首次调用 `useNetworkOpsStore()` 的位置），
   * 因此执行器由应用外壳创建，再将状态位交给它。此处返回原始 ref，而非 Pinia 解包后的值。
   */
  function opHost() {
    return { state, failReason };
  }

  /**
   * 挂载执行器。由 `DefaultLayout` 在 setup 中调用一次：该组件既位于
   * `n-message-provider` 之内，又与本次登录会话同寿命，因此 `useApiOperation` 中的
   * `onScopeDispose` 边界落在「登出 / 卸载应用外壳」，而非页面切换。
   *
   * 与 `stores/player.ts` 的 `attach(HTMLAudioElement)` 模式一致：store 持有状态，
   * 常驻组件在挂载时注入运行所需的依赖（`<audio>` 元素或此处的执行器），
   * 各 UI 组件无需自行管理生命周期。
   */
  function attach(next: ApiOperationRunner) {
    // 重新进入应用外壳（登出后再次登录）：上一次会话的操作结论不属于新会话，不得带入新界面
    if (runner) {
      state.value = { kind: 'idle' };
      failReason.value = '';
      title.value = '';
      runningText.value = '';
      successText.value = '';
      hint.value = undefined;
      switchingMode.value = null;
      modeSwitchTimedOut.value = false;
      modeSwitchTarget.value = null;
      lastRequest = null;
    }
    runner = next;
  }

  /**
   * 发起一次 A 级操作。返回设备是否已在时间预算内生效。
   *
   * 并发闸门不在此层：`useApiOperation.run` 在开头拦截（running / hidden 均视为运行中），
   * 且其为全站唯一实例，因此闸门天然是全局的，见文件头的说明。
   */
  async function run(req: NetworkOpRequest): Promise<boolean> {
    if (!runner) {
      // 正常情况下不会发生：可发起这批操作的页面都挂载在 DefaultLayout 之下，
      // 父组件 setup 必先于子路由组件执行。若从应用外壳之外调用，须显式报错，
      // 不得静默丢弃一次设备写操作。
      console.error('[networkOps] 执行器尚未挂载，A 级操作被忽略');
      return false;
    }

    /*
     * 已有操作正在运行（running / hidden，与 runner 的闸门判据一致）时，除将请求交给
     * runner 拒绝外，不得修改任何状态：闸门会提示「已有操作正在进行」并返回 false，
     * 而下面的文案、lastRequest、onStart / onSettled 均属于正在运行的那一次操作。
     * 否则后发请求的标题会覆盖进度弹窗文案，且 onSettled 会清除其中间态
     * （如制式切换的 switchingMode），使仍在运行的操作失去「切换中」标记。
     */
    const willRun = state.value.kind !== 'running' && state.value.kind !== 'hidden';
    if (willRun) {
      lastRequest = req;
      title.value = req.title;
      runningText.value = req.running;
      successText.value = req.success;
      hint.value = req.hint;
      req.onStart?.();
    }

    let reached = false;
    try {
      reached = await runner.run({
        title: req.title,
        running: req.running,
        success: req.success,
        hint: req.hint,
        submit: req.submit,
        verify: req.verify,
      });
    } finally {
      if (willRun) {
        // 使用 finally 而非仅成功分支：超时与下发被拒同样必须释放中间态，否则「切换中」将永久滞留
        req.onSettled?.(reached);
        finishedSeq.value += 1;
      }
    }
    return reached;
  }

  /** 失败态「重试」：按原样重新执行最近一次请求（该按钮仅在 failed 态渲染） */
  function retryLast() {
    if (!lastRequest) return;
    void run(lastRequest);
  }

  /** 关闭弹窗，将本次操作的状态位收敛回 idle */
  function close() {
    runner?.close();
  }

  /** 「后台进行」：仅隐藏弹窗，轮询与终态 toast 继续（`useApiOperation` 的 hidden 态） */
  function toBackground() {
    runner?.toBackground();
  }

  return {
    state,
    failReason,
    title,
    runningText,
    successText,
    hint,
    switchingMode,
    modeSwitchTimedOut,
    modeSwitchTarget,
    finishedSeq,
    opHost,
    attach,
    run,
    retryLast,
    close,
    toBackground,
  };
});

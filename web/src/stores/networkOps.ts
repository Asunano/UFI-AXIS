import { defineStore } from 'pinia';
import { ref } from 'vue';
import type { ApiOperationRunner, OperationDialogState } from '@/composables/useApiOperation';

/**
 * A 级网络操作（WiFi 总开关 / 移动数据 / 飞行模式 / 制式切换）的**状态宿主**（2026-10-14）。
 *
 * ── 为什么要有这个 store ──
 *
 * 这一批操作原来把状态位放在 `useNetworkControls()` 的实例级 ref 上
 * （`wifiOp = useApiOperation()`、`modeOpState`），而那个 composable 是**每页各调一次**：
 * 在仪表盘点 WiFi 开关 → 切到网络页 → 进度弹窗消失、操作不可见（反过来也一样），
 * 因为新页面的 `useApiOperation` 是一份全新的 `{ kind: 'idle' }`；
 * 更糟的是旧页面被卸载时 `onScopeDispose` 把 `disposed` 置真，**轮询直接停了**，
 * 于是"设备到底有没有切过去"这件事再也没人盯着。
 *
 * 这与 `stores/update.ts` 是同一条结论：**进行中的状态必须活在比组件久的地方**。
 * 两者的取舍差异只有一处 —— 更新的进度是**服务端有状态可查**的（`/api/update/status`），
 * 所以那个 store 自己起定时器 + `resume()` 就能在刷新页面后把真相捞回来；
 * 而这里的操作是"下发一次、等设备生效"，**服务端没有这次操作的状态可查**
 * （`/api/network/status` 只答设备当前事实，答不出"你刚才那次点的是哪一个"），
 * 所以状态只能由前端自己拿住：状态位放在本 store，执行（下发 → 轮询回读 → 终态）交给
 * 一个与 store 同寿命的执行器 —— 见 [attach]。
 *
 * ── 并发闸门（行为变化，2026-10-14）──
 *
 * 闸门仍在 `useApiOperation.run` 里（running / hidden 都算"有操作在跑"），
 * 但判据从"每页各一份实例"变成了**全站一份**：
 *   · 改前：仪表盘能发起一个 A 级操作，网络页同时也能发起另一个 —— 两个设备写操作并发下发，
 *     两边各自显示各自的进度，谁也拦不住谁；
 *   · 改后：同一时刻只有一个 A 级操作在跑，第二个会被拒（"已有操作正在进行"）。
 * 这是收紧，不是放宽：这批操作背后都是"网络栈重启 / 拨号重来"级别的动作，并发下发本来就没有
 * 正确的收敛结果。注意它只覆盖本 store 这一批操作，`BandLockModal` / `AppsView` 里的
 * `useApiOperation` 仍是各自独立的实例（它们不写同一批设备状态，本次不动）。
 */

/** 一次 A 级操作的完整描述。与 `ApiOperationOptions` 同形，只是多了两个"发起方收敛"钩子。 */
export interface NetworkOpRequest {
  /** 弹窗标题，同时是失败文案前缀（`${title}失败` / `${title}未在预期时间内完成`） */
  title: string;
  /** running 态主文案：必须是"正在…"，下发成功只代表受理 */
  running: string;
  /** 成功 toast / 成功态文案 */
  success: string;
  /** running 态补充说明 */
  hint?: string;
  /** 下发请求。受理判定沿用 core 信封口径（`data?.success !== false`） */
  submit: () => Promise<{ data: any }>;
  /** 回读并判定设备是否已生效。**必须用不随页面卸载取消的 client**，见 useNetworkControls */
  verify: () => Promise<boolean>;
  /**
   * 每次真正开始下发前调用（**重试也会调**）。
   * 给发起方置"切换中"这类中间态用 —— 中间态要跨页可见，所以它们也住在本 store。
   */
  onStart?: () => void;
  /**
   * 终态（成功 / 失败 / 超时）后调用。**失败分支也必须收敛中间态**，
   * 否则像 `switchingMode` 会永远卡在"切换中"，把这一页的制式标签钉死。
   */
  onSettled?: (reached: boolean) => void;
}

export const useNetworkOpsStore = defineStore('networkOps', () => {
  // ── 弹窗状态（全仓唯一真源）──
  /** 进行中 / 成功 / 失败；`hidden` = 用户选了"后台进行"，轮询继续、终态只发 toast */
  const state = ref<OperationDialogState>({ kind: 'idle' });
  /** 失败原因。下发被拒时是 core 的 error，超时时是"未在预期时间内完成…" */
  const failReason = ref('');
  /** 弹窗文案：跟着**最近一次**操作走，所以弹窗不必知道是谁发起的 */
  const title = ref('');
  const runningText = ref('');
  const successText = ref('');
  const hint = ref<string | undefined>(undefined);

  // ── 制式切换的中间态（原来也在 useNetworkControls 的实例级 ref 上）──
  /**
   * 正在切换中的**目标**制式；null = 没有进行中的切换。
   * 它同时是 `loadDeviceSettings` 的闸门：切换期间设备仍报旧档位，回读值不许覆盖待选项。
   * 提到 store 之后，这个闸门变成**全站**的 —— 在 A 页发起的切换，B 页的回读也不会把
   * 待选档位弹回切换前的值（改前 B 页根本不知道 A 页在切）。
   */
  const switchingMode = ref<string | null>(null);
  /** 最近一次切换在预算内没等到设备报出目标档位（**只有回读跑过才算**，下发被拒不算） */
  const modeSwitchTimedOut = ref(false);

  /**
   * 终态计数器：每次 A 级操作收敛（成功 / 失败 / 超时）都 +1。
   *
   * 消费方（`useNetworkControls`）据此回读自己的状态源 —— 页面可能已经不是发起它的那一个了，
   * 而"现在活着的那一页"照样要刷新：这正是状态外置之后唯一需要补上的耦合，
   * 用计数器比回调干净（回调会捕获已经卸载的页面，而计数器天然由当前页面订阅）。
   */
  const finishedSeq = ref(0);

  /** 执行器（轮询 + toast 那段逻辑）。由应用壳在 setup 里创建，见 [attach]。 */
  let runner: ApiOperationRunner | null = null;
  /** 最近一次请求。失败态的「重试」按它原样再跑一遍。 */
  let lastRequest: NetworkOpRequest | null = null;

  /**
   * 状态位本体交给应用壳去造执行器用。
   *
   * 为什么不是直接在 store 里 `useApiOperation()`：那个 composable 需要 `useMessage()`，
   * 而 `useMessage` 走的是 `inject`，**只有在 `n-message-provider` 之内的组件 setup 里才拿得到**。
   * store 的 setup 不保证在那个上下文里执行（谁先调 `useNetworkOpsStore()` 就在谁的上下文里跑），
   * 所以执行器由应用壳创建、再把状态位交给它。这里返回的是**原始 ref**，不是 Pinia 解包后的值。
   */
  function opHost() {
    return { state, failReason };
  }

  /**
   * 挂载执行器。由 `DefaultLayout` 在 setup 里调用一次 ——
   * 它是唯一"既在 `n-message-provider` 之内、又和本次登录会话同寿命"的组件，
   * 所以 `useApiOperation` 里的 `onScopeDispose` 边界正好落在"登出 / 卸载应用壳"，
   * 而不是"切页"。
   *
   * 与 `stores/player.ts` 的 `attach(HTMLAudioElement)` 是同一个套路：
   * store 拿状态，常驻组件在挂载时把"要跑起来需要的那件东西"（`<audio>` 元素 / 这里的执行器）
   * 交进来，两处 UI 谁都不用自己管生命周期。
   */
  function attach(next: ApiOperationRunner) {
    // 重新进入应用壳（登出 → 再登录）：上一个会话的操作结论不属于新会话，不能带进新界面
    if (runner) {
      state.value = { kind: 'idle' };
      failReason.value = '';
      title.value = '';
      runningText.value = '';
      successText.value = '';
      hint.value = undefined;
      switchingMode.value = null;
      modeSwitchTimedOut.value = false;
      lastRequest = null;
    }
    runner = next;
  }

  /**
   * 发起一次 A 级操作。返回"设备是否已在预算内生效"。
   *
   * 并发闸门不在这一层：`useApiOperation.run` 开头就拦（running / hidden 都算在跑），
   * 而它是全站唯一实例，所以闸门自然是全局的 —— 见文件头 2026-10-14 的说明。
   */
  async function run(req: NetworkOpRequest): Promise<boolean> {
    if (!runner) {
      // 正常到不了：能发起这批操作的页面都挂在 DefaultLayout 之下，父组件的 setup 必先于子路由组件。
      // 真出现（以后有人从应用壳之外调用），宁可明确报错也不静默丢掉一次设备写操作。
      console.error('[networkOps] 执行器尚未挂载，A 级操作被忽略');
      return false;
    }

    /*
     * 已经有操作在跑（running / hidden —— 与 runner 的闸门同一判据）时，
     * **除了把请求交给 runner 去拒绝，一个字都不要动**：
     * 闸门会弹"已有操作正在进行"并返回 false，而下面的文案、lastRequest、onStart/onSettled
     * 全都属于**正在跑的那一次**操作。照写的话，后来者的标题会顶掉进度弹窗上的文案，
     * 更糟的是 onSettled 会把它的中间态（比如制式切换的 switchingMode）清掉，
     * 让那次还在跑的操作失去"切换中"标记。
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
        // finally 而不是成功分支：超时、下发被拒同样要放开中间态（否则"切换中"会永久卡住）
        req.onSettled?.(reached);
        finishedSeq.value += 1;
      }
    }
    return reached;
  }

  /** 失败态「重试」：把最近一次请求原样再跑一遍（弹窗只在 failed 态渲染这颗按钮） */
  function retryLast() {
    if (!lastRequest) return;
    void run(lastRequest);
  }

  /** 关闭弹窗（把这次操作的状态位收敛回 idle） */
  function close() {
    runner?.close();
  }

  /** 「后台进行」：只藏弹窗，轮询与终态 toast 继续（`useApiOperation` 的 hidden 态） */
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
    finishedSeq,
    opHost,
    attach,
    run,
    retryLast,
    close,
    toBackground,
  };
});

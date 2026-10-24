/**
 * 诊断中心的运行状态（2026-10-14 新建）。
 *
 * ## 用途
 * 驱动 `/diagnostics` 上每一项检查：**跑一次 → 存一份结果**，并把"正在跑 / 排队中 / 从没跑过"
 * 这三种态暴露给卡片。动作本身极简（没有服务端作业、没有轮询），复杂度全在两处调度决定上：
 *
 * ## 决定一：状态放在模块作用域，不放在组件里
 * 手法与 `views/resources/useResourceTask.ts`、`views/files/useBatchOperation.ts` 一致。
 * 理由在诊断页特别实在：`traceroute` 一条最长可以跑 **120 秒**（15 跳 × 每跳约 5 秒，
 * core 侧逐跳发 ping），而它又是用户点完就想走开的那类操作 ——
 * 状态若挂在组件上，切走页面就把它连结果一起丢掉，回来只能重跑一次。
 *
 * ## 决定二：自动检查**串行**，手动检查立即跑
 * · 自动那 13 条里有四条会真发 root shell（`cpu-probe` 一条就 20+ 条命令、`diagnose` 4 条、
 *   `root-channel` / `magisk` 各 1 条），而 core 侧 `ShellQoS` 对 root 通道是**全局信号量限流**的。
 *   并发发出去只会互相排队，把彼此拖到超时 —— 表现就是"进页面一片红"，
 *   而真正的原因是我们自己把设备打满了。所以自动批走一条串行队列。
 * · 手动那四条（网络工具）**不排这条队列**：它们走 `/system/bin/ping` 与 Java socket，
 *   完全不碰 ShellQoS 的 root 通道，而且用户刚点下去就期待有反应，
 *   让它排在 `cpu-probe` 后面等 15 秒是不可接受的。
 *
 * ## 决定三：用非取消版客户端
 * `getApiClient()` 而不是 `useCancellableApi()`。后者的"卸载中断"会 resolve 成
 * `data === undefined`（见那个 composable 的文件头），而这里 `data === undefined`
 * 会被读成"core 回了空响应"—— 一次切页就会把结论写成"拿不到"，正是它注释里点名的那类误判。
 * 诊断项本身没有副作用，跑完就写完事，不需要在卸载时掐断。
 *
 * ## 否决过的替代方案
 * · **每张卡片自己 `await item.run()`**：那样"进页面就跑一遍"会变成 11 个并发请求
 *   （见决定二），而且切页后结果全丢、重进页面又跑一遍。
 * · **并发跑 + 全局节流**：core 侧的限流在服务端，客户端猜不到当前有多少许可空闲，
 *   猜错的表现与不做节流一样。串行是唯一不需要猜的排法。
 * · **给每条结果加超时/重试**：请求超时已经写在描述符里（各条不同，见 `diagnosticRegistry.ts`），
 *   再加一层重试会让"设备真的慢"变成"我们要等两倍时间"。
 */
import { reactive } from 'vue';
import { DIAGNOSTICS, failResult, type DiagnosticItem, type DiagnosticResult } from './diagnosticRegistry';

/** 一项检查的运行时状态。模块作用域持有，见文件头决定一。 */
export interface DiagnosticRunState {
  /** 最近一次结果；null = 从没跑过（或还没跑完） */
  result: DiagnosticResult | null;
  /** 正在跑（请求在途） */
  running: boolean;
  /** 已经排进自动队列、还没轮到。卡片据此显示「排队中」而不是「未运行」 */
  queued: boolean;
  /** 最近一次跑完的时刻（epoch ms）；0 = 从没跑过。用于"多久之前跑的" */
  finishedAt: number;
}

/**
 * id → 状态。
 *
 * 用**普通 Map + 每个值单独 `reactive()`**，而不是 `reactive<Record<…>>({})`：
 * 后者会把整张表变成深层代理，而模板里是按 id 取一项 —— 那种写法在
 * `useResourceTask.ts` 里已经踩过（那里留了同样的注释），照抄同一手法。
 */
const states = new Map<string, DiagnosticRunState>();

/** 取（必要时建）某一项的状态。**惰性创建**：从没跑过的项不该在首屏占一个响应式对象。 */
export function diagnosticStateOf(id: string): DiagnosticRunState {
  const existing = states.get(id);
  if (existing) return existing;
  const created = reactive<DiagnosticRunState>({ result: null, running: false, queued: false, finishedAt: 0 });
  states.set(id, created);
  return created;
}

/** 已经跑过结果的项（导出诊断包用；**只收跑过的**，没收到的在包里如实写"未运行"）。 */
export function diagnosticSnapshot(): Array<{ item: DiagnosticItem; state: DiagnosticRunState }> {
  return DIAGNOSTICS.map((item) => ({ item, state: diagnosticStateOf(item.id) }));
}

/**
 * 自动批的串行队列。
 *
 * `Promise` 链而不是 `for` + `await`：入队要**立刻返回**（页面挂载时不能等 11 条跑完），
 * 而链上的每一项都自己吞掉异常（`runOne` 内部 catch），所以这条链永远不会 reject ——
 * 一次失败不会让后面所有检查都不跑。
 */
let queue: Promise<void> = Promise.resolve();

/** 跑一项。单飞：同一项在途时直接返回（按钮已经 :loading，这里是第二道保险）。 */
async function runOne(item: DiagnosticItem): Promise<void> {
  const state = diagnosticStateOf(item.id);
  if (state.running) return;
  state.queued = false;
  state.running = true;
  try {
    state.result = await item.run();
  } catch (e) {
    // 描述符可以随便抛（端点 400 / 超时 / 网络断）：结论要落到卡片上，而不是只进控制台。
    state.result = failResult(e);
  } finally {
    state.running = false;
    state.finishedAt = Date.now();
  }
}

/**
 * 立即跑一项（手动项 / 单卡「重跑」）。
 *
 * **刻意不排自动队列**（见文件头决定二）：手动项不碰 root shell 通道，
 * 而用户点了就要有反应。返回 Promise 是为了让调用方能 `void` 掉它。
 */
export function runDiagnosticNow(item: DiagnosticItem): Promise<void> {
  return runOne(item);
}

/**
 * 把若干项排进串行队列（自动批 / 「重跑全部自动检查」）。
 *
 * 入队时记下时刻，轮到它时若 `finishedAt` 已经晚于入队时刻就**跳过** ——
 * 那说明用户在排队期间自己点过这一条的「重跑」，已经有一份更新的结果了。
 * 没有这道判断，用户的一次手点会被队列里的旧任务覆盖成同一份重复请求。
 */
export function enqueueDiagnostics(items: DiagnosticItem[]): void {
  for (const item of items) {
    const state = diagnosticStateOf(item.id);
    if (state.running || state.queued) continue;
    state.queued = true;
    const enqueuedAt = Date.now();
    queue = queue.then(() => {
      // 先清「排队中」再判跳过：无论走哪条分支，这一项都已经不在队列里了，
      // 漏掉这一句的表现是卡片永远停在「排队中」（手动跑过一次的那种情况最容易撞上）。
      state.queued = false;
      if (state.finishedAt >= enqueuedAt || state.running) return;
      return runOne(item);
    });
  }
}

/** 自动批的全部检查项（`mode === 'auto'`）。顺序即 [DIAGNOSTICS] 的声明顺序。 */
export function autoDiagnostics(): DiagnosticItem[] {
  return DIAGNOSTICS.filter((d) => d.mode === 'auto');
}

/**
 * 页面用的入口。与 `useResourceTask()` 同形（返回一组函数，状态在模块里）——
 * 页面不必知道队列与单飞是怎么实现的。
 *
 * 只有一个 `runAuto()`，没有「重跑」的第二个函数：自动批的**范围**是固定的
 * （`mode === 'auto'`），首屏与「重跑全部自动检查」是同一件事，写两个名字只会让人以为
 * 它们跑的东西不一样。手动项（网络四条）**不在其中** —— 没有目标地址时重跑只会得到四条失败。
 */
export function useDiagnosticRun() {
  return {
    stateOf: diagnosticStateOf,
    snapshot: diagnosticSnapshot,
    runNow: runDiagnosticNow,
    runAuto: () => enqueueDiagnostics(autoDiagnostics()),
  };
}

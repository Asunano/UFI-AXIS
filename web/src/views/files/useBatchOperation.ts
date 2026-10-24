/**
 * 文件批量操作队列（2026-10-14 新建）：逐项状态 + 取消剩余 + 失败项单独重试 + 结束后仍可回看。
 *
 * ## 它替换掉的是什么
 * 2026-10-14：原来 `FilesView.runBatch` / `pasteHere` 是「`message.loading` 转一圈 + 两个局部变量 ok/failed」：
 *   · 进度只活在**那条 toast 的文本**里（`m.content = '删除中… 3/10'`），结束即 `m.destroy()`；
 *   · `ok` / `failed` 是函数内的局部变量，函数一返回，**失败的是哪几项就没人记得了**；
 *   · 想中止只能刷新页面 —— 而刷新会把已经做完的部分一并丢掉；
 *   · 失败后唯一的路是整批重来。`pasteHere` 的注释甚至写了「部分失败时保留剪贴板，
 *     用户可重试」，但没有任何地方告诉用户"失败的是哪几个"，只能自己去列表里翻。
 * core 的 `/copy` `/move` `/delete` 都只收单个 source（没有批量端点），所以批量在前端
 * 必然是**串行循环** —— 上面四件事只有在队列里才做得出来，一行 toast 结构上做不到。
 *
 * ## 状态为什么放在模块作用域（而不是 Pinia store，也不是组件实例）
 * 上传队列（`useFileUpload`）是 composable **实例级**的，也就是活不过 FilesView 卸载；
 * 本文件把队列放在模块作用域，于是「切到别的页面再回来」时面板与逐项结果都还在
 * （全仓没有路由级 keep-alive，实例级状态一定会随卸载消失）。
 * 为什么不再往上提成 Pinia store：
 *   · 这份状态的唯一消费者就是文件页，没有任何跨模块读取者（对照 `stores/update.ts`：
 *     它进 store 是因为**服务端**持有作业状态，store 的 `resume()` 是把服务端的真相捞回来）；
 *   · core 没有批量作业端点，服务端不持有这批操作的任何进度，提成 store 只是换一个
 *     **同样活不过刷新**的容器，还要在一个全局 store 里放一堆与其它模块无关的 UI 状态。
 * 所以水平定在「模块级单例」：刷新页面仍会丢（浏览器里没有任何地方能持久化它，core 侧也没有
 * 可查询的作业 id），这一点与上传队列一致，面板上也如实写了。
 *
 * ## 为什么一次只跑一批
 * core 是跑在随身 WiFi 这类弱设备上的单进程，串行是既有取舍（见 FilesView 原 `runBatch` 的注释）；
 * 两批并行只会互相拖慢，还会让"谁先失败"变得不可归因。所以第二批发起时**直接拒绝并提示**，
 * 而不是静默排队 —— 排队会让用户以为点了没反应。
 *
 * ## 取消为什么只取消「还没轮到的项」
 * 中途 abort 那个 HTTP 请求**停不下 core 已经开始的 copy/move/delete**，把它标成"已取消"
 * 就是在谎报结果。所以取消 = 把还没轮到的项标记为已取消，正在跑的那一项等它自己落定。
 * 这与 `useCancellableApi` 的口径一致：取消不是失败，但也不能当成成功。
 */
import { computed, onScopeDispose, ref } from 'vue';

export type BatchItemStatus = 'pending' | 'running' | 'done' | 'error' | 'canceled';

export interface BatchItem {
  id: number;
  /** 源路径（复制/移动）或目标路径（删除）。面板上作为 title，用于区分同名文件 */
  path: string;
  name: string;
  status: BatchItemStatus;
  /** 失败原因，仅 status==='error' 时有值 */
  error: string;
}

export interface BatchJob {
  id: number;
  /** 动作名（复制 / 移动 / 删除）：用于文案，以及"粘贴按钮要不要转圈"的判据 */
  verb: string;
  /** 这一批的目标目录（入队时快照：循环跑到一半时用户可能已经换目录了） */
  dir: string;
  items: BatchItem[];
  /** 这一批还有未落定的项 */
  running: boolean;
  /** 用户点过"取消剩余" */
  canceled: boolean;
}

/** 一个待执行项。`run` 由调用方提供：每种端点、每种冲突规避方式都不一样。 */
export interface BatchTarget {
  path: string;
  name: string;
  /**
   * 执行一项。返回 `false` = core 明确回了失败（`{success:false}` 信封）；
   * 抛错 = 请求层失败（超时/网络/HTTP 错误）。两者都记为该失败项。
   */
  run: () => Promise<boolean>;
}

export interface BatchHooks {
  /** 提示出口（toast）。由页面注入，本 composable 不直接依赖 naive-ui 的 message 实例 */
  notify?: (kind: 'info' | 'success' | 'warning' | 'error', text: string) => void;
  /** 一批全部落定（成功/失败/取消）后回调一次：用于刷新列表、清空剪贴板等 */
  onBatchSettled?: (job: BatchJob) => void;
}

// ── 模块级队列（见文件头的「状态为什么放在模块作用域」）──
const jobs = ref<BatchJob[]>([]);
/** 面板是否被用户收起。也是模块级：切页回来不该自己弹开 */
const collapsed = ref(false);

let seq = 0;
let jobSeq = 0;
/** 串行闸门：同一时刻只允许一批在跑（见文件头） */
let running = false;
/**
 * item.id → 执行函数。刻意**不放进 `jobs`**：函数不可序列化，放进响应式数组会让 Vue
 * 去代理闭包；而且重试要用同一份闭包（粘贴的目标名、冲突规避结果都在里面），
 * 所以它必须活得比"这一次循环"更久 —— 只在清空/取消时释放。
 */
const runners = new Map<number, () => Promise<boolean>>();
/** 当前页面注入的回调；页面卸载时解绑（见 useBatchOperation） */
let hooks: BatchHooks = {};

function errText(e: unknown): string {
  const err = e as { response?: { data?: { error?: string; message?: string } }; message?: string };
  return err?.response?.data?.error || err?.response?.data?.message || err?.message || '操作失败';
}

function findItem(id: number): { job: BatchJob; item: BatchItem } | null {
  for (const job of jobs.value) {
    const item = job.items.find((i) => i.id === id);
    if (item) return { job, item };
  }
  return null;
}

/** 取全表第一个还没跑的项；没有则返回 null（队列结束） */
function nextPending(): { job: BatchJob; item: BatchItem } | null {
  for (const job of jobs.value) {
    for (const item of job.items) {
      if (item.status === 'pending') return { job, item };
    }
  }
  return null;
}

/** 一批的收尾文案。沿用原来 `reportBatch` 的措辞，失败项改为指向面板 */
function summarize(job: BatchJob) {
  const done = job.items.filter((i) => i.status === 'done').length;
  const failed = job.items.filter((i) => i.status === 'error');
  const canceled = job.items.filter((i) => i.status === 'canceled').length;
  const tail = canceled > 0 ? `，已取消 ${canceled} 项` : '';
  if (!failed.length) {
    if (done === 0) {
      hooks.notify?.('info', `${job.verb}已取消${canceled > 0 ? `（${canceled} 项未执行）` : ''}`);
      return;
    }
    hooks.notify?.('success', `${job.verb}完成（${done} 项）${tail}`);
    return;
  }
  const head =
    failed
      .slice(0, 3)
      .map((i) => i.name)
      .join('、') + (failed.length > 3 ? ' 等' : '');
  if (done === 0) {
    hooks.notify?.('error', `${job.verb}失败：${head}（可在进度面板里单独重试）`);
    return;
  }
  hooks.notify?.('warning', `${job.verb}完成 ${done} 项，失败 ${failed.length} 项：${head}${tail}`);
}

/** 一批没有未落定的项了就收尾（只收一次） */
function settleJobIfDone(job: BatchJob) {
  if (!job.running) return;
  if (job.items.some((i) => i.status === 'pending' || i.status === 'running')) return;
  job.running = false;
  summarize(job);
  hooks.onBatchSettled?.(job);
}

/**
 * 串行跑到没有 pending 为止。
 *
 * 与上传队列同款：不是"跑一个传进来的批次"，而是**不断从任务表里取下一个 pending**，
 * 于是重试、新批次都能被当前这轮循环自然接上，调用方不必关心队列是不是已经在跑。
 */
async function pump(): Promise<void> {
  if (running) return;
  running = true;
  try {
    for (;;) {
      const next = nextPending();
      if (!next) break;
      const { job, item } = next;
      const run = runners.get(item.id);
      item.status = 'running';
      let ok = false;
      let reason = '';
      if (!run) {
        // 理论上不该发生；真发生了就标失败，别让整条队列卡在这一项上
        reason = '这一项的重试信息已丢失，请重新发起操作';
      } else {
        try {
          ok = await run();
        } catch (e) {
          reason = errText(e);
        }
      }
      item.status = ok ? 'done' : 'error';
      item.error = ok ? '' : reason || '操作失败';
      settleJobIfDone(job);
    }
  } finally {
    running = false;
  }
}

export function useBatchOperation(h: BatchHooks = {}) {
  hooks = h;
  onScopeDispose(() => {
    // 页面卸载：只解绑回调，**循环本身继续跑完**（请求走全局 axios 单例，不随组件取消，
    // 这也是"切走再回来还能看到进度"的前提）。回来时新实例会重新注入回调。
    if (hooks === h) hooks = {};
  });

  const activeJob = computed(() => jobs.value.find((j) => j.running) ?? null);
  /** 正在跑的动作名（'' = 空闲）。用于让"粘贴"按钮在跨页返回后仍然正确转圈 */
  const activeVerb = computed(() => activeJob.value?.verb ?? '');

  /** 发起一批。`targets` 为空或已有批次在跑时什么都不做（后者给一条提示，见文件头） */
  function start(verb: string, dir: string, targets: BatchTarget[]): void {
    if (!targets.length) return;
    if (running) {
      hooks.notify?.('warning', '已有批量操作正在进行，请等它结束或先取消');
      return;
    }
    const items: BatchItem[] = targets.map((t) => {
      const id = ++seq;
      runners.set(id, t.run);
      return { id, path: t.path, name: t.name, status: 'pending' as const, error: '' };
    });
    // 新的排在最上面（面板是「最近一批在最上」的阅读顺序）
    jobs.value = [{ id: ++jobSeq, verb, dir, items, running: true, canceled: false }, ...jobs.value];
    collapsed.value = false;
    void pump();
  }

  /** 取消一批里所有**还没轮到**的项；正在跑的那一项等它自己落定 */
  function cancelJob(jobId: number): void {
    const job = jobs.value.find((j) => j.id === jobId);
    if (!job) return;
    job.canceled = true;
    for (const item of job.items) {
      if (item.status !== 'pending') continue;
      item.status = 'canceled';
      runners.delete(item.id);
    }
    settleJobIfDone(job);
  }

  function cancelAll(): void {
    for (const job of jobs.value) {
      if (job.running) cancelJob(job.id);
    }
  }

  /** 取消单项。只对 pending 生效：正在跑的那一项已经打到设备上了，标"已取消"就是谎报 */
  function cancelItem(itemId: number): void {
    const found = findItem(itemId);
    if (!found || found.item.status !== 'pending') return;
    found.item.status = 'canceled';
    runners.delete(found.item.id);
    settleJobIfDone(found.job);
  }

  /** 重试单项：把状态打回 pending 再让 pump 接上（不是整批重来） */
  function retryItem(itemId: number): void {
    const found = findItem(itemId);
    if (!found) return;
    const { job, item } = found;
    if (item.status !== 'error' && item.status !== 'canceled') return;
    if (running) {
      hooks.notify?.('warning', '已有批量操作正在进行，请稍后再重试');
      return;
    }
    if (!runners.has(item.id)) {
      hooks.notify?.('error', '这一项的重试信息已丢失，请重新发起操作');
      return;
    }
    item.status = 'pending';
    item.error = '';
    job.canceled = false;
    job.running = true;
    void pump();
  }

  /** 重试一批里所有失败项（跳过信息已丢失的） */
  function retryFailed(jobId: number): void {
    const job = jobs.value.find((j) => j.id === jobId);
    if (!job) return;
    const failed = job.items.filter((i) => i.status === 'error' && runners.has(i.id));
    if (!failed.length) return;
    if (running) {
      hooks.notify?.('warning', '已有批量操作正在进行，请稍后再重试');
      return;
    }
    for (const item of failed) {
      item.status = 'pending';
      item.error = '';
    }
    job.canceled = false;
    job.running = true;
    void pump();
  }

  /** 清掉已经没有未落定项的批次，并释放它们的重试闭包；正在跑的一律留着 */
  function clearFinished(): void {
    jobs.value = jobs.value.filter((j) => j.items.some((i) => i.status === 'pending' || i.status === 'running'));
    const alive = new Set<number>();
    for (const job of jobs.value) {
      for (const item of job.items) alive.add(item.id);
    }
    for (const id of [...runners.keys()]) {
      if (!alive.has(id)) runners.delete(id);
    }
  }

  function toggleCollapsed(): void {
    collapsed.value = !collapsed.value;
  }

  return {
    jobs,
    collapsed,
    activeVerb,
    start,
    cancelJob,
    cancelAll,
    cancelItem,
    retryItem,
    retryFailed,
    clearFinished,
    toggleCollapsed,
  };
}

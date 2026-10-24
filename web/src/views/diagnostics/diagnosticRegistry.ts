/**
 * 诊断中心注册表（2026-10-14 新建）。
 *
 * ## 用途
 * `/diagnostics` 上每一项检查都由本文件的一条 [DiagnosticItem] 描述：
 * 显示名 / 一句话说明 / 分组 / 触发方式（auto | manual）/ `run()` / 渲染器覆盖 / 跳转动作。
 * `DiagnosticsView.vue` 只做三件事 —— 按 group 分三段 `v-for`、首屏把 auto 项跑一遍、
 * 消费 runner 的状态；**它不认识任何一条具体检查**（没有 `id === 'cpuProbe'`，
 * 也没有按 group 写死的模板片段）。
 *
 * ## 切分理由
 * 诊断项之间的差异**全在"打哪个端点、怎么把响应读成一句结论"上**：
 * · 单端点的（SELinux / Magisk / QoS / 监控库 / 数据分区 / 缓存健康 / 媒体权限）读一个 GET 就完事；
 * · 组合的（root 通道 = `shell/root` + `system/root-check`；服务状态 = `service/status` +
 *   `service/crash`）要并发两个端点再合成一句结论；
 * · 网络四条要吃**同一个**目标地址（旧 `DiagnosticModal` 就是一个 host 输入框管四个按钮），
 *   而且它们的"结果"是原始输出本身，不是标签/值。
 * 把这些写成 `if (id === …)` 会立刻长出十来个分支；写成描述符则
 * **加一项检查 = 加一条描述符**，页面一个字都不用动。
 *
 * ## 否决过的替代方案
 * · **给 `run()` 加参数**（`run(ctx)` / `run(input)`）：冻结形状里 `run()` 是无参的。
 *   而真正需要入参的只有网络那四条，它们共用**同一个**目标 —— 所以目标放进模块作用域的
 *   `networkTarget`（单独一个叶子模块，理由见 `networkTarget.ts`：放本文件里会与
 *   `NetworkTargetBar.vue` 形成循环 import），四条描述符各自闭包读它，
 *   页面里由 [DIAGNOSTIC_GROUPS] 的 `toolbar` 渲染。
 *   这样"这一条要不要入参"不会变成每条描述符都得声明一遍的形状。
 * · **把"去日志面板"放进结果里**（`DiagnosticResult.link`）：那会让结果类型长出一个
 *   只有一项用得上的字段，而跳转是**静态**的（"去日志面板"与这次跑出什么无关），
 *   所以放在描述符的 [DiagnosticItem.action] 上。
 * · **在页面里给网络组写一段专用模板**：那等于页面认识 group 的语义，
 *   将来出现第二个需要输入的分组时又得回去改页面。改成 group 描述符自带 `toolbar` 组件。
 * · **复用 `useResourceTask` 那套"轮询到终态"的 runner**：诊断项没有服务端状态可轮询，
 *   每次 `run()` 都是一次性的请求-响应，多一层状态机只会多一处能出错的地方。
 *   两处真正相同的是**状态活在哪**（模块作用域，见 `useDiagnosticRun.ts`）。
 * · **把四条网络工具做成一个带 `tool` 参数的多态描述符**：那会让"按钮文案""超时""成败判据"
 *   全部退化成描述符内部的分支，正是资源中心那条注释里点名否决过的写法。
 *
 * ## 归属（`scope`，2026-10-14 补）：这一项**该不该在另一端也有**
 * 本文件与 app 的 `DiagnosticRegistry.kt` 各维护一份清单，而"哪些检查其实是**同一件事**"
 * 此前只存在于人脑里 —— 加一项 core 类检查时，**没有任何东西会提醒你"另一端也要加"**，
 * 于是同一台设备在两个客户端上看到的结论不一样，而没人发现。
 * 现在每条描述符都必须声明 [DiagnosticScope]：
 * · `core` —— 查的是设备 / core 自己的东西，**两端都必须有**（id 命名可以不同：
 *   本文件的 `storage` 就是 app 的 `env.monitorstorage`）；
 * · `client` —— 只在本端才有意义的检查（浏览器通知授权、这一页的 WebSocket）。
 * `scripts/check-diagnostics-scope.mjs` 拿 `scope` + 一张跨端映射表核对这件事：
 * 哪一项在另一端缺了会直接报错（退出码 1，CI 可跑）。
 * ★ **改这里必须同步另一端**：新增 / 删除 / 改归属任何一条检查，都要去
 * `app/feature-settings/src/main/java/com/ufi_axis/ui/diagnose/DiagnosticRegistry.kt`
 * 对齐，并把新的一对登记进那个脚本的映射表。
 * 页面上每张卡的角标显示的就是这个字段（`DiagnosticCard.vue`），用户据此一眼看出哪些两端都有。
 */
import type { RouteLocationRaw } from 'vue-router';
import { Endpoints } from '@/api/contract';
import { getApiClient } from '@/composables/useApi';
import { useCapabilitiesStore } from '@/stores/capabilities';
import { useWebSocketStore } from '@/stores/websocket';
import { enableBrowserNotify, isBrowserNotifyEnabled } from '@/composables/browserNotify';
import { formatBytes, formatUptime } from '@/composables/utils';
import NetworkTargetBar from './components/NetworkTargetBar.vue';
import { DEFAULT_TCP_PORT, networkTarget } from './networkTarget';

/** 三段：设备环境 / 网络工具 / 服务与诊断快照。顺序即页面顺序。 */
export type DiagnosticGroupKey = 'env' | 'network' | 'service';

export type DiagnosticStatus = 'ok' | 'warn' | 'fail' | 'info';

/**
 * 一项检查的**归属**（2026-10-14 补，动机见文件头「归属」一节）。
 *
 * · `core`：查的是设备 / core 自己的东西 —— **两端都必须有这一项**；
 * · `client`：查的是本端才有的东西（浏览器授权、这一页的 WebSocket）—— 另一端不该有。
 *
 * 它是**机制**而不是注释：`scripts/check-diagnostics-scope.mjs` 靠它 + 跨端映射表
 * 核对"core 项两端齐全"。所以 [DiagnosticItem.scope] 是**必填**的（没有默认值）——
 * 新增一项检查时必须显式回答"这是 core 还是本端"，漏填编译就过不去。
 */
export type DiagnosticScope = 'core' | 'client';

/** 一行"标签 → 值"。渲染器只认这一种形状，所以描述符不必知道界面长什么样。 */
export interface DiagnosticRow {
  label: string;
  value: string;
}

/**
 * 一个**写入动作**（2026-10-14 补）。
 *
 * ## 为什么它挂在 [DiagnosticResult] 上，而不是像 [DiagnosticItem.action] 那样挂在描述符上
 * 本文件上面否决过"把跳转入口放进结果"，理由是**跳转是静态的**（"去日志面板"与这次跑出
 * 什么无关）。写入动作恰好相反：它**只在该状态下才成立** ——
 * "core 缺权限"时才有「补全权限」，"浏览器还没授权"时才有「申请通知授权」。
 * 做成描述符上的常量字段，就必然有一个状态拿到一个错的按钮（权限已被拒绝时还在喊"申请"，
 * 而那正是浏览器**不会再弹框**的状态）。所以它属于结果：**结论本身在告诉你现在能做什么**。
 *
 * ## 硬约束（本仓的写动作约定）
 * 写动作一律**二次确认**（`DiagnosticCard.vue` 用 `n-popconfirm` 渲染），
 * `confirmBody` 必须说清三件事：**改什么、能不能撤销、失败了会怎样**。
 * `run()` 必须返回一句给人看的结果文案 —— 只弹一个"完成"等于把"到底成没成"推给用户去猜。
 */
export interface DiagnosticWriteAction {
  label: string;
  confirmTitle: string;
  confirmBody: string;
  /** 危险动作（改设备状态）：用错误色渲染，并让确认按钮不是默认焦点。 */
  danger?: boolean;
  run(): Promise<string>;
}

/**
 * 一次检查的结论。
 *
 * ★ `summary` 是**折叠时唯一还会显示的那一行**，所以它必须自己就能读懂 ——
 * 不要写成"见下表"，也不要把关键结论只放在 `rows` 里。
 */
export interface DiagnosticResult {
  status: DiagnosticStatus;
  summary: string;
  rows?: DiagnosticRow[];
  /** 原始输出（JSON 或 shell stdout）。可折叠、可复制 —— 排障要的是原文，不是渲染后的字。 */
  raw?: string;
  /** 针对**这个结论**的写入动作；null/缺省 = 没有可做的事（见 [DiagnosticWriteAction]）。 */
  write?: DiagnosticWriteAction;
}

/**
 * 一条检查。
 *
 * [render] 是**渲染器覆盖**，只解决"这一项的主信息不是标签/值"的少数情况：
 * · `verdict`：主信息是一个判定（root 通道、SELinux、服务状态），判定词要放大显示；
 * · `text`：主信息就是原始输出（ping / DNS / TCP / traceroute），原文默认展开；
 * · 缺省（`rows`）：一行结论 + 标签/值表，原始输出折叠。
 * 三种渲染器都在 `DiagnosticCard.vue` 里，**页面不参与**。
 */
export interface DiagnosticItem {
  id: string;
  name: string;
  /** 一句话：这个检查回答什么问题 */
  desc: string;
  group: DiagnosticGroupKey;
  /**
   * 归属：`core` = 两端都必须有；`client` = 只在本端有意义。见 [DiagnosticScope]。
   * 页面据此画角标，跨端自检脚本据此核对两端齐全。
   */
  scope: DiagnosticScope;
  /** auto = 进页面就跑（只读）；manual = 点了才跑 */
  mode: 'auto' | 'manual';
  run(): Promise<DiagnosticResult>;
  render?: 'rows' | 'verdict' | 'text';
  /**
   * 结果区旁边的**静态跳转**（`debug-logs` 去日志面板、`cache-health` 去性能面板）。
   *
   * 与 `run()` **可以共存**：这两条既是检查（读文件清单 / 读缓存统计），
   * 也是"去真源那儿动手"的入口 —— 而写入动作一律留在真源那边，本页只读。
   * 写成描述符的字段而不是页面里的 `v-if`，是为了让"这一页上有哪些入口"同样由注册表说了算。
   */
  action?: { label: string; to: RouteLocationRaw };
}

/** 一个分组的展示信息。`toolbar` 是该组共用的输入区（目前只有网络组有）。 */
export interface DiagnosticGroupSpec {
  key: DiagnosticGroupKey;
  title: string;
  desc: string;
  toolbar?: unknown;
}

// ══════════════════════════════════════════════
//  工具
// ══════════════════════════════════════════════

/** 只读 GET 的超时：core 侧都只读内存/文件/一次 shell，慢到 10s 就是有问题。 */
const PROBE_TIMEOUT_MS = 10_000;
/**
 * CPU 探针单独放宽到 60s：core 侧要发 20+ 条 shell（先 ls 看节点在不在、再试写、再读回），
 * 文档自述总时长 5~15s，但设备忙时会明显更久。默认 15s 会把一次正常探测掐成"失败"。
 */
const CPU_PROBE_TIMEOUT_MS = 60_000;
/**
 * `/api/diagnose` 放宽到 30s：它内部跑 4 条 root shell（id / getprop adbd /
 * settings get global mobile_data / ip route）。默认 15s 在设备忙时不够。
 */
const DIAGNOSE_TIMEOUT_MS = 30_000;
/**
 * 权限补全（`POST /api/system/permissions/grant`）单独放宽到 60s：
 * core 侧会逐条跑 `pm grant` / `appops set`（每条一次 `adb shell`，冷启动就要数秒），
 * 补 5~6 条时 10s 的默认值必然把一次**正常**的补全掐成超时 —— 而超时之后权限其实已经授上了，
 * 用户重试一次只会看到"又超时"。宁可可等，也不要给出一个假的失败。
 */
const GRANT_TIMEOUT_MS = 60_000;
/**
 * 四条网络工具各自的超时。core 侧 ping 是 `count * 2 + 5` 秒（count 默认 4 ⇒ 13s），
 * TCP 是 5s 连接超时，traceroute 最多 15 跳 × 每跳约 5s。
 * 用默认 15s 会把 traceroute 每次都判成失败（它本来就慢）。
 */
const NETWORK_TOOL_TIMEOUT_MS = { ping: 30_000, dns: 20_000, tcp: 20_000, traceroute: 120_000 } as const;

type NetworkTool = keyof typeof NETWORK_TOOL_TIMEOUT_MS;

/**
 * 请求客户端。**每次现取**（写作 `api()`）而不是在模块作用域缓存一个实例：
 * `LoginView` 在登录 / 切换服务器时会调 `resetApiClient()` 把 `useApi` 的单例置空，
 * 缓存下来的那一份就变成了"上一台设备"的实例（它的拦截器是动态读 store 的，实际仍然能用，
 * 但留这个隐患没有收益）。写法与 `views/resources/resourceRegistry.ts` 一致。
 */
const api = getApiClient;

/** 秒级时间戳（epoch ms）→ 本地可读时间。缺值回 `--`，不要显示 1970。 */
function timeText(ms: unknown): string {
  const n = Number(ms ?? 0);
  return n > 0 ? new Date(n).toLocaleString('zh-CN', { hour12: false }) : '--';
}

/** 只接受有限数值，其余统一当缺失（core 的几个端点会回 null / 空串 / -1）。 */
function num(v: unknown, fallback = 0): number {
  const n = Number(v);
  return Number.isFinite(n) ? n : fallback;
}

/**
 * 等一个条件成立，最多等 `timeoutMs`。
 *
 * 只为 [runCapabilities] 存在：能力集在**应用壳（DefaultLayout）挂载时也会拉一次**，
 * 两者并发时 store 的 `load()` 会直接返回（见 `stores/capabilities.ts` 的
 * `if (loading.value) return`），此时 `loaded` 仍是 false。不等它落地就必然把
 * "还在飞"误报成"拿不到" —— 那是本页最容易被当成"设备不支持"的一条假结论。
 */
function settle(done: () => boolean, timeoutMs: number): Promise<void> {
  return new Promise((resolve) => {
    const started = Date.now();
    const tick = () => {
      if (done() || Date.now() - started >= timeoutMs) {
        resolve();
        return;
      }
      window.setTimeout(tick, 100);
    };
    tick();
  });
}

/** 把任意异常说成一句中文（描述符可以随便抛，runner 负责兜成 fail 结果）。 */
export function reasonOf(e: unknown): string {
  if (e && typeof e === 'object') {
    const resp = (e as { response?: unknown }).response;
    if (resp && typeof resp === 'object') {
      const body = (resp as { data?: unknown }).data;
      if (body && typeof body === 'object' && typeof (body as { error?: unknown }).error === 'string') {
        return (body as { error: string }).error;
      }
      const status = (resp as { status?: unknown }).status;
      if (typeof status === 'number') return `HTTP ${status}`;
    }
    if (e instanceof Error && e.message) return e.message;
  }
  return '未知错误';
}

// ══════════════════════════════════════════════
//  env：设备环境（auto）
// ══════════════════════════════════════════════

/**
 * 设备能力集。**复用 `stores/capabilities.ts`，不重复请求** —— 那个 store 的存在理由就是
 * "一次会话内只拉一次、各页读同一份"，在这里另发一次请求只会得到第二份可能不一致的快照。
 */
async function runCapabilities(): Promise<DiagnosticResult> {
  const caps = useCapabilitiesStore();
  await caps.load();
  await settle(() => !caps.loading, 8000);
  if (!caps.loaded) {
    // fail-open 的 store 在"不知道"时一律放行，所以这里必须显式说出来：
    // 能力集没拿到 ≠ 设备不支持任何能力，界面上的门禁此刻是全开的。
    return {
      status: 'warn',
      summary: '未取到能力集 —— 旧 core 可能没有这个端点，或这次请求失败了',
      rows: [{ label: '插件 id', value: caps.pluginId || '--' }],
    };
  }
  const rows = caps.list.map((wire) => ({ label: caps.labelOf(wire), value: wire }));
  return {
    status: rows.length > 0 ? 'ok' : 'warn',
    summary:
      rows.length > 0
        ? `插件 ${caps.pluginId || '未知'} 声明了 ${rows.length} 项能力`
        : `插件 ${caps.pluginId || '未知'} 没有声明任何能力`,
    rows,
    raw: JSON.stringify({ plugin_id: caps.pluginId, capabilities: caps.list }, null, 2),
  };
}

/**
 * 特权通道。
 *
 * 两个端点判的**不是同一件事**，所以两个都要打、两行都要显示：
 * · `system/root-check` → `ShellExecutor.hasRootAccess()`，判"ADB 特权通道能不能用"
 *   （底层 ADB shell 是 uid 2000）；
 * · `shell/root` → `executeAsRoot("id")` 的原文，里面的 `uid=0` 才是真 root。
 * 实测会出现"通道可用但 shell 不是 root"，合成一个布尔值必然有一端在说谎。
 */
async function runRootChannel(): Promise<DiagnosticResult> {
  const [check, shell] = await Promise.all([
    api().get(Endpoints.diagnostics.rootCheck, { timeout: PROBE_TIMEOUT_MS }),
    api().get(Endpoints.shell.root, { timeout: PROBE_TIMEOUT_MS }),
  ]);
  const c = check.data ?? {};
  const s = shell.data ?? {};
  const hasChannel = c.hasRoot === true;
  const idText = String(s.uid ?? '').trim();
  const isUid0 = /(^|\s)uid=0(\s|$)/.test(idText);
  const rows: DiagnosticRow[] = [
    { label: '特权通道', value: hasChannel ? '可用' : '不可用' },
    { label: '通道方式', value: String(s.method ?? '').trim() || '--' },
    { label: 'id 输出', value: idText || '--' },
    { label: 'shell 身份', value: isUid0 ? 'root（uid=0）' : idText ? '非 root' : '未知' },
  ];
  let status: DiagnosticStatus = 'warn';
  let summary = '特权通道不可用：只能执行普通 shell，root 相关的检查都会退化';
  if (hasChannel && isUid0) {
    status = 'ok';
    summary = '特权通道可用，且 shell 是 root（uid=0）';
  } else if (hasChannel) {
    // 这不是故障：通道能跑命令，只是拿不到 uid=0。用 info 而不是 warn，
    // 免得把"这台设备本来就这样"说成"出问题了"。
    status = 'info';
    summary = '特权通道可用，但 shell 不是 root（uid≠0）';
  }
  return { status, summary, rows, raw: JSON.stringify({ root_check: c, shell_root: s }, null, 2) };
}

/** SELinux 策略模式（`getenforce` 原文）。 */
async function runSelinux(): Promise<DiagnosticResult> {
  const { data } = await api().get(Endpoints.diagnostics.selinux, { timeout: PROBE_TIMEOUT_MS });
  const text = String(data?.selinux ?? '').trim();
  const lower = text.toLowerCase();
  let status: DiagnosticStatus = 'warn';
  let summary = `认不出的策略模式：${text || '(空)'}`;
  if (lower === 'enforcing') {
    status = 'ok';
    summary = 'Enforcing：策略正在强制执行（正常状态）';
  } else if (lower === 'permissive') {
    status = 'warn';
    summary = 'Permissive：策略只记录不拦截 —— 设备被放宽过，原生二进制与 root 操作更容易成功';
  } else if (lower === 'disabled') {
    status = 'warn';
    summary = 'Disabled：SELinux 已关闭';
  }
  return {
    status,
    summary,
    rows: [{ label: 'getenforce', value: text || '--' }],
    raw: JSON.stringify(data, null, 2),
  };
}

/**
 * 特权 shell / Magisk 探测。
 *
 * ⚠ **路径叫 magisk，实现已经不是 Magisk 检测**：core 的 `SystemController.getMagiskStatus()`
 * 现在只是 `getPrivilegedShellStatus()` 的兼容别名，回的是 `{ adb_shell, method, uid }`。
 * 界面上如实按"特权 shell 状态"显示，不要写成"已安装 Magisk"。
 */
async function runMagisk(): Promise<DiagnosticResult> {
  const { data } = await api().get(Endpoints.diagnostics.magisk, { timeout: PROBE_TIMEOUT_MS });
  const adbShell = data?.adb_shell === true;
  const uidText = String(data?.uid ?? '').trim();
  const isUid0 = /(^|\s)uid=0(\s|$)/.test(uidText);
  const rows: DiagnosticRow[] = [
    { label: 'ADB shell 可用', value: adbShell ? '是' : '否' },
    { label: '通道方式', value: String(data?.method ?? '').trim() || '--' },
    { label: 'id 输出', value: uidText || '--' },
  ];
  return {
    status: isUid0 ? 'ok' : adbShell ? 'info' : 'warn',
    summary: isUid0
      ? '特权 shell 可用且是 root（uid=0）'
      : adbShell
        ? 'ADB shell 可用，但不是 root（uid≠0）'
        : 'ADB shell 与 root 都不可用',
    rows,
    raw: JSON.stringify(data, null, 2),
  };
}

/** 监控数据库各表占用（`monitor/storage`）。答的是"**我们自己的数据**占了多少"。 */
async function runStorage(): Promise<DiagnosticResult> {
  const { data } = await api().get(Endpoints.monitor.storage, { timeout: PROBE_TIMEOUT_MS });
  const tables: any[] = Array.isArray(data?.tables) ? data.tables : [];
  const rows = tables.map((t) => ({
    label: String(t?.name ?? '?'),
    value: `${num(t?.count)} 条 · ${num(t?.size_kb).toFixed(1)} KB`,
  }));
  return {
    status: 'ok',
    summary: `监控数据库合计 ${String(data?.total_display ?? '--')}（${rows.length} 张表）`,
    rows,
    raw: JSON.stringify(data, null, 2),
  };
}

/**
 * 设备数据分区占用（`system/storage`，2026-10-14 补）。
 *
 * ## 与上面那条 [runStorage] 的区别（两条都留着，别合并）
 * · [runStorage]（`monitor/storage`）答"**我们自己的数据**占了多少"——它是可清理的；
 * · 本条（`system/storage`）答"**设备还有没有地方写**"——它是硬上限。
 * 一个数字同时回答两个问题是不可能的，合并只会让"到底哪个满了"变得说不清。
 *
 * ## 阈值 90% / 97% 是排障经验值，不是产品规则
 * 与 app 侧 `env.storage` 同一口径（`DiagnosticRegistry.kt`）：core 的日志、监控库、
 * 下载缓存都写在同一个分区上，写满之后的表现是"某些功能莫名其妙失败"（数据库写不进去、
 * 日志被截断），而错误信息里根本不会出现"磁盘满"这三个字。所以要在它发生之前就报出来。
 *
 * ## 为什么必须在诊断页里，而不是只靠「关于」面板那两行
 * `/api/diagnose` 的响应里**没有**磁盘信息（core 手册的响应示例可证），所以诊断页不收这一项，
 * 导出的诊断包里就一个字都没有 —— 而"现场磁盘是不是满的"正是收到包的人要问的第一个问题。
 * （「关于」面板那张「运行与存储」卡只给两个数字，没有阈值判定，也不进诊断包。）
 */
async function runDiskUsage(): Promise<DiagnosticResult> {
  const { data } = await api().get(Endpoints.diagnostics.diskUsage, { timeout: PROBE_TIMEOUT_MS });
  const total = num(data?.total);
  const used = num(data?.used);
  const available = num(data?.available);
  const percent = num(data?.usage_percent);
  // core 侧 StatFs 抛异常时回的是**空对象**（不是 null、不是错误码）：
  // 此时 total 为 0，百分比没有意义 —— 按"读不到"报，绝不渲染成 0%。
  if (total <= 0) {
    return {
      status: 'warn',
      summary: '读不到数据分区占用 —— core 的 StatFs("/data") 抛了异常（回的是空对象）',
      raw: JSON.stringify(data, null, 2),
    };
  }
  const rows: DiagnosticRow[] = [
    { label: '总量', value: formatBytes(total) },
    { label: '已用', value: formatBytes(used) },
    { label: '剩余', value: formatBytes(available) },
    { label: '使用率', value: `${percent.toFixed(1)} %` },
  ];
  let status: DiagnosticStatus = 'ok';
  let summary = `数据分区已用 ${percent.toFixed(1)}% · 剩余 ${formatBytes(available)}`;
  if (percent >= 97) {
    status = 'fail';
    summary = `数据分区只剩 ${formatBytes(available)}（已用 ${percent.toFixed(1)}%）—— 写满之后各功能会「莫名失败」`;
  } else if (percent >= 90) {
    status = 'warn';
    summary = `数据分区已用 ${percent.toFixed(1)}% · 剩余 ${formatBytes(available)} —— 接近写满，日志与数据库会先出问题`;
  }
  return { status, summary, rows, raw: JSON.stringify(data, null, 2) };
}

/** 媒体库权限（`media/status`）。 */
async function runMediaPermission(): Promise<DiagnosticResult> {
  const { data } = await api().get(Endpoints.media.status, { timeout: PROBE_TIMEOUT_MS });
  const granted = (data?.granted ?? {}) as Record<string, unknown>;
  // 三个 key 是 core 的 `MediaRoutes.Kind`（video / audio / image），不是猜的。
  const kinds: Array<[string, string]> = [
    ['video', '视频'],
    ['audio', '音频'],
    ['image', '图片'],
  ];
  const rows: DiagnosticRow[] = kinds.map(([key, label]) => ({
    label,
    value: granted[key] === true ? '已授权' : '未授权',
  }));
  rows.push({ label: '全文件访问', value: data?.all_files_access === true ? '是' : '否' });
  rows.push({ label: 'Android SDK', value: String(data?.sdk_int ?? '--') });
  const denied = kinds.filter(([key]) => granted[key] !== true).map(([, label]) => label);
  return {
    status: denied.length === 0 ? 'ok' : 'warn',
    // 未授权时列表页会显示成空 —— 不在这里说清，用户会以为"设备里没有视频"。
    summary:
      denied.length === 0
        ? '三类媒体权限均已授权'
        : `${denied.join('、')}未授权：对应的媒体页会显示为空列表，请先在设备端授权`,
    rows,
    raw: JSON.stringify(data, null, 2),
  };
}

/**
 * **core 自身的权限**（`system/permissions`，2026-10-14 新增，本批的重点）。
 *
 * ## 它回答什么
 * "core 现在缺哪些它自己声明过、但没拿到的权限？"—— 这是"新版本 core 装了、功能却静默失效"
 * 的第一嫌疑。真源是 core 已安装的 manifest（不是任何手抄清单），判据是 core 侧的
 * `checkPermission` / `AppOpsManager`（不是"能不能用"的间接猜测）。
 *
 * ## 为什么这件事客户端自己做不了
 * 客户端看不见 core 的 manifest，也读不到 appop 模式。解析 `dumpsys package` 是另一条路，
 * 但输出格式随版本漂移、而且 appop 状态根本不在它里面（要另跑 `appops get` 再解析一遍）——
 * 判据会变成"解析出来像什么"。所以这条必须由 core 端点回答。
 *
 * ## 旧 core 上它是 404（不是 500）
 * 这里**特意**只兜 404 这一种异常（注册表文件头的"描述符不写 try/catch"纪律允许复合项例外）：
 * 404 是"这台设备还没有自检端点"，要给的是**可手工执行的兜底命令**；
 * 其余异常照旧抛给 runner 统一翻成 fail，不在这里编第二套错误文案。
 *
 * ## 兜底命令为什么长这样
 * 它**不依赖任何权限清单**：在设备上现读 `dumpsys package` 的权限名，再逐条 `pm grant`
 * （对没声明的权限 grant 只会静默失败，无害），最后把四条 appop 类单独放行
 * （它们 `pm grant` 必然失败，只能 `appops set`）。这段可以直接粘进终端页 ——
 * core 的 `/api/shell/exec` 就是 uid 2000 的 adb shell，与安装脚本走的是同一条通道。
 */
const FALLBACK_GRANT_COMMAND =
  'dumpsys package com.ufi_axis_core | grep -o "android\\.permission\\.[A-Z_]*" | sort -u | ' +
  'while read p; do pm grant com.ufi_axis_core $p 2>/dev/null; done; ' +
  'appops set com.ufi_axis_core MANAGE_EXTERNAL_STORAGE allow; ' +
  'appops set com.ufi_axis_core REQUEST_INSTALL_PACKAGES allow; ' +
  'appops set com.ufi_axis_core GET_USAGE_STATS allow; ' +
  'appops set com.ufi_axis_core SCHEDULE_EXACT_ALARM allow';

/** 权限类别 → 中文（只用于本项的明细行，不参与任何判定）。 */
const PERMISSION_KIND_LABEL: Record<string, string> = {
  runtime: '运行时权限',
  appop: 'appop 控制',
  install: '安装时授予',
  signature: '签名级',
  superseded: '本机已取代',
  unknown: '本机没有',
};

async function runCorePermission(): Promise<DiagnosticResult> {
  let data: any;
  try {
    ({ data } = await api().get(Endpoints.diagnostics.corePermissions, { timeout: PROBE_TIMEOUT_MS }));
  } catch (e) {
    const status = (e as { response?: { status?: number } })?.response?.status;
    if (status === 404) {
      return {
        status: 'warn',
        summary: '这台 core 还没有权限自检端点（2026-10-14 才有）—— 先升级 core，或用下面的命令手动补全',
        rows: [
          { label: '手动兜底', value: FALLBACK_GRANT_COMMAND },
          { label: '怎么执行', value: '粘进「终端」页执行即可（core 的 shell 通道就是 uid 2000 的 adb shell）' },
        ],
        raw: reasonOf(e),
      };
    }
    throw e;
  }
  const d = data ?? {};
  const missing: any[] = Array.isArray(d.missing) ? d.missing : [];
  const blocked: any[] = Array.isArray(d.blocked) ? d.blocked : [];
  const notApplicable: any[] = Array.isArray(d.not_applicable) ? d.not_applicable : [];
  const privileged = d.shell_privileged === true;
  const uid = num(d.uid);
  const rows: DiagnosticRow[] = [
    { label: '包名', value: String(d.package ?? '--') },
    { label: '声明 / 已授', value: `${num(d.requested_count)} / ${num(d.granted_count)} 项` },
    {
      label: '特权通道',
      value: privileged
        ? `adb_shell（uid ${uid}）—— 可以补权限`
        : `普通 shell（uid ${uid}）—— **补不了权限**，先去修「root / shell 通道」`,
    },
  ];
  for (const m of missing) {
    const bits = [PERMISSION_KIND_LABEL[String(m.kind)] ?? String(m.kind ?? '?')];
    if (m.appop) bits.push(`appop ${String(m.appop)}`);
    rows.push({ label: `缺失 · ${String(m.short ?? m.name ?? '?')}`, value: bits.join(' · ') });
  }
  if (notApplicable.length > 0) {
    // 「本机不适用」必须显式出现：否则用户会问"为什么没检查 WRITE_EXTERNAL_STORAGE"，
    // 而答案是"这台 Android 已经不用它了"—— 不算缺失，也补不了。
    rows.push({
      label: '本机不适用',
      value:
        notApplicable.map((x) => String(x.short ?? '?')).join('、') +
        ' —— 本机 Android 版本已不需要（不算缺失，不用管）',
    });
  }
  for (const b of blocked) {
    rows.push({ label: `补不了 · ${String(b.short ?? '?')}`, value: String(b.note ?? '') });
  }
  if (missing.length > 0) {
    // "具体到命令"是硬要求：按钮补不了（或用户更信命令）时，原文命令必须在页面上看得到。
    rows.push({ label: '手动兜底', value: FALLBACK_GRANT_COMMAND });
  }

  if (missing.length > 0) {
    const shorts = missing.map((m) => String(m.short ?? m.name ?? '?'));
    return {
      status: 'warn',
      summary: `缺 ${missing.length} 项权限：${shorts.join('、')} —— 依赖它们的${effectHintOf(shorts)}会「点了没用」，且不会报错`,
      rows,
      raw: JSON.stringify(d, null, 2),
      write: privileged
        ? {
            label: `补全 ${missing.length} 项权限`,
            confirmTitle: '补全 core 权限',
            confirmBody:
              `将对 ${String(d.package ?? 'core')} 执行 ${missing.length} 条授权命令（走设备内置 adb，uid 2000）：` +
              missing.map((m) => String(m.fix_command || m.short)).join('；') +
              '。这会真实改变该应用的权限。' +
              '⚠ 本页**无法撤销**（core 没有 revoke 端点）：要回退只能在终端里执行 ' +
              '`pm revoke <包名> <权限>` 或 `appops set <包名> <OP> default`。' +
              '顺带会把「长期未使用的权限自动回收」设为 ignore（可逆，防止补完过一阵又被系统收走）。确认执行？',
            danger: true,
            run: async () => {
              const { data: res } = await api().post(
                Endpoints.diagnostics.corePermissionsGrant,
                {},
                { timeout: GRANT_TIMEOUT_MS }
              );
              const ok = num(res?.granted);
              const failed = num(res?.failed);
              const still = Array.isArray(res?.permissions?.missing) ? res.permissions.missing.length : 0;
              const tail = String(res?.auto_revoke ?? '').trim();
              return (
                `补全结果：成功 ${ok} 项${failed > 0 ? `、失败 ${failed} 项` : ''}；重新探测后仍缺 ${still} 项。` +
                (tail ? `自动回收保护：${tail}` : '')
              );
            },
          }
        : undefined,
    };
  }
  if (blocked.length > 0) {
    return {
      status: 'info',
      summary: `core 声明的权限都已就位；另有 ${blocked.length} 项签名级权限拿不到（需要以系统应用方式安装，不影响功能）`,
      rows,
      raw: JSON.stringify(d, null, 2),
    };
  }
  return {
    status: 'ok',
    summary: `core 声明的 ${num(d.requested_count)} 项权限全部就位`,
    rows,
    raw: JSON.stringify(d, null, 2),
  };
}

/**
 * 缺哪些权限 → 会坏在哪。
 *
 * 为什么值得单独写一张小表：`READ_MEDIA_AUDIO 未授权` 对用户是零信息，
 * 而"音乐页会是空列表"是他一眼能对上的现象。**这张表只影响文案，不参与任何判定** ——
 * 判定永远来自 core 的实测（见 [runCorePermission]）。表里没有的权限回落成中性说法，
 * 不编一个可能错的功能名。
 */
function effectHintOf(shorts: string[]): string {
  const hints: Array<[string, string]> = [
    ['READ_MEDIA_AUDIO', '音乐页'],
    ['READ_MEDIA_VIDEO', '视频页'],
    ['READ_MEDIA_IMAGES', '图片页'],
    ['POST_NOTIFICATIONS', '状态栏通知'],
    ['SCHEDULE_EXACT_ALARM', '服务自恢复'],
    ['USE_EXACT_ALARM', '服务自恢复'],
    ['PACKAGE_USAGE_STATS', '流量统计'],
    ['MANAGE_EXTERNAL_STORAGE', '文件管理'],
    ['REQUEST_INSTALL_PACKAGES', '应用内安装 / 更新'],
    ['READ_SMS', '短信列表'],
    ['SEND_SMS', '本机短信渠道'],
    ['ACCESS_FINE_LOCATION', '基站信息采集'],
    ['ACCESS_COARSE_LOCATION', '基站信息采集'],
    ['READ_PHONE_STATE', 'SIM 与网络状态'],
    ['READ_PHONE_NUMBERS', '本机号码'],
  ];
  const hit = hints.filter(([k]) => shorts.includes(k)).map(([, v]) => v);
  const uniq = [...new Set(hit)];
  return uniq.length > 0 ? uniq.join('、') : '相关功能';
}

/**
 * 浏览器系统通知授权（`composables/browserNotify.ts`）。
 *
 * ## 四态必须分开说（用户明确要求："权限被拒"与"浏览器不支持"不能混为一谈）
 * · **不支持**（没有 `Notification`）：INFO。它不是故障，页内提示照常工作；
 * · **denied**：FAIL。★ 浏览器**不会再弹授权框**，`enableBrowserNotify()` 也弹不出来 ——
 *   唯一的修法是去站点设置里手动允许。**所以这一态刻意不给写动作按钮**
 *   （给了就是一个点了没用的按钮，正是本页最该消灭的东西）；
 * · **default**：WARN。还能再申请一次，所以给「申请通知授权」按钮
 *   （`Notification.requestPermission()` 必须在用户手势里调，而 popconfirm 的确认点击就是手势）；
 * · **granted**：看应用内开关 —— 开着 OK，关着 INFO（"已授权但不会发"）。
 *
 * 另外把"页面在前台时不发"这条**如实写进明细**：`notifySystem` 里有 `if (!document.hidden) return`，
 * 不说的话"我开着开关、前台也收不到"会被当成 bug（它是刻意避免与页内提示双份打扰）。
 */
async function runBrowserNotifyPermission(): Promise<DiagnosticResult> {
  const switchOn = isBrowserNotifyEnabled();
  if (typeof Notification === 'undefined') {
    return {
      status: 'info',
      summary: '当前浏览器不支持系统通知（没有 Notification API）—— 页内提示仍然可用，这不是故障',
      rows: [{ label: '应用内开关', value: switchOn ? '已打开' : '关闭' }],
    };
  }
  const perm = Notification.permission;
  const rows: DiagnosticRow[] = [
    { label: '浏览器授权', value: perm },
    { label: '应用内开关', value: switchOn ? '已打开' : '关闭' },
    { label: '前台行为', value: '页面在前台时只显示页内提示，不发系统通知（刻意避免双份打扰）' },
    { label: '发送时机', value: '只在页面不在前台（document.hidden）时发' },
  ];
  if (perm === 'denied') {
    return {
      status: 'fail',
      summary: '通知授权已被拒绝 —— 浏览器不会再弹授权框，只能去站点设置里手动允许「通知」后刷新页面',
      rows: [
        ...rows,
        {
          label: '怎么修',
          value: '点地址栏左侧的站点设置（或 浏览器设置 → 网站设置 → 通知），把本站改为「允许」，然后刷新页面',
        },
      ],
    };
  }
  if (perm === 'default') {
    return {
      status: 'warn',
      summary: '还没授权 —— 可以再申请一次（浏览器会弹授权框），点右上角「申请通知授权」',
      rows,
      write: {
        label: '申请通知授权',
        confirmTitle: '申请浏览器通知授权',
        confirmBody:
          '会调用浏览器的通知授权弹窗，并把应用内开关打开。' +
          '⚠ 若浏览器此前已记住拒绝（权限为 denied），这次不会再弹框，只会提示你去站点设置里允许。' +
          '授权是**可撤销的**：在站点设置里改回「阻止」即可（本页的开关也会跟着失效）。确认申请？',
        run: async () => await enableBrowserNotify(),
      },
    };
  }
  return {
    status: switchOn ? 'ok' : 'info',
    summary: switchOn
      ? '已授权且开关已打开：页面切到后台时，验证码与告警会发系统通知'
      : '浏览器已授权，但应用内开关关着 —— 系统通知不会发出（去设置面板打开）',
    rows,
  };
}

/**
 * CPU 控制能力探测（`cpu-probe`，web 此前零调用）。
 *
 * 这条是**唯一一条慢检查**（core 侧 20+ 条 shell、5~15s），但它只读或写回原值，
 * 对设备零影响，所以仍然放在 auto 里 —— 它的价值正是"进页面就能看到这台设备能不能调频"。
 */
async function runCpuProbe(): Promise<DiagnosticResult> {
  const { data } = await api().get(Endpoints.diagnostics.cpuProbe, { timeout: CPU_PROBE_TIMEOUT_MS });
  // core 的失败形态是 200 + { ok: false, error }，不能按 HTTP 码判。
  if (data?.ok === false) {
    return {
      status: 'fail',
      summary: `探测失败：${String(data?.error ?? '未知原因')}`,
      raw: JSON.stringify(data, null, 2),
    };
  }
  const policies: any[] = Array.isArray(data?.policies) ? data.policies : [];
  const online: any[] = Array.isArray(data?.online_nodes) ? data.online_nodes : [];
  const zones: any[] = Array.isArray(data?.thermal_zones) ? data.thermal_zones : [];
  const onlineCount = online.filter((o) => o?.online === true).length;
  const freqWritable = policies.length > 0 && policies.every((p) => p?.freq_writable === true);
  const govWritable = policies.length > 0 && policies.every((p) => p?.gov_writable === true);
  const rows: DiagnosticRow[] = [
    { label: '内核', value: String(data?.kernel ?? '').trim() || '--' },
    { label: '平台', value: String(data?.board ?? '').trim() || '--' },
    { label: '声明核数', value: String(data?.cores_declared ?? '--') },
    { label: 'shell 身份', value: String(data?.shell_uid ?? '').trim() || '--' },
    {
      label: '调频域',
      value:
        policies
          .map((p) => {
            const cpus = Array.isArray(p?.cpus) ? p.cpus.join(',') : '?';
            return `cpu${cpus} · ${String(p?.governor ?? '?')} · ${String(p?.max_freq ?? '?')} kHz`;
          })
          .join('；') || '--',
    },
    { label: '频率节点可写', value: freqWritable ? '是' : '否' },
    { label: 'governor 可写', value: govWritable ? '是' : '否' },
    { label: '在线核', value: `${onlineCount} / ${online.length}` },
    { label: '热区', value: `${zones.length} 个` },
  ];
  return {
    status: policies.length > 0 ? 'ok' : 'warn',
    summary:
      policies.length > 0
        ? `${policies.length} 个调频域 · ${onlineCount}/${online.length} 核在线 · 频率节点${freqWritable ? '可写' : '不可写'}`
        : '没有探测到任何 cpufreq 节点：这台设备可能不支持调频控制',
    rows,
    raw: JSON.stringify(data, null, 2),
  };
}

// ══════════════════════════════════════════════
//  network：四条工具（manual）
// ══════════════════════════════════════════════

/**
 * 四条网络工具的公共实现（差异只有 tool 与超时，所以留在描述符外面共用一份）。
 *
 * 从 `views/network/components/modals/DiagnosticModal.vue` 原样搬来的三条语义，一条都没改：
 * ① `host` 只进 argv、由 core 白名单校验（`^[A-Za-z0-9._-]{1,253}$`），非法回 **400**；
 * ② `tcp` 才带 `port`，`traceroute` 固定 `max_hops = 15`；
 * ③ 成败看 `exit_code`，**traceroute 除外** —— core 侧它恒回 `exit_code: 0`
 *    （逐跳结果都在 `output` 文本里，没有"整体成功/失败"这个量）。
 */
async function runNetworkTool(tool: NetworkTool): Promise<DiagnosticResult> {
  const host = networkTarget.host.trim();
  if (!host) {
    return { status: 'fail', summary: '还没填目标地址 —— 在上面「目标地址」里填域名或 IP 再跑' };
  }
  const body: Record<string, unknown> = { host };
  const port = networkTarget.port ?? DEFAULT_TCP_PORT;
  if (tool === 'tcp') {
    body.port = port;
  }
  if (tool === 'traceroute') {
    body.max_hops = 15;
  }
  const { data } = await api().post(Endpoints.diagnostic[tool], body, {
    timeout: NETWORK_TOOL_TIMEOUT_MS[tool],
  });
  const d = data ?? {};
  const output = String(d.output ?? '').trim();
  const exitCode = num(d.exit_code, -1);
  const ok = tool === 'traceroute' ? true : exitCode === 0;
  return {
    status: ok ? 'ok' : 'fail',
    summary: ok
      ? tool === 'traceroute'
        ? `对 ${host} 的逐跳结果见下方原文`
        : `${host} 探测成功（exit_code=0）`
      : `未成功（exit_code=${exitCode}），输出见下方`,
    rows: [
      { label: '目标', value: tool === 'tcp' ? `${host}:${port}` : host },
      { label: 'exit_code', value: String(exitCode) },
    ],
    raw: output || JSON.stringify(d, null, 2),
  };
}

/** 四条工具共用的说明前缀（它们回答的是同一个问题的四个侧面）。 */
const NETWORK_DESC_SUFFIX = '；目标地址与端口是上面那一份，四条共用。';

// ══════════════════════════════════════════════
//  service：服务与诊断快照（auto）
// ══════════════════════════════════════════════

/**
 * 后台服务状态 + 上一次崩溃。
 *
 * 两个端点合成一条检查而不是拆两条：它们回答的是同一个问题（"后台服务现在好不好"）——
 * 崩溃后 core 由 keepalive 拉起，HTTP 很快恢复，**只看 status 会显示一切正常**，
 * 必须把 `service/crash` 的"上一次崩溃"一起摆出来才对得上用户感知到的"数据断了一下"。
 */
async function runServiceStatus(): Promise<DiagnosticResult> {
  const [statusRes, crashRes] = await Promise.all([
    api().get(Endpoints.service.status, { timeout: PROBE_TIMEOUT_MS }),
    api().get(Endpoints.diagnostics.serviceCrash, { timeout: PROBE_TIMEOUT_MS }),
  ]);
  const s = statusRes.data ?? {};
  const c = crashRes.data ?? {};
  const enabled = s.enabled === true;
  const collecting = s.collecting === true;
  const crashed = c.crashed === true;
  const nativeExec = (s.native_exec ?? {}) as Record<string, unknown>;
  const rows: DiagnosticRow[] = [
    { label: '服务开关', value: enabled ? '已开启' : '已关闭' },
    { label: '采集循环', value: collecting ? '运行中' : '未运行' },
    { label: '已运行', value: num(s.uptime_ms) > 0 ? formatUptime(num(s.uptime_ms) / 1000) : '--' },
    { label: '开机自启', value: s.auto_start_on_boot === true ? '是' : '否' },
    { label: 'HTTP 服务', value: s.http_running === true ? '运行中' : '--' },
    {
      label: '原生二进制',
      value:
        nativeExec.probed !== true
          ? '尚未自检（组件图还没构建）'
          : nativeExec.executable === true
            ? '可执行'
            : `不可执行（SELinux ${String(nativeExec.selinux ?? 'unknown')}）`,
    },
    { label: '上次崩溃', value: crashed ? timeText(c.timestamp) : '无记录' },
  ];
  if (crashed) {
    rows.push({ label: '崩溃摘要', value: String(c.summary ?? '').trim() || '--' });
    rows.push({ label: '崩溃文件', value: String(c.file ?? '').trim() || '--' });
  }
  let status: DiagnosticStatus = 'ok';
  let summary = '后台服务在跑，采集循环也在跑，没有崩溃记录';
  if (!enabled) {
    // 停服务是用户的显式操作（HTTP 不停，所以还能远程再启动），不是故障。
    status = 'info';
    summary = '后台服务已停止 —— HTTP 仍在运行，可以远程再启动';
  } else if (crashed) {
    status = 'warn';
    summary = `后台服务在跑，但检测到上一次 core 崩溃（${timeText(c.timestamp)}）`;
  } else if (!collecting) {
    status = 'warn';
    summary = '服务开关是开的，但采集循环没在跑 —— 数据可能已经停止更新';
  }
  return {
    status,
    summary,
    rows,
    raw: JSON.stringify({ status: s, crash: c }, null, 2),
  };
}

/** QoS 并发/缓存快照（`qos/status`）。 */
async function runQosStatus(): Promise<DiagnosticResult> {
  const { data } = await api().get(Endpoints.diagnostics.qosStatus, { timeout: PROBE_TIMEOUT_MS });
  const shell = (data?.shell_qos ?? {}) as Record<string, any>;
  const rootPermits = (shell.root ?? {}) as Record<string, unknown>;
  const normalPermits = (shell.normal ?? {}) as Record<string, unknown>;
  const cache = (shell.cache ?? {}) as Record<string, unknown>;
  const batch = (shell.batch ?? {}) as Record<string, unknown>;
  const goform = (data?.goform_qos ?? {}) as Record<string, unknown>;
  const pool = (data?.dynamic_pool ?? {}) as Record<string, unknown>;
  // cpu_temp 是**毫摄氏度**，读不到时 core 回 0（不是 null）—— 直接当摄氏度显示会得到假的 0°C。
  const cpuTempMilli = num(data?.cpu_temp);
  const rows: DiagnosticRow[] = [
    {
      label: 'Shell root 许可',
      value: `${num(rootPermits.available)} / ${num(rootPermits.total)}（目标 ${num(rootPermits.target)}）`,
    },
    { label: 'Shell 普通许可', value: `${num(normalPermits.available)} / ${num(normalPermits.total)}` },
    {
      label: 'Shell 缓存',
      value: `${num(cache.entries)} 条 · TTL ${num(cache.ttl_ms)} ms`,
    },
    {
      label: 'Shell 批处理',
      value: `${num(batch.total_batches)} 批 · 省 ${num(batch.commands_saved)} 条命令`,
    },
    {
      label: 'goform 查询许可',
      value: `${num(goform.query_available)} / ${num(goform.query_total)}`,
    },
    { label: 'goform 写许可', value: `${num(goform.set_available)} / ${num(goform.set_total)}` },
    {
      label: 'goform 缓存',
      value: `${num(goform.cache_size)} 条 · TTL ${num(goform.cache_ttl_ms)} ms`,
    },
    { label: 'CPU 温度', value: cpuTempMilli > 0 ? `${(cpuTempMilli / 1000).toFixed(1)} °C` : '读不到（core 回 0）' },
    {
      label: '线程池',
      value: `core ${num(pool.core)} · current ${num(pool.current)} · max ${num(pool.max)}`,
    },
  ];
  return {
    status: 'ok',
    summary: `Shell root 通道 ${num(rootPermits.available)}/${num(rootPermits.total)} 空闲 · 线程池 ${num(pool.current)}/${num(pool.max)}`,
    rows,
    raw: JSON.stringify(data, null, 2),
  };
}

/**
 * 响应缓存健康（`cache/stats`）—— **只读引用，不是第二套维护入口**（2026-10-14 补）。
 *
 * ## 真源在哪
 * 缓存维护的真源是「设置 → 性能 → **缓存管理**」那张卡（`PerformancePanel.vue`）：
 * 条目数、体积、**清空**与**按规则失效**两个写入动作全在那边。本页一个写入动作都不放 ——
 * 诊断中心的主业是"看"（见 `DiagnosticsView.vue` 的文件头）。
 *
 * ## 那为什么还要在这里放一条
 * 因为性能面板只看得到"有多少条、占多大"，而**排障要看的是另外两个字段**，它们在 web 上
 * 此前一处都没被展示过（grep 过 `web/src`：`stale` 只出现在性能面板的注释里，
 * `expired_count` 一次都没出现）：
 *  · `stale` —— 缓存被标记为"可能过期"（WS 客户端全部断开、内部刷新失败）。
 *    "我看到的数字为什么是旧的"通常就是这一行，而性能面板上完全看不出来；
 *  · `expired_count` —— 已过期但**惰性删除还没触发**的条目数，健康态应远小于 `count`。
 *    （它不在 core 手册的字段表里，但 `ResponseCache.kt` 确实会回这个键。）
 *
 * 条目数与体积**刻意不在这里重复**：那两个数字性能面板已经有了，抄一份只是多一处会漂移的副本。
 */
async function runCacheHealth(): Promise<DiagnosticResult> {
  const { data } = await api().get(Endpoints.diagnostics.cacheStats, { timeout: PROBE_TIMEOUT_MS });
  const count = num(data?.count);
  const expired = num(data?.expired_count);
  const stale = data?.stale === true;
  // 判据用**比例**而不是绝对条数：count 本身可大可小，"5 条里 5 条过期"与
  // "500 条里 5 条过期"不是一回事，拿绝对数当阈值必然在两种规模下各错一次。
  const stuck = count > 0 && expired / count >= 0.5;
  const rows: DiagnosticRow[] = [
    { label: '过期滞留', value: `${expired} / ${count} 条（过期条目要等下一次读才被删掉）` },
    {
      label: '可能过期',
      value: stale ? '是 · 缓存被标记为 stale（WS 曾全部断开或内部刷新失败）' : '否',
    },
  ];
  let status: DiagnosticStatus = 'ok';
  let summary = `响应缓存 ${count} 条，没有过期滞留，也没有 stale 标记`;
  if (stale) {
    status = 'warn';
    summary = '缓存被标记为可能过期 —— 页面上的数字可能是旧的，去性能面板清一次再看';
  } else if (stuck) {
    status = 'warn';
    summary = `响应缓存过期滞留 ${expired}/${count} 条 —— 回收几乎全靠读时惰性删除`;
  }
  return { status, summary, rows, raw: JSON.stringify(data, null, 2) };
}

/**
 * 全量快照（`diagnose`）。
 *
 * 与「关于」页那四行刻意不同：那边是**给人看**的四个字段（还删掉了与版本卡重复的
 * `app_version`），这里是**给排障与导出用**的完整响应，`raw` 是 core 原文。
 * 两者读同一个端点、各有各的用途，不合并。
 */
async function runFullSnapshot(): Promise<DiagnosticResult> {
  const { data } = await api().get(Endpoints.diagnose, { timeout: DIAGNOSE_TIMEOUT_MS });
  const profile = (data?.device_profile ?? {}) as Record<string, unknown>;
  const rows: DiagnosticRow[] = [
    { label: '服务器时间', value: timeText(data?.server_time) },
    { label: 'Root（uid=0）', value: data?.root === true ? '已获取' : '未获取' },
    { label: 'ADB 守护进程', value: String(data?.adbd ?? '').trim() || '--' },
    { label: '移动数据', value: String(data?.mobile_data ?? '').trim() || '--' },
    { label: '网关', value: String(data?.gateway ?? '').trim() || '--' },
    { label: 'profile 状态', value: String(profile.status ?? '--') },
    { label: '生效 profile', value: String(profile.active ?? '').trim() || '--' },
    { label: '配置 profile', value: String(profile.configured ?? '').trim() || '（没配，用注册表默认）' },
    { label: '插件 id', value: String(profile.plugin_id ?? '').trim() || '--' },
    { label: '选型方式', value: String(profile.selection ?? '--') },
  ];
  return {
    status: data?.root === true ? 'ok' : 'info',
    summary: `profile ${String(profile.active ?? '--')}（${String(profile.status ?? '--')}）· 插件 ${String(profile.plugin_id ?? '--')}`,
    rows,
    raw: JSON.stringify(data, null, 2),
  };
}

/** 落盘日志文件清单（`debug-logs/files`）。 */
async function runDebugLogs(): Promise<DiagnosticResult> {
  const { data } = await api().get(Endpoints.diagnostics.debugLogFiles, { timeout: PROBE_TIMEOUT_MS });
  // core 侧 `listLogFiles()` 已经按 modified 倒序（新 → 旧），这里不再排一次。
  const files: any[] = Array.isArray(data?.files) ? data.files : [];
  const recent = files.slice(0, 5);
  const rows: DiagnosticRow[] = recent.map((f) => ({
    label: String(f?.name ?? '?'),
    value: `${formatBytes(num(f?.size))} · ${timeText(f?.modified)}`,
  }));
  if (files.length > recent.length) {
    rows.push({ label: '…', value: `另有 ${files.length - recent.length} 个文件（去日志面板看全部）` });
  }
  rows.push({ label: '日志目录', value: String(data?.dir ?? '').trim() || '--' });
  return {
    status: files.length > 0 ? 'ok' : 'info',
    summary:
      files.length > 0
        ? `落盘日志 ${files.length} 个文件，合计 ${formatBytes(num(data?.total_bytes))}`
        : '还没有落盘日志文件 —— 日志总开关或「后端日志」可能关着',
    rows,
    raw: JSON.stringify(data, null, 2),
  };
}

/**
 * 实时推送通道（`/ws/realtime`，本页自己的连接状态）。
 *
 * ## 为什么它必须出现在诊断页
 * "我开着推送却收不到"在 web 端最常见的根因就是**这条 WebSocket 没连上**：
 * 告警与验证码都经它下发，`stores/websocket.ts` 在放弃重连后会置 `fatalReason`
 * 并**停止重试**（例如 1008 = 鉴权失败，重连一万次也不会成功）—— 而界面上只有一个
 * 侧栏小圆点，用户不会去看它。诊断页要回答的正是"它现在通不通、为什么不通"。
 *
 * ## 为什么只读本地状态、不去打 `/health`
 * 判据是"**这个页面**收不收得到推送"，那只有本地 store 知道；`/health` 的
 * `ws_connections` 是 core 侧全部客户端的计数（app 与其它浏览器都算在内），
 * 拿它当结论会出现"core 说 2 个连接、本页其实是断的"这种自相矛盾。
 * 顺带也不必为一个数字新增一条端点引用。
 */
async function runRealtimeChannel(): Promise<DiagnosticResult> {
  const ws = useWebSocketStore();
  const fatal = String(ws.fatalReason ?? '').trim();
  const rows: DiagnosticRow[] = [
    { label: '本页连接', value: ws.status },
    { label: '最近一条消息', value: ws.lastMessage > 0 ? timeText(ws.lastMessage) : '本次会话还没收到过' },
    { label: '放弃重连', value: fatal || '否（断线会按 1→30 秒退避自动重连，最多 9 次）' },
  ];
  if (ws.status === 'connected') {
    return {
      status: 'ok',
      summary: '实时通道已连接 —— 告警、验证码与页面数据的推送都走它',
      rows,
    };
  }
  if (fatal) {
    return {
      status: 'fail',
      summary: `实时通道已停止重连：${fatal} —— 在这之前页面不会收到任何推送（系统通知也一起失效）`,
      rows: [...rows, { label: '怎么办', value: '按提示处理（重新登录 / 关掉一个其它客户端）后刷新页面' }],
    };
  }
  return {
    status: 'warn',
    summary: '实时通道当前未连接（正在自动重连）—— 这期间收不到推送与自动刷新',
    rows,
  };
}

/**
 * 三条外部通知渠道的配置完整性（邮件 / Webhook / 本机短信）。
 *
 * ## 它回答的是"看到功能在、点了没用、又不知道为什么"
 * 三条渠道的开关是**独立**的，而且 core 侧的投递判据是 `开关 && 配置完整`：
 * 开关打开、但必填项没填完时，通知会被**静默跳过**（只在投递记录里留一行
 * "配置不完整，未发送"，用户看不到）。所以"我开了邮件通知，一封也没收到"的答案
 * 通常就在这里。
 *
 * ## 判据直接读 core 的 `configured` / `sendable`，不自己复现
 * 三个字段名不一样是**core 的历史命名**：邮件是 `sendable`，另两条是 `configured`
 * （core 的注释写明"口径与 /api/notify/{sms,webhook}/config 的 configured 对齐，
 * 字段名沿用 /diagnose 已在用的 sendable"）。客户端自己算"填没填全"必然会分叉 ——
 * 那正是 core 加这两个字段要消掉的东西。
 */
async function runNotifyChannels(): Promise<DiagnosticResult> {
  const [mail, hook, sms] = await Promise.all([
    api().get(Endpoints.smsForward.config, { timeout: PROBE_TIMEOUT_MS }),
    api().get(Endpoints.notify.webhookConfig, { timeout: PROBE_TIMEOUT_MS }),
    api().get(Endpoints.notify.smsConfig, { timeout: PROBE_TIMEOUT_MS }),
  ]);
  /**
   * 渠道 → (名字, 配置, **完整性字段名**)。
   *
   * 第三个元素跟数据走，而不是另开一张按下标对齐的 `readyKeys` 数组：
   * 两张表按下标对齐的写法一旦有人插入一条渠道就会静默错位
   * （"Webhook 的完整性读成了邮件的字段"这种错不会报错，只会给出错的结论）。
   */
  const channels: Array<[string, any, string]> = [
    ['邮件', mail.data, 'sendable'],
    ['Webhook', hook.data, 'configured'],
    ['本机短信', sms.data, 'configured'],
  ];
  /** 一条渠道的三种状态。`ready` 字段名在三条渠道上不同（core 的历史命名），由上面那张表带过来。 */
  const textOf = (cfg: any, readyKey: string): string => {
    const enabled = cfg?.enabled === true;
    const ready = cfg?.[readyKey] === true;
    if (ready) return '已启用 · 配置完整';
    if (enabled) return `已启用但配置不完整 —— 投递会被静默跳过（${readyKey}=false）`;
    return '未启用';
  };
  const rows: DiagnosticRow[] = channels.map(([name, cfg, readyKey]) => ({
    label: name,
    value: textOf(cfg, readyKey),
  }));
  const broken = channels.filter(([, cfg, readyKey]) => cfg?.enabled === true && cfg?.[readyKey] !== true);
  const enabledCount = channels.filter(([, cfg]) => cfg?.enabled === true).length;
  if (broken.length > 0) {
    return {
      status: 'warn',
      summary: `${broken.map(([n]) => n).join('、')} 已启用但配置不完整 —— 通知会被静默跳过（界面上看不出原因）`,
      rows,
      raw: JSON.stringify({ mail: mail.data, webhook: hook.data, sms: sms.data }, null, 2),
    };
  }
  return {
    status: enabledCount > 0 ? 'ok' : 'info',
    summary:
      enabledCount > 0
        ? `已启用 ${enabledCount}/3 条外部渠道，配置均完整`
        : '三条外部渠道都没启用 —— 只有状态栏通知这一条路（页面内/系统通知）',
    rows,
    raw: JSON.stringify({ mail: mail.data, webhook: hook.data, sms: sms.data }, null, 2),
  };
}

// ══════════════════════════════════════════════
//  注册表
// ══════════════════════════════════════════════

/**
 * 三段分组。顺序即页面顺序；`toolbar` 只有网络组有（四条工具共用一个目标地址）。
 *
 * 与 [DIAGNOSTICS] 分开写：分组是**版式**信息（标题、说明、共用输入区），
 * 检查项是**行为**信息。合成一个嵌套结构后，"某一段的顺序"与"某一条属于哪一段"
 * 会同时写在两个地方，加一条检查就得想清楚往哪一层塞。
 */
export const DIAGNOSTIC_GROUPS: DiagnosticGroupSpec[] = [
  {
    key: 'env',
    title: '设备环境',
    desc: '这台设备是什么、能用什么：能力集、特权通道、SELinux、监控库与数据分区占用、媒体权限、**core 自身的权限**、浏览器通知授权、CPU 调频。除「补全权限 / 申请通知授权」两颗写按钮外全部只读，进页面自动跑一遍。',
  },
  {
    key: 'network',
    title: '网络工具',
    desc: '对指定目标做一次主动探测。**会真的发包**，所以不自动跑 —— 填好目标后点哪条跑哪条。',
    toolbar: NetworkTargetBar,
  },
  {
    key: 'service',
    title: '服务与快照',
    desc: '后台服务与 core 自己的状态：服务开关、崩溃记录、QoS 并发、响应缓存健康、实时推送通道、三条外部通知渠道的配置完整性、全量诊断快照、落盘日志。全部只读，进页面自动跑一遍。',
  },
];

/**
 * 全部检查项。**加一项检查只动这个数组**（外加它自己的 `run()`）。
 *
 * `mode` 的判据是"这次检查会不会**向外部发包**或**写设备**"：
 * · env / service 两组都是读设备自己的状态（含跑 root shell），只读、无外部副作用 ⇒ auto；
 * · network 四条会向用户指定的外部地址发 ICMP / TCP 包 ⇒ manual，绝不能进页面就发。
 * 注意"只读"不等于"便宜"：`cpu-probe` 一条就要发 20+ 条 shell，它的 `desc` 里写明了。
 */
export const DIAGNOSTICS: DiagnosticItem[] = [
  // ── env ──
  {
    id: 'capabilities',
    name: '设备能力集',
    desc: 'core 的插件声明了哪些能力（短信 / 锁频段 / Samba…）。读的是应用壳已经拉过的那一份，不会重复请求。',
    group: 'env',
    scope: 'core',
    mode: 'auto',
    run: runCapabilities,
  },
  {
    id: 'root-channel',
    name: 'root / shell 通道',
    desc: 'ADB 特权通道能不能用、shell 到底是不是 root。两个探针分别读，因为实测会出现「通道可用但 shell 不是 root」。',
    group: 'env',
    scope: 'core',
    mode: 'auto',
    render: 'verdict',
    run: runRootChannel,
  },
  {
    id: 'selinux',
    name: 'SELinux',
    desc: '系统策略模式（getenforce 原文）。Permissive 会让原生二进制与 root 操作更容易成功，所以与 Enforcing 分开说。',
    group: 'env',
    scope: 'core',
    mode: 'auto',
    render: 'verdict',
    run: runSelinux,
  },
  {
    id: 'magisk',
    name: '特权 shell 探测',
    desc: '端点路径是 device/magisk，但 core 的实现已经改成「特权 shell 状态」（adb_shell / method / uid），不是 Magisk 安装检测。',
    group: 'env',
    scope: 'core',
    mode: 'auto',
    run: runMagisk,
  },
  {
    id: 'storage',
    name: '监控数据库占用',
    desc: 'core 监控数据库各表有多少行、占多大（我们自己的数据，可清理）。注意 sms_records 不参与清理，只会涨不会降。与下面「数据分区占用」不是一回事。',
    group: 'env',
    scope: 'core',
    mode: 'auto',
    run: runStorage,
  },
  {
    id: 'disk-usage',
    name: '数据分区占用',
    desc: '设备 /data 分区的总量与剩余（硬上限，写满只能删东西）。写满时表现为「某些功能莫名失败」，而报错里不会说「磁盘满」，所以 ≥90% 就开始提醒。',
    group: 'env',
    scope: 'core',
    mode: 'auto',
    run: runDiskUsage,
  },
  {
    id: 'media-permission',
    name: '媒体库权限',
    desc: '视频 / 音频 / 图片三类权限有没有授。未授权时媒体页会显示空列表，不是「设备里没有媒体」。',
    group: 'env',
    scope: 'core',
    mode: 'auto',
    run: runMediaPermission,
  },
  {
    id: 'core-permission',
    name: 'core 自身的权限',
    desc: 'core 声明过、但实际没拿到的权限。清单来自**已安装的 manifest**（不是手抄表），判据是系统自己的 checkPermission / AppOpsManager。缺权限时功能会「点了没用」且不报错 —— 升级 core 后新增的权限没人补，是这件事最常见的成因。可一键补全（写动作，二次确认）。',
    group: 'env',
    scope: 'core',
    mode: 'auto',
    render: 'verdict',
    run: runCorePermission,
  },
  {
    id: 'notify-permission',
    name: '浏览器通知授权',
    desc: '浏览器系统通知（验证码 / 告警在后台标签页提醒）。四态分开说：浏览器不支持 / 还没授权（能再申请）/ 已被拒绝（只能去站点设置）/ 已授权但开关关着。',
    group: 'env',
    // 本端专有：浏览器通知授权是**这个浏览器**的状态，app 有它自己的那条（app 的
    // `env.notifypermission` 查的是 Android 系统通知权限）—— 两者不是同一件事，别配对。
    scope: 'client',
    mode: 'auto',
    run: runBrowserNotifyPermission,
  },
  {
    id: 'cpu-probe',
    name: 'CPU 探针',
    desc: '这台设备能不能调频：cpufreq 节点是否存在、频率与 governor 能不能写、哪些核在线、有几个热区。★ 唯一一条慢检查（core 要发 20+ 条 shell，5~15 秒），全部命令只读或写回原值。',
    group: 'env',
    scope: 'core',
    mode: 'auto',
    run: runCpuProbe,
  },

  // ── network ──
  {
    id: 'net-ping',
    name: 'Ping',
    desc: `ICMP 连通性与往返时延（core 侧 /system/bin/ping -c 4 -W 2）${NETWORK_DESC_SUFFIX}`,
    group: 'network',
    scope: 'core',
    mode: 'manual',
    render: 'text',
    run: () => runNetworkTool('ping'),
  },
  {
    id: 'net-dns',
    name: 'DNS 解析',
    desc: `用设备自己的 resolver 解析目标（core 侧 InetAddress.getAllByName）${NETWORK_DESC_SUFFIX}`,
    group: 'network',
    scope: 'core',
    mode: 'manual',
    render: 'text',
    run: () => runNetworkTool('dns'),
  },
  {
    id: 'net-tcp',
    name: 'TCP 探测',
    desc: `对目标的指定端口做一次 TCP 连接（5 秒超时）${NETWORK_DESC_SUFFIX}`,
    group: 'network',
    scope: 'core',
    mode: 'manual',
    render: 'text',
    run: () => runNetworkTool('tcp'),
  },
  {
    id: 'net-traceroute',
    name: 'Traceroute',
    desc: `逐跳路径（无 root：TTL 递增 + ping，最多 15 跳，失败跳显示 *；精度有限）${NETWORK_DESC_SUFFIX}`,
    group: 'network',
    scope: 'core',
    mode: 'manual',
    render: 'text',
    run: () => runNetworkTool('traceroute'),
  },

  // ── service ──
  {
    id: 'service-status',
    name: '服务状态与崩溃',
    desc: '后台服务开关、采集循环、运行时长、原生二进制自检，以及上一次 core 崩溃。崩溃后 core 会被自动拉起，只看服务状态是看不出「数据断过一下」的。',
    group: 'service',
    scope: 'core',
    mode: 'auto',
    render: 'verdict',
    run: runServiceStatus,
  },
  {
    id: 'qos-status',
    name: 'QoS 状态',
    desc: 'Shell 与 goform 通道的并发许可、缓存命中情况、批处理节省量、线程池规模。',
    group: 'service',
    scope: 'core',
    mode: 'auto',
    run: runQosStatus,
  },
  {
    id: 'cache-health',
    name: '响应缓存健康',
    // 「维护在性能面板」必须写在描述里：卡片右上角那颗跳转按钮只写「去性能面板」，
    // 用户得先知道为什么这里只有两个数字、没有清空按钮。
    desc: '过期滞留（惰性删除没触发的条目）与 stale 标记（缓存可能过期）。只读摘要 —— 维护（清空 / 按规则失效）的真源是「设置 → 性能 → 缓存管理」，本页刻意不放写入动作。',
    group: 'service',
    scope: 'core',
    mode: 'auto',
    run: runCacheHealth,
    action: { label: '去性能面板', to: { name: 'settings', query: { tab: 'performance' } } },
  },
  {
    id: 'ws-channel',
    name: '实时推送通道',
    desc: '本页到 core 的 WebSocket（/ws/realtime）连没连上。它断了的时候，告警、验证码推送与页面自动刷新会一起失效，而界面上只有一个侧栏小圆点在提示。',
    group: 'service',
    // 本端专有：查的是**这个页面**到 core 的 WebSocket（`stores/websocket`）。
    // core 的 `/health` 报的是全部客户端的连接数，拿它当"本页通不通"的结论会自相矛盾。
    scope: 'client',
    mode: 'auto',
    render: 'verdict',
    run: runRealtimeChannel,
  },
  {
    id: 'notify-channels',
    name: '通知渠道配置',
    desc: '邮件 / Webhook / 本机短信三条外部渠道的开关与配置完整性。判据直接读 core 的 sendable / configured，不自己复现「填没填全」——开关开着但配置不全时通知会被静默跳过。',
    group: 'service',
    scope: 'core',
    mode: 'auto',
    run: runNotifyChannels,
  },
  {
    id: 'full-snapshot',
    name: '全量快照',
    desc: 'core 的 diagnose 端点原文：root / adbd / 移动数据 / 网关 / 设备 profile 选型。导出诊断包里也附这一份。',
    group: 'service',
    scope: 'core',
    mode: 'auto',
    run: runFullSnapshot,
  },
  {
    id: 'debug-logs',
    name: '调试日志',
    desc: '落盘日志文件有几个、多大、最后一次写是什么时候。这里只看文件清单，正文在设置页的日志面板。',
    group: 'service',
    scope: 'core',
    mode: 'auto',
    run: runDebugLogs,
    action: { label: '去日志面板', to: { name: 'settings', query: { tab: 'logs' } } },
  },
];

/** 某一组里的检查项，**保持 [DIAGNOSTICS] 里的声明顺序**。 */
export function itemsOfGroup(key: DiagnosticGroupKey): DiagnosticItem[] {
  return DIAGNOSTICS.filter((d) => d.group === key);
}

/** 描述符抛出的异常 → 一条 `fail` 结果（文案取自 core 的错误信封）。 */
export function failResult(e: unknown): DiagnosticResult {
  return { status: 'fail', summary: `检查失败：${reasonOf(e)}` };
}

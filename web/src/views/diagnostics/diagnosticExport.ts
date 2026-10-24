/**
 * 诊断包导出（2026-10-14 新建）。
 *
 * ## 用途
 * 一颗「导出诊断包」= `/api/diagnose` 全量 + 各检查项结果 + 最近日志 → 拼成一个 `.txt` 下载。
 * **纯前端拼，不新增 core 端点**：core 的 `DebugLogRoutes` 只按文件给
 * （`debug-logs/files` 列表 + 单文件尾部），没有"打包"端点，也不该为一个客户端排版需求加一个。
 *
 * ## 为什么是 .txt 而不是 .json
 * 诊断包的用途是**贴进 issue / 发给别人看**。整份 JSON 里换行被转义、中文被编码成 `\uXXXX`，
 * 人眼读不了、diff 也看不出东西；而 .txt 里 `diagnose` 那一段仍然是缩进过的 JSON 原文
 * （只是作为文本嵌进去），既能直接读，也能被 `jq` 之类的工具再解析一次。
 * 需要"逐字比对原文"的场景由卡片上的「复制原始输出」承担，不必让这个包兼两份职责。
 *
 * ## 没跑过的检查项也写进去
 * 包里对未运行的项写一行「未运行」，**不是留空**：收到诊断包的人必须能分清
 * "这条检查没跑"和"这条检查跑了但没输出"。少了这一行，两种情况的包长得一模一样。
 *
 * ## 否决过的替代方案
 * · **core 侧加一个打包端点**：它要么在服务端拼文本（core 不该知道 web 想怎么排版），
 *   要么把三份数据打成一个 JSON（前端还是要自己拼）。加端点只是把一次网络往返
 *   换成一次网络往返，却多一个要跟着前端排版走的服务端契约。
 * · **打成 zip**：本仓不引入新依赖，而浏览器原生没有归档格式（`CompressionStream` 是
 *   gzip 单流，不是 zip）。一个 .txt 已经够用。
 * · **导出时重新跑一遍全部检查**：那会把"导出"变成一次几十秒的阻塞操作，
 *   而且导出的结论与屏幕上看到的不一致 —— 用户是看着屏幕决定导出的。
 *   所以包里写的是**当前屏幕上那份结果**，只有 `diagnose` 与日志是导出时新抓的
 *   （它们便宜且时效性强，见 [buildDiagnosticBundle] 的注释）。
 */
import { Endpoints } from '@/api/contract';
import { getApiClient } from '@/composables/useApi';
import { reasonOf, type DiagnosticGroupKey, type DiagnosticItem, type DiagnosticResult } from './diagnosticRegistry';
import type { DiagnosticRunState } from './useDiagnosticRun';

/** 一行分隔线。用固定长度而不是 `'-'.repeat(72)` 之类：导出的文件是要被人看的。 */
const RULE = '─'.repeat(64);

/** 每次现取的请求客户端（理由见 `diagnosticRegistry.ts` 同名的那个常量）。 */
const api = getApiClient;

/** 分组 key → 标题（包里的小标题用中文，不用 `env` 这种内部名）。 */
const GROUP_TITLE: Record<DiagnosticGroupKey, string> = {
  env: '设备环境',
  network: '网络工具',
  service: '服务与快照',
};

function localTime(ms: number): string {
  return ms > 0 ? new Date(ms).toLocaleString('zh-CN', { hour12: false }) : '--';
}

/** 把一条结果排成文本块。`rows` 与 `raw` 都按原样展开，不做截断（导出就是为了看全）。 */
function renderResult(result: DiagnosticResult): string[] {
  const out: string[] = [`结论：${result.summary}`];
  for (const row of result.rows ?? []) {
    out.push(`  ${row.label}: ${row.value}`);
  }
  if (result.raw) {
    out.push('  原始输出:');
    for (const line of result.raw.split('\n')) {
      out.push(`    ${line}`);
    }
  }
  return out;
}

/**
 * 拼出整份诊断包文本。
 *
 * 两处是**导出时新抓**的，其余取屏幕上的当前结果：
 * · `diagnose` 全量快照 —— 它是整份包的主干，值几十行，重抓一次比"导出屏幕上的旧快照"
 *   更符合"诊断包"的语义；
 * · 最近日志 —— 日志是**时间序列**，屏幕上的"文件清单"里没有正文，只能现读。
 * 两者失败都不影响包的其余部分：如实写一行抓取失败的原因，包照样下载得下来
 * （一个抓不到就整份导不出，是最不该有的行为）。
 */
export async function buildDiagnosticBundle(
  snapshot: Array<{ item: DiagnosticItem; state: DiagnosticRunState }>
): Promise<string> {
  const out: string[] = [];
  const now = new Date();
  out.push('UFI-AXIS 诊断包');
  out.push(`导出时间：${now.toLocaleString('zh-CN', { hour12: false })}`);
  // 页面地址留着是为了分清"从 web 控制台导的"与"从 app 导的"（app 那份不经过这里）。
  out.push(`来源：${window.location.origin}${window.location.pathname}#/diagnostics`);
  out.push(RULE);

  out.push('[1/3] 全量诊断快照  GET api/diagnose');
  try {
    const { data } = await api().get(Endpoints.diagnose, { timeout: 30_000 });
    out.push(JSON.stringify(data, null, 2));
  } catch (e) {
    out.push(`抓取失败：${reasonOf(e)}`);
  }
  out.push(RULE);

  out.push('[2/3] 各检查项结果');
  out.push('');
  for (const { item, state } of snapshot) {
    const group = GROUP_TITLE[item.group] ?? item.group;
    out.push(`── [${group}] ${item.name}${state.result ? `（${state.result.status}）` : '（未运行）'}`);
    out.push(`说明：${item.desc}`);
    if (state.result) {
      out.push(`跑完时间：${localTime(state.finishedAt)}`);
      out.push(...renderResult(state.result));
    } else {
      out.push('结论：未运行');
    }
    out.push('');
  }
  out.push(RULE);

  out.push('[3/3] 最近调试日志  GET api/debug-logs?limit=200');
  try {
    const { data } = await api().get(Endpoints.diagnostics.debugLogs, {
      params: { limit: 200 },
      timeout: 15_000,
    });
    // `logs` 是**字符串数组**（`HH:mm:ss.SSS [LEVEL] [TAG] message`），不是对象数组 ——
    // 设置页 LogsPanel 的文件头记着这条（旧代码按对象读直接抛异常把卡片渲染中断）。
    const logs: unknown[] = Array.isArray(data?.logs) ? data.logs : [];
    out.push(`共 ${logs.length} 条（core 侧缓冲，不是落盘文件）`);
    for (const line of logs) {
      out.push(String(line));
    }
  } catch (e) {
    out.push(`抓取失败：${reasonOf(e)}`);
  }

  return `${out.join('\n')}\n`;
}

/** 触发浏览器下载。手法与 `composables/monitorCsv.ts` 的 `downloadCsv` 逐字一致。 */
export function downloadTextFile(filename: string, text: string): void {
  const blob = new Blob([text], { type: 'text/plain;charset=utf-8' });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = filename;
  a.click();
  URL.revokeObjectURL(url);
}

/** 文件名带时间戳：同一个目录里连导两份不会互相覆盖（`ufi-axis-diagnose-20261014-153000.txt`）。 */
export function diagnosticFileName(at: Date = new Date()): string {
  const p = (n: number) => String(n).padStart(2, '0');
  const stamp = `${at.getFullYear()}${p(at.getMonth() + 1)}${p(at.getDate())}-${p(at.getHours())}${p(at.getMinutes())}${p(at.getSeconds())}`;
  return `ufi-axis-diagnose-${stamp}.txt`;
}

/**
 * 导出并下载。
 *
 * 返回文件名供调用方提示；异常往上抛（抓取失败已经在包内如实记录，这里抛的只会是
 * 浏览器层的问题，如 blob 太大 / 下载被拦），由页面弹一条错误。
 */
export async function exportDiagnosticBundle(
  snapshot: Array<{ item: DiagnosticItem; state: DiagnosticRunState }>
): Promise<string> {
  const fileName = diagnosticFileName();
  const text = await buildDiagnosticBundle(snapshot);
  downloadTextFile(fileName, text);
  return fileName;
}

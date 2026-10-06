/**
 * 监控历史数据 → CSV 导出（纯前端，复用 MonitorView 已有的 /api/monitor/history 拉数逻辑）。
 *
 * 设计要点：
 * - 与 app 端 CsvExporter 对齐的列语义（时间戳、指标名、数值），但格式走
 *   「宽表」（每个指标一列）而不是 app 的「长表」（一行一个点）—— 监控页的
 *   消费场景是人看/Excel 透视，宽表直接可用；两个导出口径不必强制一致。
 * - 数据点直接用 core 的 DownsampledPoint（t/avg/min/max）：宽表输出 avg 列，
 *   min/max 列一并导出（丢失降采样的波动区间等于丢了一半信息）。
 * - 布尔开关与主题色一样走 localStorage（web 本地偏好惯例），不上报 core ——
 *   导出是浏览器行为，与设备状态无关。
 */

/** 与 monitorShared.Pt 同构（core DownsampledPoint，t = epoch 毫秒）。 */
export interface DownsampledPoint {
  t: number;
  avg: number;
  min: number;
  max: number;
}

export interface MetricSeries {
  /** 列名前缀（同时是 CSV 表头前缀，如 cpu / temperature / signal_rsrp） */
  key: string;
  /** 中文表头展示名 */
  label: string;
  points: DownsampledPoint[];
}

/** 是否导出 min/max 列（localStorage 持久化键）。 */
const LS_EXPORT_MINMAX = 'ufi.monitor.exportMinMax';

export function getExportMinMax(): boolean {
  return localStorage.getItem(LS_EXPORT_MINMAX) !== 'false';
}

export function setExportMinMax(v: boolean): void {
  localStorage.setItem(LS_EXPORT_MINMAX, String(v));
}

/** 行时间戳对齐：以全部序列时间戳的并集为准，缺的点留空单元格。 */
export function buildMonitorCsv(
  series: MetricSeries[],
  hours: number,
  includeMinMax = getExportMinMax(),
): string {
  const headers: string[] = ['时间'];
  for (const s of series) {
    headers.push(s.label);
    if (includeMinMax) {
      headers.push(`${s.label}最小`, `${s.label}最大`);
    }
  }
  const timeFmt = new Intl.DateTimeFormat('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  });

  // 时间戳并集（升序）
  const tsSet = new Set<number>();
  for (const s of series) for (const p of s.points) tsSet.add(p.t);
  const timestamps = [...tsSet].sort((a, b) => a - b);

  // 每个序列按 ts 索引
  const byTs = new Map<string, Map<number, DownsampledPoint>>();
  for (const s of series) {
    const m = new Map<number, DownsampledPoint>();
    for (const p of s.points) m.set(p.t, p);
    byTs.set(s.key, m);
  }

  const esc = (v: string | number) => {
    const str = String(v);
    // CSV 注入防护：以 =+-@ 开头的单元格前置单引号（Excel 公式注入）
    return /^[=+\-@]/.test(str) ? `'${str}` : str;
  };

  const lines: string[] = [];
  // BOM：Excel 识别 UTF-8 必需
  lines.push('\ufeff' + headers.map(esc).join(','));
  lines.push(`# 导出时间范围:最近 ${hours} 小时;生成时间:${new Date().toLocaleString('zh-CN')}`);

  for (const ts of timestamps) {
    const cells: string[] = [timeFmt.format(new Date(ts))];
    for (const s of series) {
      const p = byTs.get(s.key)?.get(ts);
      if (includeMinMax) {
        cells.push(p ? String(p.avg) : '', p ? String(p.min) : '', p ? String(p.max) : '');
      } else {
        cells.push(p ? String(p.avg) : '');
      }
    }
    lines.push(cells.map(esc).join(','));
  }
  return lines.join('\r\n');
}

/** 触发浏览器下载。 */
export function downloadCsv(filename: string, csv: string): void {
  const blob = new Blob([csv], { type: 'text/csv;charset=utf-8' });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = filename;
  a.click();
  URL.revokeObjectURL(url);
}

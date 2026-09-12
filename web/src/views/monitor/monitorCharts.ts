/**
 * 监控页的 ECharts option 构造。
 *
 * 拆出来的理由：这 4 个构造函数在 MonitorView 里占了近 400 行，而它们是纯函数
 * ——「数据 + 上下文 → option」，不碰任何响应式状态。留在 SFC 里只是让文件变长。
 *
 * 颜色必须是已解析的字面量：ECharts 用 CanvasRenderer，`var(--x)` 赋给
 * ctx.fillStyle 会被静默忽略（画出上一次的颜色或黑色）。所以这里收 [ChartColors]，
 * 由调用方通过 useChartColors() 解析后传入，不在本文件里读 CSS 变量。
 *
 * 温度图原来是 pctChartOption 的一份逐行复制（只差单位、颜色和不夹 0..100），
 * 这里合并成同一个函数的两种调用；对比过两份 option 的每个字段，输出完全一致。
 */
import type { ChartColors } from '@/composables/chartTheme';
import { withGapBreaks } from '@/composables/chartTheme';
import { formatSpeedAxis, formatSpeedValue, formatTime, xAxisLabelFormat, type Pt } from './monitorShared';

export interface ChartCtx {
  colors: ChartColors;
  /** 当前时间范围，决定 x 轴与 tooltip 的时间格式 */
  hours: number;
  fillAlpha: number;
  fixedYAxis: boolean;
}

type Option = Record<string, any>;

/** 空数据时在图表中央显示提示，避免看起来像渲染失败 */
function emptyTitle(len: number, colors: ChartColors): Option {
  return {
    show: len === 0,
    text: '暂无数据',
    left: 'center',
    top: 'middle',
    textStyle: { color: colors.textMuted, fontSize: 13, fontWeight: 'normal' },
  };
}

function legendOf(data: string[], colors: ChartColors): Option {
  return { data, textStyle: { color: colors.textSecondary, fontSize: 11 }, top: 0, right: 0 };
}

function tooltipOf(colors: ChartColors, formatter: (params: any) => string): Option {
  return {
    trigger: 'axis',
    backgroundColor: colors.popoverBg,
    borderColor: colors.border,
    textStyle: { color: colors.textPrimary, fontSize: 12 },
    formatter,
  };
}

function timeAxis(ctx: ChartCtx): Option {
  return {
    type: 'time',
    axisLabel: { color: ctx.colors.textMuted, fontSize: 11, formatter: xAxisLabelFormat(ctx.hours) },
    axisLine: { lineStyle: { color: ctx.colors.border } },
    splitLine: { show: false },
  };
}

/**
 * 最大 / 最小 / 平均三条线：最大与最小画淡填充，最小那条用卡片底色盖掉
 * 中间区域，视觉上得到「区间带」；平均线在最上层（z 递增）。
 */
function bandSeries(pts: Pt[], color: string, ctx: ChartCtx) {
  const line = (get: (p: Pt) => number) => withGapBreaks(pts, (p) => p.t, get);
  return [
    {
      name: '最大',
      type: 'line',
      smooth: true,
      symbol: 'none',
      lineStyle: { width: 1, color, opacity: 0.3 },
      areaStyle: { color, opacity: 0.1 * ctx.fillAlpha },
      data: line((p) => p.max),
      z: 1,
    },
    {
      name: '最小',
      type: 'line',
      smooth: true,
      symbol: 'none',
      lineStyle: { width: 1, color, opacity: 0.3 },
      areaStyle: { color: ctx.colors.cardBg },
      data: line((p) => p.min),
      z: 3,
    },
    {
      name: '平均',
      type: 'line',
      smooth: true,
      symbol: 'none',
      lineStyle: { width: 2, color },
      data: line((p) => p.avg),
      z: 4,
    },
  ];
}

/**
 * 区间带折线图：CPU / 内存 / 电池（百分比）与温度共用。
 *
 * @param unit 直接拼在数值后面，需要空格的自己带（温度传 ' °C'）
 * @param clampPercent 是否套用「Y 轴固定」偏好（0..100）。
 *   只有百分比图能用：流量 / 信号 / 温度没有公认的固定区间，硬给一个只会误导。
 */
export function bandChartOption(
  data: Pt[] | undefined,
  color: string,
  ctx: ChartCtx,
  unit = '%',
  clampPercent = true
): Option {
  const pts = data || [];
  // fixedYAxis=true → 固定 0..100；false（默认）→ 按数据自适应缩放
  const range = clampPercent ? (ctx.fixedYAxis ? { min: 0, max: 100 } : { scale: true }) : {};
  return {
    color: [color],
    title: emptyTitle(pts.length, ctx.colors),
    legend: legendOf(['最大', '最小', '平均'], ctx.colors),
    grid: { top: 30, right: 16, bottom: 24, left: 50 },
    tooltip: tooltipOf(ctx.colors, (params: any) => {
      if (!params?.length) return '';
      // 按时间戳回查而不是用 dataIndex：withGapBreaks 会在断档处插入 null 点，
      // series 的下标已经和 pts 不再一一对应。
      const ts = params[0].value?.[0];
      const time = formatTime(ts, ctx.hours);
      const pt = pts.find((x) => x.t === ts);
      if (!pt) return time;
      return (
        `<b>${time}</b><br/>` +
        `平均: ${pt.avg.toFixed(1)}${unit}<br/>` +
        `最大: ${pt.max.toFixed(1)}${unit}<br/>` +
        `最小: ${pt.min.toFixed(1)}${unit}`
      );
    }),
    xAxis: timeAxis(ctx),
    yAxis: {
      type: 'value',
      ...range,
      axisLabel: { color: ctx.colors.textMuted, fontSize: 11, formatter: `{value}${unit}` },
      splitLine: { lineStyle: { color: ctx.colors.border, type: 'dashed' } },
    },
    series: bandSeries(pts, color, ctx),
  };
}

/** 网络流量（下载 / 上传双系列） */
export function trafficChartOption(rxData: Pt[] | undefined, txData: Pt[] | undefined, ctx: ChartCtx): Option {
  const rx = rxData || [];
  const tx = txData || [];
  const series = (name: string, pts: Pt[]) => ({
    name,
    type: 'line',
    smooth: true,
    symbol: 'none',
    lineStyle: { width: 2 },
    areaStyle: { opacity: 0.06 * ctx.fillAlpha },
    data: withGapBreaks(
      pts,
      (p) => p.t,
      (p) => p.avg
    ),
  });
  return {
    color: [ctx.colors.info, ctx.colors.success],
    title: emptyTitle(rx.length + tx.length, ctx.colors),
    legend: legendOf(['下载', '上传'], ctx.colors),
    grid: { top: 30, right: 16, bottom: 24, left: 60 },
    tooltip: tooltipOf(ctx.colors, (params: any) => {
      if (!params?.length) return '';
      let html = `<b>${formatTime(params[0].value[0], ctx.hours)}</b>`;
      for (const p of params) {
        html += `<br/>${p.marker} ${p.seriesName}: ${formatSpeedValue(p.value[1])}`;
      }
      return html;
    }),
    xAxis: timeAxis(ctx),
    yAxis: {
      type: 'value',
      axisLabel: { color: ctx.colors.textMuted, fontSize: 11, formatter: formatSpeedAxis },
      splitLine: { lineStyle: { color: ctx.colors.border, type: 'dashed' } },
    },
    series: [series('下载', rx), series('上传', tx)],
  };
}

/** 信号强度（RSRP / SINR 双 Y 轴：两者量纲不同，共轴会把其中一条压平） */
export function signalChartOption(rsrpData: Pt[] | undefined, sinrData: Pt[] | undefined, ctx: ChartCtx): Option {
  const rsrp = rsrpData || [];
  const sinr = sinrData || [];
  const series = (name: string, pts: Pt[], yAxisIndex: number) => ({
    name,
    type: 'line',
    smooth: true,
    symbol: 'none',
    lineStyle: { width: 2 },
    yAxisIndex,
    data: withGapBreaks(
      pts,
      (p) => p.t,
      (p) => p.avg
    ),
  });
  const axis = (name: string, position: 'left' | 'right', splitLine: boolean) => ({
    type: 'value',
    name,
    position,
    nameTextStyle: { color: ctx.colors.textMuted, fontSize: 11 },
    axisLabel: { color: ctx.colors.textMuted, fontSize: 11 },
    splitLine: splitLine ? { lineStyle: { color: ctx.colors.border, type: 'dashed' } } : { show: false },
  });
  return {
    color: [ctx.colors.info, ctx.colors.warning],
    title: emptyTitle(rsrp.length + sinr.length, ctx.colors),
    legend: legendOf(['RSRP', 'SINR'], ctx.colors),
    grid: { top: 30, right: 50, bottom: 24, left: 50 },
    tooltip: tooltipOf(ctx.colors, (params: any) => {
      if (!params?.length) return '';
      let html = `<b>${formatTime(params[0].value[0], ctx.hours)}</b>`;
      for (const p of params) {
        const u = p.seriesName === 'RSRP' ? 'dBm' : 'dB';
        html += `<br/>${p.marker} ${p.seriesName}: ${p.value[1]?.toFixed(1) ?? '--'} ${u}`;
      }
      return html;
    }),
    xAxis: timeAxis(ctx),
    yAxis: [axis('RSRP (dBm)', 'left', true), axis('SINR (dB)', 'right', false)],
    series: [series('RSRP', rsrp, 0), series('SINR', sinr, 1)],
  };
}

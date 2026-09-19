/**
 * 流量历史页的 ECharts option 构造。
 *
 * 拆出来的理由与 `monitorCharts.ts` 一致：这是「数据 + 配色 → option」的纯函数，
 * 不碰响应式状态，留在 SFC 里只是让文件变长。
 *
 * 配色收成入参而不是在这里读 CSS 变量：ECharts 走 CanvasRenderer，`var(--x)`
 * 赋给 ctx.fillStyle 会被静默忽略（画出上一次的颜色或黑色），所以调用方必须先用
 * `useChartColors()` 解析成字面量再传进来。
 */
import type { ChartColors } from '@/composables/chartTheme';
import { formatBytes } from '@/composables/utils';
import type { TrafficUsageBucket, TrafficUsageResponse } from '@/api/traffic';
import { TRAFFIC_AXIS_GRID_DIVISIONS, showTrafficBucketLabel, trafficAxisPeakBytes } from './trafficHistoryShared';

type Option = Record<string, any>;

/** 系列名同时用于 legend 与 tooltip 取 marker，抽成常量避免两处文案漂移 */
const SERIES_RX = '下行';
const SERIES_TX = '上行';

/**
 * 浮层的一行：左标签、右数值，靠 space-between 撑开。
 *
 * 刻意**不给行加 `width: 100%`**：app 端这么写过一次，浮层被撑成横跨屏幕的一条横幅
 * （tooltip 容器自身没有宽度约束，行占满宽度就等于让容器去够父级宽度）。
 * 宽度只由内容决定，另给 max-width 兜住超长数值。
 */
function tooltipRow(marker: string, label: string, bytes: number): string {
  // marker 为空时补一个等宽占位，让「合计」与上两行的文字左边缘对齐
  const dot = marker || '<span style="display:inline-block;width:10px;margin-right:4px"></span>';
  return (
    '<div style="display:flex;align-items:center;justify-content:space-between;gap:16px">' +
    `<span>${dot}${label}</span>` +
    `<span style="font-variant-numeric:tabular-nums">${formatBytes(bytes)}</span>` +
    '</div>'
  );
}

function stackedBar(name: string, values: number[]) {
  return {
    name,
    type: 'bar',
    // 同一个 stack：下行占下半段、上行占上半段，柱高等于该桶合计
    stack: 'traffic',
    barMaxWidth: 22,
    data: values,
  };
}

/**
 * 日/周/月/年用量的堆叠柱状图。
 *
 * 空态（桶为空数组或全 0）由页面换成文案，这里不负责 —— 画一排 0 高柱子的话，
 * 「没有记录」和「有数据但很小」在图上长得一模一样。
 */
export function trafficHistoryBarOption(data: TrafficUsageResponse | null, colors: ChartColors): Option {
  const buckets: TrafficUsageBucket[] = data?.buckets ?? [];
  const count = buckets.length;
  return {
    color: [colors.info, colors.success],
    legend: {
      data: [SERIES_RX, SERIES_TX],
      textStyle: { color: colors.textSecondary, fontSize: 11 },
      top: 0,
      right: 0,
    },
    // top 留 34：既给 legend 让位，也保证 Y 轴取整后最高的柱不会贴到容器上边缘
    grid: { top: 34, right: 14, bottom: 26, left: 64 },
    tooltip: {
      trigger: 'axis',
      axisPointer: { type: 'shadow' },
      backgroundColor: colors.popoverBg,
      borderColor: colors.border,
      textStyle: { color: colors.textPrimary, fontSize: 12 },
      formatter: (params: any) => {
        const list = Array.isArray(params) ? params : [params];
        const bucket = buckets[Number(list[0]?.dataIndex ?? -1)];
        if (!bucket) {
          return '';
        }
        // 标题用 core 给的 title（"9月14日 周一"）而不是 X 轴那份短标签：
        // 轴上的 "14" 单独看不出是 14 号还是 14 点
        const rxMarker = String(list.find((p: any) => p.seriesName === SERIES_RX)?.marker ?? '');
        const txMarker = String(list.find((p: any) => p.seriesName === SERIES_TX)?.marker ?? '');
        return (
          '<div style="max-width:240px">' +
          `<div style="font-weight:600;margin-bottom:4px">${bucket.title}</div>` +
          tooltipRow(rxMarker, SERIES_RX, bucket.rx_bytes) +
          tooltipRow(txMarker, SERIES_TX, bucket.tx_bytes) +
          tooltipRow('', '合计', bucket.total_bytes) +
          '</div>'
        );
      },
    },
    xAxis: {
      type: 'category',
      data: buckets.map((b) => b.label),
      // 刻度线全留（interval: 0），只抽稀标签 —— 月视图 31 个标签排不开，
      // 但刻度线本身是「一桶一格」的读图依据，抽掉就数不清柱子对应哪一天
      axisTick: { show: true, alignWithLabel: true, interval: 0, lineStyle: { color: colors.border } },
      axisLine: { lineStyle: { color: colors.border } },
      axisLabel: {
        color: colors.textMuted,
        fontSize: 11,
        interval: 0,
        formatter: (_value: string, index: number) =>
          showTrafficBucketLabel(index, count) ? (buckets[index]?.label ?? '') : '',
      },
    },
    yAxis: {
      type: 'value',
      min: 0,
      // 峰值向上取整到「末位是 0」的档位，网格线才能当尺子用（见 trafficAxisPeakBytes）
      max: trafficAxisPeakBytes(data?.peak_bytes ?? 0),
      splitNumber: TRAFFIC_AXIS_GRID_DIVISIONS,
      axisLabel: { color: colors.textMuted, fontSize: 11, formatter: (v: number) => formatBytes(v) },
      splitLine: { lineStyle: { color: colors.border, type: 'dashed' } },
    },
    series: [
      stackedBar(
        SERIES_RX,
        buckets.map((b) => b.rx_bytes)
      ),
      stackedBar(
        SERIES_TX,
        buckets.map((b) => b.tx_bytes)
      ),
    ],
  };
}

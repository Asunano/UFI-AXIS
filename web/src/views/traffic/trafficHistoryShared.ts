/**
 * 流量历史页的纯计算部分：Y 轴取整、X 轴标签抽稀、历史起点提示。
 *
 * 拆出来的理由与 `monitorShared.ts` 一致 —— 这些函数同时被页面（状态与文案）
 * 和图表组件（option 构造）需要，留在任一个 SFC 里另一方就得反向 import。
 */
import type { TrafficUsageRange } from '@/api/traffic';

/** 区间分段控件的选项（n-tabs 的 name/label 与 n-select 的 value/label 同形状） */
export const TRAFFIC_RANGE_OPTIONS: { label: string; value: TrafficUsageRange }[] = [
  { label: '日', value: 'day' },
  { label: '周', value: 'week' },
  { label: '月', value: 'month' },
  { label: '年', value: 'year' },
];

/** 4 段 = 5 条网格线（含 0 基线），落在"4~5 条"这个还能当尺子用的区间。与 app 端同值。 */
export const TRAFFIC_AXIS_GRID_DIVISIONS = 4;

/** 全 0 时的兜底峰值（10 B）：不能用 0，否则柱高归一会除出 NaN。 */
const TRAFFIC_AXIS_EMPTY_PEAK_BYTES = 10;

/**
 * Y 轴峰值（字节），与 app 端 `ufiBarChartAxis` 同一套算法。
 *
 * 规则：先找到「让显示值 ≥ 10」的最大 1024 进制档位 U，粒度 = `10 × U`，
 * 再把峰值向上取整到粒度的整数倍 —— 显示出来的峰值末位一定是 0，
 * 读图的人能拿网格线当尺子（18.9 GB 没法心算，20 GB 才能）。
 *
 * 算例：18.9 GB → 20 GB；103 MB → 110 MB；5 MB → 5120 KB；0 → 10 B（兜底）。
 *
 * 刻意不在这里产出文案：单位阶梯与小数位在既有的字节格式化工具里已有唯一口径，
 * 这里再写一份的后果是 Y 轴写「20GB」、浮层写「20.00 GB」。
 */
export function trafficAxisPeakBytes(peakBytes: number): number {
  if (!Number.isFinite(peakBytes) || peakBytes <= 0) {
    return TRAFFIC_AXIS_EMPTY_PEAK_BYTES;
  }
  let unit = 1;
  // 上界保护：unit 每轮 ×1024，正常一定先停下，这里只是不让异常输入把循环拖住
  while (unit <= Number.MAX_SAFE_INTEGER / 1024 && peakBytes / (unit * 1024) >= 10) {
    unit *= 1024;
  }
  const granularity = unit * 10;
  return Math.ceil(peakBytes / granularity) * granularity;
}

/**
 * X 轴标签是否显示：刻度线全画，标签按**桶数**抽稀。
 *
 * 判据只看桶数、不看 range 名 —— 图表不该认识"日/周/月/年"这些业务概念，
 * 而且日视图在 DST 切换日是 23 或 25 个桶（core `TrafficUsageWindow` 明确如此），
 * 写 `=== 24` 那天就会错。标签带单位（"12时" / "14日"）比裸数字宽，年视图也要抽。
 */
export function showTrafficBucketLabel(index: number, count: number): boolean {
  if (count <= 7) {
    return true; // 周：7 个全显
  }
  if (count <= 12) {
    return index % 2 === 0 || index === count - 1; // 年：1/3/5/7/9/11 月 + 12月
  }
  if (count <= 25) {
    return index % 6 === 0 || index === count - 1; // 日：0/6/12/18 时 + 末位
  }
  return index === 0 || (index + 1) % 5 === 0 || index === count - 1; // 月：1/5/10/… + 末日
}

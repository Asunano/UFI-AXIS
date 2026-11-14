/**
 * `/api/traffic/*` 的响应类型与取数封装。
 *
 * 为什么收在 `api/` 而不是页面旁边：`/api/traffic/usage` 的响应形状是**契约**，
 * 不是某一页的视图模型 —— 仪表盘流量卡以后复用同一段数据时不该再抄一份类型。
 *
 * 这里**不新建 axios 实例**：鉴权（Bearer + 设备签名）与 444 处理全在
 * `composables/useApi.ts` 的单例拦截器里，另起一个实例等于绕过验签，必然 444。
 * 客户端由调用方传入，视图传 `useCancellableApi()` 就顺带拿到「组件卸载即取消」。
 */
import type { AxiosInstance } from 'axios';
import { Endpoints } from './contract';

/** 区间：与 core `TrafficUsageRange` 的 `name.lowercase()` 一致 */
export type TrafficUsageRange = 'day' | 'week' | 'month' | 'year';

export const TRAFFIC_USAGE_RANGES: TrafficUsageRange[] = ['day', 'week', 'month', 'year'];

/**
 * 单个桶。
 *
 * `label` 与 `title` **不是同一份文案，也不许互相拼装**：
 * - `label` 给 X 轴刻度用，必须短（月视图一行要排 31 个）：`"0时"` / `"周一"` / `"14日"` / `"11月"`；
 * - `title` 给浮层与明细行用，要能独立看懂（`"14"` 是 14 号还是 14 点？）：`"9月14日 周一"`。
 * 两者都由 core 生成，前端直接展示，不按 `bucket_unit` 分支造文案。
 */
export interface TrafficUsageBucket {
  index: number;
  start: number;
  label: string;
  title: string;
  rx_bytes: number;
  tx_bytes: number;
  total_bytes: number;
}

export interface TrafficUsageResponse {
  range: TrafficUsageRange;
  range_start: number;
  range_end: number;
  /** 顶部段落文案（`"9月13日-9月19日"` 这类），直接展示 */
  label: string;
  bucket_unit: string;
  /**
   * 完整桶序列：日 23~25（DST 切换日不是恒 24）/ 周 7 / 月 28~31 / 年 12。
   * 没数据的桶三个字节数都是 0，前端**不补桶**。
   */
  buckets: TrafficUsageBucket[];
  total_rx_bytes: number;
  total_tx_bytes: number;
  total_bytes: number;
  /** 所有桶 total_bytes 的最大值，由 core 算，柱高与 Y 轴都按它归一 */
  peak_bytes: number;
  /** 左右翻页锚点：**原样回传**给下一次请求的 anchor，不在前端算"前一天" */
  prev_anchor: number;
  next_anchor: number;
  /** false = 下一段还没开始，此时不该让用户往未来翻 */
  has_next: boolean;
  /** 有记录的最早时刻；**表里还没数据时是 null**，不能当 0 处理（会被读成 1970 年） */
  earliest_data_at: number | null;
}

/** 只用到 get，收窄成最小接口，方便传入 useCancellableApi() 的包装客户端 */
type TrafficApiClient = Pick<AxiosInstance, 'get'>;

function toNumber(v: unknown): number {
  const n = Number(v);
  return Number.isFinite(n) ? n : 0;
}

function toRange(v: unknown): TrafficUsageRange {
  return TRAFFIC_USAGE_RANGES.includes(v as TrafficUsageRange) ? (v as TrafficUsageRange) : 'day';
}

function toBucket(raw: Record<string, unknown>, index: number): TrafficUsageBucket {
  const rx = toNumber(raw?.rx_bytes);
  const tx = toNumber(raw?.tx_bytes);
  const label = String(raw?.label ?? '');
  return {
    index: Number.isFinite(Number(raw?.index)) ? Number(raw.index) : index,
    start: toNumber(raw?.start),
    label,
    // 老版本 core 没有 title 时退回轴文案，不要让浮层与明细显示空白
    title: String(raw?.title ?? '') || label,
    rx_bytes: rx,
    tx_bytes: tx,
    // 合计以两段之和为准，避免"合计 ≠ 上下行相加"这种自相矛盾的显示
    total_bytes: rx + tx,
  };
}

/**
 * 归一化响应。
 *
 * 缺字段一律给"能画出来的零值"而不是抛错：这是纯展示型查询，半截数据画成空图
 * 也比整块变成错误提示有用。**唯一保留 null 语义的是 `earliest_data_at`** ——
 * 它是"还没有任何记录"与"记录从某刻开始"的判据，折成 0 就没法诚实提示了。
 */
function normalizeUsage(raw: unknown): TrafficUsageResponse | null {
  // useCancellableApi 取消在途请求时 data 是 undefined，按"无结果"处理
  if (!raw || typeof raw !== 'object') {
    return null;
  }
  const o = raw as Record<string, unknown>;
  const earliest = o.earliest_data_at;
  return {
    range: toRange(o.range),
    range_start: toNumber(o.range_start),
    range_end: toNumber(o.range_end),
    label: String(o.label ?? ''),
    bucket_unit: String(o.bucket_unit ?? ''),
    buckets: Array.isArray(o.buckets) ? o.buckets.map(toBucket) : [],
    total_rx_bytes: toNumber(o.total_rx_bytes),
    total_tx_bytes: toNumber(o.total_tx_bytes),
    total_bytes: toNumber(o.total_bytes),
    peak_bytes: toNumber(o.peak_bytes),
    prev_anchor: toNumber(o.prev_anchor),
    next_anchor: toNumber(o.next_anchor),
    has_next: o.has_next === true,
    earliest_data_at: earliest == null || !Number.isFinite(Number(earliest)) ? null : Number(earliest),
  };
}

/**
 * 拉取日/周/月/年用量分桶。
 *
 * @param anchor 上一次响应的 `prev_anchor` / `next_anchor`，**原样透传**；
 *   不传 = 当前段（core 用 now 定位窗口）。
 * @returns 请求被取消时为 null（调用方据此跳过写状态）
 */
export async function getTrafficUsage(
  api: TrafficApiClient,
  range: TrafficUsageRange,
  anchor?: number | null
): Promise<TrafficUsageResponse | null> {
  const params: Record<string, string | number> = { range };
  if (anchor != null) {
    params.anchor = anchor;
  }
  const { data } = await api.get(Endpoints.traffic.usage, { params });
  return normalizeUsage(data);
}

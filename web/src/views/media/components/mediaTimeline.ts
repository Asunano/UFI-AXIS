/**
 * 媒体列表的「按天分段」（2026-10-14）。
 *
 * ## 为什么单独一个模块
 * 段头文案（今天 / 昨天 / 9月18日 / 2025年9月18日）与"怎么算同一段"这两件事，
 * 与 `mediaShared.ts` 里那批格式化函数是同一类东西，但**那个文件本次不改**
 * （它刚被另一位改过，是本次要用的基础）。所以另起一个小模块，
 * 逐字对齐 app 的 `MediaListCommon.kt:304-359`（`groupMediaByDay` /
 * `mediaDateSectionLabel` / `NO_DATE_SECTION_LABEL`）。
 *
 * ## 口径（照抄 app，不要"改进"）
 * · **不重新排序**：顺序是 core 按当前 `sort/order` 给的，客户端再排一次会在
 *   "按名称排序"时得到一份与列表不一致的分段。本模块只做"**相邻**同一天归一段"——
 *   时间倒序进来就是倒序的段，正序进来就是正序的段。
 * · **分页追加天然正确**：新来的一页接在尾部，最多只影响最后一段。
 * · `date_modified` 为 0 的条目**单独一段**、段头是「没有时间信息」，
 *   不拿"今天"冒充 —— 那会让时间轴撒谎。
 * · **不做"3 天前"这类相对文案**：翻到半年前的照片时它毫无定位作用。
 *
 * ## 为什么用泛型
 * 音频页的列表将来也要同一种分段（app 侧就是三页共用这一个函数）。收成
 * `T extends { date_modified: number }` 之后调用方不必先把条目转成中间结构。
 *
 * ## 否决过的替代方案
 * · **在 `MediaImageView.vue` 里内联**：段头文案将来一定会被第二处引用
 *   （app 是视频/音频/图片三页共用），内联就是等着被抄第二份。
 * · **按"日期字符串"分组而不是"相邻同一天"**：那会在"按名称排序"时把同一天拆成
 *   不相邻的好几段（同名文件散落各处），段头重复出现。
 * · **用 `toLocaleDateString` 生成段头**：那会得到「2026/9/18」这类与 app 不一致的写法，
 *   而且今天/昨天这两档口语化文案它给不出来。
 */

/**
 * 「没有可用修改时间」那一段的 key。
 *
 * 用 `Number.MIN_SAFE_INTEGER` 而不是 `-1`：`toEpochDay` 的类比物（这里用
 * "当天零点的时间戳 / 86400000"）不会取到负数，而 `-1` 在数学上是 1969-12-31，
 * 一个真实的日期。哨兵必须落在取值域之外。
 */
export const NO_DATE_SECTION_KEY = Number.MIN_SAFE_INTEGER;

/** 没有修改时间时的段头。见文件头：不拿"今天"冒充。 */
export const NO_DATE_SECTION_LABEL = '没有时间信息';

/** 一段（同一天）里的条目，**带上它在原列表里的下标** —— 图片查看器翻页用的就是它。 */
export interface MediaDaySection<T> {
  /** 段 key：当天零点的时间戳 / 86400000；无时间信息的是 [NO_DATE_SECTION_KEY]。 */
  key: number;
  /** 段头文案。 */
  label: string;
  /** 段内条目。`index` 是在传入数组里的原始下标，不是段内下标。 */
  entries: Array<{ item: T; index: number }>;
}

/** 两个时间戳是不是同一个**本地日**。跨时区/夏令时都由 Date 的本地字段兜住。 */
function sameLocalDay(a: Date, b: Date): boolean {
  return a.getFullYear() === b.getFullYear() && a.getMonth() === b.getMonth() && a.getDate() === b.getDate();
}

/** 本地日序号（同一天同一个值）。与 app 的 `LocalDate.toEpochDay()` 同一个用途。 */
function localDayKey(d: Date): number {
  return Math.floor(new Date(d.getFullYear(), d.getMonth(), d.getDate()).getTime() / 86400000);
}

/**
 * 段头文案：越近越口语化。
 *
 * 「今天 / 昨天」是用户真正在找的那两天；同年只写月日（年份是冗余信息）；
 * 跨年才补上年份。与 `MediaListCommon.kt:346-351` 逐字一致。
 */
export function mediaDateSectionLabel(day: Date, today: Date = new Date()): string {
  if (sameLocalDay(day, today)) return '今天';
  const yesterday = new Date(today.getFullYear(), today.getMonth(), today.getDate() - 1);
  if (sameLocalDay(day, yesterday)) return '昨天';
  if (day.getFullYear() === today.getFullYear()) return `${day.getMonth() + 1}月${day.getDate()}日`;
  return `${day.getFullYear()}年${day.getMonth() + 1}月${day.getDate()}日`;
}

/**
 * 把**已排好序**的条目折成按天的分段。
 *
 * `today` 可传（测试/多段共用同一个"今天"），默认取当前时刻。
 */
export function groupMediaByDay<T extends { date_modified: number }>(
  items: T[],
  today: Date = new Date()
): Array<MediaDaySection<T>> {
  if (items.length === 0) return [];
  const sections: Array<MediaDaySection<T>> = [];
  let currentKey = NO_DATE_SECTION_KEY;
  let currentLabel = NO_DATE_SECTION_LABEL;
  let bucket: Array<{ item: T; index: number }> = [];

  const flush = () => {
    if (bucket.length > 0) sections.push({ key: currentKey, label: currentLabel, entries: bucket });
  };

  items.forEach((item, index) => {
    const day = item.date_modified > 0 ? new Date(item.date_modified) : null;
    const key = day ? localDayKey(day) : NO_DATE_SECTION_KEY;
    if (index === 0 || key !== currentKey) {
      flush();
      currentKey = key;
      currentLabel = day ? mediaDateSectionLabel(day, today) : NO_DATE_SECTION_LABEL;
      bucket = [];
    }
    bucket.push({ item, index });
  });
  flush();
  return sections;
}

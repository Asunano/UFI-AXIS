/**
 * 温度传感器的展示口径（2026-10-21，B3 批次独占文件，见 docs-dev/web-ui-remediation-plan.md §6.4）。
 *
 * ## 为什么单开一份
 * `DeviceView` 的温度摘要与 `ThermalModal` 的明细弹窗读的是**同一个端点**
 * （`GET /api/device/thermal`），原先各自抄了一份「三档颜色 + 名称兜底」——
 * 两处一旦分叉，同一颗传感器在卡片上是绿的、在弹窗里是黄的，用户没法判断该信哪一个。
 * 这里只放纯函数：请求与轮询节奏归各自的组件，口径归这里。
 *
 * ## 端点契约
 * core 侧见 `SystemCollector.getThermalZones()`：`{ zones: [{ name, temperature }] }`，
 * `name` 取自 `/sys/class/thermal/thermal_zoneN/type`。
 */

/** 一个热区的展示模型（模板直接消费，不再各自映射）。 */
export interface ThermalZone {
  /** 列表 key —— `name` 可能重复（同型号多颗传感器），所以拼上序号。 */
  key: string;
  /** 展示名：中文名 / 认不出的原标识 / 「未知传感器」。 */
  label: string;
  /** 设备自报的原始标识；已经等于 label 时为 null（不重复展示）。 */
  raw: string | null;
  /** 有效读数（摄氏度）。**读不到才是 null** —— 0°C 是合法读数。 */
  temp: number | null;
}

/** 高温档（≥ 此值用 `--error`）。与 app 侧温控预警默认值同为 70℃。 */
const THERMAL_HOT_C = 70;
/** 偏高档（≥ 此值用 `--warning`）。 */
const THERMAL_WARM_C = 50;

/**
 * 热区标识 → 中文名。
 *
 * 热区名来自设备 `thermal_zoneN/type`，是**厂商自报的自由文本**
 * （MTK 的 `mtktscpu`、高通的 `cpu-0-0-usr`、`pa-therm0`…），没有统一词表，
 * 所以这里按「常见族」做前缀匹配；认不出的**原样显示** —— 它仍是这颗传感器唯一的标识，
 * 抹成「未知传感器」只会让十几颗传感器变成一堆同名行（app 侧
 * `HomeMetricsDialog` 也是原样显示 `name`，只在末尾去掉 `-thmzone` 后缀）。
 * 真正缺名（空串 / 字面量 `unknown`）才落到「未知传感器」。
 */
const LABEL_RULES: Array<[RegExp, string]> = [
  // 数组顺序即优先级：取第一个命中的族名（族名之间基本不重叠，顺序只是兜底约定）
  [/^(mtktscpu|apcpu|cpuss|cpu|soc|mtktsap)/, '处理器'],
  [/^(big|mid|little|cluster)\d*/, '处理器核心簇'],
  [/^(gpu|gpuss|nsp)/, '图形处理器'],
  [/^(batt|mtktsbattery|battery)/, '电池'],
  [/^(chg|charg|mtktscharger)/, '充电'],
  [/^(modem|md[-_]|nr[-_]|lte|mtktswmt|wmt)/, '调制解调器'],
  [/^(mtktspa|pa[-_]?therm\d*|pa\d+)/, '射频功放'],
  [/^(wifi|wlan|mtktswifi)/, 'WiFi'],
  [/^(xo[-_]?therm|quiet[-_]?therm|pmic|mtktspmic|pm[-_]|buck)/, '电源管理'],
  [/^(ddr|dram|mem)/, '内存'],
  [/^(board|case|shell|skin|back|cover)/, '外壳'],
  [/^(ambient|room|env)/, '环境'],
  [/^(usb|typec)/, 'USB'],
  [/^(cam|isp|flash[-_]?light)/, '摄像头'],
  [/^(nand|ufs|emmc|storage)/, '存储器'],
];

/** 缺名时的统一占位（原先直接上屏英文 `unknown`）。 */
const UNKNOWN_LABEL = '未知传感器';

/**
 * 取展示名。
 *
 * @param zone 端点返回的单条热区（容忍 `name` 缺失，退到 `type`）。
 * @return `raw` 只在「被翻译过」时给出，用于在名称旁补一个原始标识 —— 否则多颗同族
 *   传感器会显示成完全一样的一行（如三条「处理器」）。
 */
export function thermalZoneLabel(zone: unknown): { label: string; raw: string | null } {
  const src = (zone ?? {}) as { name?: unknown; type?: unknown };
  const raw = [src.name, src.type].map((v) => (typeof v === 'string' ? v.trim() : '')).find((v) => v !== '');
  if (!raw) return { label: UNKNOWN_LABEL, raw: null };
  const lower = raw.toLowerCase();
  if (lower === 'unknown' || lower === 'null' || lower === '-') return { label: UNKNOWN_LABEL, raw: null };
  for (const [re, label] of LABEL_RULES) {
    if (re.test(lower)) return { label, raw: label === raw ? null : raw };
  }
  return { label: raw, raw: null };
}

/**
 * 读数归一化。
 *
 * - 非数字 / 非有限值 → `null`（模板渲染 `--`，且不着色）；
 * - **负数 → `null`**：core 对「读了但内容无效」的热区原样下发 `-0.001`
 *   （-1 毫摄氏度 ÷ 1000），直接上屏就是 `-0.001°C` 这种看着像故障的假读数；
 *   core 自己（`ThermalZones.readMax`）也只在 `temp > 0` 时认它有效。
 * - `0` 保留：0°C 是合法读数，不是空值。
 *
 * 剩下的歧义 web 侧消不掉：`temp` 读不到时 core 给的是 **0.0**
 * （`ThermalZones.readAll()` 的 `milliC ?: 0L`），于是「读不到」与「真的 0°C」同形。
 * 要修得让 core 下发 `null` 或省略该字段 —— 属协议层，已记入 B3 报告，不在本批次动手。
 */
export function thermalTemp(value: unknown): number | null {
  if (typeof value !== 'number' || !Number.isFinite(value)) return null;
  if (value < 0) return null;
  return value;
}

/** 端点返回的 `zones` → 展示模型。非数组（老 core / 请求被取消）当空数组。 */
export function normalizeThermalZones(input: unknown): ThermalZone[] {
  if (!Array.isArray(input)) return [];
  return input.map((zone, index) => {
    const { label, raw } = thermalZoneLabel(zone);
    const z = (zone ?? {}) as { temperature?: unknown; temp?: unknown };
    return {
      key: `${raw ?? label}#${index}`,
      label,
      raw,
      temp: thermalTemp(z.temperature ?? z.temp),
    };
  });
}

/**
 * 三档温度色（高温红 / 偏高黄 / 正常绿）。
 *
 * 返回的是**令牌名**而不是 hex：调用方都是塞进 `:style`（CSS 变量在行内样式里可用），
 * 这样换肤与暗色档都不需要改这里。
 */
export function thermalColor(temp: number): string {
  if (temp >= THERMAL_HOT_C) return 'var(--error)';
  if (temp >= THERMAL_WARM_C) return 'var(--warning)';
  return 'var(--success)';
}

/** 摄氏温度的统一显示：一位小数 + 单位（core 下发的是毫度换算后的完整浮点）。 */
export function formatThermalTemp(temp: number): string {
  return `${temp.toFixed(1)}°C`;
}

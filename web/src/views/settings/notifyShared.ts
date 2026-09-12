/**
 * 三条推送渠道（邮件 / Webhook / 本机短信）共用的词表。
 *
 * 为什么提出来：场景 id → 中文名原来是 NotifyPanel.vue 里的私有 `MAIL_SCENES`，
 * 而 Webhook 与本机短信勾的是**同一套场景 id**。每个面板各留一份的话，
 * 加一个场景必然只改到一处 —— 表现是「邮件卡能勾、Webhook 弹窗里没有」。
 * app 侧同一时期也做了这件事（`NotifySceneLabels.kt`），两边同一个理由。
 *
 * 这些 id 必须与设备端 `NotifyScenes` 的常量逐字一致（那才是真源）：core 只做集合
 * 包含判断，写错的 id 会被 PUT 拒掉，或者（在旧版本上）静默永不触发。
 *
 * 2026-09-10：三条渠道的规则在 core 侧做成同构（每条都有最低级别 + 每日上限），
 * 所以那两个旋钮的**标签、说明与用量文案也在这里各留一份** —— 界面上长成同一组旋钮
 * 才是这次改造的目的，而三处各写一句必然分叉成三种说法。
 */

import { DAILY_LIMIT_UNLIMITED, normalizeNotifyLevels, type ChannelRules, type NotifyLevelWire } from '@/api/contract';

export interface NotifySceneOption {
  id: string;
  label: string;
}

/** id → 中文名的**唯一**映射。下面两个清单只决定「哪一条渠道能勾哪几个」。 */
const SCENE_LABELS: Record<string, string> = {
  sms: '短信正文',
  verification: '验证码提取',
  alert: '阈值告警',
  connectivity: '设备离线/上线',
  traffic80: '流量预警',
  download: '下载完成/失败',
  tunnel: '隧道异常',
  events: '设备事件',
  battery: '电池状态',
  // 不在下面两个清单里：`test` 是「发送测试」那颗按钮用的场景，带 manual 跳过场景判定，
  // 勾不勾都发，所以没有对应的勾选框。但它会出现在投递记录里，缺了这一条就显示成裸的 `test`。
  test: '手动测试',
};

/**
 * 邮件渠道可勾选的场景（顺序即展示顺序）。
 *
 * 不含 `sms`：短信正文走设备端的专用转发链路（带拦截规则过滤），勾它会一条短信两封邮件。
 *
 * `battery` 于 2026-09-10 补进来。此前它被排除，理由写的是「电池事件在设备端固定只投邮件，
 * 摆进勾选栏等于给一个不起作用的勾」——那条理由本身就不成立（设备端判的就是这份 scenes，
 * 取消勾选确实会停发），于是这里少的不是一个无效勾，而是**唯一能关掉电池邮件的开关**：
 * 存量配置里带着 `battery`，而 web 上既看不见也取消不掉。手机端那张清单一直有它。
 */
const MAIL_SCENE_IDS: readonly string[] = [
  'alert',
  'connectivity',
  'verification',
  'traffic80',
  'download',
  'tunnel',
  'events',
  'battery',
];

/** Webhook 与本机短信可勾选的场景（与手机端那两页同一套顺序）。 */
const PUSH_CHANNEL_SCENE_IDS: readonly string[] = [
  'sms',
  'verification',
  'alert',
  'connectivity',
  'traffic80',
  'download',
  'tunnel',
  'events',
  'battery',
];

const toOptions = (ids: readonly string[]): NotifySceneOption[] =>
  ids.map((id) => ({ id, label: SCENE_LABELS[id] ?? id }));

export const MAIL_SCENES: NotifySceneOption[] = toOptions(MAIL_SCENE_IDS);
export const PUSH_CHANNEL_SCENES: NotifySceneOption[] = toOptions(PUSH_CHANNEL_SCENE_IDS);

/**
 * 场景 id → 中文名，取自上面那张**同一张**表。
 *
 * 投递记录列表要显示场景名，而它的 scene 取值不限于某一条渠道的勾选清单（还有 `test`），
 * 所以按 id 单独查而不是在两个 options 数组里找。认不出的 id **原样显示**：
 * 记录里可能出现新版 core 才有的场景，显示 id 至少还能对着代码查，显示"未知"就断了线索。
 */
export const sceneLabel = (sceneId: string): string => SCENE_LABELS[sceneId] ?? sceneId;

/**
 * 场景勾选区共用的补充说明。
 *
 * 三条渠道（邮件 / Webhook / 本机短信）的场景勾选区都要说这一句，所以放这里 ——
 * 与 `SCENE_LABELS` 同一个理由：各写一份必然分叉，改一处漏两处。
 */
export const SCENE_NOTE_BATTERY = '「电池状态」通过邮件、Webhook 与本机短信发送，不在手机通知栏显示。';

/**
 * 级别的显示名：**在下发的取值域里按 wire name 查 core 给的 `label`**。
 *
 * web 这边刻意没有级别名映射表 —— 那张表原来就在这个文件里，与 app 那份措辞已经分叉
 * （app「一般（info）」vs web「一般及以上（全部通知）」），而 core 的 `NotifyLevel.label`
 * 才是唯一真源。查不到就原样显示 wire name：至少还能对着代码查，显示"未知"就断了线索。
 *
 * `levels` 为空（老固件的 config 不回这个字段）时同理退化成 wire name。
 */
export const levelLabelOf = (levels: readonly NotifyLevelWire[] | undefined, wire: string): string =>
  normalizeNotifyLevels(levels).find((l) => l.name === wire)?.label ?? wire;

/**
 * 每一档实际会放过哪些通知 —— **本地的代价提示，不是级别名**。
 *
 * 这部分留在 web：core 的 `label` 只回答"这一档叫什么"，"选它之后条数会变多/变少"
 * 是界面该替用户算的那笔账。措辞刻意不复述级别名（那会变成第二张级别表），
 * 只说这一档收得多还是收得少。
 *
 * 措辞**与渠道无关**：三条渠道用的是同一组旋钮，各写一份说明会让人以为门槛的语义也不同。
 * 只有本机短信按条计费，那句代价提示由该渠道自己在旋钮下面补一行（见 `costNote`）。
 */
const LEVEL_HINTS: Record<string, string> = {
  info: '不按级别过滤，勾选场景里的通知都会发送，条数最多。',
  warning: '只发送更要紧的那些（如断线、流量预警），日常提示不发。',
  critical: '只发送最要紧的几类，例如断网、套餐用尽、自动关闭数据网络。',
};

export const levelHint = (wire: string): string => LEVEL_HINTS[wire] ?? '';

/**
 * 级别下拉的选项：`label` 一律取服务端下发的那一份，`value` 是提交用的 wire name。
 *
 * 归一（含"老固件只回字符串数组"的版本容错）在 [normalizeNotifyLevels] 里，
 * 三处渠道共用这一个函数 —— 各写一遍就又会长出第二种显示口径。
 */
export const toLevelOptions = (levels: readonly NotifyLevelWire[]): { label: string; value: string }[] =>
  normalizeNotifyLevels(levels).map((l) => ({ label: l.label, value: l.name }));

/**
 * 设备端没给级别取值域时的说明（旧版本 core 的 config 不回 `levels`）。
 *
 * 为什么必须有这一句：web 侧刻意**不留兜底级别表**（手抄的那份在 core 加档时不会报错，
 * 只会静默少一项），代价是那种版本的设备上这个下拉是禁用的。而「看得见旋钮却选不了、
 * 又没有任何说明」比干脆没有这个旋钮更糟 —— 用户只会以为界面坏了。
 *
 * 只影响级别这一项：每日上限的取值域有本地兜底，旧版本设备上照样能改。
 */
export const LEVELS_UNAVAILABLE_NOTE = '设备端服务版本较旧，未提供可选级别，该项暂不可用；升级设备端服务后可设置。';

/**
 * 「每日上限」输入框下面那句说明。
 *
 * `min === 0` 与 `min > 0` 是两种**刻意不同**的渠道语义，必须在界面上说出来：
 * 邮件与 Webhook 允许 0（= 不限），本机短信最小 1（那条渠道花钱，"不限"是账单事故）。
 * 不写清楚的话，用户只能靠撞一次 400 校验才知道这条渠道不接受 0。
 */
export function dailyLimitHint(min: number, max: number): string {
  const range = `可填 ${min} ~ ${max} 条`;
  const zero = min === DAILY_LIMIT_UNLIMITED ? '，填 0 表示不限' : '；这条渠道不支持「不限」，最少 1 条';
  return `${range}${zero}。按设备本地日期跨天重置，达到上限后当天不再发送。`;
}

/**
 * 「今日用量」那一行的完整文案（配置界面用）。
 *
 * `quota_remaining` 在**不限**时是 `null`，此时不能显示成「剩余 0 条」—— 那与"不限"恰好相反。
 */
export function quotaUsageText(rules: Pick<ChannelRules, 'sent_today' | 'daily_limit' | 'quota_remaining'>): string {
  const sent = rules.sent_today;
  if (rules.daily_limit === DAILY_LIMIT_UNLIMITED) return `已发送 ${sent} 条（未设上限）`;
  const remaining = rules.quota_remaining ?? Math.max(0, rules.daily_limit - sent);
  return `已发送 ${sent} 条，剩余 ${remaining} 条（上限 ${rules.daily_limit} 条）`;
}

/**
 * 渠道清单那一行的短文案。三行共用，写成两种说法会让人以为统计口径也不同。
 *
 * 数字读不出整数（字段缺失、类型不符）时显示 `--`：**不能替它编一个 0** ——
 * 0 在这里恰好是两种相反语义的合法取值（`daily_limit = 0` 是"不限"，`quota_remaining = 0`
 * 是"已用尽"），猜哪一个都可能把事实说反。显示 `--` 至少说的是"没读到"。
 */
export function quotaShortText(rules: Pick<ChannelRules, 'sent_today' | 'daily_limit' | 'quota_remaining'>): string {
  if (!Number.isInteger(rules.daily_limit) || !Number.isInteger(rules.sent_today)) return '--';
  if (rules.daily_limit === DAILY_LIMIT_UNLIMITED) return `今日 ${rules.sent_today} 条（不限）`;
  const remaining = rules.quota_remaining ?? Math.max(0, rules.daily_limit - rules.sent_today);
  return `今日 ${rules.sent_today}/${rules.daily_limit} 条（剩 ${remaining}）`;
}

/**
 * 两个旋钮的标签。三处引同一份常量 —— 同一个开关在三个渠道页上叫两种名字，
 * 用户就会以为那是两件事。
 */
export const MIN_LEVEL_LABEL = '最低级别';
export const DAILY_LIMIT_LABEL = '每日上限';
export const QUOTA_USAGE_LABEL = '今日用量';

// ── CRITICAL 兜底（core `NotificationConfig.critical_override_enabled`，默认开启）──

export const CRITICAL_OVERRIDE_LABEL = '严重事件兜底';

/** 开关本身的副文案：只说它是什么，穿透清单在下面两条里逐项写。 */
export const CRITICAL_OVERRIDE_DESC = '级别为「严重」的通知（如断网、套餐用尽、自动关闭数据网络）按下面的规则放行。';

/** 承诺的正面：穿透哪三档。逐条与 core 的判定一致，不多说也不少说。 */
export const CRITICAL_OVERRIDE_PENETRATES = '会穿透：免打扰时段、该渠道未勾选的触发场景、该渠道的最低级别门槛。';

/** 承诺的反面：这三档任何时候都拦得住它。少写一条就是给用户一个做不到的承诺。 */
export const CRITICAL_OVERRIDE_BLOCKED = '不会穿透：通知总开关、渠道配置不完整、当日配额已用尽。';

/**
 * 免打扰 / 场景勾选旁边的一句提示。
 *
 * 三条渠道的配置界面各放一次：用户是在这两处做"别来打扰我"的决定，
 * 而「严重事件会照样发」这件事只在另一个页面写着的话，等于藏了一条规则。
 */
export const CRITICAL_OVERRIDE_NOTE =
  '「严重事件兜底」开启时，严重级别的通知仍会穿透免打扰时段与未勾选的场景发出；通知总开关关闭、配置不完整或当日配额用尽时仍然不发。';

/** 「最低级别」旁边的短提示：那道门槛同样会被严重事件穿透。 */
export const CRITICAL_OVERRIDE_LEVEL_NOTE = '严重级别的通知不受本门槛限制（见「严重事件兜底」）。';

/**
 * 通知相关时间戳的显示格式（投递记录、最近测试结果）。
 *
 * 两处都是"设备端记下的那一刻"，必须是同一种写法：`hour12: false` 少写一次就会出现
 * 同一条记录在两个界面上一个 24 小时制、一个 上午/下午 —— 用户会以为那是两个不同的时间。
 */
export const formatNotifyTime = (ts: number): string => new Date(ts).toLocaleString('zh-CN', { hour12: false });

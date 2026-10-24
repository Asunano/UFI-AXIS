/**
 * 赞助名单的数据层（2026-10-14 新建）。
 *
 * ## 用途
 * 「赞助与感谢」页（`/sponsors`）的**保底 + 更新**两层数据：
 * · 保底：构建期用 `define` 内联进来的仓库根 `sponsors.json`（见 `vite.config.ts`），
 *   页面首帧就有数据，0 延迟、不白屏；
 * · 更新：`GET api/sponsors`，成功**且校验通过**才替换。
 * core 不可达就停在保底那份 —— 这正是"保底"这两个字的意义。
 *
 * ## 两层都要校验，而且**任何一条不合规就整份丢弃**
 * 内置那份与 core 回的那份都可能坏：内置的来自构建期读文件（文件可以被改坏），
 * 远端的来自 core 那条 12 小时一次的后台刷新（从仓库 main 分支拉，**不经过编译期校验**）。
 * 校验规则（与 `sponsors.json` 的 `_note` 同源，三端共用一套）：
 *   逐条：**恰好**有 `name`（非空、trim 后 ≤32）或 `anonymous: true` 之一；
 *        `at` 必填且形如 `YYYY-MM` 或 `YYYY-MM-DD`；
 *   整份：`version` 等于 [SPONSOR_VERSION]。
 * 不合规整份丢弃而不是"跳过坏的那条"：名单里少一个人比多一个不认识的人难解释得多，
 * 而"渲染出一半"最难解释 —— 用户看到的是一份看起来完整、实际缺人的名单。
 *
 * ## 为什么状态放在模块作用域而不是组件里
 * 与 `views/diagnostics/useDiagnosticRun.ts` 同一条决策：`/sponsors` 是独立路由，
 * 用户从「关于」点进来、看完就退回去。状态在模块里，来回切页不会重新发一次请求，
 * 也不会闪一下空名单。只有一个消费者，所以**不建 Pinia store**（对照 `stores/update.ts`：
 * 那个进 store 是因为**服务端**持有作业状态，这里的真相在 core，前端存一份必然过期）。
 *
 * ## 否决过的替代方案
 * · **把仓库根 `sponsors.json` 直接 `import` 进页面**（不经 vite.config）：路径要写成
 *   `../../../../sponsors.json`（跨出 `web/` 的 root），而且读坏了会让整个构建失败 ——
 *   一份名单不该有这种权力。见 `vite.config.ts` 的 `readBundledSponsors` 注释。
 * · **保底那份也走一次 fetch**：那就不叫保底了（首屏必然白一下），
 *   而且 core 不可达时页面会彻底空掉 —— 恰恰是保底要防的场景。
 * · **用 `version` 做前向兼容的唯一闸门**：`GET api/sponsors` 的契约里**没有** `version`
 *   字段（见 `contract.ts` 的 sponsors 组），把"没带 version"也判成不合规会让更新层
 *   永久失效。所以远端那份的判据是"带了就必须等于支持值"，逐条校验仍是硬闸门。
 */
import { computed, reactive } from 'vue';
import { Endpoints } from '@/api/contract';
import { getApiClient } from '@/composables/useApi';
import { toApiErrorMessage } from '@/composables/utils';

/**
 * 当前支持的名单格式版本。
 *
 * 与仓库根 `sponsors.json` 的 `version` 同一个数。**它必须与 core 侧一致** ——
 * core 那份拉下来之后不做格式转换，只做透传。
 */
export const SPONSOR_VERSION = 1;

/** 名字长度上限（trim 之后按字符数算）。超长的名字在窄屏上会把整行撑成两三行。 */
const NAME_MAX = 32;

/**
 * `at` 的形状：`YYYY-MM` 或 `YYYY-MM-DD`。
 *
 * 月份/日期都做范围校验（`0[1-9]|1[0-2]`、`0[1-9]|[12]\d|3[01]`），
 * 而不是只写 `\d{2}` —— 后者会让 `2026-99` 这种明显写错的值通过，
 * 而 `at` 是排序键，一个错值会把整份名单的顺序带偏。
 * 刻意**不做真实日历校验**（`2026-02-31` 会通过）：它只用来排序，不影响正确性，
 * 而把日期库搬进来只为挡一个手写 JSON 里的笔误，代价不对等。
 */
const AT_PATTERN = /^\d{4}-(0[1-9]|1[0-2])(-(0[1-9]|[12]\d|3[01]))?$/;

/** 一条合规的赞助记录。`anonymous` 为真时 `name` 恒为空串（两者互斥）。 */
export interface SponsorEntry {
  name: string;
  anonymous: boolean;
  /** `YYYY-MM` 或 `YYYY-MM-DD`。**只用于排序，不在界面上显示**（顺序公开、时间不公开）。 */
  at: string;
}

/** 一份合规的名单。 */
export interface SponsorFile {
  version: number;
  updatedAt: string;
  sponsors: SponsorEntry[];
}

/** 这份名单是从哪来的。`bundled` = 构建期内联的那份，`remote` = core 回的那份。 */
export type SponsorSource = 'bundled' | 'remote';

function str(v: unknown): string {
  return typeof v === 'string' ? v.trim() : '';
}

/**
 * 校验一条记录。**"恰好其一"是硬要求**：既写名字又标匿名、或两样都没有，都不合规。
 */
function parseEntry(raw: unknown): SponsorEntry | null {
  if (!raw || typeof raw !== 'object') return null;
  const o = raw as Record<string, unknown>;
  const at = str(o.at);
  if (!AT_PATTERN.test(at)) return null;
  const anonymous = o.anonymous === true;
  const name = str(o.name);
  if (anonymous && name) return null;
  if (!anonymous && !name) return null;
  if (name.length > NAME_MAX) return null;
  return { name: anonymous ? '' : name, anonymous, at };
}

/** 校验整份 `sponsors` 数组。**一条坏就整份 null**（见文件头）。 */
function parseEntries(raw: unknown): SponsorEntry[] | null {
  if (!Array.isArray(raw)) return null;
  const out: SponsorEntry[] = [];
  for (const item of raw) {
    const entry = parseEntry(item);
    if (!entry) return null;
    out.push(entry);
  }
  return out;
}

/** 校验构建期注入的那份。`version` **必须**存在且等于 [SPONSOR_VERSION]。 */
export function parseBundledSponsors(raw: unknown): SponsorFile | null {
  if (!raw || typeof raw !== 'object') return null;
  const o = raw as Record<string, unknown>;
  if (o.version !== SPONSOR_VERSION) return null;
  const sponsors = parseEntries(o.sponsors);
  if (!sponsors) return null;
  return { version: SPONSOR_VERSION, updatedAt: str(o.updatedAt), sponsors };
}

/**
 * 校验 `GET api/sponsors` 的响应：`{ source, updatedAt, sponsors }`。
 *
 * `version` 在契约里没有，所以判据是"带了就必须等于支持值"（理由见文件头）。
 */
export function parseRemoteSponsors(raw: unknown): { source: SponsorSource; file: SponsorFile } | null {
  if (!raw || typeof raw !== 'object') return null;
  const o = raw as Record<string, unknown>;
  if (o.version !== undefined && o.version !== SPONSOR_VERSION) return null;
  const sponsors = parseEntries(o.sponsors);
  if (!sponsors) return null;
  return {
    source: o.source === 'remote' ? 'remote' : 'bundled',
    file: { version: SPONSOR_VERSION, updatedAt: str(o.updatedAt), sponsors },
  };
}

/**
 * 排序：`at` 倒序（新赞助在前）→ 同级按 `name` 升序。
 *
 * ★ **同级按名字排不是装饰**：远端 JSON 的顺序随时可能变（core 那条后台刷新直接从
 * 仓库 main 分支拉文件，谁在文件里挪一行，名单在界面上就会跳一下）。
 * 加了第二排序键之后，同一批人的相对顺序在任何输入顺序下都相同。
 *
 * 匿名条目**不在这里**：它们只用于计数（见 [anonymousCount]），
 * 逐条列出就等于把"匿名"这个选择作废了。
 */
export function sortNamedSponsors(list: SponsorEntry[]): SponsorEntry[] {
  return list
    .filter((s) => !s.anonymous)
    .sort((a, b) => {
      if (a.at !== b.at) return a.at < b.at ? 1 : -1;
      if (a.name === b.name) return 0;
      // 中文名按拼音比 `<`（码位序）更符合"升序"的直觉；同一浏览器内结果确定。
      return a.name.localeCompare(b.name, 'zh-Hans-CN');
    });
}

/** 构建期内联的那份。`null` = 构建时没取到 / 格式与当前版本对不上。 */
const bundled = parseBundledSponsors(__SPONSORS_BUNDLED__);

/**
 * 页面读的唯一状态。
 *
 * 初值直接来自保底那份 ⇒ **首帧就有名单**，`loadSponsors()` 只是更新层。
 * `note` 非空表示"更新层这次没接上或那份数据坏了"，页面上会以小字说明 ——
 * 名单没更新时必须让人知道为什么，否则用户会以为名单就是这样的。
 */
export const sponsorsState = reactive({
  source: 'bundled' as SponsorSource,
  updatedAt: bundled?.updatedAt ?? '',
  sponsors: bundled?.sponsors ?? [],
  loading: false,
  note: bundled ? '' : '内置名单不可用（构建期没取到 sponsors.json，或它的格式与当前版本对不上）',
});

/** 要逐条显示的人（匿名条目已剔除，且已按 at 倒序 → name 升序排好）。 */
export const namedSponsors = computed(() => sortNamedSponsors(sponsorsState.sponsors));

/** 匿名赞助者数量。客户端自己数 —— core 只回名单，不回这个数。 */
export const anonymousCount = computed(() => sponsorsState.sponsors.filter((s) => s.anonymous).length);

/**
 * 拉一次更新层。**不抛异常**：core 不可达是预期情况之一（设备关机 / 不在同一网络），
 * 那时页面就停在保底那份，提示一句原因即可。
 */
export async function loadSponsors(): Promise<void> {
  if (sponsorsState.loading) return;
  sponsorsState.loading = true;
  try {
    const { data } = await getApiClient().get(Endpoints.sponsors.root, { timeout: 10_000 });
    const parsed = parseRemoteSponsors(data);
    if (!parsed) {
      sponsorsState.note = 'core 回的名单不合规（字段缺失或格式不对），已继续显示内置那份';
      return;
    }
    sponsorsState.source = parsed.source;
    sponsorsState.updatedAt = parsed.file.updatedAt;
    sponsorsState.sponsors = parsed.file.sponsors;
    sponsorsState.note = '';
  } catch (e) {
    sponsorsState.note = `core 暂时取不到名单，下面显示的是内置那份（${toApiErrorMessage(e, '请求失败')}）`;
  } finally {
    sponsorsState.loading = false;
  }
}

/**
 * UI 基线门禁：只卡增量，不回填。
 *
 * 对齐 Android 侧的 `checkLiteralBaseline`（config/literal-baseline.properties，决策 D4）——
 * 存量该有多少就多少，写进基线文件；**新增**一律报错。这样既不用先做一轮大扫除，
 * 又能保证「以后每次 review 都能看见有人绕过令牌」。
 *
 * 两个指标各对应一条已经踩过的坑：
 *
 * 1. `scopedColorLiterals` —— SFC 的 `<style>` 里写死的 hex / rgb() / rgba()。
 *    这是 §W4 清理的那一类：写死值不跟暗色模式走，也不跟换肤走。
 *    典型症状是浅底深字在暗色下压在深色卡片上，看着像没生效。
 *
 * 2. `bareSwitches` —— 直接用 `<n-switch>` 而不是 `ToggleRow`。
 *    §W2 的结论是「三种版式各有其位」，所以这里不是要禁掉裸开关，
 *    只是不希望它继续无声增长：真要加，改基线数字并在 PR 里说明是哪一种版式。
 *
 * 用法：
 *   node scripts/check-ui-baseline.mjs          # 校验（超基线退出码 1）
 *   node scripts/check-ui-baseline.mjs --update # 把当前实测值写回基线
 *
 * 基线降低时不报错，只提示可以收紧 —— 与 Android 侧同口径。
 *
 * ════════════════ 2026-10-14 修三类误报 + 补就地豁免 ════════════════
 *
 * ## 背景：基线从 13/41 漂到 58/49 而没人发现
 * 两个原因，都不是"有人故意绕过令牌"：
 *
 * ① **这个门禁从来没在 CI 里跑过**。它挂在 `npm run lint` 的最后一段，而所有
 *    workflow 只跑 `npm ci` + `npm run build`（`build.yml` / `release.yml` /
 *    `web-release.yml` 都是）。于是自 2026-09-19 基线写下之后，每一次 UI 提交
 *    都只是在**记账**，账从来没被核对过。已补进 `build.yml` 的 web 校验作业。
 *
 * ② **扫描器把三类根本不是"写死取值"的东西也算了进去**，导致真实违规被淹没：
 *
 *    · `var(--token, #fallback)` —— **令牌缺失时的兜底**。它的语义与"绕过令牌"
 *      正好相反：有令牌时用的是令牌。旧扫描器只看字面量，把 `var(--card-bg, #fff)`
 *      和 `background: #fff` 一视同仁，一口气误报 21 处。
 *    · **CSS 注释里的示例色值**。注释里写 `暗色模式下二维码卡片是 #1a1a20` 是
 *      *解释*，不是取值。
 *    · **`mask` / `-webkit-mask` 里的颜色**。遮罩只取 alpha 通道，写 `#000` 还是
 *      `#fff` 渲染结果完全一样（这是 CSS Masking 的定义），拿它当"颜色违规"没有意义。
 *
 * ## 补的机制：就地豁免
 * 旧版只有**文件级** `exempt` 列表。于是"这一处是刻意的、理由如下"根本没有地方可写 ——
 * 本仓作者们的实际做法是在声明上方写一段中文注释解释（`QrModal.vue`「二维码底色必须纯白，
 * 接 --card-bg 会让暗色下扫不出来」、`LogsPanel.vue`「等真要收时得先补一个 --neutral-light 档」、
 * `WifiInfoCard.vue`「与 QrModal 同一条豁免」…），**而扫描器读不懂注释**。
 * 结果是：理由写了，账照记，数字照涨。
 *
 * 现在可以在声明**本行、紧邻上一行，或所在规则块的开头**写：
 *
 *     .nav-btn {
 *       /* ui-baseline-allow: 灯箱控件压在图片上，两种主题下都必须是深底白字 *\/
 *       background: rgba(0, 0, 0, 0.45);
 *       color: #fff;              ← 同一块内的后续声明一并覆盖
 *     }
 *
 * 规则（刻意收紧，免得变成万能挡箭牌）：
 *   · 理由不能为空且至少 4 个字；
 *   · 覆盖范围到**该规则块的 `}` 为止**，出了块立刻失效，不能整文件豁免；
 *   · 覆盖了多少处会在输出里报出来，review 时看得见。
 * 这与 `eslint-disable-next-line` 是同一个信任模型：允许例外，但要求写在出事的地方。
 */
import { readFileSync, writeFileSync, readdirSync, statSync } from 'node:fs';
import { join, relative, sep } from 'node:path';
import { fileURLToPath } from 'node:url';

const webRoot = join(fileURLToPath(new URL('.', import.meta.url)), '..');
const srcDir = join(webRoot, 'src');
const baselinePath = join(webRoot, 'config', 'ui-baseline.json');

const baseline = JSON.parse(readFileSync(baselinePath, 'utf8'));
/** 路径后缀匹配（用 / 分隔，跨平台前先归一化） */
const exempt = baseline.exempt ?? [];

function walk(dir) {
  const out = [];
  for (const name of readdirSync(dir)) {
    const full = join(dir, name);
    if (statSync(full).isDirectory()) out.push(...walk(full));
    else if (name.endsWith('.vue')) out.push(full);
  }
  return out;
}

function isExempt(relPath) {
  return exempt.some((suffix) => relPath === suffix || relPath.endsWith(suffix));
}

// hex（#abc / #aabbcc / #aabbccdd）与 rgb()/rgba()。`#` 后必须紧跟 3/4/6/8 位十六进制，
// 避免把 `#app` 这类选择器算进来。
const COLOR_LITERAL = /#(?:[0-9a-fA-F]{8}|[0-9a-fA-F]{6}|[0-9a-fA-F]{4}|[0-9a-fA-F]{3})\b|\brgba?\(/g;
const STYLE_BLOCK = /<style[^>]*>([\s\S]*?)<\/style>/g;
const SWITCH_TAG = /<n-switch\b/g;

/** 就地豁免标记。CSS 块注释与模板注释两种收尾都认。 */
const ALLOW_MARK = /ui-baseline-allow\s*:\s*([^\n]*?)\s*(?:\*\/|-->)/;
const MIN_REASON = 4;

function allowReason(line) {
  const m = ALLOW_MARK.exec(line);
  if (!m) return null;
  const reason = (m[1] ?? '').trim();
  return reason.length >= MIN_REASON ? reason : null;
}

/** 把 CSS 注释内容换成等长空白。**保留换行**，这样行号仍然对得上原文件。 */
function blankComments(css) {
  return css.replace(/\/\*[\s\S]*?\*\//g, (m) => m.replace(/[^\n]/g, ' '));
}

/**
 * `var(--token, <fallback>)` 整体。fallback 只在令牌未定义时生效 —— 它是兜底，
 * 不是"绕过令牌"，所以整段挖掉再扫。允许 fallback 里嵌一层括号（`rgba(...)`）。
 */
const VAR_FALLBACK = /var\(\s*--[A-Za-z0-9_-]+\s*,[^()]*(?:\([^()]*\)[^()]*)*\)/g;

/** `mask` / `-webkit-mask` 声明：只用到 alpha 通道，颜色写什么都一样。 */
const MASK_DECL = /^[^:{}]*mask[^:{}]*:/i;

const perFile = [];
let colorTotal = 0;
let switchTotal = 0;
/** 就地豁免掉的处数。报出来是为了让 review 看得见"这轮又放行了多少"。 */
let allowedColors = 0;
let allowedSwitches = 0;

for (const file of walk(srcDir)) {
  const relPath = relative(webRoot, file).split(sep).join('/');
  if (isExempt(relPath)) continue;
  const text = readFileSync(file, 'utf8');

  let colors = 0;
  for (const m of text.matchAll(STYLE_BLOCK)) {
    const raw = m[1];
    // 行号对齐靠这两步都**不改变行数**：注释换空白、var 兜底换占位符
    const origLines = raw.split('\n');
    const cleanLines = blankComments(raw).replace(VAR_FALLBACK, 'var(--x)').split('\n');
    /**
     * 就地豁免的开启状态。标记一旦出现就一直生效到**当前规则块的 `}`** ——
     * CSS 的一条规则往往有多个声明（`.nav-btn` 的深底 + 白字），逐行写理由会写两遍，
     * 而"出了这个块立刻失效"保证了它不会变成整文件豁免。
     */
    let allowOpen = false;
    for (let i = 0; i < cleanLines.length; i++) {
      const line = cleanLines[i];
      // 标记只在**原始**行上找 —— 注释已经被抹成空白了
      if (allowReason(origLines[i]) || (i > 0 && allowReason(origLines[i - 1]))) allowOpen = true;
      if (!MASK_DECL.test(line)) {
        const found = line.match(COLOR_LITERAL);
        if (found) {
          if (allowOpen) allowedColors += found.length;
          else colors += found.length;
        }
      }
      if (line.includes('}')) allowOpen = false;
    }
  }

  let switches = 0;
  const textLines = text.split('\n');
  for (let i = 0; i < textLines.length; i++) {
    const hits = (textLines[i].match(SWITCH_TAG) ?? []).length;
    if (!hits) continue;
    const marked = allowReason(textLines[i]) || (i > 0 ? allowReason(textLines[i - 1]) : null);
    if (marked) {
      allowedSwitches += hits;
      continue;
    }
    switches += hits;
  }

  colorTotal += colors;
  switchTotal += switches;
  if (colors || switches) perFile.push({ relPath, colors, switches });
}

const measured = { scopedColorLiterals: colorTotal, bareSwitches: switchTotal };

if (process.argv.includes('--update')) {
  writeFileSync(baselinePath, JSON.stringify({ ...baseline, ...measured }, null, 2) + '\n', 'utf8');
  console.log('ui-baseline.json 已更新：', measured);
  process.exit(0);
}

const checks = [
  ['scopedColorLiterals', 'scoped CSS 里写死的颜色', 'colors'],
  ['bareSwitches', '裸 <n-switch>（未走 ToggleRow）', 'switches'],
];

let failed = false;
for (const [key, label, field] of checks) {
  const limit = baseline[key];
  const now = measured[key];
  if (now > limit) {
    failed = true;
    console.error(`✗ ${label}：${now} > 基线 ${limit}（新增 ${now - limit}）`);
    const top = perFile
      .filter((f) => f[field] > 0)
      .sort((a, b) => b[field] - a[field])
      .slice(0, 10);
    for (const f of top) console.error(`    ${f[field]}  ${f.relPath}`);
  } else if (now < limit) {
    console.log(`✓ ${label}：${now}（基线 ${limit}，可以收紧到 ${now}）`);
  } else {
    console.log(`✓ ${label}：${now}`);
  }
}

// 豁免数照报：它不是"通过"，是"这轮放行了多少处"，review 时要看见。
if (allowedColors || allowedSwitches) {
  console.log(`ℹ 就地豁免（ui-baseline-allow）：颜色 ${allowedColors} 处 / 裸开关 ${allowedSwitches} 处`);
}

if (failed) {
  console.error('\n新增了写死取值。三选一：');
  console.error('  1. 改用令牌 / ToggleRow；');
  console.error('  2. 确认这一处是刻意的，就在声明本行或上一行写');
  console.error('     /* ui-baseline-allow: 理由 */  —— 理由会被计入 review；');
  console.error('  3. 用 `node scripts/check-ui-baseline.mjs --update` 抬高基线并在 PR 里说明。');
  process.exit(1);
}

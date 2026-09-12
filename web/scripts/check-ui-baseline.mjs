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

const perFile = [];
let colorTotal = 0;
let switchTotal = 0;

for (const file of walk(srcDir)) {
  const relPath = relative(webRoot, file).split(sep).join('/');
  if (isExempt(relPath)) continue;
  const text = readFileSync(file, 'utf8');

  let colors = 0;
  for (const m of text.matchAll(STYLE_BLOCK)) {
    colors += (m[1].match(COLOR_LITERAL) ?? []).length;
  }
  const switches = (text.match(SWITCH_TAG) ?? []).length;

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

if (failed) {
  console.error('\n新增了写死取值。要么改用令牌 / ToggleRow，要么在 PR 里说明理由后');
  console.error('用 `node scripts/check-ui-baseline.mjs --update` 抬高基线。');
  process.exit(1);
}

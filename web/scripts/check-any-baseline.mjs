/**
 * 类型逃逸（`any`）基线门禁：只卡增量，不回填。
 *
 * 与 `check-ui-baseline.mjs`（UI 令牌）和 Android 侧的 `checkLiteralBaseline` 同一套思路：
 * 存量该有多少就多少，写进基线文件；**新增**一律报错。这样既不用先做一轮大扫除
 * （308 处 `any` 里大部分是有理由的：axios 响应、设备原始 JSON、第三方类型缺口），
 * 又能在 review 里看见"这次又绕过类型系统了"。
 *
 * ## 统计口径（刻意窄，避免把合理的写法算成债）
 *
 * 计入四种**显式的类型逃逸**：
 *   · `: any`                —— 注解
 *   · `as any`               —— 断言
 *   · `<any>`                —— 泛型实参（老式写法）
 *   · `any[]`                —— 数组元素
 *
 * 不计入：
 *   · `Record<string, any>` / `Record<string, unknown>` —— 前者是"键值表"的既有约定，
 *     换成 unknown 会在每个读取点强制收窄，属于另一个量级的改造（见 types/index.ts 的讨论）；
 *   · `*.spec.ts` —— 测试里为了构造畸形输入用 any 是合理的；
 *   · `src/components.d.ts` —— unplugin 生成物；
 *   · 注释与字符串 —— 本仓注释里大量出现 "不要写 as any" 这类说明，不剥掉会把文档算成债。
 *
 * ## 为什么不做成 eslint 规则
 * `@typescript-eslint/no-explicit-any` 只能整体开/关，没有"基线"概念；本仓的
 * `eslint.config.js` 现在是 `off`。做成计数门禁才能既保留存量、又拦住新增。
 *
 * 用法：
 *   node scripts/check-any-baseline.mjs          # 校验（超基线退出码 1）
 *   node scripts/check-any-baseline.mjs --update # 把当前实测值写回基线
 *
 * 基线降低时不报错，只提示可以收紧 —— 与 UI 基线同口径。
 */
import { readFileSync, writeFileSync, readdirSync, statSync } from 'node:fs';
import { join, relative, sep } from 'node:path';
import { fileURLToPath } from 'node:url';

const webRoot = join(fileURLToPath(new URL('.', import.meta.url)), '..');
const srcDir = join(webRoot, 'src');
const baselinePath = join(webRoot, 'config', 'any-baseline.json');
const baseline = JSON.parse(readFileSync(baselinePath, 'utf8'));
const exempt = baseline.exempt ?? [];

function walk(dir) {
  const out = [];
  for (const name of readdirSync(dir)) {
    const full = join(dir, name);
    if (statSync(full).isDirectory()) out.push(...walk(full));
    else if ((name.endsWith('.ts') || name.endsWith('.vue')) && !name.endsWith('.spec.ts')) out.push(full);
  }
  return out;
}

function isExempt(relPath) {
  return exempt.some((suffix) => relPath === suffix || relPath.endsWith(suffix));
}

/**
 * 把注释内容换成等长空白，**保留换行**（行号要对得上原文件）。
 *
 * `//` 的处理刻意带一个启发式：前一个字符是 `:` 时不当注释 —— 否则
 * `'http://192.168.0.1:8088'` 这类字符串会被从中间截断，后面的代码整段算漏。
 */
function stripComments(text) {
  return text
    .replace(/\/\*[\s\S]*?\*\//g, (m) => m.replace(/[^\n]/g, ' '))
    .split('\n')
    .map((line) => {
      const i = line.indexOf('//');
      if (i < 0) return line;
      if (i > 0 && line[i - 1] === ':') return line; // http:// / https://
      return line.slice(0, i) + ' '.repeat(line.length - i);
    })
    .join('\n');
}

/** 四种显式逃逸。注意 `Record<string, any>` 不会命中 `: any`（中间隔着 `,`）。 */
const ANY_PATTERN = /(?::\s*any\b|\bas\s+any\b|<any>|\bany\[\])/g;

let total = 0;
const perFile = [];

for (const file of walk(srcDir)) {
  const relPath = relative(webRoot, file).split(sep).join('/');
  if (isExempt(relPath) || relPath.endsWith('components.d.ts')) continue;
  const text = stripComments(readFileSync(file, 'utf8'));
  const hits = text.match(ANY_PATTERN)?.length ?? 0;
  if (hits) {
    total += hits;
    perFile.push({ relPath, hits });
  }
}

if (process.argv.includes('--update')) {
  writeFileSync(baselinePath, JSON.stringify({ ...baseline, anyOccurrences: total }, null, 2) + '\n', 'utf8');
  console.log('any-baseline.json 已更新：', { anyOccurrences: total });
  process.exit(0);
}

const limit = baseline.anyOccurrences;
if (total > limit) {
  console.error(`✗ 类型逃逸（any）：${total} > 基线 ${limit}（新增 ${total - limit}）`);
  for (const f of perFile.sort((a, b) => b.hits - a.hits).slice(0, 10)) {
    console.error(`    ${String(f.hits).padStart(3)}  ${f.relPath}`);
  }
  console.error('\n新增了 any。三选一：');
  console.error('  1. 换成具体类型 / unknown + 收窄（优先）；');
  console.error('  2. 确认这一处确实无法建模，在注释里写明理由，然后');
  console.error('     `node scripts/check-any-baseline.mjs --update` 抬高基线并在 PR 里说明；');
  console.error('  3. 用 `as unknown as T` 时也请三思 —— 它只是把 any 换了个名字。');
  process.exit(1);
} else if (total < limit) {
  console.log(`✓ 类型逃逸（any）：${total}（基线 ${limit}，可以收紧到 ${total}）`);
} else {
  console.log(`✓ 类型逃逸（any）：${total}`);
}

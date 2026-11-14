/**
 * 断点门禁（2026-10-30）：只允许正式档位，存量档案不得新增。
 *
 * ## 背景
 * `main.css` 顶部写着「全站只用 768 / 1024 / max-height:720 三档」，实际扫出来是
 * 十档：77×768、13×1024，以及 380 / 420 / 480 / 660 / 720 / 900 / 960 / 1080 / 1180。
 * 约定死掉不是因为有人故意违反，而是**没有任何东西在核对它** —— 与 UI 基线当初
 * 「写下来就没人看」是同一个失效模式（那个门禁后来补进 CI 才活过来）。
 *
 * ## 本次不改视觉，只冻结新增
 * 每一档背后都是一个真实的排版临界值（顶栏在 1180 才开始塞不下、登录页在 960 才切单列），
 * 贸然把 1180 合并到 1024 会让 1025–1180 这一段的顶栏重新挤在一起。真要收敛必须逐页
 * 目视，不属于"顺手统一"。所以这里做的是：
 *   · 把两档正式化：**480**（小手机）、**1180**（顶栏密度），加上原有的 768 / 1024；
 *   · 其余七档列为 `LEGACY`，允许存在但**只能减少**：删掉一档会提示可以收紧，
 *     新增任何一档（包括新的数值）直接失败。
 *
 * ## 为什么只查 max-width / max-height
 * `min-width` 全站只有一处组合写法（`769px and 1024px`），它表达的是"区间"而不是档位；
 * `prefers-reduced-motion` / `hover: none` 属于能力查询，与视口宽度无关，一并忽略。
 *
 * 用法：node scripts/check-breakpoints.mjs
 */
import { readFileSync, readdirSync, statSync } from 'node:fs';
import { join, relative, sep } from 'node:path';
import { fileURLToPath } from 'node:url';

const webRoot = join(fileURLToPath(new URL('.', import.meta.url)), '..');
const srcDir = join(webRoot, 'src');

/** 正式档位（max-width） */
const OFFICIAL_WIDTH = new Set(['480', '768', '1024', '1180']);
/** 正式档位（max-height）：矮视口压缩，笔记本 768p / 分屏 */
const OFFICIAL_HEIGHT = new Set(['720']);

/**
 * 存量档案：允许存在，但**不得新增**。每删掉一处，脚本会提示可以把它从这张表里移走。
 * 想新增一档，先把这一档收敛到上面四个正式值，或在 PR 里讨论后改这张表。
 */
const LEGACY_WIDTH = new Set(['380', '420', '660', '720', '900', '960', '1080']);

/**
 * 卡片级临界值：**只在本页/本卡片内部生效**的窄断点，与视口档位不是一回事。
 *
 * 为什么单独一组而不是塞进 LEGACY：LEGACY 的语义是"历史遗留、只减不增"，而这几个是
 * 2026-11 这一批页面按内容宽度算出来的、刻意选的临界值（见每条的理由）。混在一起会让
 * "允许存在但不得新增"这句话失去意义。
 *
 * 仍然要登记：**新增任何一档（包括这里没有的数）都会失败**，逼人要么收敛到正式档位，
 * 要么在这里写清为什么正式档位表达不了它。
 */
const CARD_WIDTH = new Map([
  // 今日/本月/剩余三格在 400 以下放不下（"620M↓ / 12M↑" 是最长的一格），今日那格独占一行
  ['400', 'TrafficUsageCard 三格：400 以下今日用量那格要独占一行'],
  // 网络卡的参数列 + 右侧控制列在 600 以下并排会把数值挤到换行，改为上下堆叠
  ['600', 'NetworkInfoCard：600 以下右侧控制列改为堆叠'],
  // 关于页的 hero 区在 640 以下要收窄内边距、缩小标题字号
  ['640', 'AboutPanel：640 以下 hero 收窄内边距'],
  // 设备页控制格：700 以下从两列并成单列（长描述会撑破内容区）
  ['700', 'DeviceView 控制格：700 以下两列并单列'],
  // 设备页控制格：1060 以下从三列并成两列
  ['1060', 'DeviceView 控制格：1060 以下三列并两列'],
]);

function walk(dir) {
  const out = [];
  for (const name of readdirSync(dir)) {
    const full = join(dir, name);
    if (statSync(full).isDirectory()) out.push(...walk(full));
    else if (name.endsWith('.vue') || name.endsWith('.css')) out.push(full);
  }
  return out;
}

/** 只取 `<style>` 块（.vue）或整个文件（.css）—— 模板/注释里的 `@media` 举例不算。 */
function styleText(file, text) {
  if (!file.endsWith('.vue')) return text;
  return [...text.matchAll(/<style[^>]*>([\s\S]*?)<\/style>/g)].map((m) => m[1]).join('\n');
}

/** 去掉 CSS 注释，保留行数（行号要能对回原文件） */
function blankComments(css) {
  return css.replace(/\/\*[\s\S]*?\*\//g, (m) => m.replace(/[^\n]/g, ' '));
}

const MAX_W = /max-width:\s*(\d+)px/g;
const MAX_H = /max-height:\s*(\d+)px/g;

const violations = [];
const legacyUsed = new Set();

for (const file of walk(srcDir)) {
  const relPath = relative(webRoot, file).split(sep).join('/');
  const css = blankComments(styleText(file, readFileSync(file, 'utf8')));
  const lines = css.split('\n');

  for (let i = 0; i < lines.length; i++) {
    const line = lines[i];
    if (!line.includes('@media')) continue;
    // 条件可能跨行（本仓有 `(min-width: 769px) and\n (max-width: 1024px)` 这种），
    // 所以先把这一段拼起来再匹配。
    const condition = line + (lines[i + 1]?.includes('{') ? '' : (lines[i + 1] ?? ''));

    for (const m of condition.matchAll(MAX_W)) {
      const v = m[1];
      if (OFFICIAL_WIDTH.has(v)) continue;
      if (LEGACY_WIDTH.has(v)) {
        legacyUsed.add(`w${v}`);
        continue;
      }
      if (CARD_WIDTH.has(v)) continue;
      violations.push({ relPath, line: i + 1, kind: 'max-width', value: v });
    }
    for (const m of condition.matchAll(MAX_H)) {
      const v = m[1];
      if (OFFICIAL_HEIGHT.has(v)) continue;
      violations.push({ relPath, line: i + 1, kind: 'max-height', value: v });
    }
  }
}

if (violations.length) {
  console.error(`✗ 出现了正式档位以外的断点（${violations.length} 处）：`);
  for (const v of violations) {
    console.error(`    ${v.relPath}:${v.line}  ${v.kind}: ${v.value}px`);
  }
  console.error('\n正式档位：max-width 480 / 768 / 1024 / 1180；max-height 720。');
  console.error('确实需要新档位时，先改 scripts/check-breakpoints.mjs 里的 OFFICIAL_* / LEGACY_*，');
  console.error('并在 PR 里说明为什么现有四档都表达不了这个临界值。');
  process.exit(1);
}

console.log('✓ 断点：未出现正式档位以外的取值');

// 存量档案全部消失时提示收紧，让这张表跟着代码一起变小。
const unusedLegacy = [...LEGACY_WIDTH].filter((v) => !legacyUsed.has(`w${v}`));
if (unusedLegacy.length) {
  console.log(`ℹ 以下存量档位已经没有引用，可以从 LEGACY_WIDTH 移除：${unusedLegacy.join(' / ')}`);
}

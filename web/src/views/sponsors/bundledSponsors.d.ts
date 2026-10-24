/**
 * 构建期注入的赞助名单（见 `web/vite.config.ts` 的 `define`）。
 *
 * ## 为什么声明成 `unknown` 而不是一个具体类型
 * 注入的值来自仓库根的 `sponsors.json` —— 那个文件可能被删、可能被改坏、可能被换成
 * 一个结构完全不同的 JSON。而 `sponsorData.ts` 无论如何都要走一遍逐条校验。
 * 这里若声明成 `SponsorFile`，那一步校验看起来就像"多余的防御"，
 * 而它恰恰是这一层唯一要防的事（同 `contract.ts` 里"手抄镜像"的那类注释）。
 *
 * `declare const` 不产生任何运行时代码，与 `define` 的文本替换配套。
 */
declare const __SPONSORS_BUNDLED__: unknown;

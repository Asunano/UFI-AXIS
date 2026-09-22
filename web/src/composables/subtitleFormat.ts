/**
 * 字幕格式在 **web 端**的可用性判定。
 *
 * ## 为什么不用 core 回的 `supported`
 * `/api/media/subtitles` 的 `supported` 语义是「**core 能转码并给出字幕 MIME**」，
 * 对齐的是 **app 端 media3** 的解析能力。而浏览器的能力完全是另一条线：
 *
 * | 格式 | media3（app） | 浏览器 `<track>` | ArtPlayer + 本项目渲染 |
 * |------|---------------|------------------|------------------------|
 * | vtt  | ✅            | ✅ 原生           | ✅                     |
 * | srt  | ✅            | ❌               | ✅（内部转 vtt）        |
 * | ass / ssa | ✅       | ❌               | ✅（assjs 自绘）        |
 * | ttml / dfxp / xml | ✅ | ❌              | ❌ 无通用方案           |
 * | sub / smi / idx | ❌ | ❌               | ❌                     |
 *
 * 直接拿 core 的 `supported` 当"我能播"，会把 ttml 当成能用 —— 用户选了却什么都不显示，
 * 又是一次静默失败。所以 web 侧必须有这一份**独立**判定。
 *
 * **两份判据刻意不同，不要为了"统一"合并**：合了之后必然有一端在说谎 ——
 * 要么 web 把 ttml 标成能播，要么 app 把它其实能放的 ass 标成不支持。
 *
 * ## assjs 而不是 jassub
 * ass 渲染选了 `assjs`（纯 JS/CSS，约 50KB）而不是
 * `artplayer-plugin-jassub`（libass 编译的 wasm，约 1.5MB + 需要额外部署 worker/wasm 静态资源）。
 *
 * 取舍依据：影视剧对话字幕只用字体/字号/颜色/描边/阴影/对齐，assjs 全支持。
 * 真正需要 libass 的是日番字幕组那类特效 —— 卡拉OK逐字上色（`\k`/`\kf`）、
 * `\move`/`\clip`/`\t` 动画、`\frz` 旋转、矢量绘图 `\p1`、内嵌字体。
 * 那些场景下 assjs **掉的是特效、对话仍然可读**，而前端是打包成 ZIP 上传到设备
 * 由 core 托管的，1.5MB 的 wasm 要从一台随身 WiFi 拉过来，代价不对等。
 *
 * 也**不用** `artplayer-plugin-assjs` 那个封装包：它的工厂返回 `show: ass.show()`，
 * 是立即调用而不是方法引用，构造时就把字幕藏了（踩过，见 `VideoPlayer.vue`）。
 *
 * 换 jassub 只需替换 `VideoPlayer.vue` 里 `applySubtitle` 的 ass 分支。
 */

/** web 端渲染这条字幕的方式。 */
export type SubtitleRender =
  /** 浏览器原生 WebVTT，直接给 ArtPlayer 的 subtitle.url */
  | 'vtt'
  /** ArtPlayer 内部转 vtt（它自带 srt→vtt） */
  | 'srt'
  /** 走 assjs 自绘，需要先把**文本内容**取下来 */
  | 'ass'
  /** web 端放不了 —— 列出来但标注，不要假装能用 */
  | 'unsupported';

/** 扩展名 → 渲染方式。键必须小写、不含点。 */
const RENDER_BY_EXT: Record<string, SubtitleRender> = {
  vtt: 'vtt',
  webvtt: 'vtt',
  srt: 'srt',
  ass: 'ass',
  ssa: 'ass',
  // ttml/dfxp/xml：core 认（media3 能解），但浏览器侧没有通用渲染方案
  ttml: 'unsupported',
  dfxp: 'unsupported',
  xml: 'unsupported',
  // MicroDVD / SAMI / VobSub 索引：两端都放不了
  sub: 'unsupported',
  smi: 'unsupported',
  sami: 'unsupported',
  idx: 'unsupported',
};

/** `/api/media/subtitles` 的一条 item（形状由 core 的 `subtitleEntryOf` 决定）。 */
export interface SubtitleEntry {
  name: string;
  path: string;
  ext: string;
  size: number;
  /** ⚠️ core 视角（media3 能力），**不是** web 能力。判 web 用 [renderOf] */
  supported: boolean;
  mime: string;
  /** 从文件名猜的语言标记（`zh-CN` / `chs&eng` / `forced`…），可能为空 */
  label: string;
}

/**
 * 这条字幕在 web 端怎么渲染。
 *
 * 先看 core 的 `supported`：它为 false 说明连转码都做不到（MicroDVD、超大文件），
 * 那 web 侧无论如何也拿不到可用文本 —— 这一层是必要的前置，不能只看扩展名。
 */
export function renderOf(entry: SubtitleEntry): SubtitleRender {
  if (!entry.supported) return 'unsupported';
  return RENDER_BY_EXT[entry.ext?.toLowerCase() ?? ''] ?? 'unsupported';
}

/** web 端能用的字幕。用于"有没有字幕可挂"的判断与下拉列表过滤。 */
export function isWebPlayable(entry: SubtitleEntry): boolean {
  return renderOf(entry) !== 'unsupported';
}

/**
 * 字幕轨在选择器里的显示名。
 *
 * 优先用 core 猜出来的语言标记（`zh-CN`、`简体`…），没有就退回文件名 ——
 * 一个目录里有好几条字幕时，`movie.zh.srt` / `movie.en.srt` 只靠"字幕1/字幕2"是分不出来的。
 * 不支持的格式在名字里直接标出来，让它在列表里就说清自己不能用。
 */
export function subtitleDisplayName(entry: SubtitleEntry): string {
  const base = entry.label?.trim() || entry.name;
  const ext = entry.ext?.toUpperCase() || '';
  if (renderOf(entry) === 'unsupported') return `${base}（${ext} 不支持）`;
  return ext ? `${base}（${ext}）` : base;
}

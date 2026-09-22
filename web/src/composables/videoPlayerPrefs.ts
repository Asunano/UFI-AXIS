/**
 * 视频播放器的本地偏好（音量、字幕外观、字幕轨记忆）。
 *
 * ## 为什么放在 localStorage 而不是 core
 * 这些都是**这台浏览器上的观看习惯**，不是设备配置：
 * 手机上想要大字号、电脑上不想要，写回 core 反而会互相覆盖。
 * app 端同类偏好也是走本机 SharedPreferences（`media_pos_*` 那套），两端天然不互通 ——
 * 这是已有的取舍，不是这里新引入的。
 *
 * ## 为什么是一个整块 JSON 而不是一个 key 一项
 * 字幕轨记忆是 `视频路径 → 字幕路径` 的映射，天然要序列化；
 * 音量和外观再单独开三个 key 只是让"清理/迁移"变成三件事。整块读写最省事，
 * 量级也就几 KB（映射有 [MAX_REMEMBERED] 条上限）。
 *
 * ## 写入失败一律忽略
 * 隐私模式 / 存储配额满时 localStorage 会抛。偏好丢了只是回到默认值，
 * 不该让"记不住音量"升级成"播放器崩了"。
 */

const LS_KEY = 'ufi.video.prefs';

/**
 * 字幕轨记忆的条数上限。
 *
 * 超出后按**写入顺序**淘汰最早的（见 [rememberSubtitle]）。
 * 100 条大约覆盖几季番的观看历史，再多对用户已经没有意义。
 */
const MAX_REMEMBERED = 100;

/** 描边/阴影档位。`none` 是纯文字，弱/强对应两种 text-shadow 强度。 */
export type SubtitleShadow = 'none' | 'light' | 'strong';

/** 字幕外观。**只作用于 ArtPlayer 内建字幕层（vtt/srt）**，ass 的样式由字幕文件自己定义。 */
export interface SubtitleStyle {
  /** 字号，px。对应 CSS 变量 `--art-subtitle-font-size`，ArtPlayer 默认 20 */
  size: number;
  /** 距播放器底部距离，px。对应 `--art-subtitle-bottom`，ArtPlayer 默认 15 */
  bottom: number;
  shadow: SubtitleShadow;
}

export interface VideoPlayerPrefs {
  /** 0–1。ArtPlayer 的 `volume` 选项与 `art.volume` 同量纲 */
  volume: number;
  subtitleStyle: SubtitleStyle;
  /**
   * 上次选中字幕的语言标记（core 从文件名猜的 `SC` / `TC` / `zh-CN`…）。
   *
   * 作用：换到**另一个视频**时，如果它也有同标记的字幕就优先挂那条 ——
   * 一季番里用户只想选一次"简体"，不想每集都点。
   */
  subtitleLang: string;
  /**
   * 视频路径 → 字幕路径。**空串是有意义的值**：表示用户在这个视频上主动关了字幕，
   * 下次不要自动挂回来（和"没有记录"必须区分，否则关不掉）。
   */
  subtitleByPath: Record<string, string>;
  /** [subtitleByPath] 的写入顺序，用于超限淘汰。 */
  subtitleOrder: string[];
}

const DEFAULTS: VideoPlayerPrefs = {
  volume: 0.7,
  subtitleStyle: { size: 20, bottom: 15, shadow: 'light' },
  subtitleLang: '',
  subtitleByPath: {},
  subtitleOrder: [],
};

/**
 * 读偏好。
 *
 * 每个字段都单独校验类型再落地：localStorage 里的内容可能是上一个版本写的、
 * 也可能被用户手改过，`{...DEFAULTS, ...parsed}` 那种写法会把 `volume: "0.5"`
 * 这种脏值直接带进播放器。
 */
export function readVideoPrefs(): VideoPlayerPrefs {
  try {
    const raw = localStorage.getItem(LS_KEY);
    if (!raw) return { ...DEFAULTS, subtitleStyle: { ...DEFAULTS.subtitleStyle } };
    const p = JSON.parse(raw) as Partial<VideoPlayerPrefs>;
    const style: Partial<SubtitleStyle> = p.subtitleStyle ?? {};
    return {
      volume: numIn(p.volume, 0, 1, DEFAULTS.volume),
      subtitleStyle: {
        size: numIn(style.size, 10, 64, DEFAULTS.subtitleStyle.size),
        bottom: numIn(style.bottom, 0, 200, DEFAULTS.subtitleStyle.bottom),
        shadow: isShadow(style.shadow) ? style.shadow : DEFAULTS.subtitleStyle.shadow,
      },
      subtitleLang: typeof p.subtitleLang === 'string' ? p.subtitleLang : '',
      subtitleByPath: isStringMap(p.subtitleByPath) ? p.subtitleByPath : {},
      subtitleOrder: Array.isArray(p.subtitleOrder)
        ? p.subtitleOrder.filter((k): k is string => typeof k === 'string')
        : [],
    };
  } catch {
    return { ...DEFAULTS, subtitleStyle: { ...DEFAULTS.subtitleStyle } };
  }
}

function writeVideoPrefs(p: VideoPlayerPrefs) {
  try {
    localStorage.setItem(LS_KEY, JSON.stringify(p));
  } catch {
    /* 隐私模式 / 配额满，见文件头 */
  }
}

/** 只改其中几项，其余保持原样。 */
export function patchVideoPrefs(patch: Partial<VideoPlayerPrefs>) {
  writeVideoPrefs({ ...readVideoPrefs(), ...patch });
}

/**
 * 记住某个视频选了哪条字幕。
 *
 * `subtitlePath` 传空串 = 用户主动关闭（见 [VideoPlayerPrefs.subtitleByPath]）。
 * 同时更新全局语言偏好 —— 只有真选了轨道才更新，关闭时不动它，
 * 否则"这一集不要字幕"会把整季的语言偏好也清掉。
 */
export function rememberSubtitle(videoPath: string, subtitlePath: string, label = '') {
  if (!videoPath) return;
  const p = readVideoPrefs();
  // 重新写入要先从顺序表里摘掉旧位置，否则同一个视频反复切字幕会把顺序表撑爆
  const order = p.subtitleOrder.filter((k) => k !== videoPath);
  order.push(videoPath);
  const map = { ...p.subtitleByPath, [videoPath]: subtitlePath };
  while (order.length > MAX_REMEMBERED) {
    const oldest = order.shift();
    if (oldest) delete map[oldest];
  }
  writeVideoPrefs({
    ...p,
    subtitleByPath: map,
    subtitleOrder: order,
    subtitleLang: subtitlePath ? label : p.subtitleLang,
  });
}

/**
 * 取某个视频记住的字幕路径。
 *
 * 返回 `undefined` = 没有记录（走默认挑选逻辑）；
 * 返回 `''` = 用户主动关过字幕（不要自动挂）。两者必须区分。
 */
export function recallSubtitle(videoPath: string): string | undefined {
  if (!videoPath) return undefined;
  return readVideoPrefs().subtitleByPath[videoPath];
}

/** 描边档位 → `text-shadow` 值。 */
export function shadowCssOf(shadow: SubtitleShadow): string {
  if (shadow === 'none') return 'none';
  if (shadow === 'strong') {
    // 四向 1px 实心描边 + 一层外扩，亮背景上也能读
    return '1px 1px 0 #000, -1px 1px 0 #000, 1px -1px 0 #000, -1px -1px 0 #000, 0 0 4px rgba(0,0,0,.9)';
  }
  return '0 0 3px rgba(0,0,0,.85)';
}

function numIn(v: unknown, min: number, max: number, fallback: number): number {
  return typeof v === 'number' && Number.isFinite(v) && v >= min && v <= max ? v : fallback;
}

function isShadow(v: unknown): v is SubtitleShadow {
  return v === 'none' || v === 'light' || v === 'strong';
}

function isStringMap(v: unknown): v is Record<string, string> {
  if (!v || typeof v !== 'object' || Array.isArray(v)) return false;
  return Object.values(v as Record<string, unknown>).every((x) => typeof x === 'string');
}

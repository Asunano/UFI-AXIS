/**
 * 音频标签解析（浏览器侧）。
 *
 * - ID3v2.2/2.3/2.4：`TIT2`/`TPE1`/`TALB`（v2.2 是 `TT2`/`TP1`/`TAL`）、`USLT`/`SYLT` 歌词、
 *   `APIC`/`PIC` 封面；
 * - FLAC：`VORBIS_COMMENT`（标题/艺人/专辑/歌词）、`PICTURE` 封面 —— 2026-09-14 补齐，
 *   此前只有 ID3，`.flac` 一律「未知艺人」。
 *
 * 为什么前端要自己解析：浏览器的 `<audio>` 只负责播放，**不暴露任何 tag**。
 * app 端不必如此 —— ExoPlayer（media3）的 Mp3Extractor / FlacExtractor 会直接给出 MediaMetadata。
 * 两端因此是「同一份能力、各自最省的实现方式」，而不是各写一套解析器。
 *
 * 容器不确定时用 [extractAudioTags] / [extractAudioArtwork] / [extractAudioLyrics]，
 * 它们按魔数分流，调用点不用自己 if。
 *
 * 文件名沿用 `id3Lyrics.ts`（改名会牵动引用方），内容已不止 ID3。
 */

export interface Id3TextTags {
  title?: string;
  artist?: string;
  album?: string;
}

export interface Id3Artwork {
  mime: string;
  data: Uint8Array;
}

export function isId3v2(bytes: Uint8Array): boolean {
  return bytes.length >= 3 && bytes[0] === 0x49 && bytes[1] === 0x44 && bytes[2] === 0x33;
}

export function id3TagBodySize(bytes: Uint8Array): number | null {
  if (bytes.length < 10 || !isId3v2(bytes)) return null;
  return ((bytes[6]! & 0x7f) << 21) | ((bytes[7]! & 0x7f) << 14) | ((bytes[8]! & 0x7f) << 7) | (bytes[9]! & 0x7f);
}

function latin1(bytes: Uint8Array, start: number, length: number): string {
  let s = '';
  for (let i = 0; i < length; i++) s += String.fromCharCode(bytes[start + i]!);
  return s;
}

function stripNuls(s: string): string {
  // ID3 文本帧是 NUL 填充的，必须真的把 U+0000 清掉。
  // 用 split/join 而不是 /\u0000+/g：正则里出现控制字符会触发 eslint 的
  // no-control-regex（那条规则防的是"手滑把控制字符写进正则"，而这里是有意为之），
  // 与其挂一条 disable 注释，不如换成不需要正则的写法。
  return s
    .split('\u0000')
    .join('')
    .replace(/\uFEFF/g, '')
    .trim();
}

/**
 * 尝试用指定编码严格解码；有非法字节序列就返回 null。
 *
 * `fatal: true` 是这里的关键：非 fatal 模式会把非法字节替换成 U+FFFD 并"成功"返回，
 * 那就没法用来判断"这份字节到底是不是这个编码"。
 */
function strictDecode(label: string, payload: Uint8Array): string | null {
  try {
    return new TextDecoder(label, { fatal: true }).decode(payload);
  } catch {
    return null;
  }
}

/**
 * `enc === 0`（ID3 规范里是 ISO-8859-1）的实际解码。
 *
 * 规范说 latin1，但国内大量打标工具直接把 UTF-8 或 GBK 字节塞进这个帧 ——
 * 硬按 latin1 解会得到 `ÏÄÌì` 这类乱码（注意：这类乱码**没有** U+FFFD，
 * 与 UTF-16 截断产生的 `夏天Ale�` 是两种不同故障）。
 *
 * 所以只在出现 >= 0x80 的字节时才嗅探，顺序 UTF-8 → GBK → latin1：
 * UTF-8 的字节结构校验最严，误判概率最低；GBK 次之；两者都不成立才落回 latin1。
 * 纯 ASCII 直接走 latin1，省掉两次无意义的尝试。
 */
function decodeLatin1Frame(payload: Uint8Array): string {
  let hasHighByte = false;
  for (let i = 0; i < payload.length; i++) {
    if (payload[i]! >= 0x80) {
      hasHighByte = true;
      break;
    }
  }
  if (hasHighByte) {
    const utf8 = strictDecode('utf-8', payload);
    if (utf8 != null) return utf8;
    const gbk = strictDecode('gbk', payload);
    if (gbk != null) return gbk;
  }
  return new TextDecoder('latin1').decode(payload);
}

function decodeTextPayload(enc: number, payload: Uint8Array): string {
  try {
    if (enc === 1) {
      if (payload.length >= 2 && payload[0] === 0xff && payload[1] === 0xfe) {
        return stripNuls(new TextDecoder('utf-16le').decode(payload.subarray(2)));
      }
      if (payload.length >= 2 && payload[0] === 0xfe && payload[1] === 0xff) {
        return stripNuls(new TextDecoder('utf-16be').decode(payload.subarray(2)));
      }
      return stripNuls(new TextDecoder('utf-16le').decode(payload));
    }
    if (enc === 2) return stripNuls(new TextDecoder('utf-16be').decode(payload));
    if (enc === 3) return stripNuls(new TextDecoder('utf-8').decode(payload));
    return stripNuls(decodeLatin1Frame(payload));
  } catch {
    return stripNuls(new TextDecoder('utf-8').decode(payload));
  }
}

/**
 * 文本帧（TIT2 / TPE1 / TALB …）：`[编码1字节][文本][终止符]`。
 *
 * ## 2026-09-14 修复：`夏天Ale�`
 * 这里原来有一句「贪吃式」剥零：
 * ```ts
 * while (end > 1 && body[end - 1] === 0) end--;
 * ```
 * UTF-16 里 ASCII 字符的**高位字节就是 0x00**。以 UTF-16LE 的 `TPE1 = "夏天Alex"` 为例，
 * 尾部字节是 `78 00 | 00 00`（'x' + 终止符），这个循环会一路剥到 `78` 才停，
 * 把 'x' 的高位一起吃掉 ⇒ payload 变成奇数字节 ⇒ `TextDecoder('utf-16le')`
 * 对落单的代码单元吐出 U+FFFD ⇒ 屏幕上就是 `夏天Ale�`。
 *
 * 而且那句本就**多余**：[stripNuls] 在解码之后清 `\u0000`，终止符早就有人管。
 * 同文件的 [decodeLyricsFrame] 没有这个循环 —— 这解释了为什么当时只有标题/艺人/专辑乱码、
 * 歌词却是好的。
 *
 * 现在只保留一处窄修正：UTF-16 而 payload 长度为奇数且末字节是 0x00 时丢掉它 ——
 * 那是某些打标工具只写了一个终止符字节，属真实存在的畸形数据，且这个判据不会误伤正常内容。
 */
function decodeTextFrame(body: Uint8Array): string | null {
  if (body.length < 2) return null;
  const enc = body[0]!;
  let payload = body.subarray(1);
  if ((enc === 1 || enc === 2) && payload.length % 2 === 1 && payload[payload.length - 1] === 0) {
    payload = payload.subarray(0, payload.length - 1);
  }
  const text = decodeTextPayload(enc, payload);
  return text || null;
}

function decodeLyricsFrame(body: Uint8Array): string | null {
  if (body.length < 4) return null;
  const enc = body[0]!;
  let textStart = -1;
  if (enc === 1 || enc === 2) {
    for (let j = 4; j + 1 < body.length; j += 2) {
      if (body[j] === 0 && body[j + 1] === 0) {
        textStart = j + 2;
        break;
      }
    }
  } else {
    for (let j = 4; j < body.length; j++) {
      if (body[j] === 0) {
        textStart = j + 1;
        break;
      }
    }
  }
  if (textStart < 0 || textStart >= body.length) return null;
  const text = decodeTextPayload(enc, body.subarray(textStart));
  return text || null;
}

interface FrameHit {
  id: string;
  body: Uint8Array;
}

function* id3Frames(bytes: Uint8Array): Generator<FrameHit> {
  if (bytes.length < 10 || !isId3v2(bytes)) return;
  const major = bytes[3]!;
  const tagSize = id3TagBodySize(bytes);
  if (tagSize == null) return;
  const end = Math.min(10 + tagSize, bytes.length);
  const idLen = major === 2 ? 3 : 4;
  let pos = 10;
  while (pos + idLen + 6 <= end) {
    const id = latin1(bytes, pos, idLen);
    if (!id || id.charCodeAt(0) === 0) break;
    let frameSize = 0;
    let bodyOffset = 0;
    if (major === 2) {
      frameSize = (bytes[pos + 3]! << 16) | (bytes[pos + 4]! << 8) | bytes[pos + 5]!;
      bodyOffset = pos + 6;
    } else if (major === 3) {
      frameSize = (bytes[pos + 4]! << 24) | (bytes[pos + 5]! << 16) | (bytes[pos + 6]! << 8) | bytes[pos + 7]!;
      bodyOffset = pos + 10;
    } else if (major === 4) {
      frameSize =
        ((bytes[pos + 4]! & 0x7f) << 21) |
        ((bytes[pos + 5]! & 0x7f) << 14) |
        ((bytes[pos + 6]! & 0x7f) << 7) |
        (bytes[pos + 7]! & 0x7f);
      bodyOffset = pos + 10;
    } else {
      return;
    }
    if (frameSize <= 0 || bodyOffset + frameSize > end) break;
    yield { id, body: bytes.subarray(bodyOffset, bodyOffset + frameSize) };
    pos = bodyOffset + frameSize;
  }
}

export function extractEmbeddedLyrics(bytes: Uint8Array): string | null {
  for (const f of id3Frames(bytes)) {
    if (f.id === 'USLT' || f.id === 'SYLT') {
      const text = decodeLyricsFrame(f.body);
      if (text) return text;
    }
  }
  return null;
}

/**
 * 文本标签帧 ID → 语义。
 *
 * v2.2 用 3 字符 ID（`TT2`/`TP1`/`TAL`），v2.3/v2.4 用 4 字符（`TIT2`/`TPE1`/`TALB`）。
 * 2026-09-14 补上 v2.2 那三个：`id3Frames` 本来就支持 major==2 的 3 字节帧头，
 * 只是这里的 ID 对不上，于是 v2.2 文件"能解析出帧但一个标签都取不到"。
 */
const TEXT_FRAME_IDS: Record<string, keyof Id3TextTags> = {
  TIT2: 'title',
  TPE1: 'artist',
  TALB: 'album',
  TT2: 'title',
  TP1: 'artist',
  TAL: 'album',
};

export function extractId3TextTags(bytes: Uint8Array): Id3TextTags {
  const out: Id3TextTags = {};
  for (const f of id3Frames(bytes)) {
    const key = TEXT_FRAME_IDS[f.id];
    if (!key || out[key]) continue;
    const v = decodeTextFrame(f.body);
    if (v) out[key] = v;
    if (out.title && out.artist && out.album) break;
  }
  return out;
}

/**
 * ID3v2 APIC 封面 —— 对应 App ExoPlayer MediaMetadata.artworkData。
 * 帧体：[编码][MIME\\0][类型1字节][描述\\0|\\0\\0][图片字节]
 * 优先 picture type=3（封面），否则取第一张图。
 */
export function extractId3Artwork(bytes: Uint8Array): Id3Artwork | null {
  let fallback: Id3Artwork | null = null;
  for (const f of id3Frames(bytes)) {
    if (f.id !== 'PIC' && f.id !== 'APIC') continue;
    const art = f.id === 'PIC' ? decodePicFrame(f.body) : decodeApicFrame(f.body);
    if (!art) continue;
    // APIC picture type at body[1+mime.length+1]: 3 = front cover
    if (isFrontCover(f.body, f.id)) return art;
    if (!fallback) fallback = art;
  }
  return fallback;
}

function isFrontCover(body: Uint8Array, id: string): boolean {
  // APIC: enc, mime..., 0, type
  if (id === 'APIC') {
    let i = 1;
    while (i < body.length && body[i] !== 0) i++;
    if (i + 1 >= body.length) return false;
    return body[i + 1] === 3;
  }
  // PIC: enc, 3-char format, type
  if (body.length >= 5) return body[4] === 3;
  return false;
}

function decodeApicFrame(body: Uint8Array): Id3Artwork | null {
  if (body.length < 6) return null;
  const enc = body[0]!;
  // MIME latin1 null-terminated
  let mimeEnd = 1;
  while (mimeEnd < body.length && body[mimeEnd] !== 0) mimeEnd++;
  if (mimeEnd >= body.length - 1) return null;
  const mime = latin1(body, 1, mimeEnd - 1) || 'image/jpeg';
  // picture type 1 byte
  const typeIdx = mimeEnd + 1;
  // description
  let dataStart = -1;
  if (enc === 1 || enc === 2) {
    let j = typeIdx + 1;
    // skip BOM if present on description
    if (j + 1 < body.length && body[j] === 0xff) j += 2;
    for (; j + 1 < body.length; j += 2) {
      if (body[j] === 0 && body[j + 1] === 0) {
        dataStart = j + 2;
        break;
      }
    }
  } else {
    for (let j = typeIdx + 1; j < body.length; j++) {
      if (body[j] === 0) {
        dataStart = j + 1;
        break;
      }
    }
  }
  if (dataStart < 0 || dataStart >= body.length) return null;
  const data = body.slice(dataStart);
  if (data.length < 32) return null;
  return { mime: normalizeMime(mime), data };
}

function decodePicFrame(body: Uint8Array): Id3Artwork | null {
  if (body.length < 6) return null;
  const enc = body[0]!;
  const fmt = latin1(body, 1, 3).toUpperCase();
  const typeIdx = 4;
  let dataStart = -1;
  if (enc === 1 || enc === 2) {
    let j = typeIdx + 1;
    if (j + 1 < body.length && body[j] === 0xff) j += 2;
    for (; j + 1 < body.length; j += 2) {
      if (body[j] === 0 && body[j + 1] === 0) {
        dataStart = j + 2;
        break;
      }
    }
  } else {
    for (let j = typeIdx + 1; j < body.length; j++) {
      if (body[j] === 0) {
        dataStart = j + 1;
        break;
      }
    }
  }
  if (dataStart < 0 || dataStart >= body.length) return null;
  const data = body.slice(dataStart);
  if (data.length < 32) return null;
  const mime = fmt === 'PNG' ? 'image/png' : fmt === 'GIF' ? 'image/gif' : 'image/jpeg';
  return { mime, data };
}

function normalizeMime(mime: string): string {
  const m = mime.trim().toLowerCase();
  if (m === 'image/jpg' || m === 'image/jpeg' || m === 'jpeg' || m === 'jpg') return 'image/jpeg';
  if (m === 'png' || m === 'image/png') return 'image/png';
  if (m === 'gif' || m === 'image/gif') return 'image/gif';
  if (m === 'webp' || m === 'image/webp') return 'image/webp';
  if (m.startsWith('image/')) return m;
  return 'image/jpeg';
}

/**
 * 解析带时间轴的 LRC。
 *
 * `[offset:N]` 是全局时间偏移（毫秒，可为负），必须处理：不处理的话带 offset 的歌词
 * 会整篇滚动错位，而且**与 app 端 `FilePreviewOverlay.parseLrc` 的口径不一致** ——
 * 两端号称同口径却对同一个文件解出不同时间轴，比单端解错更难查。
 *
 * BOM 用全局 `replace` 而不是 `/^\uFEFF/`：部分工具会在每行行首都写 BOM，
 * 只清开头会让第二行起的时间标签匹配不上。app 端也是全局清。
 */
export function parseLrc(text: string): Array<[number, string]> {
  const clean = text.replace(/\uFEFF/g, '');
  const tag = /\[(\d{1,2}):(\d{2})(?:[.:](\d{1,3}))?]/g;
  const offsetTag = /\[offset:\s*(-?\d+)\s*]/;
  const out: Array<[number, string]> = [];
  let offsetMs = 0;
  for (const raw of clean.split(/\r?\n/)) {
    // offset 行单独处理，不计入歌词内容
    const off = offsetTag.exec(raw.toLowerCase());
    if (off !== null) {
      offsetMs = Number(off[1] || 0);
      continue;
    }
    tag.lastIndex = 0;
    const times: Array<[number, number, number]> = [];
    let m: RegExpExecArray | null;
    while ((m = tag.exec(raw)) !== null) {
      const min = Number(m[1] || 0);
      const sec = Number(m[2] || 0);
      const fracRaw = m[3] || '';
      const frac = fracRaw ? Number((fracRaw + '000').slice(0, 3)) : 0;
      times.push([min, sec, frac]);
    }
    if (!times.length) {
      continue;
    }
    const content = raw.replace(tag, '').trim();
    if (!content) {
      continue;
    }
    for (const [min, sec, frac] of times) {
      out.push([(min * 60 + sec) * 1000 + frac + offsetMs, content]);
    }
  }
  return out.sort((a, b) => a[0] - b[0]);
}

/** 无时间轴歌词行的时间戳哨兵值（见 [parseLyrics]）。与 app 端 `LYRIC_TIME_UNSYNCED` 同值。 */
export const LYRIC_TIME_UNSYNCED = -1;

/**
 * 解析歌词文本：优先按 LRC 时间轴解析，**没有任何时间标签时降级为纯文本歌词**
 * （每行时间戳为 [LYRIC_TIME_UNSYNCED]），UI 渲染成不滚动、不高亮的静态文本。
 *
 * 网上下载的 .lrc 有相当比例只是纯文字（或只有 `[ti:]`/`[ar:]` 元信息头），
 * 只用 [parseLrc] 会得到空数组 ⇒ 显示「暂无歌词」，明明歌词文件就在旁边。
 * 与 app 端 `FilePreviewOverlay.parseLyrics` 同一口径。
 */
export function parseLyrics(text: string): Array<[number, string]> {
  const synced = parseLrc(text);
  if (synced.length) return synced;
  const meta = /^\[[a-zA-Z#]+:.*]$/;
  return text
    .replace(/\uFEFF/g, '')
    .split(/\r?\n/)
    .map((l) => l.trim())
    .filter((l) => l.length > 0 && !meta.test(l))
    .map((l) => [LYRIC_TIME_UNSYNCED, l] as [number, string]);
}

/**
 * 猜编码解码文本（UTF-8 → GBK → latin1），用于独立 `.lrc` 文件。
 *
 * 不能用 `Response.text()`：它按 Content-Type 的 charset（core 给的是
 * `application/octet-stream` → UTF-8）硬解，GBK 编码的 .lrc 会整篇变乱码。
 * 与 app 端 `decodeTextBytes` 同一口径 —— 两端对同一个 .lrc 必须解出同样的字。
 */
export function decodeTextBytes(bytes: Uint8Array): string {
  const utf8 = strictDecode('utf-8', bytes);
  if (utf8 != null) return utf8;
  const gbk = strictDecode('gbk', bytes);
  if (gbk != null) return gbk;
  return new TextDecoder('latin1').decode(bytes);
}

// ───────────────────────── FLAC（Vorbis Comment / PICTURE） ─────────────────────────
//
// 2026-09-14 新增。此前本文件**只有 ID3v2 解析器**，而 FLAC 的头是 `fLaC` 而不是 `ID3`，
// `id3Frames()` 第一行就 return，于是 `.flac` 虽然在预览白名单里，标签却恒为空 ——
// UI 落到「未知艺人」、封面落到占位图。app 端没这个问题：ExoPlayer 的 FlacExtractor 自带解析。
//
// FLAC 元数据结构（与 ID3 完全不同，别把两边的读法混用）：
//   `fLaC` magic(4B) 之后是一串 METADATA_BLOCK：
//     header 4B：byte0 = [last-block 1bit][type 7bit]，byte1..3 = 24bit **大端**长度
//     type 4 = VORBIS_COMMENT：内部所有长度是 **32bit 小端**（Vorbis 规范如此，
//              与 FLAC 自己的大端 header 相反 —— 这是最容易写错的一处）
//     type 6 = PICTURE：内部所有长度是 **32bit 大端**
//
// 另有一种少见情形：某些工具会在 FLAC 前面再加一个 ID3v2 tag。[flacMagicOffset] 会跳过它。

const FLAC_BLOCK_VORBIS_COMMENT = 4;
const FLAC_BLOCK_PICTURE = 6;

/**
 * 返回 `fLaC` magic 的位置：0（标准）或 ID3v2 tag 之后（少见的前置 ID3）；不是 FLAC 返回 -1。
 */
function flacMagicOffset(bytes: Uint8Array): number {
  const matches = (at: number) =>
    bytes.length >= at + 4 &&
    bytes[at] === 0x66 && // f
    bytes[at + 1] === 0x4c && // L
    bytes[at + 2] === 0x61 && // a
    bytes[at + 3] === 0x43; // C
  if (matches(0)) return 0;
  if (isId3v2(bytes)) {
    const tagSize = id3TagBodySize(bytes);
    if (tagSize != null && matches(10 + tagSize)) return 10 + tagSize;
  }
  return -1;
}

export function isFlac(bytes: Uint8Array): boolean {
  return flacMagicOffset(bytes) >= 0;
}

interface FlacBlock {
  type: number;
  body: Uint8Array;
  /** 该 block 数据段在**整个 bytes** 中的起始偏移，用于算"还需要读到第几个字节"。 */
  absStart: number;
  /** header 里声明的数据长度。`absStart + declaredLen > bytes.length` 即表示这个 block 被截断了。 */
  declaredLen: number;
}

function* flacBlocks(bytes: Uint8Array): Generator<FlacBlock> {
  const start = flacMagicOffset(bytes);
  if (start < 0) return;
  let pos = start + 4;
  // 上限兜底：畸形文件里 last-flag 可能永远不出现，别让循环靠数据自觉收场
  for (let guard = 0; guard < 128; guard++) {
    if (pos + 4 > bytes.length) return;
    const header = bytes[pos]!;
    const isLast = (header & 0x80) !== 0;
    const type = header & 0x7f;
    const len = (bytes[pos + 1]! << 16) | (bytes[pos + 2]! << 8) | bytes[pos + 3]!;
    const bodyStart = pos + 4;
    if (len < 0) return;
    // 只截到已有字节：只读了文件头部时后面的块可能不完整。
    // 消费方必须自己判断 `absStart + declaredLen` 是否超出 —— 尤其是 PICTURE，
    // 用截断的字节构造 Blob 会让浏览器只渲染出图片上半部分（下半露出容器底色）。
    const bodyEnd = Math.min(bodyStart + len, bytes.length);
    if (bodyEnd > bodyStart) {
      yield { type, body: bytes.subarray(bodyStart, bodyEnd), absStart: bodyStart, declaredLen: len };
    }
    if (isLast) return;
    pos = bodyStart + len;
  }
}

function readU32LE(b: Uint8Array, at: number): number {
  return (b[at]! | (b[at + 1]! << 8) | (b[at + 2]! << 16) | (b[at + 3]! << 24)) >>> 0;
}

function readU32BE(b: Uint8Array, at: number): number {
  return ((b[at]! << 24) | (b[at + 1]! << 16) | (b[at + 2]! << 8) | b[at + 3]!) >>> 0;
}

/**
 * 逐条读 VORBIS_COMMENT 的 `FIELD=value`。
 *
 * 字段名大小写不敏感（规范如此），所以统一转小写再匹配；值一律 UTF-8（规范强制，
 * 没有 ID3 那种"编码字节"，也因此不存在本文件上面那类 UTF-16 截断问题）。
 */
function vorbisComments(body: Uint8Array): Map<string, string> {
  const out = new Map<string, string>();
  if (body.length < 8) return out;
  const utf8 = new TextDecoder('utf-8');
  let pos = 0;
  const vendorLen = readU32LE(body, pos);
  pos += 4 + vendorLen;
  if (pos + 4 > body.length) return out;
  const count = readU32LE(body, pos);
  pos += 4;
  // count 来自文件，畸形值会让循环跑飞，夹一个上限
  const safeCount = Math.min(count, 512);
  for (let i = 0; i < safeCount; i++) {
    if (pos + 4 > body.length) break;
    const len = readU32LE(body, pos);
    pos += 4;
    if (len <= 0 || pos + len > body.length) break;
    const entry = utf8.decode(body.subarray(pos, pos + len));
    pos += len;
    const eq = entry.indexOf('=');
    if (eq <= 0) continue;
    const key = entry.slice(0, eq).toLowerCase();
    const value = entry.slice(eq + 1);
    if (value && !out.has(key)) out.set(key, value);
  }
  return out;
}

/** FLAC 标题/艺人/专辑 —— 与 [extractId3TextTags] 同形状，调用方无需分支。 */
export function extractFlacTags(bytes: Uint8Array): Id3TextTags {
  for (const block of flacBlocks(bytes)) {
    if (block.type !== FLAC_BLOCK_VORBIS_COMMENT) continue;
    const c = vorbisComments(block.body);
    const out: Id3TextTags = {};
    const title = c.get('title');
    // ARTIST 缺失时退 ALBUMARTIST：整轨/合辑常只写后者，否则又是一个"未知艺人"
    const artist = c.get('artist') || c.get('albumartist') || c.get('album artist');
    const album = c.get('album');
    if (title) out.title = title;
    if (artist) out.artist = artist;
    if (album) out.album = album;
    return out;
  }
  return {};
}

/**
 * FLAC 内嵌歌词。
 *
 * 常见键有 `LYRICS`（多数工具）与 `UNSYNCEDLYRICS`（照 ID3 USLT 起的名）。
 * 值可能是纯文本，也可能是带 `[mm:ss.xx]` 时间轴的 LRC —— 交给 [parseLrc] 判即可。
 */
export function extractFlacLyrics(bytes: Uint8Array): string | null {
  for (const block of flacBlocks(bytes)) {
    if (block.type !== FLAC_BLOCK_VORBIS_COMMENT) continue;
    const c = vorbisComments(block.body);
    return c.get('lyrics') || c.get('unsyncedlyrics') || c.get('unsynced lyrics') || null;
  }
  return null;
}

/**
 * FLAC PICTURE block（type 6）—— 结构近似 ID3 的 APIC，但**所有长度都是大端**。
 *
 * 布局：`[u32 图片类型][u32 MIME 长度][MIME ASCII][u32 描述长度][描述 UTF-8]
 *        [u32 宽][u32 高][u32 色深][u32 索引色数][u32 数据长度][图片数据]`
 * 图片类型 3 = front cover，优先它；没有就取第一张。
 *
 * ## 只接受**完整**的图片数据（2026-09-14 修）
 * 之前这里是「够 32 字节就先给出去」，理由写的是"能渲染出部分图也比占位符好"——
 * 那个判断是错的：浏览器对截断的 JPEG 会**渲染出上半部分、下半留空**，
 * 空白处露出 `.cover-box` 的 `var(--surface-hover)` 底色（浅色 rgba(0,0,0,.03) /
 * 深色 rgba(255,255,255,.04)），观感就是"封面下半被一层随深浅模式变色的遮罩挡住"。
 * 而只读文件头部（Range 请求）时封面被截断是**常态**，不是边缘情况。
 *
 * 现在数据不完整就返回 null，由调用方用 [flacArtworkRequiredBytes] 算出确切需要多少字节后重取。
 */
export function extractFlacArtwork(bytes: Uint8Array): Id3Artwork | null {
  let fallback: Id3Artwork | null = null;
  for (const block of flacBlocks(bytes)) {
    if (block.type !== FLAC_BLOCK_PICTURE) continue;
    // block 本身被截断：连结构都读不全，更谈不上图片数据
    if (block.absStart + block.declaredLen > bytes.length) continue;
    const b = block.body;
    if (b.length < 32) continue;
    let pos = 0;
    const picType = readU32BE(b, pos);
    pos += 4;
    const mimeLen = readU32BE(b, pos);
    pos += 4;
    if (mimeLen > 255 || pos + mimeLen > b.length) continue;
    const mime = latin1(b, pos, mimeLen);
    pos += mimeLen;
    if (pos + 4 > b.length) continue;
    const descLen = readU32BE(b, pos);
    pos += 4;
    if (pos + descLen > b.length) continue;
    pos += descLen;
    // 宽/高/色深/索引色数四个 u32 直接跳过：预览只要字节流，尺寸由 <img> 自己算
    pos += 16;
    if (pos + 4 > b.length) continue;
    const dataLen = readU32BE(b, pos);
    pos += 4;
    // 图片数据必须完整 —— 少一个字节就是"半张图"
    if (dataLen <= 0 || pos + dataLen > b.length) continue;
    const art: Id3Artwork = { mime: normalizeMime(mime), data: b.slice(pos, pos + dataLen) };
    if (picType === 3) return art;
    if (!fallback) fallback = art;
  }
  return fallback;
}

/**
 * 要拿到完整的 FLAC 内嵌封面，总共需要读到第几个字节。
 *
 * 返回 null 表示「不需要再读」：要么不是 FLAC、要么没有 PICTURE block、要么现有字节已经够了。
 * 有了它，调用方就不用靠猜一个上限（猜小了出半张图、猜大了白拉几 MB）。
 */
export function flacArtworkRequiredBytes(bytes: Uint8Array): number | null {
  let required: number | null = null;
  for (const block of flacBlocks(bytes)) {
    if (block.type !== FLAC_BLOCK_PICTURE) continue;
    const end = block.absStart + block.declaredLen;
    if (end > bytes.length && (required == null || end < required)) required = end;
  }
  return required;
}

/**
 * 统一入口：按魔数分流 ID3 / FLAC，调用方不必自己判容器。
 *
 * 这是「双端互补」的落点 —— app 端由 ExoPlayer 一视同仁地给出 MediaMetadata，
 * web 端就该有一个同样不挑容器的函数，而不是让每个调用点各写一遍 if。
 */
export function extractAudioTags(bytes: Uint8Array): Id3TextTags {
  if (isFlac(bytes)) {
    const flac = extractFlacTags(bytes);
    // 前置 ID3 的 FLAC：Vorbis Comment 为空时回落 ID3
    if (flac.title || flac.artist || flac.album) return flac;
  }
  return extractId3TextTags(bytes);
}

/** 统一入口：内嵌封面（ID3 APIC/PIC 或 FLAC PICTURE）。 */
export function extractAudioArtwork(bytes: Uint8Array): Id3Artwork | null {
  if (isFlac(bytes)) {
    const art = extractFlacArtwork(bytes);
    if (art) return art;
  }
  return extractId3Artwork(bytes);
}

/** 统一入口：内嵌歌词（ID3 USLT/SYLT 或 FLAC LYRICS）。 */
export function extractAudioLyrics(bytes: Uint8Array): string | null {
  if (isFlac(bytes)) {
    const l = extractFlacLyrics(bytes);
    if (l) return l;
  }
  return extractEmbeddedLyrics(bytes);
}

/**
 * ID3v2 helpers: USLT/SYLT lyrics + TIT2/TPE1/TALB tags.
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
  return (
    ((bytes[6]! & 0x7f) << 21) |
    ((bytes[7]! & 0x7f) << 14) |
    ((bytes[8]! & 0x7f) << 7) |
    (bytes[9]! & 0x7f)
  );
}

function latin1(bytes: Uint8Array, start: number, length: number): string {
  let s = "";
  for (let i = 0; i < length; i++) s += String.fromCharCode(bytes[start + i]!);
  return s;
}

function stripNuls(s: string): string {
  return s.replace(/\u0000+/g, "").replace(/\uFEFF/g, "").trim();
}

function decodeTextPayload(enc: number, payload: Uint8Array): string {
  try {
    if (enc === 1) {
      if (payload.length >= 2 && payload[0] === 0xff && payload[1] === 0xfe) {
        return stripNuls(new TextDecoder("utf-16le").decode(payload.subarray(2)));
      }
      if (payload.length >= 2 && payload[0] === 0xfe && payload[1] === 0xff) {
        return stripNuls(new TextDecoder("utf-16be").decode(payload.subarray(2)));
      }
      return stripNuls(new TextDecoder("utf-16le").decode(payload));
    }
    if (enc === 2) return stripNuls(new TextDecoder("utf-16be").decode(payload));
    if (enc === 3) return stripNuls(new TextDecoder("utf-8").decode(payload));
    return stripNuls(new TextDecoder("latin1").decode(payload));
  } catch {
    return stripNuls(new TextDecoder("utf-8").decode(payload));
  }
}

function decodeTextFrame(body: Uint8Array): string | null {
  if (body.length < 2) return null;
  const enc = body[0]!;
  let end = body.length;
  while (end > 1 && body[end - 1] === 0) end--;
  const text = decodeTextPayload(enc, body.subarray(1, end));
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
      frameSize =
        (bytes[pos + 4]! << 24) | (bytes[pos + 5]! << 16) | (bytes[pos + 6]! << 8) | bytes[pos + 7]!;
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
    if (f.id === "USLT" || f.id === "SYLT") {
      const text = decodeLyricsFrame(f.body);
      if (text) return text;
    }
  }
  return null;
}

export function extractId3TextTags(bytes: Uint8Array): Id3TextTags {
  const out: Id3TextTags = {};
  for (const f of id3Frames(bytes)) {
    if (f.id === "TIT2" && !out.title) {
      const v = decodeTextFrame(f.body);
      if (v) out.title = v;
    } else if (f.id === "TPE1" && !out.artist) {
      const v = decodeTextFrame(f.body);
      if (v) out.artist = v;
    } else if (f.id === "TALB" && !out.album) {
      const v = decodeTextFrame(f.body);
      if (v) out.album = v;
    }
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
    if (f.id !== "PIC" && f.id !== "APIC") continue;
    const art = f.id === "PIC" ? decodePicFrame(f.body) : decodeApicFrame(f.body);
    if (!art) continue;
    // APIC picture type at body[1+mime.length+1]: 3 = front cover
    if (isFrontCover(f.body, f.id)) return art;
    if (!fallback) fallback = art;
  }
  return fallback;
}

function isFrontCover(body: Uint8Array, id: string): boolean {
  // APIC: enc, mime..., 0, type
  if (id === "APIC") {
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
  const mime = latin1(body, 1, mimeEnd - 1) || "image/jpeg";
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
  const mime =
    fmt === "PNG" ? "image/png" : fmt === "GIF" ? "image/gif" : "image/jpeg";
  return { mime, data };
}

function normalizeMime(mime: string): string {
  const m = mime.trim().toLowerCase();
  if (m === "image/jpg" || m === "image/jpeg" || m === "jpeg" || m === "jpg") return "image/jpeg";
  if (m === "png" || m === "image/png") return "image/png";
  if (m === "gif" || m === "image/gif") return "image/gif";
  if (m === "webp" || m === "image/webp") return "image/webp";
  if (m.startsWith("image/")) return m;
  return "image/jpeg";
}

export function parseLrc(text: string): Array<[number, string]> {
  const clean = text.replace(/^\uFEFF/, "");
  const tag = /\[(\d{1,2}):(\d{2})(?:[.:](\d{1,3}))?]/g;
  const out: Array<[number, string]> = [];
  for (const raw of clean.split(/\r?\n/)) {
    tag.lastIndex = 0;
    const times: Array<[number, number, number]> = [];
    let m: RegExpExecArray | null;
    while ((m = tag.exec(raw)) !== null) {
      const min = Number(m[1] || 0);
      const sec = Number(m[2] || 0);
      const fracRaw = m[3] || "";
      const frac = fracRaw ? Number((fracRaw + "000").slice(0, 3)) : 0;
      times.push([min, sec, frac]);
    }
    if (!times.length) continue;
    const content = raw.replace(tag, "").trim();
    if (!content) continue;
    for (const [min, sec, frac] of times) out.push([(min * 60 + sec) * 1000 + frac, content]);
  }
  return out.sort((a, b) => a[0] - b[0]);
}

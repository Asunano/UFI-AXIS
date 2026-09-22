/**
 * 音频元数据探测：**标签 / 封面 / 歌词**，全部靠 Range 请求读文件头自己解。
 *
 * ## 为什么要有这一层
 * `<audio>` 不暴露任何 tag，而 `/api/files/*` 只提供字节流 —— 文件管理器要显示
 * 曲名/艺人/封面/歌词，只能自己解。解析器本体在 [id3Lyrics]（纯 TS、零网络依赖），
 * 这个文件负责的是**取字节的策略**：读多少、什么时候补取、按什么顺序回退。
 *
 * ## 从 PreviewModal 抽出来的原因
 * 音乐页（`views/media/MediaAudioView.vue`）需要同一套兜底能力，但**只需要其中两项**：
 *  · 内嵌歌词（ID3 USLT / FLAC LYRICS）—— core 的 `/api/media/lyrics` 明确只读旁挂文件，
 *    注释里写了"不在 core 里塞 ID3 解析器"，所以这条只能前端做；
 *  · 同目录 sidecar 封面（`cover.jpg` / `folder.jpg`）—— core 的 `/api/media/cover`
 *    只看内嵌图、取不到就退缩略图，不会去扫同目录。
 *
 * **标签不在复用范围里**：core 有 `/api/media/tags`（单首精确解标签），音乐页该问它，
 * 而不是在前端把同一件事解第二遍。这里保留标签探测只是给文件管理器用 ——
 * `/api/files/*` 那条路上没有等价端点。
 *
 * ## 所有失败都静默
 * 这一整套是"锦上添花"：拿不到标签就显示文件名，拿不到封面就显示占位符。
 * 任何一环冒泡成异常都会把"预览失败"这个更严重的结论推给用户。
 */
import { useAppStore } from '@/stores/app';
import { authHeaders } from './filesShared';
import {
  extractAudioArtwork,
  extractAudioLyrics,
  extractAudioTags,
  decodeTextBytes,
  flacArtworkRequiredBytes,
  id3TagBodySize,
  parseLyrics,
  type Id3TextTags,
} from './id3Lyrics';

/**
 * 头部探测长度：文本标签几乎总在这段里，够小以免每首歌都白拉几百 KB。
 */
const HEAD_PROBE_BYTES = 64 * 1024;

/**
 * 为拿完整封面而追加读取的**硬上限**。
 *
 * 注意这不是「猜要读多少」——需要读多少由 `flacArtworkRequiredBytes`（FLAC）或
 * `10 + tagSize`（ID3）**精确算出**。这个常量只用来否决"大到不值得为预览拉"的情况
 * （无损专辑的内嵌封面能到好几 MB）。超过就干脆不取，交给 sidecar 图或占位符 ——
 * **取一半比不取更糟**：截断的 JPEG 会渲染成上半张图 + 下半露底色。
 */
const COVER_FETCH_HARD_LIMIT_BYTES = 4 * 1024 * 1024;

/**
 * 补取内嵌歌词时的读取上限。
 *
 * 歌词是文本，即便截断也只是少几行、不会产生"半张图"那种视觉错觉，
 * 所以这里保留固定上限即可。
 */
const LYRICS_PROBE_MAX_BYTES = 1024 * 1024;

/**
 * 探测结果的接收端。
 *
 * 用回调而不是"跑完返回一个对象"：这条链最多发 3 次网络请求，
 * 标签先到就该先渲染出来，等封面补取完再一起刷会让曲名白白晚出现几百毫秒。
 */
export interface AudioMetaSink {
  /**
   * 这条链是否已经过期（用户切歌 / 关了弹窗）。每次 await 之后都要问一遍。
   *
   * 只判"弹窗还开着"不够：切到**另一首**时容器状态没变，
   * 上一首在飞的请求返回后会把封面/歌词盖到当前歌上。所以判据必须包含路径。
   */
  isStale(): boolean;
  /** 已经有封面了吗。有就不再覆盖，也跳过后续的封面探测 */
  hasCover(): boolean;
  /** 只回传**解出来的**字段，空字段不回传 —— 调用方不必区分"没解出"与"解出空串" */
  onTags(tags: Id3TextTags): void;
  /** 传出的是 objectURL，**调用方负责 revoke** */
  onCover(url: string): void;
  onLyrics(lines: Array<[number, string]>): void;
}

/**
 * 带鉴权的 Range 取字节。失败返回 null（不抛）。
 *
 * `/api/files/stream` 在 `/api` 下，要签名头，所以不能用裸 `fetch(url)`。
 */
export async function fetchRange(path: string, start: number, end: number): Promise<Uint8Array | null> {
  const uri = `/api/files/stream?path=${encodeURIComponent(path)}`;
  try {
    const res = await fetch(`${useAppStore().baseUrl || ''}${uri}`, {
      headers: { ...(await authHeaders(uri)), Range: `bytes=${start}-${end}` },
    });
    if (!res.ok && res.status !== 206) return null;
    return new Uint8Array(await res.arrayBuffer());
  } catch {
    return null;
  }
}

function applyBytes(bytes: Uint8Array, sink: AudioMetaSink) {
  // 统一入口按**魔数**分流 ID3 / FLAC（不看扩展名，否则 .m4a/.ogg 连尝试的机会都没有）
  sink.onTags(extractAudioTags(bytes));
  if (sink.hasCover()) return;
  const art = extractAudioArtwork(bytes);
  if (art && art.data.length > 0) {
    sink.onCover(URL.createObjectURL(new Blob([art.data], { type: art.mime })));
  }
}

/**
 * 标签 + 内嵌封面。
 *
 * 先读 64KB 头；封面没拿到时**按容器算出"还需要读到第几字节"**再补一发，而不是猜上限。
 * 猜小了会拿到被截断的图片数据 —— 浏览器对半截 JPEG 只渲染上半部分，
 * 下半露出容器底色，看起来就是「封面下半被一层随深浅模式变色的遮罩挡住」。
 *  · FLAC：PICTURE block 头里有精确数据长度，能算出绝对终点；
 *  · ID3：APIC 在 tag 内，读满 `10 + tagSize` 就一定完整。
 */
export async function probeTagsAndCover(path: string, fileSize: number | undefined, sink: AudioMetaSink) {
  const head = await fetchRange(path, 0, HEAD_PROBE_BYTES - 1);
  if (!head || sink.isStale()) return;
  applyBytes(head, sink);

  if (sink.hasCover()) return;
  const flacNeed = flacArtworkRequiredBytes(head);
  const tagSize = id3TagBodySize(head);
  const wanted = flacNeed ?? (tagSize != null ? 10 + tagSize : null);
  if (wanted == null || wanted <= head.length) return;
  // 封面大到不值得为预览拉（多为无损专辑的超大内嵌图）：不取胜过取一半
  if (wanted > COVER_FETCH_HARD_LIMIT_BYTES) return;
  const end = Math.min(wanted - 1, (fileSize ?? wanted) - 1);
  if (end < head.length) return;
  const more = await fetchRange(path, 0, end);
  if (more && !sink.isStale()) applyBytes(more, sink);
}

/**
 * 同目录 sidecar 封面。
 *
 * 无内嵌封面的文件（大量 FLAC、外挂封面的整轨）在同目录会躺着一张图。
 * 候选顺序照常见播放器：同名图优先（`歌名.jpg`），再看整张专辑共用的 `cover`/`folder`/`front`。
 *
 * 先用 `Range: bytes=0-0` 花 1 个字节探存在、命中后才整取 —— 盲目整取会在
 * 每次打开一首没封面的歌时白拉 5 次几百 KB。
 */
export async function probeSidecarCover(path: string, sink: AudioMetaSink) {
  if (sink.hasCover()) return;
  const slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
  const dir = slash >= 0 ? path.slice(0, slash) : '';
  const base = path.replace(/\.[^.\\/]+$/, '');
  const candidates: string[] = [];
  for (const ext of ['jpg', 'jpeg', 'png', 'webp']) candidates.push(`${base}.${ext}`);
  for (const name of ['cover.jpg', 'cover.png', 'folder.jpg', 'front.jpg']) {
    candidates.push(dir ? `${dir}/${name}` : name);
  }
  for (const candidate of candidates) {
    if (sink.isStale() || sink.hasCover()) return;
    const probe = await fetchRange(candidate, 0, 0);
    if (!probe || probe.length === 0) continue;
    const uri = `/api/files/stream?path=${encodeURIComponent(candidate)}`;
    try {
      const res = await fetch(`${useAppStore().baseUrl || ''}${uri}`, { headers: await authHeaders(uri) });
      if (!res.ok) continue;
      const blob = await res.blob();
      if (sink.isStale() || sink.hasCover()) return;
      sink.onCover(URL.createObjectURL(blob));
      return;
    } catch {
      // 单个候选失败就试下一个：这条链路是"锦上添花"，任何失败都不该冒泡成预览失败
    }
  }
}

/**
 * 同目录同名 `.lrc`。返回是否命中。
 *
 * 取字节而非 `res.text()`：后者按 Content-Type 的 charset（octet-stream → UTF-8）硬解，
 * GBK 编码的 .lrc 会整篇变乱码。与 app 端 `decodeTextBytes` 同一口径。
 */
export async function probeSiblingLrc(path: string, sink: AudioMetaSink): Promise<boolean> {
  if (!path.includes('.')) return false;
  const lrcPath = path.replace(/\.[^.]+$/, '') + '.lrc';
  if (lrcPath === path) return false;
  const uri = `/api/files/stream?path=${encodeURIComponent(lrcPath)}`;
  try {
    const res = await fetch(`${useAppStore().baseUrl || ''}${uri}`, { headers: await authHeaders(uri) });
    if (!res.ok) return false;
    const text = decodeTextBytes(new Uint8Array(await res.arrayBuffer()));
    if (sink.isStale()) return false;
    const parsed = parseLyrics(text);
    if (!parsed.length) return false;
    sink.onLyrics(parsed);
    return true;
  } catch {
    return false;
  }
}

/**
 * 内嵌歌词（ID3 USLT/SYLT 或 FLAC LYRICS）。返回是否命中。
 *
 * 顺带把头部的标签/封面也应用一次 —— 这一发 64KB 已经取了，白扔掉可惜。
 */
export async function probeEmbeddedLyrics(
  path: string,
  fileSize: number | undefined,
  sink: AudioMetaSink
): Promise<boolean> {
  const head = await fetchRange(path, 0, HEAD_PROBE_BYTES - 1);
  if (!head || sink.isStale()) return false;
  applyBytes(head, sink);

  const fromHead = extractAudioLyrics(head);
  if (fromHead) {
    const parsed = parseLyrics(fromHead);
    if (parsed.length) {
      sink.onLyrics(parsed);
      return true;
    }
  }

  // 头部没有：ID3 有显式 tag 长度可用，FLAC 没有（VORBIS_COMMENT 是独立 block）→ 用固定上限兜
  const tagSize = id3TagBodySize(head);
  const wanted = tagSize != null ? tagSize : LYRICS_PROBE_MAX_BYTES;
  if (wanted <= HEAD_PROBE_BYTES) return false;
  const end = Math.min(wanted - 1, (fileSize ?? wanted) - 1, LYRICS_PROBE_MAX_BYTES - 1);
  const full = await fetchRange(path, 0, end);
  if (!full || sink.isStale()) return false;
  const fromFull = extractAudioLyrics(full);
  if (!fromFull) return false;
  const parsed = parseLyrics(fromFull);
  if (!parsed.length) return false;
  sink.onLyrics(parsed);
  return true;
}

/**
 * 全套探测（文件管理器用）。回退顺序与 app 端 `FilePreviewOverlay` 对齐：
 *  · 标题/艺人/专辑：内嵌标签 → 文件名兜底（UI 层）
 *  · 封面：内嵌图 → 同目录 sidecar 图 → 占位符
 *  · 歌词：同目录 `.lrc` → 内嵌 → 「暂无歌词」
 */
export async function probeAudioMeta(path: string, fileSize: number | undefined, sink: AudioMetaSink) {
  await probeTagsAndCover(path, fileSize, sink);
  // 内嵌封面没有才去同目录找：大量 FLAC / 整轨都是外挂封面
  await probeSidecarCover(path, sink);
  if (await probeSiblingLrc(path, sink)) return;
  await probeEmbeddedLyrics(path, fileSize, sink);
}

<!--
  统一视频播放器。媒体页与文件预览两处共用。

  ## 组件职责
  组件集中承担三件事：格式分档、错误文案、ArtPlayer `customType` 注册。
  这三件事若留在各自的调用方 SFC 里必然产生行为分叉；此前两处各自渲染裸
  `<video :src>`，既不判格式也不监听 `error`，无法播放的文件统一表现为无提示的黑框。

  ## 三档处置（判据全在 composables/videoFormat.ts）
  - **unsupported**：**不创建播放器**，直接显示原因卡片与下载入口。
    mkv/avi/rmvb 等容器不被解码管线接受，且服务端转码不可用（预编译 ffmpeg .so 按
    16KB page size 链接，与设备的 4KB 页不兼容），因此"先试播一次"只会得到黑框。
  - **custom**：flv/m3u8/mpd/ts，动态 `import()` 对应解复用库喂给 MSE。
  - **native**：交由浏览器解码，**同时监听 error 兜底** ——
    容器可识别不代表内部编码可解码（HEVC / AC3 / ProRes 均在该档失败）。

  ## 两个实现约束
  1. **必须显式指定 `type`**：播放地址是 `/media/stream?ticket=…`，没有文件后缀，
     ArtPlayer 无法按后缀推断格式（其文档明确说明 `?type=` 形式无法解析）。
  2. **`id` 必须是稳定值**：播放位置记忆（`autoPlayback`）默认以 `url` 作为缓存 key，
     而该 url 每次都携带**新票据** —— 不提供 `id` 时每次都是新 key，记忆永不命中。
     此处以设备上的文件路径作为 `id`。

  ## 起播失败
  unsupported 档由 `canPlayType` 判定；native / custom 档依靠 `video:error` 兜底。
  Chromium 对部分 MKV 不发出 error，只发出 `stalled`，且 `readyState` 恒为 0，
  表现为无限加载，与网络缓慢无法区分。因此 **native 档**额外监听 `video:stalled`
  并给 15 秒宽限（见 [STALL_GRACE_MS]）：宽限期内没有出现任何可播数据即按
  "加载超时"处理（**不**断言容器不支持），给出明确文案与下载入口。
  `error.code === 3/4` 的文案同样定位到容器层（见 [runtimeErrorText]）。

  ## 外挂字幕
  三条路径**均先用 axios 取出文本**，因为 `/api/media/subtitle` 需要验签，
  而播放器内部发起的 fetch 无法携带签名头（详见 [fetchSubtitleText]）：
  - vtt / srt → 文本包成 blob URL 交给 `art.subtitle.switch`；
  - ass / ssa → 文本直接交给 assjs（直接使用该库而非 artplayer 插件包，原因见 [assInstance]）；
  - 其余（ttml/sub/smi…）→ 在选择器里列出并标注不支持（判据在 composables/subtitleFormat.ts）。
-->
<template>
  <!-- unsupported 档：不创建播放器，只显示原因与下载入口 -->
  <div v-if="tier === 'unsupported'" class="vp-unsupported">
    <n-icon :size="40" color="var(--text-muted)"><VideocamOffOutline /></n-icon>
    <p class="vp-reason">{{ unsupportedReason }}</p>
    <n-button v-if="canDownload" size="small" @click="emit('download')">下载此文件</n-button>
  </div>

  <!-- 运行时失败（native / custom 档共用）：容器已识别但解码失败，或网络中断 -->
  <div v-else-if="runtimeError" class="vp-unsupported">
    <n-icon :size="40" color="var(--text-muted)"><VideocamOffOutline /></n-icon>
    <p class="vp-reason">{{ runtimeError }}</p>
    <div class="vp-actions">
      <n-button size="small" quaternary @click="retry">重试</n-button>
      <n-button v-if="canDownload" size="small" @click="emit('download')">下载此文件</n-button>
    </div>
  </div>

  <!-- ArtPlayer 的挂载容器。它会在内部自己建 <video>，所以这里只给一个空 div -->
  <div v-else ref="hostEl" class="vp-host" />
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue';
import { VideocamOffOutline } from '@vicons/ionicons5';
import {
  artplayerTypeOf,
  mediaErrorTextOf,
  unsupportedReasonOf,
  videoExtOf,
  videoTierOf,
  type VideoTier,
} from '@/composables/videoFormat';
import { cssVar } from '@/composables/cssVar';
import { isWebPlayable, renderOf, subtitleDisplayName, type SubtitleEntry } from '@/composables/subtitleFormat';
// 只取类型，不进运行时 bundle —— assjs 本体是懒加载的（见 applySubtitle）
import type ASS from 'assjs';
import {
  patchVideoPrefs,
  readVideoPrefs,
  recallSubtitle,
  rememberSubtitle,
  shadowCssOf,
  type SubtitleShadow,
} from '@/composables/videoPlayerPrefs';
import { getApiClient } from '@/composables/useApi';
import { Endpoints } from '@/api/contract';

const props = withDefaults(
  defineProps<{
    /** 票据播放地址（`/media/stream?ticket=…`）。空串表示还在换票据 */
    url: string;
    /** 文件名，**格式判定的唯一依据**（url 没有后缀） */
    name: string;
    /**
     * 设备上的文件路径。用作播放位置记忆的稳定 key ——
     * 不能用 url，它每次带的票据都不同（见文件头说明）。
     */
    path?: string;
    /** 封面（未播放时显示）。媒体页有缩略图 blob URL 可传 */
    poster?: string;
    /** 是否提供"下载此文件"按钮 */
    canDownload?: boolean;
    /** 自动播放。弹窗里点开视频通常希望直接放 */
    autoplay?: boolean;
  }>(),
  { path: '', poster: '', canDownload: false, autoplay: true }
);

const emit = defineEmits<{ download: []; error: [message: string] }>();

// ArtPlayer 的 container 类型要求 HTMLDivElement（不是宽泛的 HTMLElement）
const hostEl = ref<HTMLDivElement | null>(null);
/**
 * ArtPlayer 实例。此处使用 `any`：其类型声明将 `customType` 处理函数的签名约束为
 * 同步返回 void，而本组件的处理函数必须为 async（需动态 import 解复用库）。
 * 逐处类型断言的维护成本高于收益。
 */
let art: any = null;
/** custom 档解复用器实例，销毁时必须一并回收，否则 MSE buffer 与 worker 泄漏 */
let demuxer: any = null;
/**
 * assjs 渲染实例（ass/ssa 字幕用）。与 [demuxer] 同理，销毁时必须回收。
 *
 * **直接使用 assjs，不使用 `artplayer-plugin-assjs`**：该插件的工厂函数写作
 * `show: ass.show(), hide: ass.hide()`，两处均为**立即调用**而非方法引用，
 * 构造时先 show 再 hide，字幕层随即被隐藏（表现为字幕请求返回 200 但播放器上没有输出）；
 * 且这两个属性并非函数，无法在运行时补救。assjs 自身的 `show()` / `hide()` /
 * `destroy()` 行为正确。
 *
 * 该实例**懒加载**：仅在遇到 ass 字幕时才 `import('assjs')`，
 * 只播放 srt 的会话不会下载这 50KB。
 */
let assInstance: ASS | null = null;
/**
 * 字幕请求的代数。
 *
 * [applySubtitle] 中间有一次 await（取文本）与一次 await（懒加载 assjs），
 * 连续切换两条字幕时两次调用会交错，先发起的请求后返回时会覆盖后发起的结果。
 * 每次进入自增，返回时不是最新一代即丢弃。
 */
let subtitleSeq = 0;
/**
 * 播放器创建的代数。与 [subtitleSeq] 同一套路。
 *
 * [createPlayer] 中 `art` 要等 `nextTick` 与 `import('artplayer')` 两次 await 之后才赋值，
 * watch(url/name) 在短时间内触发两次时，后一次开头的 destroyPlayer 读到的 art 仍为 null ——
 * 先完成的实例被覆盖且永不销毁，残留 video 元素、事件监听与解复用器
 * （表现为两层画面或两路声音）。每个 await 之后与实例化之后都校验代数，非最新代就地销毁。
 */
let createSeq = 0;
/**
 * 当前挂载的字幕走哪条渲染路径，空串 = 没挂。
 *
 * 用途只有一个：字幕外观设置只能作用于 ArtPlayer 内建字幕层（vtt/srt），
 * ass 的字号、颜色与位置由字幕文件内定义、由 assjs 按其渲染。用户在 ass 轨道上
 * 调整外观时需**当场说明该设置为何不生效**，避免误判为开关失效。
 */
let activeRender: '' | 'vtt' | 'srt' | 'ass' = '';

const runtimeError = ref('');
/**
 * 字幕文本生成的 blob URL 列表，销毁时统一回收。
 *
 * 不回收的话每换一次字幕就漏一份文本（blob URL 会一直持有 Blob，浏览器不自动收）。
 */
const subtitleBlobUrls: string[] = [];

const tier = computed<VideoTier>(() => videoTierOf(props.name));
/**
 * 已知容器名的显示名称。仅用于把失败原因**定位到容器层**：
 * `canPlayType` 判定不可用、`error.code === 4`、起播持续 stalled，这三种情况都需要
 * 显示"浏览器不支持 MKV 容器"而非笼统的"不支持该视频" —— 前者能直接指向
 * "更换容器 / 更换浏览器 / 下载"，后者只会让人怀疑文件损坏。
 */
const CONTAINER_LABELS: Record<string, string> = {
  mkv: 'MKV',
  avi: 'AVI',
  wmv: 'WMV',
  asf: 'ASF',
  rm: 'RM',
  rmvb: 'RMVB',
  vob: 'VOB',
  mpg: 'MPEG',
  mpeg: 'MPEG',
  divx: 'DivX',
  mov: 'MOV',
};

/** 容器层面的劝退文案；文件名不在表里时返回空串（调用方退回通用文案）。 */
function containerUnsupportedText(name: string): string {
  const label = CONTAINER_LABELS[videoExtOf(name)];
  if (!label) return '';
  return `浏览器不支持 ${label} 容器，请下载后用本地播放器播放。`;
}

/**
 * unsupported 档的文案。优先说明容器（「浏览器不支持 MKV 容器…」），
 * 表中没有的扩展名才退回 `videoFormat` 中的通用说法。
 */
const unsupportedReason = computed(() => containerUnsupportedText(props.name) || unsupportedReasonOf(props.name));

/**
 * `error.code` 对应的文案。`mediaErrorTextOf` 负责把码值翻译成说明，此处在其之前
 * 增加一层容器 / 编码判据：
 *  · code 4（SRC_NOT_SUPPORTED）：与 `canPlayType` 判定不可用属同一类失败 —— 直接说明容器；
 *  · code 3（DECODE）：容器可解析、内部编码无法解码 —— 同时给出容器名与常见编码，
 *    使用户能判断"并非文件损坏，而是浏览器不支持该编码"。
 */
function runtimeErrorText(err: MediaError | null | undefined): string {
  const ext = videoExtOf(props.name).toUpperCase();
  if (err?.code === 4) {
    const label = CONTAINER_LABELS[videoExtOf(props.name)];
    // 票据过期时 core 也会回 4xx，浏览器同样报 code 4 —— 所以不把"不支持容器"
    // 说成唯一原因（卡片上有「重试」按钮，会换一张新票据）
    if (label) {
      return `浏览器无法播放该 ${label} 文件（容器或内部编码不受支持，也可能是播放票据已过期）。可先点「重试」换一张票据；仍不行就下载后用本地播放器播放。`;
    }
    return mediaErrorTextOf(err, props.name);
  }
  if (err?.code === 3) {
    return `${ext ? `${ext} 容器能识别，但` : ''}视频内部的编码（如 H.265 / ProRes）或音轨（AC3 / DTS）可能不被当前浏览器支持，请下载后用本地播放器播放。`;
  }
  return mediaErrorTextOf(err, props.name);
}

/**
 * stalled 的宽限期（毫秒）。
 *
 * 播放地址是局域网直连，正常文件应在 1–3 秒内触发 `loadedmetadata`。而容器或编码
 * 不被接受时，Chromium 通常不发出 error，只发出 `stalled` 且 `readyState` 停在 0，
 * 无限加载与"网络缓慢"的外观完全一致。15 秒为保守取值：网络确实缓慢时 `readyState`
 * 会推进（计时随即被清除），到达该时长仍无任何数据解出，更可能是格式问题。
 */
const STALL_GRACE_MS = 15000;
let stallTimer: number | null = null;
function clearStallTimer() {
  if (stallTimer !== null) {
    window.clearTimeout(stallTimer);
    stallTimer = null;
  }
}

/**
 * 回收 assjs 实例。
 *
 * assjs **没有"更换内容"的 API**，切换字幕轨同样经由此处：destroy 会移除它挂在 video 上的
 * 5 个事件监听、停止 rAF 循环、移除字幕层与 ResizeObserver，之后重新构造实例。
 * 必须在 `art.destroy()` **之前**调用 —— 后者销毁时需要访问 video 元素以摘除监听。
 */
function destroyAss() {
  if (!assInstance) return;
  try {
    assInstance.destroy();
  } catch {
    /* 已经销毁过，忽略 */
  }
  assInstance = null;
}

function destroyPlayer() {
  // 起播宽限计时必须在销毁播放器之前停止：否则换片或关闭页面后计时器仍会触发，
  // 对一条已不存在的流报"不支持"（art 为 null 时有守卫，但不应保留这类悬挂计时器）
  clearStallTimer();
  // 必须先回收解复用器再销毁播放器：反序时 demuxer 仍挂在一个已解绑的 video 上，
  // 部分版本会抛异常
  if (demuxer) {
    try {
      demuxer.destroy();
    } catch {
      /* 已经销毁过，忽略 */
    }
    demuxer = null;
  }
  // assjs 持有 DOM 节点、rAF 循环与 video 事件监听，必须在 art 之前显式回收
  destroyAss();
  // 使在途的字幕请求失效，否则其返回时会向已销毁的 art 挂载渲染实例
  subtitleSeq += 1;
  activeRender = '';
  if (art) {
    // 音量在此处持久化，而非监听 volumechange：拖动音量条会连续触发数十次事件，
    // 无需每次写入 localStorage；销毁播放器时读取最终值即可
    if (typeof art.volume === 'number') patchVideoPrefs({ volume: art.volume });
    try {
      // 入参 true 表示同时移除 DOM；省略时容器里会残留空壳，换片时层层叠加
      art.destroy(true);
    } catch {
      /* 同上 */
    }
    art = null;
  }
  // 字幕文本的 blob URL：未 revoke 时会持续持有整份文本
  while (subtitleBlobUrls.length > 0) {
    const u = subtitleBlobUrls.pop();
    if (u) URL.revokeObjectURL(u);
  }
}

/** 动态加载 FLV 解复用器；仅在遇到 flv/ts 时加载该分块。 */ async function attachMpegts(
  video: HTMLVideoElement,
  url: string,
  isFlv: boolean
) {
  const mod = await import('mpegts.js');
  const mpegts = mod.default ?? mod;
  if (!mpegts.isSupported()) throw new Error('当前浏览器不支持 MSE，无法播放该格式');
  const player = mpegts.createPlayer({ type: isFlv ? 'flv' : 'mpegts', isLive: false, url });
  player.attachMediaElement(video);
  player.load();
  demuxer = player;
}

/** 动态加载 HLS。Safari 原生支持 m3u8，优先使用原生实现（省去一个 150KB 的分块）。 */
async function attachHls(video: HTMLVideoElement, url: string) {
  if (video.canPlayType('application/vnd.apple.mpegurl')) {
    video.src = url;
    return;
  }
  const mod = await import('hls.js');
  const Hls = mod.default ?? mod;
  if (!Hls.isSupported()) throw new Error('当前浏览器不支持 MSE，无法播放 HLS');
  const hls = new Hls();
  hls.loadSource(url);
  hls.attachMedia(video);
  demuxer = hls;
}

async function attachDash(video: HTMLVideoElement, url: string) {
  // 未引入 dash.js：DASH 在设备本地文件中极少出现（其形态为分片加清单的流式格式）。
  // 遇到时如实报错，不以静默黑框代替。
  void video;
  void url;
  throw new Error('暂不支持 DASH（.mpd）播放，请下载后用本地播放器打开');
}

// ──────────────────────── 外挂字幕 ────────────────────────

/**
 * 拉这个视频的字幕列表。
 *
 * **分两次取列表是有意设计**（core 的 `scope` 参数即为此提供）：
 *  1. 先 `scope=matched` —— 只有按文件名判定属于本视频的才可能被**自动挂载**；
 *  2. matched 为空时再 `scope=folder` 补一份列表，但**不自动挂** ——
 *     同目录里 `other.srt` 不属于本片，自动挂上就是挂错字幕。
 *     这种情况交给用户在设置菜单里手动选。
 *
 * 失败一律静默：字幕是增强项，拉不到不该影响放视频。
 */
async function loadSubtitles(): Promise<SubtitleEntry[]> {
  if (!props.path) return [];
  const api = getApiClient();
  const fetchScope = async (scope: 'matched' | 'folder') => {
    const { data } = await api.get(Endpoints.media.subtitles, {
      params: { path: props.path, scope },
    });
    return (data?.items ?? []) as SubtitleEntry[];
  };
  try {
    const matched = await fetchScope('matched');
    if (matched.length > 0) return matched;
    return await fetchScope('folder');
  } catch (err) {
    // 字幕是增强项，拉不到不该影响放视频；但要留痕，否则"没有字幕"和"请求失败"
    // 在界面上是同一个样子（媒体权限没给时这里会吃到 403，见 core 的 isGranted 闸门）
    console.warn('[VideoPlayer] 字幕列表获取失败', err);
    return [];
  }
}

/**
 * 取字幕**文本**（不是 URL）。
 *
 * ## 必须由本组件取文本
 * `/api/media/subtitle` 位于 `/api` 下，core 的 AuthMiddleware **强制**校验
 * `X-Timestamp` / `X-Nonce` / `X-Signature`，缺一即返回 444。而 `art.subtitle.switch(url)`
 * 由 ArtPlayer 自行发起 fetch，**无法携带这些请求头**（与 `<img src>` 取缩略图、
 * `<video src>` 取视频流属同一类限制，那两处分别以 blob URL 与票据解决）。
 *
 * ## 必须使用相对路径
 * axios 实例在拦截器里把 `config.baseURL` 设为 `appStore.baseUrl`，并以
 * `client.getUri(config)` 计算签名 URI。此处若传入**已包含 baseUrl 的完整 URL**，
 * 会被拼接为 `baseUrl + baseUrl + path`，签名 URI 不匹配，同样返回 444。
 */
async function fetchSubtitleText(entry: SubtitleEntry): Promise<string> {
  const { data } = await getApiClient().get(Endpoints.media.subtitle, {
    params: { path: entry.path },
    // 字幕是纯文本；不拦住的话 axios 会尝试 JSON.parse，srt/ass 直接解析失败
    responseType: 'text',
    transformResponse: [(d) => d],
  });
  return typeof data === 'string' ? data : '';
}

/**
 * 把字幕文本转换为 **blob URL** 交给 ArtPlayer。
 *
 * blob URL 是本地资源，不经过网络、不需要鉴权 —— 这是绕过上述鉴权限制的方式。
 * 生成的 URL 登记在 [subtitleBlobUrls] 中，销毁播放器时统一回收（不回收会持续持有文本）。
 */
function subtitleBlobUrl(text: string, mime: string): string {
  const url = URL.createObjectURL(new Blob([text], { type: mime }));
  subtitleBlobUrls.push(url);
  return url;
}

/**
 * 挂载一条字幕。`entry` 为 null = 关闭字幕。
 *
 * 两条路径，但**都先取出文本**（见 [fetchSubtitleText] 的说明）：
 *  · vtt / srt → 包成 blob URL 给 `art.subtitle.switch`（srt→vtt 由 ArtPlayer 内部转）；
 *  · ass / ssa → 文本直接喂 assjs（它吃字符串不吃 URL），assjs 懒加载。
 */
async function applySubtitle(entry: SubtitleEntry | null) {
  if (!art) return;
  const seq = ++subtitleSeq;
  if (!entry) {
    art.subtitle.show = false;
    destroyAss();
    activeRender = '';
    // 空串表示"用户主动关闭"，与"没有记录"是两种状态（见 videoPlayerPrefs 的说明）；
    // 否则下次打开会被自动重新挂载，字幕将无法保持关闭
    rememberSubtitle(props.path, '');
    return;
  }
  const render = renderOf(entry);
  if (render === 'unsupported') return;
  try {
    const text = await fetchSubtitleText(entry);
    // 返回时用户可能已切换字幕或关闭弹窗；仅最后一次点击的结果有效
    if (!text || !art || seq !== subtitleSeq) return;

    if (render === 'ass') {
      // assjs 不支持原地更换内容，切换轨即重建实例
      destroyAss();
      const ASSRenderer = (await import('assjs')).default;
      if (!art || seq !== subtitleSeq) return;
      assInstance = new ASSRenderer(text, art.video, {
        // $player 是 ArtPlayer 的播放器根节点（自带 position: relative，assjs 要求如此），
        // 也是全屏/网页全屏时真正被放大的那个元素 —— 字幕层跟着它走，不用额外处理全屏。
        // resampling 用库默认的 video_height：字幕组的 ASS 绝大多数 PlayResX/Y 与视频同比例
        // （如 1280x720），此时四种取值等价；只有比例不一致时才有差别，没有依据认为哪种更对
        container: art.template.$player,
      });
      // assjs 的渲染循环由 video 的 play/playing 事件启动，而该实例是在 `ready` 之后、
      // 又经过一次网络请求才构造的 —— 此时视频**已在播放**，这两个事件不会再次触发，
      // 结果只有构造瞬间渲染的一帧字幕。若视频已在播放，则补发一次事件以启动 rAF 循环。
      if (!art.video.paused) art.video.dispatchEvent(new Event('playing'));
      // ass 由 assjs 自己画，ArtPlayer 内建字幕层必须关掉，否则两层字幕叠在一起
      art.subtitle.show = false;
    } else {
      destroyAss();
      // srt 也用 text/vtt 的 MIME：ArtPlayer 靠 `type` 选解析器，MIME 只影响 Blob 自身
      await art.subtitle.switch(subtitleBlobUrl(text, 'text/vtt'), {
        name: subtitleDisplayName(entry),
        type: render, // 'vtt' | 'srt'
      });
      if (!art || seq !== subtitleSeq) return;
      art.subtitle.show = true;
    }
    activeRender = render;
    rememberSubtitle(props.path, entry.path, entry.label ?? '');
  } catch (err) {
    // 字幕失败不影响视频播放，但**不得静默**：空 catch 会吞掉 assjs 解析大型 ASS 文件时
    // 的真实异常，表现为请求返回 200 而界面无任何变化，无法定位。
    console.error('[VideoPlayer] 字幕挂载失败', entry.path, err);
    if (art) {
      art.notice.show = `字幕加载失败：${err instanceof Error ? err.message : String(err)}`;
    }
  }
}

/**
 * 决定打开视频时自动挂哪条字幕。`null` = 不挂。
 *
 * 三级优先，前一级命中即不再向下判断：
 *  1. **该视频上次选中的字幕** —— 信号最强，属于用户的显式选择；
 *     记录为空串表示用户上次主动关闭，此处必须返回 null（否则无法保持关闭）。
 *  2. **全局语言偏好**（上次选中字幕的 `label`，如 `SC` / `TC`）——
 *     同一系列的多集内容只需选择一次语言，无需逐集设置。
 *  3. core 排序中的第一条可用字幕（core 已将"可解析的"排在前面）。
 */
function pickAutoSubtitle(list: SubtitleEntry[]): SubtitleEntry | null {
  const playable = list.filter(isWebPlayable);
  if (playable.length === 0) return null;
  const remembered = recallSubtitle(props.path);
  if (remembered === '') return null;
  if (remembered) {
    const hit = playable.find((e) => e.path === remembered);
    if (hit) return hit;
    // 记录的那条已被删除或改名 —— 继续走默认逻辑，不因此放弃挂载
  }
  const lang = readVideoPrefs().subtitleLang.trim().toLowerCase();
  if (lang) {
    const hit = playable.find((e) => (e.label ?? '').trim().toLowerCase() === lang);
    if (hit) return hit;
  }
  return playable[0]!;
}

/**
 * 把字幕外观偏好写到播放器上。
 *
 * 字号与位置都走 **ArtPlayer 自己的 CSS 变量**，而不是给 `.art-subtitle` 设内联
 * `font-size`/`bottom`：那两个变量在 `.art-video-player` 上只定义了一处，
 * 而 `bottom` 还参与了"控制条显示时上移 `--art-control-height`"的 calc
 * （见 artplayer 的 `.art-control-show .art-subtitle`）。覆盖变量能保住这个联动，
 * 写死内联值会把它压掉、控制条弹出时字幕被挡。
 *
 * 描边没有变量，只能直接覆盖 `text-shadow`。
 */
function applySubtitleStyle() {
  if (!art) return;
  const { size, bottom, shadow } = readVideoPrefs().subtitleStyle;
  const player = art.template.$player as HTMLElement;
  player.style.setProperty('--art-subtitle-font-size', `${size}px`);
  player.style.setProperty('--art-subtitle-bottom', `${bottom}px`);
  art.subtitle.style('textShadow', shadowCssOf(shadow));
}

/** ass 轨道下调整外观不会产生任何效果，需当场说明原因，避免用户误判为设置失效。 */
function noticeIfAssActive() {
  if (art && activeRender === 'ass') {
    art.notice.show = 'ASS/SSA 字幕的字号与位置写在字幕文件里，由字幕组决定，此设置不影响它';
  }
}

/**
 * 把字幕做成播放器设置面板里的一个选择器。
 *
 * 不支持的格式**同样列出但加以标注**（`subtitleDisplayName` 会在名称里写"（XXX 不支持）"）——
 * 与 core 对 `supported: false` 仍返回条目同理：让用户看到"文件存在，但该格式无法渲染"，
 * 避免对目录中确实存在的 .ttml 文件产生误判。选中该项等同于"关闭字幕"。
 */
function buildSubtitleSetting(list: SubtitleEntry[], auto: SubtitleEntry | null) {
  if (!art || list.length === 0) return;
  const playable = list.filter(isWebPlayable);
  art.setting.add({
    name: 'subtitle-track',
    html: '字幕',
    tooltip: playable.length > 0 ? '选择字幕' : '无可用字幕',
    selector: [
      { html: '关闭', default: !auto, entry: null },
      ...list.map((e) => ({
        html: subtitleDisplayName(e),
        default: !!auto && e.path === auto.path,
        entry: isWebPlayable(e) ? e : null,
      })),
    ],
    onSelect: (item: { html: string; entry: SubtitleEntry | null }) => {
      void applySubtitle(item.entry);
      // 返回值会显示在设置项右侧（ArtPlayer 约定）
      return item.html;
    },
  });
}

/**
 * 字幕外观三项：字号 / 位置 / 描边。
 *
 * **仅对 vtt/srt 生效**，并在标题中写明 —— ass 的样式由字幕文件定义、由字幕组决定，
 * 这是 ASS 格式的本意，不应被播放器覆盖（app 端采用相反取舍：
 * `setApplyEmbeddedStyles(false)` 丢弃 ASS 内嵌样式，统一为白字黑边；两端有意不同）。
 * 用户在 ass 轨道上调整这些项时，[noticeIfAssActive] 会当场说明原因。
 *
 * 该项始终添加，不随当前字幕轨类型变化：用户可能先挂 ass，再切换到同目录的 srt，
 * 设置项不能中途出现或消失。
 */
function buildSubtitleStyleSetting() {
  if (!art) return;
  const cur = readVideoPrefs().subtitleStyle;
  const sizes = [
    { html: '小', value: 16 },
    { html: '标准', value: 20 },
    { html: '大', value: 26 },
    { html: '超大', value: 32 },
  ];
  const bottoms = [
    { html: '贴底', value: 4 },
    { html: '标准', value: 15 },
    { html: '偏上', value: 40 },
    { html: '更上', value: 80 },
  ];
  const shadows: { html: string; value: SubtitleShadow }[] = [
    { html: '无', value: 'none' },
    { html: '弱', value: 'light' },
    { html: '强', value: 'strong' },
  ];

  art.setting.add({
    name: 'subtitle-size',
    html: '字幕字号',
    tooltip: '仅 vtt/srt',
    selector: sizes.map((s) => ({ ...s, default: s.value === cur.size })),
    onSelect: (item: { html: string; value: number }) => {
      patchVideoPrefs({ subtitleStyle: { ...readVideoPrefs().subtitleStyle, size: item.value } });
      applySubtitleStyle();
      noticeIfAssActive();
      return item.html;
    },
  });
  art.setting.add({
    name: 'subtitle-bottom',
    html: '字幕位置',
    tooltip: '仅 vtt/srt',
    selector: bottoms.map((s) => ({ ...s, default: s.value === cur.bottom })),
    onSelect: (item: { html: string; value: number }) => {
      patchVideoPrefs({ subtitleStyle: { ...readVideoPrefs().subtitleStyle, bottom: item.value } });
      applySubtitleStyle();
      noticeIfAssActive();
      return item.html;
    },
  });
  art.setting.add({
    name: 'subtitle-shadow',
    html: '字幕描边',
    tooltip: '仅 vtt/srt',
    selector: shadows.map((s) => ({ ...s, default: s.value === cur.shadow })),
    onSelect: (item: { html: string; value: SubtitleShadow }) => {
      patchVideoPrefs({ subtitleStyle: { ...readVideoPrefs().subtitleStyle, shadow: item.value } });
      applySubtitleStyle();
      noticeIfAssActive();
      return item.html;
    },
  });
}

async function createPlayer() {
  const seq = ++createSeq;
  destroyPlayer();
  runtimeError.value = '';
  if (tier.value === 'unsupported' || !props.url) return;
  await nextTick();
  if (seq !== createSeq) return;
  const host = hostEl.value;
  if (!host) return;

  const Artplayer = (await import('artplayer')).default;
  if (seq !== createSeq) return;
  const type = artplayerTypeOf(props.name);

  const instance = new Artplayer({
    container: host,
    url: props.url,
    // 显式指定 type：url 无后缀，无法推断（见文件头）
    type,
    // 稳定 key，否则位置记忆永不命中（url 每次的票据都不同）
    id: props.path || props.name,
    autoPlayback: true, // 播放位置记忆
    poster: props.poster,
    autoplay: props.autoplay,
    playsInline: true,
    // 音量跨会话记住（落盘在 destroyPlayer，见那里的注释）
    volume: readVideoPrefs().volume,
    theme: cssVar('--accent-color', '#409eff'),
    lang: 'zh-cn',
    setting: true,
    playbackRate: true,
    // 画面比例：设备上的片源比例差异很大（4:3、21:9、竖屏手机录像），
    // 而容器固定为 16/9；缺少该入口时只能接受上下或左右黑边
    aspectRatio: true,
    // 镜像/翻转：前置摄像头录制的视频左右相反，翻转后方可正常阅读画面文字
    flip: true,
    pip: true,
    fullscreen: true,
    fullscreenWeb: true,
    screenshot: true,
    hotkey: true,
    // 控制条收起后在底边保留一条细进度条，全屏播放时仍可判断进度
    miniProgressBar: true,
    // 移动端：锁定控制条以防误触，并启用左右滑动调节进度、上下滑动调节音量、长按快进
    lock: true,
    gesture: true,
    fastForward: true,
    // Safari 的隔空播放（AirPlay）；非 Safari 环境下 ArtPlayer 不渲染该按钮
    airplay: true,
    // 同一页面内只允许一个播放器出声（媒体页的音乐播放器可能同时在播放）
    mutex: true,
    // 网页全屏时按视频比例转屏
    autoOrientation: true,
    customType: {
      flv: (video: HTMLVideoElement, url: string) => attachMpegts(video, url, true),
      ts: (video: HTMLVideoElement, url: string) => attachMpegts(video, url, false),
      m3u8: (video: HTMLVideoElement, url: string) => attachHls(video, url),
      mpd: (video: HTMLVideoElement, url: string) => attachDash(video, url),
    },
  });

  if (seq !== createSeq) {
    // 期间又触发了一次重建：该实例已无引用，就地销毁（含 DOM）
    try {
      instance.destroy(true);
    } catch {
      /* 已经销毁过，忽略 */
    }
    return;
  }
  art = instance;

  // native 档的第二道兜底：容器可识别不代表编码可解码（HEVC / AC3 / ProRes 均在此失败）
  art.on('video:error', () => {
    const msg = runtimeErrorText(art?.video?.error);
    runtimeError.value = msg;
    emit('error', msg);
    // 报错后把播放器收掉：留着它只会显示一个黑框盖住上面的提示
    destroyPlayer();
  });
  // customType 处理函数内部抛出的错误（解复用器不可用 / DASH 未接入）进入此分支
  art.on('error', (err: unknown) => {
    const msg = err instanceof Error ? err.message : mediaErrorTextOf(null, props.name);
    runtimeError.value = msg;
    emit('error', msg);
    destroyPlayer();
  });

  /**
   * 起播阶段的 stalled：`error` 不一定来（见文件头「起播失败」）。
   *
   * 仅在"尚未获得任何可播数据"时计时：`readyState > 0` 或已开始播放均不计时
   * （播放中途的网络抖动属于另一类情况，由控制条的缓冲提示反映）。
   */
  art.on('video:stalled', () => {
    if (!art) return;
    // 仅对 native 档生效：custom 档（flv / hls / ts）的启动由解复用器负责，
    // MSE 首帧延迟属于常态，按时间判定"不支持"会产生误报；该档失败由 `art.on('error')` 处理。
    if (tier.value !== 'native') return;
    const v = art.video as HTMLVideoElement;
    if (v.readyState > 0 || v.currentTime > 0) return;
    // 已在计时时不重新计时：`stalled` 可能每隔数秒重复触发，
    // 每次都重置将使该宽限期永不到期。
    if (stallTimer !== null) return;
    stallTimer = window.setTimeout(() => {
      stallTimer = null;
      if (!art) return;
      const video = art.video as HTMLVideoElement;
      // 宽限期内只要解出过一帧或取得元数据，即说明容器可用，不按格式问题处理
      if (video.readyState > 0 || video.currentTime > 0) return;
      // 此处**不得**输出"浏览器不支持 MKV 容器"：容器可用性已由 `videoTierOf` +
      // `canPlayType` 判定（进入 native 档即说明容器被接受），15 秒内无数据更可能是
      // 设备读取缓慢、网络阻塞或内部编码无法解码。若在此处直接使用
      // `containerUnsupportedText()`，会把网络缓慢的 MKV 误报为容器不支持，构成错误诊断。
      const msg =
        '视频在 15 秒内没有开始播放。可能是设备读取或网络较慢，也可能是文件内部编码（如 H.265 / AC3）不被当前浏览器支持。可点「重试」再试一次，或下载后用本地播放器播放。';
      runtimeError.value = msg;
      emit('error', msg);
      destroyPlayer();
    }, STALL_GRACE_MS);
  });
  // 任何"开始有数据"的信号都取消宽限计时
  art.on('video:loadedmetadata', clearStallTimer);
  art.on('video:canplay', clearStallTimer);
  art.on('video:playing', clearStallTimer);

  // 字幕：播放器建好之后再拉。放在 ready 之后是因为 `art.setting.add` 与
  // `art.subtitle.switch` 都要求播放器已初始化完成。
  art.on('ready', async () => {
    try {
      // 先应用外观偏好：若晚于字幕挂载，会先出现一帧默认字号再跳变
      applySubtitleStyle();
      buildSubtitleStyleSetting();
      const list = await loadSubtitles();
      // 期间用户可能已关闭弹窗或切换文件
      if (!art || list.length === 0) return;
      const auto = pickAutoSubtitle(list);
      // 选择器的默认选中项必须与实际挂载的字幕一致，否则面板显示与画面内容不符
      buildSubtitleSetting(list, auto);
      if (auto) await applySubtitle(auto);
    } catch (err) {
      // 该处理函数为 async 且由 ArtPlayer 调用，向外抛出只会形成 unhandled rejection
      // （调用栈无法表明来源为字幕流程）；在此捕获并标注来源
      console.error('[VideoPlayer] 字幕流程异常', err);
    }
  });
}

function retry() {
  runtimeError.value = '';
  void createPlayer();
}

// url 或文件变化时重建实例，不复用：ArtPlayer 更换 url 时不会重新绑定 customType，
// 从 flv 切换到 mp4 会沿用上一个解复用器
watch(
  () => [props.url, props.name],
  () => void createPlayer(),
  { immediate: true }
);

onBeforeUnmount(destroyPlayer);
</script>

<style scoped>
/* 视频画布。信箱边（letterbox）必须为纯黑，与影片画面同色；
   若改用 --card-bg，暗色主题下会与卡片融为一体，浅色主题下会把画面框成白色方块。
   ui-baseline-allow: 视频画布底色，两种主题下都必须是纯黑 */
.vp-host {
  width: 100%;
  aspect-ratio: 16 / 9;
  border-radius: var(--radius-md);
  overflow: hidden;
  background: #000;
}
/* ArtPlayer 默认将 video 宽高设为 100% 且不设 object-fit，4:3 与竖屏片源会被拉伸为
   16:9。此处必须使用 contain：容器固定 16:9，非 16:9 的画面保留黑边，不做拉伸。 */
.vp-host :deep(video.art-video) {
  object-fit: contain;
  /* ui-baseline-allow: 视频信箱边必须是纯黑，不能跟随主题 */
  background: #000;
}

/*
  assjs 的字幕层。它是运行时 append 到 ArtPlayer 的 $player 里的，拿不到 scoped 属性，
  所以必须用 :deep()。

  z-index 是必要的：assjs 的 .ASS-box 自身不设 z-index，而它作为**最后一个**子节点
  插入 $player，按文档顺序会绘制在控制条（.art-bottom）之上，使字幕遮盖进度条。
  此处将其压到控制层之下。该元素自带 pointer-events: none，不影响点击。
*/
:deep(.ASS-box) {
  z-index: 10;
}

/* unsupported 档与运行时失败的提示卡片。高度与播放器接近，避免弹窗高度跳变 */
.vp-unsupported {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 12px;
  min-height: 240px;
  padding: 24px;
  border-radius: var(--radius-md);
  background: var(--surface-elevated);
  text-align: center;
}
.vp-reason {
  margin: 0;
  max-width: 420px;
  font-size: 13px;
  line-height: 1.6;
  color: var(--text-secondary);
}
.vp-actions {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
  justify-content: center;
}

@media (max-width: 768px) {
  .vp-unsupported {
    min-height: 180px;
    padding: 16px;
  }
}
</style>

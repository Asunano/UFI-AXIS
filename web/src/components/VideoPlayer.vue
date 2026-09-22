<!--
  统一视频播放器。媒体页与文件预览两处共用。

  ## 为什么要抽出来
  之前两处各写一个裸 `<video :src>`（`MediaVideoView.vue` 与 `PreviewModal.vue`），
  既不判格式也不听 `error`。结果放不了的文件就是一个**黑框**，用户无从判断原因。
  格式判定、错误文案、customType 注册这三件事如果留在各自的 SFC 里，一定会分叉。

  ## 三档处置（判据全在 composables/videoFormat.ts）
  - **unsupported**：**不创建播放器**，直接出提示卡片 + 下载入口。
    mkv/avi/rmvb 这类 MSE 根本不认，而 core 侧转码方案已放弃（ffmpeg .so 的
    16KB page size 与本机 4KB 页冲突），所以"试着放一下"只会得到黑框。
  - **custom**：flv/m3u8/mpd/ts，动态 `import()` 对应解复用库喂给 MSE。
  - **native**：交给浏览器解码，**同时监听 error 兜底** ——
    容器对了不代表编码对了（HEVC / AC3 / ProRes 都会在这里失败）。

  ## 两个容易踩的实现点
  1. **必须显式传 `type`**：播放地址是 `/media/stream?ticket=…`，没有文件后缀，
     ArtPlayer 按后缀猜不出格式（其文档专门警告过 `?type=` 这种形式解析不了）。
  2. **`id` 必须是稳定值**：播放位置记忆（`autoPlayback`）默认用 `url` 当缓存 key，
     而我们的 url 每次都带一张**新票据** —— 不给 `id` 的话每次都是新 key，记忆永远不生效。
     这里用设备上的文件路径作为 `id`。

  ## 外挂字幕
  三段都走"**先用 axios 把文本取下来**"，因为 `/api/media/subtitle` 要验签，
  而播放器内部的 fetch 带不上签名头（详见 [fetchSubtitleText]）：
  - vtt / srt → 文本包成 blob URL 交给 `art.subtitle.switch`；
  - ass / ssa → 文本喂 assjs（直接用库，不用 artplayer 的插件包，原因见 [assInstance]）；
  - 其余（ttml/sub/smi…）→ 在选择器里列出但标注不支持（判据在 composables/subtitleFormat.ts）。
-->
<template>
  <!-- C 档：不挂播放器，只给说明与出路 -->
  <div v-if="tier === 'unsupported'" class="vp-unsupported">
    <n-icon :size="40" color="var(--text-muted)"><VideocamOffOutline /></n-icon>
    <p class="vp-reason">{{ unsupportedReason }}</p>
    <n-button v-if="canDownload" size="small" @click="emit('download')">下载此文件</n-button>
  </div>

  <!-- 运行时失败（A/B 档共用）：容器识别了但解码失败，或网络中断 -->
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
 * ArtPlayer 实例。刻意用 `any`：它的 d.ts 把 `customType` 处理函数的签名收得很紧
 * （要求同步返回 void），而我们的 handler 必须是 async（动态 import 解复用库）。
 * 逐个断言的噪音大于收益。
 */
let art: any = null;
/** B 档解复用器实例，销毁时要一并回收（否则 MSE buffer 与 worker 泄漏） */
let demuxer: any = null;
/**
 * assjs 渲染实例（ass/ssa 字幕用）。与 [demuxer] 同理，销毁时要收掉。
 *
 * **直接用 assjs、不用 `artplayer-plugin-assjs`**：那个插件的工厂里写的是
 * `show: ass.show(), hide: ass.hide()` —— 两个都是**立即调用**而不是方法引用，
 * 于是构造时就先 show 再 hide，字幕一上来就被藏了（2026-09-20 "字幕能加载到、
 * 显示 200，但播放器没挂上"就是这个原因），而且那两个属性根本不是函数没法调。
 * assjs 自身的 `show()`/`hide()`/`destroy()` 是正常的。
 *
 * 它是**懒加载**的：只有真遇到 ass 字幕才 `import('assjs')`，
 * 所以那 50KB 在只放 srt 的会话里不会被下载。
 */
let assInstance: ASS | null = null;
/**
 * 字幕请求的代数。
 *
 * [applySubtitle] 中间有一次 await（取文本）+ 一次 await（懒加载 assjs），
 * 用户连点两条字幕时两次调用会交错，后发的先回就会被先发的覆盖。
 * 每次进入自增，回来后不是最新一代就丢弃。
 */
let subtitleSeq = 0;
/**
 * 当前挂载的字幕走哪条渲染路径，空串 = 没挂。
 *
 * 用途只有一个：字幕外观设置只能作用于 ArtPlayer 内建字幕层（vtt/srt），
 * ass 的字号/颜色/位置都写在字幕文件里、由 assjs 照着画。用户在 ass 轨道上
 * 调外观时要**当场说明为什么没反应**，而不是让他以为开关坏了。
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
const unsupportedReason = computed(() => unsupportedReasonOf(props.name));

/**
 * 收掉 assjs 实例。
 *
 * assjs **没有"换内容"的 API**，所以换字幕轨也走这里：destroy 会摘掉它挂在 video 上的
 * 5 个事件监听、停掉 rAF 循环、移除字幕层与 ResizeObserver，然后重新 new 一个。
 * 必须在 `art.destroy()` **之前**调用 —— 它 destroy 时要访问 video 元素摘监听。
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
  // 先收解复用器再销毁播放器：反过来的话 art already destroyed 时 demuxer 还挂在
  // 一个已解绑的 video 上，某些版本会抛
  if (demuxer) {
    try {
      demuxer.destroy();
    } catch {
      /* 已经销毁过，忽略 */
    }
    demuxer = null;
  }
  // assjs 自己持有 DOM、rAF 与 video 监听，必须在 art 之前显式收
  destroyAss();
  // 让在途的字幕请求失效，否则它回来时会往一个已销毁的 art 上挂
  subtitleSeq += 1;
  activeRender = '';
  if (art) {
    // 音量在这里落盘而不是监听 volumechange：拖音量条会连发几十个事件，
    // 每个都写一次 localStorage 没有必要。退出播放器时取最终值就够了
    if (typeof art.volume === 'number') patchVideoPrefs({ volume: art.volume });
    try {
      // true = 同时移除 DOM。不传的话容器里会留一个空壳，换片时叠加
      art.destroy(true);
    } catch {
      /* 同上 */
    }
    art = null;
  }
  // 字幕文本的 blob URL：不 revoke 会一直持有整份文本
  while (subtitleBlobUrls.length > 0) {
    const u = subtitleBlobUrls.pop();
    if (u) URL.revokeObjectURL(u);
  }
}

/** 动态加载 FLV 解复用器。只在真遇到 flv/ts 时才拉这个 chunk。 */ async function attachMpegts(
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

/** 动态加载 HLS。Safari 原生支持 m3u8，优先用原生（省一个 150KB 的 chunk）。 */
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
  // dash.js 没有装：DASH 在设备本地文件里几乎不会出现（它是切片+清单的流式格式）。
  // 真遇到就如实报错，而不是静默黑框。
  void video;
  void url;
  throw new Error('暂不支持 DASH（.mpd）播放，请下载后用本地播放器打开');
}

// ──────────────────────── 外挂字幕 ────────────────────────

/**
 * 拉这个视频的字幕列表。
 *
 * **两次取法是刻意的**（core 的 `scope` 参数就是为此设计的）：
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
 * ## 为什么必须自己取、不能把 URL 丢给播放器
 * `/api/media/subtitle` 在 `/api` 下，core 的 AuthMiddleware **强制**校验
 * `X-Timestamp`/`X-Nonce`/`X-Signature`，缺一即 444。而 `art.subtitle.switch(url)`
 * 是 ArtPlayer 自己发 fetch，**带不上这些头** —— 2026-09-19 的"字幕完全不生效"
 * 就是这个原因（和 `<img src>` 取不到缩略图、`<video src>` 取不到视频流是同一类问题，
 * 那两处分别用 blob URL 和票据解决了）。
 *
 * ## 必须用相对路径
 * axios 实例在拦截器里会把 `config.baseURL` 设成 `appStore.baseUrl`，并用
 * `client.getUri(config)` 算签名 URI。这里若传一个**已经带 baseUrl 的完整 URL**，
 * 会拼成 `baseUrl + baseUrl + path`，签名 URI 对不上 → 同样 444。
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
 * 把字幕文本变成 **blob URL** 交给 ArtPlayer。
 *
 * blob URL 是本地的，不经过网络、不需要鉴权 —— 这正是绕过上面那个鉴权限制的办法。
 * 生成的 URL 登记在 [subtitleBlobUrls] 里，销毁播放器时统一回收（不回收会一直持有文本）。
 */
function subtitleBlobUrl(text: string, mime: string): string {
  const url = URL.createObjectURL(new Blob([text], { type: mime }));
  subtitleBlobUrls.push(url);
  return url;
}

/**
 * 挂载一条字幕。`entry` 为 null = 关闭字幕。
 *
 * 两条路径，但**都先把文本取下来**（见 [fetchSubtitleText] 的说明）：
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
    // 空串 = "用户主动关了"，和"没记录"是两件事（见 videoPlayerPrefs 的说明），
    // 否则下次打开又被自动挂回来，字幕就成了关不掉的
    rememberSubtitle(props.path, '');
    return;
  }
  const render = renderOf(entry);
  if (render === 'unsupported') return;
  try {
    const text = await fetchSubtitleText(entry);
    // 取回来时用户可能已经换过字幕/关了弹窗；只有最后一次点击算数
    if (!text || !art || seq !== subtitleSeq) return;

    if (render === 'ass') {
      // assjs 不支持原地换内容，换轨即重建
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
      // assjs 的渲染循环挂在 video 的 play/playing 事件上，而我们是在 `ready` 之后
      // 又等了一次网络请求才 new 出来的 —— 此时视频**早就在播了**，那两个事件不会再来，
      // 结果只有构造瞬间那一帧字幕、之后永远不动。已经在播就补发一次把 rAF 循环踢起来。
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
    // 字幕失败不影响放视频，但**绝不能静默**：2026-09-20 排查"字幕不显示"时，
    // 这里原本是个空 catch，把 assjs 解析 1.7MB ASS 时的真实异常整个吞掉了，
    // 表现就是"请求 200、什么也不发生"，无从下手。
    console.error('[VideoPlayer] 字幕挂载失败', entry.path, err);
    if (art) {
      art.notice.show = `字幕加载失败：${err instanceof Error ? err.message : String(err)}`;
    }
  }
}

/**
 * 决定打开视频时自动挂哪条字幕。`null` = 不挂。
 *
 * 三级优先，前面命中就不看后面：
 *  1. **这个视频上次选的那条** —— 最强信号，用户已经明确表达过；
 *     记录是空串说明他上次主动关了，这里必须返回 null（否则关不掉）。
 *  2. **全局语言偏好**（上次选中字幕的 `label`，如 `SC`/`TC`）——
 *     一季番里用户只想选一次"简体"，不该每集都点一遍。
 *  3. core 排序的第一条可用字幕（core 已把"能解析的"排在前面）。
 */
function pickAutoSubtitle(list: SubtitleEntry[]): SubtitleEntry | null {
  const playable = list.filter(isWebPlayable);
  if (playable.length === 0) return null;
  const remembered = recallSubtitle(props.path);
  if (remembered === '') return null;
  if (remembered) {
    const hit = playable.find((e) => e.path === remembered);
    if (hit) return hit;
    // 记住的那条被删/改名了 —— 往下走默认逻辑，不要因此什么都不挂
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

/** ass 轨道下调外观不会有任何反应，当场说明原因，别让用户以为开关坏了。 */
function noticeIfAssActive() {
  if (art && activeRender === 'ass') {
    art.notice.show = 'ASS/SSA 字幕的字号与位置写在字幕文件里，由字幕组决定，此设置不影响它';
  }
}

/**
 * 把字幕做成播放器设置面板里的一个选择器。
 *
 * 不支持的格式**也列出来但标注**（`subtitleDisplayName` 会在名字里写"（XXX 不支持）"）——
 * 与 core 的 `supported: false` 仍然返回同一个道理：让用户看到"文件在、但这个格式不行"，
 * 而不是对着目录里明明存在的 .ttml 怀疑程序瞎了。选中它的效果等同于"关闭"。
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
 * **只对 vtt/srt 生效**，标题里就写清楚 —— ass 的样式写在字幕文件里由字幕组决定，
 * 这是 ASS 格式的本意，不该被播放器覆盖（app 端选的是相反的取舍：
 * `setApplyEmbeddedStyles(false)` 丢掉 ASS 样式统一成白字黑边。两端刻意不同）。
 * 用户在 ass 轨道上动这些项时 [noticeIfAssActive] 会当场说明。
 *
 * 只在**存在 vtt/srt 轨道**或**根本没有字幕**时都不加？—— 不。始终加：
 * 用户可能先挂 ass、再换到同目录的 srt，设置项不能中途出现/消失。
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
  destroyPlayer();
  runtimeError.value = '';
  if (tier.value === 'unsupported' || !props.url) return;
  await nextTick();
  const host = hostEl.value;
  if (!host) return;

  const Artplayer = (await import('artplayer')).default;
  const type = artplayerTypeOf(props.name);

  art = new Artplayer({
    container: host,
    url: props.url,
    // 显式给 type：url 没后缀，猜不出来（见文件头）
    type,
    // 稳定 key，否则位置记忆永远不命中（url 每次票据都不同）
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
    // 画面比例：设备上的片源比例五花八门（4:3 老番、21:9 电影、竖屏手机录像），
    // 而我们的容器是固定 16/9，没有这个入口就只能接受上下/左右黑边
    aspectRatio: true,
    // 镜像/翻转：前摄录的视频是左右反的，翻一下才能看清字
    flip: true,
    pip: true,
    fullscreen: true,
    fullscreenWeb: true,
    screenshot: true,
    hotkey: true,
    // 控制条收起后在底边留一条细进度条，不然全屏看片时完全不知道进度
    miniProgressBar: true,
    // 移动端：锁住控制条（防误触）+ 左右滑进度/上下滑音量 + 长按快进
    lock: true,
    gesture: true,
    fastForward: true,
    // Safari 的隔空播放。非 Safari 下 ArtPlayer 自己不会渲染这个按钮
    airplay: true,
    // 页面里同时只允许一个播放器出声（媒体页的音乐播放器也可能在放）
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

  // A 档的第二道兜底：容器对了不代表编码对了（HEVC / AC3 / ProRes 都在这里失败）
  art.on('video:error', () => {
    const msg = mediaErrorTextOf(art?.video?.error, props.name);
    runtimeError.value = msg;
    emit('error', msg);
    // 报错后把播放器收掉：留着它只会显示一个黑框盖住上面的提示
    destroyPlayer();
  });
  // customType 内部抛出的错误（解复用器不支持 / DASH 未接）走这里
  art.on('error', (err: unknown) => {
    const msg = err instanceof Error ? err.message : mediaErrorTextOf(null, props.name);
    runtimeError.value = msg;
    emit('error', msg);
    destroyPlayer();
  });

  // 字幕：播放器建好之后再拉。放在 ready 之后是因为 `art.setting.add` 与
  // `art.subtitle.switch` 都要求播放器已初始化完成。
  art.on('ready', async () => {
    try {
      // 外观偏好先落到播放器上：晚于挂字幕的话会看到一帧默认字号再跳变
      applySubtitleStyle();
      buildSubtitleStyleSetting();
      const list = await loadSubtitles();
      // 期间用户可能已经关了弹窗 / 换了片
      if (!art || list.length === 0) return;
      const auto = pickAutoSubtitle(list);
      // 选择器的默认勾选必须和真正要挂的那条一致，否则面板显示 A、画面放 B
      buildSubtitleSetting(list, auto);
      if (auto) await applySubtitle(auto);
    } catch (err) {
      // 这个 handler 是 async 且由 ArtPlayer 调用，抛出去只会变成
      // unhandled rejection（栈里看不出是字幕流程）。自己收下并标明来源
      console.error('[VideoPlayer] 字幕流程异常', err);
    }
  });
}

function retry() {
  runtimeError.value = '';
  void createPlayer();
}

// url 或文件换了就重建。不复用实例：ArtPlayer 换 url 时 customType 的绑定不会重来，
// 从 flv 切到 mp4 会带着上一个解复用器
watch(
  () => [props.url, props.name],
  () => void createPlayer(),
  { immediate: true }
);

onBeforeUnmount(destroyPlayer);
</script>

<style scoped>
.vp-host {
  width: 100%;
  aspect-ratio: 16 / 9;
  max-height: 68vh;
  border-radius: var(--radius-md);
  overflow: hidden;
  background: #000;
}

/*
  assjs 的字幕层。它是运行时 append 到 ArtPlayer 的 $player 里的，拿不到 scoped 属性，
  所以必须用 :deep()。

  z-index 是必要的：assjs 的 .ASS-box 自身不设 z-index，而它作为**最后一个**子节点
  插进 $player，按文档顺序会画在控制条（.art-bottom）之上 —— 字幕会糊在进度条上。
  这里压到控制层之下。它本身带 pointer-events: none，不影响点击。
*/
:deep(.ASS-box) {
  z-index: 10;
}

/* C 档 / 运行时失败的提示卡片。高度与播放器接近，避免弹窗高度跳变 */
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
  .vp-host {
    max-height: 42vh;
  }
  .vp-unsupported {
    min-height: 180px;
    padding: 16px;
  }
}
</style>

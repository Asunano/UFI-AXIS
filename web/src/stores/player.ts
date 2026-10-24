/**
 * 全局音频播放器。**播放状态从音乐页搬到了这里**（2026-10-14）。
 *
 * ──── 为什么必须搬 ────
 * 原来 `<audio>`、队列、当前曲目、音量全都活在 `MediaAudioView.vue` 的 setup 里。
 * 于是"离开音乐页"就等于"停止播放"：Vue 卸载组件时 `onUnmounted` 里的
 * `audioEl.pause()` 直接把歌掐了，回到音乐页还要从头选一遍。
 * 用户要的是「切页不断、刷新能接上」，那播放状态就必须比页面活得久 ——
 * 只有两种落点：路由级的 keep-alive，或者一个 store + 一个常驻宿主组件。
 *
 * 选后者：keep-alive 会把整个音乐页（列表、缩略图 blob、歌词、歌单面板）
 * 一起缓存下来，三页媒体各来一份就是三份内存；而且它救不了"刷新"。
 *
 * ──── 职责边界 ────
 * · 本 store 持有**全部**播放状态与 `<audio>` 元素（通过 [attach] 拿引用）；
 * · `components/GlobalAudioPlayer.vue` 只负责渲染那个 `<audio>` 并 attach，没有任何 UI；
 * · `components/HeaderAudioBar.vue` 与音乐页的 `AudioControlBar.vue` 都是**只读展示 + 派发动作**，
 *   它们不持有播放状态 —— 两处 UI 同时存在时，"谁是播放状态的真源"必须只有一个。
 *
 * ──── 队列的所有权 ────
 * 队列由**音乐页推给 store**（[setQueue]），因为"媒体库列表"和"歌单曲目"都只有音乐页知道。
 * 顶栏迷你条只是读它。这样做的代价是：没进过音乐页就没有队列（刷新后直接去别的页面时），
 * 所以刷新恢复还要额外把队列快照存进 sessionStorage —— 见 [persist] / [restoreFromSession]。
 *
 * ──── 刷新恢复为什么用 sessionStorage 而不是 localStorage ────
 * sessionStorage 是**每标签页**的：同一个浏览器开两个标签页听不同的歌，
 * 用 localStorage 会互相覆盖（后开的那个把前一个的队列顶掉）。
 *
 * ──── 刷新恢复为什么不存播放地址 ────
 * `POST /api/files/stream-ticket` 回的 `url` 带 `ticket=…`，**滑动过期 10 分钟**，
 * 而且它只授权那一个文件。把 blob URL 或票据 URL 存起来，刷新后拿到的是一张必然失效的票
 * （blob URL 更是刷新即作废，它绑在原来那个 document 上）。
 * 所以只存"**听的是哪一首、听到第几秒**"，恢复后**重新换票**。
 * 这也是为什么快照里只有元数据，没有 src。
 *
 * ──── 关于"顺序"这层间接 ────
 * `queue` 是**展示顺序**（音乐页列表按修改时间倒序、歌单按用户排的顺序），
 * `order` 是**播放顺序**（一个下标置换）。随机播放就是换掉 `order`，而不是打乱 `queue` ——
 * 打乱 queue 会让音乐页的列表跟着乱跳，用户下次找歌就找不到原来那一行在哪了。
 *
 * 这层间接的代价是**队列编辑必须同时照顾两层**（2026-10-14 补队列操作时踩的正是这里）：
 * 动过 `queue` 之后 `order` 里存的下标就全错位了，`orderPos` 也会指向别人 ——
 * 那不是"顺序有点乱"，而是"下一首"会跳到一首完全无关的曲目上。
 * 所以移除 / 重排 / 插入一律走 [remapOrder] / [rebuildOrder] 重建，绝不手改 `order` 的元素。
 */
import { defineStore } from 'pinia';
import { computed, ref, shallowRef } from 'vue';
import { getApiClient } from '@/composables/useApi';
import { mediaErrorTextOf } from '@/composables/videoFormat';
import { parseLyrics } from '@/views/files/id3Lyrics';
import {
  createBlobUrlPool,
  fetchAudioTags,
  fetchCoverUrl,
  fetchLyricsText,
  fetchStreamUrl,
  readAudioVolume,
  trackTitleOf,
  writeAudioVolume,
  type MediaApiClient,
} from '@/views/media/mediaShared';
import type { PlaylistTrack } from '@/views/media/playlistShared';

/** 播放队列里的曲目。与媒体库/歌单的条目同构，`missing` 的条目不可播。 */
export type PlayerTrack = PlaylistTrack;

/**
 * 队列的来源。用于两件事：顶栏显示"在放哪个歌单"，以及刷新后**重新拉一遍队列**。
 *
 * `library` 没有额外字段：它就是音乐页那套"整库平铺 + 分页"。
 */
export type PlayerSource =
  | { kind: 'library' }
  | { kind: 'playlist'; id: string; name: string }
  | { kind: 'group'; by: 'album' | 'artist' | 'folder'; key: string; title: string };

/** 循环模式。`one` 只影响**自动续播**，用户手动点下一首仍然换歌（与 app 一致）。 */
export type RepeatMode = 'off' | 'all' | 'one';

/** 倍速档位。0.5–2.0 覆盖播客/有声书与快速试听两端，再多档位在一条下拉里读不过来。 */
export const PLAYBACK_RATES = [0.5, 0.75, 1, 1.25, 1.5, 2] as const;

/**
 * 睡眠定时的模式（2026-10-14 补）。
 *
 * `countdown` = 到点**直接暂停**（可能停在一首歌中间）；`end-of-track` = 放完**当前这一首**再停。
 *
 * app 把后者做成了一个"到点后是否先放完当前这首"的开关（默认开，见 `MediaSleepTimer`），
 * web 这边把它做成**并列的一档**：播放条右侧只有一个下拉/浮层的宽度，塞不进一个开关，
 * 而"今晚听完这首就睡"本身就是最常用的一种意图，值得单独一档。
 */
export type SleepTimerMode = 'off' | 'countdown' | 'end-of-track';

/** 睡眠定时的预设档位（分钟）。与 app 的 `UFI_SLEEP_PRESET_MINUTES` 逐字一致。 */
export const SLEEP_PRESET_MINUTES = [10, 20, 30, 60] as const;

/**
 * 自定义时长的区间（分钟）。上限 1440 = 24 小时，与 app 的 `UFI_SLEEP_CUSTOM_MIN/MAX` 一致 ——
 * 上限存在的意义只是挡住"手滑多打两个 0"，而不是真的有人要定 24 小时。
 */
export const SLEEP_CUSTOM_MIN = 1;
export const SLEEP_CUSTOM_MAX = 1440;

/** sessionStorage 快照键。**只存元数据**，见文件头"为什么不存播放地址"。 */
const SS_KEY = 'ufi.player.session';

/**
 * 快照里队列的条数上限。
 *
 * 音乐页的队列来自分页加载（一页 60）或歌单（core 侧上限几百），现实中到不了这个数。
 * 留这个上限纯粹是为了防御"某天 core 回了一个几万条的列表"把 sessionStorage 写爆
 * （配额通常 5MB，超了 `setItem` 直接抛 QuotaExceededError）。
 * **截断时保证当前曲目一定在里面**，否则恢复出来就是个空条。
 */
const MAX_SNAPSHOT_QUEUE = 2000;

/** 位置快照的节流间隔。timeupdate 每 250ms 一次，不节流会把 sessionStorage 写成瓶颈。 */
const POSITION_SAVE_INTERVAL_MS = 2000;

export const usePlayerStore = defineStore('player', () => {
  // ──────────────────────────── 状态 ────────────────────────────

  /** 展示顺序的队列。 */
  const queue = ref<PlayerTrack[]>([]);
  /** 队列来源。null = 还没有队列（没进过音乐页）。 */
  const source = ref<PlayerSource | null>(null);
  /** 播放顺序：`queue` 下标的置换。随机播放只换它。 */
  const order = ref<number[]>([]);
  /** 当前曲目在 [order] 里的位置。**-1 表示"当前这首不在队列里"**（切了歌单之后的常态）。 */
  const orderPos = ref(-1);

  /**
   * 正在播（或暂停在中途）的那一首。
   *
   * **不从 `order[orderPos]` 推导**：切歌单/换分组之后当前曲目会掉出队列
   * （`orderPos = -1`），但它必须继续放完 —— 用户切歌单只是换"接下来从哪挑歌"，
   * 不是"把正在听的掐掉"。旧版音乐页也是这个语义（`currentIndex` 可以是 -1）。
   */
  const currentTrack = shallowRef<PlayerTrack | null>(null);

  const playing = ref(false);
  /** 在等数据（换票 / 缓冲）。UI 用它显示转圈，不要拿它当"暂停"。 */
  const buffering = ref(false);
  const positionMs = ref(0);
  const durationMs = ref(0);
  const volume = ref(readAudioVolume());
  const playbackRate = ref(1);
  const shuffle = ref(false);
  const repeatMode = ref<RepeatMode>('off');
  /** 播放失败的文案。空串 = 没有错误。 */
  const errorText = ref('');

  /** 封面 blob URL。由本 store 自己持有并回收（切歌时换、卸载时放）。 */
  const coverUrl = ref('');
  /** 解析好的歌词时间轴，格式与 `LyricsPanel` 的入参一致。 */
  const lyricLines = ref<Array<[number, string]>>([]);

  /** `/api/media/tags` 补回来的标签，只在 `/list` 那份为空时才用得上。 */
  const tagTitle = ref('');
  const tagArtist = ref('');
  const tagAlbum = ref('');

  /** `<audio>` 是否已经挂上。false 时所有"要播"的请求先排队（见 [pendingLoad]）。 */
  const attached = ref(false);

  /**
   * 睡眠定时的模式（2026-10-14 补）。默认 `off`。
   *
   * 它住在 store 而不是播放条里，理由与播放状态搬进 store 完全相同：
   * 用户完全可能"设 30 分钟然后离开音乐页去刷别的"，而播放条随音乐页一起卸载 ——
   * 定时器挂在那上面等于"一离开这一页定时就没了"。
   */
  const sleepMode = ref<SleepTimerMode>('off');
  /** 倒计时剩余秒数，由 [tickSleep] 每秒刷新。`end-of-track` 模式下恒为 0（没有倒计时可言）。 */
  const sleepRemainingSec = ref(0);

  // ──────────────────────────── 内部 ────────────────────────────

  /**
   * 取数用**非取消版**客户端。
   *
   * 不能用 `useCancellableApi()`：那个包装随**组件**卸载 abort 在途请求，
   * 而这里的请求必须在页面切走之后照常完成 —— 否则"切页时正好在换票"就会静默失败。
   * 并发正确性由 [playSeq] 代数保证，不靠 abort。
   */
  const api: MediaApiClient = getApiClient();

  /** 封面 blob 池。与列表缩略图分开：它的生命周期跟着"当前曲目"，不是跟着页面。 */
  const coverPool = createBlobUrlPool();

  /**
   * 换曲代数。
   *
   * [load] 里有 `await 换票`，之后还并发着标签/封面/歌词三条链。快速连点下一首时
   * 这些回调会交错，必须靠代数判断"我还是当前那一首吗" —— 只比对 path 不够：
   * 同一首被连点两次 path 相同，但票已经换过，慢的那一次会把新的 `src` 覆盖回去。
   */
  let playSeq = 0;
  /** 一首歌只自动换票一次，否则错误会变成死循环。 */
  let ticketRetried = false;
  /**
   * 待 seek 的位置（秒）。
   *
   * 流式音频在 `src` 刚赋值时 `readyState` 还是 0，直接写 `currentTime` 会被忽略，
   * 所以推迟到 `loadedmetadata` 再落。刷新恢复到"听到第 3 分钟"就靠它。
   */
  let pendingSeekSec: number | null = null;
  /** `<audio>` 还没挂上时排队的播放请求。 */
  let pendingLoad: { track: PlayerTrack; autoplay: boolean; startSec: number } | null = null;

  let lastPositionSaveAt = 0;
  let el: HTMLAudioElement | null = null;
  /**
   * 睡眠定时的截止时刻（`Date.now()` 基准）。0 = 没开。
   *
   * **不用"剩余秒数"当唯一真源**：后台标签页里 `setInterval` 会被节流（可能一分钟才跑一次），
   * 递减式的倒计时会越走越慢，表现是"定了 30 分钟，两小时后还在放"。
   * 记截止时刻、每次 tick 现算，节流最多让收口晚一点，不会累积误差。
   */
  let sleepDeadline = 0;
  /** 倒计时的 interval id。`end-of-track` 模式不需要它（那一档没有倒计时）。 */
  let sleepTickId: ReturnType<typeof setInterval> | undefined;

  // ──────────────────────────── 派生 ────────────────────────────

  /** 当前曲目在**展示队列**里的下标。-1 = 不在队列里。 */
  const currentIndex = computed(() => (orderPos.value >= 0 ? (order.value[orderPos.value] ?? -1) : -1));

  /** 显示用标题：`/list` 优先 → `/tags` 补的 → 文件名。 */
  const displayTitle = computed(() =>
    currentTrack.value ? currentTrack.value.title || tagTitle.value || trackTitleOf(currentTrack.value) : ''
  );
  const displayArtist = computed(() => currentTrack.value?.artist || tagArtist.value || '未知艺人');
  const displayAlbum = computed(() => currentTrack.value?.album || tagAlbum.value || '');

  const positionSec = computed(() => positionMs.value / 1000);
  const durationSec = computed(() => durationMs.value / 1000);

  /** 队列名（顶栏迷你条的 tooltip / 音乐页队列标题）。 */
  const sourceLabel = computed(() => {
    const s = source.value;
    if (!s) return '';
    if (s.kind === 'playlist') return s.name || '歌单';
    if (s.kind === 'group') return s.title || '分组';
    return '播放队列';
  });

  /**
   * 能不能上一首 / 下一首。
   *
   * `orderPos < 0`（当前曲目不在队列里）时**下一首仍然可用**：从队列头开始放，
   * 这比"整条走带全灰"有用得多 —— 用户切了歌单之后最自然的动作就是"那就从新的这张开始放"。
   * 上一首则要求真的在队列里且有前驱。
   */
  const hasPrev = computed(() => orderPos.value > 0);
  const hasNext = computed(() => {
    if (orderPos.value < 0) return queue.value.some((t) => !t.missing);
    if (orderPos.value < order.value.length - 1) return true;
    return repeatMode.value === 'all' && order.value.length > 1;
  });

  // ──────────────────────────── 队列 ────────────────────────────

  /** 洗牌。Fisher–Yates；`Math.random` 对"随机听歌"足够，不需要可复现性。 */
  function shuffleArray(a: number[]) {
    for (let i = a.length - 1; i > 0; i--) {
      const j = Math.floor(Math.random() * (i + 1));
      const t = a[i]!;
      a[i] = a[j]!;
      a[j] = t;
    }
  }

  /**
   * 重算播放顺序。
   *
   * 开随机时把**当前曲目放在置换的第一位**：否则"点了随机"之后第一下"下一首"
   * 可能跳回一首刚听完的，用户会以为随机没生效。
   */
  function rebuildOrder() {
    const n = queue.value.length;
    const curQueueIdx = currentTrack.value ? queue.value.findIndex((t) => t.path === currentTrack.value!.path) : -1;
    if (!shuffle.value) {
      order.value = Array.from({ length: n }, (_, i) => i);
    } else {
      const rest = Array.from({ length: n }, (_, i) => i).filter((i) => i !== curQueueIdx);
      shuffleArray(rest);
      order.value = curQueueIdx >= 0 ? [curQueueIdx, ...rest] : rest;
    }
    orderPos.value = curQueueIdx >= 0 ? order.value.indexOf(curQueueIdx) : -1;
  }

  /**
   * 音乐页把队列推上来。**不打断正在播的那一首**（见 [currentTrack] 的说明）。
   *
   * `tracks` 会原样成为展示顺序；同一次调用里 [rebuildOrder] 重算播放顺序并把
   * `orderPos` 重新定位到当前曲目。列表分页加载更多时反复调它是安全的。
   */
  function setQueue(tracks: PlayerTrack[], src: PlayerSource) {
    queue.value = tracks;
    source.value = src;
    rebuildOrder();
    persist(true);
  }

  /**
   * 队列结构变化之后重算播放顺序（[order]）与当前位置（[orderPos]）。
   *
   * `mapOld` 把"变化前的展示下标"翻成"变化后的展示下标"，返回 -1 表示这一项已经不在队列里。
   *
   * ──── 为什么不能图省事直接调 [rebuildOrder] ────
   * 那个函数在随机开着时会**重掷**一次置换：用户只是删掉一首，接下来的十首却全换了，
   * 与"随机播放"这个开关给人的预期不符（"下一首"应该还是本来会听到的那一首）。
   * 所以这里做**外科式重映射**：从 `order` 里抽掉这一项、把受影响的下标整体平移，
   * 置换里活下来的那些下标的相对顺序原样保留。
   *
   * `orderPos` 一律由"当前曲目在新 `queue` 里的下标"反查，而不是靠 ±1 猜 ——
   * 这是那条不变量（`order` 是 `queue` 下标的置换、`orderPos` 指向 `order` 里的当前曲目）
   * 唯一的重建方式，猜错一次"下一首"就会跳到别的曲目。
   */
  function remapOrder(mapOld: (oldIndex: number) => number) {
    const remapped: number[] = [];
    for (const old of order.value) {
      const next = mapOld(old);
      if (next >= 0) remapped.push(next);
    }
    order.value = remapped;
    const cur = currentTrack.value;
    const curQueueIdx = cur ? queue.value.findIndex((t) => t.path === cur.path) : -1;
    orderPos.value = curQueueIdx >= 0 ? order.value.indexOf(curQueueIdx) : -1;
  }

  /**
   * 从队列里移除一项。`index` 是**展示顺序**（也就是音乐页那一列）的下标。
   *
   * ──── 移除的若是正在播的那一首 ────
   * **接着播播放顺序里的下一首**。这是与 app 对齐的行为：media3 的 `removeMediaItem`
   * 删掉当前项时自己会跳到下一首（见 `UfiAudioPlayback.ufiRemoveFromQueue` 的 KDoc）。
   * 这样 `currentTrack` 仍在队列里、`orderPos` 仍然有效，两条不变量天然成立，
   * 也不会出现"删掉当前这首之后按下一首却跳回队首"的怪事。
   *
   * 后面已经没有可播的条目（删的是播放顺序里的最后一个）时**暂停**，并把这一首留在
   * `currentTrack` 上（`orderPos = -1`，即"当前曲目不在队列里"这个既有状态）：
   * 这时**不替用户挑一首**（静默换成别的歌更没道理），停下来、保留进度，
   * 按一下播放键还能接着听完。
   */
  async function removeFromQueue(index: number) {
    const track = queue.value[index];
    if (!track) return;
    const wasCurrent = currentTrack.value?.path === track.path;
    /** 它在**播放顺序**里的位置。抽走之后，同一位置上就是原来排在它后面的那一首。 */
    const posInOrder = order.value.indexOf(index);
    queue.value.splice(index, 1);
    remapOrder((old) => (old === index ? -1 : old > index ? old - 1 : old));
    persist(true);
    if (!wasCurrent) return;
    for (let k = posInOrder; k >= 0 && k < order.value.length; k++) {
      const qi = order.value[k]!;
      const nextTrack = queue.value[qi];
      // 与 [step] 同一条规则：跳过歌单里已失效的条目
      if (nextTrack && !nextTrack.missing) {
        await playAt(qi);
        return;
      }
    }
    pause();
  }

  /**
   * 队列内重排：把第 [index] 项往 [dir] 方向挪一位（`dir` 只取 ±1）。
   *
   * ──── 为什么重排会顺手关掉随机 ────
   * 与 app 的 `onMove` 同一条决策（`MediaAudioPlayerScreen` 里那段注释）：`queue` 是展示顺序、
   * `order` 是播放顺序，随机开着时**手动排的顺序对播放没有任何影响** —— 用户排了半天，
   * 听的时候还是按打乱的顺序走，那是"看起来生效了其实没有"，比不给排更糟。
   * 他既然在手排顺序，就是想按这个顺序听，所以这里把随机关掉；**提示由调用方负责**
   * （store 不碰 UI，见文件头"职责边界"）。
   *
   * 关掉随机之后 [rebuildOrder] 会把 `order` 重建为恒等置换，展示顺序从此就是播放顺序。
   */
  function moveInQueue(index: number, dir: 1 | -1) {
    const to = index + dir;
    if (index < 0 || index >= queue.value.length || to < 0 || to >= queue.value.length) return;
    const [moved] = queue.value.splice(index, 1);
    if (!moved) return;
    queue.value.splice(to, 0, moved);
    shuffle.value = false;
    rebuildOrder();
    persist(true);
  }

  /**
   * 清空队列。
   *
   * **不抹掉正在听的那一首**：它只是掉出了队列（`orderPos = -1`），与"切歌单"是同一种状态 ——
   * 用户按一下播放还能接着听完，不会因为清个队列丢掉进度。
   * 但播放要**停下来**：队列都没了，"接下来放什么"无从谈起，继续放会让用户以为清空没生效
   * （app 侧同样是"队列空了就 stop"，见 `UfiAudioPlayback.ufiRemoveFromQueue`）。
   *
   * `source` 刻意保留：它描述的是"这份队列从哪来"，清空之后这个来源依然成立，
   * 顶栏那行"在放哪个歌单"也还要读它。
   */
  function clearQueue() {
    queue.value = [];
    order.value = [];
    orderPos.value = -1;
    pause();
    persist(true);
  }

  /**
   * 「下一首播放」：把这一首插到**当前曲目之后**（直接操作 [order]，不碰 [queue]）。
   *
   * ──── 为什么动 order 而不是 queue ────
   * 随机开着时 `order` 是一份置换，"插到当前曲目的后一位"在置换里同样成立；而在 `queue` 里
   * 挪一行只会改**展示**顺序、对播放毫无影响 —— 那正是这个功能要避免的"看起来生效了其实没有"。
   * 所以这里只重排 `order`，音乐页那一列原样不动（用户找歌的位置不会因为插一首歌而乱掉）。
   *
   * 当前曲目不在队列里（`orderPos = -1`）时插到**播放顺序的队首**：那种状态下"下一首"本来
   * 就是从队首开始（见 [step]），插到队首才等于"紧接着放它"。
   * 已经在下一首的位置、或点的是正在播的那一首时**什么都不做**：重复插入只会白白重排一次。
   */
  function playNext(track: PlayerTrack) {
    const qi = queue.value.findIndex((t) => t.path === track.path);
    if (qi < 0) return;
    const curQueueIdx = orderPos.value >= 0 ? (order.value[orderPos.value] ?? -1) : -1;
    if (qi === curQueueIdx) return;
    const from = order.value.indexOf(qi);
    if (from < 0) return;
    // 先把它从置换里抽走，再按"当前曲目在**抽走之后**的位置"决定插到哪 ——
    // 直接拿旧下标算会差一位（抽走的项在当前位置之前时，当前位置整体左移了一格）
    const rest = order.value.filter((_, i) => i !== from);
    const curPosInRest = curQueueIdx >= 0 ? rest.indexOf(curQueueIdx) : -1;
    const insertAt = curPosInRest >= 0 ? curPosInRest + 1 : 0;
    if (insertAt === from) return;
    rest.splice(insertAt, 0, qi);
    order.value = rest;
    // `insertAt` 恒大于当前曲目的位置（或当前曲目不在置换里），所以它的位置没有变
    orderPos.value = curPosInRest;
  }

  /**
   * 从头播放整个队列（歌单 / 分组卡片上的「播放全部」与「随机播放」走这里）。
   *
   * `random = false`：关掉随机，按**展示顺序**从第一首可播的开始放。
   * `random = true`：打开随机，并把置换**轮转**到一个随机起点再从头放。
   *
   * ──── 为什么随机那一档是"轮转"而不是直接播 `order[0]` ────
   * [rebuildOrder] 会把当前曲目摆在置换的第一位（那是"点随机开关"时不想跳回刚听完那一首的
   * 处理），照搬到"随机播放这个歌单"就变成"重播当前这首"。轮转**不改变置换的合法性**
   * （仍是一份完整排列），于是从起点一路放到队尾，每一首都会播到一次，只是起点是随机的。
   */
  async function playAll(random = false) {
    if (queue.value.length === 0) return;
    shuffle.value = random;
    rebuildOrder();
    if (random) {
      const at = Math.floor(Math.random() * order.value.length);
      order.value = [...order.value.slice(at), ...order.value.slice(0, at)];
    }
    const qi = order.value.find((i) => queue.value[i] && !queue.value[i]!.missing);
    if (qi == null || qi < 0) return;
    await playAt(qi);
    persist(true);
  }

  /** 清空队列与当前曲目，并停止播放。退出登录 / 切服务器时用。 */
  function reset() {
    playSeq++;
    // 定时器也要一起收掉：切了服务器还留着上一个会话的定时，用户会听到"新账号的歌
    // 放了几分钟自己停了"这种无从解释的行为。
    cancelSleepTimer();
    currentTrack.value = null;
    queue.value = [];
    order.value = [];
    orderPos.value = -1;
    source.value = null;
    playing.value = false;
    buffering.value = false;
    positionMs.value = 0;
    durationMs.value = 0;
    errorText.value = '';
    coverPool.release(coverUrl.value);
    coverUrl.value = '';
    lyricLines.value = [];
    tagTitle.value = '';
    tagArtist.value = '';
    tagAlbum.value = '';
    if (el) {
      el.removeAttribute('src');
      el.load();
    }
    try {
      sessionStorage.removeItem(SS_KEY);
    } catch {
      /* 隐私模式 */
    }
  }

  // ──────────────────────────── 播放 ────────────────────────────

  /**
   * 播一首。`queueIndex` 是它在**展示队列**里的下标；不传表示"不在队列里"
   * （例如从文件管理器直接点开一首试听），此时上/下一首会退回"从队列头开始"。
   */
  async function playAt(queueIndex: number, startSec = 0) {
    const track = queue.value[queueIndex];
    if (!track || track.missing) return;
    orderPos.value = order.value.indexOf(queueIndex);
    await load(track, { autoplay: true, startSec });
  }

  /** 直接播一个条目（不要求它在队列里）。 */
  async function playTrack(track: PlayerTrack) {
    if (track.missing) return;
    const idx = queue.value.findIndex((t) => t.path === track.path);
    orderPos.value = idx >= 0 ? order.value.indexOf(idx) : -1;
    await load(track, { autoplay: true, startSec: 0 });
  }

  /** 从当前曲目重新取一张票接着放（刷新恢复后的第一次播放走这里）。 */
  async function resume() {
    const t = currentTrack.value;
    if (!t) return;
    if (el && el.src && el.paused) {
      // 元素里还有上一张票：直接续播，省一次换票
      try {
        await el.play();
        return;
      } catch {
        /* 票据可能已过期，落到下面重新换票 */
      }
    }
    await load(t, { autoplay: true, startSec: positionMs.value / 1000 });
  }

  /**
   * 装载并播放一首。
   *
   * 顺序刻意是"**先清空上一首的封面/歌词/标签，再换票**"：新的到达之前显示旧的，
   * 会被误读成"切歌没成功"。
   */
  async function load(track: PlayerTrack, opts: { autoplay: boolean; startSec: number }) {
    if (!attached.value || !el) {
      // 宿主还没挂上（音乐页 onMounted 早于 DefaultLayout 的 onMounted 时会发生）。
      // 状态照常更新，等 attach 时补播 —— 丢一个 Promise 比丢一次点击好。
      currentTrack.value = track;
      positionMs.value = opts.startSec * 1000;
      durationMs.value = track.duration_ms ?? 0;
      pendingLoad = { track, autoplay: opts.autoplay, startSec: opts.startSec };
      return;
    }
    const seq = ++playSeq;
    ticketRetried = false;
    errorText.value = '';
    currentTrack.value = track;
    coverPool.release(coverUrl.value);
    coverUrl.value = '';
    lyricLines.value = [];
    tagTitle.value = '';
    tagArtist.value = '';
    tagAlbum.value = '';
    positionMs.value = opts.startSec * 1000;
    durationMs.value = track.duration_ms ?? 0;
    buffering.value = true;
    persist(true);

    el.playbackRate = playbackRate.value;
    el.volume = volume.value;
    pendingSeekSec = opts.startSec > 0 ? opts.startSec : null;
    try {
      const src = await fetchStreamUrl(api, track.path);
      // 期间用户已经点了别的歌：这张票连 src 都不要赋，否则会把新的覆盖掉
      if (seq !== playSeq) return;
      el.src = src;
      if (opts.autoplay) await el.play();
      if (seq !== playSeq) return;
      playing.value = !el.paused;
    } catch (e: any) {
      if (seq !== playSeq) return;
      // 用户手势之外的 play() 会被浏览器拒（NotAllowedError）—— 那不是取数失败，不报错。
      if (e?.name !== 'NotAllowedError') {
        errorText.value = e?.message || '播放失败';
        playing.value = false;
      }
    } finally {
      if (seq === playSeq) buffering.value = false;
    }
    if (seq !== playSeq) return;
    void loadTags(track, seq);
    void loadCover(track, seq);
    void loadLyrics(track, seq);
  }

  function toggle() {
    if (!el || !currentTrack.value) return;
    if (el.paused) void resume();
    else el.pause();
  }

  /**
   * 暂停（睡眠定时到点时用）。
   *
   * 与 [toggle] 分开是**必须**的：到点那一刻用户可能刚好自己按了暂停，
   * `toggle` 会把它又打开 —— 定时关闭变成"定时开始播放"，是最坏的一种错。
   * 所以这里只做"停"，而且对"本来就没在播"是幂等的。
   */
  function pause() {
    if (el && !el.paused) {
      // onPause 会同步 playing 并落盘
      el.pause();
      return;
    }
    playing.value = false;
  }

  /** 找下一个可播的（跳过歌单里已失效的条目）。`dir` 只取 ±1。 */
  function step(dir: 1 | -1): number {
    const n = order.value.length;
    if (n === 0) return -1;
    let pos = orderPos.value;
    if (pos < 0) {
      // 当前曲目不在队列里：往前就是从队列头，往后也是从队列头（没有"当前"可参照）
      if (dir === 1) pos = -1;
      else return -1;
    }
    for (let k = pos + dir; k >= 0 && k < n; k += dir) {
      const qi = order.value[k]!;
      const t = queue.value[qi];
      if (t && !t.missing) return qi;
    }
    return -1;
  }

  /** 下一首。`auto = true` 表示由 `ended` 触发（受 `repeatMode === 'one'` 影响）。 */
  async function next(auto = false) {
    if (auto && repeatMode.value === 'one' && currentTrack.value) {
      if (el) {
        el.currentTime = 0;
        try {
          await el.play();
        } catch {
          /* 忽略：自动续播失败不该弹错 */
        }
      }
      return;
    }
    let qi = step(1);
    if (qi < 0 && repeatMode.value === 'all' && order.value.length > 0) {
      // 单曲循环之外的另一半：列表循环回到队首
      qi = order.value.find((i) => queue.value[i] && !queue.value[i]!.missing) ?? -1;
    }
    if (qi < 0) {
      // 到尾就停，不循环（旧版就是这个语义）
      playing.value = false;
      positionMs.value = durationMs.value;
      if (el) el.pause();
      persist(true);
      return;
    }
    orderPos.value = order.value.indexOf(qi);
    await load(queue.value[qi]!, { autoplay: true, startSec: 0 });
  }

  async function prev() {
    const qi = step(-1);
    if (qi < 0) return;
    orderPos.value = order.value.indexOf(qi);
    await load(queue.value[qi]!, { autoplay: true, startSec: 0 });
  }

  function seek(sec: number) {
    if (!el || !Number.isFinite(sec)) return;
    el.currentTime = sec;
    positionMs.value = sec * 1000;
    persist(true);
  }

  function setVolume(v: number) {
    const clamped = Math.min(1, Math.max(0, v));
    volume.value = clamped;
    if (el) el.volume = clamped;
    // 音量落 localStorage 而不是 sessionStorage：它是"这台浏览器上的习惯"，
    // 与"这次听的是哪首"不是一类东西（见 mediaShared 的说明）。
    writeAudioVolume(clamped);
  }

  function setPlaybackRate(r: number) {
    if (!Number.isFinite(r) || r <= 0) return;
    playbackRate.value = r;
    if (el) el.playbackRate = r;
    persist(true);
  }

  /** 倍速按钮的循环切换（顶栏迷你条用，省一个下拉）。 */
  function cyclePlaybackRate() {
    const list = PLAYBACK_RATES as readonly number[];
    const i = list.indexOf(playbackRate.value);
    setPlaybackRate(list[(i + 1) % list.length] ?? 1);
  }

  function toggleShuffle() {
    shuffle.value = !shuffle.value;
    rebuildOrder();
    persist(true);
  }

  function cycleRepeat() {
    const nextMode: RepeatMode = repeatMode.value === 'off' ? 'all' : repeatMode.value === 'all' ? 'one' : 'off';
    repeatMode.value = nextMode;
    persist(true);
  }

  // ──────────────────────────── 睡眠定时 ────────────────────────────
  //
  // 纯客户端：一个截止时刻 + 一个 1 秒的 ticker，到点调 [pause]。
  //
  // ──── 为什么不写进 sessionStorage 快照 ────
  // 快照的用途是"刷新后接着听"（见 [persist]），而睡眠定时表达的是"我打算现在就睡"。
  // 刷新通常意味着用户又回到了电脑前，此时按刷新前那一刻的截止时间静音是纯粹的惊吓；
  // 而且快照里的剩余时间要按 `savedAt` 反推，恢复出来往往是"立刻到点"这种更荒唐的结果。
  // 代价是"设了定时又刷新"要重设一次 —— 比莫名静音小得多，所以**刻意不持久化**。

  /**
   * 起一个 `minutes` 分钟的定时关闭。
   *
   * 上下限在这里再夹一次：播放条那个输入框只是第一道闸，写进状态的必须是合法值 ——
   * 否则 `0` 会立刻到点、`1e9` 会让按钮显示出一个荒唐的分钟数。
   */
  function startSleepTimer(minutes: number) {
    if (!Number.isFinite(minutes)) return;
    const m = Math.min(SLEEP_CUSTOM_MAX, Math.max(SLEEP_CUSTOM_MIN, Math.round(minutes)));
    sleepDeadline = Date.now() + m * 60_000;
    sleepMode.value = 'countdown';
    sleepRemainingSec.value = m * 60;
    startSleepTicker();
  }

  /**
   * 「放完当前这首再停」：不设倒计时，等 `ended` 时收口（见 [onEnded]）。
   *
   * 没有当前曲目时退化成"什么都不做"：那一刻没有"这一首"可言，
   * 挂一个永远等不到的回调只会让按钮亮着而永不生效。
   */
  function sleepAfterCurrentTrack() {
    if (!currentTrack.value) return;
    stopSleepTicker();
    sleepDeadline = 0;
    sleepRemainingSec.value = 0;
    sleepMode.value = 'end-of-track';
  }

  /** 开关语义：再点一次「播完当前曲目」必须能关掉，而不是重复写同一个模式。 */
  function toggleSleepAfterCurrentTrack() {
    if (sleepMode.value === 'end-of-track') cancelSleepTimer();
    else sleepAfterCurrentTrack();
  }

  /** 取消定时。到点自己收口时也走这里 —— 状态必须回到 `off`，否则按钮会一直亮着。 */
  function cancelSleepTimer() {
    stopSleepTicker();
    sleepDeadline = 0;
    sleepRemainingSec.value = 0;
    sleepMode.value = 'off';
  }

  function startSleepTicker() {
    stopSleepTicker();
    // 1 秒一跳只是为了刷新倒计时；**判定用截止时刻**（见 [sleepDeadline]），
    // 所以后台被节流时最多晚一点收口，不会像递减那样越走越慢。
    sleepTickId = setInterval(tickSleep, 1000);
  }

  function stopSleepTicker() {
    if (sleepTickId === undefined) return;
    clearInterval(sleepTickId);
    sleepTickId = undefined;
  }

  function tickSleep() {
    if (sleepMode.value !== 'countdown') return;
    const left = Math.ceil((sleepDeadline - Date.now()) / 1000);
    sleepRemainingSec.value = Math.max(0, left);
    if (left > 0) return;
    cancelSleepTimer();
    pause();
  }

  // ──────────────────────────── 元数据 ────────────────────────────

  async function loadTags(track: PlayerTrack, seq: number) {
    if (track.title && track.artist && track.album) return;
    const tags = await fetchAudioTags(api, track.id);
    if (!tags || seq !== playSeq) return;
    tagTitle.value = tags.title || '';
    tagArtist.value = tags.artist || '';
    tagAlbum.value = tags.album || '';
  }

  async function loadCover(track: PlayerTrack, seq: number) {
    if (track.id <= 0) return;
    const url = await fetchCoverUrl(api, track.id);
    if (seq !== playSeq) {
      // 取回来时用户已经切歌了：丢掉并回收，否则泄漏一个 blob
      if (url) URL.revokeObjectURL(url);
      return;
    }
    if (url) {
      coverUrl.value = coverPool.keep(url);
      return;
    }
    // core 的 /cover 只看内嵌图，不会去扫同目录。大量 FLAC / 整轨是外挂封面
    // （cover.jpg / folder.jpg），这里补上这条兜底。动态 import：探测那套 Range
    // 逻辑不便宜，没必要跟着应用壳一起进首屏 bundle。
    const { probeSidecarCover } = await import('@/views/files/audioMetaProbe');
    await probeSidecarCover(track.path, metaSink(seq));
  }

  async function loadLyrics(track: PlayerTrack, seq: number) {
    if (track.id > 0) {
      const text = await fetchLyricsText(api, track.id);
      if (seq !== playSeq) return;
      // 解析复用文件页那份（含 [offset:] 与"纯文本降级"），不在这里再写一遍
      const parsed = text ? parseLyrics(text) : [];
      if (parsed.length) {
        lyricLines.value = parsed;
        return;
      }
    }
    // core 的 /lyrics 明确只读旁挂 .lrc/.txt，内嵌歌词只能前端解
    const { probeEmbeddedLyrics } = await import('@/views/files/audioMetaProbe');
    await probeEmbeddedLyrics(track.path, track.size, metaSink(seq));
  }

  /** `audioMetaProbe` 的结果接收端。标签走 core 的 `/tags`，这里刻意忽略 `onTags`。 */
  function metaSink(seq: number) {
    return {
      isStale: () => seq !== playSeq,
      hasCover: () => !!coverUrl.value,
      onTags: () => undefined,
      onCover: (u: string) => {
        if (seq !== playSeq) {
          URL.revokeObjectURL(u);
          return;
        }
        coverUrl.value = coverPool.keep(u);
      },
      onLyrics: (lines: Array<[number, string]>) => {
        if (seq === playSeq) lyricLines.value = lines;
      },
    };
  }

  // ──────────────────────────── <audio> 宿主 ────────────────────────────

  /**
   * 接管一个 `<audio>` 元素。由 `GlobalAudioPlayer.vue` 在挂载时调用。
   *
   * 事件在**这里**注册而不是模板里 `@timeupdate`：这样"播放状态"只有一个写入口，
   * 两处 UI 谁都不用碰元素。
   */
  function attach(element: HTMLAudioElement) {
    el = element;
    el.volume = volume.value;
    el.playbackRate = playbackRate.value;
    el.addEventListener('timeupdate', onTimeUpdate);
    el.addEventListener('loadedmetadata', onLoadedMetadata);
    el.addEventListener('durationchange', onLoadedMetadata);
    el.addEventListener('play', onPlay);
    el.addEventListener('pause', onPause);
    el.addEventListener('waiting', onWaiting);
    el.addEventListener('playing', onPlaying);
    el.addEventListener('ended', onEnded);
    el.addEventListener('error', onError);
    attached.value = true;
    const p = pendingLoad;
    pendingLoad = null;
    if (p) void load(p.track, { autoplay: p.autoplay, startSec: p.startSec });
  }

  function detach() {
    if (!el) return;
    el.removeEventListener('timeupdate', onTimeUpdate);
    el.removeEventListener('loadedmetadata', onLoadedMetadata);
    el.removeEventListener('durationchange', onLoadedMetadata);
    el.removeEventListener('play', onPlay);
    el.removeEventListener('pause', onPause);
    el.removeEventListener('waiting', onWaiting);
    el.removeEventListener('playing', onPlaying);
    el.removeEventListener('ended', onEnded);
    el.removeEventListener('error', onError);
    el = null;
    attached.value = false;
  }

  function onTimeUpdate() {
    if (!el) return;
    positionMs.value = el.currentTime * 1000;
    // 位置落盘节流：timeupdate 每 250ms 一次，每次都写 sessionStorage 会拖慢主线程
    const now = Date.now();
    if (now - lastPositionSaveAt >= POSITION_SAVE_INTERVAL_MS) {
      lastPositionSaveAt = now;
      persist();
    }
  }

  function onLoadedMetadata() {
    if (!el) return;
    const d = el.duration;
    // 流式音频的 duration 可能是 Infinity（未知），此时回落到 MediaStore 给的时长
    durationMs.value = Number.isFinite(d) && d > 0 ? d * 1000 : (currentTrack.value?.duration_ms ?? 0);
    if (pendingSeekSec != null) {
      const at = Math.min(pendingSeekSec, Math.max(0, durationMs.value / 1000 - 0.5));
      pendingSeekSec = null;
      if (at > 0.2) {
        el.currentTime = at;
        positionMs.value = at * 1000;
      }
    }
  }

  function onPlay() {
    playing.value = true;
    persist(true);
  }

  function onPause() {
    playing.value = false;
    persist(true);
  }

  function onWaiting() {
    buffering.value = true;
  }

  function onPlaying() {
    buffering.value = false;
  }

  function onEnded() {
    // 「放完当前这首再停」：这一首就是"当前这首"，所以收口点就在这里 —— 不续播。
    // 用 `ended` 而不是"位置快到头就暂停"：位置回调可能整个跨过结尾那几百毫秒
    //（上一帧还在 11.5s、下一帧歌已经换了），于是要么早停要么停不下来。
    // app 侧为同一件事写过同样的结论（见 `MediaSleepTimer` 的注释）。
    if (sleepMode.value === 'end-of-track') {
      cancelSleepTimer();
      pause();
      positionMs.value = durationMs.value;
      persist(true);
      return;
    }
    void next(true);
  }

  /**
   * 播放出错。
   *
   * 两类要分开，不能一句"浏览器不支持该编码"了事：
   *  · **票据过期**（滑动过期 10 分钟）—— 长时间暂停后按播放/拖进度就会撞上。
   *    表现是网络类错误，而"重新换一张票、seek 回原位继续"完全可以自动完成；
   *  · 真的解不了 —— 交给 `mediaErrorTextOf` 按 MediaError 的 code 给准确文案
   *    （与视频播放器同一份映射，两处对同一个 code 必须说同一句话）。
   */
  async function onError() {
    if (!el?.src || !currentTrack.value) return;
    const code = el.error?.code;
    const played = positionMs.value > 0;
    if (played && code === MediaError.MEDIA_ERR_NETWORK && !ticketRetried) {
      ticketRetried = true;
      await resumeWithFreshTicket();
      return;
    }
    errorText.value = mediaErrorTextOf(el.error, currentTrack.value.name);
  }

  async function resumeWithFreshTicket() {
    const track = currentTrack.value;
    if (!el || !track) return;
    const at = el.currentTime;
    const seq = playSeq;
    try {
      const src = await fetchStreamUrl(api, track.path);
      if (seq !== playSeq) return;
      el.src = src;
      pendingSeekSec = at;
      await el.play();
    } catch {
      // 换票也失败：可能是 core 重启或文件已删，这时如实报错
      errorText.value = '播放中断，且重新获取播放地址失败';
    }
  }

  // ──────────────────────────── 刷新恢复 ────────────────────────────

  /**
   * 把快照写进 sessionStorage。**不含任何 URL**（见文件头）。
   *
   * @param force 跳过节流。切歌/播放暂停/开关变化这类"状态跳变"必须立即落盘，
   *              否则用户在切歌后 1 秒内刷新，恢复出来的是上一首。
   */
  function persist(force = false) {
    const now = Date.now();
    if (!force && now - lastPositionSaveAt < POSITION_SAVE_INTERVAL_MS) return;
    lastPositionSaveAt = now;
    try {
      const cur = currentTrack.value;
      let tracks = queue.value;
      if (tracks.length > MAX_SNAPSHOT_QUEUE) {
        // 截断也要保住当前曲目，否则恢复出来是个空条
        const keep = tracks.slice(0, MAX_SNAPSHOT_QUEUE);
        if (cur && !keep.some((t) => t.path === cur.path)) keep.unshift(cur);
        tracks = keep;
      }
      sessionStorage.setItem(
        SS_KEY,
        JSON.stringify({
          v: 1,
          queue: tracks,
          source: source.value,
          current: cur,
          // 位置取整到秒：快照是"接着听"，不需要毫秒级精度，整数还省字节
          positionSec: Math.round(positionMs.value / 1000),
          durationMs: durationMs.value,
          volume: volume.value,
          playbackRate: playbackRate.value,
          shuffle: shuffle.value,
          repeatMode: repeatMode.value,
          savedAt: now,
        })
      );
    } catch {
      /* 隐私模式 / 配额满：丢了只是恢复不到上次的位置，不该影响播放 */
    }
  }

  /**
   * 页面加载时恢复上次的状态。**恢复后是暂停的**，不自动出声。
   *
   * 为什么暂停：浏览器自动播放策略会把"没有用户手势的 `play()`"拒掉
   * （NotAllowedError），自动尝试的结果是控制条显示"在播"而实际没声音 ——
   * 比直接暂停更让人困惑。用户按下播放键时才是真手势，那时换票续播一次成功。
   */
  function restoreFromSession() {
    let raw: string | null = null;
    try {
      raw = sessionStorage.getItem(SS_KEY);
    } catch {
      return;
    }
    if (!raw) return;
    let snap: any;
    try {
      snap = JSON.parse(raw);
    } catch {
      return;
    }
    if (!snap || snap.v !== 1 || !snap.current?.path) return;

    queue.value = Array.isArray(snap.queue) ? snap.queue : [];
    source.value = snap.source ?? null;
    currentTrack.value = snap.current;
    positionMs.value = Math.max(0, Number(snap.positionSec) || 0) * 1000;
    durationMs.value = Number(snap.durationMs) || snap.current?.duration_ms || 0;
    playbackRate.value = Number(snap.playbackRate) || 1;
    shuffle.value = !!snap.shuffle;
    repeatMode.value = snap.repeatMode === 'all' || snap.repeatMode === 'one' ? snap.repeatMode : 'off';
    // 音量不恢复：它已经在 localStorage 里（readAudioVolume 的初值），
    // 两条通道同时写会在"改了音量但没听歌"时互相打架。
    playing.value = false;
    rebuildOrder();
    // 封面/歌词重新取一遍：blob URL 刷新即作废，持久化它没有意义
    const seq = ++playSeq;
    void loadTags(currentTrack.value!, seq);
    void loadCover(currentTrack.value!, seq);
    void loadLyrics(currentTrack.value!, seq);
  }

  return {
    // 状态
    queue,
    source,
    order,
    orderPos,
    currentTrack,
    playing,
    buffering,
    positionMs,
    durationMs,
    volume,
    playbackRate,
    shuffle,
    repeatMode,
    errorText,
    coverUrl,
    lyricLines,
    tagTitle,
    tagArtist,
    tagAlbum,
    attached,
    sleepMode,
    sleepRemainingSec,
    // 派生
    currentIndex,
    displayTitle,
    displayArtist,
    displayAlbum,
    positionSec,
    durationSec,
    sourceLabel,
    hasPrev,
    hasNext,
    // 动作
    setQueue,
    reset,
    removeFromQueue,
    moveInQueue,
    clearQueue,
    playNext,
    playAll,
    playAt,
    playTrack,
    resume,
    toggle,
    pause,
    next,
    prev,
    seek,
    setVolume,
    setPlaybackRate,
    cyclePlaybackRate,
    toggleShuffle,
    cycleRepeat,
    startSleepTimer,
    sleepAfterCurrentTrack,
    toggleSleepAfterCurrentTrack,
    cancelSleepTimer,
    attach,
    detach,
    persist,
    restoreFromSession,
  };
});

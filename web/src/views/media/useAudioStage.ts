/**
 * 音乐页「沉浸播放层」的状态与行为（2026-10-30 从 `MediaAudioView.vue` 抽出）。
 *
 * ## 抽出的边界
 * 只放「**只对沉浸层成立**的规则」：
 *   · 打开/关闭、全屏 vs 内容区两种档位；
 *   · 打开时锁壳层滚动（`html.stage-locked`）、关闭时还原焦点；
 *   · 空闲预挂载（避免第一次打开掉帧）；
 *   · Esc 的逐层关闭顺序（队列下拉 → 全屏 → 沉浸层）。
 *
 * **留在视图里的两个开关**：`queueOpen` 与 `lyricsOpen`。它们同时被资料库侧的
 * 播放条使用（队列浮层挂在播放条上、歌词按钮在资料库与沉浸层里语义不同），
 * 属于「两个区域共用」的状态，塞进本文件会让依赖方向反过来 —— 所以由调用方传入。
 *
 * ## 为什么不用 props / emit
 * 这些能力不是"给父组件用的子组件接口"，而是同一棵 setup 作用域里的状态。
 * 用 composable 而不是拆组件，是因为模板里 `stageCoverBroken = true` 这类**赋值**
 * 必须落在响应式 ref 上；拆成组件就得再造一套 v-model，反而更容易漂移。
 *
 * ## 与 store 的关系
 * 读 `player`（当前曲目、队列、封面）但**不写**它：播放控制仍归 `stores/player`，
 * 这里只决定"沉浸层怎么显示"。
 */
import { computed, nextTick, onUnmounted, ref, watch, type Ref } from 'vue';
import type { usePlayerStore } from '@/stores/player';

type PlayerStore = ReturnType<typeof usePlayerStore>;

/** 带 `requestIdleCallback` 的 window（Safari 老版本没有，走 setTimeout 兜底） */
type IdleCapableWindow = Window & {
  requestIdleCallback?: (cb: () => void, opts?: { timeout: number }) => number;
  cancelIdleCallback?: (id: number) => void;
};

export interface AudioStageOptions {
  /** 播放器 store（只读：作为"显示什么"的依据） */
  player: PlayerStore;
  /** 手机布局：切过去时没有桌面播放条可挂队列浮层 */
  isMobile: Ref<boolean>;
  /** 队列下拉开关，与资料库侧共用；打开沉浸时收起它 */
  queueOpen: Ref<boolean>;
}

export function useAudioStage({ player, isMobile, queueOpen }: AudioStageOptions) {
  /** 沉浸视图：主列从队列换成大封面。 */
  const stageOpen = ref(false);
  /**
   * 沉浸层是否覆盖顶栏/侧栏。
   *
   * 默认 **false**：沉浸层只铺满内容区（用户仍能看见自己在哪一页、能点侧栏切走）。
   * 顶栏右侧的全屏钮把它切成 `position: fixed; inset: 0`，覆盖整个视口。
   * 关闭沉浸时复位，下次进来默认仍是"内容区"模式。
   */
  const stageFullscreen = ref(false);
  /** 封面 URL 失效时退回占位图标，避免正在播放页出现浏览器破图 + alt 文本。 */
  const stageCoverBroken = ref(false);
  watch(
    () => player.coverUrl,
    () => {
      stageCoverBroken.value = false;
    }
  );

  /** 顶栏状态文案：能定位到队列时显示"第 n / m 首"。 */
  const stageStatusText = computed(() => {
    const path = player.currentTrack?.path;
    const idx = path ? player.queue.findIndex((t) => t.path === path) : -1;
    return idx >= 0 ? `正在播放 · 第 ${idx + 1} / ${player.queue.length} 首` : '正在播放';
  });

  /**
   * 打开沉浸前的焦点。关闭时还回去，键盘用户不会掉到 body 上。
   * 只做「打开时聚焦层、关闭时还原」，不在这里实现焦点陷阱：默认档顶栏/侧栏
   * 本来就是可交互的，过度限制反而让用户不能用导航离开。
   */
  let stageReturnFocus: HTMLElement | null = null;

  /**
   * 沉浸层是否已在后台挂载。
   *
   * 第一次打开沉浸页要现挂整套控制条/歌词/封面，低端机上会有一次明显掉帧。
   * 在选歌后（或鼠标移到播放条上时）用 requestIdleCallback 预挂载，打开时只切
   * v-show，不再走首次创建；隐藏时 display:none，不参与布局/绘制。
   */
  const stageWarm = ref(false);
  let stageWarmTimer: number | null = null;

  function warmStageSoon() {
    if (stageWarm.value || !player.currentTrack || stageWarmTimer !== null) return;
    const run = () => {
      stageWarmTimer = null;
      if (player.currentTrack) stageWarm.value = true;
    };
    const idleWindow = window as IdleCapableWindow;
    stageWarmTimer = idleWindow.requestIdleCallback
      ? idleWindow.requestIdleCallback(run, { timeout: 1200 })
      : window.setTimeout(run, 800);
  }
  watch(
    () => player.currentTrack?.path,
    (path) => {
      if (path) warmStageSoon();
      else stageWarm.value = false;
    },
    { immediate: true }
  );

  /** 进入沉浸播放：清掉上次的浮层与全屏状态，默认停在内容区模式。 */
  function openStage() {
    const active = document.activeElement;
    stageReturnFocus = active instanceof HTMLElement && active !== document.body ? active : null;
    queueOpen.value = false;
    stageFullscreen.value = false;
    stageWarm.value = true;
    stageOpen.value = true;
  }

  function toggleStageFullscreen() {
    stageFullscreen.value = !stageFullscreen.value;
  }

  /**
   * 沉浸打开时锁住壳层滚动。
   *
   * 沉浸层默认是 absolute（只盖内容区），页面若还能滚，用户一滑就会把整层带走；
   * 全屏模式虽然 fixed 不会跟着滚，但背后内容仍会被滚轮带着走。两种模式都锁。
   * 类挂在 <html> 上，样式见 main.css 的 `html.stage-locked`。
   */
  watch(stageOpen, async (open) => {
    document.documentElement.classList.toggle('stage-locked', open);
    if (!open) {
      stageFullscreen.value = false;
      stageReturnFocus?.focus();
      stageReturnFocus = null;
      return;
    }
    await nextTick();
    (document.querySelector('.immersive') as HTMLElement | null)?.focus();
  });
  onUnmounted(() => document.documentElement.classList.remove('stage-locked'));
  onUnmounted(() => {
    if (stageWarmTimer === null) return;
    (window as IdleCapableWindow).cancelIdleCallback?.(stageWarmTimer);
    window.clearTimeout(stageWarmTimer);
    stageWarmTimer = null;
  });

  /** 切到手机布局时没有桌面播放条可挂队列浮层；不在沉浸层里就收起它。 */
  watch(isMobile, (mobile) => {
    if (mobile && !stageOpen.value) queueOpen.value = false;
  });

  /** 退出沉浸：同时收起队列下拉与全屏状态，避免回到资料库后浮层还挂着。 */
  function closeStage() {
    stageOpen.value = false;
    stageFullscreen.value = false;
    queueOpen.value = false;
  }

  /** Esc 逐层关闭：队列下拉 → 全屏（退回内容区）→ 沉浸播放。 */
  function onAudioKeydown(e: KeyboardEvent) {
    if (e.key !== 'Escape') return;
    if (queueOpen.value) {
      queueOpen.value = false;
      return;
    }
    if (stageOpen.value) {
      if (stageFullscreen.value) {
        stageFullscreen.value = false;
        return;
      }
      closeStage();
    }
  }

  return {
    stageOpen,
    stageFullscreen,
    stageCoverBroken,
    stageStatusText,
    stageWarm,
    warmStageSoon,
    openStage,
    toggleStageFullscreen,
    closeStage,
    onAudioKeydown,
  };
}

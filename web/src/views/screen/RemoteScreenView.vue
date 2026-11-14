<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue';
import { AndroidKeyCode, ScrcpyControlMessageType, ScrcpySetClipboardControlMessage } from '@yume-chan/scrcpy';
import { useScrcpyStream } from '@/composables/useScrcpyStream';
import { useScreenInput } from '@/composables/useScreenInput';
import { useAppStore } from '@/stores/app';
import { getApiClient } from '@/composables/useApi';
import { updateConfig } from '@/api/config';
import { toApiErrorMessage } from '@/composables/utils';

/**
 * 远程屏幕（2026-10-31）。
 *
 * ## 分区
 * 顶栏只回答"我在不在连着"；画面占满剩余高度；参数统一收进可折叠的「画面设置」——
 * 分辨率/帧率/码率都是**会话启动参数**，改一次就要重连，把它们摊在顶栏会让每次操作
 * 都像在改配置而不是在看屏幕。
 *
 * ## 两处容易误解的地方（UI 上必须写出来）
 * 1. 改画质参数 = **重开会话**（scrcpy 的这些参数运行期不可改）。
 * 2. 「显示大小」只影响**本地显示缩放**，与设备分辨率无关 —— 两者常被混为一谈。
 */

const canvasEl = ref<HTMLCanvasElement | null>(null);
const { status, error, meta, codec, activeParams, writer, ctrlFrames, ctrlBytes, ctrlError, connect, disconnect } =
  useScrcpyStream(canvasEl);
const { key, scroll, downCount, sentCount, lastError } = useScreenInput(canvasEl, writer, meta);
const appStore = useAppStore();

const statusText = computed(() => {
  switch (status.value) {
    case 'connecting':
      return '连接中…';
    case 'streaming':
      return '已连接';
    case 'error':
      return '连接失败';
    default:
      return '未连接';
  }
});
const isBusy = computed(() => status.value === 'connecting' || status.value === 'streaming');
const codecName = computed(() => (codec.value === 1748121140 ? 'H.264' : '—'));

// ── 功能总开关 ──
const enabled = ref<boolean | null>(null);
const enabling = ref(false);
const enableError = ref('');

async function loadEnabled() {
  try {
    const { data } = await getApiClient().get('/api/screen/status');
    // 旧 core 没注册这个路由时，`HttpServer` 的 SPA 兜底会回 **200 + index.html**。
    // 直接 `Boolean(data?.enabled)` 会得到 false —— 页面假称「未开启」，用户点「启用」
    // 必然失败。所以先要求响应确实是对象，否则按"设备端不支持"处理。
    if (!data || typeof data !== 'object' || !('enabled' in data)) {
      enabled.value = null;
      return;
    }
    enabled.value = Boolean(data.enabled);
  } catch {
    enabled.value = null; // 读不到就不显示开关，别假装它是关的
  }
}

async function putScreenEnabled(next: boolean) {
  return updateConfig(getApiClient(), { screen_enabled: next });
}

/** 打开/关闭投屏开关；只要求已配对身份（Bearer + 设备签名）。 */
async function setEnabled(next: boolean) {
  enabling.value = true;
  enableError.value = '';
  try {
    const data = await putScreenEnabled(next);
    // core 部分成功也返回 success:true，唯一可信的是 updated_fields
    if (!(data?.updated_fields || []).includes('screen_enabled')) {
      enableError.value = '开关未生效：设备未接受此项改动';
      enabled.value = !next;
      return;
    }
    enabled.value = next;
    if (!next) disconnect();
  } catch (e: unknown) {
    enableError.value = toApiErrorMessage(e, '开关未生效');
  } finally {
    enabling.value = false;
  }
}

// ── 画面参数（会话启动参数，改了就重连） ──
const showSettings = ref(false);
const draft = ref({ maxSize: 848, maxFps: 20, bitRate: 2_000_000 });

/** 把服务端钳制后的实际值同步回草稿，避免滑块显示的和真正生效的不一致 */
function syncDraftFromActive() {
  draft.value = { ...activeParams.value };
}

/**
 * 设备原生长边。scrcpy 的 `max_size` 只会**缩小**，超过原生值是空操作 ——
 * 滑块上限必须卡在原生值，否则用户往上拖看不到任何变化（实测被当成"不生效"）。
 */
const nativeLongEdge = computed(() => {
  const m = meta.value;
  if (!m) return 848;
  return Math.max(m.width, m.height);
});
const maxSizeLimit = computed(() => Math.min(1280, Math.max(320, nativeLongEdge.value)));

/** 按当前滑块预估输出尺寸，让"改没改"一眼可见 */
const estSizeText = computed(() => {
  const m = meta.value;
  if (!m) return '';
  const long = Math.max(m.width, m.height);
  const scale = Math.min(1, draft.value.maxSize / long);
  const round8 = (v: number) => Math.max(8, Math.round(v / 8) * 8);
  return `${round8(m.width * scale)}×${round8(m.height * scale)}`;
});

/** 显示缩放：只影响本地，与设备分辨率无关 */
const zoomMode = ref<'fit' | number>('fit');
const zoomPercent = computed(() => (zoomMode.value === 'fit' ? 100 : Number(zoomMode.value)));

const canvasStyle = computed(() => {
  // touch-action/user-select 必须和尺寸放同一个 style 绑定里：静态 style 与 :style 并存时
  // 容易被后者整体覆盖，而那两条丢了就会变成"浏览器抢走拖动"。
  const base = { touchAction: 'none', userSelect: 'none' } as Record<string, string>;
  if (zoomMode.value === 'fit') return { ...base, maxWidth: '100%', maxHeight: '100%' };
  const m = meta.value;
  if (!m) return { ...base, maxWidth: '100%' };
  const scale = Number(zoomMode.value) / 100;
  return {
    ...base,
    width: `${Math.round(m.width * scale)}px`,
    height: `${Math.round(m.height * scale)}px`,
  };
});

async function applySettings() {
  if (status.value === 'streaming') {
    disconnect();
    await connect(draft.value);
  }
  syncDraftFromActive();
}

async function toggle() {
  if (isBusy.value) {
    disconnect();
    return;
  }
  if (!appStore.token) {
    error.value = '未登录，无法连接';
    return;
  }
  await connect(draft.value);
}

// ── 截屏（设备原生像素） ──
const shotBusy = ref(false);
const shotError = ref('');

async function takeScreenshot() {
  if (shotBusy.value) return;
  shotBusy.value = true;
  shotError.value = '';
  try {
    const res = await getApiClient().get('/api/screen/screenshot', { responseType: 'blob' });
    const url = URL.createObjectURL(res.data as Blob);
    const a = document.createElement('a');
    const ts = new Date().toISOString().replace(/[:.]/g, '-').slice(0, 19);
    a.href = url;
    a.download = `screen-${ts}.png`;
    a.click();
    setTimeout(() => URL.revokeObjectURL(url), 10_000);
  } catch (e: unknown) {
    shotError.value = toApiErrorMessage(e, '截屏失败');
  } finally {
    shotBusy.value = false;
  }
}

// ── 文本输入 ──
const textInput = ref('');
const textSending = ref(false);
const textHint = ref('');

/**
 * 发文本。两条路，都不依赖 ADBKeyboard：
 *
 * - **ASCII** → scrcpy `INJECT_TEXT`（按按键映射注入，最可靠）
 * - **非 ASCII（中文等）** → scrcpy `SET_CLIPBOARD` + `paste=true`：
 *   服务端把文本写进设备剪贴板，然后自己发一次粘贴。中文靠这条，不需要额外装 IME。
 *
 * 前提是**设备上要有焦点输入框**（否则剪贴板写了也没处粘）。
 * `sequence` 用 0n = 不要回执；要回执就得接 device message 通道，这里不需要。
 */
async function sendText() {
  const t = textInput.value;
  if (!t || textSending.value) return;
  const w = writer.value;
  if (!w) {
    textHint.value = '未连接';
    return;
  }
  textSending.value = true;
  textHint.value = '';
  try {
    if (/^[\x20-\x7E\r\n\t]*$/.test(t)) {
      await w.injectText(t);
    } else {
      // 不能用 writer.setClipboard()：它内部转给 `AckClipboardHandler`，而那个 handler
      // 只在 options 带 `clipboard`（设备→客户端的剪贴板流）时才创建，我们没有那条流，
      // 调用就会炸在 `serializeSetClipboardControlMessage` of undefined。
      // 这里自己序列化再 write()；`sequence: 0n` 表示不要回执，正好用不着 handler。
      await w.write(
        ScrcpySetClipboardControlMessage.serialize({
          type: ScrcpyControlMessageType.SetClipboard,
          sequence: 0n,
          paste: true,
          content: t,
        })
      );
    }
    textInput.value = '';
  } catch (e: unknown) {
    textHint.value = toApiErrorMessage(e, '发送失败');
  } finally {
    textSending.value = false;
  }
}

// ── 诊断 + 轮询 ──
const serverUpstreamBytes = ref(0);
const serverDownstreamBytes = ref(0);
const showDiag = ref(false);
let statsTimer = 0;

async function pollStats() {
  if (status.value !== 'streaming') return;
  try {
    const { data } = await getApiClient().get('/api/screen/status');
    serverUpstreamBytes.value = Number(data?.upstream_bytes ?? 0);
    serverDownstreamBytes.value = Number(data?.downstream_bytes ?? 0);
  } catch {
    // 轮询失败不打扰用户
  }
}

/**
 * 画面区遮罩：让人一眼看出"现在是没开/没连/在连/出错"。
 * 只在非 streaming 时出现，并用实心背景盖掉上一帧残留 —— 否则半透明的旧画面
 * 会让人以为还连着。
 */
const overlay = computed(() => {
  if (status.value === 'streaming') return null;
  if (enabled.value === false) {
    return { text: '远程屏幕未开启', label: '启用', action: () => void setEnabled(true) };
  }
  if (status.value === 'connecting') return { text: '连接中…', label: '', action: null };
  if (status.value === 'error') {
    return { text: error.value || '连接失败', label: '重试', action: () => void toggle() };
  }
  return { text: '未连接', label: '连接', action: () => void toggle() };
});

const navButtons = [
  { label: '返回', code: AndroidKeyCode.AndroidBack },
  { label: 'Home', code: AndroidKeyCode.AndroidHome },
  { label: '多任务', code: AndroidKeyCode.AndroidAppSwitch },
  { label: '电源', code: AndroidKeyCode.Power },
];

function onWheel(e: WheelEvent) {
  e.preventDefault();
  scroll(e.deltaY);
}

function onVisibility() {
  if (document.visibilityState === 'hidden' && status.value === 'streaming') disconnect();
}

onMounted(() => {
  document.addEventListener('visibilitychange', onVisibility);
  statsTimer = window.setInterval(pollStats, 2000);
  void loadEnabled();
  syncDraftFromActive();
});

onBeforeUnmount(() => {
  document.removeEventListener('visibilitychange', onVisibility);
  window.clearInterval(statsTimer);
  disconnect();
});
</script>

<template>
  <!--
    ══ 布局 ══
    设备是**竖屏**（480×848），所以画面永远居中显示原始比例，绝不被拉宽。
    宽屏（≥1024）时控制项收进右侧窄列（w-60），窄屏时落到画面下方 ——
    横着铺一排按钮会把画面挤成一条，而这一页的主角是画面。
  -->
  <div class="flex h-full flex-col gap-3 overflow-y-auto p-4 lg:flex-row lg:overflow-hidden">
    <!-- 画面 -->
    <div
      class="relative flex min-h-[45vh] flex-1 items-start justify-center overflow-auto rounded p-2 lg:min-h-0"
      style="background: var(--code-bg)"
    >
      <canvas ref="canvasEl" :style="canvasStyle" @wheel="onWheel" @contextmenu.prevent />

      <!-- 遮罩：明确显示"现在是没开 / 没连 / 在连 / 出错" -->
      <div
        v-if="overlay"
        class="absolute inset-0 flex flex-col items-center justify-center gap-3 p-6 text-center"
        style="background: var(--card-bg); border: 1px solid var(--border-subtle)"
      >
        <p class="text-sm" style="color: var(--text-primary)">{{ overlay.text }}</p>
        <button
          v-if="overlay.action"
          class="rounded px-4 py-1 text-sm"
          style="background: var(--accent-color); color: var(--on-accent)"
          @click="overlay.action()"
        >
          {{ overlay.label }}
        </button>
      </div>
    </div>

    <!-- 控制列 -->
    <aside class="flex w-full shrink-0 flex-col gap-2 lg:w-80 lg:max-w-80">
      <div class="flex items-center gap-2">
        <span
          class="inline-block h-2 w-2 rounded-full"
          :style="{
            background:
              status === 'streaming' ? 'var(--success)' : status === 'error' ? 'var(--error)' : 'var(--text-muted)',
          }"
        />
        <span class="text-sm">{{ statusText }}</span>
        <span class="flex-1" />
        <button
          class="rounded px-3 py-1 text-sm"
          style="background: var(--accent-color); color: var(--on-accent)"
          :disabled="enabled === false"
          @click="toggle"
        >
          {{ isBusy ? '断开' : '连接' }}
        </button>
      </div>

      <!--
        未启用时的高优先提示。
        之前「启用」藏在折叠的画质面板里，而「连接」又被禁用 —— 用户看到的是一个
        点不动的按钮和找不到的开关。功能没开这件事必须自己冒出来。
      -->
      <div
        v-if="enabled === false"
        class="flex flex-col gap-2 rounded p-2 text-xs"
        style="background: var(--warning-light); color: var(--text-primary)"
      >
        <span>远程屏幕默认关闭，开启后才能连接（画面与触摸都会暴露给已登录的客户端）。</span>
        <button
          class="rounded px-3 py-1 text-sm"
          style="background: var(--accent-color); color: var(--on-accent)"
          :disabled="enabling"
          @click="setEnabled(true)"
        >
          启用远程屏幕
        </button>
      </div>

      <p v-if="enableError" class="text-xs" style="color: var(--error)">{{ enableError }}</p>
      <p v-if="error" class="text-xs" style="color: var(--error)">{{ error }}</p>

      <!-- 文本 -->
      <!-- 文本输入：放在导航键**上方** —— 它是最常用的操作，不该沉到底部 -->
      <div class="flex gap-2">
        <input
          v-model="textInput"
          class="min-w-0 flex-1 rounded px-2 py-1 text-sm"
          style="background: var(--surface-elevated); color: var(--text-primary)"
          placeholder="中文/英文，回车发送（需设备上有焦点输入框）"
          :disabled="status !== 'streaming' || textSending"
          @keyup.enter="sendText"
        />
        <button
          class="shrink-0 rounded px-3 py-1 text-sm"
          style="background: var(--accent-color); color: var(--on-accent)"
          :disabled="status !== 'streaming' || textSending || !textInput"
          @click="sendText"
        >
          发送
        </button>
      </div>
      <p v-if="textHint" class="text-xs" style="color: var(--error)">{{ textHint }}</p>

      <!-- 导航键 -->
      <div class="grid grid-cols-2 gap-2">
        <button
          v-for="btn in navButtons"
          :key="btn.label"
          class="rounded px-2 py-2 text-sm"
          style="background: var(--surface-elevated); color: var(--text-primary)"
          :disabled="status !== 'streaming'"
          @click="key(btn.code)"
        >
          {{ btn.label }}
        </button>
      </div>

      <!-- 截屏 + 设置 -->
      <div class="flex gap-2">
        <button
          class="flex-1 rounded px-2 py-1 text-sm"
          style="background: var(--surface-elevated); color: var(--text-primary)"
          :disabled="enabled !== true || shotBusy"
          @click="takeScreenshot"
        >
          {{ shotBusy ? '截屏中…' : '截屏' }}
        </button>
        <button
          class="flex-1 rounded px-2 py-1 text-sm"
          style="background: var(--surface-elevated); color: var(--text-primary)"
          @click="showSettings = !showSettings"
        >
          {{ showSettings ? '收起设置' : '画面设置' }}
        </button>
      </div>
      <p v-if="shotError" class="text-xs" style="color: var(--error)">{{ shotError }}</p>

      <!-- 设置面板（折叠） -->
      <section
        v-if="showSettings"
        class="flex flex-col gap-3 rounded p-2 text-sm"
        style="background: var(--surface-elevated)"
      >
        <label class="flex flex-col gap-1">
          <span class="text-xs opacity-70">
            分辨率（长边）{{ draft.maxSize }}px
            <span v-if="meta">→ {{ estSizeText }}</span>
          </span>
          <input v-model.number="draft.maxSize" type="range" min="320" :max="maxSizeLimit" step="16" />
        </label>
        <label class="flex flex-col gap-1">
          <span class="text-xs opacity-70">帧率 {{ draft.maxFps }} fps</span>
          <input v-model.number="draft.maxFps" type="range" min="5" max="60" step="1" />
        </label>
        <label class="flex flex-col gap-1">
          <span class="text-xs opacity-70"> 码率 {{ (draft.bitRate / 1000000).toFixed(1) }} Mbps </span>
          <input v-model.number="draft.bitRate" type="range" min="500000" max="16000000" step="500000" />
        </label>
        <label class="flex flex-col gap-1">
          <span class="text-xs opacity-70">显示大小（只影响本地）</span>
          <select
            class="rounded px-2 py-1"
            :value="String(zoomMode)"
            style="background: var(--card-bg); color: var(--text-primary)"
            @change="
              zoomMode =
                ($event.target as HTMLSelectElement).value === 'fit'
                  ? 'fit'
                  : Number(($event.target as HTMLSelectElement).value)
            "
          >
            <option value="fit">适应窗口</option>
            <option value="100">100%</option>
            <option value="150">150%</option>
            <option value="200">200%</option>
          </select>
        </label>
        <button
          class="rounded px-2 py-1 text-sm"
          style="background: var(--accent-color); color: var(--on-accent)"
          @click="applySettings"
        >
          应用并重连
        </button>
        <span class="text-xs opacity-60">分辨率/帧率/码率是启动参数，改了必须重连</span>

        <button
          v-if="enabled === true"
          class="rounded px-2 py-1 text-sm"
          style="background: var(--card-bg); color: var(--text-primary)"
          :disabled="enabling"
          @click="setEnabled(false)"
        >
          关闭远程屏幕
        </button>
      </section>

      <!-- 信息 -->
      <div class="mt-auto flex flex-col gap-1 text-xs" style="color: var(--text-muted)">
        <span v-if="meta">
          {{ meta.width }}×{{ meta.height }} · {{ codecName }} · {{ activeParams.maxFps }}fps ·
          {{ (activeParams.bitRate / 1000000).toFixed(1) }}Mbps
        </span>
        <div class="flex items-center gap-2">
          <span v-if="zoomPercent !== 100">显示 {{ zoomPercent }}%</span>
          <button class="underline" @click="showDiag = !showDiag">
            {{ showDiag ? '隐藏诊断' : '诊断' }}
          </button>
        </div>
        <p v-if="showDiag">
          触摸 down={{ downCount }} / 调用={{ sentCount }} / 发出={{ ctrlFrames }}帧 {{ ctrlBytes }}B ｜ core
          upstream={{ serverUpstreamBytes }}B / downstream={{ serverDownstreamBytes }}B<span
            v-if="lastError || ctrlError"
          >
            / 错误：{{ lastError || ctrlError }}</span
          >
        </p>
      </div>
    </aside>
  </div>
</template>

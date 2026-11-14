<template>
  <div ref="wrapRef" class="pty-pane">
    <div v-if="!enabled" class="pty-tip">
      <p>真 PTY 终端未开启。</p>
      <p class="pty-tip-sub">开启后浏览器将获得设备上的可交互 shell（支持 vim/htop 等全屏程序）。</p>
      <n-button type="primary" size="small" @click="enableTtyd">开启真 PTY 终端</n-button>
    </div>
    <div v-else-if="errorText" class="pty-tip">
      <p class="pty-tip-err">{{ errorText }}</p>
      <n-button size="small" @click="connect">重试</n-button>
    </div>
    <div v-show="connected" ref="termRef" class="pty-term"></div>
    <div v-if="enabled" class="pty-status">
      <span class="pty-status-dot" :class="{ on: running }" />
      <span class="pty-status-text">{{ running ? 'ttyd 正在设备后台常驻（退出页面不会停止）' : 'ttyd 未在运行，连接时自动启动' }}</span>
      <n-button v-if="running" size="tiny" quaternary type="warning" @click="stopDaemon">停止后台 ttyd</n-button>
    </div>
  </div>
</template>

<script setup lang="ts">
/**
 * 真 PTY 终端面板（2026-10-06，ttyd 反代 + @xterm/xterm）。
 *
 * 链路：POST /api/terminal/pty-ticket（头部鉴权，拿短时票据）
 *   → new WebSocket(`/ws/terminal?ticket=...`)（core 反代，凭票）
 *   → core ↔ 127.0.0.1:ttyd 透传。
 *
 * ttyd 的 WS 帧是 JSON 数组指令流（见 core TtydRoutes 注释），core 不解析、原样透传；
 * 本端也不解析 —— xterm.js 负责渲染 `["1", data]` 里的 data，键盘输入包成 `["2", input]`
 * 发回。这是 ttyd 官方 html 客户端的最小子集（不看窗口大小同步：fit addon 只在本端
 * 排版，ttyd 侧终端大小由首次握手参数带过去，此处从简 —— 全屏程序在异常尺寸下会有
 * 换行瑕疵，属于已知取舍，不影响可用性）。
 */
import { onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { Terminal } from '@xterm/xterm';
import { FitAddon } from '@xterm/addon-fit';
import '@xterm/xterm/css/xterm.css';
import { getApiClient } from '@/composables/useApi';
import { loadDeviceIdentity } from '@/composables/deviceIdentityLazy';
import { useAppStore } from '@/stores/app';

const api = getApiClient();

const wrapRef = ref<HTMLDivElement>();
const termRef = ref<HTMLDivElement>();
const enabled = ref(false);
const connected = ref(false);
const running = ref(false);
const errorText = ref('');

let term: Terminal | null = null;
let fit: FitAddon | null = null;
let ws: WebSocket | null = null;
let ticket = '';
let sessionId = '';
let heartbeat: ReturnType<typeof setInterval> | null = null;

/** 票据滑动过期 30min（TtydTicketStore），每 10min 静默重签一次换新连接太粗暴，
 *  改为：收到 1008 关闭码（票据失效）时自动重签重连一次，其余关闭码交给用户点重试。 */
let reauthOnce = false;

async function checkEnabled() {
  try {
    const { data } = await api.get('/api/terminal/status');
    enabled.value = Boolean(data?.enabled);
    running.value = Boolean(data?.running);
  } catch {
    enabled.value = false;
  }
}

/** 手动停掉设备后台常驻的 ttyd（开关不动，下次连接自动再起）。 */
async function stopDaemon() {
  try {
    await api.post('/api/terminal/stop');
  } finally {
    running.value = false;
  }
}

async function enableTtyd() {
  try {
    await api.put('/api/config', { ttyd_enabled: true });  // 2026-10-07 修复：/api/config 只有 GET/PUT，POST 一律 405
    enabled.value = true;
    connect();
  } catch {
    errorText.value = '开启失败：请确认设备在线后重试';
  }
}

/**
 * ttyd 1.7.x 帧构造（权威定义见 ttyd `src/server.h`）。
 *
 * ⚠️ 2026-10-10 修正：此前用的是 `["0", ...] / ["1", data] / ["2", data]` 这种
 * **JSON 数组**帧 —— 那是 ttyd 1.6 及更早的老协议。本机 ttyd 是 **1.7.7**，它的
 * 帧是「1 个 ASCII 命令码 + 载荷」的**二进制**帧，JSON 数组会被 ttyd 当垃圾丢弃：
 * 表现就是连接建立、但终端一片空白且完全无法输入（2026-10-10 r32 真机）。
 *
 * client → server（`server.h` 的 client message）：
 *   '0' INPUT            载荷 = 原始按键字节
 *   '1' RESIZE_TERMINAL  载荷 = {"columns":N,"rows":N}（注意是 columns，不是 cols）
 *   '2' PAUSE / '3' RESUME
 *   '{' JSON_DATA        载荷 = {"AuthToken":"","columns":..,"rows":..}，握手用
 * server → client：'0' OUTPUT / '1' SET_WINDOW_TITLE / '2' SET_PREFERENCES
 *
 * 鉴权：AuthToken 留空 —— 鉴权已在 core 反代层完成，ttyd 只绑 127.0.0.1。
 */
const TTYD_INPUT = '0'; // client → ttyd：按键输入
const TTYD_RESIZE = '1'; // client → ttyd：尺寸同步
const TTYD_JSON_DATA = '{'; // client → ttyd：握手（首帧）

/** 发一帧：命令码 + 载荷，二进制 WS 帧（ttyd 用 LWS_WRITE_BINARY 收）。 */
function writeFrame(cmd: string, payload: string) {
  ws?.send(cmd + payload);
}

/** 握手帧：请求初始输出 + 告知终端尺寸。ttyd 收到它才真正 fork pty。 */
function writeInit(cols: number, rows: number) {
  writeFrame(TTYD_JSON_DATA, JSON.stringify({ AuthToken: '', columns: cols, rows: rows }));
}

/**
 * 把一条下行帧的载荷（OUTPUT 文本）写进终端。非 OUTPUT 命令码直接丢弃。
 * 纯函数、不碰 ws —— ArrayBuffer 和 Blob 两条路径共用它。
 */
function decodeTtydFrame(data: string) {
  if (!data.length) return;
  const cmd = data.charCodeAt(0);
  if (cmd !== 0x30 /* '0' OUTPUT */) return; // '1' TITLE / '2' PREFS：不消费
  term?.write(data.slice(1));
}

/** 跨浏览器/跨帧类型的下行帧入口：string / ArrayBuffer / Blob 三种都要收。 */
async function decodeFrame(raw: unknown) {
  let data: string;
  if (typeof raw === 'string') data = raw;
  else if (raw instanceof ArrayBuffer) data = new TextDecoder('utf-8').decode(raw);
  else if (typeof Blob !== 'undefined' && raw instanceof Blob) data = new TextDecoder('utf-8').decode(await raw.arrayBuffer());
  else return; // 未知帧类型：丢弃，不让一条脏帧炸掉会话
  decodeTtydFrame(data);
}

async function connect() {
  errorText.value = '';
  disconnect();
  createTerm();

  let data: any;
  try {
    ({ data } = await api.post('/api/terminal/pty-ticket', sessionId ? { session_id: sessionId } : {}));
  } catch (e: any) {
    errorText.value = e?.response?.data?.message ?? '拿不到 PTY 票据（设备不在线或功能未开）';
    return;
  }
  ticket = data.ticket;
  sessionId = data.session_id;

  const proto = location.protocol === 'https:' ? 'wss' : 'ws';
  // 浏览器 WS 发不了自定义头 → 与 /ws/realtime 完全同一套 query 验签
  // （token/ts/nonce/sig，签 GET /ws/terminal）；PTY 票据作为第二道闸附加。
  const path = '/ws/terminal';
  let authQs: string;
  try {
    authQs = await (await loadDeviceIdentity()).signWsQuery(useAppStore().token, path);
  } catch {
    errorText.value = '设备身份不可用，请重新登录后重试';
    return;
  }

  ws = new WebSocket(`${proto}://${location.host}${path}?${authQs}&ticket=${encodeURIComponent(ticket)}`);
  ws.onopen = () => {
    connected.value = true;
    running.value = true; // WS 通了 = ttyd 已在设备后台常驻
    reauthOnce = false;
    // 握手帧：'{' + {"AuthToken":"","columns":..,"rows":..}。ttyd 收到这一帧才 fork pty，
    // 尺寸必须带上 —— 缺了它 shell 会按 0×0 起（表现为提示符错位 / 无回显）。
    writeInit(term?.cols ?? 80, term?.rows ?? 24);
    term?.focus();
    scheduleFit();
  };
  ws.onmessage = (ev) => {
    // ttyd 1.7.x 下行帧：第 1 个字符是命令码，其余是载荷。
    //  '0' OUTPUT           → 终端输出字节，写进 xterm
    //  '1' SET_WINDOW_TITLE → 标题（这里不处理）
    //  '2' SET_PREFERENCES  → 偏好 JSON（忽略）
    //  注：老协议的 ["1", data] JSON 数组格式**不再存在**，别再加回来。
    //
    // ⚠️ 2026-10-10 修（r33 真机实锤：WS 已连接、状态显示「已连接设备 shell」，
    // 但终端零输出、按键无反应）：ttyd 用 `LWS_WRITE_BINARY` 发帧 —— 浏览器的
    // ev.data 是 ArrayBuffer/Blob，**不是 string**。之前只认 string 分支，
    // binary 帧被 `if (!data.length) return` 全部丢弃：连上了，却一个字都没有。
    void decodeFrame(ev.data);
  };
  ws.onclose = (ev) => {
    connected.value = false;
    if (ev.code === 1008 && !reauthOnce) {
      // 鉴权/票据失败：静默重签重连一次（可能是票据过期）
      reauthOnce = true;
      void connect();
    } else if (ev.code !== 1000) {
      errorText.value = `连接已断开（code ${ev.code}），点击重试`;
    }
  };
  ws.onerror = () => {
    errorText.value = '连接错误';
  };
}

function createTerm() {
  if (term) return;
  term = new Terminal({
    cursorBlink: true,
    fontSize: 13,
    theme: {
      background: '#101014',
      foreground: '#e0e0e6',
      cursor: '#63e6be',
    },
    scrollback: 5000,
  });
  fit = new FitAddon();
  term.loadAddon(fit);
  term.onData((data) => {
    // 键盘输入 → ttyd 输入指令 ["2", input]
    // ttyd 1.7.x：按键输入是 '0' + 原始字节（不是老协议的 ["2", data]）
    if (ws && ws.readyState === WebSocket.OPEN) writeFrame(TTYD_INPUT, data);
  });
  term.onResize(({ cols, rows }) => {
    // 尺寸同步：ttyd 1.7.x 是 '1' + {"columns":..,"rows":..}（字段名是 columns，不是 cols）。
    // 老版本（1.6）用的是 ["1", {...}] 数组格式 —— 那套在 1.7.7 上是无效帧。
    if (ws && ws.readyState === WebSocket.OPEN) {
      writeFrame(TTYD_RESIZE, JSON.stringify({ columns: cols, rows: rows }));
    }
  });
  if (termRef.value) term.open(termRef.value);
}

function scheduleFit() {
  requestAnimationFrame(() => {
    try {
      fit?.fit();
    } catch {
      /* 容器尚未布局完成：下一次 resize 事件会再触发 */
    }
  });
}

function disconnect() {
  if (heartbeat) clearInterval(heartbeat);
  heartbeat = null;
  if (ws) {
    ws.onclose = null;
    ws.close(1000);
    ws = null;
  }
  connected.value = false;
}

onMounted(() => {
  checkEnabled().then(() => {
    if (enabled.value) connect();
  });
  window.addEventListener('resize', scheduleFit);
});

onBeforeUnmount(() => {
  window.removeEventListener('resize', scheduleFit);
  disconnect();
  term?.dispose();
  term = null;
});

// 组件可见性：切走时保持连接（ttyd 会话在服务端存活），切回时补一次 fit
watch(connected, (v) => {
  if (v) scheduleFit();
});
</script>

<style scoped>
.pty-pane {
  position: relative;
  height: 100%;
  min-height: 0;
  display: flex;
  flex-direction: column;
}
.pty-term {
  flex: 1;
  min-height: 0;
  padding: 6px;
  background: #101014;
  border-radius: 8px;
}
.pty-tip {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 10px;
  color: var(--text-secondary, #999);
}
.pty-tip-sub {
  font-size: 12px;
  opacity: 0.75;
}
.pty-tip-err {
  color: #e05555;
}

.pty-status {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 4px 10px;
  font-size: 12px;
  opacity: 0.75;
}
.pty-status-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--cat-neutral-fg, #888);
  flex: none;
}
.pty-status-dot.on {
  background: #18a058;
}
.pty-status-text {
  flex: 1;
}
</style>

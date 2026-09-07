import { defineStore } from 'pinia';
import { ref } from 'vue';
import { WS_UI_TOPICS } from '../api/contract';
import { loadDeviceIdentity } from '@/composables/deviceIdentityLazy';

export type WsStatus = 'disconnected' | 'connecting' | 'connected';

export const useWebSocketStore = defineStore('websocket', () => {
  const status = ref<WsStatus>('disconnected');
  const lastMessage = ref(0);
  /** 停止重连的原因（非空表示已放弃自动重连，需要用户干预）。 */
  const fatalReason = ref('');
  let ws: WebSocket | null = null;
  let reconnectTimer: ReturnType<typeof setTimeout> | null = null;
  let reconnectAttempts = 0;
  const maxReconnectDelay = 30_000;
  /** 重连次数上限：约 1+2+4+8+16+30*4 秒后放弃，避免离线时无限打洞 */
  const maxReconnectAttempts = 9;
  let shouldReconnect = true;

  // 回调注册表
  const messageHandlers = new Map<string, Set<(data: any) => void>>();

  function on(type: string, handler: (data: any) => void) {
    if (!messageHandlers.has(type)) messageHandlers.set(type, new Set());
    messageHandlers.get(type)!.add(handler);
    return () => {
      messageHandlers.get(type)?.delete(handler);
    };
  }

  /**
   * 建立连接。
   *
   * async 是必须的：握手签名要等 WebCrypto。core 的 `WebSocketManager` 强制校验
   * `token/ts/nonce/sig`，缺一即 1008，所以不能"签不上就先连上去试试"。
   */
  async function connect(token: string, wsBaseUrl: string) {
    if (ws && ws.readyState <= WebSocket.OPEN) return;

    shouldReconnect = true;
    fatalReason.value = '';
    status.value = 'connecting';
    // 同源模式下 baseUrl 为空串，此时以当前页面 origin 兜底，避免依赖
    // WebSocket 构造器对相对 URL 的解析（部分环境会抛 SyntaxError）
    const origin = (wsBaseUrl || window.location.origin).replace(/^http/, 'ws');
    const path = '/ws/realtime';
    // 浏览器 WebSocket 不支持自定义 Header，token 与设备签名一律走 query
    let query: string;
    try {
      query = await (await loadDeviceIdentity()).signWsQuery(token, path);
    } catch {
      // 设备密钥不可用（IndexedDB 被清、隐私模式限制等）：重连解决不了，直接置终态
      ws = null;
      status.value = 'disconnected';
      fatalReason.value = '设备身份不可用，请重新登录';
      return;
    }
    try {
      ws = new WebSocket(`${origin}${path}?${query}`);
    } catch {
      // 构造失败（URL 非法）不可能靠重试解决，直接置终态，避免异常逃出调用方的 onMounted
      ws = null;
      status.value = 'disconnected';
      fatalReason.value = 'WebSocket 地址无效';
      return;
    }

    ws.onopen = () => {
      status.value = 'connected';
      reconnectAttempts = 0;
      // 订阅频道走 core:contract 镜像（WS_UI_TOPICS = core 广播全集 - notification，
      // 因为同一条告警 core 会 notification + alert 双发，UI 只认 alert）
      ws!.send(JSON.stringify({ subscribe: WS_UI_TOPICS }));
    };

    ws.onmessage = (event) => {
      lastMessage.value = Date.now();
      try {
        const msg = JSON.parse(event.data);
        const type = msg.type as string;
        const data = msg.data;
        // 分发给注册的 handler
        const handlers = messageHandlers.get(type);
        if (handlers) handlers.forEach((h) => h(data));
        // 同时分发给通配符订阅
        const allHandlers = messageHandlers.get('*');
        if (allHandlers) allHandlers.forEach((h) => h(msg));
      } catch {
        // 忽略解析错误
      }
    };

    ws.onclose = (event) => {
      status.value = 'disconnected';
      ws = null;
      // core 的两个主动关闭码需要区分对待（见 WebSocketManager）：
      // 1008 VIOLATED_POLICY = token 无效，重连一万次也不会成功，REST 侧 401 会跳登录；
      // 1013 TRY_AGAIN_LATER = 连接数超上限（App + Web 共享 4 个），可重试但要有次数上限。
      if (event.code === 1008) {
        shouldReconnect = false;
        fatalReason.value = '鉴权失败，请重新登录';
        return;
      }
      if (event.code === 1013) {
        fatalReason.value = '设备连接数已达上限';
      }
      scheduleReconnect(token, wsBaseUrl);
    };

    ws.onerror = () => {
      ws?.close();
    };
  }

  function scheduleReconnect(token: string, wsBaseUrl: string) {
    if (!shouldReconnect || reconnectTimer) return;
    if (reconnectAttempts >= maxReconnectAttempts) {
      shouldReconnect = false;
      if (!fatalReason.value) fatalReason.value = '连接中断，请刷新页面重试';
      return;
    }
    const delay = Math.min(1000 * Math.pow(2, reconnectAttempts), maxReconnectDelay);
    reconnectAttempts++;
    reconnectTimer = setTimeout(() => {
      reconnectTimer = null;
      void connect(token, wsBaseUrl);
    }, delay);
  }

  function disconnect() {
    shouldReconnect = false;
    if (reconnectTimer) {
      clearTimeout(reconnectTimer);
      reconnectTimer = null;
    }
    ws?.close();
    ws = null;
    reconnectAttempts = 0;
    fatalReason.value = '';
    status.value = 'disconnected';
  }

  return { status, lastMessage, fatalReason, connect, disconnect, on };
});

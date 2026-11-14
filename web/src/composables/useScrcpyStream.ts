import { ref, shallowRef, type Ref } from 'vue';
import { ScrcpyControlMessageWriter, ScrcpyOptions3_3_3 } from '@yume-chan/scrcpy';
import { TinyH264Decoder } from '@yume-chan/scrcpy-decoder-tinyh264';
// ★ 必须用库自带的那个 ReadableStream：它会 monkey-patch 全局实现补上
// `values` / `[Symbol.asyncIterator]`，而库的类型声明要求这两个成员。
// 直接用全局 `new ReadableStream()` 在运行时能跑，但 tsc 过不了（实测 TS2345）。
import { Consumable, ReadableStream } from '@yume-chan/stream-extra';
import { getApiClient } from '@/composables/useApi';
import { loadDeviceIdentity } from '@/composables/deviceIdentityLazy';
import { useAppStore } from '@/stores/app';

/**
 * 远程屏幕的视频流（2026-10-31，S5）。
 *
 * ## 链路
 * `POST /api/screen/session` 拿票据 → `WS /ws/screen`（query 验签 + 票据）
 * → 字节流 → `parseVideoStreamMetadata()` 吃掉 76 字节流头 → `createMediaStreamTransformer()`
 * 切成 H.264 包 → `TinyH264Decoder` 画到 canvas。
 *
 * ## 为什么默认用 tinyh264 而不是 WebCodecs
 * WebCodecs 只在 **secure context**（HTTPS / localhost）可用，而设备默认是纯 HTTP 局域网。
 * tinyh264 是 WASM 软解，不要求 secure context、不要求 SharedArrayBuffer（因此**不需要**
 * COOP/COEP 响应头）。代价是客户端 CPU，480×848@20fps 完全吃得下。
 *
 * ## 版本锁
 * `options` 的版本类必须与 core 的 `ScrcpyServerLauncher.SERVER_VERSION` 一致。
 * 不一致时 server 照样能起（首参只做比对），但**流头的解析会对不上**，
 * 表现为"连上了、没画面"。所以这里先比对 `/api/screen/session` 回显的版本，不匹配直接报错。
 */

type Status = 'idle' | 'connecting' | 'streaming' | 'error';

/**
 * 与 core 的 `ScrcpyServerLauncher.SERVER_VERSION`（3.3.3）锁死的兼容前缀。
 *
 * 改成别的版本时**三处一起改**：这里、core 的常量、assets 里的 jar。
 * 见 `dev-docs/UFI-AXIS-远程屏幕-实施手册-v0.5.md` S3 段（为什么不是 5.0.1）。
 */
const SUPPORTED_VERSION_PREFIX = '3.3';

export function useScrcpyStream(canvas: Ref<HTMLCanvasElement | null>) {
  const status = ref<Status>('idle');
  const error = ref('');
  /** 视频真实尺寸（设备像素）。输入坐标必须换算到这个尺寸，不能用画布 CSS 尺寸。 */
  const meta = ref<{ width: number; height: number } | null>(null);
  /** 控制消息出口（与视频同一条 WS 的写方向） */
  const writer = shallowRef<ScrcpyControlMessageWriter | null>(null);
  /** 真正发出 WebSocket 帧的控制消息数（不是 injectTouch 的调用次数） */
  /** 视频编码（1748121140 = H.264，见 ScrcpyVideoCodecId） */
  const codec = ref(0);
  /** 实际生效的画面参数（服务端钳制后的值从 /session 回显里取） */
  const activeParams = ref({ maxSize: 848, maxFps: 20, bitRate: 2000000 });
  const ctrlFrames = ref(0);
  const ctrlBytes = ref(0);
  const ctrlError = ref('');
  const decoder = shallowRef<TinyH264Decoder | null>(null);

  let ws: WebSocket | null = null;
  let closing = false;
  /**
   * 被我们**主动**关掉的 socket。
   *
   * 原来只用一个全局 `closing` 布尔，而 `connect()` 一开头就把它重置成 false ——
   * 于是"切分辨率 → 断开旧连接 → 建新连接"时，旧 socket 的 close 事件晚一步到达，
   * 看到的 closing 已经是 false，就被当成意外错误弹出「client closed」。
   * 改成按 socket 实例记账，谁被主动关只认谁。
   */
  const intentionallyClosed = new WeakSet<WebSocket>();
  let connecting = false;

  /** 连接看门狗的句柄与"第几次尝试"，见 connect() 里的说明。 */
  let watchdogTimer: ReturnType<typeof setTimeout> | null = null;
  let connectAttempt = 0;
  /**
   * WS 关闭的真实原因。
   *
   * 必须有它：服务端拒会话时走 `close(1013, reason)`，此时**流是空的**，
   * `parseVideoStreamMetadata()` 会抛 `ExactReadable ended` —— 那是个纯粹的
   * 症状描述（"读不满 76 字节流头"），跟真正的原因（reverse 失败、server 没起来）
   * 毫无关系。没有这个变量，用户只会看到"流解析失败"，排障要跨三端找。
   * （2026-10-31 真机就是这么被误导的。）
   */
  let closeDetail = '';

  function teardown() {
    closing = true;
    const old = ws;
    if (old) intentionallyClosed.add(old);
    try {
      old?.close(1000, 'client closed');
    } catch {
      // 已经关了：忽略
    }
    ws = null;
    try {
      decoder.value?.dispose();
    } catch {
      // dispose 失败不影响后续重连
    }
    decoder.value = null;
    writer.value = null;
    meta.value = null;
  }

  function disconnect() {
    if (watchdogTimer !== null) {
      clearTimeout(watchdogTimer);
      watchdogTimer = null;
    }
    teardown();
    status.value = 'idle';
    connecting = false;
  }

  async function connect(params = { maxSize: 848, maxFps: 20, bitRate: 2000000 }) {
    // 防重入：第二次 connect() 开头的 teardown() 会把第一条 WS 掐掉，
    // 服务端随之结束会话、杀掉 server —— 表现是"第一次点就失败"，
    // 而且第二条还会撞 BUSY。前端按钮能连点，这一层必须挡住。
    //
    // 注意顺序：原来先排看门狗再 return，重入时每次都多埋一个定时器。
    if (connecting) return;
    connecting = true;

    // 看门狗：`new WebSocket(...)` / `ReadableStream` 构造在下面的 try **之外**，
    // 同步抛错会绕过所有 catch，`connecting` 永久为 true（按钮失效、状态卡在「连接中…」，
    // 而 unhandledrejection 被全局忽略，只能刷新页面）。20 秒后只要还没进 streaming 就复位。
    //
    // 定时器必须能被取消、且认「第几次尝试」：用户在第 19 秒重连时，上一次的定时器
    // 会在 1 秒后把**新**连接判成超时（connecting 被清、状态变 error），
    // 表现成"手快一点就必然失败"。
    const myAttempt = ++connectAttempt;
    if (watchdogTimer !== null) clearTimeout(watchdogTimer);
    watchdogTimer = setTimeout(() => {
      watchdogTimer = null;
      if (myAttempt !== connectAttempt) return;
      if (connecting && status.value !== 'streaming') {
        connecting = false;
        if (status.value !== 'error') {
          status.value = 'error';
          error.value = error.value || '连接超时';
        }
      }
    }, 20_000);
    error.value = '';
    teardown();
    closing = false;
    status.value = 'connecting';

    // ① 票据（头部鉴权；开关关着时这里就 403）
    const api = getApiClient();
    let ticket = '';
    let serverVersion = '';
    try {
      const { data } = await api.post('/api/screen/session', {
        max_size: params.maxSize,
        max_fps: params.maxFps,
        bit_rate: params.bitRate,
      });
      // 服务端会把越界值钳到边界，以回显为准
      if (typeof data?.max_size === 'number') {
        activeParams.value = {
          maxSize: data.max_size,
          maxFps: data.max_fps,
          bitRate: data.bit_rate,
        };
      }
      ticket = String(data?.ticket ?? '');
      serverVersion = String(data?.scrcpy_version ?? '');
    } catch (e: unknown) {
      const msg = (e as { response?: { data?: { message?: string } } })?.response?.data?.message;
      error.value = msg ?? '拿不到投屏票据（功能未开启或设备不在线）';
      status.value = 'error';
      connecting = false;
      return;
    }

    // ② 版本协商：不匹配时**明确报错**，不要让人对着黑屏猜
    if (!serverVersion.startsWith(SUPPORTED_VERSION_PREFIX)) {
      error.value = `投屏组件版本不匹配（设备 ${serverVersion || '未知'}，前端支持 ${SUPPORTED_VERSION_PREFIX}.x）`;
      status.value = 'error';
      connecting = false;
      return;
    }

    // ③ WS 握手（浏览器发不了自定义头 → query 验签，与 /ws/terminal 同一套）
    const path = '/ws/screen';
    let authQs = '';
    try {
      authQs = await (await loadDeviceIdentity()).signWsQuery(useAppStore().token, path);
    } catch {
      error.value = '设备身份不可用，请重新登录后重试';
      status.value = 'error';
      connecting = false;
      return;
    }

    // WS 必须打到**配置里的设备地址**，不是页面 origin。
    //
    // REST 走 `appStore.baseUrl`（登录页跨域时会 setBaseUrl）；只有投屏这里写死
    // `location.host` —— 跨域登录的用户
    // 能拿到票据、却把 WS 打到面板自己的域名上，表现为"一进页面就断开"。
    const base = useAppStore().baseUrl || location.origin;
    const wsUrl = new URL(base, location.origin);
    wsUrl.protocol = wsUrl.protocol === 'https:' ? 'wss:' : 'ws:';
    // 画面参数随 WS 一起传：它们是启动期固定的，改参数只能重开会话
    const qs =
      `${authQs}&ticket=${encodeURIComponent(ticket)}` +
      `&maxsize=${params.maxSize}&fps=${params.maxFps}&bitrate=${params.bitRate}`;
    const socket = new WebSocket(`${wsUrl.origin}${path}?${qs}`);
    socket.binaryType = 'arraybuffer';
    ws = socket;

    // ④ 把 WS 的字节流包成 ReadableStream，交给库去解析
    const byteStream = new ReadableStream<Uint8Array>({
      start(controller) {
        socket.onmessage = (ev: MessageEvent) => {
          // 只接受二进制帧。text 帧说明服务端/中间层动过手脚，直接忽略而不是当字节用。
          if (ev.data instanceof ArrayBuffer) controller.enqueue(new Uint8Array(ev.data));
        };
        socket.onclose = (ev: CloseEvent) => {
          try {
            controller.close();
          } catch {
            // 已经关过
          }
          if (closing) return;
          // reason 优先，不分码：服务端会把诊断写进来（例如 `scrcpy-eof bytes=64`），
          // 那比任何前端文案都值钱。
          if (intentionallyClosed.has(socket)) return; // 我们自己关的：不报错
          if (ev.reason) {
            closeDetail = ev.reason;
          } else if (ev.code === 1013) {
            closeDetail = '已有投屏会话在进行，或设备暂不可用';
          } else if (ev.code === 1008) {
            closeDetail = '凭据或票据无效，请重新登录';
          } else if (ev.code !== 1000) {
            closeDetail = `连接已断开（code ${ev.code}）`;
          }
          if (closeDetail) {
            // 无条件覆盖：catch 里那句「流解析失败：ExactReadable ended」只是症状，
            // 而且它可能比 close 事件先跑。关闭原因是服务端给的，优先级更高。
            error.value = closeDetail;
            status.value = 'error';
          }
        };
        socket.onerror = () => {
          if (intentionallyClosed.has(socket)) return;
          error.value = '连接错误';
          status.value = 'error';
        };
      },
    });

    try {
      const options = new ScrcpyOptions3_3_3({
        audio: false,
        control: true,
        video: true,
        maxSize: 1280,
        maxFps: 20,
        videoBitRate: 2_000_000,
        stayAwake: true,
        clipboardAutosync: false,
      });

      // ⑤ 控制通道：scrcpy 的控制消息与视频走**同一条 socket**，只是一上一下。
      //    Consumable.WritableStream 的 sink 直接收到 Uint8Array，内部负责 consume。
      const controlStream = new Consumable.WritableStream<Uint8Array>({
        write(chunk) {
          // 这里原来写的是「WS 不是 OPEN 就静默丢弃」——于是"发了 10 条但设备没反应"
          // 和"一条都没发出去"在页面上长得一模一样。现在两个都记数、失败也记原因。
          if (socket.readyState !== WebSocket.OPEN) {
            ctrlError.value = `WS 状态 ${socket.readyState}，控制消息被丢弃`;
            return;
          }
          socket.send(chunk);
          ctrlFrames.value += 1;
          ctrlBytes.value += chunk.byteLength;
        },
      });
      const controlWriter = new ScrcpyControlMessageWriter(controlStream.getWriter(), options);
      writer.value = controlWriter;

      // ⑥ 吃掉流头（64B 设备名 + codec + 宽高），拿真实尺寸
      const video = await options.parseVideoStreamMetadata(byteStream);

      if (!canvas.value) throw new Error('画布未就绪');
      const dec = new TinyH264Decoder({ canvas: canvas.value });
      decoder.value = dec;
      codec.value = video.metadata.codec;
      if (video.metadata.width && video.metadata.height) {
        meta.value = { width: video.metadata.width, height: video.metadata.height };
        // 画布像素尺寸 = 视频尺寸；CSS 尺寸由布局决定，指针换算按两者的比例做
        canvas.value.width = video.metadata.width;
        canvas.value.height = video.metadata.height;
      }

      // 尺寸变化只发生在流重建时；同步 canvas 的像素尺寸，CSS 尺寸由布局决定
      video.stream
        .pipeThrough(options.createMediaStreamTransformer())
        // 不 await：这是一条活到会话结束的长流，卡在这里会让 connect() 永远不返回
        .pipeTo(dec.writable)
        .catch((e: unknown) => {
          // 正常收尾（unmount / 换参数）与真实解码错误都走这里，必须分开：
          // 一律吞掉的话，解码器挂了界面仍停在「已连接」，画布冻结且没有任何提示。
          // `AbortError` 是主动取消（disconnect/卸载），其余都按失败处理。
          const name = (e as { name?: string } | null)?.name;
          if (name === 'AbortError' || status.value === 'idle') return;
          error.value = '画面解码中断，请重试';
          status.value = 'error';
          console.warn('[screen] decode stream ended', e);
        });

      status.value = 'streaming';
      connecting = false;
    } catch (e: unknown) {
      // 主动断开（切参数、切页面）时流会被中止，这不是错误
      if (intentionallyClosed.has(socket)) {
        connecting = false;
        status.value = 'idle';
        return;
      }
      const msg = e instanceof Error ? e.message : String(e);
      // 服务端先关流的话，真正的失败原因在 closeDetail 里；`ExactReadable ended`
      // 只是"没读到 76 字节流头"的症状，不能拿它当结论。
      error.value = closeDetail || `流解析失败：${msg}`;
      status.value = 'error';
      connecting = false;
      teardown();
    } finally {
      // 兜底：`new WebSocket(...)` / `ReadableStream` 构造在**既有 try 之外**，
      // 同步抛错（非法 URL、浏览器限额）会绕过上面的 catch，`connecting` 就永久是 true ——
      // 按钮从此失效、状态钉在「连接中…」，且 unhandledrejection 被全局忽略，只能刷新页面。
      if (connecting && status.value !== 'streaming') {
        connecting = false;
        if (status.value !== 'error') {
          status.value = 'error';
          error.value = error.value || '连接失败';
        }
      }
    }
  }

  return {
    status,
    error,
    decoder,
    meta,
    codec,
    activeParams,
    writer,
    ctrlFrames,
    ctrlBytes,
    ctrlError,
    connect,
    disconnect,
  };
}

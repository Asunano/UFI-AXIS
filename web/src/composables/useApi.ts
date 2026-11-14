import axios from 'axios';
import type { AxiosInstance } from 'axios';
import { useAppStore } from '@/stores/app';
import { router } from '@/router';
import { loadDeviceIdentity } from '@/composables/deviceIdentityLazy';
import { calibrateClockOffset } from '@/composables/deviceIdentity';
import { releaseActiveTab } from '@/composables/tabLock';
import { clearCachedOwnerPassword, getCachedOwnerPassword, requestOwnerPassword } from '@/composables/ownerPassword';

let apiClient: AxiosInstance | null = null;

export function getApiClient(): AxiosInstance {
  if (apiClient) return apiClient;

  const client = axios.create({
    timeout: 15_000,
    headers: { 'Content-Type': 'application/json' },
  });
  apiClient = client;

  // 请求拦截器：Bearer Token + 设备签名。
  //
  // 拦截器是 async 的：签名要等 WebCrypto，而 core 的 AuthMiddleware **强制**校验
  // X-Timestamp / X-Nonce / X-Signature，缺一即 444。所以这里不能"能签就签、签不上就算了"，
  // 只有 /pairing/* 免鉴权路径才允许无签名通过。
  client.interceptors.request.use(async (config) => {
    const appStore = useAppStore();
    if (appStore.token) {
      config.headers.Authorization = `Bearer ${appStore.token}`;
    }
    // 动态 baseURL：如果 appStore 有 baseUrl 则覆盖
    if (appStore.baseUrl) {
      config.baseURL = appStore.baseUrl;
    }
    // 必须用 client.getUri(config) 而不是 config.url：axios 在**拦截器之后**才把
    // config.params 序列化进 URL，直接签 config.url 会漏掉 query（如
    // `/api/files/list?path=%2F`），而服务端验签用的是完整 `call.request.uri`，
    // 结果就是所有带 params 的请求 100% 验签失败（444）。getUri 走的正是 axios
    // 自己发请求时的那套序列化，逐字节一致。
    // deviceIdentity 含 @noble crypto，按需动态加载（脱离首屏 chunk）。
    const devId = await loadDeviceIdentity();
    const uri = devId.toSignableUri(client.getUri(config), config.baseURL ?? '');
    // /pairing/* 走挑战-应答，不需要请求签名（此刻还没有 token）
    if (!uri.startsWith('/pairing/')) {
      const signed = await devId.signRequest(config.method ?? 'GET', uri);
      Object.assign(config.headers, signed);
    }
    return config;
  });

  // 响应拦截器：鉴权失败 → 跳转登录。
  //
  // **只有 444（吊销类）才丢凭据**。core AuthMiddleware：
  // - 444 = token 无效 / 未配对 / 必须重新配对 → 清 token 回登录
  // - 401 = 时间戳/签名/重放（可重试）→ 不能 clearAuth
  // 旧实现把 401 也当吊销，测速等并发场景下一次签名竞态就会把用户踢下线
  //（2026-09-08 / 2026-09-12 同类事故）。
  //
  // 2026-10-17 新增：**401 时用 /health 重新校准时钟并重试一次**。
  // 原因：core 跑在没有电池的 F50 上，重启后设备时钟回到出厂值，而浏览器时钟是对的；
  // 之前偏差只存在内存里，页面一刷新就归零 → 之后每个签名请求都 STALE_TIMESTAMP → 401。
  // 现场表现就是"登录明明成功，控制台整片 401"。重试只做一次（`_ufiClockRetried`），
  // 且用裸 fetch 取 /health，不经过本 client（否则 401→校准→401 会自我递归）。
  client.interceptors.response.use(
    (res) => res,
    async (err) => {
      const status = err.response?.status;
      if (status === 444) {
        const appStore = useAppStore();
        // 2026-10-21（审计 §B8）：444 有两种成因，别让用户对着登录页猜。
        // `/code` 由 AuthMiddleware 统一下发（DEVICE_BLOCKED / UNAUTHORIZED / ...）。
        const code = err.response?.data?.code;
        appStore.setAuthFailureReason(
          code === 'DEVICE_BLOCKED'
            ? '本设备已被机主拉黑，无法继续使用。请联系设备主人解除拉黑后重新配对。'
            : '登录凭据已失效（可能已被移除或重新配对），请重新登录。'
        );
        // 保留设备地址：掉线是"这台设备不认我了"，不是"我不知道设备在哪"。
        appStore.clearAuth({ keepBaseUrl: true });
        // 换过一次身份了，旧设备的主人口令不该留在内存里继续给下一次操作用。
        clearCachedOwnerPassword();
        // 2026-10-21（决策 D7-3）：掉线即释放"活跃标签页"锁 —— 否则同源其它标签页
        // 会一直被提示"已被另一个标签页接管"，而实际上那个标签页已经退出了。
        releaseActiveTab();
        // 2026-10-21（审计 §B8）：与「手动退出」做同样的收尾 —— 之前 444 只清 token，
        // 实时通道用旧 token 继续重连、能力集还留着上一台设备的声明（换设备后门禁会错）。
        // 用动态 import 避开 useApi ↔ stores 的静态循环依赖（这里已在异步上下文）。
        void (async () => {
          try {
            const [{ useWebSocketStore }, { useCapabilitiesStore }] = await Promise.all([
              import('@/stores/websocket'),
              import('@/stores/capabilities'),
            ]);
            useWebSocketStore().disconnect();
            useCapabilitiesStore().reset();
          } catch {
            /* 清理失败不影响回登录页 */
          }
        })();
        router.push('/login');
        return Promise.reject(err);
      }
      // ── 主人密码门（core 的 OwnerGate）──
      //
      // 只有「破坏性操作保护」开关打开时 core 才会回这个码；关着时整段不会命中，
      // 默认配置下用户永远不会看到密码框。
      //
      // 重试走请求头而不是改请求体：这个拦截器不认识每个端点的 body 形状（有的是 JSON，
      // 有的是备份包文件流），而 core 的 `requireOwner` 已经支持 body 缺失时读
      // `X-Owner-Password`。一次输入之后缓存在内存里，同一次会话的后续操作不再弹框。
      const { code: errorCode, message: errorMessage } = await readErrorBody(err);
      if (errorCode === 'INVALID_PASSWORD') {
        // 缓存的那个不再有效（改过密码 / 之前输错）：清掉，并**再问一次**。
        // 只清不重问的话，用户看到的是一次「保存失败」，既不知道是密码错、也没机会重输
        // （App 端 2026-11-14 修的是同一个问题，这里对齐成最多追问 2 次）。
        clearCachedOwnerPassword();
        if (status === 401 && !err.config?._ufiOwnerRetried) {
          err.config._ufiOwnerRetried = true;
          const password = await requestOwnerPassword('配对密码不正确，请重新输入');
          if (!password) return Promise.reject(err);
          applyOwnerPasswordHeader(err.config, password);
          return client.request(err.config);
        }
      }
      if (status === 401 && errorCode === 'MISSING_PASSWORD' && !err.config?._ufiOwnerRetried) {
        err.config._ufiOwnerRetried = true;
        const password = getCachedOwnerPassword() ?? (await requestOwnerPassword(errorMessage));
        if (!password) return Promise.reject(err);
        applyOwnerPasswordHeader(err.config, password);
        // 重发走同一个实例：请求拦截器会重新签名（时间戳/nonce 复用会触发重放拒绝）。
        return client.request(err.config);
      }

      // 2026-10-21（审计 §A3）：校准重试只对**要签名**的 /api/* 有意义。
      // `/pairing/*` 走的是挑战-应答、不验签名，重试纯属副作用：
      // 它会打到 confirm 的 500ms 每 IP 限频上（把"密码错"变成 429"操作过于频繁"），
      // 慢一点的情况还会把一次密码失败记成两次（提前 15 分钟锁定）。
      const url = String(err.config?.url ?? '');
      // 只对「签名/时间戳」这类**可自愈**的 401 做校时重试。
      // 密码错（`INVALID_PASSWORD`）也是 401：重试只会用同一个错密码再撞一次，
      // 而 core 的 PasswordAttemptLimiter 每次失败都计数（5 次 / 15 分钟）——
      // 一次手滑就变成两次，默认配置下「解除全部配对」输错 3 次就锁 15 分钟。
      const clockRetryable = errorCode == null || errorCode === 'STALE_TIMESTAMP' || errorCode === 'INVALID_SIGNATURE';
      if (status === 401 && clockRetryable && !err.config?._ufiClockRetried && !url.startsWith('/pairing/')) {
        err.config._ufiClockRetried = true;
        const recalibrated = await recalibrateClockFromHealth(client, err.config);
        if (recalibrated) {
          // 重新发一次：请求拦截器会用新偏差重新签名
          return client.request(err.config);
        }
      }
      return Promise.reject(err);
    }
  );

  return client;
}

/**
 * 用 `/health` 的服务器时间重新校准签名时钟。
 *
 * 用裸 `fetch` 而不是本 axios 实例：`/health` 免鉴权，且走本实例会在 401 时再次进入
 * 本拦截器，形成"401 → 校准 → 401"的递归。
 *
 * @return true = 校准成功（调用方可以重试原请求）
 */
async function recalibrateClockFromHealth(client: AxiosInstance, config: { baseURL?: string }): Promise<boolean> {
  try {
    const base = config.baseURL ?? client.defaults.baseURL ?? '';
    const res = await fetch(`${base}/health`, { cache: 'no-store' });
    if (!res.ok) return false;
    const data = await res.json();
    if (data?.timestamp == null) return false;
    calibrateClockOffset(data.timestamp);
    return true;
  } catch {
    return false;
  }
}

/**
 * 取 core 错误体里的 `code` 与 `error`。
 *
 * ## 为什么不能直接读 `err.response.data.code`
 *
 * 备份导出用 `responseType: 'blob'`（`BackupPanel.vue:386`），错误体也是 **Blob** ——
 * `data.code` 恒为 `undefined`，于是「主人密码门」在导出这一条上完全失效：
 * 既不弹密码框，也不重发，用户只看到 core 原文「请输入配对密码」，界面上无处可输。
 *
 * 这里把 Blob 先读成文本再解析；解析不出来就当作"没有 code"，交给调用方走保守分支。
 */
async function readErrorBody(err: any): Promise<{ code?: string; message?: string }> {
  const data = err?.response?.data;
  if (data == null) return {};
  // Blob / File：只有这两种才有 text()（JSON 对象没有）
  if (typeof data === 'object' && typeof data.text === 'function') {
    try {
      const parsed = JSON.parse(await data.text());
      return {
        code: typeof parsed?.code === 'string' ? parsed.code : undefined,
        message: typeof parsed?.error === 'string' ? parsed.error : undefined,
      };
    } catch {
      return {};
    }
  }
  return {
    code: typeof data?.code === 'string' ? data.code : undefined,
    message: typeof data?.error === 'string' ? data.error : undefined,
  };
}

/**
 * 把主人密码写进请求头，准备重发。
 *
 * axios 的 config.headers 在 v1 里是 `AxiosHeaders`（有 `set`），但历史配置可能塞的是
 * 普通对象；两种都兜住，否则重试会静默不带头、然后稳定 401。
 */
function applyOwnerPasswordHeader(config: any, password: string): void {
  const headers = config.headers ?? (config.headers = {});
  if (typeof headers.set === 'function') headers.set('X-Owner-Password', password);
  else headers['X-Owner-Password'] = password;
}

/**
 * 重置 API 客户端（切换服务器时调用）
 */
export function resetApiClient() {
  apiClient = null;
}

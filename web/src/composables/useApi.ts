import axios from 'axios';
import type { AxiosInstance } from 'axios';
import { useAppStore } from '@/stores/app';
import { router } from '@/router';
import { loadDeviceIdentity } from '@/composables/deviceIdentityLazy';

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
  client.interceptors.response.use(
    (res) => res,
    (err) => {
      const status = err.response?.status;
      if (status === 444) {
        const appStore = useAppStore();
        appStore.clearAuth();
        router.push('/login');
      }
      return Promise.reject(err);
    }
  );

  return client;
}

/**
 * 重置 API 客户端（切换服务器时调用）
 */
export function resetApiClient() {
  apiClient = null;
}

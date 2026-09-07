import { isCancel, type AxiosInstance, type AxiosResponse } from 'axios';
import { getApiClient } from './useApi';
import { useRequestScope } from './useRequestScope';

export interface CancellableApi
  extends Pick<AxiosInstance, 'get' | 'post' | 'put' | 'delete' | 'patch'> {
  abortAll: () => void;
}

/**
 * 在 `useApi` 单例 client 之上包装出「随组件卸载自动取消」的客户端。
 *
 * 每次请求内部新建一个 AbortController 并纳入组件作用域（见 `useRequestScope`）；
 * 组件卸载时作用域会 abort 全部在途请求。被取消的请求**不会抛错到调用方**，
 * 而是 resolve 为 `{ __canceled: true }`，因此调用方
 * `const { data } = await api.get(...)` 得到 `data = undefined`，配合既有
 * `if (data)` 守卫自然跳过逻辑，不会触发错误 toast。
 *
 * 用法：视图里把 `const api = getApiClient()` 换成 `const api = useCancellableApi()` 即可，
 * 其余 `api.get/post/...` 调用无需改动。
 */
export function useCancellableApi(): CancellableApi {
  const scope = useRequestScope();
  const base = getApiClient();

  const wrap = <M extends 'get' | 'post' | 'put' | 'delete' | 'patch'>(
    method: M
  ): AxiosInstance[M] => {
    const orig = base[method] as (...args: unknown[]) => Promise<AxiosResponse>;
    const fn = (url: unknown, body?: unknown, config?: Record<string, unknown>) => {
      const controller = scope.newController();
      const merged = { ...(config ?? {}), signal: controller.signal };
      return orig(url, body, merged).catch((err: unknown) => {
        if (isCancel(err)) {
          // 取消即视为「无结果」，避免冒泡成错误。
          return { __canceled: true, data: undefined, status: 0 } as unknown as AxiosResponse;
        }
        throw err;
      });
    };
    return fn as unknown as AxiosInstance[M];
  };

  return {
    get: wrap('get'),
    post: wrap('post'),
    put: wrap('put'),
    delete: wrap('delete'),
    patch: wrap('patch'),
    abortAll: scope.abortAll,
  };
}

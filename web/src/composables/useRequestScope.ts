import { getCurrentInstance, onUnmounted } from 'vue';

/**
 * 组件级请求生命周期作用域。
 *
 * 跟踪本组件发出的所有 AbortController，在组件卸载时一并 abort，
 * 避免「结果已无人需要」的在途请求继续占用带宽、或把响应写回已卸载的组件
 * （图表类组件尤为明显：setOption 打到已卸载实例会报错 / 浪费算力）。
 */
export function useRequestScope() {
  const controllers = new Set<AbortController>();

  const newController = (): AbortController => {
    const c = new AbortController();
    controllers.add(c);
    return c;
  };

  const abortAll = () => {
    controllers.forEach((c) => {
      try {
        c.abort();
      } catch {
        /* noop */
      }
    });
    controllers.clear();
  };

  if (getCurrentInstance()) {
    onUnmounted(abortAll);
  }

  return { newController, abortAll };
}

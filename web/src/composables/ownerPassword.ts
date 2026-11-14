/**
 * 「主人密码」弹窗的桥（破坏性操作保护）。
 *
 * ## 为什么需要
 *
 * core 的 `OwnerGate` 只管一件事：开关打开时，不可逆操作要多一次配对密码。而密码在
 * **请求发出之前**谁也不该知道，所以客户端必须有办法在收到 `401 MISSING_PASSWORD` 之后
 * 问用户要一次，然后把同一个请求重发出去。
 *
 * 拦截器（`useApi.ts`）不在组件里，拿不到 `useDialog()`；本仓既有做法是「模块级状态 +
 * 根组件渲染」（见 `composables/globalError.ts` 与 `FatalErrorOverlay`），这里照抄同一形状：
 * 拦截器 `await requestOwnerPassword()`，`OwnerPasswordDialog` 负责真正的输入。
 *
 * ## 密码只放内存
 *
 * 不做 localStorage / sessionStorage：它是**设备主人身份**，XSS 一旦能读就等于拿走设备。
 * 页面刷新后重新问一次是可以接受的代价。这与 2026-10-30 那版对票据的取舍一致
 *（服务端那张票据后来没实现，见 `OwnerGate` 的说明）。
 *
 * ## 并发
 *
 * 多个请求同时撞上 401 时只弹一次框：所有等待者排队，一次输入答复全部 ——
 * 反正它们要的是同一个密码。取消则全部按「未提供」失败，调用方各自把 401 抛给用户。
 */
import { readonly, ref } from 'vue';

/** 当前等待中的调用方。输入一次答复全部。 */
let pending: Array<(password: string | null) => void> = [];

/**
 * 已成功过一次的密码。
 *
 * 存在是为了「一次恢复出厂 + 清数据 + 重装组件」这种连续操作只问一次；
 * 校验失败（`INVALID_PASSWORD`）时由拦截器清掉，下一次重新问。
 */
let cached: string | null = null;
let cachedAt = 0;

/**
 * 缓存有效期（5 分钟），与 App 端 `OwnerPasswordGate.CACHE_TTL_MS` 对齐。
 *
 * 原先没有有效期：输过一次密码后，只要标签页不刷新，再点「恢复出厂」也不弹框 ——
 * 保护对用户"隐身"（App 端 2026-11-14 修过同一个问题，这里同步）。
 */
const CACHE_TTL_MS = 5 * 60 * 1000;

/** 等用户输入的截止时间：超时按「取消」处理，不能让调用方永久挂起。 */
const PROMPT_TIMEOUT_MS = 120 * 1000;

const visible = ref(false);
const hint = ref('');

/** 弹窗是否可见（`OwnerPasswordDialog` 读） */
export const ownerPasswordVisible = readonly(visible);

/** 给用户看的一句话：为什么突然要密码 */
export const ownerPasswordHint = readonly(hint);

/**
 * 请求一次主人密码。返回 `null` = 用户取消。
 *
 * @param reason core 返回的 `error` 文案，缺省用通用说法
 */
export function requestOwnerPassword(reason?: string): Promise<string | null> {
  return new Promise((resolve) => {
    if (pending.length === 0) {
      hint.value = reason || '该操作已开启主人密码保护，请输入配对密码。';
      visible.value = true;
    }
    // 超时兜底：弹窗没挂载（路由异常、浮层被卸载）或用户一直不答时，
    // 原来的 Promise 永远不 resolve —— 破坏性请求会一直挂在拦截器里，
    // 调用方的 loading 也永远不结束。App 端同样的等待是 120s。
    const timer = setTimeout(() => {
      const i = pending.indexOf(wrapped);
      if (i >= 0) pending.splice(i, 1);
      if (pending.length === 0) {
        visible.value = false;
        hint.value = '';
      }
      resolve(null);
    }, PROMPT_TIMEOUT_MS);
    const wrapped = (value: string | null) => {
      clearTimeout(timer);
      resolve(value);
    };
    pending.push(wrapped);
  });
}

/**
 * 弹窗提交（`null` = 取消）。
 *
 * 只有非空密码才进缓存 —— 取消不该把上一次的正确密码抹掉（用户可能只是手滑关了框）。
 */
export function submitOwnerPassword(password: string | null): void {
  const value = password && password.length > 0 ? password : null;
  if (value) {
    cached = value;
    cachedAt = Date.now();
  }
  visible.value = false;
  const waiting = pending;
  pending = [];
  hint.value = '';
  waiting.forEach((resolve) => resolve(value));
}

/** 已缓存的密码（拦截器先查它，命中就不弹框） */
export function getCachedOwnerPassword(): string | null {
  if (!cached) return null;
  if (Date.now() - cachedAt >= CACHE_TTL_MS) {
    // 过期即视为没有：让拦截器重新问，而不是拿旧密码去撞服务端的失败计数。
    cached = null;
    cachedAt = 0;
    return null;
  }
  return cached;
}

/** 校验失败 / 退出登录时清掉缓存 */
export function clearCachedOwnerPassword(): void {
  cached = null;
  cachedAt = 0;
}

import { defineStore } from 'pinia';
import { ref, computed } from 'vue';
import { resetInsecureNoticeMute } from '@/composables/utils';

const LS_TOKEN = 'ufi-token';
const LS_BASE_URL = 'ufi-baseUrl';
const LS_DARK_MODE = 'ufi-darkMode';
const LS_DEVICE_FP = 'ufi-deviceFingerprint';

export const useAppStore = defineStore('app', () => {
  // ── 认证状态（从 localStorage 恢复） ──
  const token = ref(localStorage.getItem(LS_TOKEN) ?? '');
  const baseUrl = ref(localStorage.getItem(LS_BASE_URL) ?? '');
  const darkMode = ref(localStorage.getItem(LS_DARK_MODE) === 'true');
  /**
   * 本浏览器的设备指纹，由 `/pairing/confirm` 响应回显（服务端据公钥计算）。
   * 只用于配对界面高亮"本浏览器"。**不参与任何鉴权**——真实身份是 IndexedDB 里
   * 那把不可导出的私钥，这里存的只是它的公开哈希，被改写也没有任何安全后果。
   */
  const deviceFingerprint = ref(localStorage.getItem(LS_DEVICE_FP) ?? '');

  // 启动时同步 darkMode class
  if (darkMode.value) document.documentElement.classList.add('dark');

  const isAuthenticated = computed(() => !!token.value);

  function setAuth(serverUrl: string, authToken: string) {
    baseUrl.value = serverUrl.replace(/\/$/, '');
    token.value = authToken;
    localStorage.setItem(LS_TOKEN, authToken);
    localStorage.setItem(LS_BASE_URL, baseUrl.value);
  }

  /** 仅设置 baseUrl（密码登录流程中，先定位设备再写回 token 时需要）。 */
  function setBaseUrl(url: string) {
    baseUrl.value = url.replace(/\/$/, '');
    localStorage.setItem(LS_BASE_URL, baseUrl.value);
  }

  /** 仅写回 token（经配对确认拿到 token 后调用）。 */
  function setToken(authToken: string) {
    token.value = authToken;
    localStorage.setItem(LS_TOKEN, authToken);
  }

  /** 写回服务端算出的设备指纹（配对确认响应里的 `fingerprint`）。 */
  function setDeviceFingerprint(fingerprint: string) {
    deviceFingerprint.value = fingerprint;
    if (fingerprint) localStorage.setItem(LS_DEVICE_FP, fingerprint);
    else localStorage.removeItem(LS_DEVICE_FP);
  }

  function clearAuth() {
    token.value = '';
    baseUrl.value = '';
    localStorage.removeItem(LS_TOKEN);
    localStorage.removeItem(LS_BASE_URL);
    // 退出/掉线是一次会话结束，HTTP 明文提示的"一天内不再提示"随之失效
    resetInsecureNoticeMute();
  }

  function toggleDarkMode() {
    darkMode.value = !darkMode.value;
    document.documentElement.classList.toggle('dark', darkMode.value);
    localStorage.setItem(LS_DARK_MODE, String(darkMode.value));
  }

  return {
    token,
    baseUrl,
    darkMode,
    deviceFingerprint,
    isAuthenticated,
    setAuth,
    setBaseUrl,
    setToken,
    setDeviceFingerprint,
    clearAuth,
    toggleDarkMode,
  };
});

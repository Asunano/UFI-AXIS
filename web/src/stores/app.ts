import { defineStore } from 'pinia';
import { ref, computed } from 'vue';
import { resetInsecureNoticeMute } from '@/composables/utils';
import { normalizeThemeId } from '@/composables/themePresets';

const LS_TOKEN = 'ufi-token';
const LS_BASE_URL = 'ufi-baseUrl';
const LS_DARK_MODE = 'ufi-darkMode';
const LS_DEVICE_FP = 'ufi-deviceFingerprint';
const LS_THEME_ID = 'ufi-themeId';

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

  /**
   * 当前配色皮肤 id。写进 `<html data-theme>`，CSS 侧靠 `[data-theme='xxx']` 覆盖颜色令牌。
   *
   * 读取时就 normalize：localStorage 里可能留着已下线的皮肤 id，
   * 直接用会得到「设置里显示 A、实际渲染默认皮肤」的不一致（见 themePresets.ts）。
   */
  const themeId = ref(normalizeThemeId(localStorage.getItem(LS_THEME_ID)));

  // 启动时同步 darkMode class 与 data-theme。两者都是「内存状态 → DOM」的单向同步，
  // 唯一真源是上面两个 ref，不要在别处再读 DOM 反推。
  if (darkMode.value) document.documentElement.classList.add('dark');
  document.documentElement.dataset.theme = themeId.value;

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

  /**
   * 切换配色皮肤。
   *
   * 只改一个 DOM 属性就够了：naive 组件色（composables/naiveTheme.ts）与 ECharts 色
   * （composables/chartTheme.ts）都是运行时读 CSS 变量、并且声明了对 `themeId` 的依赖，
   * 会跟着重算 —— 不需要在这里通知任何消费方。
   */
  function setThemeId(id: string) {
    themeId.value = normalizeThemeId(id);
    document.documentElement.dataset.theme = themeId.value;
    localStorage.setItem(LS_THEME_ID, themeId.value);
  }

  return {
    token,
    baseUrl,
    darkMode,
    themeId,
    deviceFingerprint,
    isAuthenticated,
    setAuth,
    setBaseUrl,
    setToken,
    setDeviceFingerprint,
    clearAuth,
    toggleDarkMode,
    setThemeId,
  };
});

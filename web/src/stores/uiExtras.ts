/**
 * 界面小功能（天气 + 每日诗词）的配置与数据。
 *
 * 为什么是 store 而不是各组件自己取：这两块同时出现在**顶栏挂件**与**设置面板**里，
 * 而设置面板改完开关后顶栏必须立刻跟上。各自取数的话，改完要么顶栏不变（用户以为没保存），
 * 要么得在两个组件间连一根 emit 链 —— 那条链会随着以后多一处展示位而越来越长。
 *
 * 真源在设备端（core 的 `AppSettings`，与手机端共享同一份配置）：
 * - 天气：`GET/PUT /api/weather/config`，数据 `GET /api/weather`
 * - 诗词：`GET/PUT /api/poetry/config`，数据 `GET /api/poetry`
 *
 * 取数节流与手机端同一口径（10 分钟）：core 自己也有缓存（天气 15 分钟 / 诗词 10 分钟），
 * 客户端再节流一层是为了**不把请求打到 core 的 QoS 许可上** —— 顶栏挂件在每次路由切换、
 * 每次窗口重新可见时都会想刷一次，不节流就是白打。
 */
import { defineStore } from 'pinia';
import { ref } from 'vue';
import { getApiClient } from '@/composables/useApi';
import { Endpoints, WeatherUnit, type GeoInfo, type PoetryNow, type WeatherNow } from '@/api/contract';

/** 客户端节流窗口。与手机端 `WeatherModule` / `PoetryModule` 的 10 分钟一致。 */
const REFRESH_THROTTLE_MS = 10 * 60 * 1000;

/**
 * 天气配置。
 *
 * 本地初值必须与 core 的 `WeatherConfig` 默认值逐字一致：回读失败时界面沿用这份初值，
 * 写反就是「界面显示开着、设备上其实关着」的假开关。
 * `latitude === 0 && longitude === 0` 是 core 侧"未设城市"的哨兵。
 */
export interface WeatherConfig {
  enabled: boolean;
  city: string;
  latitude: number;
  longitude: number;
  unit: string;
}

/** 诗词配置。core 侧默认 `enabled = false` / `show_origin = true`。 */
export interface PoetryConfig {
  enabled: boolean;
  show_origin: boolean;
}

function emptyWeatherConfig(): WeatherConfig {
  return { enabled: false, city: '', latitude: 0, longitude: 0, unit: WeatherUnit.CELSIUS };
}

function emptyPoetryConfig(): PoetryConfig {
  return { enabled: false, show_origin: true };
}

export const useUiExtrasStore = defineStore('uiExtras', () => {
  // ── 天气 ──
  const weatherConfig = ref<WeatherConfig>(emptyWeatherConfig());
  const weather = ref<WeatherNow | null>(null);
  const weatherLoading = ref(false);
  /** 配置是否已回读成功。设置面板靠它决定开关能不能动 —— 没读到就切换等于拿本地初值覆盖设备真值。 */
  const weatherConfigLoaded = ref(false);
  let weatherFetchedAt = 0;

  // ── 诗词 ──
  const poetryConfig = ref<PoetryConfig>(emptyPoetryConfig());
  const poetry = ref<PoetryNow | null>(null);
  const poetryLoading = ref(false);
  const poetryConfigLoaded = ref(false);
  let poetryFetchedAt = 0;

  // ── 国家/地区（诗词与天气上游选源的依据，只读展示）──
  const geo = ref<GeoInfo | null>(null);

  /**
   * 回读两份配置。
   *
   * 失败**静默**：这是装饰性功能，一次网络抖动不该在顶栏弹错误。
   * `*_configured` 保持 false，界面继续用本地初值（都是"关"，与 core 默认一致）。
   */
  async function loadConfigs() {
    const api = getApiClient();
    await Promise.all([
      api
        .get(Endpoints.weather.config)
        .then(({ data }) => {
          if (data) {
            weatherConfig.value = { ...emptyWeatherConfig(), ...data };
            weatherConfigLoaded.value = true;
          }
        })
        .catch(() => {
          /* 静默：老 core 没这个端点会 404 */
        }),
      api
        .get(Endpoints.poetry.config)
        .then(({ data }) => {
          if (data) {
            poetryConfig.value = { ...emptyPoetryConfig(), ...data };
            poetryConfigLoaded.value = true;
          }
        })
        .catch(() => {
          /* 静默 */
        }),
    ]);
  }

  /**
   * 取天气。
   *
   * @param force 跳过客户端节流（用户手动点刷新时用）。**不会**跳过 core 的 15 分钟缓存 ——
   *   连点刷新拿回同一份数据是正常的，界面上不要承诺"一定是最新"。
   *
   * 关着（`enabled = false`）或没设城市时直接不发请求：core 会回
   * `{configured: false}`，打一次只是为了确认一件本地已经知道的事。
   */
  async function fetchWeather(force = false) {
    if (!weatherConfig.value.enabled) return;
    if (!force && Date.now() - weatherFetchedAt < REFRESH_THROTTLE_MS) return;
    weatherLoading.value = true;
    try {
      const { data } = await getApiClient().get<WeatherNow>(Endpoints.weather.now);
      // `configured: false` 也照样收下：顶栏要据此显示"未设城市"而不是空白
      if (data) weather.value = data;
      weatherFetchedAt = Date.now();
    } catch {
      /* 上游不可达是 502，静默保留上一次的值 */
    } finally {
      weatherLoading.value = false;
    }
  }

  /**
   * 取诗词。
   *
   * @param force 带上 `refresh=1`。它只绕过 core 本地那 10 分钟缓存，
   *   上游 jinrishici 自己也有约 10 分钟缓存 —— 所以**完全可能拿回同一首**。
   */
  async function fetchPoetry(force = false) {
    if (!poetryConfig.value.enabled) return;
    if (!force && Date.now() - poetryFetchedAt < REFRESH_THROTTLE_MS) return;
    poetryLoading.value = true;
    try {
      const { data } = await getApiClient().get<PoetryNow>(Endpoints.poetry.now, {
        params: force ? { refresh: 1 } : undefined,
      });
      if (data?.content) poetry.value = data;
      poetryFetchedAt = Date.now();
    } catch {
      /* 上游不可达是 502，静默保留上一次的值 */
    } finally {
      poetryLoading.value = false;
    }
  }

  /**
   * 保存天气配置（字段级 patch）。
   *
   * core 会**校验**：纬度 -90..90、经度 -180..180、`unit` 只认两档、`city` ≤ 64 字符，
   * 越界回 400 + 中文 `error`。所以这里把错误抛给调用方显示，不像取数那样静默。
   *
   * 保存成功后重置节流并重新取数：改了城市还显示旧城市的天气就是在说假话。
   */
  async function saveWeatherConfig(patch: Partial<WeatherConfig>) {
    const { data } = await getApiClient().put(Endpoints.weather.config, patch);
    if (data?.config) {
      weatherConfig.value = { ...emptyWeatherConfig(), ...data.config };
      weatherConfigLoaded.value = true;
    } else {
      Object.assign(weatherConfig.value, patch);
    }
    weatherFetchedAt = 0;
    if (!weatherConfig.value.enabled) {
      // 关掉时清空数据：留着旧值会在下次打开的瞬间闪一下过时的温度
      weather.value = null;
    } else {
      await fetchWeather(true);
    }
  }

  /** 保存诗词配置（字段级 patch）。core 侧这一组**没有校验**，只可能因类型错误回 400。 */
  async function savePoetryConfig(patch: Partial<PoetryConfig>) {
    const { data } = await getApiClient().put(Endpoints.poetry.config, patch);
    if (data?.config) {
      poetryConfig.value = { ...emptyPoetryConfig(), ...data.config };
      poetryConfigLoaded.value = true;
    } else {
      Object.assign(poetryConfig.value, patch);
    }
    poetryFetchedAt = 0;
    if (!poetryConfig.value.enabled) {
      poetry.value = null;
    } else {
      await fetchPoetry(true);
    }
  }

  /**
   * 读国家/地区。`country` 为空串 = 从未探测成功，此时 `source` 恒为 `unknown`
   * （即便刚刚真的抓过一次），所以判"有没有结果"只看 `country`。
   */
  async function loadGeo() {
    try {
      const { data } = await getApiClient().get<GeoInfo>(Endpoints.geo.root);
      if (data) geo.value = data;
    } catch {
      /* 静默 */
    }
  }

  /** 顶栏挂载时调一次：先拿配置，再按开关决定要不要取数。 */
  async function init() {
    await loadConfigs();
    await Promise.all([fetchWeather(), fetchPoetry()]);
  }

  return {
    weatherConfig,
    weather,
    weatherLoading,
    weatherConfigLoaded,
    poetryConfig,
    poetry,
    poetryLoading,
    poetryConfigLoaded,
    geo,
    init,
    loadConfigs,
    loadGeo,
    fetchWeather,
    fetchPoetry,
    saveWeatherConfig,
    savePoetryConfig,
  };
});

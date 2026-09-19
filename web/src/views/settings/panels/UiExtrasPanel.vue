<!--
  界面小功能：天气 + 每日诗词。与手机端「界面小功能」（`UiExtrasSettingsScreen`）是同一页的两端镜像。

  配置真源在设备端（core 的 `AppSettings`），**app 与 web 共享同一份** —— 在这里改完，
  手机端下次进页面也会跟着变。所以每一项都是即时保存（字段级 PUT），没有"保存"按钮：
  两个开关 + 一个城市 + 一个单位，攒批提交只会让"改了没生效"多一种可能。

  天气有一个前置条件：**必须先选城市**（core 用 `latitude = 0 && longitude = 0` 当"未设置"的哨兵）。
  没选城市就打开开关等于开了一个永远显示不出东西的功能 —— 所以开关在未选城市时禁用，
  并在旁边说明原因，而不是让用户打开后自己去猜为什么顶栏还是空的。
-->
<template>
  <div class="settings-panel">
    <GridCard title="天气" :loading="loadingConfigs">
      <template #extra>
        <n-button size="small" quaternary :loading="store.weatherLoading" @click="store.fetchWeather(true)">
          刷新数据
        </n-button>
      </template>

      <ToggleRow
        :model-value="store.weatherConfig.enabled"
        :disabled="!store.weatherConfigLoaded || !hasCity"
        :loading="savingWeather"
        label="显示天气"
        description="在顶栏显示当前温度与天气，点击可看体感温度、湿度、风速与日出日落"
        @update:model-value="onWeatherEnabled"
      />
      <!-- 禁用必须给出原因：一个点不动又没有说明的开关，用户只会以为界面坏了 -->
      <span v-if="store.weatherConfigLoaded && !hasCity" class="row-hint">
        请先在下方选择城市。未选城市时设备端无法取到天气，开关也就打不开。
      </span>
      <span v-else-if="!store.weatherConfigLoaded" class="row-hint">
        尚未读到设备端的天气配置（较旧的设备端服务没有这个功能），暂不可设置。
      </span>

      <div class="field">
        <span class="field-label">城市</span>
        <!--
          filterable + remote：输入即调 /api/weather/search（上游 Open-Meteo 地理编码）。
          选项的 value 塞的是整份 {name, latitude, longitude} 的 JSON —— 经纬度必须跟着
          城市名一起提交，只存名字的话设备端拿不到坐标，等于没设。
        -->
        <n-select
          v-model:value="citySelection"
          filterable
          remote
          clearable
          size="small"
          placeholder="输入城市名搜索，如「杭州」"
          :options="cityOptions"
          :loading="searching"
          :render-label="renderCityLabel"
          @search="onCitySearch"
          @update:value="onCityPicked"
        />
        <span class="row-hint"> 当前：{{ currentCityText }} </span>
      </div>

      <div class="field">
        <span class="field-label">温度单位</span>
        <n-radio-group
          :value="store.weatherConfig.unit"
          size="small"
          :disabled="!store.weatherConfigLoaded"
          @update:value="onUnitChange"
        >
          <n-radio-button :value="WeatherUnit.CELSIUS">摄氏度 °C</n-radio-button>
          <n-radio-button :value="WeatherUnit.FAHRENHEIT">华氏度 °F</n-radio-button>
        </n-radio-group>
      </div>

      <div class="sub-panel">
        <span class="row-hint">
          数据来自 Open-Meteo，由设备端代为请求（无需 API Key），设备端缓存 15 分钟 ——
          点「刷新数据」在缓存期内会拿回同一份。
        </span>
      </div>
    </GridCard>

    <GridCard title="每日诗词" :loading="loadingConfigs">
      <template #extra>
        <n-button size="small" quaternary :loading="store.poetryLoading" @click="store.fetchPoetry(true)">
          换一句
        </n-button>
      </template>

      <ToggleRow
        :model-value="store.poetryConfig.enabled"
        :disabled="!store.poetryConfigLoaded"
        :loading="savingPoetry"
        label="显示每日诗词"
        description="在顶栏显示一句古诗，点击可看全篇、作者与译文"
        @update:model-value="(v: boolean) => savePoetry({ enabled: v })"
      />
      <ToggleRow
        :model-value="store.poetryConfig.show_origin"
        :disabled="!store.poetryConfigLoaded || !store.poetryConfig.enabled"
        :loading="savingPoetry"
        label="展开显示全篇"
        description="关闭后点击只显示作者与译文，不展开整首原文"
        @update:model-value="(v: boolean) => savePoetry({ show_origin: v })"
      />
      <span v-if="!store.poetryConfigLoaded" class="row-hint">
        尚未读到设备端的诗词配置（较旧的设备端服务没有这个功能），暂不可设置。
      </span>

      <div v-if="store.poetry" class="sub-panel">
        <span class="field-label">当前</span>
        <p class="preview-line">{{ store.poetry.content }}</p>
        <span class="row-hint">{{ store.poetry.title }} · {{ store.poetry.author }}</span>
      </div>

      <div class="sub-panel">
        <span class="row-hint">
          选哪一首由上游（今日诗词）按设备出口 IP 的地理位置、当地天气、时辰与节气决定，不能指定。 上游与设备端各有约 10
          分钟缓存，「换一句」完全可能拿回同一首。
        </span>
        <span v-if="geoText" class="row-hint">设备出口地区：{{ geoText }}</span>
      </div>
    </GridCard>
  </div>
</template>

<script setup lang="ts">
import { computed, h, onMounted, ref } from 'vue';
import { useMessage } from 'naive-ui';
import { getApiClient } from '@/composables/useApi';
import { Endpoints, WeatherUnit, type WeatherSearchResult } from '@/api/contract';
import { useUiExtrasStore, type PoetryConfig, type WeatherConfig } from '@/stores/uiExtras';
import GridCard from '@/components/GridCard.vue';
import ToggleRow from '@/components/ToggleRow.vue';

const message = useMessage();
const api = getApiClient();
const store = useUiExtrasStore();

const loadingConfigs = ref(false);
const savingWeather = ref(false);
const savingPoetry = ref(false);

/** core 用 `0, 0` 当"未设置"的哨兵，不是几内亚湾的某个点。 */
const hasCity = computed(() => store.weatherConfig.latitude !== 0 || store.weatherConfig.longitude !== 0);

const currentCityText = computed(() => {
  const c = store.weatherConfig;
  if (!hasCity.value) return '未设置';
  // 坐标一起显示：同名城市（全世界有很多个「Springfield」）靠名字分不出选中的是哪一个
  return `${c.city || '（未命名）'}（${c.latitude.toFixed(2)}, ${c.longitude.toFixed(2)}）`;
});

const geoText = computed(() => {
  const g = store.geo;
  // country 为空串 = 从未探测成功；此时 source 恒为 unknown，显示它没有意义
  return g?.country ? g.country : '';
});

// ── 城市搜索 ──

/** 选项的 value 是整份坐标的 JSON 串：经纬度必须与城市名一起提交。 */
interface CityOption {
  label: string;
  value: string;
  raw: WeatherSearchResult;
}

const citySelection = ref<string | null>(null);
const cityOptions = ref<CityOption[]>([]);
const searching = ref(false);

/** 同名城市靠 admin1（省/州）与 country 区分，所以标签要把它们带上。 */
function cityLabelOf(r: WeatherSearchResult): string {
  return [r.name, r.admin1, r.country].filter(Boolean).join(' · ');
}

/**
 * 候选项渲染：名字在左、坐标在右 —— 同名城市（全世界有很多个 Springfield）只靠名字分不出来。
 *
 * 样式走 inline 而不是 scoped class：下拉面板是 naive-ui teleport 出去的，
 * 本组件的 scoped 选择器（含 `:deep()`）到不了那棵子树，写成 class 的话坐标那行会完全没样式。
 */
function renderCityLabel(option: unknown) {
  const o = option as CityOption;
  return h('div', { style: 'display:flex;align-items:baseline;justify-content:space-between;gap:12px' }, [
    // min-width:0 + 省略号：坐标那段是 nowrap 不可压缩的，城市名不让位就会把整行撑破。
    // 「Springfield · Illinois · United States」这类标签在 298px 的下拉里必然超宽。
    h('span', { style: 'min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap' }, o.label),
    h(
      'span',
      { style: 'flex-shrink:0;font-size:11px;color:var(--text-muted);white-space:nowrap' },
      `${o.raw.latitude.toFixed(2)}, ${o.raw.longitude.toFixed(2)}`
    ),
  ]);
}

/**
 * 搜城市。core 要求 `name` 至少 1 个字符，空串直接清空候选而不发请求。
 * 上游不可达是 502，此处静默 —— 搜索框空着比弹一条错误更符合"再打几个字试试"的操作。
 */
async function onCitySearch(query: string) {
  const q = query.trim();
  if (!q) {
    cityOptions.value = [];
    return;
  }
  searching.value = true;
  try {
    const { data } = await api.get(Endpoints.weather.search, { params: { name: q } });
    const results: WeatherSearchResult[] = data?.results ?? [];
    cityOptions.value = results.map((r) => ({
      label: cityLabelOf(r),
      // 坐标进 value 保证唯一：同名同省的两个点也不会互相覆盖
      value: `${r.latitude},${r.longitude}`,
      raw: r,
    }));
  } catch {
    cityOptions.value = [];
  } finally {
    searching.value = false;
  }
}

/**
 * 选中城市：**城市名与经纬度一次提交**。
 * 只提交名字的话设备端拿不到坐标，配置写进去了但天气永远取不到 —— 那正是"假配置"。
 */
async function onCityPicked(value: string | null) {
  if (!value) return;
  const picked = cityOptions.value.find((o) => o.value === value);
  if (!picked) return;
  await saveWeather({
    city: picked.raw.name,
    latitude: picked.raw.latitude,
    longitude: picked.raw.longitude,
  });
  // 选完即清空输入框：留着一个"已选"状态会和下面那行「当前：」重复，且两者可能不一致
  citySelection.value = null;
  cityOptions.value = [];
}

// ── 保存 ──

/**
 * 天气配置字段级保存。
 *
 * core 会校验（纬度 -90..90、经度 -180..180、unit 两档、city ≤ 64 字符），越界回 400 + 中文原因 ——
 * 直接显示 core 的原文，web 不翻译第二份。失败时回读一次配置把界面拉回设备真值：
 * 保存失败却留着用户刚填的值，就是一个显示与设备不一致的假配置。
 */
async function saveWeather(patch: Partial<WeatherConfig>) {
  savingWeather.value = true;
  try {
    await store.saveWeatherConfig(patch);
  } catch (e: any) {
    const res = e?.response;
    message.error(res?.data?.error || res?.data?.message || '保存天气配置失败');
    await store.loadConfigs();
  } finally {
    savingWeather.value = false;
  }
}

/** 打开天气前必须已有城市。开关本身已 disabled，这里再挡一次防止别的入口绕过。 */
async function onWeatherEnabled(next: boolean) {
  if (next && !hasCity.value) {
    message.warning('请先选择城市');
    return;
  }
  await saveWeather({ enabled: next });
}

async function onUnitChange(unit: string) {
  await saveWeather({ unit });
}

async function savePoetry(patch: Partial<PoetryConfig>) {
  savingPoetry.value = true;
  try {
    await store.savePoetryConfig(patch);
  } catch (e: any) {
    const res = e?.response;
    message.error(res?.data?.error || res?.data?.message || '保存诗词配置失败');
    await store.loadConfigs();
  } finally {
    savingPoetry.value = false;
  }
}

onMounted(async () => {
  loadingConfigs.value = true;
  try {
    // 顶栏挂件已经拉过一次，但这里必须再拉：用户可能在手机端改过，
    // 而顶栏的节流窗口（10 分钟）不适用于"刚打开设置页"这个明确的查看意图。
    await store.loadConfigs();
    store.loadGeo();
  } finally {
    loadingConfigs.value = false;
  }
});
</script>

<style scoped>
/* .settings-panel 栅格与断点已统一到 src/styles/main.css（全局，各面板共用一份） */
.row-hint {
  font-size: 12px;
  color: var(--text-muted);
  line-height: 1.5;
}
.field {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 10px 0;
}
.field-label {
  font-size: 13px;
  color: var(--text-secondary);
}
.preview-line {
  margin: 2px 0;
  font-size: 13px;
  line-height: 1.6;
  color: var(--text-primary);
}
/* .sub-panel 是全局类；这里只补内部的纵向间距 */
.sub-panel {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
</style>

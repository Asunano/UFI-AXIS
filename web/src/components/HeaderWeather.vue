<!--
  顶栏天气挂件。与手机端 `UfiHeaderWeather` 是同一个功能的两端镜像。

  两条刻意的口径：
  1. **天气描述只显示 core 下发的 `description`**。WMO 天气码 → 中文的映射在 core 里
     （`WeatherRoutes.describe`），web 不留第二份 —— 抄一份的结果是 core 补了一档新码、
     这边显示成空白，或者同一种天在手机端和网页上叫两个名字。
  2. 关着、未设城市、或还没取到数时**整个挂件不渲染**。顶栏空间紧，
     显示一个"--°C"的占位只会让人以为功能坏了；未设城市的引导放在设置面板里。
-->
<template>
  <n-popover v-if="visible" trigger="click" placement="bottom-end" :width="300">
    <template #trigger>
      <div class="weather-chip" :title="chipTitle">
        <span class="weather-temp">{{ tempText }}</span>
        <span class="weather-desc">{{ w?.description || '' }}</span>
      </div>
    </template>

    <!-- 详情：与手机端 WeatherDetailDialog 同一组字段、同一顺序 -->
    <div class="weather-detail">
      <div class="detail-head">
        <span class="detail-city">{{ w?.city }}</span>
        <n-button size="tiny" quaternary :loading="store.weatherLoading" @click="store.fetchWeather(true)">
          刷新
        </n-button>
      </div>
      <div class="detail-now">
        <span class="detail-temp">{{ tempText }}</span>
        <span class="detail-desc">{{ w?.description }}</span>
      </div>
      <div class="detail-grid">
        <div v-for="row in detailRows" :key="row.label" class="detail-row">
          <span class="detail-label">{{ row.label }}</span>
          <span class="detail-value">{{ row.value }}</span>
        </div>
      </div>
      <!-- core 的 15 分钟缓存意味着这个时间可能比"刚点的刷新"早不少，所以显式标出来 -->
      <div v-if="updatedText" class="detail-foot">数据时间：{{ updatedText }}</div>
    </div>
  </n-popover>
</template>

<script setup lang="ts">
import { computed } from 'vue';
import { WeatherUnit } from '@/api/contract';
import { useUiExtrasStore } from '@/stores/uiExtras';

const store = useUiExtrasStore();

const w = computed(() => store.weather);

/** `configured: false` 时 core 只回一句 message，没有任何数值 —— 那种情况不渲染。 */
const visible = computed(() => store.weatherConfig.enabled && w.value?.configured === true);

/** 单位符号取响应里的 `unit`（而不是本地配置）：配置刚改完、数据还是上一单位时不会说错。 */
const unitSuffix = computed(() => (w.value?.unit === WeatherUnit.FAHRENHEIT ? '°F' : '°C'));

const fmt = (v: number | undefined): string => (typeof v === 'number' ? `${Math.round(v)}${unitSuffix.value}` : '--');

const tempText = computed(() => fmt(w.value?.temperature));

const chipTitle = computed(() => {
  const c = w.value;
  if (!c) return '';
  return [c.city, c.description, tempText.value].filter(Boolean).join(' · ');
});

const detailRows = computed(() => {
  const c = w.value;
  if (!c) return [];
  return [
    { label: '体感温度', value: fmt(c.apparent_temperature) },
    { label: '今日范围', value: `${fmt(c.temp_min)} ~ ${fmt(c.temp_max)}` },
    { label: '湿度', value: typeof c.humidity === 'number' ? `${c.humidity}%` : '--' },
    { label: '降水', value: typeof c.precipitation === 'number' ? `${c.precipitation} mm` : '--' },
    // 风速单位由上游给定（km/h），core 不换算，这里也不换算
    { label: '风速', value: typeof c.wind_speed === 'number' ? `${c.wind_speed} km/h` : '--' },
    { label: '日出 / 日落', value: [hhmm(c.sunrise), hhmm(c.sunset)].join(' / ') },
  ];
});

/**
 * 日出日落是上游给的 ISO 本地时间串（`2026-09-19T05:48`），**不带时区**。
 * 用 `new Date()` 解析会按浏览器时区再偏移一次，而设备可能在另一个时区 ——
 * 所以直接截取 `HH:mm`，不做任何时间运算。
 */
function hhmm(iso: string | undefined): string {
  if (!iso) return '--';
  const at = iso.indexOf('T');
  return at >= 0 ? iso.slice(at + 1, at + 6) : iso;
}

const updatedText = computed(() => {
  const ts = w.value?.updated_at;
  return ts ? new Date(ts).toLocaleString('zh-CN', { hour12: false }) : '';
});
</script>

<style scoped>
.weather-chip {
  display: inline-flex;
  align-items: baseline;
  gap: 6px;
  padding: 4px 10px;
  border-radius: 999px;
  background: var(--cat-neutral-bg);
  color: var(--cat-neutral-fg);
  font-size: 12px;
  line-height: 1;
  cursor: pointer;
  user-select: none;
  flex-shrink: 0;
  max-width: 180px;
}
.weather-chip:hover {
  background: var(--surface-hover);
}
.weather-temp {
  font-weight: 600;
  white-space: nowrap;
}
.weather-desc {
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  color: var(--text-secondary);
}

/* ── 详情浮层 ── */
.weather-detail {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.detail-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}
.detail-city {
  font-size: 14px;
  font-weight: 600;
  color: var(--text-primary);
}
.detail-now {
  display: flex;
  align-items: baseline;
  gap: 10px;
}
.detail-temp {
  font-size: 26px;
  font-weight: 600;
  line-height: 1;
  color: var(--text-primary);
}
.detail-desc {
  font-size: 13px;
  color: var(--text-secondary);
}
.detail-grid {
  display: flex;
  flex-direction: column;
}
.detail-row {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 12px;
  padding: 5px 0;
  border-bottom: 1px solid var(--border-subtle);
}
.detail-row:last-child {
  border-bottom: none;
}
.detail-label {
  font-size: 12px;
  color: var(--text-muted);
}
.detail-value {
  font-size: 12px;
  color: var(--text-primary);
  text-align: right;
}
.detail-foot {
  font-size: 11px;
  color: var(--text-muted);
}

/* 手机上只留温度：顶栏已经很挤，描述换行会把顶栏撑成两行 */
@media (max-width: 768px) {
  .weather-desc {
    display: none;
  }
  .weather-chip {
    padding: 6px 8px;
  }
}
</style>

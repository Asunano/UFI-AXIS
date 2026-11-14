<!--
  顶栏天气挂件。与手机端 `UfiHeaderWeather` 是同一个功能的两端镜像。

  两条刻意的口径：
  1. **天气描述只显示 core 下发的 `description`**。WMO 天气码 → 中文的映射在 core 里
     （`WeatherRoutes.describe`），web 不留第二份 —— 抄一份的结果是 core 补了一档新码、
     这边显示成空白，或者同一种天在手机端和网页上叫两个名字。
  2. 关着、未设城市、或还没取到数时**整个挂件不渲染**。顶栏空间紧，
     显示一个"--°C"的占位只会让人以为功能坏了；未设城市的引导放在设置面板里。
-->
<!--
  2026-10-31：这里的 n-popover **不能**用 `v-if="visible"`。
  那样在 visible 由 true 变 false 时（数据轮询、换一句、路由跳转）会直接卸载组件，
  跳过内部 <Transition> 的离场动画，Vue 抛 runtime-15
  「Cannot read properties of null (reading 'parentNode')」。
  改成常驻 + :disabled 控制交互、trigger 用 v-show 保持"关着不占位"的视觉。
-->
<template>
  <n-popover trigger="click" :disabled="!visible" placement="bottom-end" :width="300">
    <template #trigger>
      <div
        v-show="visible || loading"
        class="weather-chip"
        :class="{ 'weather-loading': loading }"
        :title="loading ? '天气加载中' : chipTitle"
      >
        <template v-if="visible">
          <span class="weather-temp">{{ tempText }}</span>
          <span class="weather-desc">{{ w?.description || '' }}</span>
        </template>
        <span v-else class="weather-loading-text">天气加载中…</span>
      </div>
    </template>

    <!-- 详情浮层（2026-11-07 改版）：温度优先，指标卡片化；
         与手机端 WeatherDetailDialog 同一组字段，顺序一致。 -->
    <div class="weather-detail">
      <div class="wx-hero">
        <span class="wx-temp">{{ tempText }}</span>
        <span class="wx-hero-right">
          <span v-if="wxGlyph" class="wx-glyph" aria-hidden="true">{{ wxGlyph }}</span>
          <span class="wx-desc">{{ w?.description }}</span>
        </span>
      </div>

      <div class="wx-sub">
        <span class="wx-city">{{ w?.city }}</span>
        <span v-if="updatedShort" class="wx-updated">· {{ updatedShort }} 更新</span>
        <span class="wx-grow"></span>
        <n-button size="tiny" quaternary :loading="store.weatherLoading" @click="store.fetchWeather(true)">
          刷新
        </n-button>
      </div>

      <div class="wx-metrics">
        <div v-for="m in metrics" :key="m.label" class="wx-metric">
          <span class="wx-metric-label">{{ m.label }}</span>
          <span class="wx-metric-value">{{ m.value }}</span>
        </div>
      </div>

      <div class="wx-extra">
        <span v-for="e in extras" :key="e.label"
          >{{ e.label }} <b>{{ e.value }}</b></span
        >
      </div>
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
/** 配置已开但数据还没回来（首次打开 / 网络慢）：顶栏先显示一个轻量占位，避免整块空白。 */
const loading = computed(
  () => store.weatherConfigLoaded && store.weatherConfig.enabled && !w.value && store.weatherLoading
);

/** 单位符号取响应里的 `unit`（而不是本地配置）：配置刚改完、数据还是上一单位时不会说错。 */
const unitSuffix = computed(() => (w.value?.unit === WeatherUnit.FAHRENHEIT ? '°F' : '°C'));

const fmt = (v: number | undefined): string => (typeof v === 'number' ? `${Math.round(v)}${unitSuffix.value}` : '--');

const tempText = computed(() => fmt(w.value?.temperature));

const chipTitle = computed(() => {
  const c = w.value;
  if (!c) return '';
  return [c.city, c.description, tempText.value].filter(Boolean).join(' · ');
});

/** 四项主指标：体感 / 今日范围 / 湿度 / 风速（顺序与手机端一致） */
const metrics = computed(() => {
  const c = w.value;
  if (!c) return [];
  return [
    { label: '体感', value: fmt(c.apparent_temperature) },
    { label: '今日', value: `${fmt(c.temp_min)} ~ ${fmt(c.temp_max)}` },
    { label: '湿度', value: typeof c.humidity === 'number' ? `${c.humidity}%` : '--' },
    // 风速单位由上游给定（km/h），core 不换算，这里也不换算
    { label: '风速', value: typeof c.wind_speed === 'number' ? `${c.wind_speed} km/h` : '--' },
  ];
});

/** 次级信息：降水与日出日落，压成一行 */
const extras = computed(() => {
  const c = w.value;
  if (!c) return [];
  return [
    { label: '降水', value: typeof c.precipitation === 'number' ? `${c.precipitation} mm` : '--' },
    { label: '日出', value: hhmm(c.sunrise) },
    { label: '日落', value: hhmm(c.sunset) },
  ];
});

/**
 * 天气图标：core 只下发中文描述，没有天气码，所以按关键字粗分五档。
 * 匹配不到就返回空串（宁可没有图标，也不要显示一个错的太阳）。
 * 更稳的做法是让 core 在天气接口里带一个 code —— 那时这里换查表即可。
 */
const wxGlyph = computed(() => {
  const d = w.value?.description || '';
  if (!d) return '';
  if (d.includes('雷')) return '⚡';
  if (d.includes('雪') || d.includes('冻')) return '❄';
  if (d.includes('雨')) return '☂';
  if (d.includes('雾') || d.includes('霾') || d.includes('沙')) return '≡';
  if (d.includes('阴')) return '☁';
  if (d.includes('云')) return '⛅';
  if (d.includes('晴')) return '☀';
  return '';
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

/**
 * 时间戳归一成毫秒。
 *
 * core 的 `updated_at` 可能是秒级 Unix 时间戳、毫秒级，或 ISO 串（不同上游/版本混着来）。
 * 直接把秒级数字丢给 `new Date()` 会被当成毫秒 → 显示成 1970 年，用户只会以为天气卡坏了。
 *
 * 判据用**量级**而不是位数：1e12 毫秒 ≈ 公元 33658 年，任何真实时间戳都到不了；
 * 反过来，秒级时间戳要到 1e12 秒才是公元 33658 年，所以「< 1e12 就是秒」这条不会误判。
 */
function toMillis(ts: unknown): number | null {
  if (typeof ts === 'number') {
    if (!Number.isFinite(ts) || ts <= 0) return null;
    return ts < 1e12 ? ts * 1000 : ts;
  }
  if (typeof ts === 'string') {
    const s = ts.trim();
    if (!s) return null;
    // 纯数字串按数字处理（"1758..." 这类），否则交给 Date.parse
    if (/^\d+(\.\d+)?$/.test(s)) return toMillis(Number(s));
    const parsed = Date.parse(s);
    return Number.isFinite(parsed) ? parsed : null;
  }
  return null;
}

const updatedShort = computed(() => {
  const ms = toMillis(w.value?.updated_at);
  if (ms == null) return '';
  return new Date(ms).toLocaleTimeString('zh-CN', {
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  });
});
</script>

<style scoped>
.weather-chip {
  position: relative;
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
/* hover 不再换到另一个令牌族。
   原先写的是 `background: var(--surface-hover)` —— 那是「透明底 + 3% 黑」，
   而静止态是 `--cat-neutral-bg` 的实色中性底，两态观感像是换了块东西（暗色下尤其明显：
   0.08 白 → 0.04 白，胶囊一下子变透明）。改成在**原底色上叠一层**同样的中性 hover 色，
   两态同族、只差一档明度，且与全站其它 hover 用的是同一个令牌。 */
.weather-chip::after {
  content: '';
  position: absolute;
  inset: 0;
  border-radius: inherit;
  background: var(--surface-hover);
  opacity: 0;
  transition: opacity 0.15s ease;
  pointer-events: none;
}
.weather-chip:hover::after {
  opacity: 1;
}
.weather-temp {
  font-weight: 600;
  white-space: nowrap;
}
.weather-loading-text {
  color: var(--text-muted);
  font-size: 11px;
  white-space: nowrap;
}
.weather-desc {
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  color: var(--text-secondary);
}

/* ── 详情浮层（B 方案：温度优先 + 指标小卡；无渐变底色） ── */
.weather-detail {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.wx-hero {
  display: flex;
  align-items: center;
  gap: 10px;
}
.wx-temp {
  font-size: 36px;
  font-weight: 700;
  line-height: 1;
  color: var(--text-primary);
  font-variant-numeric: tabular-nums;
}
.wx-hero-right {
  margin-left: auto;
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 2px;
  min-width: 0;
}
.wx-glyph {
  font-size: 22px;
  line-height: 1;
  color: var(--warning, #e0a020);
}
.wx-desc {
  font-size: 12.5px;
  color: var(--text-secondary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* 城市与更新时间：比其他次要文字大一档，避免"浮层里最看不清的是地名" */
.wx-sub {
  display: flex;
  align-items: baseline;
  gap: 6px;
  font-size: 13px;
  color: var(--text-secondary);
}
.wx-city {
  font-weight: 600;
  color: var(--text-primary);
}
.wx-updated {
  color: var(--text-muted);
  font-variant-numeric: tabular-nums;
}
.wx-grow {
  flex: 1;
}

.wx-metrics {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 8px;
}
.wx-metric {
  display: flex;
  flex-direction: column;
  gap: 1px;
  padding: 7px 10px;
  border: 1px solid var(--border-subtle);
  border-radius: 10px;
  background: var(--surface-elevated, var(--card-bg));
  min-width: 0;
}
.wx-metric-label {
  font-size: 11px;
  color: var(--text-muted);
}
.wx-metric-value {
  font-size: 13.5px;
  font-weight: 600;
  color: var(--text-primary);
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.wx-extra {
  display: flex;
  flex-wrap: wrap;
  gap: 4px 14px;
  font-size: 11.5px;
  color: var(--text-secondary);
}
.wx-extra b {
  font-weight: 600;
  color: var(--text-primary);
  font-variant-numeric: tabular-nums;
}

@media (max-width: 768px) {
  .weather-desc {
    display: none;
  }
  .weather-chip {
    padding: 6px 8px;
  }
}
@media (max-width: 1180px) {
  .weather-chip {
    display: none;
  }
}
</style>

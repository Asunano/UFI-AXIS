<template>
  <div class="gallery">
    <GridCard title="组件画廊">
      <template #extra>
        <div class="gallery-actions">
          <n-select
            :value="appStore.themeId"
            :options="themeOptions"
            size="small"
            style="width: 140px"
            @update:value="appStore.setThemeId"
          />
          <n-switch :value="appStore.darkMode" @update:value="appStore.toggleDarkMode()">
            <template #checked>暗色</template>
            <template #unchecked>浅色</template>
          </n-switch>
        </div>
      </template>
      <p class="gallery-desc">
        这一页是 UI 改动的人工验收工具：改了公共组件或 main.css 的全局类之后，先在这里逐段比对，
        再去翻真实页面。每一段的标题写的是「唯一实现在哪」，出现两种观感就说明有人又写了第二份。
        右上角的皮肤下拉与明暗开关走的都是全站那一套（`stores/app.ts`），不是本页私有的预览态；
        目前只注册了默认皮肤，加皮肤的口径见 `composables/themePresets.ts` 文件头。
      </p>
    </GridCard>

    <!-- ── 1. 令牌 ── -->
    <GridCard title="1. 令牌（唯一真源：styles/main.css）">
      <div class="swatch-grid">
        <div v-for="c in colorTokens" :key="c" class="swatch">
          <div class="swatch-chip" :style="{ background: `var(${c})` }"></div>
          <code>{{ c }}</code>
        </div>
      </div>
      <div class="token-rows">
        <div class="token-row">
          <span class="token-name">圆角</span>
          <span v-for="r in radiusTokens" :key="r" class="radius-chip" :style="{ borderRadius: `var(${r})` }">
            {{ r.replace('--radius-', '') }}
          </span>
        </div>
        <div class="token-row">
          <span class="token-name">间距</span>
          <span v-for="s in spaceTokens" :key="s" class="space-chip" :style="{ width: `var(${s})` }" :title="s"></span>
          <code class="token-note">--space-1 … --space-6</code>
        </div>
        <div class="token-row">
          <span class="token-name">字号</span>
          <span v-for="f in fontTokens" :key="f" :style="{ fontSize: `var(${f})` }">
            {{ f.replace('--font-', '') }}
          </span>
        </div>
        <div class="token-row">
          <span class="token-name">分类色</span>
          <span
            v-for="h in catHues"
            :key="h"
            class="cat-chip"
            :style="{ background: `var(--cat-${h}-bg)`, color: `var(--cat-${h}-fg)` }"
          >
            {{ h }}
          </span>
        </div>
      </div>
    </GridCard>

    <!-- ── 2. GridCard ── -->
    <GridCard title="2. 卡片容器（唯一实现：components/GridCard.vue）">
      <div class="demo-grid">
        <GridCard title="density=compact" density="compact">
          <InfoRow label="内距" value="8 / 12 / 12" />
        </GridCard>
        <GridCard title="density=default">
          <InfoRow label="内距" value="12 / 16 / 16" />
        </GridCard>
        <GridCard title="density=spacious" density="spacious">
          <InfoRow label="内距" value="16 / 20 / 20" />
        </GridCard>
        <GridCard title="accent 色条" accent="var(--accent-color)">
          <InfoRow label="用途" value="强调某张卡" />
        </GridCard>
        <GridCard title="collapsible" collapsible>
          <InfoRow label="点标题" value="可折叠" />
        </GridCard>
        <GridCard title="loading 骨架" :loading="true" :skeleton-repeat="2" />
        <GridCard title="带 footer">
          <InfoRow label="footer" value="右对齐动作区" />
          <template #footer>
            <n-button size="small">取消</n-button>
            <n-button size="small" type="primary">确定</n-button>
          </template>
        </GridCard>
      </div>
    </GridCard>

    <!-- ── 3. 三种开关行 ── -->
    <GridCard title="3. 三种开关版式（刻意并存，别互相替换）">
      <div class="section-subtitle">A · ToggleRow —— 全宽设置行，14px 标签 + 说明 + 分隔线</div>
      <ToggleRow v-model="demoToggleA" label="全宽设置行" description="有说明文字、占满整行时用这个" />
      <ToggleRow v-model="demoToggleB" label="禁用态" description="disabled 时整行 opacity 0.5" disabled />
      <ToggleRow label="#control 插槽" description="右侧不是开关时（数字框、下拉…）走插槽，标签排版仍只有一套">
        <template #control>
          <n-input-number v-model:value="demoNumber" size="small" style="width: 110px" />
        </template>
      </ToggleRow>

      <div class="section-subtitle gallery-gap">
        B · .config-grid + .switch-item —— 两列栅格里的紧凑单元格，13px 次要色标签
      </div>
      <div class="config-grid">
        <div class="config-item">
          <span class="config-label">普通字段</span>
          <n-input v-model:value="demoText" size="small" placeholder="与开关并排" />
        </div>
        <div class="config-item switch-item">
          <span class="config-label">栅格内开关</span>
          <n-switch v-model:value="demoToggleC" />
        </div>
        <div class="config-item full">
          <span class="config-label">.full 通栏变体</span>
          <n-switch v-model:value="demoToggleD" />
        </div>
      </div>
    </GridCard>

    <!-- ── 4. 二级分区 ── -->
    <GridCard title="4. 二级分区（唯一实现：main.css 的 .sub-panel）">
      <div class="sub-panel">
        <div class="section-subtitle">卡片内再分一层</div>
        <InfoRow label="内距" value="var(--space-3)" />
        <InfoRow label="圆角" value="var(--radius-sm)" />
        <InfoRow label="底色" value="var(--surface-elevated) —— 抬升，不是凹陷" />
      </div>
      <div class="sub-panel gallery-gap">
        <div class="demo-tile-grid">
          <div class="sub-panel demo-tile">嵌套也只有一种观感</div>
          <div class="sub-panel demo-tile">第二块</div>
        </div>
      </div>
    </GridCard>

    <!-- ── 5. 信息行与徽标 ── -->
    <GridCard title="5. InfoRow / ValueBadge">
      <InfoRow label="短值" value="OK" />
      <InfoRow label="超长值会省略号" value="AA:BB:CC:DD:EE:FF / 192.168.0.1 / 一段很长的说明文字用来触发省略" />
      <InfoRow label="插槽值">
        <ValueBadge label="RSRP" :value="-92" unit=" dBm" variant="outline" />
      </InfoRow>
      <div class="badge-row gallery-gap">
        <ValueBadge :value="42" unit="%" size="sm" />
        <ValueBadge label="tinted" :value="42" unit="%" />
        <ValueBadge label="lg 读数" :value="1024" unit=" KB/s" size="lg" />
        <ValueBadge label="outline" :value="42" unit="%" variant="outline" />
        <ValueBadge label="染色" :value="88" unit="%" color="var(--warning)" variant="outline" />
      </div>
    </GridCard>

    <!-- ── 6. 仪表 ── -->
    <GridCard title="6. 两个仪表组件（2026-09-08 判定不合并）">
      <div class="gauge-row">
        <div class="gauge-cell">
          <RingGauge :value="gaugeValue" :max="100" label="RingGauge" unit="%" :size="130" />
          <code>整圆 + HTML 文字层</code>
        </div>
        <div class="gauge-cell">
          <GaugeChart :value="gaugeValue" :max="100" :size="120" label="GaugeChart" unit="%" />
          <code>半圆 + SVG text + auto 阈值配色</code>
        </div>
        <div class="gauge-cell">
          <n-slider v-model:value="gaugeValue" :min="0" :max="100" style="width: 180px" />
          <code>拖动看两者动画差异（1s vs 0.6s）</code>
        </div>
      </div>
    </GridCard>

    <!-- ── 7. 按钮与动作区 ── -->
    <GridCard title="7. 按钮与卡片动作区（.card-actions 在 main.css）">
      <div class="btn-row">
        <n-button size="small">default</n-button>
        <n-button size="small" type="primary">primary</n-button>
        <n-button size="small" type="error">error</n-button>
        <n-button size="small" type="warning">warning</n-button>
        <n-button size="small" type="success">success</n-button>
        <n-button size="small" quaternary>quaternary</n-button>
        <n-button size="small" :loading="true">loading</n-button>
        <n-button size="small" disabled>disabled</n-button>
      </div>
      <p class="gallery-desc gallery-gap">
        ⚠ 若 primary 按钮与上面第 1 段的 <code>--accent-color</code> 色块不是同一个颜色， 说明
        <code>App.vue</code> 还没补 <code>themeOverrides</code>（计划 §W1）—— 这一页就是那条改动的验收位置。
      </p>
      <div class="card-actions">
        <n-button size="small">动作 A</n-button>
        <n-button size="small">动作 B</n-button>
        <n-button size="small" type="primary">主动作</n-button>
      </div>
    </GridCard>

    <!-- ── 8. 弹窗壳 ── -->
    <GridCard title="8. 弹窗壳（n-modal preset=card，与 GridCard 是两套外观）">
      <n-button size="small" @click="modalVisible = true">打开弹窗比对边框/圆角</n-button>
      <n-modal v-model:show="modalVisible" preset="card" title="弹窗壳" style="width: 420px">
        <ToggleRow v-model="demoToggleA" label="弹窗里的设置行" description="与页面里应当完全一致" />
        <div class="sub-panel gallery-gap">弹窗里的 .sub-panel</div>
        <template #footer>
          <div class="card-actions">
            <n-button size="small" @click="modalVisible = false">关闭</n-button>
          </div>
        </template>
      </n-modal>
    </GridCard>
  </div>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue';
import { useAppStore } from '@/stores/app';
import { THEME_PRESETS } from '@/composables/themePresets';
import GridCard from '@/components/GridCard.vue';
import ToggleRow from '@/components/ToggleRow.vue';
import InfoRow from '@/components/InfoRow.vue';
import ValueBadge from '@/components/ValueBadge.vue';
import RingGauge from '@/components/RingGauge.vue';
import GaugeChart from '@/components/GaugeChart.vue';

// 明暗切换刻意复用全站那一个开关（appStore + <html class="dark">），
// 不在本页另建一套预览态 —— 否则画廊看着对、真实页面却不对。
const appStore = useAppStore();
const themeOptions = computed(() => THEME_PRESETS.map((p) => ({ label: p.label, value: p.id })));

const colorTokens = [
  '--page-bg',
  '--card-bg',
  '--surface-elevated',
  '--surface-hover',
  '--border-subtle',
  '--accent-color',
  '--accent-color-light',
  '--success',
  '--warning',
  '--error',
  '--info',
  '--code-bg',
];
const radiusTokens = ['--radius-sm', '--radius-md', '--radius-lg', '--radius-pill'];
const spaceTokens = ['--space-1', '--space-2', '--space-3', '--space-4', '--space-5', '--space-6'];
const fontTokens = ['--font-xs', '--font-sm', '--font-base', '--font-md', '--font-lg', '--font-xl', '--font-2xl'];
/** 分类色十档。切到暗色时这一排应当变成「深底亮字」而不是浅底深字 —— 这是 W4 的验收位置 */
const catHues = ['green', 'blue', 'red', 'orange', 'brown', 'yellow', 'purple', 'teal', 'indigo', 'neutral'];

const demoToggleA = ref(true);
const demoToggleB = ref(false);
const demoToggleC = ref(true);
const demoToggleD = ref(false);
const demoNumber = ref(30);
const demoText = ref('');
const gaugeValue = ref(42);
const modalVisible = ref(false);
</script>

<style scoped>
.gallery {
  display: flex;
  flex-direction: column;
  gap: var(--space-4);
}
.gallery-actions {
  display: flex;
  align-items: center;
  gap: var(--space-3);
}
.gallery-desc {
  font-size: var(--font-sm);
  color: var(--text-muted);
  line-height: 1.6;
}
.gallery-gap {
  margin-top: var(--space-3);
}

/* 令牌段 */
.swatch-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(150px, 1fr));
  gap: var(--space-3);
}
.swatch {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  font-size: var(--font-xs);
  min-width: 0;
}
.swatch-chip {
  width: 28px;
  height: 28px;
  flex-shrink: 0;
  border: 1px solid var(--border-subtle);
  border-radius: var(--radius-sm);
}
.swatch code {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.token-rows {
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
  margin-top: var(--space-4);
}
.token-row {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  flex-wrap: wrap;
}
.token-name {
  min-width: 40px;
  font-size: var(--font-sm);
  color: var(--text-secondary);
}
.radius-chip {
  padding: 4px 10px;
  font-size: var(--font-xs);
  background: var(--surface-elevated);
  border: 1px solid var(--border-subtle);
}
.space-chip {
  height: 16px;
  background: var(--accent-color);
  border-radius: 2px;
}
.token-note {
  font-size: var(--font-xs);
  color: var(--text-muted);
}
.cat-chip {
  padding: 3px 10px;
  border-radius: var(--radius-pill);
  font-size: var(--font-xs);
  font-weight: 500;
}

/* 卡片段 */
.demo-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(240px, 1fr));
  gap: var(--space-3);
  align-items: start;
}

/* 二级分区段 */
.demo-tile-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: var(--space-3);
}
.demo-tile {
  font-size: var(--font-sm);
  color: var(--text-secondary);
}

/* 徽标 / 按钮 / 仪表 */
.badge-row,
.btn-row {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  flex-wrap: wrap;
}
.gauge-row {
  display: flex;
  align-items: center;
  gap: var(--space-6);
  flex-wrap: wrap;
}
.gauge-cell {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--space-2);
  font-size: var(--font-xs);
  color: var(--text-muted);
}
</style>

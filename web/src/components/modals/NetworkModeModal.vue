<!--
  网络模式选择弹窗。

  ──── 2026-09-19 修复：着色的选项不是真实选项 ────
  原来是 `n-radio-group > n-flex > n-radio-button`。naive-ui 的 `RadioGroup`（见
  `radio/src/RadioGroup.mjs` 的 `mapSlot`）**只认直系子节点**：它遍历自己的 children，
  只有 `type.name === 'RadioButton'` 时才把 `isButtonGroup` 置真、进而给容器加上
  `n-radio-group--button-group`。中间夹了一层 `n-flex`，那个类就永远加不上，而
  「选中态的描边与底色」全部写在该类之下 —— 于是真正选中的那一项没有任何视觉变化，
  看起来像是随便某一项被点亮了。

  这里不再依赖那套内部约定，改成**自己渲染选项行 + 显式 `.is-selected`**：
  高亮判据只有一处（`m.value === selectedMode`），看得见也测得到。

  ──── 为什么要把「当前档位」单独列出来 ────
  弹窗里有两个语义不同的值：设备当前真实档位，和用户正要选的那一项。合成一个
  高亮去表达，用户就无法回答"我到底改没改"。所以顶部单独一行显示设备当前档位
  （文案取 core 下发的 `network_mode_label`），选项行只表达"我选了哪个"，
  并给设备当前那一项额外标一个「当前」徽标。
-->
<template>
  <n-modal v-model:show="show" preset="card" title="网络模式" style="width: 460px; max-width: calc(100vw - 32px)">
    <!-- 设备当前档位。与下面的"待应用选项"是两件事，必须分开说 -->
    <div class="current-row">
      <span class="current-label">设备当前</span>
      <span class="current-value">{{ currentLabel || '读取中…' }}</span>
      <n-button size="tiny" quaternary :loading="refreshing" @click="emit('refresh')">重新读取</n-button>
    </div>

    <div class="mode-list" role="radiogroup" aria-label="网络模式">
      <button
        v-for="m in networkModes"
        :key="m.value"
        type="button"
        role="radio"
        :aria-checked="m.value === selectedMode"
        class="mode-item"
        :class="{ 'is-selected': m.value === selectedMode }"
        :disabled="switching"
        @click="selectedMode = m.value"
      >
        <span class="mode-dot" :class="{ 'dot-on': m.value === selectedMode }" />
        <span class="mode-label">{{ m.label }}</span>
        <!-- 设备当前那一档额外标出来：让"我选的"与"设备上的"一眼可辨 -->
        <n-tag v-if="m.value === currentMode" size="tiny" :bordered="false" type="success">当前</n-tag>
      </button>
    </div>

    <!-- 「切换中 / 尚未完成」由 useNetworkControls 的回读确认给出，不在这里猜设备状态 -->
    <n-text v-if="switching" :depth="3" class="mode-hint"> 正在切换，设备重新搜网需要一点时间 </n-text>
    <n-text v-else-if="timedOut" type="warning" class="mode-hint"> 设备尚未完成切换，可稍后刷新查看 </n-text>
    <!--
      读数陈旧的诚实说明：`GET /api/device/settings` 在设备端有 5 分钟缓存
      （`CacheTTL.DEVICE_SETTINGS`），而它只在 core 自己写入后才失效。
      从手机端或设备自带面板改过档位时，这里最长要等 5 分钟才跟上 —— 不写出来，
      用户只会以为网页坏了。
    -->
    <n-text v-else :depth="3" class="mode-hint">
      设备端对该配置有最长 5 分钟的读缓存；若刚在手机端或设备面板改过，这里可能稍后才跟上。
    </n-text>

    <template #footer>
      <div class="modal-footer">
        <n-button size="small" @click="show = false">取消</n-button>
        <n-button
          size="small"
          type="primary"
          :loading="modeLoading || switching"
          :disabled="switching || selectedMode === currentMode"
          @click="emit('apply')"
        >
          应用
        </n-button>
      </div>
    </template>
  </n-modal>
</template>

<script setup lang="ts">
import { computed } from 'vue';

const props = withDefaults(
  defineProps<{
    show: boolean;
    /** 用户正要应用的档位（双向） */
    selectedMode: string;
    modeLoading: boolean;
    networkModes: ReadonlyArray<{ label: string; value: string }>;
    /** 设备当前真实档位（别名）。空串 = 还没读到 */
    currentMode?: string;
    /**
     * 设备当前档位的中文名。**优先取 core 下发的 `network_mode_label`** ——
     * 那是全仓唯一文案真源（`NetworkMode.LABELS`），web 不留第二份映射表。
     */
    currentLabel?: string;
    /** 正在重新读取设备设置 */
    refreshing?: boolean;
    /** 切换已下发、正在等设备报出目标档位 */
    switching?: boolean;
    /** 上一次切换在回读预算内没等到目标档位 */
    timedOut?: boolean;
  }>(),
  { currentMode: '', currentLabel: '', refreshing: false, switching: false, timedOut: false }
);

const emit = defineEmits<{
  'update:show': [boolean];
  'update:selectedMode': [string];
  apply: [];
  /** 用户点「重新读取」：调用方去打一次 /api/device/settings */
  refresh: [];
}>();

const show = computed({
  get: () => props.show,
  set: (v) => emit('update:show', v),
});

const selectedMode = computed({
  get: () => props.selectedMode,
  set: (v) => emit('update:selectedMode', v),
});
</script>

<style scoped>
.modal-footer {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}

.mode-hint {
  display: block;
  margin-top: 12px;
  font-size: 12px;
  line-height: 1.5;
}

/* ── 当前档位（与待应用选项分开的一行）── */
.current-row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding-bottom: 12px;
  margin-bottom: 4px;
  border-bottom: 1px solid var(--border-subtle);
}
.current-label {
  font-size: 12px;
  color: var(--text-muted);
}
.current-value {
  font-size: 14px;
  font-weight: 600;
  color: var(--text-primary);
  margin-right: auto;
}

/* ── 选项列表 ──
   自绘而不是用 n-radio-button 的按钮组：见模板顶部注释。
   6 档纵排比横排分段器更好读，也不会因为换行破坏分段器的圆角。 */
.mode-list {
  display: flex;
  flex-direction: column;
}
.mode-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 11px 12px;
  border: 1px solid transparent;
  border-radius: var(--radius-sm);
  background: transparent;
  cursor: pointer;
  text-align: left;
  font: inherit;
  color: inherit;
  transition:
    background 0.15s,
    border-color 0.15s;
}
.mode-item:hover:not(:disabled) {
  background: var(--surface-hover);
}
.mode-item:disabled {
  cursor: not-allowed;
  opacity: 0.6;
}
.mode-item.is-selected {
  border-color: var(--accent-color);
  background: var(--accent-color-light);
}
.mode-label {
  flex: 1;
  min-width: 0;
  font-size: 14px;
  color: var(--text-primary);
}
/* 单选圆点：选中态填心。用描边+内点而不是 ✓，与 n-radio 的视觉习惯一致 */
.mode-dot {
  width: 16px;
  height: 16px;
  flex-shrink: 0;
  border-radius: 50%;
  border: 1.5px solid var(--border-subtle);
  position: relative;
}
.mode-dot.dot-on {
  border-color: var(--accent-color);
}
.mode-dot.dot-on::after {
  content: '';
  position: absolute;
  inset: 3px;
  border-radius: 50%;
  background: var(--accent-color);
}
</style>

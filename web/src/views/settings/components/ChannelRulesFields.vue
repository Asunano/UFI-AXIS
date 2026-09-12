<!--
  三条渠道（邮件 / Webhook / 本机短信）共用的「投递规则」旋钮：最低级别 + 每日上限 + 今日用量。

  为什么是一个组件而不是三份表单：2026-09-10 core 把这组规则做成同构（字段名与语义逐字一致，
  只有取值域刻意不同）。同构的**目的**就是让用户在任一渠道页上看到同一组旋钮 ——
  三处各写一遍的话，措辞、排布、校验提示必然各自跑偏，用户又得重新猜"这个渠道有没有这道闸"。

  取值域（`levels` / `daily_limit_min` / `daily_limit_max`）一律由调用方从该渠道的 GET 里取来，
  **web 不留第二份**：手抄一份时 core 改了范围这边不会报错，只会让用户撞一次 400。

  刻意不同的那一处（本机短信不允许「不限」）由 `dailyLimitHint` 按 `min` 说清楚，
  不靠用户撞校验错误才发现。
-->
<template>
  <div class="rules-fields">
    <div class="rules-grid">
      <div class="field">
        <span class="field-label">{{ MIN_LEVEL_LABEL }}</span>
        <n-select
          :value="minLevel"
          size="small"
          :options="levelOptions"
          :placeholder="levelsUnavailable ? '暂不可用' : '读取中'"
          :disabled="levelOptions.length === 0"
          @update:value="(v: string) => emit('update:minLevel', v)"
        />
        <!--
          取值域读不到时必须说出原因：「看得见旋钮却选不了、还没有任何说明」比缺这个旋钮更糟。
          区分两种情况靠 configLoaded —— 回读还没完成是"读取中"，回读完成了却没有 `levels`
          就只能是设备端版本较旧（那一版的 config 不回这个字段）。
        -->
        <span v-if="levelsUnavailable" class="row-hint">{{ LEVELS_UNAVAILABLE_NOTE }}</span>
        <template v-else>
          <span v-if="levelHintText" class="row-hint">{{ levelHintText }}</span>
          <span class="row-hint">{{ CRITICAL_OVERRIDE_LEVEL_NOTE }}</span>
        </template>
      </div>

      <div class="field">
        <span class="field-label">{{ DAILY_LIMIT_LABEL }}</span>
        <n-input-number
          :value="dailyLimit"
          :min="dailyLimitMin"
          :max="dailyLimitMax"
          size="small"
          style="width: 150px"
          @update:value="(v: number | null) => emit('update:dailyLimit', v)"
        >
          <template #suffix>条</template>
        </n-input-number>
        <span class="row-hint">{{ dailyLimitHintText }}</span>
        <!-- 只有本机短信按条计费，那句代价提示由它自己传进来 -->
        <span v-if="costNote" class="row-hint">{{ costNote }}</span>
      </div>
    </div>

    <!-- 用量与上限必须挨着显示：只给上限的话，用户无从判断"今天还发得出去吗" -->
    <div class="usage-row">
      <span class="field-label">{{ QUOTA_USAGE_LABEL }}</span>
      <span class="usage-value">{{ usageText }}</span>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue';
import { DAILY_LIMIT_UNLIMITED, type NotifyLevelWire } from '@/api/contract';
import {
  CRITICAL_OVERRIDE_LEVEL_NOTE,
  DAILY_LIMIT_LABEL,
  LEVELS_UNAVAILABLE_NOTE,
  MIN_LEVEL_LABEL,
  QUOTA_USAGE_LABEL,
  dailyLimitHint,
  levelHint,
  quotaUsageText,
  toLevelOptions,
} from '../notifyShared';

const props = withDefaults(
  defineProps<{
    /** 当前最低级别（wire name，小写）。 */
    minLevel: string;
    /** 当前每日上限；`0` = 不限（只有允许它的渠道才会传到 0）。 */
    dailyLimit: number | null;
    /**
     * 级别取值域，来自该渠道 config 的 `levels`。空数组 = 读不到，下拉此时禁用。
     * 每一项的**显示名由设备端给**（见 `NotifyLevelWire`），web 不翻译第二份。
     */
    levels: NotifyLevelWire[];
    /**
     * 该渠道的 config 是否已经回读完成。
     *
     * 只为了区分「还在读」与「读完了但设备端没给 `levels`」（老 core 的 config 不回这个字段）：
     * 后者要给一句解释，否则界面上就是一个没有任何说明的死下拉。
     */
    configLoaded: boolean;
    dailyLimitMin: number;
    dailyLimitMax: number;
    /** 只读用量位，来自该渠道 config。`quotaRemaining` 在不限时是 null。 */
    sentToday: number;
    quotaRemaining: number | null;
    /** 该渠道额外的代价说明（本机短信按条计费）。 */
    costNote?: string;
  }>(),
  { costNote: '' }
);

const emit = defineEmits<{
  'update:minLevel': [string];
  'update:dailyLimit': [number | null];
}>();

const levelOptions = computed(() => toLevelOptions(props.levels));
const levelHintText = computed(() => levelHint(props.minLevel));
const dailyLimitHintText = computed(() => dailyLimitHint(props.dailyLimitMin, props.dailyLimitMax));

/**
 * 「设备端没提供级别取值域」。
 *
 * 判据是**回读已完成但一个可选项都归一不出来** —— 那只可能是设备端版本较旧（那一版的
 * config 不回这个字段）。看的是归一后的结果而不是原始数组长度：形状认不出的项会被
 * [normalizeNotifyLevels] 丢掉，只数原始长度会得到一个"启用着但一项都没有"的死下拉。
 * web 侧不为此写一份兜底级别表：手抄的那份在 core 加档或改措辞时不会报错，只会静默显示错的名字。
 * 每日上限不受影响 —— 它的取值域有本地兜底，老 core 上照样可以改。
 */
const levelsUnavailable = computed(() => props.configLoaded && levelOptions.value.length === 0);

/**
 * 用量文案读的是**设备回的只读位**，不拿输入框里那个未保存的上限去算 ——
 * 改了输入框还没保存时，"剩余"必须仍然是设备侧的事实。
 */
const usageText = computed(() =>
  quotaUsageText({
    sent_today: props.sentToday,
    daily_limit: props.dailyLimit ?? DAILY_LIMIT_UNLIMITED,
    quota_remaining: props.quotaRemaining,
  })
);
</script>

<style scoped>
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
/* 两个旋钮并排：它们是一组（"什么级别、每天几条"），拆成上下两行会读成两件事 */
.rules-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 0 14px;
  align-items: start;
}
.usage-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 10px 0;
  border-bottom: 1px solid var(--border-subtle);
}
.usage-value {
  font-size: 13px;
  color: var(--text-primary);
}

@media (max-width: 768px) {
  .rules-grid {
    grid-template-columns: 1fr;
  }
}
</style>

<template>
  <div class="channel-row" :class="{ 'is-open': expanded }">
    <div class="channel-row-top">
      <div class="channel-row-info">
        <div class="channel-row-head">
          <span class="channel-row-title">{{ title }}</span>
          <n-tag size="tiny" :bordered="false" :type="status.type">{{ status.text }}</n-tag>
          <span v-if="meta" class="row-hint">{{ meta }}</span>
        </div>
        <div v-if="lines.length" class="channel-row-facts">
          <span v-for="l in lines" :key="l.label" class="channel-fact">
            <span class="row-hint">{{ l.label }}</span>
            <span class="channel-fact-value">{{ l.value }}</span>
          </span>
        </div>
        <span v-if="note" class="row-hint">{{ note }}</span>
        <span v-if="lastTest" class="row-hint">最近测试：{{ lastTest }}</span>
      </div>
      <div class="channel-row-actions">
        <slot name="actions" />
      </div>
    </div>

    <!-- 展开区：邮件那一行把整份 SMTP 表单放在这里，另两条渠道走弹窗、不用这个插槽 -->
    <div v-if="expanded" class="channel-row-body">
      <slot name="expanded" />
    </div>
  </div>
</template>

<script setup lang="ts">
/**
 * 通知渠道清单里的一行。
 *
 * 三条渠道原来是三张并排的摘要卡，问题有三个：
 *   · `.settings-panel` 在宽屏上会摊成多列，每列只剩 ~390px，长地址全靠截断；
 *     （2026-09-10 二次改后列数已固定为 2，这条降到「每列 ~780px」，但压成清单
 *     的理由不变 —— 三张卡并排仍然要横向扫三次，且行数不同底边参差。）
 *   · 三张卡内容行数不同（本机短信 4 行、Webhook 2 行），顶部对齐后底边参差；
 *   · 卡与卡之间没有可比性 —— 同样是"能不能发、发到哪、勾了几个场景"，却要横向扫三次。
 * 压成纵向清单后这三点一起消失，而且以后加第四条渠道就是加一行。
 *
 * 组件只管排版：状态判定、摘要文案、按钮行为全部由调用方给，
 * 因为每条渠道的"可投递"判据都来自设备端自己的字段（见 NotifyPanel 里各 status 计算）。
 */
/** 键值摘要的一项。不 export —— `<script setup>` 里不允许，调用方直接传字面量数组即可 */
interface ChannelFact {
  label: string;
  value: string;
}

withDefaults(
  defineProps<{
    title: string;
    status: { text: string; type: 'default' | 'success' | 'warning' | 'error' };
    /** 状态徽标右侧的补充信息：预设名、今日配额等 */
    meta?: string;
    /** 一行行的键值摘要（目标地址、最低级别、触发场景…） */
    lines?: ChannelFact[];
    /** 这条渠道是什么、有什么代价，一句话 */
    note?: string;
    lastTest?: string;
    /** 是否展开下方的 expanded 插槽 */
    expanded?: boolean;
  }>(),
  {
    meta: '',
    lines: () => [],
    note: '',
    lastTest: '',
    expanded: false,
  }
);
</script>

<style scoped>
.channel-row {
  padding: 14px 0;
  border-top: 1px solid var(--border-subtle);
}
/* 清单的第一行紧贴卡片标题，不需要再来一条分隔线 */
.channel-row:first-child {
  border-top: none;
  padding-top: 4px;
}
.channel-row:last-child {
  padding-bottom: 0;
}

.channel-row-top {
  display: flex;
  align-items: flex-start;
  gap: 16px;
}
.channel-row-info {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.channel-row-head {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.channel-row-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--text-primary);
}

/* 键值摘要横排：整行有 ~500px 可用，横排比原来那种"标签左、值右对齐"更紧凑，
   也不会因为各卡字段数不同而错位 */
.channel-row-facts {
  display: flex;
  flex-wrap: wrap;
  gap: 4px 18px;
}
.channel-fact {
  display: inline-flex;
  align-items: baseline;
  gap: 6px;
  min-width: 0;
}
/* 地址与号码可能很长：单行截断，完整值在配置弹窗里可见可改 */
.channel-fact-value {
  font-size: 13px;
  color: var(--text-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 340px;
}

.channel-row-actions {
  flex-shrink: 0;
  display: flex;
  align-items: center;
  gap: 6px;
}

.channel-row-body {
  margin-top: 12px;
  padding-top: 12px;
  border-top: 1px dashed var(--border-subtle);
}

@media (max-width: 768px) {
  /* 窄屏放不下"信息 + 按钮"两列：按钮落到下面一行并靠右 */
  .channel-row-top {
    flex-direction: column;
    gap: 10px;
  }
  .channel-row-actions {
    width: 100%;
    justify-content: flex-end;
  }
  .channel-fact-value {
    max-width: 60vw;
  }
}
</style>

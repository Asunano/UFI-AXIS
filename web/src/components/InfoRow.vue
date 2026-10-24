<template>
  <div class="info-row">
    <span class="info-label">{{ label }}</span>
    <span class="info-value" :title="valueTitle">
      <slot>{{ value }}</slot>
    </span>
  </div>
</template>

<script setup lang="ts">
import { computed, useSlots } from 'vue';

const props = defineProps<{
  label: string;
  value?: string | number;
}>();

const slots = useSlots();

/**
 * 值被省略号截断（见 `.info-value` 的 `max-width: 65%` + ellipsis）之后就看不全了，
 * 把同一份文本挂到 `title` 上，鼠标悬停能读全文。长值场景（SSID / ICCID / IMEI /
 * MAC / 长域名）都是靠这一条兜住可读性，卡片本身不用再改成 `word-break` 换行。
 *
 * 两种情况**不设** title：
 *  · `#default` 插槽有内容 —— 里面可能是徽标、按钮组、多个 span，无法文本化，
 *    硬拼一个 title 只会显示半句话或 `[object Object]`；
 *  · 值不是字符串 —— 数字（及缺省的 `undefined`）不会被截断，且 title 只接受字符串。
 */
const valueTitle = computed(() => (slots.default || typeof props.value !== 'string' ? undefined : props.value));
</script>

<style scoped>
.info-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 8px 0;
  border-bottom: 1px solid var(--border-subtle);
}
.info-row:last-child {
  border-bottom: none;
}
.info-label {
  font-size: 13px;
  color: var(--text-secondary);
  /* 不换行 —— 这一条是「1 个 InfoRow = 1 行」这条高度不变量的前提。
     没有它时，值那一侧的长内容（长 URL、长域名）会把标签挤到折行：
     实测 408px 列宽下「更新地址」的标签折成 2 行，该行 37 → 56px，
     于是整张卡的高度变成**宽度敏感量**，2 列栅格里按行数配好的等高配对
     会在窄列宽下失效（关于页同行参差 9 → 28px，反而是宽屏才整齐）。
     安全性：全站标签最长 13 字符（「启用 Webhook 通知」≈120px），
     最窄的卡（<1024 单列，手机上约 300px）仍装得下，不会溢出；
     值的侧照旧 shrink + 省略号。 */
  white-space: nowrap;
}
.info-value {
  font-size: 13px;
  color: var(--text-primary);
  font-weight: 500;
  text-align: right;
  /* 长值（如 SSID）不撑爆列宽：溢出省略号 + 限最大占比 */
  max-width: 65%;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
</style>

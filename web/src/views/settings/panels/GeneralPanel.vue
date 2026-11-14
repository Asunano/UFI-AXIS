<!--
  连接与端口（`?tab=general` 深链接的落地页）。

  2026-11-12：表单本体抽到 `components/ConnectionSettingsForm.vue` —— 设置首页现在用
  弹窗打开同一份表单（4 个输入框不值得一条路由和一整屏留白），这里只保留"深链接仍然能用"
  这一条职责。保存逻辑、配置回读、恢复默认都在共享组件里，本文件不持有任何表单状态。

  历史搬迁（别在这里加回来）：
  · 日志四开关 + 排障三开关 → LogsPanel.vue（同一域的开关要在一起）；
  · 短信验证码 → 短信页「验证码设置」弹窗；
  · 更新源（下载方式 / 出口地区）→ UpdatePanel.vue；
  · 封面预热 → 视频页「封面设置」；
  · 开机自启 → 服务控制卡（写同一个后端字段，两处会互不刷新）。
-->
<template>
  <div class="settings-panel">
    <GridCard title="连接与端口">
      <ConnectionSettingsForm />
    </GridCard>
  </div>
</template>

<script setup lang="ts">
import GridCard from '@/components/GridCard.vue';
import ConnectionSettingsForm from '@/views/settings/components/ConnectionSettingsForm.vue';
</script>

<style scoped>
/* 版式（.settings-panel / .config-grid / .config-item / .section-subtitle）
   统一在 src/styles/main.css，本文件不再重复定义。 */
</style>

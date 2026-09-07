<template>
  <div class="device-view">
    <n-tabs type="segment" animated>
      <n-tab-pane name="overview" tab="概览">
        <OverviewTab />
      </n-tab-pane>
      <n-tab-pane name="control" tab="控制">
        <ControlTab />
      </n-tab-pane>
      <n-tab-pane name="network" tab="网络设置">
        <NetworkTab />
      </n-tab-pane>
      <n-tab-pane name="advanced" tab="高级">
        <AdvancedTab />
      </n-tab-pane>
    </n-tabs>
  </div>
</template>

<script setup lang="ts">
import OverviewTab from './components/OverviewTab.vue';
import ControlTab from './components/ControlTab.vue';
import NetworkTab from './components/NetworkTab.vue';
import AdvancedTab from './components/AdvancedTab.vue';
</script>

<style scoped>
.device-view {
  padding: 0;
}
</style>

<!--
  网络诊断弹窗（2026-10-22）。

  ## 为什么从"诊断中心页"改成"四条独立路由 + 弹窗"
  原来网络页的「诊断中心」跳去 `#/diagnostics`（一个含 11 项检查的长页），
  但那 11 项里只有 4 项是"从这台设备发出去、要填目标"的网络工具，其余是设备环境快照。
  用户点「Ping / DNS / TCP / 路由追踪」时，期望的是**这一个工具**，不是一页别的东西。

  现在：
    · 四条工具各有自己的路由（`/network/diagnostics/ping` 等），可分享、可浏览器后退；
    · 路由本身渲染在网络页上，内容以弹窗呈现 —— 关掉就回到网络页原样；
    · 选择器（`/network/diagnostics`）只列四项，不夹带任何快照检查。

  ## 不自动执行
  弹窗打开时**不发任何请求**：目标是用户填的，包是用户要发的。只有点卡片上的
  「运行 / 重跑」才会调用 `runDiagnosticNow`。过程提示（"正在执行…"）走 toast；
  结果本身留在卡片里 —— 那是结论，不是提示，塞进 toast 会一闪而过、也没法复制原文。

  ## 执行逻辑不重写
  注册表里的 `net-*` 描述符已经定义了端点、超时、成败判定与渲染器；这里只按 id 取出来
  交给 `DiagnosticCard`。目标地址与端口沿用 `NetworkTargetBar` 绑定的那份模块级状态
  （四条共用一份，理由见那个组件文件头）。
-->
<template>
  <n-modal
    v-model:show="showModel"
    preset="card"
    :title="title"
    class="modal-fit"
    style="--modal-w: 720px; --modal-h: 760px"
  >
    <!-- 选择器：只在没有具体工具时出现 -->
    <template v-if="!tool">
      <p class="nd-intro">四条工具共用同一个目标地址。选中一项才会进入，<strong>不会自动执行</strong>。</p>
      <div class="nd-list">
        <EntryTile
          v-for="t in TOOLS"
          :key="t.key"
          :icon="t.icon"
          :title="t.label"
          :desc="t.desc"
          @click="emit('select', t.key)"
        />
      </div>
      <p class="nd-foot">每项检查都会真实向目标发包。执行提示用 toast，结论与原始输出留在卡片里。</p>
    </template>

    <!-- 具体工具：目标栏 + 注册表里的那张卡 -->
    <template v-else>
      <NetworkTargetBar />
      <DiagnosticCard v-if="item" :item="item" :state="diagnosticStateOf(item.id)" @run="onRun" />
      <n-empty v-else description="未找到这项检查" />
    </template>
  </n-modal>
</template>

<script setup lang="ts">
import { computed } from 'vue';
import type { Component } from 'vue';
import { useMessage } from 'naive-ui';
import { GitNetworkOutline, GlobeOutline, MapOutline, SearchOutline } from '@vicons/ionicons5';
import EntryTile from '@/components/EntryTile.vue';
import DiagnosticCard from '@/views/diagnostics/components/DiagnosticCard.vue';
import NetworkTargetBar from '@/views/diagnostics/components/NetworkTargetBar.vue';
import { DIAGNOSTICS } from '@/views/diagnostics/diagnosticRegistry';
import { diagnosticStateOf, runDiagnosticNow } from '@/views/diagnostics/useDiagnosticRun';
import { NETWORK_DIAG_TOOLS, type NetworkDiagTool } from '../../diagTools';

const props = defineProps<{
  show: boolean;
  /** 为空 = 显示四项选择器；非空 = 直接展示该工具 */
  tool?: NetworkDiagTool | null;
}>();
const emit = defineEmits<{
  (e: 'update:show', value: boolean): void;
  (e: 'select', tool: NetworkDiagTool): void;
}>();

const message = useMessage();

const showModel = computed({
  get: () => props.show,
  set: (v: boolean) => emit('update:show', v),
});

/** 图标只在这里配：数据文件（diagTools.ts）不引组件。 */
const ICONS: Record<NetworkDiagTool, Component> = {
  ping: GlobeOutline,
  dns: SearchOutline,
  tcp: GitNetworkOutline,
  traceroute: MapOutline,
};
const TOOLS = NETWORK_DIAG_TOOLS.map((t) => ({ ...t, icon: ICONS[t.key] }));

const current = computed(() => TOOLS.find((t) => t.key === props.tool) ?? null);

const title = computed(() => (current.value ? `网络诊断 · ${current.value.label}` : '网络诊断'));

const item = computed(() => {
  const spec = current.value;
  return spec ? (DIAGNOSTICS.find((d) => d.id === spec.id) ?? null) : null;
});

/**
 * 点「运行」：先 toast 一条"正在执行"，再把结果交给卡片。
 * 不使用 message 显示结果 —— 输出可能有几十行，必须能展开、复制。
 */
function onRun() {
  const spec = current.value;
  if (!spec || !item.value) return;
  message.info(`正在执行 ${spec.label}…`);
  void runDiagnosticNow(item.value);
}
</script>

<style scoped>
.nd-intro {
  margin: 0 0 var(--space-4);
  font-size: var(--font-base);
  line-height: 1.7;
  color: var(--text-secondary);
}
.nd-intro strong {
  color: var(--text-primary);
}
.nd-list {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(220px, 1fr));
  gap: var(--space-3);
}
.nd-foot {
  margin: var(--space-4) 0 0;
  font-size: var(--font-sm);
  line-height: 1.65;
  color: var(--text-muted);
}
@media (max-width: 768px) {
  .nd-list {
    grid-template-columns: 1fr;
  }
}
</style>

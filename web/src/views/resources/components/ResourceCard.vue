<!--
  资源卡片（2026-10-14 新建）：**纯描述符驱动**，一张卡对应一条 `ResourceDescriptor`。
  2026-10-23 起同一张卡在两种页面复用：服务更新页的 `WEB_RESOURCE`（Web 前端服务）与
  资源中心的 `COMPONENT_RESOURCES`（frpc / cloudflared 等插件）。

  ## 硬约束（会 review）
  本文件里**不允许**出现 `kind === ...` 或 `id === 'frpc'` 这类分支，`ResourceActionModal`
  同样不允许。所有差异走描述符的字段与**可选回调**：
  · 按钮渲不渲染 → 看 `descriptor.install` / `upload` / `uninstall` / `rollback` / `clear`
    有没有实现（"没实现的就不渲染那颗按钮"）；
  · 进度怎么读 → 看 `descriptor.stateMeta` 那张词表；
  · 多出来的信息行 → `descriptor.extraRows()`；
  · 上传后缀与上限 → `descriptor.accept` / `descriptor.uploadLimit()`。
  于是"加一个新资源"这件事在本文件里是**零改动**。

  ## 为什么进度是"状态标签 + 一条进度条 + 一句 message"，而不是一个百分比
  三类的百分比语义不同（见 `resourceRegistry.ts` 的 `ResourceStatus`）：
  · **上传期间**画的是"浏览器 → core"的本地进度（`state.uploadPercent`）；
  · **其余忙态**画的是 core 自报的 `percent`（Web ZIP 实质只有 0/100，组件是真百分比）；
  · 两者**不合并**：合并之后"60%"到底是在传还是在装就再也说不清，而这两种 60%
    对用户的含义完全相反（一个可以走开，一个不能关页面）。所以卡片两处都标清楚。
-->
<template>
  <GridCard :loading="initialLoading">
    <template #title>
      <span class="res-title">
        <n-icon :size="16"><component :is="descriptor.icon" /></n-icon>
        <span class="res-title-text">{{ descriptor.name }}</span>
        <!-- 安装态标签：只有**回答过这个问题**的资源才显示（web 前端没有"装没装"的概念，
             它的 installed 是 undefined）—— 判据是字段有没有值，不是资源的 id。 -->
        <n-tag
          v-if="status && status.installed !== undefined"
          :type="status.installed ? 'success' : 'warning'"
          size="tiny"
          :bordered="false"
        >
          {{ status.installed ? '已安装' : '未安装' }}
        </n-tag>
        <n-tag v-if="status?.updateAvailable" size="tiny" type="info" :bordered="false">有新版</n-tag>
      </span>
    </template>
    <template #extra>
      <n-button v-if="descriptor.refresh" size="tiny" quaternary :loading="state.loading" @click="onRefresh">
        检查更新
      </n-button>
    </template>

    <p class="res-desc">{{ descriptor.desc }}</p>
    <InfoRow label="当前版本" :value="status?.currentVersion || '--'" />
    <InfoRow label="最新版本" :value="status?.latestVersion || '--'" />
    <InfoRow v-for="row in extraRows" :key="row.label" :label="row.label" :value="row.value" />

    <!--
      读取失败：与「未安装 / 版本 --」分开显示。
      旧实现把 fetch 失败静默成 `status = null`，卡片于是渲染成「未安装」—— 那是把
      "没读到"说成"设备上确实没有"，用户会去点「下载安装」再失败一次。
    -->
    <div v-if="state.failed" class="res-status sub-panel res-failed" role="alert">
      <div class="res-status-head">
        <n-tag type="error" size="small" :bordered="false">读取失败</n-tag>
        <span class="res-status-msg">{{ failedHint }}</span>
        <n-button size="tiny" :loading="state.loading" @click="onRetryStatus">重试</n-button>
      </div>
    </div>

    <!--
      状态区：三类的 state 词表由描述符的 stateMeta 解释，本组件不认识任何一个具体状态名。
      `v-if` 的口径与设置页「前端资源」卡一致 —— 空闲且没在传的时候整块不出现，
      免得每张卡都挂一行"空闲"。
    -->
    <div v-if="showStatusBlock" class="res-status sub-panel">
      <div class="res-status-head">
        <n-tag :type="stateType" size="small" :bordered="false" :title="stateTitle">{{ stateLabel }}</n-tag>
        <span class="res-status-msg">{{ statusMessage }}</span>
      </div>
      <n-progress v-if="showProgress" type="line" :percentage="progressPercent" :height="6" :show-indicator="false" />
    </div>

    <!--
      「上次结果」那一行：**常驻**，不随弹窗开合消失。
      真源是 `updateResult.ts` 的快照（终态写入 + sessionStorage 落盘），所以它扛得住刷新；
      失败原因走与设置页两个弹窗同一套分类口径（`classifyPushError`），载体名词由描述符推。
      没有记录时整行不渲染，**不摆"暂无记录"占位**。
    -->
    <div v-if="lastResultText" class="res-result" :class="lastResultClass">{{ lastResultText }}</div>

    <div class="card-actions">
      <!-- 按"回调有没有实现"决定渲不渲染：这是本页唯一的按钮开关口径 -->
      <n-button
        v-if="descriptor.install"
        type="info"
        size="small"
        :disabled="busy"
        :loading="state.submitting"
        @click="onInstall"
      >
        {{ installLabel }}
      </n-button>
      <n-button v-if="descriptor.upload" type="primary" size="small" :disabled="busy" @click="openModal">
        本地上传
      </n-button>
      <!-- 弹窗关掉（「后台运行」）之后还要有入口回到进度上：上传活在模块作用域，与弹窗无关 -->
      <n-button v-if="state.uploading && !modalVisible" size="small" @click="openModal">查看进度</n-button>
      <n-button v-if="descriptor.rollback" size="small" :disabled="busy" @click="onRollback">回滚</n-button>
      <n-button v-if="descriptor.clear" type="warning" size="small" :disabled="busy" @click="onClear">
        恢复内置
      </n-button>
      <n-button
        v-if="descriptor.uninstall"
        type="error"
        size="small"
        quaternary
        :disabled="busy"
        @click="confirmUninstall"
      >
        卸载
      </n-button>
    </div>

    <ResourceActionModal v-model:show="modalVisible" :descriptor="descriptor" />
  </GridCard>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue';
import { useDialog } from 'naive-ui';
import GridCard from '@/components/GridCard.vue';
import InfoRow from '@/components/InfoRow.vue';
import { formatLastResult } from '@/views/settings/components/updateResult';
import { artifactNoun, type ResourceDescriptor } from '../resourceRegistry';
import { useResourceTask } from '../useResourceTask';
import ResourceActionModal from './ResourceActionModal.vue';

const props = defineProps<{ descriptor: ResourceDescriptor }>();

const dialog = useDialog();
const task = useResourceTask();
/**
 * 本卡片的运行时状态。**在 setup 里取一次**而不是在模板里现取：`stateOf` 会惰性创建状态，
 * 在渲染期间写响应式对象会多触发一轮渲染（资源页已经在 setup 里为每条描述符建好了）。
 * 卡片由 `v-for` 按 `descriptor.id` 作 key，实例不会被换给另一条描述符复用。
 */
const state = task.stateOf(props.descriptor.id);

const modalVisible = ref(false);

const status = computed(() => state.status);
/** 服务端状态键。认不出的键显示「未知」（原始键放 title），不谎报成"空闲"、也不直出英文 */
const serverState = computed(() => status.value?.state ?? 'idle');

const stateMeta = computed(() => props.descriptor.stateMeta?.[serverState.value]);
const stateLabel = computed(() => stateMeta.value?.label ?? '未知');
const stateTitle = computed(() => (stateMeta.value ? undefined : `未知状态：${serverState.value}`));
const stateType = computed(() => stateMeta.value?.type ?? 'default');

/** 首屏骨架：只在「一次都还没读到、也没失败」时占位，避免每轮 load 都把卡闪成骨架 */
const initialLoading = computed(() => state.loading && !state.status && !state.failed);
const failedHint = computed(
  () => `没读到设备上 ${props.descriptor.name} 的状态（设备可能不可达），版本与安装状态未知。`
);

/** 有动作在跑：所有按钮统一禁用（core 侧同一时刻只允许一个任务，本地也只有一个上传在跑） */
const busy = computed(() => state.submitting || state.uploading || state.watching);

const showStatusBlock = computed(() => busy.value || serverState.value !== 'idle' || state.failReason !== '');
/** 进度条该不该画：上传中，或服务端自报忙态。终态不画（那会是一条永远 100% 的条） */
const showProgress = computed(() => state.uploading || task.isBusyState(serverState.value));
/**
 * 进度条的取值口径。**刻意保留两条来源**（见文件头）：上传期间是浏览器这一侧的字节数，
 * 其余时候是 core 自报的百分比。上传期间即使 core 也在报数，也只画上传那一条 ——
 * 两条同时出现会被读成"没在传"（core 在上传阶段恒为 0）。
 */
const progressPercent = computed(() => (state.uploading ? state.uploadPercent : (status.value?.percent ?? 0)));
const statusMessage = computed(() => {
  if (state.uploading) {
    return `正在上传 ${state.uploadingName || '安装包'}… ${state.uploadPercent}%`;
  }
  return status.value?.message || state.failReason || '';
});

const extraRows = computed(() => props.descriptor.extraRows?.(status.value) ?? []);

const lastResultText = computed(() => formatLastResult(state.lastResult, artifactNoun(props.descriptor)));
const lastResultClass = computed(() => (state.lastResult?.ok ? 'is-ok' : 'is-error'));

/**
 * 「在线更新 / 下载安装」的文案。判据是 `status.installed` 这个**字段**，不是资源 id：
 * 没装过 → "下载安装"；其余（含从没回答过这个问题的 web 前端）→ "在线更新"。
 */
const installLabel = computed(() => (status.value?.installed === false ? '下载安装' : '在线更新'));

function onRefresh() {
  void task.refreshList(props.descriptor);
}

/** 读取失败横幅上的「重试」：只重读状态，不触发任何安装动作 */
function onRetryStatus() {
  void task.load(props.descriptor);
}

function onInstall() {
  void task.install(props.descriptor);
}

function onRollback() {
  void task.rollback(props.descriptor);
}

function onClear() {
  void task.clear(props.descriptor);
}

function openModal() {
  modalVisible.value = true;
}

/**
 * 卸载是破坏性动作（删掉设备上的二进制与元数据），所以走二次确认。
 * 文案由描述符的 `name` 拼出来，不认识具体是哪个组件。
 */
function confirmUninstall() {
  dialog.warning({
    title: `卸载 ${props.descriptor.name}`,
    content: `会删除设备上 ${props.descriptor.name} 的二进制与元数据。相关隧道配置不受影响，但在重新安装前无法启动。`,
    positiveText: '卸载',
    negativeText: '取消',
    onPositiveClick: () => {
      void task.uninstall(props.descriptor);
    },
  });
}
</script>

<style scoped>
.res-title {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
}
.res-title-text {
  font-size: 14px;
  font-weight: 600;
  color: var(--text-primary);
  white-space: nowrap;
}
.res-desc {
  margin: 0 0 10px;
  font-size: 12px;
  color: var(--text-muted);
  line-height: 1.5;
}
/* 描边/内距/圆角/底色走 main.css 的全局 .sub-panel */
.res-status {
  margin-top: 12px;
}
/* 失败态换个描边色（scoped 选择器特异性高于全局 .sub-panel，能盖住） */
.res-failed {
  border-color: var(--error);
}
.res-status-head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
  flex-wrap: wrap;
}
.res-status-msg {
  flex: 1;
  min-width: 0;
  font-size: 13px;
  color: var(--text-secondary);
  word-break: break-all;
}
/* 「上次结果」那一行：成功/失败只用语义色令牌区分，不写死颜色。
   word-break 是给失败原因用的 —— core 的原话可能是一整句中文加状态码，长到必须折行。 */
.res-result {
  margin-top: 8px;
  font-size: 12px;
  color: var(--text-secondary);
  line-height: 1.5;
  word-break: break-all;
}
.res-result.is-ok {
  color: var(--success);
}
.res-result.is-error {
  color: var(--error);
}
/* .card-actions 已统一到 src/styles/main.css（全局一份） */
</style>

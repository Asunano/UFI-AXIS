<!--
  资源上传弹窗（2026-10-14 新建）：**纯描述符驱动**，`ResourceCard` 为每条描述符各挂一份。

  ## 硬约束（会 review）
  与 `ResourceCard.vue` 一样：本文件里**不允许**出现 `kind === ...` 或 `id === 'frpc'`。
  上传后缀、上限、载体名词、说明文案全部从 `descriptor` 读。

  ## 为什么不直接用 `components/common/OperationProgressModal.vue`
  那个组件是 `useApiOperation` 的渲染端，状态形状 `OperationDialogState` 只有
  `idle/running/hidden/success/failed` **五档、没有百分比**。而本页最需要说清的恰恰是
  百分比：cloudflared 裸二进制约 36MB，设备 Wi-Fi 下要传十几秒，没有百分比的转圈
  分不清"在传"和"卡死了"。要让它装下 percent 就得改它的 props 与状态形状，
  而它同时被 `stores/networkOps`（A 级网络操作）用着 —— 那是**改坏共享件**，
  本次明令不许。所以这一层薄适配写在这里：**环复用 `UpdateStatusRing`**
  （与设置页两个更新弹窗同一个环，四种形态的取值口径不会分叉），
  进度值来自 `useResourceTask` 的 `uploadPercent`。

  ## 与 `views/settings/components/WebUpdateModal.vue` 的关系
  形态照抄（拖拽投放区、失败分「原因 + 建议」两行、上传中禁止遮罩/ESC 关闭、
  「后台运行」关掉弹窗不中断上传），但**不复制它的分支**：那一个写死了 ZIP、
  `webStore` 与"装完刷新页面"，本文件把这些都换成描述符字段与回调。

  ## 扩展名为什么只提示、不拦截
  组件这一类合法的输入包括**无扩展名的裸二进制**（`frpc`、`cloudflared-linux-amd64`），
  core 按文件头识别并做 SHA-256 校验 —— 拿扩展名当门禁会把主用法挡在门外。
  所以不匹配时只给一条提示，真正的判定在 core。**超限是硬拦**：几十 MB 白传一遍才看到 413
  是最没必要的等待（见 `composables/uploadLimit.ts` 的文件头）。
-->
<template>
  <n-modal
    :show="show"
    class="modal-fit"
    preset="card"
    :title="title"
    :mask-closable="dismissable"
    :close-on-esc="dismissable"
    :closable="dismissable"
    style="--modal-w: 460px"
    @update:show="onUpdateShow"
  >
    <div v-if="phase === 'select'" class="ram-pick">
      <div
        class="ram-drop"
        :class="{ 'is-active': dropActive }"
        @dragenter="onDragEnter"
        @dragover.prevent
        @dragleave="onDragLeave"
        @drop.prevent="onDrop"
      >
        <div class="ram-drop-title">把 {{ descriptor.name }} 安装包拖到这里</div>
        <div class="ram-drop-sub">或</div>
        <n-button size="small" @click="pick">选择文件</n-button>
        <div v-if="limitText" class="ram-drop-sub">设备端上限 {{ limitText }}：超出会在选择时就拦下，不会白传一遍</div>
      </div>

      <div v-if="pickedName" class="ram-file sub-panel">
        <div class="ram-file-main">
          <span class="ram-file-name">{{ pickedName }}</span>
          <span class="ram-file-size">{{ pickedSize }}</span>
        </div>
        <div class="ram-file-actions">
          <n-button size="tiny" @click="pick">重新选择</n-button>
          <n-button size="tiny" type="primary" @click="startPicked">开始上传</n-button>
        </div>
      </div>

      <div v-if="warning" class="ram-warn">{{ warning }}</div>
      <div class="ram-hint">{{ descriptor.desc }}</div>
    </div>

    <template v-else>
      <UpdateStatusRing
        v-if="phase === 'uploading'"
        status="progress"
        :percent="state.uploadPercent"
        :caption="uploadCaption"
        sub-caption="请保持与设备的连接"
      />
      <UpdateStatusRing
        v-else-if="phase === 'done'"
        status="success"
        :caption="doneCaption"
        sub-caption="安装完成。若这次替换了正在运行的前端资源，页面会自动刷新。"
      />
      <UpdateStatusRing
        v-else
        status="error"
        :caption="`原因：${failure.cause}`"
        :sub-caption="`建议：${failure.hint}`"
      />
    </template>

    <input ref="inputRef" class="ram-input" type="file" :accept="descriptor.accept" @change="onFileChange" />

    <template #footer>
      <div class="ram-footer">
        <n-button v-if="phase === 'select'" size="small" @click="close">取消</n-button>
        <n-button v-if="phase === 'select' && pickedName" size="small" type="primary" @click="startPicked">
          开始上传
        </n-button>
        <!-- 上传活在模块作用域（useResourceTask），关弹窗不等于放弃这次上传 -->
        <n-button v-if="phase === 'uploading'" size="small" @click="close">后台运行</n-button>
        <n-button v-if="phase === 'done'" size="small" type="primary" @click="acknowledge">完成</n-button>
        <template v-if="phase === 'failed'">
          <n-button size="small" @click="acknowledge">知道了</n-button>
          <n-button size="small" type="primary" :loading="retrying" @click="onRetry">重试</n-button>
        </template>
      </div>
    </template>
  </n-modal>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { formatBytes } from '@/composables/utils';
import { formatUploadLimit, uploadSizeError } from '@/composables/uploadLimit';
import UpdateStatusRing from '@/views/settings/components/UpdateStatusRing.vue';
import { classifyPushError, type PushError } from '@/views/settings/components/updateShared';
import { artifactNoun, type ResourceDescriptor } from '../resourceRegistry';
import { useResourceTask } from '../useResourceTask';

const props = defineProps<{ show: boolean; descriptor: ResourceDescriptor }>();
const emit = defineEmits<{ 'update:show': [boolean] }>();

const task = useResourceTask();
const state = task.stateOf(props.descriptor.id);

type Phase = 'select' | 'uploading' | 'done' | 'failed';

const phase = ref<Phase>('select');
const inputRef = ref<HTMLInputElement | null>(null);
/**
 * 选中的文件。
 *
 * 与 core APK 那条链路不同，这里**不**提到模块级：组件类安装包最大 36MB，失败后重选一次
 * 成本很低；而"关掉弹窗就丢"的那件事（上传进度）已经在 `useResourceTask` 里了。
 * 组件实例本身在弹窗隐藏时不会被销毁（naive 的 `display-directive` 只影响弹窗**内容**），
 * 所以「后台运行」后再打开，这个文件与进度都还在。
 */
const pickedFile = ref<File | null>(null);
const warning = ref('');
const retrying = ref(false);

/**
 * 阶段由**上传状态**驱动，不由"点过什么按钮"驱动：
 * 上传活在模块作用域，关掉弹窗（「后台运行」）期间它照样跑完，
 * 重开弹窗必须直接看到结论，而不是回到选文件态假装什么都没发生。
 */
watch(
  () => state.uploading,
  (now, before) => {
    if (now) {
      phase.value = 'uploading';
      return;
    }
    if (before) phase.value = state.failReason ? 'failed' : 'done';
  }
);
/** 重开弹窗时若还在传，直接落回上传态 */
watch(
  () => props.show,
  (v) => {
    if (v && state.uploading) phase.value = 'uploading';
  }
);

/** 上传中不许误触关闭（点空白处关掉会让人以为上传被取消了） */
const dismissable = computed(() => phase.value !== 'uploading');

const title = computed(() => {
  if (phase.value === 'uploading') return `正在上传 ${props.descriptor.name}`;
  if (phase.value === 'done') return '安装成功';
  if (phase.value === 'failed') return '安装失败';
  return `本地安装 ${props.descriptor.name}`;
});

/** 上传阶段的文案：到 100% 之后字节已经推完，剩下的是设备在装（那是另一条进度） */
const uploadCaption = computed(() => (state.uploadPercent >= 100 ? '已传完，设备正在安装…' : '正在上传安装包'));

const doneCaption = computed(() => state.lastResult?.message || `${props.descriptor.name} 已安装`);

/** 失败的两行：原因用 core 的原话，分类口径与设置页两个弹窗同一份（载体名词由描述符推） */
const failure = computed<PushError>(() => classifyPushError(state.failReason, artifactNoun(props.descriptor)));

const limitText = computed(() => {
  const limit = props.descriptor.uploadLimit?.(state.status) ?? 0;
  return limit > 0 ? formatUploadLimit(limit) : '';
});

const pickedName = computed(() => pickedFile.value?.name ?? '');
const pickedSize = computed(() => (pickedFile.value ? formatBytes(pickedFile.value.size) : ''));

// ── 拖拽投放区（与设置页两个更新弹窗 / FilesView 同一套口径）──
const dragDepth = ref(0);
const dropActive = computed(() => dragDepth.value > 0);

function isFileDrag(e: DragEvent): boolean {
  const types = e.dataTransfer?.types;
  return !!types && Array.from(types).includes('Files');
}

function onDragEnter(e: DragEvent) {
  if (!isFileDrag(e)) return;
  e.preventDefault();
  dragDepth.value++;
}

function onDragLeave(e: DragEvent) {
  if (!isFileDrag(e)) return;
  dragDepth.value = Math.max(0, dragDepth.value - 1);
}

function onDrop(e: DragEvent) {
  dragDepth.value = 0;
  const files = Array.from(e.dataTransfer?.files ?? []);
  const first = files[0];
  if (first) accept(first);
  if (files.length > 1 && !warning.value) {
    warning.value = '一次只能上传一个文件，已取第一个';
  }
}

function pick() {
  inputRef.value?.click();
}

function onFileChange(e: Event) {
  const input = e.target as HTMLInputElement;
  const f = input.files?.[0];
  // 清空 value：同一个文件再选一次也要能触发 change
  input.value = '';
  if (f) accept(f);
}

/** 扩展名只作提示（见文件头），返回一条可显示的中文，或空串 */
function extensionHint(name: string): string {
  const exts = props.descriptor.accept
    .split(',')
    .map((s) => s.trim().toLowerCase())
    .filter(Boolean);
  const lower = name.toLowerCase();
  const dot = lower.lastIndexOf('.');
  // 没有扩展名 = 组件那类的**裸二进制**，是合法输入，不给提示
  if (dot <= 0) return '';
  const ext = lower.slice(dot);
  if (exts.includes(ext)) return '';
  return `「${name}」不在 ${props.descriptor.accept} 之列；如果设备端能识别它的格式，仍可继续上传。`;
}

/** 选完即校验并开始上传（超限在选完那一刻就报，不白烧上行） */
function accept(f: File) {
  warning.value = extensionHint(f.name);
  const tooBig = uploadSizeError(f, props.descriptor.uploadLimit?.(state.status) ?? 0);
  if (tooBig) {
    warning.value = tooBig;
    return;
  }
  pickedFile.value = f;
  void startUpload(f);
}

function startPicked() {
  if (pickedFile.value) void startUpload(pickedFile.value);
}

async function startUpload(f: File) {
  await task.upload(props.descriptor, f);
}

/** 「重试」：文件还在内存里就直接重传；刷新过页面（文件没了）就回到选文件态。 */
async function onRetry() {
  if (retrying.value) return;
  retrying.value = true;
  try {
    if (pickedFile.value) {
      await startUpload(pickedFile.value);
      return;
    }
    task.clearFailReason(props.descriptor.id);
    phase.value = 'select';
    warning.value = '文件已不在内存中（页面刷新过），请重新选择';
  } finally {
    retrying.value = false;
  }
}

/** 「完成」/「知道了」：清掉失败原文并回到选文件态（下次打开不该还挂着上一次的原因）。 */
function acknowledge() {
  task.clearFailReason(props.descriptor.id);
  phase.value = 'select';
  close();
}

function close() {
  emit('update:show', false);
}

function onUpdateShow(v: boolean) {
  if (!v && !dismissable.value) return;
  emit('update:show', v);
}
</script>

<style scoped>
.ram-input {
  display: none;
}
.ram-pick {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.ram-drop {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  padding: 22px 16px;
  border: 1px dashed var(--border-subtle);
  border-radius: var(--radius-sm);
  background: var(--surface-elevated);
  text-align: center;
  transition: border-color 0.15s ease;
}
.ram-drop.is-active {
  border-color: var(--accent-color);
}
.ram-drop-title {
  font-size: 13px;
  color: var(--text-primary);
}
.ram-drop-sub {
  font-size: 12px;
  color: var(--text-muted);
  line-height: 1.5;
}
.ram-file {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
}
.ram-file-main {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}
.ram-file-name {
  font-size: 13px;
  color: var(--text-primary);
  word-break: break-all;
}
.ram-file-size {
  font-size: 12px;
  color: var(--text-muted);
}
.ram-file-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}
.ram-warn {
  font-size: 12px;
  color: var(--warning);
  line-height: 1.5;
}
.ram-hint {
  font-size: 12px;
  color: var(--text-muted);
  line-height: 1.5;
}
.ram-footer {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}
</style>

<!--
  前端资源「本地更新」弹窗（2026-10-14）。

  ## 为什么要有它
  改造前「前端资源」卡上有两条**各自孤立**的路：「检查更新」（让 core 自己去更新源拉包）
  与「上传更新」（手选 ZIP）。选哪条由用户自己判断，而且两条路的进行中状态只活在
  `UpdatePanel.vue` 的组件局部变量里 —— 关掉设置弹窗或刷新页面，正在跑的 web 更新就
  完全不可见、也没有任何入口恢复现场。现在：
  · 状态与轮询搬到 `stores/webUpdate.ts`（含 ZIP 上传进度），`resume()` 让刷新/重进能恢复；
  · 手选 ZIP 这条走**与 core APK 同一个弹窗形态**：选 ZIP → 进度 → 自动安装 → 完成/失败；
  · 在线通道失败到 `need_push`（更新源不可用）时，弹窗直接给出"改用手动上传"的入口 ——
     两条路从"用户自己判断"变成"一条失败就落到另一条"。

  ## 与 core APK 那个弹窗（LocalUpdateModal.vue）的差异 —— 决定了不能合并成一个组件
  · 手动上传 ZIP 是**同步**端点：core 收完 body 就地解压安装再回包，所以上传成功即安装完成，
    没有"安装中"这一段（core 的 WebUpdateManager 也没有 UPLOADING 这一档状态）；
  · web 资源是 ZIP 原子覆盖，**不会重启 core**，所以没有"core 正在重启 / 等 /health"那两段；
  · 成功后必须**刷新页面**（当前页面跑的还是旧 bundle），沿用改造前的 1200ms 时机。
  这三条差异散布在状态派生、标题、文案、按钮上，塞进一个组件只会得到到处 `if (isZip)` 的分支。

  ## 从 LocalUpdateModal 继承的两条硬约束
  ① 上传/进行中禁止遮罩/ESC 关闭（误触空白处会让人以为更新被取消了）；
  ② 失败分「原因 + 建议」两行，分类同样移植自 app 的 `classifyPushError`，只是载体名词换成 ZIP。

  ## 尺寸（2026-10-21 复核）
  `--modal-w: 460px` + `--modal-h: 560px` 都交给 `.modal-fit`。选文件态最长（拖拽区 +
  在线通道结论 + 已选包 + 两行提示），560 只是设计上限；矮屏由 `.modal-fit` 收到
  `100dvh - 24px` 并让内容区自己滚，头尾始终可见。
-->
<template>
  <n-modal
    :show="show"
    preset="card"
    :title="title"
    :mask-closable="dismissable"
    :close-on-esc="dismissable"
    :closable="dismissable"
    class="modal-fit"
    style="--modal-w: 460px; --modal-h: 560px"
    @update:show="onUpdateShow"
  >
    <div v-if="phase === 'select'" class="wu-pick">
      <div
        class="wu-drop"
        :class="{ 'is-active': dropActive }"
        @dragenter="onDragEnter"
        @dragover.prevent
        @dragleave="onDragLeave"
        @drop.prevent="onDrop"
      >
        <div class="wu-drop-title">把前端资源 ZIP 拖到这里</div>
        <div class="wu-drop-sub">或</div>
        <n-button size="small" @click="pick">选择文件</n-button>
        <div v-if="limitText" class="wu-drop-sub">设备端上限 {{ limitText }}：超出会在选择时就拦下，不会白传一遍</div>
      </div>

      <!-- 在线通道刚给出的结论（「更新源不可用，可手动上传 ZIP」/「已是最新版本」）：
           把 core 的原话摆在选文件区上面，用户才知道为什么现在要手动传。 -->
      <div v-if="conclusionHint" class="wu-hint" :class="{ 'wu-hint-warn': webStore.state === 'need_push' }">
        {{ conclusionHint }}
      </div>

      <div v-if="pickedName" class="wu-file sub-panel">
        <div class="wu-file-main">
          <span class="wu-file-name">{{ pickedName }}</span>
          <span class="wu-file-size">{{ pickedSize }}</span>
        </div>
        <div class="wu-file-actions">
          <n-button size="tiny" @click="pick">重新选择</n-button>
          <n-button size="tiny" type="primary" @click="startPicked">开始上传</n-button>
        </div>
      </div>

      <div v-if="localWarning" class="wu-warn">{{ localWarning }}</div>
      <div class="wu-hint">ZIP 是整份前端资源（dist 打包产物）。设备端会原子替换，装好后页面自动刷新加载新版本。</div>
    </div>

    <template v-else>
      <UpdateStatusRing
        v-if="phase === 'uploading'"
        status="progress"
        :percent="webStore.uploadPercent"
        :caption="uploadCaption"
        sub-caption="请保持与设备的连接"
      />
      <UpdateStatusRing
        v-else-if="phase === 'busy'"
        status="indeterminate"
        :caption="busyCaption"
        :sub-caption="busySubCaption"
      />
      <UpdateStatusRing
        v-else-if="phase === 'done'"
        status="success"
        :caption="doneCaption"
        sub-caption="页面即将自动刷新以加载新版本（当前页面跑的还是旧前端）"
      />
      <UpdateStatusRing
        v-else
        status="error"
        :caption="`原因：${failure.cause}`"
        :sub-caption="`建议：${failure.hint}`"
      />

      <div v-if="phase === 'uploading' && totalBytes > 0" class="wu-bytes">
        {{ formatBytes(uploadedBytes) }} / {{ formatBytes(totalBytes) }}
      </div>
    </template>

    <input ref="inputRef" class="wu-input" type="file" accept=".zip" @change="onFileChange" />

    <template #footer>
      <div class="wu-footer">
        <n-button v-if="phase === 'select'" size="small" @click="close">取消</n-button>
        <n-button v-if="phase === 'select' && pickedName" size="small" type="primary" @click="startPicked">
          开始上传
        </n-button>
        <n-button v-if="phase === 'uploading' || phase === 'busy'" size="small" @click="close">后台运行</n-button>
        <!-- 忙态但**没人在盯**（轮询到 10 分钟上限自己停了）：给一条出路，别让用户对着一个
             永远不动的进度环。core 对重复触发是静默忽略并回当前状态，所以这颗就是"重新检查"。 -->
        <n-button
          v-if="phase === 'busy' && !webStore.checking && !webStore.watching"
          size="small"
          type="primary"
          @click="webStore.check()"
          >重新检查</n-button
        >
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
import { computed, ref } from 'vue';
import { formatBytes } from '@/composables/utils';
import { formatUploadLimit, uploadSizeError } from '@/composables/uploadLimit';
import { useWebUpdateStore } from '@/stores/webUpdate';
import UpdateStatusRing from './UpdateStatusRing.vue';
import { classifyPushError, type PushError } from './updateShared';

defineProps<{ show: boolean }>();
const emit = defineEmits<{ 'update:show': [boolean] }>();

const webStore = useWebUpdateStore();

type Phase = 'select' | 'uploading' | 'busy' | 'done' | 'failed';

const inputRef = ref<HTMLInputElement | null>(null);
/**
 * 选中的 ZIP。
 *
 * 与 core APK 那条链路不同，这里**不**把它提到模块级：ZIP 只有 1-5MB，失败后重新选一次
 * 成本很低，而 store 里已经存着上传进度（那才是"关掉弹窗就丢"的东西）。
 * 组件实例本身在弹窗隐藏时不会被销毁（naive 的 `display-directive` 只影响弹窗**内容**），
 * 所以「后台运行」后再打开，这个文件与进度都还在。
 */
const pickedFile = ref<File | null>(null);
const localWarning = ref('');
const retrying = ref(false);

/**
 * 当前该显示哪一态。
 *
 * `webStore.session`（本次更新会话）是必须的：`/api/web/status` 是**历史结论的载体**，
 * 上一次检查成功后它一直停在 `done`；只看 core 状态的话，用户下次点「本地更新」看到的是
 * 上一次的结论、连选文件的机会都没有。
 */
const phase = computed<Phase>(() => {
  if (webStore.uploading) return 'uploading';
  if (webStore.session) {
    const s = webStore.state;
    if (s === 'done') return 'done';
    if (s === 'failed') return 'failed';
    // need_push = 更新源不可用。那不是"失败"，而是"该手动传了" —— 直接落回选文件态，
    // 并把 core 的原话摆在上面（见 needPushHint）。
    if (s === 'need_push') return 'select';
    // idle = core 比完版本认为没什么可做的（已是最新）。这时摆一个转圈的"正在更新"是假的，
    // 落回选文件态（想强制覆盖版本的话，手选 ZIP 正是唯一办法）。
    if (s === 'idle') return 'select';
    return 'busy';
  }
  // 本次没发起过更新，但 core 正在跑（多半是卡片上「检查更新」触发的）：只读展示，
  // 等它结束再让用户选 ZIP
  if (webStore.busy) return 'busy';
  return 'select';
});

const dismissable = computed(() => phase.value !== 'uploading' && phase.value !== 'busy');

const title = computed(() => {
  switch (phase.value) {
    case 'uploading':
      return '正在上传前端资源';
    case 'busy':
      return busyTitle.value;
    case 'done':
      return '更新成功';
    case 'failed':
      return '更新失败';
    default:
      return '本地更新';
  }
});

const busyTitle = computed(() => {
  switch (webStore.state) {
    case 'checking':
      return '正在检查更新源';
    case 'downloading':
      return '正在下载前端资源';
    case 'verifying':
      return '正在校验安装包';
    case 'installing':
      return '正在安装前端资源';
    default:
      return '正在更新';
  }
});

/** 固定文案，不带百分比（core 的 message 里本来就带「… 42%」，重复了） */
const uploadCaption = computed(() =>
  webStore.uploadPercent >= 100 ? '已传完，设备正在解压并覆盖…' : '正在上传前端资源包'
);

const busyCaption = computed(() => webStore.message || '正在更新…');

const busySubCaption = computed(() => {
  if (!webStore.session) {
    return '设备端正在跑另一次前端资源更新（卡片上的「检查更新」或另一端发起），等它结束后再上传本地包。';
  }
  return '进度由设备端上报，请保持与设备的连接';
});

const doneCaption = computed(() => webStore.message || '前端资源已更新');

/**
 * 在线通道留下的结论（只在选文件态显示）。
 *
 * 两种情形都会让弹窗落回选文件态，理由不同、都必须说出来：
 * · `need_push` —— 更新源不可用，手动上传正是兜底通道；
 * · `idle`      —— core 比完版本认为已是最新，想强制覆盖版本只能手选 ZIP。
 */
const conclusionHint = computed(() => {
  if (!webStore.session) return '';
  return webStore.state === 'need_push' || webStore.state === 'idle' ? webStore.message : '';
});

/** 失败的两行：core 的 message 就是原因原文（web 组只回 `{ error }`），分类见 updateShared.ts */
const failure = computed<PushError>(() => classifyPushError(webStore.message, 'ZIP'));

const limitText = computed(() => (webStore.maxUploadBytes > 0 ? formatUploadLimit(webStore.maxUploadBytes) : ''));

const pickedName = computed(() => pickedFile.value?.name ?? '');
const pickedSize = computed(() => (pickedFile.value ? formatBytes(pickedFile.value.size) : ''));
const uploadedBytes = computed(() => webStore.uploadBytes);
const totalBytes = computed(() => webStore.uploadTotalBytes);

// ── 拖拽投放区（与 LocalUpdateModal / FilesView 同一套口径）──
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
  if (files.length > 1 && !localWarning.value) {
    localWarning.value = '一次只能上传一个 ZIP，已取第一个文件';
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

/** 选完即校验并开始上传（超限在选完那一刻就报，不白烧上行）。 */
function accept(f: File) {
  localWarning.value = '';
  if (!f.name.toLowerCase().endsWith('.zip')) {
    localWarning.value = `「${f.name}」不是 .zip 前端资源包`;
    return;
  }
  const tooBig = uploadSizeError(f, webStore.maxUploadBytes);
  if (tooBig) {
    localWarning.value = tooBig;
    return;
  }
  pickedFile.value = f;
  void startUpload(f);
}

function startPicked() {
  if (pickedFile.value) void startUpload(pickedFile.value);
}

async function startUpload(f: File) {
  await webStore.upload(f);
}

/**
 * 「重试」：上传阶段失败才需要重传（ZIP 的安装就在上传请求里完成，没有第二段）。
 * 文件还在内存里就直接重传；刷新过页面（文件没了）就回到选文件态。
 */
async function onRetry() {
  if (retrying.value) return;
  retrying.value = true;
  try {
    if (pickedFile.value) {
      await webStore.upload(pickedFile.value);
      return;
    }
    webStore.endSession();
    localWarning.value = '文件已不在内存中（页面刷新过），请重新选择 ZIP';
  } finally {
    retrying.value = false;
  }
}

/** 「完成」/「知道了」：结束本次会话（回到选文件态）并关弹窗。 */
function acknowledge() {
  webStore.endSession();
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
.wu-input {
  display: none;
}
.wu-pick {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.wu-drop {
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
.wu-drop.is-active {
  border-color: var(--accent-color);
}
.wu-drop-title {
  font-size: 13px;
  color: var(--text-primary);
}
.wu-drop-sub {
  font-size: 12px;
  color: var(--text-muted);
  line-height: 1.5;
}
.wu-file {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
}
.wu-file-main {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}
.wu-file-name {
  font-size: 13px;
  color: var(--text-primary);
  word-break: break-all;
}
.wu-file-size {
  font-size: 12px;
  color: var(--text-muted);
}
.wu-file-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}
.wu-warn {
  font-size: 12px;
  color: var(--warning);
  line-height: 1.5;
}
.wu-hint {
  font-size: 12px;
  color: var(--text-muted);
  line-height: 1.5;
}
.wu-hint-warn {
  color: var(--warning);
}
.wu-bytes {
  margin-top: 10px;
  text-align: center;
  font-size: 12px;
  color: var(--text-secondary);
}
.wu-footer {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}
</style>

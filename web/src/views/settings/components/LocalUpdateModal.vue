<!--
  本地更新（core APK 推送）弹窗（2026-10-14）。

  ## 为什么要有它
  改造前这一条链路被拆成四颗互相孤立的按钮：「上传 APK」→（toast 提示）→「安装已上传的包」→
  「重新检查状态」/「重置状态」。用户点完一颗不知道下一颗在哪，上传进度还只是组件局部 ref
  （关掉弹窗/刷新即丢）。这里收成一个弹窗走完：
  **选文件 → 上传（真实进度）→ 自动安装 → 轮询 → 完成 / 失败**。

  形态照 app 端 `UpdateDialogs.kt` 的 `ApkPushDialog`（449-545 行），包括三条硬约束：

  ① **上传/安装中禁止点遮罩关闭**（app 是 `dismissOnClickOutside = false`，注释写明理由：
     推送过程中误触空白处会把弹窗关掉，用户以为推送被取消了，其实还在传）。
     这里只在上传/进行中两态禁（`:mask-closable` / `:close-on-esc` / `:closable` 三个口子一起关），
     成功/失败态随便关 —— 那时关掉不会产生误解。
  ② **进度文案不与百分比重复**：core 生成的 message 里本来就带「… 42%」
     （见 app 侧 UpdateDialogs.kt:496-498 的说明），所以上传态的 caption 用固定文案，
     百分比只由环承担。
  ③ **失败分「原因 + 建议」两行**，分类逐条移植自 app 的 `classifyPushError`
     （见 `updateShared.ts`，那里注明了来源与移植口径）。

  ## 与 app 端的差异（web 特有的两段现实）
  · app 只有 4 态；web 的 core 安装会**重启自身**，`stores/update.ts` 为此多出
    `restarting` / `timeout` 两个本地阶段。这里把 restarting 归进"进行中"（同一个不确定态环），
    timeout 归进"失败"（它确实是"没等到结论"，只是原因不同）。
  · 「重试」的判据不是 app 那套"重新发起一次推送"，而是先看**包在不在设备上** —— 见
    `localUpdatePush.ts` 的 `retry()` 注释（那是本文件唯一需要解释的决策）。

  ## 尺寸（2026-10-21 复核）
  `--modal-w: 460px` + `--modal-h: 560px` 都交给 `.modal-fit`。选文件态在 360px 宽 / 640px
  高的视口上是最长的一屏（拖拽区 + 已选包 + 两行提示），560 是"设计上限"——
  矮屏由 `.modal-fit` 收到 `100dvh - 24px`，内容区自己滚、底部按钮固定。
  全程没有 `preset="dialog"` 的 446px 默认宽度问题。
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
    <!-- ── 选文件态 ──
         拖拽区与「选择文件」是同一个入口的两种用法。选完**立即开始上传**（用户描述是
         "选择好后在弹窗显示进度"，app 侧也是选完即推），所以下面那颗「开始上传」在正常路径上
         基本看不到 —— 它的真实用途是**重试**：上传阶段失败后回到这一态时，文件还留在内存里
         （见 localUpdatePush.ts 的 `file`），用户点一下就能重传，不必再去文件管理器翻一遍。 -->
    <div v-if="phase === 'select'" class="lu-pick">
      <div
        class="lu-drop"
        :class="{ 'is-active': dropActive }"
        @dragenter="onDragEnter"
        @dragover.prevent
        @dragleave="onDragLeave"
        @drop.prevent="onDrop"
      >
        <div class="lu-drop-title">把 UFI-AXIS Core 的 APK 拖到这里</div>
        <div class="lu-drop-sub">或</div>
        <n-button size="small" @click="pick">选择文件</n-button>
        <div v-if="limitText" class="lu-drop-sub">
          设备端上限 {{ limitText }}：超出会在选择时就拦下，不会白传一遍（413 要等 body 推完才被浏览器读到）
        </div>
      </div>

      <div v-if="pickedName" class="lu-file sub-panel">
        <div class="lu-file-main">
          <span class="lu-file-name">{{ pickedName }}</span>
          <span class="lu-file-size">{{ pickedSize }}</span>
        </div>
        <div class="lu-file-actions">
          <n-button size="tiny" @click="pick">重新选择</n-button>
          <n-button size="tiny" type="primary" @click="startPicked">开始上传</n-button>
        </div>
      </div>

      <div v-if="localWarning" class="lu-warn">{{ localWarning }}</div>
      <div class="lu-hint">推送的是设备端 core 自身的安装包。装错包不会重启 core，请确认选的是 UFI-AXIS Core。</div>
    </div>

    <!-- ── 进行中 / 完成 / 失败：同一个环 + 环下两行文案 ── -->
    <template v-else>
      <UpdateStatusRing
        v-if="phase === 'uploading'"
        status="progress"
        :percent="apkUploadPercent"
        :caption="uploadCaption"
        sub-caption="请保持与设备的连接"
      />
      <UpdateStatusRing
        v-else-if="phase === 'busy'"
        status="indeterminate"
        :caption="busyCaption"
        :sub-caption="busySubCaption"
      />
      <UpdateStatusRing v-else-if="phase === 'done'" status="success" :caption="doneCaption" />
      <UpdateStatusRing
        v-else
        status="error"
        :caption="`原因：${failure.cause}`"
        :sub-caption="`建议：${failure.hint}`"
      />

      <!-- 已传 / 总量：百分比由环承担，这里补"传了多少字节"，因为 APK 有 15MB 上下，
           光看百分比判断不出"还剩多久" -->
      <div v-if="phase === 'uploading' && totalBytes > 0" class="lu-bytes">
        {{ formatBytes(uploadedBytes) }} / {{ formatBytes(totalBytes) }}
      </div>
    </template>

    <input ref="inputRef" class="lu-input" type="file" accept=".apk" @change="onFileChange" />

    <template #footer>
      <div class="lu-footer">
        <n-button v-if="phase === 'select'" size="small" @click="close">取消</n-button>
        <n-button v-if="phase === 'select' && pickedName" size="small" type="primary" @click="startPicked">
          开始上传
        </n-button>
        <!-- 「后台运行」= 关弹窗但**不停**上传/轮询：上传活在 localUpdatePush 模块里，
             轮询活在 stores/update.ts 里，两者都与弹窗生命周期无关。 -->
        <n-button v-if="phase === 'uploading' || phase === 'busy'" size="small" @click="close">后台运行</n-button>
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
import { useUpdateStore } from '@/stores/update';
import UpdateStatusRing from './UpdateStatusRing.vue';
import { useLocalApkPush } from './localUpdatePush';
import { classifyPushError, type PushError } from './updateShared';

defineProps<{ show: boolean }>();
const emit = defineEmits<{ 'update:show': [boolean] }>();

const updateStore = useUpdateStore();
// 解构到顶层：模板只对顶层 ref 自动解包，`push.uploadPercent` 那样写会渲染出 Ref 对象
const {
  file: pickedFile,
  uploading: apkUploading,
  uploadPercent: apkUploadPercent,
  uploadedBytes,
  totalBytes,
  error: pushError,
  active: pushActive,
  installing: pushInstalling,
  startUpload,
  retry: retryPush,
  reset: resetPush,
} = useLocalApkPush();

/** 弹窗自己的四态（core 侧状态 + 本地上传状态的合成，见 [phase]）。 */
type Phase = 'select' | 'uploading' | 'busy' | 'done' | 'failed';

const inputRef = ref<HTMLInputElement | null>(null);
const localWarning = ref('');
const retrying = ref(false);

/**
 * 当前该显示哪一态。
 *
 * 为什么要看 [pushActive] 而不是只看 core 状态：`/api/update/status` 是**历史结论的载体** ——
 * 上一次更新成功后它一直停在 `done`。只按 core 状态判断的话，用户下次点「本地更新」看到的
 * 是上一次的结论、连选文件的机会都没有。所以"本次推送"要有自己的生命周期
 * （见 `localUpdatePush.ts` 里 `active` 的说明）。
 */
const phase = computed<Phase>(() => {
  if (apkUploading.value) return 'uploading';
  if (pushError.value) return 'failed';
  const ds = updateStore.displayState;
  if (pushActive.value) {
    if (ds === 'done') return 'done';
    if (ds === 'failed' || ds === 'timeout') return 'failed';
    // 「安装请求在途」与「core 在忙」都算进行中。
    // 前者覆盖"上传成功、core 刚被 finishUpload 置回 idle"那一小段空窗（见 pushInstalling 的说明）；
    // 后者覆盖 installing / restarting / downloading / verifying。
    if (pushInstalling.value || updateStore.busy) return 'busy';
    // 两个都不成立 = 真的没有任何事在跑（例如用户刚在卡片上点过「重置状态」）：
    // 这时必须回到选文件态，否则会留下一个永远在转、又没有任何出路的环。
    return 'select';
  }
  // 本次没推送过，但 core 正在被**别人**更新（在线更新 / 另一端发起）：core 同一时刻只接受
  // 一次更新，此时上传必然 409，所以先进"进行中"只读态，等它跑完再让用户选包
  if (updateStore.busy) return 'busy';
  return 'select';
});

/** 进行中禁止遮罩 / ESC / 右上 × 关闭（app 的 `dismissOnClickOutside = false` 同一条理由）。 */
const dismissable = computed(() => phase.value !== 'uploading' && phase.value !== 'busy');

const title = computed(() => {
  switch (phase.value) {
    case 'uploading':
      return '正在上传 APK';
    case 'busy':
      return busyTitle.value;
    case 'done':
      return '安装成功';
    case 'failed':
      return '推送失败';
    default:
      return '本地更新';
  }
});

/** 进行中态的标题按 core 阶段走：下载 / 校验 / 安装 / 重启是四件不同的事，不能都叫「正在安装」。 */
const busyTitle = computed(() => {
  switch (updateStore.displayState) {
    case 'installing':
      return '正在安装';
    case 'restarting':
      return 'core 正在重启';
    case 'downloading':
      return '正在下载更新';
    case 'verifying':
      return '正在校验安装包';
    case 'uploading':
      return '正在上传 APK';
    default:
      return '正在更新';
  }
});

/** 上传态固定文案（**不带百分比**，百分比只由环承担 —— 见文件头约束②）。 */
const uploadCaption = computed(() =>
  apkUploadPercent.value >= 100 ? '已传完，设备正在校验并落盘…' : '正在上传安装包'
);

/** 安装由设备端 pm/PI 完成，拿不到进度 → 环走不确定态，文案用后端的 message（空则固定文案）。 */
const busyCaption = computed(() => updateStore.status.message || '正在安装…');

const busySubCaption = computed(() => {
  if (!pushActive.value) {
    return '设备端正在进行另一次更新（在线更新或另一端发起）。core 同一时刻只接受一次更新，等它结束后再推送本地包。';
  }
  switch (updateStore.displayState) {
    case 'restarting':
      return '接口会中断十几秒，页面正在轮询 /health 探测就绪';
    case 'downloading':
    case 'verifying':
      return '进度由设备端上报';
    default:
      return '请保持与设备的连接';
  }
});

const doneCaption = computed(() => updateStore.status.message || 'APK 已推送安装，设备将自动重启生效');

/** 失败的两行：本地上传/开装阶段的原文优先，其次是 core 的 message；超时单独一套说法。 */
const failure = computed<PushError>(() => {
  if (pushError.value) return classifyPushError(pushError.value, 'APK');
  if (updateStore.displayState === 'timeout') {
    const why = updateStore.lastProbeError ? `（最后一次探测：${updateStore.lastProbeError}）` : '';
    return {
      cause: '等待设备完成更新超时',
      hint: `已停止轮询${why}。先关掉本弹窗、在卡片上点「重新检查状态」确认设备当前状态，确实没装上再重试。`,
    };
  }
  return classifyPushError(updateStore.status.message, 'APK');
});

const limitText = computed(() => {
  const limit = updateStore.status.uploadLimitBytes;
  return limit > 0 ? formatUploadLimit(limit) : '';
});

const pickedName = computed(() => pickedFile.value?.name ?? '');
const pickedSize = computed(() => (pickedFile.value ? formatBytes(pickedFile.value.size) : ''));

// ── 拖拽投放区 ──
// 与 FilesView 同一套口径：dragover 必须 preventDefault，否则浏览器不认这是投放区；
// 用**计数器**而不是布尔量跟踪拖入/拖出（拖过子元素时 dragleave/dragenter 成对冒泡，
// 布尔量会让高亮疯狂闪烁）；只在真的拖着文件时才高亮。
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
  // 拖进来的多于一个时如实说一句，但**不要覆盖** accept 刚给出的校验原因（那更重要）
  if (files.length > 1 && !localWarning.value) {
    localWarning.value = '一次只能推送一个 APK，已取第一个文件';
  }
}

function pick() {
  inputRef.value?.click();
}

function onFileChange(e: Event) {
  const input = e.target as HTMLInputElement;
  const f = input.files?.[0];
  // 清空 value：同一个文件再选一次也要能触发 change（否则重传同一个包时毫无反应）
  input.value = '';
  if (f) accept(f);
}

/**
 * 选完即校验并开始上传。
 *
 * 两处校验放在**这里**（选完那一刻）而不是等上传失败：
 * · 扩展名：core 只接受 APK，先拦掉能省一次往返；
 * · 体积上限：上限是 core 按清单 apkSize 动态算的（`status.uploadLimitBytes`），
 *   不预检就会把几十 MB 白传一遍才看到 413（详见 composables/uploadLimit.ts）。
 */
function accept(f: File) {
  localWarning.value = '';
  if (!f.name.toLowerCase().endsWith('.apk')) {
    localWarning.value = `「${f.name}」不是 .apk 安装包，请选择 UFI-AXIS Core 的安装包`;
    return;
  }
  const tooBig = uploadSizeError(f, updateStore.status.uploadLimitBytes);
  if (tooBig) {
    localWarning.value = tooBig;
    return;
  }
  void startUpload(f);
}

function startPicked() {
  if (pickedFile.value) void startUpload(pickedFile.value);
}

/**
 * 「重试」。
 *
 * 超时**不**走"再装一次"：那时 core 很可能仍在安装，再发一次 install-local 会被它按
 * "更新进行中"拒掉；而超时的真实含义是"没人知道装到哪了"，所以先 `recheck()` 把状态查回来
 * （读到忙态会重新起表，读到 done 会走 /health + 版本复核）。
 * 其余失败交给 `localUpdatePush.retry()` —— 它按"包在不在设备上"决定重传还是只重装。
 */
async function onRetry() {
  if (retrying.value) return;
  retrying.value = true;
  try {
    if (updateStore.displayState === 'timeout') {
      await updateStore.recheck();
      return;
    }
    await retryPush();
  } finally {
    retrying.value = false;
  }
}

/** 「完成」/「知道了」：结束本次推送会话（回到选文件态）并关弹窗。文件仍留着供重传。 */
function acknowledge() {
  resetPush();
  close();
}

function close() {
  emit('update:show', false);
}

/** 遮罩 / ESC / × 都走这里；进行中态直接吞掉（约束①）。 */
function onUpdateShow(v: boolean) {
  if (!v && !dismissable.value) return;
  emit('update:show', v);
}
</script>

<style scoped>
.lu-input {
  display: none;
}
.lu-pick {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.lu-drop {
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
/* 拖拽悬停：只换描边色（令牌），不动底色 —— 底色在明暗两态下差异很大，
   改底色容易和 .sub-panel 那套"抬升"语义打架 */
.lu-drop.is-active {
  border-color: var(--accent-color);
}
.lu-drop-title {
  font-size: 13px;
  color: var(--text-primary);
}
.lu-drop-sub {
  font-size: 12px;
  color: var(--text-muted);
  line-height: 1.5;
}
.lu-file {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
}
.lu-file-main {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}
.lu-file-name {
  font-size: 13px;
  color: var(--text-primary);
  word-break: break-all;
}
.lu-file-size {
  font-size: 12px;
  color: var(--text-muted);
}
.lu-file-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}
.lu-warn {
  font-size: 12px;
  color: var(--warning);
  line-height: 1.5;
}
.lu-hint {
  font-size: 12px;
  color: var(--text-muted);
  line-height: 1.5;
}
.lu-bytes {
  margin-top: 10px;
  text-align: center;
  font-size: 12px;
  color: var(--text-secondary);
}
.lu-footer {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}
</style>

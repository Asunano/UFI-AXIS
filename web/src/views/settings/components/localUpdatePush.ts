import { ref, type Ref } from 'vue';
import { getApiClient } from '@/composables/useApi';
import { Endpoints } from '@/api/contract';
import { useUpdateStore } from '@/stores/update';

/**
 * 「本地更新」core APK 的**推送链路**（浏览器 → core 上传 APK，然后自动安装）。
 *
 * ## 为什么是一个模块级单例，而不是弹窗里的 `ref`
 * 改造前这一套（`apkUploading` / `apkUploadPercent` / 文件 input）是 `UpdatePanel.vue` 的
 * 组件局部状态，于是：
 * · 点「后台运行」关掉弹窗 → 上传进度与"正在传哪个文件"全丢，用户以为推送被取消了；
 * · 离开设置页 → 面板卸载，`useCancellableApi` 直接把在途上传 abort 掉。
 * 所以状态必须活在**组件树之外**，并且上传要用**不带取消**的 `getApiClient()`
 * —— 这正是 app 端「后台运行 = 关弹窗、任务继续」的语义（`UpdateDialogs.kt:440-443`）。
 *
 * ## 为什么不是 pinia store（放在 `src/stores/`）
 * 本次改造的写权限边界里，`src/stores/update.ts`（core 状态机）是**别人已写好、不许改**的，
 * 而这一段链路与它的边界很清晰：**上传**（core 的 `/api/update/upload` + 本地进度）归这里，
 * **安装/重启/校验的轮询**归 `stores/update.ts`。再开一个 `stores/localUpdate.ts` 会与
 * `stores/update.ts` 争"谁是 core APK 更新的真源"。所以这里只做一个模块级单例：
 * 生命周期跟着 SPA 走，不跟着任何组件走。
 *
 * ## 否决过的替代方案
 * · 状态留在 `LocalUpdateModal.vue` 里：naive 的 `n-modal` 默认 `display-directive="if"`，
 *   隐藏即卸载内容 —— 「后台运行」一按进度就没了，正是要修的 P0；
 * · 状态提到 `UpdatePanel.vue`：设置页是弹窗，关掉即卸载面板，同样丢；
 * · 上传也用 `useCancellableApi()`：卸载即 abort，等于"关掉设置页就取消推送"。
 *
 * ## 取消语义
 * 这里**不提供取消**。app 端在 2026-08-27 特意删掉了那颗假的「取消」（它的 onClick 就是
 * onDismiss，并不会真的中断上传/安装，见 `UpdateDialogs.kt:441-442`），只留「后台运行」。
 * 关弹窗不中断上传；真要中断只能等它自己结束或断开设备 —— 所以界面上不要摆一颗"取消"。
 */

/** 失败发生在哪一段 —— 决定「重试」是重传还是只重装（见 [retry]）。 */
export type ApkPushErrorStage = 'upload' | 'install' | '';

/** 接不上 store 轮询时的静默重试次数 / 间隔（core 重启期间 /api/update/status 会连不上）。 */
const ATTACH_TRIES = 5;
const ATTACH_INTERVAL_MS = 2000;

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => window.setTimeout(resolve, ms));
}

/**
 * 记一次失败终态：写本模块的 [error]（弹窗读它做"原因 + 建议"两行）**并**写进
 * `stores/update.ts` 的 `lastResult`（卡片上那行常驻结论）。
 *
 * 为什么必须两处都写：上传 / 开装这两段失败**不经过** `stores/update.ts` 的状态机
 * （那是"core 在装"那一段的机器），store 自己不会知道这次失败了 ——
 * 不补记的话，卡片上的「上次更新结果」会停在上一次的成功上，那比不显示更误导。
 * 而且本模块是**模块级单例**（活过弹窗开合与设置页卸载），结论记在这里才不会被
 * "用户已经关掉弹窗"漏掉（那正是本次要修的 P0）。
 */
function failAt(stage: ApkPushErrorStage, text: string) {
  errorStage.value = stage;
  error.value = text;
  useUpdateStore().recordResult(false, '', text);
}

/** `/api/update/*` 的失败信封是 `{ error, message, code }`，两个键都要认。 */
function updateErr(e: unknown, fallback: string): string {
  const d = (e as { response?: { data?: { message?: string; error?: string } } })?.response?.data;
  if (d?.message) return d.message;
  if (d?.error) return d.error;
  if (e instanceof Error && e.message) return e.message;
  return fallback;
}

// 模块级单例状态：跨弹窗开合、跨面板卸载都活着
const file = ref<File | null>(null);
const uploading = ref(false);
const uploadPercent = ref(0);
const uploadedBytes = ref(0);
const totalBytes = ref(0);
const error = ref('');
const errorStage = ref<ApkPushErrorStage>('');
/**
 * 「本次推送还没被用户结束」。
 *
 * 为什么需要它：core 的 `/api/update/status` 是**历史结论的载体** —— 上一次更新成功后会一直
 * 停在 `done`，失败后停在 `failed`。弹窗只按 core 状态决定显示什么的话，用户下次点「本地更新」
 * 看到的是上一次的结论，连选文件的机会都没有。所以"这次推送"必须有自己的生命周期：
 * 选完包（[startUpload]）置真，用户按「完成」/「知道了」或重新选包（[reset]）置假。
 */
const active = ref(false);
/**
 * 「安装请求已发出，还没看到 core 的任何反应」。
 *
 * 为什么需要它：上传成功后 core 会先把状态置回 `idle`（`finishUpload` 的语义是"包已就位"），
 * 而 `install-local` 要过一会儿才把状态推到 `installing`。没有这个标记，弹窗会在那一小段
 * 空窗里闪回选文件区（"我明明在装，怎么又让我选文件"）。
 * 它同时把另一件事说清楚：这一小段里 core 并不是"没事干"，所以不该让用户去点上传。
 */
const installing = ref(false);

export interface LocalApkPush {
  /** 最近一次选中的文件。**留着是为了「重试」不必让用户再选一遍**（同一 SPA 会话内有效）。 */
  file: Ref<File | null>;
  uploading: Ref<boolean>;
  uploadPercent: Ref<number>;
  uploadedBytes: Ref<number>;
  totalBytes: Ref<number>;
  /** 本地阶段的失败原文（分类成"原因 + 建议"交给调用方，见 `updateShared.ts`）。 */
  error: Ref<string>;
  /** 失败发生在哪一段（`upload` = 设备上还没有包；`install` = 包已就位，只是没能开装）。 */
  errorStage: Ref<ApkPushErrorStage>;
  /** 本次推送是否还没被用户结束（见上面 `active` 的说明）。 */
  active: Ref<boolean>;
  /** 安装请求在途（见上面 `installing` 的说明）。 */
  installing: Ref<boolean>;
  startUpload: (f: File) => Promise<void>;
  retry: () => Promise<void>;
  /** 回到选文件态（保留 [file]，用户可以直接再点一次上传）。 */
  reset: () => void;
}

/**
 * 把 store 的轮询接回来。
 *
 * 为什么需要它（`install-local` 的响应被打断时）：core 的安装会杀掉 core 进程
 * （`PackageInstaller.commit` 终止 core），HTTP 响应**可能**因连接被重置/超时而中断 ——
 * 这是预期行为、不是安装失败（app 侧同口径注释见 `ToolsModule.pushApkAndInstall` 的
 * 364-402 行）。而 `stores/update.ts` 的 `installLocal` 在抛异常时**不会**起轮询
 * （`beginInstallWatch()` 写在它后面，见 stores/update.ts:421-432），core 此刻又在重启、
 * 第一次 `/api/update/status` 很可能连不上，`resume()` 那种情况下也不起表
 * （它只在读到忙态时才 `startTimer`，见 stores/update.ts:439-449）。
 *
 * 所以这里做有限次静默重试，**直到 store 真的在盯守**为止。
 * 这不是"第二套轮询"：起表之后仍然只有 store 那一条定时器，这里只是敲门。
 */
async function attachWatch(store: ReturnType<typeof useUpdateStore>): Promise<void> {
  for (let i = 0; i < ATTACH_TRIES; i++) {
    if (store.watching) return;
    const ds = store.displayState;
    // 已经有结论了（安装完成 / 失败 / 超时）：不需要再起表
    if (ds === 'done' || ds === 'failed' || ds === 'timeout') return;
    await store.resume();
    if (store.watching) return;
    await sleep(ATTACH_INTERVAL_MS);
  }
  // 静默重试用尽仍接不上：交给 store 的「重新检查状态」兜底。它是"手动再确认一次"的入口 ——
  // 读到忙态会起表、读到 done 会走 /health + 版本复核；确实连不上时会推一条错误提示。
  // 宁可让用户看见"没人在盯这次更新了"，也不要让进度环永远冻在"正在安装"。
  await store.recheck();
}

/**
 * 调 `install-local` 并保证 store 的轮询被接上。
 *
 * 返回后 store 要么在盯守（`watching`）、要么已经有终态；两条都不成立时**明确置失败**
 * （见末尾），绝不让界面停在一个没有人在盯、也没有出路的"正在安装"。
 */
async function installLocalAndWatch(apkPath: string): Promise<void> {
  const store = useUpdateStore();
  // 覆盖整段（含 attachWatch）：这一段里 core 都不该被别的上传打扰
  installing.value = true;
  try {
    try {
      const ok = await store.installLocal(apkPath);
      if (!ok) {
        // core 明确拒绝（忙 / 包不存在 / 拿不到版本基线），它已经把原因写进 status.message
        failAt('install', store.status.message || '安装未启动');
        return;
      }
    } catch (e) {
      // 分两种，判据是**有没有响应**（app 侧同口径：`HttpException` 判真失败、IO 异常继续等）：
      // · 有响应 = core 明确回了失败（400/409/444…），那是真失败，把它的原话透出；
      // · 无响应 = 连接被重置/超时 —— core 正在自杀重启，属预期中断（见 attachWatch 的说明），
      //   不能判失败，去把状态查回来。
      const status = (e as { response?: { status?: number } })?.response?.status;
      if (status) {
        failAt('install', updateErr(e, '安装失败'));
        return;
      }
    }
    await attachWatch(store);
    const ds = store.displayState;
    if (!store.watching && ds !== 'done' && ds !== 'failed' && ds !== 'timeout') {
      // attachWatch 连它的 recheck 兜底都没接上（core 一直连不上，或重启后丢掉了这次更新的记录）。
      // 必须说出来：否则进度环会永远冻在"正在安装"，而用户以为还在装。
      failAt(
        'install',
        '安装请求已发出，但设备约 10 秒内没有回报状态（core 可能已重启并丢失了这次更新的记录）。请重新检查状态或重试。'
      );
    }
  } finally {
    installing.value = false;
  }
}

/** 上传 APK 并在成功后**立刻**安装 —— 中间不再出现第二颗按钮。 */
async function startUpload(f: File): Promise<void> {
  if (uploading.value) return;
  file.value = f;
  active.value = true;
  error.value = '';
  errorStage.value = '';
  uploading.value = true;
  uploadPercent.value = 0;
  uploadedBytes.value = 0;
  totalBytes.value = f.size;
  try {
    const formData = new FormData();
    // 字段名必须是 file：core 只认这一个 multipart 字段名
    formData.append('file', f);
    const { data } = await getApiClient().post(Endpoints.update.upload, formData, {
      headers: { 'Content-Type': 'multipart/form-data' },
      // core APK 约 15MB，设备 Wi-Fi 下要几十秒 —— 默认 15s 超时会在传到一半时掐断
      timeout: 0,
      onUploadProgress: (ev: { loaded: number; total?: number }) => {
        // e.total 在个别浏览器/代理下缺失，回落到 file.size
        const total = ev.total || f.size;
        if (!total) return;
        const loaded = Math.min(ev.loaded, total);
        uploadedBytes.value = loaded;
        totalBytes.value = total;
        uploadPercent.value = Math.floor((loaded / total) * 100);
      },
    });
    const apkPath = typeof data?.apk_path === 'string' ? data.apk_path : '';
    if (!apkPath) {
      failAt('upload', String(data?.message || data?.error || '上传失败：设备没有返回 APK 路径'));
      return;
    }
    // 上传成功 → 立刻安装。用户描述里的"选择好后在弹窗显示进度"到这里就接上了，
    // 不再有「安装已上传的包」那颗第二步按钮。
    const store = useUpdateStore();
    // 老 core 的 /status 不回 apk_path，先本地记下，界面上的"设备上已有包"才不会闪没
    store.setApkPath(apkPath);
    await installLocalAndWatch(apkPath);
  } catch (e) {
    // 409 UPDATE_IN_PROGRESS / 400 BAD_REQUEST / INVALID_PACKAGE（包名不匹配）都会到这里，
    // 原因原文（core 写好的中文）直接透出，分类交给调用方
    failAt('upload', updateErr(e, '上传 APK 失败'));
  } finally {
    uploading.value = false;
    uploadPercent.value = 0;
  }
}

/**
 * 重试。**先读 core 的语义再决定重传还是只重装**（这是本文件唯一需要解释的决策）：
 *
 * · `POST /api/update/install-local` 装的是**设备上那个固定路径**的包
 *   （`/sdcard/Download/UFI-AXIS/update/ufi-core-uploaded.apk`，见 core 的
 *   `UpdateManager.installLocalApk`：它只 `File(apkPath)` 检查存在性后交给
 *   AppManager 安装），所以「安装阶段失败」重试**不需要重传** ——
 *   15MB 重传在设备 Wi-Fi 下要几十秒，而设备上那个包原封不动。
 * · 唯一例外是 core 明确回「APK 文件不存在」：那时包真的没了（重启清理 / 被下一次上传
 *   删掉），继续 installLocal 只会立刻再失败一次，必须重传。
 * · 「上传阶段失败」时设备上根本没有可装的包，必须重传；文件对象还在内存里
 *   （同一个 SPA 会话），直接重传，不必让用户再选一遍。
 * · 刷新过页面（文件对象没了）且设备上也没包 → 回到选文件态，让用户重新选。
 */
async function retry(): Promise<void> {
  if (uploading.value) return;
  const store = useUpdateStore();
  const apkPath = store.status.apkPath;
  const fileGoneOnDevice = store.status.message.includes('APK 文件不存在');
  const canInstallWithoutUpload = errorStage.value !== 'upload' && !!apkPath && !fileGoneOnDevice;

  error.value = '';
  errorStage.value = '';
  if (canInstallWithoutUpload) {
    await installLocalAndWatch(apkPath);
    return;
  }
  if (file.value) {
    await startUpload(file.value);
    return;
  }
  reset();
}

/**
 * 回到选文件态（用户按了「完成」/「知道了」，或主动重新选包）。
 * **保留 [file]**：用户可能只是想换一个包，重新选会覆盖它。
 */
function reset(): void {
  active.value = false;
  installing.value = false;
  error.value = '';
  errorStage.value = '';
  uploadPercent.value = 0;
  uploadedBytes.value = 0;
  totalBytes.value = 0;
}

export function useLocalApkPush(): LocalApkPush {
  return {
    file,
    uploading,
    uploadPercent,
    uploadedBytes,
    totalBytes,
    error,
    errorStage,
    active,
    installing,
    startUpload,
    retry,
    reset,
  };
}

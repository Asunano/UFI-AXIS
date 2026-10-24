/**
 * 更新相关的**展示口径**（2026-10-14）：状态 → 标签/标签色，失败 → 原因 + 建议。
 *
 * ## 为什么单独一个文件
 * 两张卡（「前端资源」/「后端更新」）与两个弹窗（`LocalUpdateModal.vue` core APK、
 * `WebUpdateModal.vue` 前端资源 ZIP）都要这两张表。抄成两份的代价不是重复代码，而是
 * **同一个状态在两个地方叫两个名字** —— 本仓在通知级别名上已经踩过一次
 * （app「一般（info）」vs web「一般及以上（全部通知）」，见 contract.ts 里那段说明）。
 *
 * ## 为什么不放 store
 * store 里是状态，这里是中文展示口径；`stores/update.ts` / `stores/webUpdate.ts` 都不该
 * 认识"标签色"这种东西。也**不**放 `views/settings/settingsShared.ts`：那个文件是设置项的
 * 共用口径（保存语义、开关版式），与更新流程无关。
 *
 * ## 否决过的替代方案
 * · 各组件各留一份：正是上面那条"两个名字"的来源；
 * · 塞进 `stores/update.ts`：那个文件已被本次改造列为**不可改**（状态机已就绪），
 *   且往 store 里加 UI 文案会把状态与展示绑死。
 */

/** naive-ui 标签色档。 */
export type StateTagType = 'default' | 'info' | 'success' | 'warning' | 'error';

export interface StateMeta {
  label: string;
  type: StateTagType;
}

/**
 * `/api/web/status` 与 `/api/update/status` 共有的状态键。
 *
 * 两个通道的 state 名刻意取成同一套（core 侧 `WebUpdateManager.State` 与
 * `UpdateManager.State` 只差一个 `UPLOADING`），所以这张表是两者共用的底座，
 * core APK 那张只在它之上补两个**只存在于前端**的键。
 */
export const UPDATE_STATE_META: Record<string, StateMeta> = {
  idle: { label: '空闲', type: 'default' },
  checking: { label: '检查中', type: 'info' },
  downloading: { label: '下载中', type: 'info' },
  verifying: { label: '校验中', type: 'info' },
  installing: { label: '安装中', type: 'warning' },
  uploading: { label: '上传中', type: 'info' },
  done: { label: '更新完成', type: 'success' },
  failed: { label: '更新失败', type: 'error' },
  need_push: { label: '需手动上传', type: 'warning' },
};

/**
 * core APK 通道多出来的两档，它们**只存在于前端**（core 侧没有对应 state）：
 * · `restarting` —— core 已装完正在重启（`stores/update.ts` 的本地阶段）；
 * · `timeout`    —— 前端等到超时（同样是本地阶段）。
 * 这两个键必须在表里，否则 `displayState` 落到它们时标签会退化成裸英文串。
 */
export const BACKEND_STATE_META: Record<string, StateMeta> = {
  ...UPDATE_STATE_META,
  restarting: { label: 'core 重启中', type: 'warning' },
  timeout: { label: '更新超时', type: 'error' },
};

/**
 * 可选组件通道（frpc / cloudflared，2026-10-14 资源中心接入）多出来的一档：
 * `extracting` —— core 的 `ComponentManager.State` 比 `WebUpdateManager.State` 多一个
 * `EXTRACTING`（官方包是 tar.gz 时，下载完要先从压缩包里取二进制）。
 *
 * 与 `BACKEND_STATE_META` 同一个做法：**在公共表之上补差异**，而不是另抄一张。
 * 少写这一项的表现是标签退化成裸英文 `extracting`（`stateLabel` 的兜底），
 * 而"下载完还要解包"这一段恰恰是最容易被误读成"卡住了"的阶段。
 */
export const COMPONENT_STATE_META: Record<string, StateMeta> = {
  ...UPDATE_STATE_META,
  extracting: { label: '解压中', type: 'info' },
};

/** 取标签，认不出的键退化成裸 state（宁可露出英文，也不要显示成"空闲"骗人）。 */
export function stateLabel(table: Record<string, StateMeta>, state: string): string {
  return table[state]?.label ?? state;
}

/** 取标签色，认不出的键用 default。 */
export function stateTagType(table: Record<string, StateMeta>, state: string): StateTagType {
  return table[state]?.type ?? 'default';
}

/** 失败的两行：原因 + 建议。 */
export interface PushError {
  cause: string;
  hint: string;
}

/**
 * 推送/更新失败的原因 + 建议分类。
 *
 * **逐条移植自 app 端** `app/feature-settings/src/main/java/com/ufi_axis/ui/components/
 * UpdateDialogs.kt` 的 `classifyPushError`（552-579 行）：分支**顺序**、匹配关键字、
 * 除名词以外的文案全部照抄。两端同一个失败必须给同一句话，否则用户在 app 与 web 上
 * 看到两种解释、也就没法互相印证。**不要在这里"顺手优化"措辞** —— 要改就两端一起改。
 *
 * 匹配对象是 `state.message`：core 的中文原因、Ktor/Java 的英文异常、errno 都可能出现，
 * 所以既有 `HTTP 413` 这种状态码，也有 `ENOSPC` 这种 errno。
 *
 * 与 app 那份**唯一**的差异是 `artifact` 参数：app 只推 APK，web 还有一条推 ZIP 的
 * 前端资源更新链路，而 413/文件读取/SHA-256/体积这四条的文案里带着"APK"这个名词。
 * 参数化只换这个名词，**不新增也不合并任何分类** —— 分支条件一字未动。
 *
 * 2026-10-14 补第三档 `BIN`：资源中心把 frpc / cloudflared 也收进来，它们推的是**裸二进制**
 * （或官方 tar.gz），不是 APK 也不是 ZIP。同样只加枚举值，分支一字未改。
 *
 * @param message `state.message`（可为空）
 * @param artifact 载体名，只影响上面说的四处文案
 */
export function classifyPushError(
  message: string | null | undefined,
  artifact: 'APK' | 'ZIP' | 'BIN' = 'APK'
): PushError {
  const msg = (message ?? '').trim();
  if (!msg) return { cause: '未知错误', hint: '请重试或检查 Core 端日志' };
  const has = (needle: string) => msg.includes(needle);
  const hasIgnoreCase = (needle: string) => msg.toLowerCase().includes(needle.toLowerCase());

  if (has('HTTP 413') || has('Entity Too Large')) {
    return {
      cause: `${artifact} 文件超过后端请求体上限`,
      // 2026-10-14：这里原来硬编码 "APK"（原文如此），于是资源中心里推 frpc / cloudflared
      // 的二进制撞上 413 时，建议里会显示"检查 APK 体积"—— 名词与载体对不上。
      // 改成与 cause 同一个 `${artifact}`，四条带名词的分类（413 / 文件读取 / SHA-256 / 体积）口径一致。
      hint: `确认 Core 版本支持动态上传限制（commit 7c26ee3+），否则检查 ${artifact} 体积或更新 Core`,
    };
  }
  if (has('HTTP 500') || has('HTTP 502') || has('HTTP 503')) {
    return { cause: '设备端 Core 服务器错误', hint: '请稍后重试；若持续失败请检查设备端 Core 日志' };
  }
  if (has('HTTP 404')) {
    return { cause: '设备端接口路径不存在', hint: '确认设备端 Core 版本与前端 App 兼容' };
  }
  if (has('HTTP 401') || has('HTTP 403') || has('Unauthorized')) {
    return { cause: '设备配对鉴权失败', hint: '检查设备端 Core 配对 token 是否与前端一致' };
  }
  if (
    has('ConnectException') ||
    has('无法连接') ||
    hasIgnoreCase('timeout') ||
    has('Failed to connect') ||
    has('ECONNREFUSED')
  ) {
    return { cause: '无法连接设备 Core', hint: '检查设备 IP/端口是否可达、Core 是否在线、是否在同一局域网' };
  }
  if (has('脚本启动失败') || has('守护脚本') || has('ADB')) {
    return {
      cause: '更新脚本启动失败',
      hint: '无法写入/执行守护脚本，请检查 ADB 通道（设备开发者模式+USB调试授权）后重试',
    };
  }
  if (has('磁盘') || has('space') || has('ENOSPC')) {
    return { cause: '设备磁盘空间不足', hint: `清理设备端 /sdcard/UFI/update 目录下的旧 ${artifact} 文件后重试` };
  }
  if (has('FileNotFound') || has('NoSuchFile') || has('无法读取')) {
    return { cause: `${artifact} 文件读取失败`, hint: `确认所选 ${artifact} 文件存在且可读，重新选择文件后重试` };
  }
  if (has('APK SHA-256') || has('校验失败')) {
    return { cause: `${artifact} 签名/SHA-256 校验失败`, hint: `检查 ${artifact} 文件完整性或重新下载官方版本` };
  }
  if (has('file size') || has('体积')) {
    return { cause: `${artifact} 体积不符合预期`, hint: `确认 ${artifact} 是官方 release 包，未被截断` };
  }
  // 兜底：把 core 的原话当原因（它通常已经是一句完整中文），建议给排查方向
  return { cause: msg, hint: '请重试或查看设备端 Core 日志（adb logcat / UFI/log）' };
}

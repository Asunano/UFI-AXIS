/**
 * 设置页各分栏共用的小工具。
 *
 * 背景：原来 SettingsView.vue 是 1740 行单文件、10 张卡平铺在一个 2 列网格里，
 * onMounted 一次并发 11 个请求（其中 GET /api/config 被请求两次）。拆成分栏后每个
 * 面板自己加载自己的数据，这里只保留真正被多个面板复用的部分，避免又长出两份实现。
 */
import type { ConfigRejectedField } from '@/api/contract';
import { describeConfigReject } from '@/api/contract';

/**
 * 只把「表单值与 core 返回的原值不同」的字段放进 PUT /api/config 的 body（部分更新语义）。
 *
 * 2026-09-04：**跳过 `original` 里不存在的字段**。此前只比较值，于是
 * 「GET /api/config 失败（GeneralPanel 里是 silent catch）或 core 没返回某个键」时，
 * `original` 是空的，而表单还是硬编码初值（`logEnabled: true` / `appLogEnabled: true` …），
 * 用户一点保存就把这些**从没读到过的默认值**全量 PUT 上去 —— 手机端刚关掉的日志开关
 * 就这样被 web 端一次保存又打开了（用户报的「a 在 app 端关闭、b 在 web 端访问后又变开」）。
 * 没读到过的字段一律不提交，才是真正的部分更新。
 */
export function buildChangedPayload(
  formKeys: Record<string, string>,
  form: Record<string, any>,
  original: Record<string, any>
): Record<string, any> {
  const payload: Record<string, any> = {};
  for (const [formKey, apiKey] of Object.entries(formKeys)) {
    if (!(apiKey in original)) continue;
    if (form[formKey] !== original[apiKey]) {
      payload[apiKey] = form[formKey];
    }
  }
  return payload;
}

/** 最小的 message API 形状，避免把 naive-ui 的类型拖进来 */
interface MessageLike {
  success(content: string): void;
  warning(content: string): void;
  error(content: string): void;
}

/**
 * 处理 PUT /api/config 的「部分成功」响应。
 *
 * core 从 C03 起会回 `rejected_fields`（带 reason 与 min/max），但
 * `updated_fields` 仍是「真正生效的字段」的唯一依据 —— 老 core 没有
 * rejected_fields，此时按「提交了但没进 updated_fields」推断被拒字段。
 *
 * @param onReject 有字段被拒时调用，用来重新拉一次配置、把表单回滚成设备实际采纳的值。
 *                 拆分栏之后各面板只需回滚自己那份（另一个面板没挂载，下次打开自然是新的）。
 */
export function commitConfigSave(
  payload: Record<string, any>,
  data: any,
  original: Record<string, any>,
  section: string,
  message: MessageLike,
  onReject: () => void
): void {
  const updated: string[] = data?.updated_fields || [];
  const rejectedDetail: ConfigRejectedField[] = data?.rejected_fields || [];
  const rejectedKeys =
    rejectedDetail.length > 0
      ? rejectedDetail.map((r) => r.field)
      : Object.keys(payload).filter((k) => !updated.includes(k));

  for (const key of updated) {
    if (key in payload) original[key] = payload[key];
  }

  if (rejectedKeys.length > 0) {
    const detail =
      rejectedDetail.length > 0
        ? rejectedDetail.map(describeConfigReject).join('；')
        : `取值超出后端允许范围：${rejectedKeys.join(', ')}`;
    message.error(`${section}部分未生效 —— ${detail}`);
    onReject();
    return;
  }

  if (data?.needs_restart) {
    message.warning(`${section}已保存，${data.hint || '需要重启服务生效'}`);
  } else {
    message.success(`${section}已保存`);
  }
}

/**
 * 毫秒 → "3 天 2 小时 5 分" / "5 小时 12 分" / "42 分 3 秒" / "9 秒"。
 *
 * 收的是**毫秒**（内部自己 /1000），与拆分前 SettingsView.vue 里的实现逐字一致 ——
 * 「服务控制」的运行时长和「系统信息」的开机时长都直接把毫秒差传进来，
 * 换成收秒会让两处都显示错值。
 */
export function formatUptime(ms: number): string {
  if (!ms || ms <= 0) return '--';
  const totalSec = Math.floor(ms / 1000);
  const d = Math.floor(totalSec / 86400);
  const h = Math.floor((totalSec % 86400) / 3600);
  const m = Math.floor((totalSec % 3600) / 60);
  const s = totalSec % 60;
  if (d > 0) return `${d} 天 ${h} 小时 ${m} 分`;
  if (h > 0) return `${h} 小时 ${m} 分`;
  if (m > 0) return `${m} 分 ${s} 秒`;
  return `${s} 秒`;
}

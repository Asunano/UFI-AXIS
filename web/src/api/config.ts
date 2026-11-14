/**
 * `/api/config` 的唯一入口（2026-10-30）。
 *
 * ## 为什么要有这个模块
 * 这条端点在 5 个面板里各写了一遍（GeneralPanel / LogsPanel / PerformancePanel /
 * UpdatePanel / PtyPane），共 9 处调用。重复的代价不是多打几个字，而是三件事会分别漂移：
 *
 *   · **路径**：`/api/config` 与 `/api/config/reset` 是两个端点，后者**不在**本模块里 ——
 *     写错成同一个，就会把"恢复默认配置"发成一个普通 PUT；
 *   · **取消语义**：`useCancellableApi()` 在组件卸载时把响应 resolve 成 `data: undefined`，
 *     每个调用点都得判断"是没数据还是被取消"（写错就是把已读到的基线抹成未加载）；
 *   · **写回响应的解释**：core 部分成功也返回 `success: true`，判断依据是
 *     `updated_fields` / `rejected_fields`，全靠各面板自己解析，少判一处就会出现
 *     "界面显示已保存、设备其实拒了"。
 *
 * ## 刻意不做的事
 *   · **不缓存**：几个面板读的是同一份配置，但缓存会让"另一个面板刚写完"在这里看不见。
 *   · **不吞错**：axios 错误照旧抛给调用方，由各面板决定文案（多数走 `toApiErrorMessage`）。
 *   · **不改写键名**：`DeviceConfig` 是 core `AppSettings` 原样平铺的键值表，键集合不属于前端。
 *   · **不做默认值**：读不到某个键就是读不到，调用方据此把控件禁用（见各面板的 `*Loaded`）。
 */
import type { AxiosInstance } from 'axios';
import { Endpoints } from './contract';
import type { ConfigRejectedField } from './contract';

/**
 * 本模块需要的客户端能力。
 *
 * 用结构类型而不是直接 import `getApiClient()`：调用方可能用 `getApiClient()`（不取消）
 * 或 `useCancellableApi()`（组件卸载即取消），两者都满足这两个方法，模块不该替它们选。
 */
export type ConfigClient = Pick<AxiosInstance, 'get' | 'put'>;

/**
 * `GET /api/config` 的响应：core 把设置表原样平铺成键值对。
 *
 * 值类型随键而定（布尔开关 / 端口数字 / 地址字符串），前端不做二次建模 ——
 * 需要类型收窄的地方在各面板的 form 上，不在这里造一份"全量配置类型"。
 */
export type DeviceConfig = Record<string, unknown>;

/**
 * `PUT /api/config` 的响应。
 *
 * **部分成功仍返回 `success: true`**（API 文档 5426 行），所以判断生效与否只能看：
 *   · `updated_fields` —— 真正写进 core 的键；
 *   · `rejected_fields` —— 被逐键校验拦下的，带原因（文案见 `describeConfigReject`）。
 */
export interface ConfigUpdateResult {
  success?: boolean;
  updated_fields?: string[];
  rejected_fields?: ConfigRejectedField[];
  /** 本次改动需要重启后台服务才生效（如日志开关） */
  needs_restart?: boolean;
  /** `needs_restart` 为 true 时 core 给的说明，直接展示 */
  hint?: string;
}

/**
 * 读配置。
 *
 * 返回 `undefined` 专门表示**请求被取消**（`useCancellableApi` 的取消语义），
 * 调用方必须直接返回、不要当成"读到了空配置"，否则会把表单刷成默认值。
 */
export async function fetchConfig(api: ConfigClient): Promise<DeviceConfig | undefined> {
  const { data } = await api.get<DeviceConfig>(Endpoints.config.root);
  return data;
}

/** 写配置（只提交改动过的键）。同样以 `undefined` 表示被取消。 */
export async function updateConfig(
  api: ConfigClient,
  patch: Record<string, unknown>
): Promise<ConfigUpdateResult | undefined> {
  const { data } = await api.put<ConfigUpdateResult>(Endpoints.config.root, patch);
  return data;
}

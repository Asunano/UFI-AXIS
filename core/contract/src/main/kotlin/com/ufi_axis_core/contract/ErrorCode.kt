package com.ufi_axis_core.contract

/**
 * 失败响应的 `code` 取值（C01）。
 *
 * 为什么需要它：core 原先的失败信封三种写法混用（`{success:false,error}`、
 * `{ok:false,error}`、纯 `{error}`），键名与文案都不稳定，两端只能逐端点猜"读 error 还是 message"。
 * 统一后**客户端只需要认 `code`**，文案变化不再影响判定逻辑。
 *
 * 规则：
 * - 值是**稳定的机器可读标识**，一旦发布不得改名（改名等于破坏契约）；
 * - 已在 core 出现过的字面量原样收进来（`INVALID_CODE`/`PASSWORD_LOCKED`/…），不改值；
 * - 新增失败分支若无对应项，优先用本文件的通用码，不要就地编字符串。
 */
object ErrorCode {

    // ── 通用 ──
    /** 请求参数缺失/格式错误（HTTP 400）。 */
    const val BAD_REQUEST = "BAD_REQUEST"
    /** 未认证或凭据无效（HTTP 401）。 */
    const val UNAUTHORIZED = "UNAUTHORIZED"
    /** 已认证但不允许（HTTP 403），如路径越权、URL 不在白名单。 */
    const val FORBIDDEN = "FORBIDDEN"
    /** 目标不存在（HTTP 404）。 */
    const val NOT_FOUND = "NOT_FOUND"
    /** 状态冲突（HTTP 409），如已存在、正在进行中。 */
    const val CONFLICT = "CONFLICT"
    /** 触发限频（HTTP 429）。 */
    const val TOO_MANY_REQUESTS = "TOO_MANY_REQUESTS"
    /** 服务端内部错误（HTTP 500）。 */
    const val INTERNAL_ERROR = "INTERNAL_ERROR"
    /** 依赖的下层能力不可用（设备/goform/root shell/上游），HTTP 502/503 或 200+success:false。 */
    const val UNAVAILABLE = "UNAVAILABLE"
    /** 操作被设备或系统拒绝（命令执行失败、goform 返回失败）。 */
    const val OPERATION_FAILED = "OPERATION_FAILED"
    /** 数据尚未就绪（采集未预热），如 `/api/traffic/realtime`。 */
    const val NO_DATA_YET = "NO_DATA_YET"

    // ── 配对 / 配对密码（沿用 core 已有字面量，禁止改值）──
    const val INVALID_CODE = "INVALID_CODE"
    const val ALREADY_PAIRED = "ALREADY_PAIRED"
    const val INVALID_PASSWORD = "INVALID_PASSWORD"
    const val PASSWORD_REQUIRED = "PASSWORD_REQUIRED"
    const val MISSING_PASSWORD = "MISSING_PASSWORD"
    const val WRONG_OLD_PASSWORD = "WRONG_OLD_PASSWORD"
    const val PASSWORD_LOCKED = "PASSWORD_LOCKED"
    const val INVALID_NEW_PASSWORD = "INVALID_NEW_PASSWORD"
    const val INVALID_DEVICE_NAME = "INVALID_DEVICE_NAME"
    const val DEVICE_NOT_FOUND = "DEVICE_NOT_FOUND"
    const val INVALID_GOFORM_CONFIG = "INVALID_GOFORM_CONFIG"

    // ── 严格设备独立性（2026-08-28：设备身份=不可导出密钥对）──
    /** `device_pubkey` 无法解析为 EC 公钥，或用它验签挑战/请求失败（HTTP 401）。 */
    const val INVALID_DEVICE_KEY = "INVALID_DEVICE_KEY"
    /** 配对挑战不存在 / 已被使用 / 已过期，客户端应重新 `POST /pairing/challenge`（HTTP 401）。 */
    const val INVALID_CHALLENGE = "INVALID_CHALLENGE"
    /** 请求签名缺失、验签失败或 nonce 重放（HTTP 401）：客户端换新的 ts/nonce 重签**重试**即可。 */
    const val INVALID_SIGNATURE = "INVALID_SIGNATURE"

    /**
     * 请求 `X-Timestamp` 超出 ±5min 窗口（HTTP 401）。
     *
     * 2026-09-08 从 [INVALID_SIGNATURE] 里拆出来：时钟漂移是**可恢复**的，
     * 客户端该做的是校时后重试，而不是像收到 444 那样清掉设备凭据去重新配对。
     */
    const val STALE_TIMESTAMP = "STALE_TIMESTAMP"

    /**
     * 配对存储不可读（HTTP 503），服务端处于降级态，**无法判断**请求方是否已配对。
     *
     * 2026-09-08 事故的对外契约：`paired_devices.json` 损坏时若回 444「你没配对」，
     * 客户端会照约定清空本地 token → 全员重新配对 + 重新输密码。
     * 回 503 + 本码的语义是「保留凭据，退避重试」。
     */
    const val AUTH_STORE_UNAVAILABLE = "AUTH_STORE_UNAVAILABLE"


    // ── 告警配置 ──
    /** `PUT api/alerts/config` 版本守门失败（HTTP 409），值与历史响应一致。 */
    const val CONFIG_VERSION_CONFLICT = "config_version_conflict"

    // ── 配置写入被拒的原因（C03：`PUT /api/config` 的 `rejected_fields[].reason`）──
    /** 数值不在允许区间内，响应同时带 `min`/`max`（见 [ConfigLimits]）。 */
    const val OUT_OF_RANGE = "OUT_OF_RANGE"
    /** 回写了 GET 返回的脱敏值（含 `***`），会覆盖真实密钥，因此拒绝。 */
    const val MASKED_VALUE = "MASKED_VALUE"
    /** 该字段不允许空串（如 token/secret/goform_ip）。 */
    const val BLANK_VALUE = "BLANK_VALUE"
    /** 键存在但值类型不对（如 `"port": "8080"` 传成字符串），旧实现会静默丢弃。 */
    const val WRONG_TYPE = "WRONG_TYPE"

    // ── 更新 / 上传 ──

    /** 更新任务进行中，拒绝并发操作。 */
    const val UPDATE_IN_PROGRESS = "UPDATE_IN_PROGRESS"
    /** 上游（镜像/下载源）不可用或返回非 2xx。 */
    const val UPSTREAM_FAILED = "UPSTREAM_FAILED"

    // ── 文件 ──
    /** 危险路径（写 /system 等）被拦截。 */
    const val DANGEROUS_PATH = "DANGEROUS_PATH"
    /** 目标已存在（复制/移动/重命名）。 */
    const val ALREADY_EXISTS = "ALREADY_EXISTS"

    // ── 定时任务 / 自动化 ──
    /** actionType 不在 [ActionType] 白名单内。 */
    const val INVALID_ACTION_TYPE = "INVALID_ACTION_TYPE"
}

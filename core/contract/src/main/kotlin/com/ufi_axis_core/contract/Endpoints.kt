package com.ufi_axis_core.contract

/**
 * 端点路径常量。
 *
 * 约定：
 * - 常量值**不带前导斜杠**，与 app 侧 Retrofit 注解现状一致（`@GET("api/alerts/list")`）；
 *   需要带斜杠的场景（web / Ktor 路由）用 [abs] 包一层。
 * - 本文件**不追求一次性覆盖全部 200+ 端点**。规则是：
 *   **本次改到的端点必须走 contract**（新增或改动某端点时把它挪进来），
 *   避免又造出一份"看起来权威、实际半旧"的清单。
 * - 校验器 `scripts/verify-api-contract.mjs` 是这份清单的守门人：
 *   声明了 core 不存在的端点会报 P0。
 *
 * ## 负面清单（**core 从不存在**，禁止在任何一端声明）
 * 首条即历史事故来源：
 * - `api/adb/status`、`api/adb/ping`、`api/adb/auto-start`、`api/adb/start`、
 *   `api/adb/stop`、`api/adb/auto-start`
 *   —— web 曾照文档实现整张卡片，四个请求全 404。ADB / 特权 shell 的只读状态请用
 *   [Shell.ROOT]（返回 `root`/`uid`/`method`）与 [DIAGNOSE]（返回 `adbd`）。
 * - `api/device/usb-mode` —— core 全仓无实现，USB 模式切换目前无后端能力。
 *
 * 登记位置：`docs/UFI-AXIS-Core-API-Reference.md` 末尾「文档幻影端点」小节；
 * 该小节会被校验器解析为负面清单，core 某天真的实现了其中某条时会报 P1 提示回写正文。
 */
object Endpoints {

    /** 统一加上前导斜杠（web / Ktor 侧使用）。 */
    fun abs(path: String): String = if (path.startsWith("/")) path else "/$path"

    // ───────────────────────── 模块前缀 ─────────────────────────

    const val API = "api"

    object Alerts {
        /** 仅前缀，不是端点（core 的 `route("/alerts")` 下没有根级 handler）。 */
        const val BASE = "$API/alerts"
        const val LIST = "$BASE/list"
        const val CONFIG = "$BASE/config"
        const val ACK = "$BASE/ack"
        const val ACK_ALL = "$BASE/ack-all"
        const val ACK_RESOLVED = "$BASE/ack-resolved"
        const val DELETE = "$BASE/delete"
    }

    object Config {
        /** GET 读取 + PUT 局部更新；取值范围见 [ConfigLimits]。 */
        const val BASE = "$API/config"
        const val VERSION = "$BASE/version"
        const val RESET = "$BASE/reset"
    }

    object Monitor {
        /** 仅前缀。**core 没有 `/monitor/overview`** —— 别再凭印象加。 */
        const val BASE = "$API/monitor"
        const val HISTORY = "$BASE/history"
        const val STORAGE = "$BASE/storage"
        const val CLEAN = "$BASE/clean"

        /**
         * 采集开关。与 [Service.START] / [Service.STOP] 写的是同一份持久化状态
         * （`AppSettings.backgroundServiceEnabled`），两边任意一端改动都会互相可见。
         */
        const val CONTROL = "$BASE/control"
    }

    /**
     * 后台服务控制（2026-08-26 新增）。
     *
     * 语义：[STOP] **只停数据采集与告警检测，HTTP 服务不停** —— 所以停止之后仍然能调 [START] 恢复，
     * 不会把客户端锁在门外。[RESTART] 重启后端服务（销毁 Service 组件后由 AlarmManager 以前台服务
     * 语义拉起），接口中断约 10 秒；**注意不是进程级重启**，进程通常不退出、静态状态不清空。
     */
    object Service {
        const val BASE = "$API/service"
        const val STATUS = "$BASE/status"
        const val START = "$BASE/start"
        const val STOP = "$BASE/stop"
        const val RESTART = "$BASE/restart"
        const val AUTOSTART = "$BASE/autostart"

        /** 上一次 core 崩溃的信息（2026-09-04）：崩溃自动重启后由两端各自弹一次窗。 */
        const val CRASH = "$BASE/crash"
    }

    /**
     * Web 前端静态资源的 override 通道（`WebUpdateRoutes`，2026-08-27 入 contract）。
     *
     * 与 core APK 自身的更新是两套东西：这一组换的是 `assets/web` 那份控制面板产物，
     * override 落在 `filesDir/web`。[CLEAR] 会连 `web_backup` / `web_staging` 一起删掉
     * 恢复内置版本 —— 所以 clear 之后 [ROLLBACK] 也没有备份可回了。
     */
    object Web {
        const val BASE = "$API/web"
        const val CHECK = "$BASE/check"
        const val STATUS = "$BASE/status"
        const val VERSION = "$BASE/version"
        const val UPDATE = "$BASE/update"
        const val ROLLBACK = "$BASE/rollback"

        /**
         * 清除 override，恢复 APK 内置版本。**失败时返回 500 且 `success:false`**：
         * core 删完会复核 `hasOverride()`，删不干净就不对客户端谎报成功。
         */
        const val CLEAR = "$BASE/clear"
    }

    object Network {

        const val BASE = "$API/network"
        const val STATUS = "$BASE/status"

        /** 入参走 [NetworkMode] 的别名集，core 会映射成 BearerPreference 再下发。 */
        const val MODE = "$BASE/mode"
        const val BAND = "$BASE/band"
        const val BAND_STATUS = "$BASE/band-status"
        const val CONNECTION_MODE = "$BASE/connection-mode"
    }

    object Shell {
        const val BASE = "$API/shell"

        /** `{ root, uid, method: "adb_shell" | "shell" }` —— 替代不存在的 `api/adb/status`。 */
        const val ROOT = "$BASE/root"

        /** 执行一条 shell 命令（特权命令走 ADB 公共服务，见项目约定）。 */
        const val EXEC = "$BASE/exec"
    }

    object Tasks {
        const val BASE = "$API/tasks"

        /** 自动化规则（与定时任务共用 [ActionType] 白名单）。 */
        const val RULES = "$API/rules"
    }

    object Dashboard {
        const val SUMMARY = "$API/dashboard/summary"
    }

    /**
     * 测速。内网吞吐/探针 + 外网节点白名单转发。
     *
     * [RELAY] 仅供 Web 浏览器使用：外网自建节点未下发 CORS 头，
     * 浏览器无法直连 `https://…/speedtest` 读 body；App 走原生 HTTP 不经此处。
     */
    object SpeedTest {
        const val BASE = "$API/speedtest"
        const val UPLOAD = "$BASE/upload"
        /** GET/POST：`url` 必须是白名单外网节点地址；转发 Range / 流式 body */
        const val RELAY = "$BASE/relay"
    }

    /**
     * 短信。这里只登记 2026-09-08 拦截改造**新增**的端点 ——
     * 本文件的规则是「本次改到的端点必须走 contract」，不追求把 api/sms 下的端点一次性搬完，
     * 免得又造出一份看起来权威、实际半旧的清单。
     *
     * 现存但未登记的：`list` / `contacts` / `count` / `{id}` / `send` / `delete` / `read` /
     * `read-conversation` / `mark-all-read` / `verification-codes`。
     */
    object Sms {
        /** 仅前缀，不是端点。 */
        const val BASE = "$API/sms"

        /**
         * 拦截规则（号码黑名单 + 关键词，同一张表的不同 scope）。
         * `GET` 列表 / `POST` 新增；单条改删走 [RULE_BY_ID]。
         *
         * **没有** `rules/test`：去掉正则后 contains/equals/prefix/suffix 行为可预测，
         * 「规则有没有生效」由 `hit_count` + [BLOCKED] 回答。
         */
        const val RULES = "$BASE/rules"

        /** `PUT`（字段级合并，含 enabled 切换）/ `DELETE` 单条规则。 */
        fun ruleById(id: Long): String = "$RULES/$id"

        /** 路径模板（Ktor 路由 / 文档用）。 */
        const val RULE_BY_ID = "$RULES/{id}"

        /**
         * 拦截记录。`GET` keyset 游标分页（`limit` / `cursor_ts` / `cursor_id`，**无 offset**）；
         * `DELETE` 清空。单条删除走 [BLOCKED_BY_ID]。
         */
        const val BLOCKED = "$BASE/blocked"

        fun blockedById(id: Long): String = "$BLOCKED/$id"

        /** 路径模板。 */
        const val BLOCKED_BY_ID = "$BLOCKED/{id}"
    }

    /**
     * 邮件通知渠道（`SmsForwardRoutes`，2026-09-12 入 contract）。
     *
     * 路径**保持 `sms-forward` 不变**（2026-08-29 由「短信转发」改名后只剩 SMTP 一种通道，
     * 但 app 与 API 手册都按旧路径引用，改名只会破坏跨端契约）。
     *
     * 两处行为约定：
     * - 写配置是 **POST** 而非 PUT，且是字段级合并；凭据字段传空串等于不传（保留原值）；
     * - [CONFIG] 在 `daily_limit` 越界或 `min_level` 认不出时回 **400**（不是 200）；
     *   [TEST] 失败时反而是 HTTP 200 + `{success:false,error}` —— 两者要分开处理。
     */
    object SmsForward {
        const val BASE = "$API/sms-forward"
        const val CONFIG = "$BASE/config"
        const val DIAGNOSE = "$BASE/diagnose"
        const val TEST = "$BASE/test"

        /** 三条渠道（邮件 / Webhook / 本机短信）共用的投递记录，`channel` 列区分。 */
        const val HISTORY = "$BASE/history"
    }

    /**
     * 另两条通知渠道（`WebhookRoutes` / `LocalSmsRoutes`，2026-09-12 入 contract）。
     *
     * 与邮件渠道并列、各自独立存配置。与 [SmsForward.TEST] 同样的坑：两个 test 端点
     * **失败也是 HTTP 200** + `{ success: false, error }`。
     *
     * [SMS_TEST] 会**真的从设备 SIM 发出短信**（产生费用并占用当日配额），调用前必须确认。
     */
    object Notify {
        const val BASE = "$API/notify"

        const val WEBHOOK_CONFIG = "$BASE/webhook/config"
        const val WEBHOOK_TEST = "$BASE/webhook/test"
        const val SMS_CONFIG = "$BASE/sms/config"
        const val SMS_TEST = "$BASE/sms/test"
    }

    /**
     * AT 命令通道（`ATRoutes`，2026-09-12 入 contract）。
     *
     * [COMMAND] 执行单条 AT 指令；[STATUS] / [PLATFORM] 是只读探测。
     * 执行历史由 `ConsoleRoutes` 在写侧记录（`channel=at`），客户端不通过本组写历史。
     */
    object At {
        const val BASE = "$API/at"
        const val COMMAND = "$BASE/command"
        const val STATUS = "$BASE/status"
        const val PLATFORM = "$BASE/platform"
    }

    /** `{ adbd, ... }` —— 诊断快照。 */
    const val DIAGNOSE = "$API/diagnose"

    /**
     * 配置备份与恢复（`BackupRoutes`，2026-09-12 入 contract）。
     *
     * 四个端点全部在 `/api` 鉴权块内，没有免鉴权例外。两处行为约定：
     * - [EXPORT] 在 `encrypted=false` 时**必须**带 `acknowledge_plaintext=true`，
     *   否则 400 —— 明文包里含设备后台密码与隧道凭据，服务端不接受"悄悄导出";
     * - [PREVIEW] / [IMPORT] 的请求体是备份包二进制，口令走 `X-Backup-Passphrase`
     *   请求头（**不是** query：query 会进访问日志与浏览器历史）。
     *
     * 上传体积上限 8MB（`BackupRoutes.MAX_UPLOAD_BYTES`），HTTP 层的
     * 请求体限制按同一常量放行。
     */
    object Backup {
        const val BASE = "$API/backup"
        const val INFO = "$BASE/info"
        const val EXPORT = "$BASE/export"
        const val PREVIEW = "$BASE/preview"
        const val IMPORT = "$BASE/import"
    }

    /**
     * 终端命令历史（`ConsoleRoutes`，2026-09-12 入 contract）。
     *
     * 这一组**只读写历史、不执行命令**：历史的真源是「core 实际执行过什么」，
     * 由 `ShellRoutes` / `ATRoutes` 在执行后写入。唯一的例外是 [HISTORY_IMPORT]，
     * 供两端把升级前的本地历史一次性搬进来（客户端负责幂等，服务端不做去重）。
     *
     * 分页是 keyset 游标：`limit` / `cursor_ts` / `cursor_id`，响应回
     * `next_cursor_ts` / `next_cursor_id` / `has_more`（与 alerts / sms blocked 同形）。
     */
    object Console {
        const val BASE = "$API/console"
        const val HISTORY = "$BASE/history"

        /** `POST`：一次性导入两端本地旧历史，单次最多接受 1000 条。 */
        const val HISTORY_IMPORT = "$HISTORY/import"

        /** `DELETE` 单条。id 不存在也算成功（目标状态「它不在列表里」已达成）。 */
        fun historyById(id: Long): String = "$HISTORY/$id"

        /** 路径模板（Ktor 路由 / 文档用）。 */
        const val HISTORY_BY_ID = "$HISTORY/{id}"
    }

    /** WebSocket 实时通道，订阅协议见 [WsChannel]。 */
    const val WS_REALTIME = "ws/realtime"
}

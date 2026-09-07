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
    }

    object Tasks {
        const val BASE = "$API/tasks"

        /** 自动化规则（与定时任务共用 [ActionType] 白名单）。 */
        const val RULES = "$API/rules"
    }

    object Dashboard {
        const val SUMMARY = "$API/dashboard/summary"
    }

    /** `{ adbd, ... }` —— 诊断快照。 */
    const val DIAGNOSE = "$API/diagnose"

    /** WebSocket 实时通道，订阅协议见 [WsChannel]。 */
    const val WS_REALTIME = "ws/realtime"
}

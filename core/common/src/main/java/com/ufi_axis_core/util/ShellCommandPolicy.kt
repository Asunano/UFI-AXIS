package com.ufi_axis_core.util

/**
 * 危险 Shell 命令判据（2026-10-30 从 `ShellRoutes` 的伴生对象搬到这里）。
 *
 * ## 为什么必须搬
 *
 * 判据原先只存在于 `:core:api` 的 `ShellRoutes` 里，于是**只有 HTTP 入口受它约束**。
 * 而同一台设备上还有第二条执行 root shell 的路：定时任务 / 自动化规则的
 * `custom_shell` 动作（`core/src/.../service/ActionExecutorImpl.kt`）。它直接调
 * `ShellExecutor.executeAsRoot`，什么都不判 ——
 * `/api/shell/exec` 明确回 403 的 `reboot`、`insmod`、`kill -9 rild`
 * 只要「建一个定时任务」就能跑。这是典型的「操作 A（建任务）干了被禁止的 B（危险命令）」，
 * 见 2026-10-30 越权审查 §2.2。
 *
 * 判据放在 `:core:common` 是因为两个调用方分属不同模块且都依赖它：
 * `:core:api`（route）与 `:core`（应用模块，含 ActionExecutorImpl）。
 * 放进任何一边都会让另一边反向依赖。
 *
 * ## 这是什么、不是什么
 *
 * 它是**黑名单**：拦的是「已知会把内核/基带打死」的少数命令，不是「允许的命令清单」。
 * root shell 本身就是最高权限入口，黑名单只降低误操作与脚本事故的概率，
 * 不构成安全边界 —— 真正的边界是「谁拿得到 root shell」（Bearer + 设备签名）。
 */
object ShellCommandPolicy {

    /**
     * 危险命令模式。**逐字搬自** `ShellRoutes`（2026-10-05 R4-9 时期的表），
     * 新增条目请同时考虑 `ATRoutes.DANGEROUS_AT_PATTERNS` 与
     * `DeviceRoutes.DANGEROUS_GOFORM_IDS` 的口径，三者是同一条纪律的三个协议层。
     */
    private val DANGEROUS_SHELL_PATTERNS = listOf(
        // 直接触发内核模块操作
        Regex("\\binsmod\\b", RegexOption.IGNORE_CASE),
        Regex("\\bmodprobe\\b", RegexOption.IGNORE_CASE),
        Regex("\\brmmod\\b", RegexOption.IGNORE_CASE),
        // service call 到 sprd HIDL 服务 (绕过 AT 黑名单)
        Regex("service\\s+call\\s+.*sprd", RegexOption.IGNORE_CASE),
        // 整机重启/关机
        Regex("\\breboot\\b", RegexOption.IGNORE_CASE),
        Regex("\\bpoweroff\\b", RegexOption.IGNORE_CASE),
        Regex("\\bshutdown\\b", RegexOption.IGNORE_CASE),
        // 直接写入 sysfs（可能触发内核竞态）
        Regex("echo\\s+.*\\s*>\\s*/sys/class/(sblock|smem|smsg|mem)", RegexOption.IGNORE_CASE),
        // 杀掉关键系统进程
        Regex("kill\\s+-9\\s+.*(rild|modem|netd|servicemanager)", RegexOption.IGNORE_CASE)
    )

    /**
     * 该命令是否命中危险黑名单。
     *
     * 判据是**子串正则**（`containsMatchIn`），不是整串匹配：命令里只要出现
     * `reboot` 这个词就拦，包括 `svc power reboot`、`sh -c 'reboot'` 这类包装。
     * 代价是 `echo reboot` 也会被拦 —— 宁可误拦一条读命令，也不放行一条重启。
     */
    fun isDangerous(command: String): Boolean =
        DANGEROUS_SHELL_PATTERNS.any { it.containsMatchIn(command) }
}

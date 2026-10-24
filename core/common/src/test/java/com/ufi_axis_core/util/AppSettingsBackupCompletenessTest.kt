package com.ufi_axis_core.util

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「`AppSettings` 的每个 key 都必须显式表态」的守卫测试（2026-10-15）。
 *
 * ## 为什么需要它
 * `AppSettings.kt:1437` 的注释记录过一次真实事故：一个开关的 KEY / 读写属性 / `BACKUP_FIELDS`
 * 三处必须同步，结果只漏了 `BACKUP_FIELDS` 一行 —— 表现是"导入备份改不动它"，**不报错**。
 * 之后又出现了同一类的漏登记（DLNA 的开关与目录、媒体库扫描范围）。
 *
 * 所以这里把口径钉死：**每个 `KEY_*` 常量要么进 `BACKUP_FIELDS`，要么进下面这份
 * "明确不进备份"的名单（每条都要有理由）**。新增 key 忘了表态 → 测试直接红，
 * 而不是等用户换设备时才发现设置丢了。
 *
 * ## 为什么读源码而不是反射
 * `KEY_*` 是 `private const`、`BACKUP_FIELDS` 是 `internal` —— 反射拿不到私有的那些。
 * 与仓里既有的源码守卫（`NavInsetHandoffGuardTest` / `PairingBlacklistWiringGuardTest`）
 * 同一套做法：定位源文件、剥注释、断言可执行代码。
 */
class AppSettingsBackupCompletenessTest {

    private val appSettingsPath = "core/common/src/main/java/com/ufi_axis_core/util/AppSettings.kt"

    private fun source(relative: String): String {
        var dir: File? = File(".").absoluteFile.normalize()
        while (dir != null) {
            for (candidate in listOf(File(dir, relative), File(dir, "core/common/$relative"))) {
                if (candidate.isFile) return candidate.readText()
            }
            dir = dir.parentFile
        }
        throw AssertionError("定位不到源文件 '$relative'（起点：${File(".").absolutePath}）")
    }

    private fun code(text: String): String =
        text.replace(Regex("""/\*[\s\S]*?\*/"""), "").replace(Regex("""//[^\n]*"""), "")

    /**
     * 明确**不进**备份的 key → 理由。
     *
     * 分三类，判据见 `BACKUP_FIELDS` 的 KDoc：身份与凭据（备份包不该能搬走设备所有权）、
     * 运行态（描述"这台设备进行到哪了"）、一次性迁移标记。
     */
    private val notBackedUp: Map<String, String> = mapOf(
        // ── 身份与凭据 ──
        "KEY_DEVICE_ID" to "设备身份",
        "KEY_DEVICE_PASSWORD_HASH" to "配对密码哈希",
        "KEY_DEVICE_PASSWORD_SALT" to "配对密码盐",
        "KEY_DEVICE_PASSWORD_SET" to "配对密码是否已设（身份状态）",
        "KEY_DEVICE_PASSWORD_VERSION" to "密码算法版本",
        "KEY_PAIRED" to "配对状态",
        "KEY_PAIRED_APP_FP" to "已配对指纹",
        "KEY_PAIRED_AT" to "配对时刻",
        "KEY_PAIRING_CODE" to "一次性配对码",
        "KEY_ARIA2_RPC_SECRET" to "本机运行凭据（还原过去会让正在跑的 aria2 立刻鉴权失败）",
        "KEY_POETRY_TOKEN" to "上游身份，换设备应重新签发",
        "KEY_TUNNEL_CF_TOKEN" to "旧版全局 token 槽（已废弃，见 BACKUP_FIELDS 注释）",
        "KEY_LAST_SIG_FINGERPRINT" to "签名缓存（运行态）",
        // ── 运行态 ──
        "KEY_ALERT_LEVEL_STATE" to "告警级别状态机",
        "KEY_GEO_DETECTED_AT" to "出网地区检测时刻（跟着备份走会让新设备迟迟不重测）",
        "KEY_LAST_CRASH_AT" to "崩溃标记（还原等于伪造一次别的设备的崩溃）",
        "KEY_LAST_CRASH_FILE" to "崩溃标记",
        "KEY_LAST_CRASH_SUMMARY" to "崩溃标记",
        "KEY_LAST_SEEN_BUNDLED_WEB_VERSION" to "内置 web 版本追踪",
        "KEY_LAST_UPDATE_RESULT" to "上次更新结果",
        "KEY_MON_BOOT_GRACE_MS" to "开机宽限期标记",
        "KEY_PENDING_LOCAL_INSTALL_LUT" to "待安装包时间戳",
        "KEY_PENDING_UPDATE" to "待更新标记",
        "KEY_SMS_HIGH_WATER_MARK" to "短信水位",
        "KEY_TRAFFIC_AUTO_OFF_STATE" to "自动关网状态机",
        // 2026-10-19：安全模式的运行态标记。跟着备份走会造成两种事故：
        // 把别处的"失败计数"带过来 → 新设备一开机就进安全模式（强制默认端口、关后台）；
        // 或把"已进安全模式"还原到一台本来正常的设备上。两种都属于"伪造运行态"。
        "KEY_STARTUP_FAILURES" to "启动失败计数（运行态，跨重启累计，不应随备份迁移）",
        "KEY_SAFE_MODE" to "安全模式标记（运行态，一次性；还原等于伪造一次别的设备的启动失败）",
        // 2026-10-19：已读水位是"这台设备看到哪了"的进度，不是配置。跟着备份走会让
        // 新设备继承旧设备的已读进度（未读内容被悄悄标成已读），属于伪造运行态。
        "KEY_SMS_CODE_SEEN_MSG_ID" to "验证码通知已读水位（运行态，跨设备同步走 /api/config 而不是备份）",
        "KEY_SMS_BLOCKED_SEEN_MSG_ID" to "已拦截角标已读水位（运行态，同上）",
        "KEY_TRAFFIC_DAILY_BASELINE" to "流量日基线",
        "KEY_TRAFFIC_HOURLY_SAMPLER" to "整点采样标记",
        // ── 一次性迁移标记 ──
        "KEY_SMS_INITIALIZED" to "一次性迁移标记（还原会让迁移被跳过）",
        // ── 前缀常量（不是键本身）──
        "KEY_MEDIA_SCAN_DIRS" to "前缀常量：真实键是 media_scan_dirs_{video,audio,image}，那三个已显式进 BACKUP_FIELDS"
    )

    @Test
    fun `每个 KEY 常量要么进备份白名单要么在排除名单里`() {
        val src = code(source(appSettingsPath))
        val allKeys = Regex("""const val (KEY_[A-Z0-9_]+)""")
            .findAll(src).map { it.groupValues[1] }.toSortedSet()
        val backedUp = Regex("""BackupField\((KEY_[A-Z0-9_]+)""")
            .findAll(src).map { it.groupValues[1] }.toSortedSet()

        assertTrue("前置：应能解析出一批 KEY_ 常量（解析失败说明正则/路径失效）", allKeys.size > 30)
        assertTrue("前置：应能解析出 BACKUP_FIELDS 的引用", backedUp.isNotEmpty())

        val unaccounted = allKeys - backedUp - notBackedUp.keys
        assertTrue(
            "以下 key 既不在 BACKUP_FIELDS、也没在排除名单里 —— " +
                "新增 key 必须显式表态：进备份，或者写进 notBackedUp 并给出理由。" +
                "漏登记的表现是「导入备份改不动它」，而且不报错。实际漏了：$unaccounted",
            unaccounted.isEmpty()
        )
    }

    @Test
    fun `排除名单里不能有已不存在的 key`() {
        val src = code(source(appSettingsPath))
        val allKeys = Regex("""const val (KEY_[A-Z0-9_]+)""")
            .findAll(src).map { it.groupValues[1] }.toSet()

        val stale = notBackedUp.keys - allKeys
        assertTrue(
            "排除名单里有已经不存在的 key：$stale —— 留着它会让「新 key 忘了表态」更容易被漏过" +
                "（名单越长越像「反正都在里面」）",
            stale.isEmpty()
        )
    }

    @Test
    fun `DLNA 备份键名必须与 DlnaService 的常量一致`() {
        // 为什么用字面量 + 守卫，而不是直接引用常量：`DlnaService` 在 `:core`，
        // 本文件所在的 `:core:common` 不能反向依赖它（依赖方向 api/core → common）。
        // 所以键名在两边各出现一次，由这条测试保证它们不会漂移。
        val dlna = code(source("core/src/main/java/com/ufi_axis_core/service/DlnaService.kt"))
        assertTrue(
            "DlnaService 应声明 KEY_ENABLED = \"dlna_enabled\"（与备份白名单里的字面量一致）",
            Regex("""KEY_ENABLED\s*=\s*"dlna_enabled"""").containsMatchIn(dlna)
        )
        assertTrue(
            "DlnaService 应声明 KEY_DIRS = \"dlna_dirs_json\"（与备份白名单里的字面量一致）",
            Regex("""KEY_DIRS\s*=\s*"dlna_dirs_json"""").containsMatchIn(dlna)
        )
        // 而且 DLNA 用的是 getRawString/setRawString（存 "true"/"false" 文本）——
        // 备份字段必须是 STRING，写成 BOOL 会在恢复时把 String 当 Boolean 读。
        val src = code(source(appSettingsPath))
        assertTrue(
            "dlna_enabled 必须按 STRING 备份（DlnaService 存的是文本 \"true\"/\"false\"）",
            Regex("""BackupField\("dlna_enabled",\s*BackupValueType\.STRING""").containsMatchIn(src)
        )
    }

    @Test
    fun `本轮补漏的四处必须在备份白名单里`() {
        val src = code(source(appSettingsPath))
        val backedUp = Regex("""BackupField\(([^)]*)\)""")
            .findAll(src).map { it.groupValues[1] }.joinToString("\n")

        listOf(
            "dlna_enabled" to "DLNA 开关",
            "dlna_dirs_json" to "DLNA 共享目录",
            // 扫描范围这三条在主源码里写成模板（`"${KEY_MEDIA_SCAN_DIRS}_video"`），
            // 所以这里也按模板形式断言 —— 保持"前缀只有一个来源"，而不是把键名抄两遍。
            "\${KEY_MEDIA_SCAN_DIRS}_video" to "媒体库扫描范围（视频）",
            "\${KEY_MEDIA_SCAN_DIRS}_audio" to "媒体库扫描范围（音频）",
            "\${KEY_MEDIA_SCAN_DIRS}_image" to "媒体库扫描范围（图片）"
        ).forEach { (key, label) ->
            assertTrue("$label（$key）必须进备份白名单", backedUp.contains(key))
        }
    }
}

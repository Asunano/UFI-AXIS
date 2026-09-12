package com.ufi_axis.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * [UfiLogPaths] 的纯函数部分：路径拼接、导出文件名、展示路径裁剪、两处一次性搬迁的判据。
 *
 * 只测不碰文件系统的那一半 —— 目录准备（`ensureLogDir` / `exportDir`）依赖
 * `Environment.getExternalStoragePublicDirectory`，属于仪器测试的范围。
 * 这里钉死的是"路径长什么样"和"搬迁会挑中哪些名字"，那两件事才是这次改造漂移的根源。
 */
class UfiLogPathsTest {

    // ── 路径拼接 ────────────────────────────────────────────────────────────────

    @Test
    fun `app log root is under brand log app`() {
        assertEquals("UFI-AXIS/log/app", UfiLogPaths.appLogRelative())
    }

    @Test
    fun `crash dump lives under app crash date, not brand root`() {
        // 改造前是 UFI-AXIS/2026-09-11/ —— 直接躺在品牌根，正是这次要修的形态
        assertEquals(
            "UFI-AXIS/log/app/crash/2026-09-11",
            UfiLogPaths.appCrashRelative("2026-09-11")
        )
        assertEquals("UFI-AXIS/log/app/crash", UfiLogPaths.appCrashRootRelative())
    }

    @Test
    fun `api error lives under app api-error date, not log root`() {
        assertEquals(
            "UFI-AXIS/log/app/api-error/2026-09-11",
            UfiLogPaths.apiErrorRelative("2026-09-11")
        )
    }

    @Test
    fun `export dir splits by source and optional sub`() {
        assertEquals(
            "UFI-AXIS/log/export/app/2026-09-11",
            UfiLogPaths.exportRelative(UfiLogPaths.APP_DIR, "2026-09-11")
        )
        assertEquals(
            "UFI-AXIS/log/export/core/2026-09-11",
            UfiLogPaths.exportRelative(UfiLogPaths.CORE_DIR, "2026-09-11")
        )
        assertEquals(
            "UFI-AXIS/log/export/app/crash/2026-09-11",
            UfiLogPaths.exportRelative(UfiLogPaths.APP_DIR, "2026-09-11", UfiLogPaths.CRASH_DIR)
        )
    }

    @Test
    fun `monitor export stays outside the log tree`() {
        // 监控 CSV 是业务数据，不能进 log/：否则会被日志的保留天数/体积预算清掉
        val relative = UfiLogPaths.monitorExportRelative("2026-09-11")
        assertEquals("UFI-AXIS/export/monitor/2026-09-11", relative)
        assertFalse(relative.contains("/log/"))
        // 旧品牌名 UFI 不能再出现在任何导出路径里
        assertTrue(relative.startsWith("UFI-AXIS/"))
    }

    @Test
    fun `today formats as date dir name`() {
        val cal = Calendar.getInstance().apply { set(2026, Calendar.SEPTEMBER, 11, 12, 15, 30) }
        val date = UfiLogPaths.today(cal.time)
        assertEquals("2026-09-11", date)
        assertTrue(UfiLogPaths.isDateDirName(date))
    }

    // ── 导出文件名与展示路径 ────────────────────────────────────────────────────

    @Test
    fun `export file name carries kind and level`() {
        assertEquals("net_error_121530.txt", UfiLogPaths.exportFileName("net", "error", "121530"))
        assertEquals(
            "all_all_121530.txt",
            UfiLogPaths.exportFileName(UfiLogPaths.UNFILTERED, UfiLogPaths.UNFILTERED, "121530")
        )
    }

    @Test
    fun `display path starts at brand dir`() {
        assertEquals(
            "UFI-AXIS/log/export/app/2026-09-11/net_error_121530.txt",
            UfiLogPaths.displayPath(
                "/storage/emulated/0/Download/UFI-AXIS/log/export/app/2026-09-11/net_error_121530.txt"
            )
        )
    }

    @Test
    fun `display path keeps absolute path when outside brand dir`() {
        // 私有回退目录里没有品牌目录段，此时报绝对路径至少还能 adb pull
        val fallback = "/data/user/0/com.ufi_axis/files/logs/app/2026-09-11/net.log"
        assertEquals(fallback, UfiLogPaths.displayPath(fallback))
    }

    // ── 一次性搬迁的判据 ────────────────────────────────────────────────────────

    @Test
    fun `date dir predicate skips core and other component dirs`() {
        assertTrue(UfiLogPaths.isDateDirName("2026-09-11"))
        // 崩溃搬迁遍历品牌根的子目录，`log` 必须落选 —— 否则会一路走进 core 的地盘
        assertFalse(UfiLogPaths.isDateDirName("log"))
        assertFalse(UfiLogPaths.isDateDirName("core"))
        assertFalse(UfiLogPaths.isDateDirName("watchdog"))
        assertFalse(UfiLogPaths.isDateDirName("keepalive"))
        assertFalse(UfiLogPaths.isDateDirName("_archive"))
        assertFalse(UfiLogPaths.isDateDirName("export"))
        // 位数不全的不算（宁可漏搬也不能误搬）
        assertFalse(UfiLogPaths.isDateDirName("2026-9-11"))
        assertFalse(UfiLogPaths.isDateDirName("2026-09-11_old"))
    }

    @Test
    fun `crash file predicate matches both old and new naming`() {
        assertTrue(UfiLogPaths.isCrashFileName("crash_12-15-30.log"))
        assertTrue(UfiLogPaths.isCrashFileName("crash_2026-09-11_12-15-30.log"))
        assertFalse(UfiLogPaths.isCrashFileName("runtime.log"))
        assertFalse(UfiLogPaths.isCrashFileName("crash_12-15-30.txt"))
    }

    @Test
    fun `legacy api error predicate only matches its own files`() {
        assertTrue(UfiLogPaths.isLegacyApiErrorFileName("api_error.log"))
        assertTrue(UfiLogPaths.isLegacyApiErrorFileName("api_error_2026-09-11_12-15-30_HTTP_REQUEST.log"))
        // 与它同在 log/ 下的其它组件不能被搬走（这几个是目录名，判据也必须拒绝）
        assertFalse(UfiLogPaths.isLegacyApiErrorFileName("core"))
        assertFalse(UfiLogPaths.isLegacyApiErrorFileName("app"))
        assertFalse(UfiLogPaths.isLegacyApiErrorFileName("_archive"))
        assertFalse(UfiLogPaths.isLegacyApiErrorFileName("watchdog"))
        assertFalse(UfiLogPaths.isLegacyApiErrorFileName("api_error.txt"))
    }
}

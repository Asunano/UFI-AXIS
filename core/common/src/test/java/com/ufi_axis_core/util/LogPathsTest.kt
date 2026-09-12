package com.ufi_axis_core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [LogPaths] 的拼接结果 —— 用**字面量**锁死。
 *
 * 为什么断言里写死字符串而不是拿常量互相拼：这些路径同时被两个 shell 脚本
 * （`ufi_update.sh` / `ufi_keepalive.sh`）和 app 侧持有，Kotlin 这边引用不到它们。
 * 用字面量断言，任何人改动根目录或子目录名都会让本测试**红**，红了就得去把那两个脚本
 * 一起改 —— 这是把「必须同步修改」从注释变成机器可判定的唯一办法。
 *
 * 落盘行为不在这里测：[AppLogger] / [DownloadLog] 要真的 `File` 与外部存储，
 * 本模块单测源集只有 junit + Robolectric，纯判据抽出来测的分法同
 * `:core:controller` 的 `WebhookPrefsKeysTest`。
 */
class LogPathsTest {

    @Test
    fun `应用根目录与日志根目录`() {
        assertEquals("/sdcard/Download/UFI-AXIS", LogPaths.appRoot())
        assertEquals("/sdcard/Download/UFI-AXIS/log", LogPaths.logRoot())
        assertEquals(
            "/storage/emulated/0/Download/UFI-AXIS",
            LogPaths.appRoot(LogPaths.EMULATED_BASE)
        )
        assertEquals(
            "/storage/emulated/0/Download/UFI-AXIS/log",
            LogPaths.logRoot(LogPaths.EMULATED_BASE)
        )
    }

    /** 五个组件目录，与 `ufi_update.sh:20` 那句成文约定（按组件分子目录）逐字对应。 */
    @Test
    fun `各组件目录`() {
        assertEquals(
            "/sdcard/Download/UFI-AXIS/log/core",
            LogPaths.dir(LogPaths.Component.CORE)
        )
        assertEquals(
            "/sdcard/Download/UFI-AXIS/log/install",
            LogPaths.dir(LogPaths.Component.INSTALL)
        )
        assertEquals(
            "/sdcard/Download/UFI-AXIS/log/watchdog",
            LogPaths.dir(LogPaths.Component.WATCHDOG)
        )
        assertEquals(
            "/sdcard/Download/UFI-AXIS/log/keepalive",
            LogPaths.dir(LogPaths.Component.KEEPALIVE)
        )
        assertEquals(
            "/sdcard/Download/UFI-AXIS/log/_archive",
            LogPaths.dir(LogPaths.Component.ARCHIVE)
        )
    }

    @Test
    fun `组件目录下的文件`() {
        assertEquals(
            "/sdcard/Download/UFI-AXIS/log/watchdog/watchdog.log",
            LogPaths.file(LogPaths.Component.WATCHDOG, "watchdog.log")
        )
        assertEquals(
            "/sdcard/Download/UFI-AXIS/log/keepalive/keepalive.log",
            LogPaths.file(LogPaths.Component.KEEPALIVE, "keepalive.log")
        )
        assertEquals(
            "/sdcard/Download/UFI-AXIS/log/core/update.log",
            LogPaths.file(LogPaths.Component.CORE, "update.log")
        )
        assertEquals(
            "/sdcard/Download/UFI-AXIS/log/install/install.log",
            LogPaths.file(LogPaths.Component.INSTALL, "install.log")
        )
    }

    /**
     * 探测顺序：**emulated 在前、sdcard 在后**。
     *
     * 调用方（`UpdateManager.coreLogDir` / `InstallService.installLogPath`）是
     * 「第一个能建出来的就用」，顺序反了会改变落盘目录 —— 而两者在文件管理器里
     * 看起来是同一个地方，出问题时极难察觉。
     */
    @Test
    fun `候选路径 emulated 优先 sdcard 兜底`() {
        assertEquals(
            listOf(
                "/storage/emulated/0/Download/UFI-AXIS/log/core",
                "/sdcard/Download/UFI-AXIS/log/core"
            ),
            LogPaths.dirCandidates(LogPaths.Component.CORE)
        )
        assertEquals(
            listOf(
                "/storage/emulated/0/Download/UFI-AXIS/log/install",
                "/sdcard/Download/UFI-AXIS/log/install"
            ),
            LogPaths.dirCandidates(LogPaths.Component.INSTALL)
        )
        assertEquals(
            listOf(
                "/storage/emulated/0/Download/UFI-AXIS/log/watchdog/watchdog.log",
                "/sdcard/Download/UFI-AXIS/log/watchdog/watchdog.log"
            ),
            LogPaths.fileCandidates(LogPaths.Component.WATCHDOG, "watchdog.log")
        )
    }

    /** 拼接不许出现 `//` 或结尾斜杠：多一个斜杠 `File` 能容忍，写进 shell 的字符串比对就不一定了。 */
    @Test
    fun `拼出来的路径没有多余斜杠`() {
        val all = LogPaths.Component.entries.flatMap {
            LogPaths.dirCandidates(it) + LogPaths.fileCandidates(it, "x.log")
        } + listOf(LogPaths.appRoot(), LogPaths.logRoot())
        for (p in all) {
            assertTrue("$p 含有 //", "//" !in p)
            assertTrue("$p 以斜杠结尾", !p.endsWith("/"))
            assertTrue("$p 不是绝对路径", p.startsWith("/"))
        }
    }

    /** 组件子目录名两两不同：撞名等于两个组件的日志混在一个文件夹里。 */
    @Test
    fun `组件子目录名互不相同`() {
        val names = LogPaths.Component.entries.map { it.dirName }
        assertEquals("有重复的子目录名：$names", names.size, names.toSet().size)
    }
}

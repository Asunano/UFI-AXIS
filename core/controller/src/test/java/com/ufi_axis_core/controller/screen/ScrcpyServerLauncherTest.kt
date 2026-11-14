package com.ufi_axis_core.controller.screen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ScrcpyServerLauncher] 的纯函数部分（2026-10-31）。
 *
 * 这两条命令**无法在 CI 里真跑**（要设备、要 app_process），但写错的代价是
 * "画面永远不出来"，而且现象和网络问题、解码问题长得一样。所以只能靠断言锁死形状。
 */
class ScrcpyServerLauncherTest {

    // ── buildStartCommand ──

    @Test
    fun `参数是 server 侧格式——没有客户端 CLI 的短横线`() {
        val cmd = ScrcpyServerLauncher.buildStartCommand()

        // v0.3 的 bug 就长这样：--max-size=1280 传给 server，解析不了
        assertTrue(
            "server 参数不得带 -- 前缀：$cmd",
            ScrcpyServerLauncher.SERVER_ARGS.none { it.startsWith("--") },
        )
        // 每一项都必须是 key=value
        ScrcpyServerLauncher.SERVER_ARGS.forEach {
            assertTrue("参数缺 '='：$it", it.contains('='))
        }
    }

    @Test
    fun `版本号是 argv 的第一个参数且与 jar 对齐`() {
        val cmd = ScrcpyServerLauncher.buildStartCommand()

        assertTrue(
            "版本号必须紧跟在 Server 之后：$cmd",
            cmd.contains("com.genymobile.scrcpy.Server ${ScrcpyServerLauncher.SERVER_VERSION} "),
        )
        assertEquals("3.3.3", ScrcpyServerLauncher.SERVER_VERSION)
    }

    @Test
    fun `码率写字节数而不是 2M`() {
        assertTrue(ScrcpyServerLauncher.SERVER_ARGS.contains("video_bit_rate=2000000"))
        assertTrue(
            "不得出现 2M 这类人类写法",
            ScrcpyServerLauncher.SERVER_ARGS.none { it.endsWith("M") },
        )
    }

    @Test
    fun `布尔取反用 =false 而不是 --no-xxx`() {
        assertTrue(ScrcpyServerLauncher.SERVER_ARGS.contains("clipboard_autosync=false"))
        assertTrue(ScrcpyServerLauncher.SERVER_ARGS.contains("audio=false"))
        // M0 实测 display 初始 state OFF，必须阻止休眠，否则中途黑屏
        assertTrue(ScrcpyServerLauncher.SERVER_ARGS.contains("stay_awake=true"))
    }

    @Test
    fun `启动是 detached 的——nohup setsid 加取 PID`() {
        val cmd = ScrcpyServerLauncher.buildStartCommand()

        assertTrue("必须脱离 adb shell 会话：$cmd", cmd.contains("nohup setsid sh -c"))
        assertTrue("必须取子进程 PID 而不是 shell 的：$cmd", cmd.contains("P=\$!"))
        assertTrue("必须把 PID 落盘以便 core 重启后清理：$cmd", cmd.contains("scrcpy.pid"))
        assertTrue("必须有可解析的 PID 标记：$cmd", cmd.contains("echo __PID__\$P"))
        // 反向断言：绝不能是 v0.3 那种"exec 之后永不返回"的写法
        assertTrue("不得使用 echo \$\$; exec 形式：$cmd", !cmd.contains("echo \$\$"))
    }

    @Test
    fun `启动前先建目录并把输出丢弃`() {
        val cmd = ScrcpyServerLauncher.buildStartCommand()
        assertTrue(cmd.contains("mkdir -p ${ScrcpyServerLauncher.REMOTE_DIR}"))
        // 重定向到文件而不是 /dev/null：起不来时需要 server 自己的说法
        assertTrue("必须重定向，否则 adb shell 会等管道：$cmd", cmd.contains("scrcpy.log 2>&1"))
        assertTrue("日志要落在同目录：$cmd", cmd.contains(">/data/local/tmp/ufi/scrcpy.log"))
    }

    @Test
    fun `判断 server 存活用 argv 里的类名而不是 jar 名`() {
        // 2026-10-31 真机 bug：app_process 的 argv 是
        //   app_process / com.genymobile.scrcpy.Server 3.3.3 ...
        // jar 路径只在 CLASSPATH 环境变量里（/proc/<pid>/environ）。
        // 用 "scrcpy-server.jar" 判 cmdline 永远为假 → 每次判"没起来" → 自己拆会话。
        val realCmdline = "app_process / com.genymobile.scrcpy.Server 3.3.3 max_size=1280"
        assertTrue(realCmdline.contains("com.genymobile.scrcpy.Server"))
        assertTrue(
            "jar 名不该出现在 argv 里（这条断言防止有人又改回按 jar 名判断）",
            !realCmdline.contains("scrcpy-server.jar"),
        )
    }

    // ── parsePid ──

    @Test
    fun `从干净输出里取 PID`() {
        assertEquals(12345, ScrcpyServerLauncher.parsePid("__PID__12345"))
    }

    @Test
    fun `混有退出码标记行时仍能取到 PID`() {
        // AdbShellExecutor 会在命令末尾追加 `; echo ___EXIT_n___$?`
        val stdout = "__PID__23456\n___EXIT_12345___0"
        assertEquals(23456, ScrcpyServerLauncher.parsePid(stdout))
    }

    @Test
    fun `没有 PID 行时返回 null 而不是猜`() {
        assertNull(ScrcpyServerLauncher.parsePid(""))
        assertNull(ScrcpyServerLauncher.parsePid("sh: nohup: not found"))
        // 只认自己的前缀：别的数字（比如报错里的行号）不算
        assertNull(ScrcpyServerLauncher.parsePid("line 42: syntax error"))
    }

    @Test
    fun `PID 必须是纯数字`() {
        assertNull(ScrcpyServerLauncher.parsePid("__PID__abc"))
        assertNull(ScrcpyServerLauncher.parsePid("__PID__"))
    }
}

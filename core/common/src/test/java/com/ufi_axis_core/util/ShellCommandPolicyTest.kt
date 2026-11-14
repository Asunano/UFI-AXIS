package com.ufi_axis_core.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 危险 Shell 命令判据的单元测试（2026-10-30 越权审查 §2.2）。
 *
 * 这份判据原先活在 `ShellRoutes` 的伴生对象里、只有 HTTP 入口用它。搬到
 * [ShellCommandPolicy] 之后有两个调用方（`/api/shell/exec` 与定时任务的
 * `custom_shell`），所以这些用例守的是**两个入口共用同一份事实**：
 * 任何一条模式被删/被改都会在这里红，而不是等到「定时任务能重启设备」被发现。
 */
class ShellCommandPolicyTest {

    @Test
    fun `拦截内核模块操作`() {
        assertTrue(ShellCommandPolicy.isDangerous("insmod /sdcard/x.ko"))
        assertTrue(ShellCommandPolicy.isDangerous("rmmod sprd_wcn"))
        assertTrue(ShellCommandPolicy.isDangerous("modprobe foo"))
    }

    @Test
    fun `拦截重启与关机`() {
        assertTrue(ShellCommandPolicy.isDangerous("reboot"))
        assertTrue(ShellCommandPolicy.isDangerous("shutdown -h now"))
        assertTrue(ShellCommandPolicy.isDangerous("poweroff"))
        // 包装形式同样要拦：这是黑名单用「子串正则」而不是整串匹配的原因。
        assertTrue(ShellCommandPolicy.isDangerous("svc power reboot"))
        assertTrue(ShellCommandPolicy.isDangerous("sh -c 'reboot'"))
    }

    @Test
    fun `拦截杀关键系统进程`() {
        assertTrue(ShellCommandPolicy.isDangerous("kill -9 1234 rild"))
        assertTrue(ShellCommandPolicy.isDangerous("kill -9 99 netd"))
        // 不带 -9 的普通 kill 不在黑名单里：它拦的是「直接打死基带/网络守护」这一类。
        assertFalse(ShellCommandPolicy.isDangerous("kill 1234"))
    }

    @Test
    fun `拦截 sprd HIDL 服务调用`() {
        assertTrue(ShellCommandPolicy.isDangerous("service call sprd.radio 1"))
    }

    @Test
    fun `放行常规诊断命令`() {
        assertFalse(ShellCommandPolicy.isDangerous("ls -l /sdcard/Download"))
        assertFalse(ShellCommandPolicy.isDangerous("id"))
        assertFalse(ShellCommandPolicy.isDangerous("getprop ro.product.model"))
        assertFalse(ShellCommandPolicy.isDangerous("logcat -d -t 200"))
        assertFalse(ShellCommandPolicy.isDangerous("df -h /data"))
    }

    /**
     * 刻意记录的**误伤**：`echo reboot` 也会被拦。
     *
     * 这不是 bug，是取舍 —— 黑名单用子串匹配才能盖住 `svc power reboot` 这类包装，
     * 代价是「命令里出现 reboot 这个词」一律拒绝。写在这里是为了让下一个想改成
     * 「精确匹配」的人先看到这条已知代价。
     */
    @Test
    fun `含 reboot 字样的普通命令会被误伤这是已知取舍`() {
        assertTrue(ShellCommandPolicy.isDangerous("echo reboot"))
    }
}

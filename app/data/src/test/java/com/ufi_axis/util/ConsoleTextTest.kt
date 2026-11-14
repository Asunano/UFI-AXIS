package com.ufi_axis.util

import org.junit.Assert.assertEquals
import org.junit.Test

/** `stripShellPrompt`：复制 Shell 消息时不带 `$` / `#` 提示符（2026-10-31）。 */
class ConsoleTextTest {

    @Test
    fun `去掉用户提示符`() {
        assertEquals("ls -la", stripShellPrompt("$ ls -la"))
    }

    @Test
    fun `去掉 root 提示符`() {
        assertEquals("id", stripShellPrompt("# id"))
    }

    @Test
    fun `命令自身以井号开头时只去掉外层提示符`() {
        assertEquals("# comment", stripShellPrompt("$ # comment"))
    }

    @Test
    fun `没有提示符时原样返回`() {
        assertEquals("echo \$HOME", stripShellPrompt("echo \$HOME"))
        assertEquals("AT+CSQ", stripShellPrompt("AT+CSQ"))
        assertEquals("", stripShellPrompt(""))
    }

    @Test
    fun `只处理开头`() {
        assertEquals("printf '$ x'", stripShellPrompt("$ printf '$ x'"))
    }
}

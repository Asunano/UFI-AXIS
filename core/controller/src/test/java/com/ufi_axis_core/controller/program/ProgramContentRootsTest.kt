package com.ufi_axis_core.controller.program

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `content_root` 白名单。
 *
 * 这条是安全问题，不是参数校验：内容目录会被创建、卸载时还会被递归删除。
 * 放行一个任意绝对路径，等于给已认证调用方一个"任意目录建/删"的原语。
 */
class ProgramContentRootsTest {

    private val allowed = listOf(
        "/sdcard/Download/UFI-AXIS/programs/content",
        "/storage/064E93164E92FD9D/UFI-AXIS/content"
    )

    @Test
    fun `空值表示用默认位置`() {
        assertNull(ProgramContentRoots.issue(null, allowed))
        assertNull(ProgramContentRoots.issue("", allowed))
        assertNull(ProgramContentRoots.issue("   ", allowed))
    }

    @Test
    fun `白名单里的路径放行`() {
        assertNull(ProgramContentRoots.issue(allowed[0], allowed))
        assertNull(ProgramContentRoots.issue(allowed[1], allowed))
    }

    @Test
    fun `相对路径与根目录被拒绝`() {
        assertNotNull(ProgramContentRoots.issue("UFI-AXIS/content", allowed))
        assertNotNull(ProgramContentRoots.issue("/", allowed))
    }

    @Test
    fun `不在卷列表里的路径被拒绝（哪怕它看起来合理）`() {
        // 这一条正是"任意目录写/删"的入口：/data/data/<pkg> 是私有目录，不该出现在卷列表里
        assertNotNull(ProgramContentRoots.issue("/data/data/com.ufi_axis_core", allowed))
        assertNotNull(ProgramContentRoots.issue("/sdcard/Download/UFI-AXIS/programs/content/../..", allowed))
    }
}

package com.ufi_axis_core.controller.program

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [ProgramMemoryBudget]：多实例的内存总量口径。
 *
 * 这条测试守的是"预算必须留在机器能承受的区间里"：
 * 比例写错（0.55 -> 0.95）不会崩，只会让三个程序把 core 一起拖死，
 * 而现场表现是"服务自己没了"，跟程序商店看起来毫无关系。
 */
class ProgramMemoryBudgetTest {

    @Test
    fun `拿不到总内存时退回保守预算`() {
        assertEquals(512L, ProgramMemoryBudget.budgetMb(null))
        assertEquals(512L, ProgramMemoryBudget.budgetMb(0))
    }

    @Test
    fun `目标机 1467MB 的预算约 806MB`() {
        // 1467 * 0.55 = 806.85 -> 806（向下取整，宁可少放一个）
        assertEquals(806L, ProgramMemoryBudget.budgetMb(1467))
    }

    @Test
    fun `预算被夹在上下限之间`() {
        assertEquals(ProgramMemoryBudget.MIN_BUDGET_MB, ProgramMemoryBudget.budgetMb(100))
        assertEquals(ProgramMemoryBudget.MAX_BUDGET_MB, ProgramMemoryBudget.budgetMb(1_000_000))
    }

    @Test
    fun `淘汰 RSS 最大的那个`() {
        assertEquals("cloudreve", ProgramMemoryBudget.pickVictim(mapOf("dufs" to 40, "cloudreve" to 500, "syncthing" to 120)))
        assertNull("空表没有可淘汰对象", ProgramMemoryBudget.pickVictim(emptyMap()))
        assertNull("全是 0 也不该挑一个出来", ProgramMemoryBudget.pickVictim(mapOf("a" to 0L)))
    }
}

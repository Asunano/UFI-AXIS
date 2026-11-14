package com.ufi_axis.data.bill

import org.junit.Assert.assertEquals
import org.junit.Test

/** 账单模型的派生量与演示数据一致性（2026-10-31）。 */
class BillModelsTest {

    @Test
    fun `剩余量取数据源给的值而不是现算`() {
        val flow = BillFlow(
            generalUsedGb = 0.46,
            generalTotalGb = 2.20,
            generalRemainingGb = 1.73,
            directedUsedGb = 0.95,
            directedTotalGb = 110.00,
            directedRemainingGb = 109.05
        )
        // 2.20 - 0.46 = 1.74 是舍入假象，不能覆盖营业厅给的 1.73
        assertEquals(1.73, flow.generalRemainingGb, 0.001)
        assertEquals(109.05, flow.directedRemainingGb, 0.001)
        assertEquals(1.41, flow.usedGb, 0.001)
        assertEquals(112.20, flow.totalGb, 0.001)
        assertEquals(110.78, flow.remainingGb, 0.001)
    }

    @Test
    fun `演示数据里流量包合计与流量总量一致`() {
        val bill = SampleBillRepository.SAMPLE_BILL
        assertEquals(bill.flow.totalGb, bill.packages.sumOf { it.totalGb }, 0.001)
        assertEquals(bill.flow.remainingGb, bill.packages.sumOf { it.remainingGb }, 0.001)
    }
}

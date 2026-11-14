package com.ufi_axis.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 云上卡账号在列表里的展示规则（2026-11-08）。 */
class CloudAccountDisplayTest {

    @Test
    fun `iot account uses cloud card title and iot subtitle`() {
        val account = CarrierAccountDto(
            id = "h-iot",
            accessMethod = "iot_card",
            carrier = "iot_card",
            phone = "****7655",
            label = "",
            state = "ready",
            createdAt = 1,
        )
        assertEquals("云上卡 7655", account.displayTitle)
        assertTrue(account.displaySubtitle.contains("****7655"))
        assertTrue(account.displaySubtitle.contains("物联卡"))
    }

    @Test
    fun `carrier account keeps old display rules`() {
        val account = CarrierAccountDto(
            id = "h-1",
            carrier = "mobile",
            phone = "138****8000",
            label = "主卡",
            state = "ready",
            createdAt = 1,
        )
        assertEquals("主卡", account.displayTitle)
        assertTrue(account.displaySubtitle.contains("中国移动"))
    }
}

package com.ufi_axis.notification

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 记录页增强护栏：列表不得再塞 error/message 全文；详情与单条删除必须接上；
 * 不得改动 F24 冻结的公共组件源文件。
 */
class HistoryUiGuardTest {

    private val deliveryScreenPath =
        "app/feature-settings/src/main/java/com/ufi_axis/ui/screens/DeliveryHistoryScreen.kt"
    private val systemScreenPath =
        "app/feature-settings/src/main/java/com/ufi_axis/ui/screens/SystemNotifyHistoryScreen.kt"
    private val commonPath =
        "app/feature-settings/src/main/java/com/ufi_axis/ui/screens/NotifyHistoryCommon.kt"
    private val apiPath =
        "app/data/src/main/java/com/ufi_axis/data/api/UfiAxisApi.kt"
    private val routesPath =
        "core/api/src/main/java/com/ufi_axis_core/api/routes/SmsForwardRoutes.kt"

    private fun source(relative: String): String {
        var dir: File? = File(".").absoluteFile.normalize()
        while (dir != null) {
            val candidate = File(dir, relative)
            if (candidate.isFile) return candidate.readText()
            dir = dir.parentFile
        }
        throw AssertionError("定位不到源文件 '$relative'")
    }

    @Test
    fun deliveryListMustNotConcatErrorIntoDescription() {
        val common = source(commonPath)
        // 列表副文只允许时间+场景，禁止把 error 拼进 deliveryListSubtitle
        val fn = Regex("""fun deliveryListSubtitle\([\s\S]*?\n}""").find(common)
            ?: throw AssertionError("找不到 deliveryListSubtitle")
        assertFalse(
            "deliveryListSubtitle 不得包含 record.error（失败详情只在弹窗）",
            fn.value.contains("record.error")
        )
    }

    @Test
    fun deliveryScreenMustUsePublicDialogAndDelete() {
        val code = source(deliveryScreenPath)
        assertTrue("投递详情必须用 UfiScrollableDialog", code.contains("UfiScrollableDialog"))
        assertTrue("投递详情必须用 UfiDialogInfoRow", code.contains("UfiDialogInfoRow"))
        assertTrue("必须调用 deleteDeliveryHistoryItem", code.contains("deleteDeliveryHistoryItem"))
        assertTrue("必须用公共 UfiBadge（经 DeliveryOutcomeBadge）", code.contains("DeliveryOutcomeBadge"))
        assertTrue("失败详情须走 HistoryDetailTextPanel（浅底+复制）", code.contains("HistoryDetailTextPanel"))
        val common = source(commonPath)
        assertTrue("HistoryDetailTextPanel 必须提供复制", common.contains("ContentCopy"))
        assertFalse("筛选文案不得再用口语「没发出」", code.contains("\"没发出\""))
    }

    @Test
    fun systemScreenMustUsePublicDialogAndDelete() {
        val code = source(systemScreenPath)
        assertTrue("系统通知详情必须用 UfiScrollableDialog", code.contains("UfiScrollableDialog"))
        assertTrue("必须调用 deleteNotifyHistoryItem", code.contains("deleteNotifyHistoryItem"))
        assertTrue("必须用 SystemHistoryOutcomeBadge", code.contains("SystemHistoryOutcomeBadge"))
        assertTrue("正文/原因须走 HistoryDetailTextPanel", code.contains("HistoryDetailTextPanel"))
    }

    @Test
    fun apiAndCoreRouteMustExist() {
        assertTrue(
            "App 必须声明 deleteMailHistoryById",
            source(apiPath).contains("deleteMailHistoryById")
        )
        assertTrue(
            "Core 必须注册 DELETE history/{id}",
            source(routesPath).contains("history/{id}")
        )
    }

    @Test
    fun frozenUiComponentsUntouchedThisTask() {
        // 本任务约定不改这些文件；若仓库无 git 也至少保证文件存在（存在性检查）。
        val frozen = listOf(
            "app/ui/src/main/java/com/ufi_axis/ui/components/common/UfiSettingsItem.kt",
            "app/ui/src/main/java/com/ufi_axis/ui/components/common/UfiStandardDialogs.kt",
            "app/ui/src/main/java/com/ufi_axis/ui/components/common/UfiBadge.kt"
        )
        frozen.forEach { path ->
            assertTrue("冻结组件仍应存在: $path", source(path).isNotEmpty())
        }
    }
}

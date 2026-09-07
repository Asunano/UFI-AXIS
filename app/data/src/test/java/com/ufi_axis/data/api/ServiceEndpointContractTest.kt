package com.ufi_axis.data.api

import com.ufi_axis_core.contract.Endpoints
import org.junit.Assert.assertEquals
import org.junit.Test
import retrofit2.http.GET
import retrofit2.http.POST

/**
 * 锁死 app 侧 Retrofit 注解路径与 `core:contract` 常量一致（T19 复核补强）。
 *
 * 为什么不直接在注解里写常量：`scripts/verify-api-contract.mjs` 靠**扫描字面量**比对 core 路由，
 * 注解换成常量后校验器就看不到这些端点（实测 app 端点数 173→168），等于削弱了跨端校验。
 * 于是保留字面量 + 用本测试兜住"字面量与 contract 漂移"这一种失败模式，两层防护都不丢。
 */
class ServiceEndpointContractTest {

    private fun getPath(method: String): String {
        val m = UfiAxisApi::class.java.methods.first { it.name == method }
        return m.getAnnotation(GET::class.java)?.value
            ?: m.getAnnotation(POST::class.java)?.value
            ?: error("$method 上既没有 @GET 也没有 @POST")
    }

    @Test
    fun `service control paths match contract constants`() {
        assertEquals(Endpoints.Service.STATUS, getPath("getServiceStatus"))
        assertEquals(Endpoints.Service.START, getPath("startBackgroundService"))
        assertEquals(Endpoints.Service.STOP, getPath("stopBackgroundService"))
        assertEquals(Endpoints.Service.RESTART, getPath("restartBackendService"))
        assertEquals(Endpoints.Service.AUTOSTART, getPath("setServiceAutoStart"))
        // core 崩溃提醒（2026-09-04）：路径写错 = 崩溃永远提示不出来，且不会有任何报错
        assertEquals(Endpoints.Service.CRASH, getPath("getCoreCrashReport"))
    }

    @Test
    fun `monitor control path matches contract constant`() {
        // /api/monitor/control 与 /api/service/start|stop 是同一份持久化开关，路径不能各写各的
        assertEquals(Endpoints.Monitor.CONTROL, getPath("setMonitorControl"))
    }

    @Test
    fun `web asset paths match contract constants`() {
        // 这两个是「上传了坏面板之后」唯一的恢复通道，路径写错等于恢复入口失效且没有替代路径
        assertEquals(Endpoints.Web.VERSION, getPath("getWebAssetVersion"))
        assertEquals(Endpoints.Web.CLEAR, getPath("clearWebAssets"))
        // 面板管理入口（2026-08-30）：rollback 是一次性备份，update 是唯一的上传通道
        assertEquals(Endpoints.Web.STATUS, getPath("getWebUpdateStatus"))
        assertEquals(Endpoints.Web.CHECK, getPath("checkWebAssetUpdate"))
        assertEquals(Endpoints.Web.UPDATE, getPath("uploadWebAssets"))
        assertEquals(Endpoints.Web.ROLLBACK, getPath("rollbackWebAssets"))
    }

    @Test
    fun `alert batch ack path matches contract constant`() {
        assertEquals(Endpoints.Alerts.ACK_RESOLVED, getPath("ackResolvedAlerts"))
    }
}

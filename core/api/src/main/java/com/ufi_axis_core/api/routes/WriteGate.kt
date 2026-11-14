package com.ufi_axis_core.api.routes

import com.ufi_axis_core.contract.ErrorCode
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall

/**
 * 设备写操作全局门（2026-10-05 R4-9 修复）。
 *
 * 此前重启 / 恢复出厂不感知更新状态机：OTA 拷贝到一半收到 /device/reboot 可致半写状态。
 * UPDATE_IN_PROGRESS 错误码已存在（UpdateRoutes 自用），这里把它复用到跨路由的写入口。
 *
 * 判据由装配层在构造 UpdateManager 后注入（[updateBusy]），core/api 层拿不到
 * UpdateManager 的构造依赖，所以用回调而不是直引。崩溃后标志滞留的自愈由
 * UpdateManager 自身的启动恢复（recoverResultFromLog / recoverFromPending）兜底，
 * 不在本门重复做 TTL。
 */
object WriteGate {
    /** 返回 true 表示更新流程占用中（DOWNLOADING/VERIFYING/INSTALLING/UPLOADING）。 */
    @Volatile
    var updateBusy: () -> Boolean = { false }

}

/**
 * 更新进行中则回 409 CONFLICT + UPDATE_IN_PROGRESS 并返回 true（调用方须立刻 return）；
 * 否则什么都不做返回 false。
 * 顶层扩展：同包调用点无需 import（2026-10-05 R4-9 修复）。
 */
suspend fun ApplicationCall.rejectIfUpdating(): Boolean {
    if (!WriteGate.updateBusy()) return false
    respondFail(HttpStatusCode.Conflict, ErrorCode.UPDATE_IN_PROGRESS,
        "更新进行中，暂不接受该操作")
    return true
}

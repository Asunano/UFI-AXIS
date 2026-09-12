package com.ufi_axis.viewmodel.module

import com.ufi_axis.data.api.UfiAxisApi
import com.ufi_axis.data.model.BACKUP_MODE_MERGE
import com.ufi_axis.data.model.BACKUP_MODE_REPLACE
import com.ufi_axis.data.model.BackupExportRequest
import com.ufi_axis.data.model.BackupImportResponse
import com.ufi_axis.data.model.BackupInfoResponse
import com.ufi_axis.data.model.BackupPreviewResponse
import com.ufi_axis.util.DebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.HttpException

/**
 * 配置备份与恢复（`/api/backup`）。
 *
 * 本模块不持有状态：备份是一次性的用户动作，页面停留期间没有需要轮询或跨页共享的数据，
 * 因此每个方法返回一次结果，由 UI 自行 remember。这与 [AppManagerModule] 那种「列表 + loading 态」
 * 的模块不属于同一类，套用 StateFlow 只会多出一份需要清理的残留状态。
 *
 * 备份包的组装、加解密与段的取舍全部在 core。本模块只负责三件事：提交手机端自身的偏好段、
 * 传递备份包字节、把 core 的失败信封转换为一句可展示的错误说明。
 */
class BackupModule(private val api: UfiAxisApi) {

    /** 读包格式与口令约束。失败回 null —— 页面据此禁用按钮并提示"读不到设备端信息"。 */
    suspend fun loadInfo(): BackupInfoResponse? = runCatching { api.getBackupInfo() }
        .onFailure { DebugLog.w(TAG, "读备份信息失败", it) }
        .getOrNull()

    /**
     * 导出。
     *
     * @param clientApp 手机端自身的偏好段，由调用方组装：它需要读 ThemeManager，而后者位于
     *   `:app:ui`，本模块不可见。
     * @return 成功返回 `.ufibak` 的完整字节；失败返回一条错误说明
     */
    suspend fun export(
        encrypted: Boolean,
        passphrase: String,
        clientApp: JsonObject
    ): Result<ByteArray> = call {
        val body = BackupExportRequest(
            encrypted = encrypted,
            passphrase = passphrase,
            // 不加密 = 用户在导出弹窗里主动关掉了开关，且当场的风险提示块已经把
            // "包内凭据会是明文"说清楚了。core 要求这个标记才肯出明文包（见 BackupRoutes），
            // 因此这里把"用户的这次操作"翻译成显式确认。
            acknowledge_plaintext = !encrypted,
            client = mapOf(com.ufi_axis.data.model.BACKUP_CLIENT_APP to clientApp)
        )
        // @Streaming 的响应体必须在 IO 线程读完再 close，不能带出协程
        withContext(Dispatchers.IO) { api.exportBackup(body).use { it.bytes() } }
    }

    /** 预览：只读清单，不落任何配置。加密包必须给对口令，否则 core 回 401。 */
    suspend fun preview(pack: ByteArray, passphrase: String): Result<BackupPreviewResponse> = call {
        api.previewBackup(pack.toRequestBody(OCTET_STREAM), passphrase.ifBlank { null })
    }

    /** 恢复。[replace] = 连本机多出来的项也清掉；默认只覆盖包里有的。 */
    suspend fun import(
        pack: ByteArray,
        replace: Boolean,
        passphrase: String
    ): Result<BackupImportResponse> = call {
        api.importBackup(
            body = pack.toRequestBody(OCTET_STREAM),
            mode = if (replace) BACKUP_MODE_REPLACE else BACKUP_MODE_MERGE,
            passphrase = passphrase.ifBlank { null }
        )
    }

    /**
     * 统一的错误转换。
     *
     * core 的失败信封是 `{"success":false,"error":"...","code":"..."}`，而 Retrofit 抛出的
     * 异常只有 `HTTP 401 Unauthorized` 这类状态行 —— 直接展示异常 message 无法说明原因。
     * 这里从 errorBody 中取出 `error` 字段：口令错误、包损坏、超出大小上限，全靠它区分。
     */
    private suspend fun <T> call(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: HttpException) {
        val detail = withContext(Dispatchers.IO) { errorMessageOf(e) }
        DebugLog.w(TAG, "备份接口失败 code=${e.code()} detail=$detail")
        Result.failure(IllegalStateException(detail))
    } catch (e: Exception) {
        DebugLog.w(TAG, "备份接口异常", e)
        Result.failure(IllegalStateException(e.message ?: "请求失败"))
    }

    private fun errorMessageOf(e: HttpException): String {
        val raw = runCatching { e.response()?.errorBody()?.string() }.getOrNull()
        if (raw.isNullOrBlank()) return "请求失败（HTTP ${e.code()}）"
        return runCatching {
            LENIENT_JSON.parseToJsonElement(raw).jsonObject["error"]?.jsonPrimitive?.contentOrNull
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: "请求失败（HTTP ${e.code()}）"
    }

    companion object {
        private const val TAG = "Backup"

        /**
         * 必须显式带类型：[com.ufi_axis.data.api.RetrofitClient] 的签名拦截器会给
         * **没有** Content-Type 的请求体补 `application/json`，那样 core 会按 JSON 去解一段 ZIP。
         */
        private val OCTET_STREAM = "application/octet-stream".toMediaType()

        private val LENIENT_JSON = Json { ignoreUnknownKeys = true; isLenient = true }
    }
}

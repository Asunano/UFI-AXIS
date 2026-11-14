package com.ufi_axis_core.api.program

import com.ufi_axis_core.api.update.MirrorResolver
import com.ufi_axis_core.controller.program.ProgramCatalog
import com.ufi_axis_core.controller.program.ProgramManifestCodec
import com.ufi_axis_core.util.AppLogger
import com.ufi_axis_core.util.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * 远端程序清单刷新（P1）。
 *
 * 信任链与组件更新一致：`update_url` 指向仓库根 `version.json`，经 [MirrorResolver]
 * 候选回退拉取；只解析其中的 `programs` 段，并由 [ProgramManifestCodec] 做完整静态校验。
 * **远端是唯一可信锚点**（版本/url/sha256），内置 catalog 只是断网/拉取失败时的兜底。
 *
 * 失败不抛到调用方：返回 Result，调用方只记日志；内置清单一律保留。
 */
class ProgramCatalogUpdater(
    private val settings: AppSettings,
    private val catalog: ProgramCatalog
) {

    data class RefreshResult(
        val source: String,
        val total: Int,
        val bad: List<Pair<String, String>>
    )

    suspend fun refresh(): Result<RefreshResult> = withContext(Dispatchers.IO) {
        val updateUrl = settings.updateUrl.trim()
        if (updateUrl.isEmpty()) {
            return@withContext Result.failure(IllegalStateException("未配置更新源（update_url 为空）"))
        }
        MirrorResolver.prepare(settings)
        val candidates = MirrorResolver.candidates(settings, updateUrl)
        if (candidates.isEmpty()) {
            return@withContext Result.failure(IllegalStateException("更新源候选为空"))
        }

        val errors = mutableListOf<String>()
        for ((index, url) in candidates.withIndex()) {
            try {
                ProgramManifestCodec.requireSecureUrl(url).getOrThrow()
                val text = fetch(url)
                val parsed = ProgramManifestCodec.parseCatalog(text).getOrThrow()
                if (parsed.ok.isEmpty()) {
                    throw IllegalStateException("更新源没有可用的 programs 条目（内置清单已保留）")
                }
                catalog.replaceRemote(parsed.ok)
                AppLogger.i(
                    TAG,
                    "远端程序清单刷新成功：${parsed.ok.size} 条（第 ${index + 1}/${candidates.size} 源，丢弃 ${parsed.bad.size} 条）"
                )
                return@withContext Result.success(
                    RefreshResult(source = url, total = parsed.ok.size, bad = parsed.bad)
                )
            } catch (e: Exception) {
                errors += "[${index + 1}] $url → ${e.message ?: e.javaClass.simpleName}"
                AppLogger.w(TAG, "远端程序清单候选失败: ${e.message}")
            }
        }
        Result.failure(IllegalStateException("全部 ${candidates.size} 个清单候选都失败：\n" + errors.joinToString("\n")))
    }

    private fun fetch(urlStr: String): String {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 20_000
            requestMethod = "GET"
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "UFI-AXIS-Core/1.0")
            setRequestProperty("Accept", "application/json")
        }
        try {
            if (conn.responseCode !in 200..299) {
                throw IllegalStateException("HTTP ${conn.responseCode}")
            }
            val declared = conn.contentLengthLong
            if (declared > MAX_MANIFEST_BYTES) {
                throw IllegalStateException("清单体积 ${declared / 1024}KB 超过上限 ${MAX_MANIFEST_BYTES / 1024}KB")
            }
            val text = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            if (text.length > MAX_MANIFEST_BYTES) {
                throw IllegalStateException("清单体积超过上限 ${MAX_MANIFEST_BYTES / 1024}KB")
            }
            return text
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    private companion object {
        const val TAG = "ProgramCatalogUpdater"

        /** 根 version.json 含前端/后端/组件/程序，正常几 KB；2MB 足够且防异常响应。 */
        const val MAX_MANIFEST_BYTES = 2L * 1024 * 1024
    }
}

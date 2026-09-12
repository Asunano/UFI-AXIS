package com.ufi_axis_core.api.backup

import android.content.Context
import android.content.SharedPreferences
import com.ufi_axis_core.core.database.AppDatabase
import com.ufi_axis_core.core.database.SmsRule
import com.ufi_axis_core.util.AppLogger
import com.ufi_axis_core.util.AppSettings
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.File

/**
 * 备份包内各段的采集与写回。
 *
 * 这个类**只管内容**：把配置拆成一组「相对路径 → 字节」，以及把这样一组内容写回系统。
 * ZIP 打包、manifest、加密都在 `BackupRoutes` 里，两件事分开才好测。
 *
 * ## 段划分
 *
 * - `core/settings.json` —— [AppSettings] 白名单键（类型与范围校验在 AppSettings 里）
 * - `core/prefs/<file>.json` —— 五个独立 prefs 文件（任务、自动化规则、三条通知渠道）
 * - `core/sms_rules.json` —— Room `sms_rule` 表
 * - `core/tunnel/frp/<name>.toml`、`core/tunnel/cf/<name>.token` —— 隧道通道配置原文
 * - `client/app.json`、`client/web.json` —— 客户端自己的偏好，core **不解释内容**，只转存
 *
 * ## 为什么 prefs 值要带类型标记
 *
 * `SharedPreferences` 对类型是强绑定的：一个键用 `putLong` 写进去，再用 `getInt` 读就直接抛
 * `ClassCastException`。而 JSON 里 `30` 既可能是 Int 也可能是 Long。所以每个值都编码成
 * `{"t":"i","v":30}`，导入时按 `t` 决定调哪个 `putXxx`。少了这一步，恢复出来的配置会在
 * 第一次读取时崩，而且崩的地方离备份功能很远、极难查。
 */
class BackupAssembler(
    private val context: Context,
    private val settings: AppSettings,
    private val database: AppDatabase
) {

    /** 采集结果：相对路径 → 内容字节。 */
    suspend fun collect(clientSections: Map<String, String>): Map<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()

        out[PATH_SETTINGS] = encodeTypedMap(settings.exportBackupFields()).toBytes()

        PREFS_FILES.forEach { spec ->
            val prefs = context.getSharedPreferences(spec.name, Context.MODE_PRIVATE)
            val values = prefs.all.filterKeys { key ->
                spec.excludedKeys.none { excluded -> key == excluded }
            }
            if (values.isNotEmpty()) {
                out["$PREFS_DIR${spec.name}.json"] = encodeTypedMap(values).toBytes()
            }
        }

        val rules = runCatching { database.smsRuleDao().getAll() }.getOrElse {
            AppLogger.w(TAG, "读取拦截规则失败，本段跳过: ${it.message}")
            emptyList()
        }
        if (rules.isNotEmpty()) out[PATH_SMS_RULES] = encodeRules(rules).toBytes()

        collectTunnelFiles(out)

        clientSections.forEach { (name, json) ->
            // 客户端段原样转存：core 不解析、不校验内容。它是客户端自己的偏好，
            // 语义只有客户端知道；core 越解释越容易在双方版本不一致时出错。
            if (name == CLIENT_APP || name == CLIENT_WEB) {
                out["$CLIENT_DIR$name.json"] = json.toByteArray()
            }
        }
        return out
    }

    private fun collectTunnelFiles(out: MutableMap<String, ByteArray>) {
        listOf(
            Triple(File(context.filesDir, "frp/configs"), "$TUNNEL_DIR" + "frp/", ".toml"),
            Triple(File(context.filesDir, "cloudflared/tunnels"), "$TUNNEL_DIR" + "cf/", ".token")
        ).forEach { (dir, prefix, suffix) ->
            val files = dir.listFiles()?.filter { it.isFile && it.name.endsWith(suffix) } ?: return@forEach
            files.forEach { file ->
                runCatching { out[prefix + file.name] = file.readBytes() }
                    .onFailure { AppLogger.w(TAG, "读取隧道配置失败 ${file.name}: ${it.message}") }
            }
        }
    }

    /**
     * 把一组段写回系统。
     *
     * @param replace true = 每一段先回到"空/默认"再写入；false = 只覆盖包里出现的内容
     */
    suspend fun apply(entries: Map<String, ByteArray>, replace: Boolean): ApplyReport {
        val applied = mutableListOf<String>()
        val failed = LinkedHashMap<String, String>()
        var needsRestart = false

        entries[PATH_SETTINGS]?.let { bytes ->
            runCatching {
                val report = settings.importBackupFields(decodeTypedMap(bytes), replace)
                needsRestart = needsRestart || report.needsRestart
                applied += "$PATH_SETTINGS(${report.applied.size} 项)"
                report.rejected.forEach { (k, why) -> failed["$PATH_SETTINGS:$k"] = why }
            }.onFailure { failed[PATH_SETTINGS] = it.message ?: "解析失败" }
        }

        PREFS_FILES.forEach { spec ->
            val path = "$PREFS_DIR${spec.name}.json"
            val bytes = entries[path] ?: return@forEach
            runCatching {
                val values = decodeTypedMap(bytes)
                val prefs = context.getSharedPreferences(spec.name, Context.MODE_PRIVATE)
                val editor = prefs.edit()
                if (replace) {
                    // 只清"非排除项"：配额与统计属于运行态，既不进包也不该被清零，
                    // 否则恢复一次备份就等于把今天的短信配额重置了。
                    prefs.all.keys
                        .filter { key -> spec.excludedKeys.none { it == key } }
                        .forEach { editor.remove(it) }
                }
                values.forEach { (key, value) -> putTyped(editor, key, value) }
                editor.apply()
                applied += "$path(${values.size} 项)"
            }.onFailure { failed[path] = it.message ?: "解析失败" }
        }

        entries[PATH_SMS_RULES]?.let { bytes ->
            runCatching {
                val rules = decodeRules(bytes)
                val dao = database.smsRuleDao()
                if (replace) dao.deleteAll()
                rules.forEach { dao.insert(it) }
                applied += "$PATH_SMS_RULES(${rules.size} 条)"
            }.onFailure { failed[PATH_SMS_RULES] = it.message ?: "解析失败" }
        }

        applyTunnelFiles(entries, applied, failed)
        return ApplyReport(applied, failed, needsRestart)
    }

    private fun applyTunnelFiles(
        entries: Map<String, ByteArray>,
        applied: MutableList<String>,
        failed: MutableMap<String, String>
    ) {
        entries.keys.filter { it.startsWith(TUNNEL_DIR) }.forEach { path ->
            val name = path.substringAfterLast('/')
            // 防目录穿越：包里的路径来自不可信文件，只取文件名，且拒绝可疑名字
            if (name.isBlank() || name.contains("..") || name.contains('\\')) {
                failed[path] = "文件名不合法"
                return@forEach
            }
            val dir = when {
                path.startsWith("${TUNNEL_DIR}frp/") -> File(context.filesDir, "frp/configs")
                path.startsWith("${TUNNEL_DIR}cf/") -> File(context.filesDir, "cloudflared/tunnels")
                else -> null
            }
            if (dir == null) {
                failed[path] = "未知的隧道类型"
                return@forEach
            }
            runCatching {
                dir.mkdirs()
                File(dir, name).writeBytes(entries.getValue(path))
                applied += path
            }.onFailure { failed[path] = it.message ?: "写入失败" }
        }
    }

    /** 预览：只报告包里有什么、会影响多少项，不落地任何改动。 */
    fun describe(entries: Map<String, ByteArray>): List<SectionSummary> {
        val out = mutableListOf<SectionSummary>()
        entries[PATH_SETTINGS]?.let { bytes ->
            val values = runCatching { decodeTypedMap(bytes) }.getOrDefault(emptyMap())
            val sensitive = values.keys.count { it in AppSettings.SENSITIVE_BACKUP_KEYS }
            out += SectionSummary(PATH_SETTINGS, "服务与设备配置", values.size, sensitive)
        }
        PREFS_FILES.forEach { spec ->
            val bytes = entries["$PREFS_DIR${spec.name}.json"] ?: return@forEach
            val values = runCatching { decodeTypedMap(bytes) }.getOrDefault(emptyMap())
            out += SectionSummary("$PREFS_DIR${spec.name}.json", spec.label, values.size, 0)
        }
        entries[PATH_SMS_RULES]?.let { bytes ->
            val rules = runCatching { decodeRules(bytes) }.getOrDefault(emptyList())
            out += SectionSummary(PATH_SMS_RULES, "短信拦截规则", rules.size, 0)
        }
        val tunnelCount = entries.keys.count { it.startsWith(TUNNEL_DIR) }
        if (tunnelCount > 0) out += SectionSummary(TUNNEL_DIR, "隧道通道配置", tunnelCount, tunnelCount)
        listOf(CLIENT_APP to "手机端偏好", CLIENT_WEB to "网页端偏好").forEach { (name, label) ->
            if (entries.containsKey("$CLIENT_DIR$name.json")) {
                out += SectionSummary("$CLIENT_DIR$name.json", label, 1, 0)
            }
        }
        return out
    }

    /** 取出客户端段原文，供路由回给对应客户端自行落地。 */
    fun clientSection(entries: Map<String, ByteArray>, name: String): String? =
        entries["$CLIENT_DIR$name.json"]?.toString(Charsets.UTF_8)

    // ── 带类型标记的 prefs 编解码 ──

    private fun encodeTypedMap(values: Map<String, Any?>): JsonObject = buildJsonObject {
        values.forEach { (key, value) ->
            when (value) {
                is Boolean -> putJsonObject(key) { put("t", "b"); put("v", value) }
                is Int -> putJsonObject(key) { put("t", "i"); put("v", value) }
                is Long -> putJsonObject(key) { put("t", "l"); put("v", value) }
                is Float -> putJsonObject(key) { put("t", "f"); put("v", value) }
                is String -> putJsonObject(key) { put("t", "s"); put("v", value) }
                is Set<*> -> putJsonObject(key) {
                    put("t", "ss")
                    putJsonArray("v") { value.filterIsInstance<String>().forEach { add(it) } }
                }
                // 其余类型（含 null）不进包：SharedPreferences 只有上面这几种
                else -> Unit
            }
        }
    }

    private fun decodeTypedMap(bytes: ByteArray): Map<String, Any?> {
        val root = JSON.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
        val out = LinkedHashMap<String, Any?>()
        root.forEach { (key, element) ->
            val obj = element as? JsonObject ?: return@forEach
            val type = obj["t"]?.jsonPrimitive?.contentOrNull ?: return@forEach
            val raw = obj["v"] ?: return@forEach
            out[key] = when (type) {
                "b" -> raw.jsonPrimitive.booleanOrNull
                "i" -> raw.jsonPrimitive.intOrNull
                "l" -> raw.jsonPrimitive.longOrNull
                "f" -> raw.jsonPrimitive.contentOrNull?.toFloatOrNull()
                "s" -> raw.jsonPrimitive.contentOrNull
                "ss" -> raw.jsonArray.mapNotNull { it.jsonPrimitive.contentOrNull }.toSet()
                else -> null
            }
        }
        return out
    }

    private fun putTyped(editor: SharedPreferences.Editor, key: String, value: Any?) {
        when (value) {
            is Boolean -> editor.putBoolean(key, value)
            is Int -> editor.putInt(key, value)
            is Long -> editor.putLong(key, value)
            is Float -> editor.putFloat(key, value)
            is String -> editor.putString(key, value)
            is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
            else -> Unit
        }
    }

    // ── 拦截规则编解码 ──

    /**
     * 规则序列化。
     *
     * 刻意**不带 `id` / `hit_count` / `last_hit_at`：id 由目标库插入时重新分配（带过去会
     * 和目标库已有行冲突），命中统计是运行态、跨设备没有意义。
     */
    private fun encodeRules(rules: List<SmsRule>): JsonArray = buildJsonArray {
        rules.forEach { rule ->
            add(buildJsonObject {
                put("enabled", rule.enabled)
                put("scope", rule.scope)
                put("match_type", rule.match_type)
                put("pattern", rule.pattern)
                put("note", rule.note)
                put("created_at", rule.created_at)
            })
        }
    }

    private fun decodeRules(bytes: ByteArray): List<SmsRule> {
        val array = JSON.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonArray
        return array.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val pattern = obj["pattern"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val scope = obj["scope"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val matchType = obj["match_type"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            if (pattern.isBlank()) return@mapNotNull null
            SmsRule(
                enabled = obj["enabled"]?.jsonPrimitive?.booleanOrNull ?: true,
                scope = scope,
                match_type = matchType,
                pattern = pattern,
                note = obj["note"]?.jsonPrimitive?.contentOrNull ?: "",
                created_at = obj["created_at"]?.jsonPrimitive?.longOrNull ?: System.currentTimeMillis()
            )
        }
    }

    private fun JsonObject.toBytes(): ByteArray = JSON.encodeToString(JsonObject.serializer(), this).toByteArray()
    private fun JsonArray.toBytes(): ByteArray = JSON.encodeToString(JsonArray.serializer(), this).toByteArray()

    /** 一段的概览，供导入前预览。 */
    class SectionSummary(
        val path: String,
        val label: String,
        val itemCount: Int,
        val sensitiveCount: Int
    )

    class ApplyReport(
        val applied: List<String>,
        val failed: Map<String, String>,
        val needsRestart: Boolean
    )

    /**
     * 一个要备份的 prefs 文件。
     *
     * [excludedKeys] 是运行态与一次性迁移标记：它们既不进包，`replace` 模式下也不清除。
     * 把配额清零会让"恢复一次备份"变成"重置今天的发送额度"，把迁移标记清掉会让
     * 迁移重跑一遍。
     */
    private class PrefsSpec(
        val name: String,
        val label: String,
        val excludedKeys: List<String> = emptyList()
    )

    companion object {
        private const val TAG = "Backup"

        const val PATH_SETTINGS = "core/settings.json"
        const val PATH_SMS_RULES = "core/sms_rules.json"
        const val PREFS_DIR = "core/prefs/"
        const val TUNNEL_DIR = "core/tunnel/"
        const val CLIENT_DIR = "client/"
        const val CLIENT_APP = "app"
        const val CLIENT_WEB = "web"

        private val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        private val PREFS_FILES = listOf(
            PrefsSpec("scheduled_tasks", "定时任务"),
            PrefsSpec("automation_rules", "自动化规则"),
            PrefsSpec(
                "sms_forward", "邮件通知渠道",
                excludedKeys = listOf(
                    "quota_day", "quota_count",
                    "stat_success", "stat_failed", "stat_last_at", "stat_last_error",
                    "last_forwarded_sms_id",
                    "scenes_migrated_v2", "scenes_migrated_v3", "blacklist_migrated_to_rules"
                )
            ),
            PrefsSpec(
                "notify_webhook", "Webhook 通知渠道",
                excludedKeys = listOf("quota_day", "quota_count")
            ),
            PrefsSpec(
                "notify_local_sms", "本机短信通知渠道",
                excludedKeys = listOf("quota_day", "quota_count")
            )
        )
    }
}

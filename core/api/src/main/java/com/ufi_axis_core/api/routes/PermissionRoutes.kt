package com.ufi_axis_core.api.routes

import android.app.AppOpsManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import com.ufi_axis_core.api.ResponseHelper.toJsonElement
import com.ufi_axis_core.util.AdbShellExecutor
import com.ufi_axis_core.util.AppLogger
import com.ufi_axis_core.util.ShellExecutor
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray

/**
 * Core 自身的权限自检与补全（2026-10-14 新增）。
 *
 * ## 为什么非加这个端点不可（这是本文件存在的唯一理由）
 *
 * 痛点（用户原话）：**从旧版 scripts 脚本装出来的设备，升级到新 core 之后新增的权限没人补**。
 * 事实链条如下，每一环都在本仓里可查：
 *
 * 1. 「core 需要哪些权限」的唯一真源是 **core 自己的 AndroidManifest**
 *    （`core/src/main/AndroidManifest.xml`）。给 core 没声明的权限做 grant 是无效动作
 *    （`pm grant` 回 not a requested permission），所以清单不能凭空写。
 * 2. 真正执行授予的**只有三处**，且都不是「升级后自动重跑」：
 *    · `scripts/UFI-AXIS-Core-install-Windows-Linux/UFI-AXIS-Core-Installation.{sh,bat}`
 *      与 Android 安装器 `PermissionGranter.kt` —— 只在用户**手动跑安装脚本**时执行；
 *    · `BackendService.ensureRuntimePermissions()` —— 每次服务启动跑，但只覆盖 3 条权限
 *      （FINE/COARSE_LOCATION、READ_PHONE_STATE），且用的是裸 `pm`（uid 10101 必失败）；
 *    · `AppManager.grantAllPermissionsViaAdb()`（`POST /api/apps/grant-all-permissions`）
 *      —— 走内置 adb（uid 2000）能成功，但那份清单是**另一份手抄的短清单**
 *      （10 条 pm grant + 3 条 appops，缺 READ_MEDIA_*、SCHEDULE_EXACT_ALARM、GET_USAGE_STATS），
 *      且它的语义是「给**别的**应用授权」，UI 上只出现在应用详情页。
 * 3. `pm install -r` 覆盖安装**不会**给新版本新增的 dangerous 权限补授权，
 *    appop 类（MANAGE_EXTERNAL_STORAGE / REQUEST_INSTALL_PACKAGES / GET_USAGE_STATS /
 *    SCHEDULE_EXACT_ALARM）也不会自动放行 —— 于是新版本里依赖新权限的功能**静默失效**：
 *    界面在、点了没用、日志里也没有一句"权限不足"。
 *
 * 客户端自己算不出来这件事：它看不见 core 的 manifest，也读不到 appop 模式。
 * 两条替代路都不成立：
 * · **让客户端解析 `dumpsys package`**：输出格式随 Android 版本漂移，而且 appop 状态
 *   （MANAGE_EXTERNAL_STORAGE 这类）根本不在 `dumpsys package` 里，要另跑 `appops get`
 *   再解析一次 —— 判据会变成"解析出来像什么"，而不是"系统说是什么"；
 * · **在客户端手抄一份权限清单**：那就是第 4 份会漂移的镜像（脚本、Android 安装器、
 *   AppManager 三份已经互不一致了）。清单必须从**已安装包的 manifest** 现场读。
 *
 * 所以这里用平台 API 做**实际查询**，而不是"能不能用"的间接猜测：
 * · `PackageManager.getPackageInfo(…, GET_PERMISSIONS).requestedPermissions` → 要哪些；
 * · `PackageManager.checkPermission()` → dangerous 类授没授；
 * · `AppOpsManager.permissionToOp()` + `unsafeCheckOpNoThrow()` → appop 类放没放行。
 * 三者都是系统自己的判据，与 `pm grant` / `appops set` 的效果一一对应。
 *
 * ## 补全动作
 * 授予走 [ShellExecutor.executeAsRoot]，即 **adb shell（uid 2000）**：
 * `pm grant` 要求 uid 0 或 2000，应用自身 uid（10101）执行会静默失败
 * （`AdbShellExecutor` 会回显设备侧真实退出码，所以成功与否**不是猜的**）。
 * ADB 通道不可用时本端点**不假装成功**：逐条失败并给出下一步指引。
 *
 * ## 端点
 * · `GET  /api/system/permissions`        只读自检
 * · `POST /api/system/permissions/grant`  补全（写动作，客户端必须二次确认）
 */
class PermissionRoutes(private val context: Context) {

    private companion object {
        const val TAG = "PermissionRoutes"

        /**
         * `PermissionInfo` 的 `@hide` 常量。
         *
         * 为什么写字面量而不是引用：`PROTECTION_MASK_BASE` / `PROTECTION_FLAG_APPOP`
         * 是 @hide，SDK 的 `android.jar` 里没有它们，引用会直接编译不过。
         * 取值与 AOSP `frameworks/base/core/java/android/content/pm/PermissionInfo.java` 一致。
         */
        const val PROTECTION_MASK_BASE = 0x0f
        const val PROTECTION_FLAG_APPOP = 0x40

        /**
         * 权限短名 → appop **操作名**的例外表。
         *
         * ## 为什么需要它、又为什么只有一条
         * 平台上「权限名 → appop 名」的映射（AOSP `AppOpsManager.sPermToOp`）**不是公开 API**：
         * `AppOpsManager.permissionToOp()` 是 @hide，SDK 的 android.jar 里没有（试过，编译不过）。
         * 而那条映射里唯一一条不满足"appop 名 = 权限短名"的就是它：
         * `PACKAGE_USAGE_STATS` 的 appop 叫 **`GET_USAGE_STATS`**（安装脚本里也是手抄了这一条例外）。
         * 其余三条（MANAGE_EXTERNAL_STORAGE / REQUEST_INSTALL_PACKAGES / SCHEDULE_EXACT_ALARM）
         * 名字与权限短名逐字相同。
         *
         * ⚠ 这张表是**平台事实**，不是 core manifest 的镜像 —— 它不会随 core 加权限而漂移
         * （"core 要哪些权限"永远来自已安装的 manifest，见 [probes]）。
         */
        val APPOP_NAME_OVERRIDES = mapOf("PACKAGE_USAGE_STATS" to "GET_USAGE_STATS")

        /**
         * 「在本机这个 Android 版本上已被取代、不再需要」的权限 → 最高适用的 API level。
         *
         * 与 [APPOP_NAME_OVERRIDES] 同性质：这是**平台版本历史**的事实，不是 core manifest 的镜像。
         *
         * 为什么非有它不可：`WRITE_EXTERNAL_STORAGE` 在 Android 11（API 30）起对 targetSdk ≥ 30
         * 的应用不再授予，`READ_EXTERNAL_STORAGE` 在 Android 13 起被 `READ_MEDIA_*` 细分取代。
         * core 的 manifest 里两条都还声明着（要兼容老设备），于是 `checkPermission` 在新机上回
         * DENIED —— 不排除的话诊断会报"缺 2 项权限"，而这两项**补了也没用**，用户点「补全」会得到
         * 一个看不出问题、也修不好任何东西的结果（正是本文件最想避免的假诊断）。
         * 安装脚本早就按 maxSdk 跳过它们（`grant_one READ_EXTERNAL_STORAGE 0 32 grant`），
         * 这里只是把同一条平台事实搬到 core 侧，判据仍然是"系统实际怎么说"。
         */
        val SUPERSEDED_MAX_SDK = mapOf(
            "WRITE_EXTERNAL_STORAGE" to 29,
            "READ_EXTERNAL_STORAGE" to 32,
        )

        /** `pm grant` / `appops set` 都是秒级命令，10s 已经非常宽松。 */
        const val GRANT_TIMEOUT_MS = 10_000L

        /**
         * 「用不着的权限被系统自动回收」的 appop 名（`appops set` 吃的是**不带 android: 前缀**
         * 的大写操作名，与 `permissionToOp` 回的 OPSTR 形状不同，两处别抄混）。
         *
         * 它是"权限莫名其妙少了"的第二大来源（第一大是升级后没补）：Android 11+ 默认会把
         * 长期未使用的运行时权限自动撤销。安装脚本会把它设成 `ignore`，但**升级路径不会重跑
         * 脚本**，所以升级/新装后的设备可能停在 `default` 上。补全动作顺手设成 `ignore`
         * （幂等、可逆：`appops set … default` 即恢复）。
         */
        const val OP_AUTO_REVOKE_NAME = "AUTO_REVOKE_PERMISSIONS_IF_UNUSED"

        /** 命令输出里出现这些词就是失败（退出码之外的第二道判据，与安装脚本 `cmd_ok` 同口径）。 */
        val FAILURE_HINTS = Regex("exception|error|failure|not allowed|unknown|denied", RegexOption.IGNORE_CASE)
    }

    /**
     * 一条权限的实测状态（**纯数据**，不含 JSON 形状；拼响应在 [probeJson] 一处做）。
     *
     * @param kind `runtime`（dangerous，`pm grant` 可补）/ `appop`（appop 控制，`appops set` 可补）/
     *   `install`（normal，安装时自动授予，缺了说明安装方式异常）/ `signature`（签名级，
     *   shell 补不了，只能以系统应用方式安装）/ `superseded`（本机 Android 版本已不再使用它，
     *   见 [SUPERSEDED_MAX_SDK]）/ `unknown`（本机没有这条权限，多半是跨版本残留）。
     *   后两类**不算缺失也不算补不了**，单独进 `not_applicable`。
     * @param appop 该权限对应的 appop **操作名**（`appops set <pkg> <这个名字> allow` 用的形状）；
     *   非 appop 类为 null。注意它不是 OPSTR：OPSTR 是 `android:` + 这个名字的小写，
     *   `checkOpNoThrow` 吃的是 OPSTR —— 两者形状不同，别抄混（[probeOne] 里现场拼）。
     * @param fixable 能不能由 core 自己补上。**显式字段而不是从 kind 推导**：
     *   "读不到模式的 appop" 也是 kind=appop 却补不了，推导会让它落进可补集合里，
     *   然后客户端给出一个必然失败的按钮。
     * @param note 未授予时的原因说明；已授予为空串
     */
    private data class Probe(
        val name: String,
        val short: String,
        val kind: String,
        val appop: String?,
        val granted: Boolean,
        val fixable: Boolean,
        val note: String,
    )

    fun register(route: Route) {
        route.route("/system") {
            // ── 只读自检 ──
            get("/permissions") {
                val snapshot = withContext(Dispatchers.IO) { probes() }
                call.respond(toJsonElement(snapshotJson(snapshot)))
            }

            /**
             * 补全权限（**写动作**）。
             *
             * body 可空；`{"permissions": ["READ_MEDIA_AUDIO", …]}` 可以只补指定的几条
             * （短名或全名都认），不传则补全部"缺且可补"的。
             *
             * 刻意**不**提供"撤销"端点：本端点的语义是"把 core 自己声明的权限补齐"，
             * 撤销 core 自己的权限只会让功能坏掉，没有正当用途。真要撤销，
             * `pm revoke` / `appops set … default` 都是可逆的原文命令，响应里会连命令一起给出。
             */
            post("/permissions/grant") {
                val body = call.receiveJsonObject()
                val wanted = (body["permissions"] as? JsonArray)
                    ?.mapNotNull { it.toString().trim('"').takeIf { s -> s.isNotBlank() } }

                val before = withContext(Dispatchers.IO) { probes() }
                val targets = before
                    .filter { it.fixable }
                    .filter { wanted == null || wanted.any { w -> w == it.short || w == it.name } }

                if (targets.isEmpty()) {
                    call.respond(
                        toJsonElement(
                            mapOf(
                                "success" to true,
                                "ok" to true,
                                "attempted" to 0,
                                "granted" to 0,
                                "failed" to 0,
                                "results" to emptyList<Any>(),
                                "message" to "没有可补全的权限（要么都授上了，要么剩下的 shell 补不了）",
                                "permissions" to snapshotJson(before)
                            )
                        )
                    )
                    return@post
                }

                val results = withContext(Dispatchers.IO) {
                    targets.map { target ->
                        val (ok, detail) = runOne(target)
                        mapOf<String, Any?>(
                            "name" to target.name,
                            "short" to target.short,
                            "kind" to target.kind,
                            "ok" to ok,
                            "detail" to detail
                        )
                    }
                }

                // 顺手把「自动撤销未使用权限」关掉：不关的话今天补上的权限过一阵又会被系统收走，
                // 用户会以为"补全没生效"。这一步失败不影响上面的结果（老系统上这个 op 可能不存在）。
                val autoRevokeDetail = withContext(Dispatchers.IO) { hardenAutoRevoke() }

                // 补完**重新探测一次**再回：结论必须是"现在的实际状态"，不是"我们发过命令了"。
                val after = withContext(Dispatchers.IO) { probes() }
                val okCount = results.count { it["ok"] == true }
                call.respond(
                    toJsonElement(
                        mapOf(
                            "success" to (okCount > 0),
                            "ok" to (okCount > 0),
                            "attempted" to results.size,
                            "granted" to okCount,
                            "failed" to (results.size - okCount),
                            "results" to results,
                            "auto_revoke" to autoRevokeDetail,
                            "permissions" to snapshotJson(after)
                        )
                    )
                )
            }
        }
    }

    // ────────────────────────── 探测 ──────────────────────────

    /**
     * 现场读一次全部权限的实测状态。
     *
     * 每次请求都重新读（不做缓存）：权限会被用户在系统设置里改、会被系统自动回收，
     * 缓存一份的后果是诊断页给出**过期的结论** —— 而这一项的全部价值就在于"现在"。
     */
    private fun probes(): List<Probe> {
        val pm = context.packageManager
        val info = try {
            pm.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
        } catch (e: Exception) {
            AppLogger.w(TAG, "getPackageInfo failed: ${e.message}")
            return emptyList()
        }
        val requested = info.requestedPermissions?.filterNotNull().orEmpty()
        val uid = context.applicationInfo.uid
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
        return requested.map { name -> probeOne(pm, appOps, name, uid) }
    }

    @Suppress("DEPRECATION")
    private fun probeOne(pm: PackageManager, appOps: AppOpsManager?, name: String, uid: Int): Probe {
        val short = name.substringAfterLast('.')
        val level = runCatching { pm.getPermissionInfo(name, 0).protectionLevel }.getOrNull()
        if (level == null) {
            // 系统不认这条权限：本机 Android 版本没有它（如 API < 33 的 USE_EXACT_ALARM），
            // 或者是旧版本残留。**不报成缺失**：它补不上，报出来只会让用户去点一个必然失败的按钮。
            return Probe(
                name, short, "unknown", null, false, false,
                "本机 Android 版本没有这条权限（不需要处理）"
            )
        }
        val sdk = android.os.Build.VERSION.SDK_INT
        val supersededAt = SUPERSEDED_MAX_SDK[short]
        if (supersededAt != null && sdk > supersededAt) {
            // 见 SUPERSEDED_MAX_SDK 的说明：补了也没用，所以既不算"缺"也不算"补不了"。
            return Probe(
                name, short, "superseded", null, false, false,
                "本机 Android $sdk 已不再使用它（由别的权限取代），不需要补 —— 安装脚本同样按 maxSdk 跳过"
            )
        }
        val base = level and PROTECTION_MASK_BASE
        // 「被 appop 控制」而不是「有同名 appop」：ACCESS_FINE_LOCATION / READ_SMS 在平台上也有
        // appop 镜像，但它们本质是 runtime 权限（补法是 `pm grant`）。
        // 只有带 PROTECTION_FLAG_APPOP 的那几条才必须走 `appops set`（`pm grant` 必然失败）。
        val appopControlled = (level and PROTECTION_FLAG_APPOP) != 0
        return when {
            appopControlled -> {
                val opName = APPOP_NAME_OVERRIDES[short] ?: short
                // checkOpNoThrow 吃 OPSTR（`android:` + 小写操作名）；opstr 认不出时它会抛
                // IllegalArgumentException，所以 runCatching 兜住并如实报"读不到"，
                // 绝不把"读不到"当成"未放行"（那会给出一个必然失败的补全按钮）。
                val mode = appOps?.let { ops ->
                    runCatching { ops.checkOpNoThrow("android:${opName.lowercase()}", uid, context.packageName) }
                        .getOrNull()
                }
                when {
                    mode == null -> Probe(
                        name, short, "appop", opName, false, false,
                        "读不到这条 appop 的模式（opstr=android:${opName.lowercase()}）—— " +
                            "请用 `appops get ${context.packageName} $opName` 人工核对"
                    )

                    mode == AppOpsManager.MODE_ALLOWED -> Probe(name, short, "appop", opName, true, false, "")

                    else -> Probe(
                        name, short, "appop", opName, false, true,
                        "appop 未放行（mode=$mode）：`pm grant` 对它必然失败，只能用 appops"
                    )
                }
            }

            base == PermissionInfo.PROTECTION_DANGEROUS -> {
                val granted = pm.checkPermission(name, context.packageName) == PackageManager.PERMISSION_GRANTED
                Probe(
                    name, short, "runtime", null, granted, !granted,
                    if (granted) "" else "运行时权限未授予：可用 `pm grant` 补（需 uid 0/2000）"
                )
            }

            base == PermissionInfo.PROTECTION_SIGNATURE -> Probe(
                name, short, "signature", null, false, false,
                "签名级权限：只有以系统应用（platform 签名 / priv-app + 白名单）方式安装才拿得到，" +
                    "shell 补不了。core 的降级路径本来就不依赖它"
            )

            else -> {
                // normal：安装时自动授予。这里若为 false，说明安装方式异常（例如被 appops 拦了）。
                val granted = pm.checkPermission(name, context.packageName) == PackageManager.PERMISSION_GRANTED
                Probe(
                    name, short, "install", null, granted, false,
                    if (granted) "" else "安装时本应自动授予却没有：多半是安装方式异常或被 appops 拦了"
                )
            }
        }
    }

    /** 补全命令的**原文**。补不了时也为空 —— 客户端据此决定给按钮还是给指引。 */
    private fun fixCommandOf(p: Probe): String = when {
        !p.fixable -> ""
        p.kind == "appop" -> "appops set ${context.packageName} ${p.appop ?: p.short} allow"
        else -> "pm grant ${context.packageName} ${p.name}"
    }

    private fun probeJson(p: Probe): Map<String, Any?> = mapOf(
        "name" to p.name,
        "short" to p.short,
        "kind" to p.kind,
        "appop" to p.appop,
        "granted" to p.granted,
        "fixable" to p.fixable,
        "note" to p.note,
        "fix_command" to fixCommandOf(p),
    )

    /**
     * 整个自检快照。
     *
     * `shell_method` / `shell_privileged` 是「能不能补」的前提：uid 2000（adb_shell）能 `pm grant`，
     * 应用自身 uid（10101）不能。客户端据此决定是给「补全」按钮，还是给"先去修 ADB 通道"的指引 ——
     * 少了这两个字段，客户端只能给出一个必然失败的按钮。
     */
    private fun snapshotJson(all: List<Probe>): Map<String, Any?> {
        val missing = all.filter { !it.granted && it.fixable }
        // 「补不了」与「本机不适用」必须分开：前者用户可能想别的办法（装成系统应用），
        // 后者是**不需要处理**。混在一起会让 `blocked` 永远非空，摘要里那句
        // "另有 N 项拿不到"就变成了噪音（每次都出现的话没人会再读它）。
        val blocked = all.filter { !it.granted && !it.fixable && it.kind != "unknown" && it.kind != "superseded" }
        val notApplicable = all.filter { it.kind == "unknown" || it.kind == "superseded" }
        val granted = all.filter { it.granted }
        return mapOf(
            "package" to context.packageName,
            "sdk_int" to android.os.Build.VERSION.SDK_INT,
            "uid" to context.applicationInfo.uid,
            "shell_method" to if (AdbShellExecutor.isAvailable) "adb_shell" else "shell",
            "shell_privileged" to AdbShellExecutor.isAvailable,
            "requested_count" to all.size,
            "granted_count" to granted.size,
            "missing_count" to missing.size,
            "missing" to missing.map { probeJson(it) },
            "blocked" to blocked.map { probeJson(it) },
            "not_applicable" to notApplicable.map { probeJson(it) },
            "granted" to granted.map { it.short },
            "checked_at" to System.currentTimeMillis(),
        )
    }

    // ────────────────────────── 补全 ──────────────────────────

    /** 补一条权限，回 (成功, 说明)。**判据是设备侧真实退出码**，不是"命令发出去了"。 */
    private suspend fun runOne(p: Probe): Pair<Boolean, String> {
        val cmd = fixCommandOf(p)
        if (cmd.isBlank()) return false to "这类权限 shell 补不了（kind=${p.kind}）"
        return try {
            val result = ShellExecutor.executeAsRoot(cmd, GRANT_TIMEOUT_MS)
            val out = result.stdout.trim()
            // 双判据：AdbShellExecutor 会回显设备侧真实退出码（可靠），
            // 但普通 shell 兜底路径的退出码是本地 sh 的，所以再按输出关键词兜一层
            //（与安装脚本的 cmd_ok 同口径：`pm grant` 成功时没有任何输出）。
            val ok = result.isSuccess && !FAILURE_HINTS.containsMatchIn(out)
            AppLogger.i(TAG, "$cmd → exit=${result.exitCode} ok=$ok out=${out.take(120)}")
            if (ok) true to cmd
            else false to out.ifBlank { "exit=${result.exitCode}" + result.stderr.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty() }
        } catch (e: Exception) {
            AppLogger.w(TAG, "$cmd → exception: ${e.message}")
            false to "执行异常：${e.message ?: e.javaClass.simpleName}"
        }
    }

    /**
     * 关掉「长期未使用的权限自动回收」。
     *
     * 回的是给人看的结果文案。失败**不算整体失败**：老系统上这个 op 不存在，
     * 而它只影响"以后会不会再丢"，不影响"现在补上了没有"。
     */
    private suspend fun hardenAutoRevoke(): String {
        val cmd = "appops set ${context.packageName} $OP_AUTO_REVOKE_NAME ignore"
        return try {
            val result = ShellExecutor.executeAsRoot(cmd, GRANT_TIMEOUT_MS)
            val out = result.stdout.trim()
            if (result.isSuccess && !FAILURE_HINTS.containsMatchIn(out)) {
                "已设为 ignore（系统不会再自动回收未使用的权限）"
            } else {
                "未能设置（本机可能不支持这个 op）：${out.ifBlank { "exit=${result.exitCode}" }}"
            }
        } catch (e: Exception) {
            "未能设置：${e.message ?: e.javaClass.simpleName}"
        }
    }
}

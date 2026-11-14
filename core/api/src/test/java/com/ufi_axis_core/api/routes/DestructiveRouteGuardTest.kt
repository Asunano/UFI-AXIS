package com.ufi_axis_core.api.routes

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 反向覆盖守卫：**破坏性族的路由必须登记在 [DESTRUCTIVE_ENDPOINTS] 里**。
 *
 * ## 为什么需要（和已有的 OwnerGateCoverageTest 互补）
 *
 * 现有那只守一个方向：「有门 ⇒ 有锚」。它抓不到历史事故的真实形态 ——
 * **新加一个同能力的入口，忘了写门**（黑名单批量解封就是这么漏的；
 * 2026-11-14 又漏了 `reset-secret` / 组件上传 / `apps/{action}=clear` 等 5 处）。
 * 那种情况下锚与计数全都对得上，测试全绿，门却是装饰。
 *
 * ## 判据为什么是「敏感族」而不是「所有变更类路由」
 *
 * 全仓有 400+ 条 `post/put/delete/patch`。逐条判断"可不可逆"既写不完、也会把
 * 产品口径（装 APK / 装程序 / 组件更新 / 隧道配置明确不打扰）重新判一遍。
 * 这里只守住**能力本身已经写进表头 KDoc 的那几族**：擦配置、导入导出（交出凭据）、
 * root shell、卸载、客户端字节上传、解绑、重置口令。新增这类端点必须登记 ——
 * 不想登记就得在 [EXEMPT] 里写清"为什么它是可撤销的"，让 review 的人看得见。
 */
class DestructiveRouteGuardTest {

    private fun repoRoot(): File {
        var dir: File? = File(".").absoluteFile.normalize()
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile
        }
        throw AssertionError("定位不到仓库根（起点：${File(".").absolutePath}）")
    }

    private val routesPath = "core/api/src/main/java/com/ufi_axis_core/api/routes"

    /**
     * 已经属于"破坏性能力"的路径片段。
     *
     * 只放**语义上就是不可逆或交出凭据**的那些；不做模糊匹配（`/delete` 这种
     * 既可能是删短信也可能是删缓存，留给人为判断，不进这张表）。
     */
    private val sensitiveFamilies = listOf(
        "factory-reset",
        "/reset",
        "/import",
        "/export",
        "/exec",
        "secret",
        "uninstall",
        "/upload",
        "/unpair",
        "blacklist/clear",
        "devices/{fingerprint}",
        "change-password",
    )

    /**
     * 落在敏感族里、但**按产品口径不该进门**的端点。
     *
     * 每条必须写理由；理由不成立就该把它挪进 [DESTRUCTIVE_ENDPOINTS]。
     */
    private val exempt: Map<Triple<String, String, String>, String> = mapOf(
        Triple("ComponentRoutes.kt", "post", "/{id}/uninstall") to
            "组件卸载：二进制可重新下载安装，属可撤销",
        Triple("ConsoleRoutes.kt", "post", "/history/import") to
            "导入控制台历史：只写历史记录，可清空重来",
        Triple("FileRoutes.kt", "post", "/upload") to "文件上传：写用户存储，可删除",
        Triple("FileRoutes.kt", "post", "/upload/session") to "文件上传会话：同上",
        Triple("FileRoutes.kt", "put", "/upload/chunk") to "文件上传分片：同上",
        Triple("FileRoutes.kt", "post", "/upload/complete") to "文件上传完成：同上",
        Triple("FileRoutes.kt", "delete", "/upload/session") to "取消上传会话：同上",
        Triple("PairedDevicesRoutes.kt", "patch", "/pairing/devices/{fingerprint}") to
            "重命名设备：可改回",
        Triple("PairingRoutes.kt", "post", "/unpair") to
            "免 Bearer 的 /pairing 路径：凭据本身就是配对密码，handler 内校验",
        Triple("PairingRoutes.kt", "post", "/change-password") to
            "同上：改密要提供旧密码（旧密码即管理权限证明）",
        Triple("SpeedTestRoutes.kt", "post", "/upload") to "测速上传：写入的样本可删除",
        Triple("UpdateRoutes.kt", "post", "/upload") to
            "上传 APK：装 APK 属明确不打扰的口径（见表头 KDoc）",
        Triple("UpdateRoutes.kt", "post", "/reset") to "重置更新渠道配置：可重新设置",
        Triple("HttpServer.kt", "post", "/unpair") to
            "免 Bearer 的 /pairing/unpair：body 必须带配对密码（HttpServer 内联处理）",
        Triple("HttpServer.kt", "post", "/unpair/{fingerprint}") to
            "同上：逐台解绑，同样要求配对密码",
    )

    @Test
    fun `敏感族的变更端点必须登记在门禁表里`() {
        val dir = File(repoRoot(), routesPath)
        val files = (dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() +
            File(repoRoot(), "core/network/src/main/java/com/ufi_axis_core/core/server/HttpServer.kt"))
            .filter { it.isFile }

        val table = DESTRUCTIVE_ENDPOINTS.map { Triple(it.file, it.method, it.path) }.toSet()
        val unclassified = mutableListOf<String>()
        val seenExempt = mutableSetOf<Triple<String, String, String>>()

        for (f in files) {
            // 去掉块注释：`OwnerGate.kt` 的 KDoc 里就有一段 `post("/factory-reset")` 示例，
            // 不剥掉会被当成真路由。
            val code = f.readText()
                .replace(Regex("""/\*[\s\S]*?\*/"""), "")
                .lineSequence()
                .joinToString("\n") { line -> line.substringBefore("//") }

            for (m in Regex("""\b(post|put|patch|delete)\s*\(\s*"([^"]*)"""").findAll(code)) {
                val path = m.groupValues[2]
                if (sensitiveFamilies.none { it in path }) continue
                val key = Triple(f.name, m.groupValues[1], path)
                if (key in table) continue
                if (exempt.containsKey(key)) {
                    seenExempt += key
                    continue
                }
                unclassified += "${f.name}  ${m.groupValues[1]}  \"$path\""
            }
        }

        assertTrue(
            "以下端点落在破坏性能力族里，却既没登记进 DESTRUCTIVE_ENDPOINTS、也没在 EXEMPT 里写明理由：\n" +
                unclassified.distinct().joinToString("\n") { "  - $it" } +
                "\n（登记：在表里加一行 + handler 里那道门旁写 `// [owner] <anchor>`；" +
                "可撤销：在 DestructiveRouteGuardTest.EXEMPT 里加一条并写清理由）",
            unclassified.isEmpty()
        )

        // 反向：EXEMPT 里的条目必须仍然存在，否则它就成了"过期豁免"，掩盖将来同名的新端点
        val stale = exempt.keys - seenExempt
        assertTrue(
            "EXEMPT 里有已经不存在（或已改名）的条目，请清理，否则会掩盖同名新端点：\n" +
                stale.joinToString("\n") { "  - $it" },
            stale.isEmpty()
        )
    }
}
